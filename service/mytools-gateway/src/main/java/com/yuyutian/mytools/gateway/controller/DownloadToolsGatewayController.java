package com.yuyutian.mytools.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.gateway.common.ErrorCode;
import com.yuyutian.mytools.gateway.config.GatewayProperties;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.util.Set;

/** 下载聊天入口代理，仅转发受控路径和可信身份。 */
@RestController
@RequestMapping("/api/app/v1/download-tools")
public class DownloadToolsGatewayController {
    private static final int LIMIT = 1048576;
    private final GatewayProperties properties;
    private final ObjectMapper json;

    /** 注入网关配置及 JSON 序列化器。 */
    public DownloadToolsGatewayController(GatewayProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    /** 流式转发单个上传块，将内部响应包装为 APP 信封。 */
    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT})
    public void forward(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!(request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE) instanceof GatewayPrincipal principal)) {
            error(response, 401, ErrorCode.DOWNLOAD_002);
            return;
        }
        if (!properties.downloadRouteEnabled() || properties.downloadToken() == null || properties.downloadToken().isBlank()) {
            error(response, 503, ErrorCode.DOWNLOAD_003);
            return;
        }
        String path = request.getRequestURI().substring("/api/app/v1/download-tools".length());
        if (!allowed(request.getMethod(), path)) {
            error(response, 404, ErrorCode.DOWNLOAD_004);
            return;
        }
        long length = request.getContentLengthLong();
        // 明确长度后再打开下游连接，避免不完整块或无界请求进入上传服务。
        if (!request.getMethod().equals("GET") && (length < 0 || length > LIMIT)) {
            error(response, 413, ErrorCode.DOWNLOAD_001);
            return;
        }
        String offset = request.getHeader("X-Upload-Offset");
        if (request.getMethod().equals("PUT") && (offset == null || !offset.matches("[0-9]{1,10}"))) {
            error(response, 400, ErrorCode.DOWNLOAD_001);
            return;
        }
        String query = "";
        if (request.getMethod().equals("GET") && path.equals("/history")) {
            String[] limits = request.getParameterValues("limit");
            String[] cursors = request.getParameterValues("before");
            if (limits != null || cursors != null) {
                if (limits == null || limits.length != 1 || !limits[0].matches("[1-9][0-9]?")
                        || (cursors != null && (cursors.length != 1 || !cursors[0].matches("[A-Za-z0-9_-]{1,128}")))) {
                    error(response, 400, ErrorCode.DOWNLOAD_001);
                    return;
                }
                // 只转发分页字段，用户的身份参数不能替换网关注入的可信主体。
                query = "?limit=" + limits[0] + (cursors == null ? "" : "&before=" + cursors[0]);
            }
        }
        HttpURLConnection connection = (HttpURLConnection) URI.create(properties.downloadUrl().replaceAll("/+$", "")
                + "/internal/v1/app-download-tools" + path + query).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(120000);
        connection.setRequestMethod(request.getMethod());
        connection.setRequestProperty("Authorization", "Bearer " + properties.downloadToken());
        connection.setRequestProperty("X-Owner-Id", Long.toString(principal.userId()));
        connection.setRequestProperty("X-Resource-Username", principal.username());
        connection.setRequestProperty("Content-Type", request.getMethod().equals("PUT") ? "application/octet-stream" : "application/json");
        if (offset != null) connection.setRequestProperty("X-Upload-Offset", offset);
        try {
            if (!request.getMethod().equals("GET")) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(length);
                try (var output = connection.getOutputStream()) {
                    byte[] buffer = new byte[65536];
                    long remaining = length;
                    // 逐块转发，不把整个图片或视频缓冲在网关内存中。
                    while (remaining > 0) {
                        int count = request.getInputStream().read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (count < 0) throw new IOException("truncated_upload");
                        output.write(buffer, 0, count);
                        remaining -= count;
                    }
                }
            }
            int status = connection.getResponseCode();
            if (status >= 300) {
                error(response, status == 404 ? 404 : status == 400 ? 400 : 503,
                        status == 404 ? ErrorCode.DOWNLOAD_004 : status == 400 ? ErrorCode.DOWNLOAD_001 : ErrorCode.DOWNLOAD_003);
                return;
            }
            try (var input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(LIMIT + 1);
                if (bytes.length > LIMIT) throw new IOException("response_too_large");
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                json.writeValue(response.getOutputStream(), Map.of("code", "0000", "message", "OK", "data", json.readTree(bytes)));
            }
        } catch (IOException exception) {
            if (!response.isCommitted()) error(response, 503, ErrorCode.DOWNLOAD_003);
        } finally {
            connection.disconnect();
        }
    }

    /** 校验固定的下载聊天路由，阻止访问其他内部接口。 */
    public static boolean allowed(String method, String path) {
        String id = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        return switch (method) {
            case "GET" -> path.equals("/history") || path.matches("/messages/" + id);
            case "POST" -> Set.of("/messages", "/uploads").contains(path)
                    || path.matches("/uploads/" + id + "/complete") || path.matches("/messages/" + id + "/cancel");
            case "PUT" -> path.matches("/uploads/" + id + "/content");
            default -> false;
        };
    }

    private void error(HttpServletResponse response, int status, ErrorCode code) throws IOException {
        response.reset();
        response.setStatus(status);
        response.setContentType("application/json");
        json.writeValue(response.getOutputStream(), Map.of("code", code.name(), "message", code.name()));
    }
}
