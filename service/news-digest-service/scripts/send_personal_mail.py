#!/usr/bin/env python3
"""验证分类图文邮件，显式发送时复用 Ubuntu MyTools 的 SMTP 配置。"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import smtplib
import ssl
import subprocess
import sys
import zipfile
from email.message import EmailMessage
from email.policy import SMTP
from email.utils import formatdate


def digest(data):
    return hashlib.sha256(data).hexdigest()


def emit(record):
    print(json.dumps(record, ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('job')
    action = parser.add_mutually_exclusive_group()
    action.add_argument('--send', action='store_true')
    action.add_argument('--status', action='store_true')
    args = parser.parse_args()
    job_path = Path(args.job).resolve()
    root = job_path.parent
    job = json.loads(job_path.read_text(encoding='utf-8'))
    job_id = job['job_id']
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,95}', job_id):
        raise ValueError('Invalid job_id')

    def read_file(name):
        relative = Path(name)
        path = (root / relative).resolve()
        if relative.is_absolute() or root not in path.parents or not path.is_file():
            raise ValueError('Invalid task file path')
        return path.read_bytes()

    recipients = job['to']
    if not isinstance(recipients, list) or not recipients or len(set(recipients)) != len(recipients):
        raise ValueError('Recipients must be a nonempty unique list')
    for address in recipients:
        if not re.fullmatch(r'[^\s<>@,;]+@[^\s<>@,;]+\.[^\s<>@,;]+', address):
            raise ValueError('Invalid recipient address')
        address.encode('ascii')
    subject = job['subject']
    if not isinstance(subject, str) or '\r' in subject or '\n' in subject:
        raise ValueError('Invalid subject')
    body = read_file(job['body_file'])
    msg = EmailMessage(policy=SMTP)
    msg['To'] = ', '.join(recipients)
    msg['Subject'] = subject
    msg['Date'] = formatdate(localtime=True)
    msg['Message-ID'] = '<' + job_id + '@mytools-mail.local>'
    msg.set_content(body.decode('utf-8'))
    html_body = read_file(job['html_file']) if job.get('html_file') else None
    inline_checks = []
    if html_body is not None:
        msg.add_alternative(html_body.decode('utf-8'), subtype='html')
        html_part = msg.get_payload()[-1]
        seen_cids = set()
        for picture in job.get('inline_images', []):
            data = read_file(picture['path'])
            cid, mime = picture['cid'], picture['mime']
            if not re.fullmatch(r'[A-Za-z0-9_.@-]{1,120}', cid) or cid in seen_cids \
                    or mime not in ('image/jpeg', 'image/png', 'image/gif') or len(data) > 2 * 1024 * 1024:
                raise ValueError('Invalid inline image')
            if digest(data) != picture['sha256'] or ('cid:' + cid) not in html_body.decode('utf-8'):
                raise ValueError('Inline image digest or reference mismatch')
            signatures = {'image/jpeg': data.startswith(b'\xff\xd8\xff'), 'image/png': data.startswith(b'\x89PNG\r\n\x1a\n'),
                          'image/gif': data.startswith((b'GIF87a', b'GIF89a'))}
            if not signatures[mime]:
                raise ValueError('Inline image media type mismatch')
            seen_cids.add(cid)
            html_part.add_related(data, maintype='image', subtype=mime.split('/')[1], cid='<' + cid + '>',
                                  filename=Path(picture['path']).name, disposition='inline')
            inline_checks.append({'path': picture['path'], 'sha256': digest(data), 'bytes': len(data), 'cid': cid})
        if set(re.findall(r'cid:([A-Za-z0-9_.@-]+)', html_body.decode('utf-8'))) != seen_cids:
            raise ValueError('Unresolved inline image reference')
    elif job.get('inline_images'):
        raise ValueError('Inline images require HTML content')
    checks = []
    for attachment in job.get('attachments', []):
        data = read_file(attachment['path'])
        sha = digest(data)
        if sha != attachment['sha256']:
            raise ValueError('Attachment SHA256 mismatch')
        name = Path(attachment['path']).name
        if name.lower().endswith('.zip'):
            import io
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                if archive.testzip() is not None:
                    raise ValueError('ZIP CRC mismatch')
        checks.append({'path': attachment['path'], 'sha256': sha, 'bytes': len(data)})
        msg.add_attachment(data, maintype='application', subtype='octet-stream', filename=name)
    fingerprint = digest(json.dumps({'job': job, 'body_sha256': digest(body), 'html_sha256': digest(html_body or b''),
                                    'inline_images': inline_checks, 'attachments': checks}, sort_keys=True).encode())
    maximum = int(job.get('max_message_bytes', 30 * 1024 * 1024))
    if maximum <= 0 or len(msg.as_bytes()) > maximum:
        raise ValueError('Message exceeds configured size limit')
    receipt = root / (job_id + '.receipt.json')
    if args.status:
        if not receipt.exists():
            emit({'status': 'prepared', 'job_id': job_id, 'fingerprint': fingerprint})
        else:
            previous = json.loads(receipt.read_text())
            if previous['fingerprint'] != fingerprint:
                raise ValueError('Existing job ID has different content')
            emit(previous)
        return
    if not args.send:
        emit({'status': 'validated', 'job_id': job_id, 'attachments': len(checks), 'inline_images': len(inline_checks),
              'mime_bytes': len(msg.as_bytes()), 'fingerprint': fingerprint})
        return

    lock = open(root / (job_id + '.lock'), 'a')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    if receipt.exists():
        previous = json.loads(receipt.read_text())
        if previous['fingerprint'] != fingerprint:
            raise ValueError('Existing job ID has different content')
        if previous['status'] == 'accepted':
            emit({'status': 'skipped_already_accepted', 'job_id': job_id})
            return
        if previous['status'] in ('inflight', 'unknown'):
            raise ValueError('Ambiguous prior delivery; reconcile before retry')

    record = {'job_id': job_id, 'fingerprint': fingerprint, 'message_id': str(msg['Message-ID'])}

    def save(status, **extra):
        record.update(status=status, **extra)
        temporary = receipt.with_suffix('.tmp')
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'w') as stream:
            json.dump(record, stream, ensure_ascii=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, receipt)
        emit(record)

    client = None
    data_started = False
    try:
        unit = job.get('service', 'mytools-messaging-service.service')
        if not re.fullmatch(r'[A-Za-z0-9_.@-]+\.service', unit):
            raise ValueError('Invalid service unit')
        pid = subprocess.check_output(['systemctl', 'show', unit, '-p', 'MainPID', '--value'], stderr=subprocess.DEVNULL, text=True).strip()
        if not pid.isdigit() or int(pid) <= 0:
            raise ValueError('Service is not running')
        environ = dict(item.split(b'=', 1) for item in Path('/proc', pid, 'environ').read_bytes().split(b'\0') if b'=' in item)

        def config(key, default=''):
            return environ.get(('MESSAGING_' + key).encode(), default.encode()).decode()

        host, sender = config('SMTP_HOST'), config('MAIL_FROM')
        if not host or not sender or '\n' in sender or '\r' in sender:
            raise ValueError('Missing SMTP configuration')
        msg['From'] = sender
        raw = msg.as_bytes()
        if len(raw) > maximum:
            raise ValueError('Message exceeds configured size limit')
        use_ssl = config('SMTP_SSL', 'true').lower() == 'true'
        port = int(config('SMTP_PORT', '465' if use_ssl else '587'))
        if use_ssl:
            client = smtplib.SMTP_SSL(host, port, timeout=120, context=ssl.create_default_context())
        else:
            client = smtplib.SMTP(host, port, timeout=120)
            client.ehlo()
            client.starttls(context=ssl.create_default_context())
        client.ehlo()
        server_limit = int(client.esmtp_features.get('size', '0').split()[0])
        if server_limit and len(raw) > server_limit:
            raise ValueError('Message exceeds server size limit')
        if config('SMTP_AUTH', 'true').lower() == 'true':
            client.login(config('SMTP_USERNAME'), config('SMTP_PASSWORD'))
        options = ['SIZE=' + str(len(raw))] if client.has_extn('size') else []
        code, _ = client.mail(sender, options=options)
        if code != 250:
            raise smtplib.SMTPResponseException(code, b'MAIL rejected')
        for address in recipients:
            code, _ = client.rcpt(address)
            if code not in (250, 251):
                raise smtplib.SMTPResponseException(code, b'RCPT rejected')
        save('inflight', bytes=len(raw))
        data_started = True
        code, _ = client.data(raw)
        save('accepted' if code == 250 else 'failed', smtp_code=code)
        return 0 if code == 250 else 1
    except Exception as error:
        if record.get('status') == 'accepted':
            return 0
        # DATA 明确拒绝与连接中断分别处理，后者不能自动重发。
        ambiguous = data_started and not isinstance(error, smtplib.SMTPResponseException)
        save('unknown' if ambiguous else 'failed', error_type=type(error).__name__, smtp_code=getattr(error, 'smtp_code', None))
        return 1
    finally:
        if client:
            try:
                client.quit()
            except Exception:
                pass
        lock.close()


if __name__ == '__main__':
    try:
        sys.exit(main() or 0)
    except Exception as error:
        # 不输出进程环境、凭据或 SMTP 原始错误内容。
        emit({'status': 'error', 'error_type': type(error).__name__})
        sys.exit(1)
