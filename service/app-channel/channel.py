"""将客户端加密 WebSocket 通道内的 HTTP 请求转至固定的本机业务入口。"""
import asyncio
import contextlib
import os
from urllib.parse import unquote, urlsplit

import aiohttp
from aiohttp import web
import h11
from yarl import URL

ORIGIN = 'https://[::1]:8443'
INGRESS_NAME = os.environ.get('MYTOOLS_CHANNEL_HOST', '')
IDENTITY = 'http://127.0.0.1:23200/api/app/v1/identity/me'
STATE = web.AppKey('channel_state', dict)
OWNERS = web.AppKey('channel_owners', dict)
CLIENT = web.AppKey('channel_client', aiohttp.ClientSession)
BODY_LIMIT = 201 * 1024 * 1024
MAX_FRAME = 65536
WINDOW = 1024 * 1024
PROBE_PATH = '/api/app/v1/connectivity/transport-probe'
PROBE_BODY = bytes(range(256)) * 2048
REQUEST_READ_SECONDS = 45
FLUSH_ACK_SECONDS = 40
END_ACK_SECONDS = 30
IDLE_SECONDS = 120
SESSION_LIFETIME_SECONDS = 900
REQUEST_HEADERS = {'accept', 'accept-encoding', 'accept-language', 'authorization', 'content-type',
                   'if-none-match', 'if-modified-since', 'if-range', 'range', 'content-length', 'cookie'}
RESPONSE_HEADERS = {'content-type', 'content-length', 'content-range', 'accept-ranges', 'content-encoding',
                    'etag', 'cache-control', 'last-modified', 'content-disposition', 'vary', 'set-cookie'}


def checked_target(raw):
    """只允许业务路径；重复编码不能引入点段、不同来源或内部管理路径。"""
    if len(raw) > 8192 or not raw.startswith('/api/') or raw.startswith('//'):
        raise ValueError('invalid target')
    parts = urlsplit(raw)
    if parts.netloc or parts.scheme or parts.fragment:
        raise ValueError('invalid target')
    path = parts.path
    for _ in range(5):
        if ('\\' in path or '//' in path or any(ord(c) < 32 for c in path) or
                any(p in ('.', '..', 'internal', 'actuator') for p in path.lower().split('/'))):
            raise ValueError('invalid target')
        new = unquote(path)
        if new == path:
            break
        path = new
    else:
        raise ValueError('invalid encoding')
    if not path.startswith('/api/') or any(c in path for c in '?#;'):
        raise ValueError('invalid target')
    return URL(ORIGIN + raw, encoded=True)


async def next_chunk(ws, parser):
    while True:
        event = parser.next_event()
        if event is not h11.NEED_DATA:
            return event
        msg = await asyncio.wait_for(ws.receive(), REQUEST_READ_SECONDS)
        if msg.type != aiohttp.WSMsgType.BINARY or not 0 < len(msg.data) <= MAX_FRAME:
            raise ValueError('invalid request frame')
        parser.receive_data(msg.data)


async def serve_request(ws, client, state):
    """按完整 HTTP 交换累计诊断，不保留地址、路径、请求头或身份字段。"""
    metrics = state['requestMetrics']
    metrics['started'] += 1
    metrics['active'] += 1
    start = asyncio.get_running_loop().time()
    observed = {'phase': 'request', 'bytes': 0}
    try:
        await _relay_request(ws, client, state, observed)
    except BaseException as error:
        # 服务关闭或请求失败时，也须结算此前已送入通道的正文与耗时。
        metrics['failed'] += 1
        metrics['failedBodyBytes'] += observed['bytes']
        metrics['failedDurationMs'] += int((asyncio.get_running_loop().time() - start) * 1000)
        phase = observed['phase']
        if isinstance(error, asyncio.CancelledError):
            kind = 'cancelled'
        elif isinstance(error, (TimeoutError, asyncio.TimeoutError)):
            kind = {'request': 'requestTimeout', 'flushAck': 'flushTimeout',
                    'endAck': 'endTimeout'}.get(phase, 'upstreamTimeout')
        elif isinstance(error, aiohttp.ClientPayloadError):
            kind = 'upstreamPayload'
        elif isinstance(error, (ValueError, UnicodeError, h11.ProtocolError)):
            kind = {'flushAck': 'flushProtocol', 'endAck': 'endProtocol'}.get(phase, 'protocol')
        elif isinstance(error, (OSError, aiohttp.ClientError)):
            kind = 'upstreamTransport' if phase in ('upstreamConnect', 'responseBody') else 'clientTransport'
        else:
            kind = 'internal'
        state['failures'][kind] += 1
        raise
    else:
        metrics['succeeded'] += 1
        metrics['successfulBodyBytes'] += observed['bytes']
        metrics['successfulDurationMs'] += int((asyncio.get_running_loop().time() - start) * 1000)
    finally:
        metrics['active'] -= 1


