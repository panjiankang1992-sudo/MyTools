"""每日发布后比对公开订阅与本期快照，输出一条可与客户端日志对时的结果。"""
import argparse
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
import re
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

from check_public_rss import CLIENTS, request, validate_response


def audit(config, now, result):
    """保留失败的 HTTP 状态和字节数，并检出公网及本地同时停留在旧版的情况。"""
    if not config['enabled'] or config['feedFormat'] != 'articles':
        result['status'] = 'disabled'
        return
    url = config['baseUrl'].rstrip('/') + '/rss/outlook.xml'
    origin = urlsplit(url)
    if origin.scheme != 'https' or not origin.hostname or origin.username or origin.password or origin.query or origin.fragment:
        raise ValueError('RSS_PUBLIC_HTTPS_URL_REQUIRED')
    current = Path(config['directory']) / 'current'
    site = current.resolve(strict=True)
    source = (site / 'rss/outlook.xml').read_bytes()
    expected = sha256(source).hexdigest()
    day = now.astimezone(ZoneInfo('Asia/Shanghai')).strftime('%Y/%m/%d')
    result.update(release=site.parent.name, expectedBytes=len(source), expectedSha256=expected, editionDay=day)
    # 与真实 Outlook 日志区分检测标识，每次发布仅执行一次公网 GET。
    transport, body, headers = request(url, 'edition', CLIENTS['outlook'], True, 30)
    result['transport'] = transport
    if 'error' in transport:
        raise ValueError(transport['error'])
    if current.resolve(strict=True) != site:
        raise ValueError('RSS_RELEASE_CHANGED_DURING_CHECK')
    result.update(validate_response(transport['status'], body, headers, expected, plain_response=True))
    titles = ET.fromstring(body).findall('channel/item/title')
    pattern = re.compile(r'^\u3010[^\u3011]+\u3011\u3010' + re.escape(day) + r'\u3011')
    result['todayItems'] = sum(bool(pattern.match(title.text or '')) for title in titles)
    if not result['todayItems']:
        raise ValueError('RSS_CURRENT_EDITION_MISSING')
    result['status'] = 'ok'


def main():
    """检查结果独立写入任务日志，供复发时核对当期快照及公网请求。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('config', type=Path)
    args = parser.parse_args()
    now = datetime.now(timezone.utc)
    result = {'event': 'outlook_edition_audit', 'checkedAt': now.isoformat()}
    try:
        audit(json.loads(args.config.read_text()), now, result)
    except (OSError, KeyError, ValueError, ET.ParseError) as error:
        code = str(error) if isinstance(error, ValueError) and str(error).startswith('RSS_') else type(error).__name__
        result.update(status='failed', error=code)
    print(json.dumps(result), flush=True)
    return int(result['status'] == 'failed')


if __name__ == '__main__':
    raise SystemExit(main())
