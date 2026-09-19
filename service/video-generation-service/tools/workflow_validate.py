"""用固定运行时的 /object_info 预检工作流图，避免在 GPU 窗口内才发现节点或输入错误。

预检只检查结构、必填输入与**输入文件可解码性**，不能替代真实推理：类型不匹配、模型不存在等
问题仍由 ComfyUI 在提交时判定。

输入文件必须实际解码一次：只检查"文件存在且非空"抓不到被截断的图片——传输中断会留下一个
尺寸正常但缺数据块的文件，ComfyUI 仍可能给出成功结果，从而让整轮实验建立在坏素材上。
"""

import hashlib
from pathlib import Path

# 这两类节点直接引用输入目录中的文件，需要在开窗之前逐个验证。
INPUT_NODES = {'LoadImage': ('image', 'image'), 'LoadVideo': ('file', 'video')}


def node_schema(schema, class_type):
    """返回节点定义，节点不存在时立即失败。"""
    node = schema.get(class_type)
    if not isinstance(node, dict) or 'input' not in node:
        raise ValueError('VIDEO_NODE_UNKNOWN:' + str(class_type))
    return node


def required_inputs(schema, class_type):
    """返回该节点的必填输入名集合。"""
    return set(node_schema(schema, class_type)['input'].get('required', {}))


def is_link(value):
    """判断输入值是否为节点连线 [node_id, slot]。"""
    return (isinstance(value, list) and len(value) == 2
            and isinstance(value[0], str) and isinstance(value[1], int))


def validate(graph, schema):
    """校验节点存在、必填输入齐全、以及所有连线都指向图中已有的节点。"""
    if not isinstance(graph, dict) or not graph:
        raise ValueError('VIDEO_GRAPH_EMPTY')
    for node_id, node in graph.items():
        if not isinstance(node, dict) or 'class_type' not in node:
            raise ValueError('VIDEO_NODE_INVALID:' + str(node_id))
        class_type = node['class_type']
        inputs = node.get('inputs')
        if not isinstance(inputs, dict):
            raise ValueError('VIDEO_NODE_INPUT_INVALID:' + class_type)
        missing = required_inputs(schema, class_type) - set(inputs)
        if missing:
            raise ValueError('VIDEO_NODE_INPUT_MISSING:' + class_type + ':' + ','.join(sorted(missing)))
        for name, value in inputs.items():
            if is_link(value) and value[0] not in graph:
                raise ValueError('VIDEO_LINK_DANGLING:' + class_type + '.' + name + ':' + value[0])
    return {'nodes': len(graph), 'classTypes': sorted({node['class_type'] for node in graph.values()})}


def input_files(graph):
    """列出图中引用的输入文件名及其类别。"""
    found = []
    for node in graph.values():
        if not isinstance(node, dict):
            continue
        specification = INPUT_NODES.get(node.get('class_type'))
        if specification is None:
            continue
        field, kind = specification
        value = (node.get('inputs') or {}).get(field)
        if isinstance(value, str):
            found.append({'kind': kind, 'file': value})
    return found


def resolve(folder, name):
    """把输入名解析到输入目录内的真实文件，拒绝越界路径。"""
    relative = Path(name)
    if relative.is_absolute() or '..' in relative.parts:
        raise ValueError('VIDEO_INPUT_PATH_INVALID:' + name)
    root = Path(folder).resolve()
    path = (root / relative).resolve()
    if not path.is_relative_to(root):
        raise ValueError('VIDEO_INPUT_PATH_INVALID:' + name)
    return path


def sha256(path):
    """计算文件哈希，随证据归档。"""
    result = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def validate_input_files(graph, folder, probe):
    """确认每个输入文件存在、非空且能真正解码一次，并返回可归档的清单。

    :param probe: 接受 (路径, 类别) 并返回布尔值的可注入探测器，便于脱离 ffmpeg 测试
    """
    records = []
    for item in input_files(graph):
        path = resolve(folder, item['file'])
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError('VIDEO_INPUT_MISSING:' + item['file'])
        if not probe(path, item['kind']):
            raise ValueError('VIDEO_INPUT_UNDECODABLE:' + item['file'])
        records.append({**item, 'bytes': path.stat().st_size, 'sha256': sha256(path), 'decodable': True})
    # 纯文生工作流没有任何输入文件，这是合法情况；返回空清单即可。
    return records
