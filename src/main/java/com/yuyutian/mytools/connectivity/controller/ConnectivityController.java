package com.yuyutian.mytools.connectivity.controller;

import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.connectivity.model.LanBootstrapResponse;
import com.yuyutian.mytools.connectivity.model.LanChallengeRequest;
import com.yuyutian.mytools.connectivity.model.LanChallengeResponse;
import com.yuyutian.mytools.connectivity.service.LanVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * App 局域网安全直连验证接口。
 */
@RestController
@RequiredArgsConstructor
public class ConnectivityController {

    private final LanVerificationService verificationService;

    /**
     * 通过已认证的公网连接获取短期探测材料。
     *
     * @param request HTTP 请求
     * @return 局域网探测材料
     */
    @GetMapping("/api/app/v1/connectivity/bootstrap")
    public Result<LanBootstrapResponse> bootstrap(HttpServletRequest request) {
        return Result.success(verificationService.issue((Long) request.getAttribute("userId")));
    }

    /**
     * 在候选局域网地址验证当前服务身份，不接收用户 JWT。
     *
     * @param request 随机挑战
     * @return 服务证明
     */
    @PostMapping("/api/public/connectivity/challenge")
    public Result<LanChallengeResponse> challenge(@Valid @RequestBody LanChallengeRequest request) {
        return Result.success(verificationService.challenge(request));
    }
}
