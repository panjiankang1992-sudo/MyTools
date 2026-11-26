#!/usr/bin/env python3
"""从公网检查 Outlook RSS 的不同客户端、压缩与条件请求，不修改订阅或站点。"""
import argparse
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET

CLIENTS = {
    'outlook': 'Mozilla/5.0 (compatible; MSIE 10.0; Windows NT 10.0; Win64; x64; Trident/7.0; Microsoft Outlook 16.0.10417; ms-office; MSOffice 16) MyTools-Check/1.0',
    'windows-rss': 'Windows-RSS-Platform/2.0 (MSIE 11.0; Windows NT 10.0) MyTools-Check/1.0',
}
HEADER_NAMES = {'content-type', 'content-encoding', 'etag', 'last-modified',
                'cache-control', 'cf-cache-status', 'cf-ray', 'age', 'content-length', 'transfer-encoding'}


def validate_response(status, body, headers, expected_hash=None, conditional=False, plain_response=False):
    """HTTP 成功不等于 RSS 可用；检查完整 XML、文章身份和正文，拒绝拦截页。"""
    if status == 304 and conditional and not plain_response:
        if body:
            raise ValueError('RSS_304_BODY_PRESENT')
        return {'notModified': True}
    if status != 200:
        raise ValueError('RSS_HTTP_' + str(status))
    if 'application/rss+xml' not in headers.get('content-type', '').lower():
        raise ValueError('RSS_CONTENT_TYPE_INVALID')
    if plain_response:
        # 原订阅即使带旧验证器也必须返回完整原文，与实机成功的诊断源一致。
        directives = {part.strip().lower() for part in headers.get('cache-control', '').split(',')}
        if not {'no-store', 'no-transform'} <= directives or headers.get('etag') \
                or headers.get('content-encoding') or headers.get('transfer-encoding') \
                or headers.get('content-length') != str(len(body)):
            raise ValueError('RSS_PLAIN_RESPONSE_INVALID')
    if len(body) > 512 * 1024 or b'<!DOCTYPE' in body.upper():
        raise ValueError('RSS_BODY_INVALID')
    try:
        root = ET.fromstring(body)
    except ET.ParseError as error:
        raise ValueError('RSS_XML_INVALID') from error
    items = root.findall('channel/item')
    if root.tag != 'rss' or root.get('version') != '2.0' or not 1 <= len(items) <= 100:
        raise ValueError('RSS_ITEMS_INVALID')
    guids = [item.findtext('guid', '') for item in items]
    if not all(guids) or len(guids) != len(set(guids)):
        raise ValueError('RSS_GUID_INVALID')
    if any(not all(item.findtext(key, '').strip() for key in ('title', 'link', 'description', 'pubDate'))
           for item in items):
        raise ValueError('RSS_DETAILS_MISSING')
    digest = hashlib.sha256(body).hexdigest()
    # 各客户端必须拿到相同公开快照，避免只检验浏览器请求掩盖 RSS 客户端失败。
    if expected_hash and digest != expected_hash:
        raise ValueError('RSS_SNAPSHOT_MISMATCH')
    return {'bytes': len(body), 'items': len(items), 'sha256': digest,
            'images': sum('<img' in item.findtext('description', '') for item in items)}


def request(url, name, agent, compressed, timeout, etag=None, maximum=512 * 1024, modified_since=None):
    """使用无凭据的独立请求复测，不自动重试而掩盖间歇性错误。"""
    with tempfile.TemporaryDirectory(prefix='rss-check-') as directory:
        root = Path(directory)
        command = ['curl', '-q', '--http1.1', '--silent', '--show-error', '--connect-timeout', '10',
                   '--max-time', str(timeout), '--max-filesize', str(maximum),
                   '--user-agent', agent, '--dump-header', str(root / 'headers'),
                   '--output', str(root / 'body'), '--write-out', '%{http_code} %{size_download} %{time_total}']
        if compressed:
            command.append('--compressed')
        if etag:
            command.extend(['--header', 'If-None-Match: ' + etag])
        if modified_since:
            command.extend(['--header', 'If-Modified-Since: ' + modified_since])
        command.append(url)
        result = {'name': name}
        try:
            completed = subprocess.run(command, capture_output=True, text=True, timeout=timeout + 5, check=False)
            status, size, seconds = completed.stdout.strip().split()
            result.update(status=int(status), wireBytes=int(size), seconds=float(seconds), curlExit=completed.returncode)
        except (OSError, ValueError, subprocess.TimeoutExpired):
            return dict(result, error='RSS_TRANSPORT_FAILED'), b'', {}
        # 只记录诊断所需的公开响应头，不输出 Cookie、代理信息或客户端地址。
        headers = {}
        if (root / 'headers').exists():
            for line in (root / 'headers').read_text(errors='replace').splitlines():
                if line.startswith('HTTP/'):
                    headers = {}
                elif ':' in line:
                    key, value = line.split(':', 1)
                    if key.lower() in HEADER_NAMES:
                        headers[key.lower()] = value.strip()
        result['headers'] = headers
        body = (root / 'body').read_bytes() if (root / 'body').exists() else b''
        if completed.returncode:
            result['error'] = 'RSS_TRANSPORT_FAILED'
        return result, body, headers


