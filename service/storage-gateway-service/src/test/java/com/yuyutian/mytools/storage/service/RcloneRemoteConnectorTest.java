package com.yuyutian.mytools.storage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RcloneRemoteConnectorTest {

    @Test
    void shouldRejectNonLoopbackControlEndpoint() {
        RcloneRemoteConnector connector = new RcloneRemoteConnector(
                new ObjectMapper(), "http://example.com:5572", "", "");

        assertThatThrownBy(connector::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rclone RC must use a loopback HTTP endpoint");
    }
}
