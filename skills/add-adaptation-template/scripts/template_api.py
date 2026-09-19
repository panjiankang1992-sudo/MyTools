#!/usr/bin/env python3
"""通过私有文件令牌调用模板管理 API，不打印凭据或错误正文。"""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """拒绝跨地址重定向，避免发送管理令牌到其他主机。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def origin(value):
    """仅接受明确来源地址，不允许任意路径、用户信息或查询。"""
    parsed = urllib.parse.urlsplit(value)
    if not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('', '/'):
        raise ValueError('INVALID_ORIGIN')
    try:
        loopback = ipaddress.ip_address(parsed.hostname).is_loopback
    except ValueError:
        loopback = parsed.hostname == 'localhost'
    if parsed.scheme != 'https' and not (parsed.scheme == 'http' and loopback):
        raise ValueError('HTTPS_OR_LOOPBACK_REQUIRED')
    return value.rstrip('/')


def request_file(path):
    """固定请求字段和乐观版本，不生成新的重试键。"""
    raw = Path(path).read_bytes()
    if len(raw) > 65536:
        raise ValueError('REQUEST_TOO_LARGE')
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('DUPLICATE_FIELD')
            result[key] = value
        return result
    body = json.loads(raw, object_pairs_hook=unique)
    if not isinstance(body, dict) or set(body) != {'idempotencyKey', 'code', 'expectedLatestVersion', 'name', 'description', 'prompt'}:
        raise ValueError('INVALID_FIELDS')
    if not isinstance(body['code'], str) or not re.fullmatch(r'[a-z][a-z0-9-]{0,63}', body['code']):
        raise ValueError('INVALID_CODE')
    if not isinstance(body['idempotencyKey'], str) or not re.fullmatch(r'[!-~]{1,128}', body['idempotencyKey']):
        raise ValueError('INVALID_KEY')
    if type(body['expectedLatestVersion']) is not int or not 0 <= body['expectedLatestVersion'] < 1000000:
        raise ValueError('INVALID_VERSION')
    for field, minimum, maximum in [('name', 1, 80), ('description', 1, 500), ('prompt', 5, 6000)]:
        text = body[field]
        if not isinstance(text, str) or not minimum <= len(text) <= maximum or not text.strip() or any(
                0xD800 <= ord(char) <= 0xDFFF or (ord(char) < 32 and char not in '\n\r\t') or 127 <= ord(char) <= 159 for char in text):
            raise ValueError('INVALID_TEXT')
    return body


def call(base_url, token_file, body=None):
    """只发送一次请求，结果未知时由操作者用同一请求文件重试。"""
    url = origin(base_url) + '/api/v1/reader-admin/adaptation-style-templates'
    path = Path(token_file)
    info = path.stat()
    if not stat.S_ISREG(info.st_mode) or info.st_size > 4096 or (os.name == 'posix' and info.st_mode & 0o077):
        raise ValueError('PRIVATE_TOKEN_FILE_REQUIRED')
    token = path.read_text(encoding='utf-8').strip()
    if not 32 <= len(token) <= 4096 or any(ord(char) < 33 or ord(char) > 126 for char in token):
        raise ValueError('INVALID_TOKEN_FILE')
    payload = None if body is None else json.dumps(body, ensure_ascii=True, separators=(',', ':')).encode('utf-8')
    request = urllib.request.Request(url, data=payload, headers={'Authorization': 'Bearer ' + token,
        'Content-Type': 'application/json', 'Accept': 'application/json'}, method='GET' if body is None else 'POST')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(request, timeout=20) as response:
            if response.headers.get_content_type() != 'application/json':
                raise ValueError('INVALID_RESPONSE_TYPE')
            raw = response.read(2097153)
            if len(raw) > 2097152:
                raise ValueError('RESPONSE_TOO_LARGE')
            result = json.loads(raw)
    except urllib.error.HTTPError as error:
        raise ValueError('HTTP_' + str(error.code)) from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise ValueError('NETWORK_RESULT_UNKNOWN_RETRY_SAME_FILE') from None
    items = result.get('items') if body is None and isinstance(result, dict) else [result]
    if not isinstance(items, list) or any(not isinstance(item, dict) or set(item) != {'code', 'version', 'name', 'description', 'promptSha256'} for item in items):
        raise ValueError('INVALID_RESPONSE')
    if body is not None and (result['code'] != body['code'] or result['version'] != body['expectedLatestVersion'] + 1 or
            result['promptSha256'] != hashlib.sha256(body['prompt'].encode('utf-8')).hexdigest()):
        raise ValueError('RECEIPT_MISMATCH')
    return result


def main():
    """输出白名单模板摘要，异常只输出固定错误代号。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=['list', 'publish'])
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--token-file', required=True)
    parser.add_argument('--request-file')
    args = parser.parse_args()
    try:
        if (args.operation == 'publish') != bool(args.request_file):
            raise ValueError('REQUEST_FILE_REQUIRED_ONLY_FOR_PUBLISH')
        body = request_file(args.request_file) if args.request_file else None
        print(json.dumps(call(args.base_url, args.token_file, body), ensure_ascii=False))
        return 0
    except ValueError as error:
        code = str(error)
        print(code if re.fullmatch('[A-Z0-9_]+', code) else 'INVALID_DATA', file=sys.stderr)
        return 1
    except (OSError, UnicodeError):
        print('LOCAL_FILE_UNAVAILABLE', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
