package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.config.DshGatewayProperties;
import com.yuyutian.mytools.gateway.model.DshGatewayModels.BindingView;
import com.yuyutian.mytools.gateway.model.GatewayPrincipal;
import com.yuyutian.mytools.gateway.service.DshGatewayClient;
import com.yuyutian.mytools.gateway.service.GatewayRouteDisabledException;
import com.yuyutian.mytools.gateway.service.GatewayUnauthorizedException;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 从可信主体管理 DSH 会话绑定。
 */
@Validated
@RestController
@RequestMapping("/api/app/v1/dsh/sessions")
public class DshGatewayController {
    private final DshGatewayProperties properties;
    private final DshGatewayClient client;

    /**
     * 创建 DSH Gateway 控制器。
     *
     * @param properties Gateway 配置
     * @param client DSH 客户端
     */
    public DshGatewayController(DshGatewayProperties properties, DshGatewayClient client) {
        this.properties = properties;
        this.client = client;
    }

    /**
     * 查询当前主体的会话绑定。
     *
     * @param request HTTP 请求
     * @return 会话绑定
     */
    @GetMapping
    public List<BindingView> list(HttpServletRequest request) {
        GatewayPrincipal principal = requirePrincipal(request);
        return client.list(principal.userId(), correlation(request));
    }

    /**
     * 归档当前主体的会话。
     *
     * @param sessionId 外部会话标识
     * @param request HTTP 请求
     */
    @DeleteMapping("/{sessionId}")
    public void archive(@PathVariable @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._:-]+") String sessionId,
                        HttpServletRequest request) {
        GatewayPrincipal principal = requirePrincipal(request);
        client.archive(sessionId, principal.userId(), correlation(request));
    }

    private GatewayPrincipal requirePrincipal(HttpServletRequest request) {
        Object value = request.getAttribute(GatewayRequestFilter.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof GatewayPrincipal principal)) {
            if (!properties.routeEnabled()) {
                throw new GatewayRouteDisabledException();
            }
            throw new GatewayUnauthorizedException();
        }
        return principal;
    }

    private String correlation(HttpServletRequest request) {
        return request.getAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE).toString();
    }
}