def check(url, timeout, expected_hash=None, plain_response=False):
    """每种客户端检查完整响应、压缩响应以及成功下载后的缓存更新。"""
    results = []
    sample = None
    for client, agent in CLIENTS.items():
        validator = None
        for compressed in (False, True):
            result, body, headers = request(url, client + ('-gzip' if compressed else '-identity'),
                                            agent, compressed, timeout)
            if 'error' not in result:
                try:
                    result.update(validate_response(result['status'], body, headers, expected_hash,
                                                    plain_response=plain_response))
                    sample = body
                    expected_hash = expected_hash or result['sha256']
                    validator = headers.get('etag')
                except ValueError as error:
                    result['error'] = str(error)
            results.append(result)
        # 普通模式只使用成功下载所得验证器；完整响应模式主动模拟旧缓存与未来时间。
        if validator or plain_response:
            probes = [('conditional', validator or '"mytools-rss-previous-response"', {})]
            if plain_response:
                # 两种验证器分开测试，避免 ETag 优先级掩盖日期条件仍返回 304。
                future = format_datetime(datetime.now(timezone.utc) + timedelta(days=1), usegmt=True)
                probes.append(('modified-since', None, {'modified_since': future}))
            for name, etag, extra in probes:
                result, body, headers = request(url, client + '-' + name, agent, True, timeout, etag, **extra)
                if 'error' not in result:
                    try:
                        result.update(validate_response(result['status'], body, headers, expected_hash, conditional=True,
                                                        plain_response=plain_response))
                    except ValueError as error:
                        result['error'] = str(error)
                results.append(result)
    feed_ok = len(results) == (4 if plain_response else 3) * len(CLIENTS) and all('error' not in result for result in results)
    if sample:
        results.extend(check_resources(url, sample, timeout))
    return {'checkedAt': datetime.now(timezone.utc).isoformat(), 'url': url,
            'ok': feed_ok and all('error' not in result for result in results), 'checks': results}


def check_resources(feed_url, body, timeout):
    """图文资源也须允许 RSS 客户端读取；只抽查同站公开文章和图片，不请求外部新闻网站。"""
    items = ET.fromstring(body).findall('channel/item')
    article = items[0].findtext('link', '')
    images = re.findall(r'<img\b[^>]*\bsrc="([^"]+)"', ''.join(item.findtext('description', '') for item in items))
    targets = [('article', article)] + ([('image', images[0])] if images else [])
    origin = urlsplit(feed_url)
    base_path = origin.path.rsplit('/rss/', 1)[0]
    results = []
    for kind, url in targets:
        parsed = urlsplit(url)
        prefix = base_path + ('/articles/' if kind == 'article' else '/assets/')
        # 避免远端 XML 把验收探测引向凭据地址、其他主机或非新闻资源。
        if parsed.scheme != 'https' or parsed.netloc != origin.netloc or parsed.query or parsed.fragment \
                or not parsed.path.startswith(prefix):
            results.append({'name': kind, 'error': 'RSS_RESOURCE_URL_INVALID'})
            continue
        result, payload, headers = request(url, kind, CLIENTS['windows-rss'], True, timeout, maximum=4 * 1024 * 1024)
        if 'error' not in result:
            mime = headers.get('content-type', '').split(';', 1)[0].strip().lower()
            if result['status'] != 200:
                result['error'] = 'RSS_RESOURCE_HTTP_' + str(result['status'])
            elif not payload or (kind == 'article' and mime != 'text/html') \
                    or (kind == 'image' and mime not in ('image/jpeg', 'image/png', 'image/gif')):
                result['error'] = 'RSS_RESOURCE_CONTENT_INVALID'
            else:
                result['bytes'] = len(payload)
        results.append(result)
    return results


def main():
    """输出 JSON 和非零失败退出码，供部署验收及手动排查复用。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='https://toolbox.yuyutian.top/news/rss/outlook.xml')
    parser.add_argument('--timeout', type=int, default=30)
    parser.add_argument('--expected-sha256')
    parser.add_argument('--plain-response', action='store_true', help='Require complete uncached responses even for conditional requests')
    args = parser.parse_args()
    parsed = urlsplit(args.url)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        parser.error('RSS_PUBLIC_HTTPS_URL_REQUIRED')
    if not 1 <= args.timeout <= 60 or (args.expected_sha256 and not re.fullmatch('[0-9a-f]{64}', args.expected_sha256)):
        parser.error('RSS_ARGUMENT_INVALID')
    result = check(args.url, args.timeout, args.expected_sha256, plain_response=args.plain_response)
    print(json.dumps(result, indent=2))
    return 0 if result['ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
