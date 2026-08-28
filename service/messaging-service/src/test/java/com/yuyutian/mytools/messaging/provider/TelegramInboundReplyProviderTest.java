package com.yuyutian.mytools.messaging.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramInboundReplyProviderTest {

    @Test
    void shouldResolveReplyMessageFromPlainAndAlbumIdentity() {
        assertThat(TelegramInboundReplyProvider.replyMessageId("tg-main:42:31")).isEqualTo(31L);
        assertThat(TelegramInboundReplyProvider.replyMessageId("tg-main:42:31:album:album-7")).isEqualTo(31L);
        assertThatThrownBy(() -> TelegramInboundReplyProvider.replyMessageId("tg-main:42:album:album-7"))
                .isInstanceOf(IllegalStateException.class);
    }
}
