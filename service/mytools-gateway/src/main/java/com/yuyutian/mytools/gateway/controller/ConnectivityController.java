package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.model.ConnectivityModels.Bootstrap;
import com.yuyutian.mytools.gateway.model.ConnectivityModels.Challenge;
import com.yuyutian.mytools.gateway.model.ConnectivityModels.ChallengeRequest;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.ConnectivityService;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供 App 局域网发现所需的 Gateway 边缘接口。
 */
@RestController
public class ConnectivityController {
    private final ConnectivityService service;

    /**
     * 创建 Connectivity 控制器。
     *
     * @param service 局域网探测服务
     */
    public ConnectivityController(ConnectivityService service) {
        this.service = service;
    }

    /**
     * 通过认证连接签发短期探测材料。
     *
     * @param request HTTP 请求
     * @return 探测材料
     */
    @GetMapping("/api/app/v1/connectivity/bootstrap")
    public Bootstrap bootstrap(HttpServletRequest request) {
        Object value = request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof GatewayPrincipal principal)) {
            throw new GatewayUnauthorizedException();
        }
        return service.issue(principal.userId());
    }

    /**
     * 在候选局域网地址响应匿名随机挑战。
     *
     * @param request 挑战参数
     * @return 服务身份证明
     */
    @PostMapping("/api/public/connectivity/challenge")
    public Challenge challenge(@Valid @RequestBody ChallengeRequest request) {
        return service.challenge(request);
    }
}
