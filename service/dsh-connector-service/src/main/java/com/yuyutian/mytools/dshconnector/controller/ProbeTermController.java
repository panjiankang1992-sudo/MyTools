package com.yuyutian.mytools.dshconnector.controller;

import com.yuyutian.mytools.dshconnector.model.ProbeTermModels;
import com.yuyutian.mytools.dshconnector.service.ProbeTermService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 仅供任务执行器调用的 DSH 探测词接口。 */
@RestController
@RequestMapping("/internal/v1/dsh/probe-terms")
public class ProbeTermController {
    private final ProbeTermService service;
    private final String token;

    /** 创建探测词控制器。 */
    public ProbeTermController(ProbeTermService service,
                               @Value("${dsh-connector.internal-token}") String token) {
        this.service = service;
        this.token = token;
    }

    /** 分析自然语言图书线索。 */
    @PostMapping
    public ProbeTermModels.Result analyze(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody ProbeTermModels.Request request) {
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        byte[] actual = (authorization == null ? "" : authorization).getBytes(StandardCharsets.UTF_8);
        if (token.isBlank() || !MessageDigest.isEqual(expected, actual)) {
            throw new SecurityException("unauthorized");
        }
        return service.analyze(request);
    }
}
