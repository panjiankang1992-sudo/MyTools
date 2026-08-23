package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.CreateHealthCheckRequest;
import com.yuyutian.mytools.reader.model.HealthCheckView;
import com.yuyutian.mytools.reader.service.SourceHealthCheckService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 书源健康检查编排接口。
 */
@RestController
@RequestMapping("/api/v1/source-health-checks")
public class SourceHealthCheckController {

    private final SourceHealthCheckService healthCheckService;

    /**
     * 创建书源健康检查控制器。
     *
     * @param healthCheckService 健康检查服务
     */
    public SourceHealthCheckController(SourceHealthCheckService healthCheckService) {
        this.healthCheckService = healthCheckService;
    }

    /**
     * 创建异步健康检查。
     *
     * @param request 创建请求
     * @return 已受理检查
     */
    @PostMapping
    public ResponseEntity<HealthCheckView> create(@Valid @RequestBody CreateHealthCheckRequest request) {
        return ResponseEntity.accepted().body(healthCheckService.create(request));
    }

    /**
     * 查询并汇总健康检查。
     *
     * @param id 请求标识
     * @return 检查视图
     */
    @GetMapping("/{id}")
    public HealthCheckView get(@PathVariable UUID id) {
        return healthCheckService.get(id);
    }

    /**
     * 取消健康检查。
     *
     * @param id 请求标识
     * @return 检查视图
     */
    @PostMapping("/{id}/cancel")
    public HealthCheckView cancel(@PathVariable UUID id) {
        return healthCheckService.cancel(id);
    }
}
