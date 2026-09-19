#!/usr/bin/env python3
"""生成"发布增量"：只保留本地 jar 相对已部署 jar 真正变化的成员。

隧道带宽很低（实测约 20KB/s），整包传输 80MB 需要一小时以上且容易中断。这里用
"已部署 jar + 可验证增量"的方式构造发布产物：增量只含变化的成员，随发布一起带上
**最终 jar 的完整成员清单与摘要**，由 `assemble_release_delta.py` 在目标机上重组并逐成员校验。

安全性来自三点：
1. 基线 jar 的 SHA-256 写在清单里，目标机重组前先校验基线没被换过；
2. 重组后逐成员比对摘要与成员集合，任何缺失、多余或内容不符都会失败；
3. 只新增/替换成员，不依赖压缩元数据，因此校验的是内容而不是字节。

用法：
    python3 build_release_delta.py --base <基线 jar> --base-path <目标机上的基线路径> \
        --jar <本地构建的 jar> --output <增量输出目录>
"""

import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def members(path):
    """返回 jar 内每个成员的解压后摘要。"""
    values = {}
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if name.endswith('/'):
                continue
            values[name] = hashlib.sha256(archive.read(name)).hexdigest()
    return values


def entries(path):
    """返回 jar 的完整条目清单（含目录条目），用于结构等价校验。"""
    with zipfile.ZipFile(path) as archive:
        return sorted(archive.namelist())


def build(base, base_path, jar, output):
    """比较两个 jar 并写出增量目录与清单。"""
    base_members = members(base)
    jar_members = members(jar)
    changed = sorted(name for name, digest in jar_members.items() if base_members.get(name) != digest)
    # 基线里有、新 jar 里没有的成员必须被删掉（例如视频包不该带图片服务的类）；
    # 重组端只保留 allMembers 里的成员，因此删除会被自动处理，这里只记录下来备查。
    removed = sorted(set(base_members) - set(jar_members))
    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(jar) as archive:
        for name in changed:
            target = output / 'members' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))
    manifest = {
        'jar': Path(jar).name,
        'base': base_path,
        'baseSha256': hashlib.sha256(Path(base).read_bytes()).hexdigest(),
        'members': {name: jar_members[name] for name in changed},
        'removed': removed,
        'allMembers': jar_members,
        'allEntries': entries(jar),
        'baseEntries': entries(base),
    }
    (output / 'jar.json').write_text(json.dumps(manifest, sort_keys=True, separators=(',', ':')) + '\n')
    return manifest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', required=True)
    parser.add_argument('--base-path', required=True)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--output', required=True)
    arguments = parser.parse_args()
    manifest = build(Path(arguments.base), arguments.base_path, Path(arguments.jar), Path(arguments.output))
    total = sum((Path(arguments.output) / 'members' / name).stat().st_size for name in manifest['members'])
    print(json.dumps({'jar': manifest['jar'], 'changedMembers': len(manifest['members']),
                      'removedMembers': len(manifest['removed']), 'totalMembers': len(manifest['allMembers']),
                      'deltaBytes': total, 'baseSha256': manifest['baseSha256']}, separators=(',', ':')))


if __name__ == '__main__':
    main()
