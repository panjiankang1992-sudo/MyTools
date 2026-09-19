package com.yuyutian.mytools.gateway.web;

import com.yuyutian.mytools.gateway.common.ErrorCode;
import com.yuyutian.mytools.gateway.controller.VideoGenerationGatewayController;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 在 MVC 读取请求体之前按 Content-Length 拒绝超限的视频素材上传。
 *
 * <p>控制器的 {@code @RequestBody byte[]} 会先缓冲整个请求体，因此仅靠控制器内的长度判断
 * 无法阻止超大请求体进入内存，这里提前拦截。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class VideoGenerationUploadSizeFilter extends OncePerRequestFilter {

    private static final String IMAGE_UPLOAD_PATH = "/api/app/v1/video-generation/uploads/image";
    private static final String VIDEO_UPLOAD_PATH = "/api/app/v1/video-generation/uploads/video";

    /**
     * 只拦截两个原始字节上传入口。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !IMAGE_UPLOAD_PATH.equals(path) && !VIDEO_UPLOAD_PATH.equals(path);
    }

    /**
     * 依据 Content-Length 提前返回 413，避免超大请求体被缓冲。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 图片与视频使用不同的上限，视频上传固定按 200MB 判断。
        long limit = IMAGE_UPLOAD_PATH.equals(request.getRequestURI())
                ? VideoGenerationGatewayController.MAX_IMAGE_UPLOAD_BYTES
                : VideoGenerationGatewayController.MAX_VIDEO_UPLOAD_BYTES;
        if (request.getContentLengthLong() > limit) {
            response.setStatus(413);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"code\":\"" + ErrorCode.VIDEO_001.name()
                    + "\",\"message\":\"" + ErrorCode.VIDEO_001.name() + "\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