async def _relay_request(ws, client, state, observed):
    parser = h11.Connection(h11.SERVER, max_incomplete_event_size=32768)
    request = await next_chunk(ws, parser)
    if not isinstance(request, h11.Request) or request.method not in (b'GET', b'HEAD', b'POST', b'PUT', b'DELETE', b'PATCH'):
        raise ValueError('invalid method')
    raw_target = request.target.decode('ascii')
    target = checked_target(raw_target)
    is_probe = target.path == PROBE_PATH
    headers = {}
    for key, value in request.headers:
        name = key.decode('ascii').lower()
        if name in REQUEST_HEADERS:
            if name in headers:
                raise ValueError('duplicate header')
            headers[name] = value.decode('latin1')
    size = int(headers.get('content-length', '0'))
    if size < 0 or size > BODY_LIMIT:
        raise ValueError('body limit')
    # 上游身份只来自内层原始请求，不能将建通道的令牌注入其他请求。
    async def body():
        total = 0
        while True:
            event = await next_chunk(ws, parser)
            if isinstance(event, h11.EndOfMessage):
                return
            if not isinstance(event, h11.Data):
                raise ValueError('invalid request body')
            total += len(event.data)
            if total > BODY_LIMIT:
                raise ValueError('body limit')
            yield bytes(event.data)

    has_body = size > 0 or any(k == b'transfer-encoding' for k, _ in request.headers)
    if not has_body:
        if not isinstance(await next_chunk(ws, parser), h11.EndOfMessage):
            raise ValueError('unexpected data')
    if is_probe:
        # 只有外层 WSS 已验证身份后才能执行固定探针，探针不访问任何上游。
        if raw_target != PROBE_PATH or request.method not in (b'GET', b'HEAD') or has_body:
            raise ValueError('invalid fixed probe')
        if parser.trailing_data[0]:
            raise ValueError('unexpected probe data')
        state['probeRequests'] += 1

        async def probe_content():
            if request.method == b'HEAD':
                return
            for offset in range(0, len(PROBE_BODY), 49152):
                yield PROBE_BODY[offset:offset + 49152]

        output = [(b'content-type', b'application/octet-stream'),
                  (b'content-length', str(len(PROBE_BODY)).encode('ascii')),
                  (b'content-encoding', b'identity'), (b'cache-control', b'no-store')]
        await _send_response(ws, parser, 200, output, probe_content(), state, observed)
    else:
        # 回到同一个已认证的 TLS 虚拟主机，保留原入口的授权策略和鉴权边界。
        if target.scheme == 'https':
            headers['Host'] = INGRESS_NAME + ':8443'
        observed['phase'] = 'upstreamConnect'
        async with client.request(request.method.decode(), target, headers=headers, data=body() if has_body else None,
                                  server_hostname=INGRESS_NAME if target.scheme == 'https' else None,
                                  allow_redirects=False, skip_auto_headers={'Accept-Encoding','Content-Type','User-Agent'}) as response:
            await _send_response(ws, parser, response.status, response.raw_headers,
                                 response.content.iter_chunked(49152), state, observed,
                                 video=target.path.endswith(('.m3u8', '.m4s', '.ts')),
                                 image='thumbnail' in target.path or response.headers.get('Content-Type', '').startswith('image/'))
    observed['phase'] = 'endAck'
    await ws.send_str('END')
    ack = await asyncio.wait_for(ws.receive(), END_ACK_SECONDS)
    if ack.type != aiohttp.WSMsgType.TEXT or ack.data != 'ACK':
        raise ValueError('stream not finished')


