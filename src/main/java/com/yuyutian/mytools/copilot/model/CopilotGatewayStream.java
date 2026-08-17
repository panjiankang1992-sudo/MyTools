package com.yuyutian.mytools.copilot.model;

import java.io.InputStream;
import java.io.IOException;

/**
 * Copilot上游流式响应。
 *
 * @param statusCode HTTP状态码。
 * @param contentType 响应内容类型。
 * @param body 响应流。
 */
public record CopilotGatewayStream(int statusCode, String contentType, InputStream body) implements AutoCloseable {

    /**
     * 关闭上游响应流。
     */
    @Override
    public void close() throws IOException {
        body.close();
    }
}
