"""加载并校验不可变工作流规格；客户端只能注入已被声明的字段。

规格文件与索引都由 `tools/emit_workflow_specs.py` 从已验证的构图代码生成，并在部署时把
索引摘要写进环境变量。执行器先校验索引摘要、再校验单模式规格摘要，最后校验节点类型白名单；
任何一环不匹配都拒绝运行，而不是退回默认行为。
"""

import copy
import hashlib
import json
import os
from pathlib import Path


def load_json(path, expected_sha256, error_code):
    """读取文件并校验 SHA-256；摘要不匹配或文件缺失一律拒绝。"""
    try:
        raw = Path(path).read_bytes()
    except OSError as error:
        raise ValueError(error_code) from error
    if not expected_sha256 or hashlib.sha256(raw).hexdigest() != expected_sha256:
        raise ValueError(error_code)
    return json.loads(raw)


def index():
    """读取工作流索引；索引摘要是部署期固定的唯一入口。"""
    return load_json(os.environ['VIDEO_WORKFLOW_INDEX_FILE'],
                     os.environ.get('VIDEO_WORKFLOW_INDEX_SHA256', ''), 'VIDEO_WORKFLOW_DIGEST_MISMATCH')


def spec(mode, frames):
    """按模式取规格，并校验该模式允许的帧数。"""
    entry = index()
    if int(entry.get('frames', 0)) != frames:
        raise ValueError('VIDEO_FRAMES_NOT_VALIDATED')
    item = entry.get('specs', {}).get(mode)
    if not item:
        raise ValueError('VIDEO_MODE_NOT_VALIDATED')
    folder = Path(os.environ['VIDEO_WORKFLOW_INDEX_FILE']).parent
    value = load_json(folder / item['file'], item['sha256'], 'VIDEO_WORKFLOW_DIGEST_MISMATCH')
    if value.get('mode') != mode or int(value.get('frames', 0)) != frames:
        raise ValueError('VIDEO_WORKFLOW_UNSUPPORTED')
    return value


def bind(value, values):
    """按绑定表注入字段；缺失、越界或未声明的字段都直接拒绝。"""
    missing = [name for name in value['requiredInputs'] if name not in values]
    if missing:
        raise ValueError('VIDEO_WORKFLOW_BINDING_MISSING:' + ','.join(missing))
    unexpected = sorted(set(values) - set(value['bindings']))
    if unexpected:
        raise ValueError('VIDEO_WORKFLOW_BINDING_UNEXPECTED:' + ','.join(unexpected))
    graph = copy.deepcopy(value['graph'])
    for name, item in values.items():
        for node, field in value['bindings'][name]:
            if node not in graph or field not in graph[node]['inputs']:
                raise ValueError('VIDEO_WORKFLOW_BINDING_INVALID')
            graph[node]['inputs'][field] = item
    allowed = set(value['allowedClassTypes'])
    for node in graph.values():
        if node['class_type'] not in allowed:
            raise ValueError('VIDEO_WORKFLOW_NODE_REJECTED')
    return graph


def output_node(value):
    """返回产物节点编号；缺失即视为规格损坏。"""
    node = value.get('outputNode')
    if not node or node not in value['graph']:
        raise ValueError('VIDEO_WORKFLOW_UNSUPPORTED')
    return node
