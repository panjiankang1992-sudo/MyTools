#!/usr/bin/env python3
"""离线风格目录与提示词合成工具，不访问账户或创建生成任务。"""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def load_styles(path):
    """读取有界配置，并拒绝不受支持的模板与负向参数。"""
    if path.stat().st_size > 1_000_000:
        raise ValueError('Style pack exceeds 1 MB')
    data = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(data, dict) or data.get('schemaVersion') != 1:
        raise ValueError('Unsupported schemaVersion')
    rows = data.get('styles')
    if not isinstance(rows, list) or not 1 <= len(rows) <= 200:
        raise ValueError('Expected 1..200 styles')
    result = {}
    for row in rows:
        # 仅做字面替换，不执行模板表达式或脚本。
        if not isinstance(row, dict):
            raise ValueError('Invalid style object')
        key, template = row.get('id'), row.get('promptTemplate')
        if not isinstance(key, str) or not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,63}', key):
            raise ValueError('Invalid style id')
        if key in result:
            raise ValueError('Duplicate style id: ' + key)
        if not isinstance(template, str) or not 1 <= len(template) <= 2000:
            raise ValueError('Template must contain 1..2000 characters')
        rest = template.replace('{prompt}', '')
        if template.count('{prompt}') != 1 or '{' in rest or '}' in rest:
            raise ValueError('Template must contain exactly one literal {prompt} placeholder')
        if row.get('kind') != 'PROMPT' or row.get('negativePrompt', '') != '':
            raise ValueError('Current workflow supports positive PROMPT styles only')
        if not isinstance(row.get('name'), str) or not 1 <= len(row['name']) <= 80:
            raise ValueError('Style name must contain 1..80 characters')
        if type(row.get('version')) is not int or row['version'] < 1:
            raise ValueError('Style version must be a positive integer')
        if row.get('compatibleModels') != ['krea2-local'] or row.get('modes') != ['TEXT_TO_IMAGE', 'IMAGE_TO_IMAGE']:
            raise ValueError('Unsupported model or modes')
        result[key] = row
    return result


def main():
    """列出风格或将选定风格与主体描述合成可粘贴文本。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--custom', type=Path)
    parser.add_argument('--list', action='store_true')
    parser.add_argument('--style')
    parser.add_argument('--prompt')
    parser.add_argument('--prompt-file', type=Path)
    args = parser.parse_args()
    try:
        styles = load_styles(ROOT / 'builtin-styles.json')
        if args.custom:
            custom = load_styles(args.custom)
            if set(styles) & set(custom):
                raise ValueError('Custom style ids must not override built-in ids')
            styles.update(custom)
        if args.list:
            for key, row in styles.items():
                print(key + '\t' + row['name'])
            return
        if args.style not in styles:
            raise ValueError('Select a style id from --list')
        if (args.prompt is None) == (args.prompt_file is None):
            raise ValueError('Provide exactly one of --prompt or --prompt-file')
        if args.prompt_file and args.prompt_file.stat().st_size > 32_000:
            raise ValueError('Prompt file exceeds 32 KB')
        prompt = args.prompt_file.read_text(encoding='utf-8') if args.prompt_file else args.prompt
        if not prompt.strip():
            raise ValueError('Prompt cannot be blank')
        output = styles[args.style]['promptTemplate'].replace('{prompt}', prompt.strip())
        # 对齐 App 的 UTF-16 字符计数，避免表情等字符超出接口长度限制。
        if len(output.encode('utf-16-le')) // 2 > 4000:
            raise ValueError('Combined prompt exceeds 4000 UTF-16 code units')
        print(output)
    except (ValueError, OSError, UnicodeError) as error:
        parser.exit(2, str(error) + '\n')


if __name__ == '__main__':
    main()
