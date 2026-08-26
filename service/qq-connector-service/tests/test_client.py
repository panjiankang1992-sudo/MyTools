import asyncio
from dataclasses import replace

from mytools_qq_connector.client import QQConnector
from mytools_qq_connector.config import Config


CONFIG = Config("qq_main", "app", "secret", 55, "allowed", "https://api.example.test",
                "https://token.example.test", "wss://gateway.example.test", 1,
                "http://messaging", "messaging-token", "http://scheduler",
                "http://onebot", "onebot-token", "qq-napcat", "automation-token")


class FakeConnector(QQConnector):
    def __init__(self, config=CONFIG):
        super().__init__(config, object())
        self.received = []
        self.commands = []
        self.replies = []

    async def _messaging_receive(self, payload, sender, message_id, content):
        self.received.append((payload["t"], sender, message_id, content))
        return content or "[empty]"

    async def _relogin_and_reply(self, sender, message_id):
        self.commands.append((sender, message_id))

    async def send_text(self, sender, message_id, text):
        self.replies.append((sender, message_id, text))


class CapturingConnector(QQConnector):
    def __init__(self, config=CONFIG):
        super().__init__(config, object())
        self.request = None

    async def _internal_json(self, method, url, payload, token):
        self.request = (method, url, payload, token)
        return {"id": "inbound-message"}


def event(content="登录", sender="allowed", message_id="message-1"):
    return {"t": "C2C_MESSAGE_CREATE", "d": {"id": message_id, "content": content,
            "author": {"user_openid": sender}}}


def test_exact_authorized_login_command_is_persisted_and_taskized():
    async def scenario():
        connector = FakeConnector()
        await connector.receive(event())
        await asyncio.sleep(0)
        assert connector.received == [("C2C_MESSAGE_CREATE", "allowed", "message-1", "登录")]
        assert connector.commands == [("allowed", "message-1")]
    asyncio.run(scenario())


def test_untrusted_or_non_exact_command_is_only_persisted():
    async def scenario():
        connector = FakeConnector()
        await connector.receive(event(content="请登录"))
        await connector.receive(event(sender="other", message_id="message-2"))
        await asyncio.sleep(0)
        assert len(connector.received) == 2
        assert connector.commands == []
    asyncio.run(scenario())


def test_send_image_is_bounded_before_any_network_call():
    async def scenario():
        connector = FakeConnector(replace(CONFIG))
        try:
            await connector.send_image("allowed", "message", b"", "caption")
        except ValueError:
            pass
        else:
            raise AssertionError("empty image must be rejected")
    asyncio.run(scenario())


def test_messaging_request_uses_current_inbound_contract():
    async def scenario():
        connector = CapturingConnector()
        await connector._messaging_receive(event(), "allowed", "message-1", "登录")
        payload = connector.request[2]
        assert payload["externalMessageId"] == "qq_main:C2C_MESSAGE_CREATE:message-1"
        assert "externalId" not in payload
    asyncio.run(scenario())


def test_authorized_url_is_persisted_and_acknowledged():
    async def scenario():
        connector = FakeConnector()
        await connector.receive(event(content="https://example.test/file", message_id="message-url"))
        await asyncio.sleep(0)
        assert connector.replies == [
            ("allowed", "message-url", "已接收，正在创建下载任务。")]
    asyncio.run(scenario())


def test_qq_attachment_is_normalized_for_messaging():
    async def scenario():
        connector = CapturingConnector()
        payload = event(content="", message_id="message-file")
        payload["d"]["attachments"] = [{"url": "https://example.test/photo.jpg",
            "content_type": "image/jpeg", "filename": "photo.jpg", "size": 123}]
        normalized = await connector._messaging_receive(
            payload, "allowed", "message-file", "")
        request = connector.request[2]
        assert normalized == "https://example.test/photo.jpg"
        assert request["parts"][1]["attachmentType"] == "IMAGE"
        assert request["parts"][1]["declaredSize"] == 123
    asyncio.run(scenario())


def test_expired_passive_reply_falls_back_to_active_message():
    class ReplyConnector(FakeConnector):
        def __init__(self):
            super().__init__()
            self.requests = []

        async def _qq_request(self, method, path, payload=None):
            self.requests.append(payload)
            if len(self.requests) == 1:
                raise RuntimeError("QQ request failed with HTTP 400, code=40034024, reason=expired")
            return {"id": "sent"}

    async def scenario():
        connector = ReplyConnector()
        await QQConnector.send_text(connector, "allowed", "message-old", "done", 2)
        assert connector.requests == [
            {"msg_type": 0, "content": "done", "msg_id": "message-old", "msg_seq": 2},
            {"msg_type": 0, "content": "done"}]
    asyncio.run(scenario())
