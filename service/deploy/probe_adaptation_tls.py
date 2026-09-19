#!/usr/bin/env python3
"""Emit only TLS verification classification for the production loopback listener."""
import json
import runpy
import urllib.request

namespace = runpy.run_path('/opt/yuyutian/mytools/runtime/adaptation-production-20260911/operator/deploy.py')
try:
    code, _ = namespace['request'](24411, '/api/internal/v1/task-execution-authorizations/jwks', tls=True)
    print(json.dumps({'status': code}))
except Exception as error:
    reason = getattr(error, 'reason', error)
    print(json.dumps({'errorType': type(error).__name__, 'reasonType': type(reason).__name__,
        'errno': getattr(reason, 'errno', None), 'verifyCode': getattr(reason, 'verify_code', None),
        'verifyMessage': getattr(reason, 'verify_message', None), 'localhostProxyBypass': urllib.request.proxy_bypass('localhost')}))
