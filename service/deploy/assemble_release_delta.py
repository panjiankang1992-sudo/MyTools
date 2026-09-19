#!/usr/bin/env python3
"""在目标机上把"基线 jar + 增量"重组成发布 jar，并逐成员校验。

只允许在发布目录内操作：基线 jar 必须与清单里的 SHA-256 一致，重组结果必须与清单里的
成员集合和逐个成员摘要完全一致，否则不落盘。压缩产物先写临时文件再原子替换，避免留下半成品。

用法：
    python3 assemble_release_delta.py --release /tmp/video-production-20260914-v1
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import zipfile


def sha256(path):
    """计算文件摘要。"""
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def assemble_one(entry, apps):
    """重组单个 jar 并返回校验结果。"""
    manifest = json.loads((entry / 'jar.json').read_text())
    base = Path(manifest['base'])
    if not base.is_file():
        raise SystemExit('base_missing:' + str(base))
    if sha256(base) != manifest['baseSha256']:
        raise SystemExit('base_digest_mismatch:' + str(base))
    target = apps / manifest['jar']
    temporary = target.with_name(target.name + '.tmp')
    members = manifest['members']
    expected = manifest['allMembers']
    # 目录条目以最终清单为准：基线里属于被删除部分的空目录不再保留。
    expected_dirs = {name for name in manifest['allEntries'] if name.endswith('/')}
    directories = set()
    with zipfile.ZipFile(base) as source, zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as output:
        # 目录条目必须保留：Spring Boot 的加载器靠 `BOOT-INF/classes/` 目录条目把应用类加进类路径。
        for info in source.infolist():
            if info.filename.endswith('/'):
                if info.filename not in expected_dirs:
                    continue
                directories.add(info.filename)
                output.writestr(info, b'')
                continue
            if info.filename in members or info.filename not in expected:
                continue
            output.writestr(info, source.read(info.filename))
        for name in sorted(members):
            # 为新增成员补齐父目录条目（累计前缀，逐级向上），保证目录结构与 maven 构建一致。
            segments = name.split('/')[:-1]
            for index in range(1, len(segments) + 1):
                directory = '/'.join(segments[:index]) + '/'
                if directory in expected_dirs and directory not in directories:
                    directories.add(directory)
                    output.writestr(directory, b'')
            output.writestr(name, (entry / 'members' / name).read_bytes())
    # 逐成员校验：成员集合必须完全一致，且每个成员的解压内容摘要必须匹配。
    with zipfile.ZipFile(temporary) as archive:
        actual = {name: hashlib.sha256(archive.read(name)).hexdigest()
                  for name in archive.namelist() if not name.endswith('/')}
    with zipfile.ZipFile(temporary) as archive:
        actual_entries = sorted(archive.namelist())
    if actual_entries != manifest['allEntries']:
        temporary.unlink(missing_ok=True)
        missing_entries = sorted(set(manifest['allEntries']) - set(actual_entries))
        extra_entries = sorted(set(actual_entries) - set(manifest['allEntries']))
        raise SystemExit('entry_list_mismatch missing=' + ','.join(missing_entries[:5])
                         + ' extra=' + ','.join(extra_entries[:5]))
    missing = sorted(set(expected) - set(actual))
    extra = sorted(set(actual) - set(expected))
    if missing or extra:
        temporary.unlink(missing_ok=True)
        raise SystemExit('member_set_mismatch missing=' + ','.join(missing[:3]) + ' extra=' + ','.join(extra[:3]))
    wrong = sorted(name for name, digest in expected.items() if actual[name] != digest)
    if wrong:
        temporary.unlink(missing_ok=True)
        raise SystemExit('member_digest_mismatch:' + ','.join(wrong[:3]))
    os.replace(temporary, target)
    return {'jar': manifest['jar'], 'entries': len(actual), 'verified': True, 'sha256': sha256(target),
            'base': str(base)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--release', required=True)
    arguments = parser.parse_args()
    release = Path(arguments.release)
    apps = release / 'apps'
    apps.mkdir(parents=True, exist_ok=True)
    entries = sorted(path.parent for path in (release / 'jars').glob('*/jar.json'))
    if not entries:
        raise SystemExit('no_delta_entries')
    results = [assemble_one(entry, apps) for entry in entries]
    (release / 'jar-verification.json').write_text(json.dumps(results, indent=2, sort_keys=True) + '\n')
    print(json.dumps({'assembled': len(results),
                      'jars': {item['jar']: item['sha256'] for item in results}}, separators=(',', ':')))


if __name__ == '__main__':
    main()
