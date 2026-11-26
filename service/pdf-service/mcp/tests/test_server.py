"""MCP 协议、隔离和本机路径限制回归。"""
import asyncio
import hashlib
import importlib.util
import json
from pathlib import Path
import pytest
import httpx

spec = importlib.util.spec_from_file_location('pdf_mcp', Path(__file__).parents[1] / 'server.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def test_paths_and_gateway(monkeypatch, tmp_path):
    monkeypatch.setenv('PDF_MCP_FILE_ROOT', str(tmp_path))
    assert m.permitted_path(str(tmp_path / 'new.pdf')) == tmp_path / 'new.pdf'
    with pytest.raises(ValueError):
        m.permitted_path('/etc/passwd')
    with pytest.raises(ValueError):
        m.PdfClient('http://example.com', 'test')
    with pytest.raises(ValueError):
        m.identifier('../../work')


def test_remote_tools_hide_local_paths(monkeypatch):
    async def check():
        server = m.create_server(True)
        tools = await server.list_tools()
        names = {tool.name for tool in tools}
        assert {'pdf_plan', 'pdf_start', 'pdf_artifacts', 'pdf_create_upload'} <= names
        assert 'pdf_upload_local' not in names
        assert 'pdf_download_local' not in names
    asyncio.run(check())


def test_http_protocol_and_identity(monkeypatch, tmp_path):
    credentials = tmp_path / 'accounts.json'
    credentials.write_text(json.dumps({hashlib.sha256(b'caller-a').hexdigest(): {'gatewayToken': 'account-a'},
                                       hashlib.sha256(b'caller-b').hexdigest(): {'gatewayToken': 'account-b'}}))
    credentials.chmod(0o600)
    monkeypatch.setenv('MYTOOLS_GATEWAY_URL', 'http://127.0.0.1:12345')
    async def fake_call(self, method, path, body=None, key=None, offset=None):
        await asyncio.sleep(.01)
        return {'operations': ['PDF_PAGE_CUT'], 'identity': self.token}
    monkeypatch.setattr(m.PdfClient, 'call', fake_call)

    async def check():
        server = m.create_server(True)
        app = m.BearerAccounts(server.streamable_http_app(), credentials)
        async with server.session_manager.run():
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://127.0.0.1:23343') as client:
                payload = {'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call', 'params': {'name': 'pdf_capabilities', 'arguments': {}}}
                async def call(token):
                    return await client.post('/mcp', json=payload, headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/json, text/event-stream'})
                assert (await call('invalid')).status_code == 401
                first, second = await asyncio.gather(call('caller-a'), call('caller-b'))
                assert first.status_code == second.status_code == 200
                assert 'account-a' in first.text and 'account-b' not in first.text
                assert 'account-b' in second.text and 'account-a' not in second.text
                credentials.write_text('{}')
                assert (await call('caller-a')).status_code == 401
                assert m._gateway_token.get() == ''
    asyncio.run(check())


def test_real_stdio_protocol():
    import sys
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client
    async def check():
        parameters = StdioServerParameters(command=sys.executable, args=[str(Path(__file__).parents[1] / 'server.py')])
        async with stdio_client(parameters) as (read, write):
            async with ClientSession(read, write) as session:
                await session.initialize()
                result = await session.list_tools()
                names = {tool.name for tool in result.tools}
                assert 'pdf_upload_local' in names
                assert 'pdf_download_local' in names
                plan = next(tool for tool in result.tools if tool.name == 'pdf_plan')
                assert 'enum' in plan.inputSchema['properties']['operation']
    asyncio.run(check())
