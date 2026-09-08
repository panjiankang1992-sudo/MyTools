package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.service.GatewayReaderRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayExceptionHandlerTest {

    @Test
    void shouldReturnReaderBusinessCodeWithOriginalClientStatus() {
        var response = new GatewayExceptionHandler().readerRejected(
                new GatewayReaderRejectedException(HttpStatus.FORBIDDEN, "READER_029"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "READER_029");
        assertThat(response.getBody()).containsEntry("message", "Reader request was rejected (READER_029)");
    }
}
