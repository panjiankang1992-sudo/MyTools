"""使用真实 HTTP 与 WebSocket 套接字验证通道的认证、分段、流控和复用。"""
import asyncio
import unittest
from unittest.mock import patch

import aiohttp
from aiohttp import web
from aiohttp.test_utils import TestServer
import h11

import channel


class TunnelTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.payload = bytes(range(256)) * 8192
        self.seen = []

        async def identity(request):
            accepted = request.headers.get('Authorization') == 'Bearer example'
            return web.json_response({'code': '0000' if accepted else '401',
                                      'data': {'userId': 'tester'} if accepted else None})

        async def service(request):
            self.seen.append(dict(request.headers))
            if request.path.endswith('/authenticated'):
                accepted = request.headers.get('Authorization') == 'Bearer business-inner'
                return web.Response(status=200 if accepted else 403, body=b'allowed' if accepted else b'denied')
            if request.path.endswith('/forbidden'):
                return web.Response(status=403, body=b'denied')
            if request.path.endswith('/unsatisfied'):
                return web.Response(status=416, body=b'bad-range',
                                    headers={'Content-Range': f'bytes */{len(self.payload)}'})
            if request.path.endswith('/truncated'):
                response = web.StreamResponse(status=206, headers={'Content-Length': '10000',
                                              'Content-Range': 'bytes 0-9999/20000'})
                await response.prepare(request)
                await response.write(b'x' * 100)
                if request.transport is not None:
                    request.transport.close()
                return response
            if request.path.endswith('/slow'):
                await asyncio.sleep(.15)
            if request.path.endswith('/redirect'):
                raise web.HTTPFound('https://example.invalid/')
            if request.method == 'POST':
                return web.Response(body=await request.read())
            if request.headers.get('Range') == 'bytes=17-48':
                return web.Response(status=206, body=self.payload[17:49],
                                    headers={'Content-Range': f'bytes 17-48/{len(self.payload)}'})
            return web.Response(body=self.payload)

        upstream = web.Application()
        upstream.router.add_get('/identity', identity)
        upstream.router.add_route('*', '/api/{tail:.*}', service)
        server = TestServer(upstream)
        await server.start_server()
        self.addAsyncCleanup(server.close)
        with_upstream = patch.multiple(channel, ORIGIN=str(server.make_url('')).rstrip('/'),
                                       IDENTITY=str(server.make_url('/identity')))
        with_upstream.start()
        self.addCleanup(with_upstream.stop)
        self.relay = TestServer(channel.application())
        await self.relay.start_server()
        self.addAsyncCleanup(self.relay.close)
        self.client = aiohttp.ClientSession()
        self.addAsyncCleanup(self.client.close)
        self.url = self.relay.make_url('/_mytools/app-channel/v1')
        self.headers = {'Authorization': 'Bearer example', 'X-MyTools-Channel': '1'}

    async def connect(self):
        ws = await self.client.ws_connect(self.url, headers=self.headers)
        self.addAsyncCleanup(ws.close)
        self.assertEqual(await ws.receive_str(timeout=2), 'READY')
        return ws

    async def exchange(self, ws, method, target, headers=(), body=b''):
        conn = h11.Connection(h11.CLIENT)
        head = conn.send(h11.Request(method=method, target=target, headers=[(b'host', b'untrusted'), *headers]))
        await ws.send_str('BEGIN')
        await ws.send_bytes(head)
        for start in range(0, len(body), channel.MAX_FRAME):
            await ws.send_bytes(conn.send(h11.Data(data=body[start:start + channel.MAX_FRAME])))
        tail = conn.send(h11.EndOfMessage())
        if tail:
            await ws.send_bytes(tail)
        windows = 0
        while True:
            frame = await ws.receive(timeout=4)
            if frame.type == aiohttp.WSMsgType.BINARY:
                conn.receive_data(frame.data)
            elif frame.data == 'FLUSH':
                windows += 1
                await ws.send_str('ACK')
            elif frame.data == 'END':
                await ws.send_str('ACK')
                break
            else:
                self.fail('unexpected stream termination')
        status = conn.next_event()
        self.assertIsInstance(status, h11.Response)
        data = bytearray()
        while True:
            event = conn.next_event()
            if isinstance(event, h11.EndOfMessage):
                return status, data, windows
            self.assertIsInstance(event, h11.Data)
            data.extend(event.data)

    async def test_reused_stream_handles_range_upload_and_window(self):
        ws = await self.connect()
        response, body, _ = await self.exchange(ws, b'GET', b'/api/range', [(b'range', b'bytes=17-48'),
                                                (b'x-forwarded-for', b'evil'), (b'x-user-id', b'evil')])
        self.assertEqual(response.status_code, 206)
        self.assertEqual(dict(response.headers)[b'content-range'], f'bytes 17-48/{len(self.payload)}'.encode())
        self.assertEqual(body, self.payload[17:49])
        lower = {k.lower(): v for k, v in self.seen[-1].items()}
        self.assertTrue({'x-forwarded-for', 'x-user-id', 'authorization'}.isdisjoint(lower))
        self.assertNotEqual(lower['host'], 'untrusted')
        upload = self.payload[:90001]
        _, body, _ = await self.exchange(ws, b'POST', b'/api/upload', [(b'content-length', str(len(upload)).encode())], upload)
        self.assertEqual(body, upload)
        _, body, windows = await self.exchange(ws, b'GET', b'/api/media')
        self.assertEqual(body, self.payload)
        self.assertGreaterEqual(windows, 1)
        _, body, _ = await self.exchange(ws, b'HEAD', b'/api/media')
        self.assertFalse(body)

    async def health(self, expected_succeeded=None):
        deadline = asyncio.get_running_loop().time() + 2
        while True:
            remaining = deadline - asyncio.get_running_loop().time()
            self.assertGreater(remaining, 0, 'request metrics did not settle before deadline')
            async with self.client.get(self.relay.make_url('/health'),
                                       timeout=aiohttp.ClientTimeout(total=remaining)) as result:
                state = await result.json()
            if expected_succeeded is None:
                return state
            # END 确认发出后仍须等待服务端回收接收任务并结算请求指标。
            metrics = state['requestMetrics']
            if metrics['active'] == 0 and metrics['succeeded'] == expected_succeeded:
                return state

    async def begin(self, ws, target, headers=b''):
        await ws.send_str('BEGIN')
        await ws.send_bytes(b'GET ' + target + b' HTTP/1.1\r\nHost: fixture\r\n' + headers + b'\r\n')

    async def read_until(self, ws, marker):
        while True:
            message = await ws.receive(timeout=2)
            if message.type == aiohttp.WSMsgType.TEXT and message.data == marker:
                return
            self.assertEqual(message.type, aiohttp.WSMsgType.BINARY)

    async def test_upstream_status_histogram_preserves_403_and_416(self):
        ws = await self.connect()
        first, first_body, _ = await self.exchange(ws, b'GET', b'/api/forbidden')
        second, second_body, _ = await self.exchange(ws, b'GET', b'/api/unsatisfied')
        self.assertEqual((first.status_code, second.status_code), (403, 416))
        self.assertEqual(dict(second.headers)[b'content-range'], f'bytes */{len(self.payload)}'.encode())
        state = await self.health(expected_succeeded=2)
        self.assertEqual(state['responseStatus'], {'403': 1, '416': 1})
        self.assertEqual(state['requestMetrics']['succeeded'], 2)
        self.assertEqual(state['requestMetrics']['failed'], 0)
        self.assertEqual(state['requestMetrics']['successfulBodyBytes'], len(first_body) + len(second_body))
        self.assertTrue(all(count == 0 for count in state['failures'].values()))

    async def test_missing_credit_records_flush_timeout_and_partial_body(self):
        with patch.object(channel, 'FLUSH_ACK_SECONDS', .02):
            ws = await self.connect()
            await self.begin(ws, b'/api/media')
            await self.read_until(ws, 'FLUSH')
            self.assertEqual((await ws.receive(timeout=2)).type, aiohttp.WSMsgType.CLOSE)
        state = await self.health()
        self.assertEqual(state['failures']['flushTimeout'], 1)
        self.assertEqual(state['requestMetrics']['started'], 1)
        self.assertEqual(state['requestMetrics']['failed'], 1)
        self.assertEqual(state['requestMetrics']['succeeded'], 0)
        self.assertEqual(state['requestMetrics']['active'], 0)
        self.assertGreaterEqual(state['requestMetrics']['failedBodyBytes'], channel.WINDOW)
        self.assertGreaterEqual(state['requestMetrics']['failedDurationMs'], 15)
        self.assertEqual(state['responseStatus'], {'200': 1})
        self.assertEqual(state['completed'], 0)

    async def test_wrong_credit_records_protocol_failure(self):
        ws = await self.connect()
        await self.begin(ws, b'/api/media')
        await self.read_until(ws, 'FLUSH')
        await ws.send_str('WRONG')
        self.assertEqual((await ws.receive(timeout=2)).type, aiohttp.WSMsgType.CLOSE)
        state = await self.health()
        self.assertEqual(state['failures']['flushProtocol'], 1)
        self.assertEqual(state['requestMetrics']['failed'], 1)
        self.assertGreaterEqual(state['requestMetrics']['failedBodyBytes'], channel.WINDOW)

    async def test_missing_end_ack_records_end_timeout(self):
        with patch.object(channel, 'END_ACK_SECONDS', .02):
            ws = await self.connect()
            await self.begin(ws, b'/api/range', b'Range: bytes=17-48\r\n')
            await self.read_until(ws, 'END')
            self.assertEqual((await ws.receive(timeout=2)).type, aiohttp.WSMsgType.CLOSE)
        state = await self.health()
        self.assertEqual(state['failures']['endTimeout'], 1)
        self.assertEqual(state['requestMetrics']['failedBodyBytes'], 32)
        self.assertEqual(state['responseStatus'], {'206': 1})
        self.assertEqual(state['ranges'], 1)

    async def test_short_upstream_range_records_payload_failure(self):
        ws = await self.connect()
        await self.begin(ws, b'/api/truncated')
        while True:
            message = await ws.receive(timeout=2)
            if message.type == aiohttp.WSMsgType.CLOSE:
                break
            self.assertEqual(message.type, aiohttp.WSMsgType.BINARY)
        state = await self.health()
        self.assertEqual(state['failures']['upstreamPayload'], 1)
        self.assertEqual(state['responseStatus'], {'206': 1})
        self.assertEqual(state['requestMetrics']['failed'], 1)
        self.assertEqual(state['requestMetrics']['active'], 0)

    async def test_connection_lifetime_drains_active_response_before_rotation(self):
        with patch.object(channel, 'SESSION_LIFETIME_SECONDS', .03):
            ws = await self.connect()
            response, body, _ = await self.exchange(ws, b'GET', b'/api/slow')
            self.assertEqual(response.status_code, 200)
            self.assertEqual(body, self.payload)
            self.assertEqual((await ws.receive(timeout=2)).type, aiohttp.WSMsgType.CLOSE)
        state = await self.health()
        self.assertEqual(state['lifetimeRotations'], 1)
        self.assertEqual(state['failures']['cancelled'], 0)
        self.assertEqual(state['requestMetrics']['failed'], 0)
        self.assertEqual(state['requestMetrics']['succeeded'], 1)
        self.assertEqual(state['requestMetrics']['successfulBodyBytes'], len(self.payload))
        self.assertEqual(state['requestMetrics']['active'], 0)
        self.assertGreaterEqual(state['requestMetrics']['successfulDurationMs'], 100)
        self.assertEqual(state['completed'], 1)

    async def test_connection_lifetime_rotates_idle_lane_without_accepting_new_requests(self):
        with patch.object(channel, 'SESSION_LIFETIME_SECONDS', .02):
            ws = await self.connect()
            self.assertEqual((await ws.receive(timeout=2)).type, aiohttp.WSMsgType.CLOSE)
        state = await self.health()
        self.assertEqual(state['lifetimeRotations'], 1)
        self.assertEqual(state['requestMetrics']['started'], 0)
        self.assertEqual(state['requestMetrics']['failed'], 0)
        self.assertTrue(all(count == 0 for count in state['failures'].values()))

    async def test_fixed_probe_get_and_head_are_complete_and_never_call_business_upstream(self):
        ws = await self.connect()
        path = channel.PROBE_PATH.encode()
        response, body, credits = await self.exchange(ws, b'GET', path,
                                                     [(b'accept-encoding', b'gzip'), (b'range', b'bytes=0-3')])
        self.assertEqual(response.status_code, 200)
        headers = dict(response.headers)
        self.assertEqual(headers[b'content-length'], b'524288')
        self.assertEqual(headers[b'content-type'], b'application/octet-stream')
        self.assertEqual(headers[b'content-encoding'], b'identity')
        self.assertEqual(headers[b'cache-control'], b'no-store')
        self.assertEqual(body, bytes(range(256)) * 2048)
        self.assertEqual(credits, 0)
        head, body, _ = await self.exchange(ws, b'HEAD', path)
        self.assertEqual(head.status_code, 200)
        self.assertEqual(dict(head.headers)[b'content-length'], b'524288')
        self.assertFalse(body)
        self.assertFalse(self.seen)
        state = await self.health(expected_succeeded=2)
        self.assertEqual(state['probeRequests'], 2)
        self.assertEqual(state['requestMetrics']['succeeded'], 2)
        self.assertEqual(state['requestMetrics']['successfulBodyBytes'], 524288)

    async def test_fixed_probe_uses_the_same_credit_window(self):
        with patch.object(channel, 'WINDOW', 32768):
            ws = await self.connect()
            _, body, credits = await self.exchange(ws, b'GET', channel.PROBE_PATH.encode())
        self.assertEqual(body, channel.PROBE_BODY)
        self.assertGreater(credits, 1)
        self.assertFalse(self.seen)

    async def test_fixed_probe_rejects_parameters_methods_and_request_bodies(self):
        cases = [
            (b'POST', channel.PROBE_PATH.encode(), b''),
            (b'DELETE', channel.PROBE_PATH.encode(), b''),
            (b'PUT', channel.PROBE_PATH.encode(), b''),
            (b'PATCH', channel.PROBE_PATH.encode(), b''),
            (b'GET', channel.PROBE_PATH.encode(), b'Content-Length: 1\r\n'),
            (b'HEAD', channel.PROBE_PATH.encode(), b'Transfer-Encoding: chunked\r\n'),
            (b'GET', (channel.PROBE_PATH + '?bytes=1').encode(), b''),
            (b'GET', (channel.PROBE_PATH + '?url=http%3A%2F%2Fexample.invalid').encode(), b''),
            (b'GET', channel.PROBE_PATH.replace('transport', '%74ransport').encode(), b''),
            (b'GET', b'https://example.invalid' + channel.PROBE_PATH.encode(), b'')
        ]
        for method, target, headers in cases:
            with self.subTest(method=method, target=target):
                ws = await self.connect()
                await ws.send_str('BEGIN')
                await ws.send_bytes(method + b' ' + target + b' HTTP/1.1\r\nHost: fixture\r\n' + headers + b'\r\n')
                self.assertEqual((await ws.receive(timeout=2)).type, aiohttp.WSMsgType.CLOSE)
        self.assertFalse(self.seen)
        self.assertEqual((await self.health())['probeRequests'], 0)

    async def test_probe_does_not_inject_outer_auth_into_normal_business_requests(self):
        ws = await self.connect()
        probe, _, _ = await self.exchange(ws, b'GET', channel.PROBE_PATH.encode())
        denied, _, _ = await self.exchange(ws, b'GET', b'/api/authenticated')
        accepted, _, _ = await self.exchange(ws, b'GET', b'/api/authenticated',
                                            [(b'authorization', b'Bearer business-inner')])
        self.assertEqual((probe.status_code, denied.status_code, accepted.status_code), (200, 403, 200))
        self.assertNotIn('authorization', {key.lower() for key in self.seen[0]})
        self.assertEqual(self.seen[1]['Authorization'], 'Bearer business-inner')
        self.assertEqual((await self.health())['probeRequests'], 1)

    async def test_probe_is_not_an_unauthenticated_http_route(self):
        async with self.client.get(self.relay.make_url(channel.PROBE_PATH)) as response:
            self.assertEqual(response.status, 404)
        for headers in ({}, {**self.headers, 'Authorization': 'Bearer invalid'}):
            with self.assertRaises(aiohttp.WSServerHandshakeError):
                await self.client.ws_connect(self.url, headers=headers)
        self.assertFalse(self.seen)
        self.assertEqual((await self.health())['probeRequests'], 0)

    async def test_fixed_probe_is_not_available_through_plain_http_handshake_route(self):
        async with self.client.get(self.url, headers={**self.headers, 'X-MyTools-Channel-Probe': '1'}) as response:
            self.assertEqual(response.status, 400)
            self.assertNotEqual(await response.read(), channel.PROBE_BODY)
        state = await self.health()
        self.assertEqual(state['probeRequests'], 0)
        self.assertEqual(state['sessions'], 0)
        self.assertEqual(state['completed'], 0)
        self.assertEqual(state['bytes'], 0)
        self.assertEqual(state['active'], 0)
        self.assertEqual(self.relay.app[channel.OWNERS], {})
        self.assertTrue({'wakeupSessions', 'wakeupFrames', 'httpProbeRequests', 'httpProbeBytes'}.isdisjoint(state))
        self.assertFalse(self.seen)

    async def test_handshake_requires_native_header_and_valid_identity(self):
        for headers in ({}, {'Authorization': 'Bearer example'}, {**self.headers, 'Authorization': 'Bearer wrong'},
                        {**self.headers, 'Origin': 'https://untrusted.invalid'}):
            with self.assertRaises(aiohttp.WSServerHandshakeError) as raised:
                await self.client.ws_connect(self.url, headers=headers)
            self.assertEqual(raised.exception.status, 401)

    async def test_redirect_does_not_escape_origin(self):
        ws = await self.connect()
        await ws.send_str('BEGIN')
        await ws.send_bytes(b'GET /api/redirect HTTP/1.1\r\nHost: example\r\n\r\n')
        self.assertEqual((await ws.receive(timeout=2)).type, aiohttp.WSMsgType.CLOSE)

    def test_target_cannot_escape_api(self):
        for path in ('https://example.invalid/api/a', '//host/api/', '/api/%252e%252e/secret',
                     '/api/a%2f..%2factuator', '/api/a%5cb', '/api/internal/x', '/api/path#fragment'):
            with self.subTest(path=path), self.assertRaises(ValueError):
                channel.checked_target(path)


if __name__ == '__main__':
    unittest.main()
