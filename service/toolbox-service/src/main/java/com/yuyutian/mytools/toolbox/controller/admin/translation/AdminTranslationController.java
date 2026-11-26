package com.yuyutian.mytools.toolbox.controller.admin.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.toolbox.common.ApiResponse;
import com.yuyutian.mytools.toolbox.model.TranslationRequest;
import com.yuyutian.mytools.toolbox.model.TranslationResult;
import com.yuyutian.mytools.toolbox.service.admin.session.AdminSession;
import com.yuyutian.mytools.toolbox.service.translation.TranslationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端自动补全入口。
 *
 * 管理端此前只能走公开访客入口（/public/v1/translations），
 * 于是管理员消耗的是访客额度。审查 F06 要求管理端专用接口与独立额度。
 *
 * 额度如何独立：本入口用 admin:管理员标识 作为额度主体，
 * 公开入口用 guest:访客标识。额度表本来就按主体分桶，
 * 所以两者天然互不影响，不需要另建一套额度系统。
 */
@RestController
@RequestMapping("/admin/v1/translations")
public class AdminTranslationController {

    private final TranslationService translations;

    public AdminTranslationController(TranslationService translations) {
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
        return ApiResponse.ok(translations.translate("admin:" + actor(request), TranslationRequest.strictFrom(body)));
    }

    /**
     * 读取当前管理员标识，用于组成独立的额度主体。
     *
     * @param request 当前请求
     * @return MyTools 用户标识；未认证时为 unknown
     */
    private static String actor(HttpServletRequest request) {
        Object session = request.getAttribute(AdminSession.class.getName());
        return session instanceof AdminSession admin ? admin.mytoolsUserId() : "unknown";
    }
}
