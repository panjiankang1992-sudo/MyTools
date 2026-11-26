package com.yuyutian.mytools.toolbox.controller.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.toolbox.common.ApiResponse;
import com.yuyutian.mytools.toolbox.common.BusinessException;
import com.yuyutian.mytools.toolbox.model.TranslationRequest;
import com.yuyutian.mytools.toolbox.model.TranslationResult;
import com.yuyutian.mytools.toolbox.service.session.SessionService;
import com.yuyutian.mytools.toolbox.service.translation.TranslationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 匿名翻译入口，必须先验证访客会话与绑定的 CSRF 请求头。 */
@RestController
@RequestMapping("/public/v1/translations")
public class TranslationController {

    private final SessionService sessions;
    private final TranslationService translations;

    /**
     * 构造控制器。
     *
     * @param sessions 匿名会话服务
     * @param translations 受限翻译服务
     */
    public TranslationController(SessionService sessions, TranslationService translations) {
        this.sessions = sessions;
        this.translations = translations;
    }

    /**
     * 翻译并逐条报告结果，不向调用方转发上游错误正文。
     *
     * @param body 批量正文
     * @param request 当前请求
     * @return 统一响应信封
     */
    @PostMapping
    public ApiResponse<TranslationResult> translate(@RequestBody JsonNode body, HttpServletRequest request) {
        var guest = sessions.requireVerified(request);
        return ApiResponse.ok(translations.translate(guest, TranslationRequest.strictFrom(body)));
    }

}
