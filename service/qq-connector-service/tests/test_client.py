import asyncio
from dataclasses import replace

from mytools_qq_connector.client import QQConnector
from mytools_qq_connector.config import Config


CONFIG = Config("qq_main", "app", "secret", 55, "allowed", "https://api.example.test",
                "https://token.example.test", "wss://gateway.example.test", 1,
                "http://messaging", "messaging-token", "http://scheduler",
                "http://onebot", "onebot-token", "qq-napcat")


class FakeConnector(QQConnector):
    def __init__(self, config=CONFIG):
        super().__init__(config, object())
        self.received = []
        self.commands = []

    async def _messaging_receive(self, payload, sender, message_id, content):
        self.received.append((payload["t"], sender, message_id, content))

    async def _relogin_and_reply(self, sender, message_id):
        self.commands.append((sender, message_id))


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