async def _send_response(ws, parser, status_code, headers, content, state, observed, video=False, image=False):
    # HTTP 业务拒绝也计入状态分布，可区分票据过期、无效 Range 与传输失败。
    status = str(status_code) if 100 <= status_code <= 599 else 'other'
    state['responseStatus'][status] = state['responseStatus'].get(status, 0) + 1
    observed['phase'] = 'responseHeaders'
    if 300 <= status_code < 400 and status_code != 304:
        raise ValueError('redirect refused')
    output = [(k, v) for k, v in headers if k.decode('ascii').lower() in RESPONSE_HEADERS]
    output.append((b'connection', b'close'))
    await ws.send_bytes(parser.send(h11.Response(status_code=status_code, headers=output)))
    if status_code == 206:
        state['ranges'] += 1
    if video:
        state['video'] += 1
    if image:
        state['images'] += 1
    outstanding = 0
    observed['phase'] = 'responseBody'
    async for data in content:
        frame = parser.send(h11.Data(data=data))
        observed['phase'] = 'responseWrite'
        await ws.send_bytes(frame)
        outstanding += len(frame)
        state['bytes'] += len(data)
        observed['bytes'] += len(data)
        # 每个窗口只确认一次，既限制内存，也避免逐小包等待一个网络往返。
        if outstanding >= WINDOW:
            observed['phase'] = 'flushAck'
            await ws.send_str('FLUSH')
            ack = await asyncio.wait_for(ws.receive(), FLUSH_ACK_SECONDS)
            if ack.type != aiohttp.WSMsgType.TEXT or ack.data != 'ACK':
                raise ValueError('missing credit')
            outstanding = 0
        observed['phase'] = 'responseBody'
    observed['phase'] = 'responseWrite'
    end = parser.send(h11.EndOfMessage())
    if end:
        await ws.send_bytes(end)


