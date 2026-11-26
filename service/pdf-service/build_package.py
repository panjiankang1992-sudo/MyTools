#!/usr/bin/env python3
"""在独立暂存目录构建含固定内核摘要的不可变 PDF 工作包。"""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess


def main() -> None:
    """先运行内核测试，再复制发布材料；不向源码包写二进制。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--destination', type=Path, required=True)
    parser.add_argument('--prepare-source', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    target = args.destination.resolve() / 'pdf_process' / '1.0.1'
    source_tree = root / 'packages/pdf_process/1.0.1'
    preparing = args.prepare_source and target == source_tree
    if target.exists() and not preparing:
        raise SystemExit('Refusing to overwrite immutable package')
    subprocess.run(['mvn', '-q', '-f', str(root.parent / 'pdf-worker/pom.xml'), 'package'], check=True)
    if not preparing:
        shutil.copytree(source_tree, target, ignore=shutil.ignore_patterns('__pycache__', '*.pyc', 'tests', 'lib'))
    lib = target / 'lib'
    lib.mkdir(exist_ok=preparing)
    source = root.parent / 'pdf-worker/target/pdf-worker-1.0.0.jar'
    shutil.copy2(source, lib / 'pdf-worker.jar')
    (lib / 'pdf-worker.sha256').write_text(hashlib.sha256(source.read_bytes()).hexdigest() + '\n')
    print(target)


if __name__ == '__main__':
    main()
