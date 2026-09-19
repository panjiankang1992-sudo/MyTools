#!/usr/bin/env python3
"""验收真实图生图、视觉反推、文生图回归及用户隔离。"""
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import sys
import time

spec = importlib.util.spec_from_file_location('extension', Path(__file__).with_name('deploy_image_extension.py'))
e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e)
OTHER = e.OWNER + 1


def wait(job):
    started = time.monotonic()
    previous = None
    while time.monotonic() - started < 900:
        job = e.image('/jobs/' + job['id'])
        if previous != job['status']:
            e.emit({'jobId': job['id'], 'mode': job['request']['mode'], 'status': job['status'], 'errorCode': job['errorCode']})
            previous = job['status']
        if job['status'] in ['SUCCEEDED', 'FAILED', 'CANCELLED', 'PARTIAL_SUCCESS', 'UNCONFIRMED']:
            assert job['status'] == 'SUCCEEDED', 'job_failed_' + job['id']
            return job
        time.sleep(3)
    raise RuntimeError('job_timeout_' + job['id'])


def main():
    models = e.image('/models')
    assert 'IMAGE_TO_IMAGE' in next(m for m in models if m['id'] == 'krea2-local')['modes']
    assert next(m for m in models if m['id'] == 'vision-local')['status'] == 'READY'
    raw = (e.EVAL / 'source.png').read_bytes()
    reference = e.image('/uploads', 'POST', {'base64': base64.b64encode(raw).decode()})['id']
    e.image('/uploads/' + reference, owner=OTHER, expected=404)
    body = {'resourceId': 'krea2-local', 'mode': 'IMAGE_TO_IMAGE',
            'prompt': 'Keep the same alpine lake, snowy mountains, rocks and composition. Render the entire scene as a colorful watercolor painting with soft blue and lavender washes, loose brush strokes and visible paper texture.',
            'references': [reference], 'size': '1216x832', 'count': 1, 'seed': 812, 'idempotencyKey': e.NAME + '-edit'}
    invalid = dict(body, references=[], idempotencyKey=e.NAME + '-invalid')
    e.image('/jobs', 'POST', invalid, expected=400)
    e.image('/jobs', 'POST', body, owner=OTHER, expected=404)
    created = e.image('/jobs', 'POST', body)
    assert e.image('/jobs', 'POST', body)['id'] == created['id']
    e.image('/jobs', 'POST', dict(body, prompt='Changed prompt'), expected=409)
    e.image('/jobs/' + created['id'], owner=OTHER, expected=404)
    edited = wait(created)
    image = e.image('/jobs/' + edited['id'] + '/images/0')
    assert image[:8] == b'\x89PNG\r\n\x1a\n' and struct.unpack('>II', image[16:24]) == (1216, 832)
    assert image != raw and edited['result']['images'][0]['assetId']
    e.image('/jobs/' + edited['id'] + '/images/0', owner=OTHER, expected=404)
    e.d.write(e.EVAL / 'accepted-edit.png', image, user='mytools', mode=0o640)
    prompt_body = dict(body, resourceId='vision-local', mode='IMAGE_TO_PROMPT', size='1024x1024',
                       prompt='Describe the image as a generation prompt.', idempotencyKey=e.NAME + '-prompt')
    prompt = wait(e.image('/jobs', 'POST', prompt_body))
    text = prompt['result'].get('prompt', '')
    assert 1 <= len(text) <= 4000 and not prompt['result'].get('images'), 'invalid_prompt_result'
    assert e.image('/jobs', 'POST', prompt_body)['id'] == prompt['id']
    e.image('/jobs/' + prompt['id'], owner=OTHER, expected=404)
    regenerated = wait(e.image('/jobs', 'POST', dict(body, mode='TEXT_TO_IMAGE', references=[], prompt=text, idempotencyKey=e.NAME + '-regenerate')))
    regenerated_raw = e.image('/jobs/' + regenerated['id'] + '/images/0')
    assert regenerated_raw[:8] == b'\x89PNG\r\n\x1a\n'
    e.d.write(e.EVAL / 'accepted-regenerated.png', regenerated_raw, user='mytools', mode=0o640)
    cancelled = e.image('/jobs', 'POST', dict(prompt_body, idempotencyKey=e.NAME + '-cancel'))
    e.image('/jobs/' + cancelled['id'] + '/cancel', 'POST', {})
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        cancelled = e.image('/jobs/' + cancelled['id'])
        if cancelled['status'] == 'CANCELLED': break
        time.sleep(2)
    assert cancelled['status'] == 'CANCELLED', 'cancel_failed'
    e.run(['systemctl', 'restart', e.unit('image-generation-service')])
    e.d.health(23340)
    assert e.image('/jobs/' + edited['id'] + '/images/0') == image
    assert e.image('/jobs/' + prompt['id'])['result']['prompt'] == text
    history = e.image('/works?page=0')
    assert all(any(j['id'] == expected['id'] for j in history) for expected in [edited, prompt, regenerated])
    report = {'release': e.NAME, 'jobs': [{'id': j['id'], 'mode': j['request']['mode'], 'status': j['status'], 'elapsedMillis': j['elapsedMillis']} for j in [edited, prompt, regenerated]],
              'prompt': text, 'editSha256': hashlib.sha256(image).hexdigest(), 'regeneratedSha256': hashlib.sha256(regenerated_raw).hexdigest(),
              'editAssetId': edited['result']['images'][0]['assetId'], 'ownerIsolation': True, 'idempotency': True,
              'idempotencyConflict': True, 'emptyReferenceRejected': True, 'cancelled': True, 'restartPersistence': True, 'historyReadable': True}
    e.d.write(e.STATE / 'acceptance.json', json.dumps(report, indent=2))
    e.emit(report)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        e.emit({'failed': True, 'type': type(error).__name__, 'reason': str(error) if isinstance(error, (RuntimeError, AssertionError)) else 'private_diagnostic_suppressed'})
        raise SystemExit(1)