async def tunnel(request):
    state = request.app[STATE]
    state['attempts'] += 1
    # 部分原生客户端在 Origin 中省略 TLS 端口，仍须同时提供原生请求头和有效令牌。
    trusted_origins = {scheme + INGRESS_NAME + port for scheme in ('http://', 'https://')
                       for port in ('', ':8443')}
    if request.headers.get('Origin') and request.headers['Origin'] not in trusted_origins:
        state['originDenied'] += 1
        raise web.HTTPUnauthorized()
    if request.headers.get('X-MyTools-Channel') != '1' or not request.headers.get('Authorization', '').startswith('Bearer '):
        state['headerDenied'] += 1
        raise web.HTTPUnauthorized()
    if state['active'] >= 48:
        raise web.HTTPServiceUnavailable()
    state['active'] += 1
    ws = web.WebSocketResponse(max_msg_size=MAX_FRAME + 1, compress=False, heartbeat=30)
    owner = None
    phase = 'identity'
    try:
        async with request.app[CLIENT].get(IDENTITY, headers={'Authorization':request.headers['Authorization']},
                                              allow_redirects=False, timeout=6) as result:
            if result.status != 200:
                raise web.HTTPUnauthorized()
            identity = await result.json()
            data = identity.get('data') if isinstance(identity, dict) and identity.get('code') == '0000' else None
            value = data.get('userId') if isinstance(data, dict) else None
            if type(value) not in (str, int) or not str(value).strip():
                raise web.HTTPUnauthorized()
            key = str(value)
            if request.app[OWNERS].get(key, 0) >= 10:
                raise web.HTTPTooManyRequests()
            owner = key
            request.app[OWNERS][key] = request.app[OWNERS].get(key, 0) + 1
        await ws.prepare(request)
        state['sessions'] += 1
        await ws.send_str('READY')
        expires_at = asyncio.get_running_loop().time() + SESSION_LIFETIME_SECONDS
        for _ in range(128):
            remaining = expires_at - asyncio.get_running_loop().time()
            if remaining <= 0:
                state['lifetimeRotations'] += 1
                break
            phase = 'idle'
            try:
                msg = await asyncio.wait_for(ws.receive(), min(IDLE_SECONDS, remaining))
            except asyncio.TimeoutError:
                if asyncio.get_running_loop().time() >= expires_at:
                    state['lifetimeRotations'] += 1
                    break
                raise
            if msg.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED):
                break
            if asyncio.get_running_loop().time() >= expires_at:
                state['lifetimeRotations'] += 1
                break
            if msg.type != aiohttp.WSMsgType.TEXT or msg.data != 'BEGIN':
                raise ValueError('invalid stream')
            phase = 'request'
            # 寿命只限制接收新交换；已授权响应沿用原有读超时和确认窗口自然结束。
            await serve_request(ws, request.app[CLIENT], state)
            state['completed'] += 1
        if not ws.closed:
            await ws.close(code=1000)
    except (ValueError, UnicodeError, h11.ProtocolError, OSError, asyncio.TimeoutError, aiohttp.ClientError) as error:
        # 活跃请求已有独立结算；连接层诊断只记录认证与空闲阶段。
        if phase != 'request':
            if isinstance(error, (TimeoutError, asyncio.TimeoutError)):
                kind = 'identityTimeout' if phase == 'identity' else 'idleTimeout'
            else:
                kind = 'identityTransport' if phase == 'identity' else 'connectionProtocol'
            state['failures'][kind] += 1
        if ws.prepared:
            with contextlib.suppress(Exception):
                await ws.close(code=1008)
        else:
            raise web.HTTPBadGateway()
    finally:
        state['active'] -= 1
        if owner is not None:
            request.app[OWNERS][owner] -= 1
            if not request.app[OWNERS][owner]:
                del request.app[OWNERS][owner]
    return ws


async def session(app):
    async with aiohttp.ClientSession(auto_decompress=False, cookie_jar=aiohttp.DummyCookieJar(),
                                     timeout=aiohttp.ClientTimeout(total=None, sock_connect=4, sock_read=120),
                                     read_bufsize=65536) as client:
        app[CLIENT] = client
        yield


def application():
    app = web.Application(client_max_size=65536)
    app[STATE] = dict.fromkeys(('attempts', 'originDenied', 'headerDenied', 'active', 'sessions', 'completed', 'ranges', 'images', 'video', 'bytes', 'lifetimeRotations', 'probeRequests'), 0)
    app[STATE]['responseStatus'] = {}
    app[STATE]['requestMetrics'] = dict.fromkeys(('started', 'active', 'succeeded', 'failed',
        'successfulBodyBytes', 'failedBodyBytes', 'successfulDurationMs', 'failedDurationMs'), 0)
    app[STATE]['failures'] = dict.fromkeys(('requestTimeout', 'flushTimeout', 'endTimeout', 'upstreamTimeout',
        'upstreamPayload', 'flushProtocol', 'endProtocol', 'protocol', 'upstreamTransport', 'clientTransport',
        'internal', 'cancelled', 'identityTimeout', 'idleTimeout',
        'identityTransport', 'connectionProtocol'), 0)
    app[OWNERS] = {}
    async def health(request):
        return web.json_response(request.app[STATE])

    app.router.add_get('/health', health)
    app.cleanup_ctx.append(session)
    app.router.add_get('/_mytools/app-channel/v1', tunnel)
    return app


if __name__ == '__main__':
    if not INGRESS_NAME or not all(c.isalnum() or c in '.-' for c in INGRESS_NAME):
        raise ValueError('Missing fixed ingress hostname')
    web.run_app(application(), host='127.0.0.1', port=23483, access_log=None, print=None)
