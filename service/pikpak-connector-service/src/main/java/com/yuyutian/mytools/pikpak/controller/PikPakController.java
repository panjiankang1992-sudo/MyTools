package com.yuyutian.mytools.pikpak.controller;

import static com.yuyutian.mytools.pikpak.model.PikPakModels.*;

import com.yuyutian.mytools.pikpak.service.InternalAuthorizer;
import com.yuyutian.mytools.pikpak.service.PikPakOperationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** PikPak 账户和离线操作内部接口。 */
@RestController
@RequestMapping("/api/internal/v1/pikpak")
public class PikPakController {
    private final PikPakOperationService service;
    private final InternalAuthorizer authorizer;

    /** 创建控制器。 @param service 操作服务 @param authorizer 鉴权器 */
    public PikPakController(PikPakOperationService service, InternalAuthorizer authorizer) {
        this.service = service;
        this.authorizer = authorizer;
    }

    /** 登记账户。 @param authorization 授权头 @param request 请求 @return 账户 */
    @PostMapping("/accounts")
    public AccountView register(@RequestHeader("Authorization") String authorization,
                            @Valid @RequestBody RegisterAccountRequest request) {
        authorizer.require(authorization);
        return service.registerAccount(request);
    }

    /** 创建离线操作。 @param authorization 授权头 @param request 请求 @return 操作 */
    @PostMapping("/operations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OperationView create(@RequestHeader("Authorization") String authorization,
                                @Valid @RequestBody CreateOperationRequest request) {
        authorizer.require(authorization);
        return service.create(request);
    }

    /** 推进离线操作。 @param id 操作标识 @param authorization 授权头 @param request 推进请求 @return 操作 */
    @PostMapping("/operations/{id}/advance")
    public OperationView advance(@PathVariable UUID id,
        @RequestHeader("Authorization") String authorization,
        @RequestBody(required=false) AdvanceRequest request) {
        authorizer.require(authorization);
        return service.advance(id, request == null ? null : request.magnetUri());
    }

    /** 查询离线操作。 @param id 操作标识 @param authorization 授权头 @return 操作 */
    @GetMapping("/operations/{id}")
    public OperationView get(@PathVariable UUID id,
        @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        return service.get(id);
    }

    /** 取消离线操作。 @param id 操作标识 @param authorization 授权头 @return 操作 */
    @PostMapping("/operations/{id}/cancel")
    public OperationView cancel(@PathVariable UUID id,
        @RequestHeader("Authorization") String authorization) {
        authorizer.require(authorization);
        return service.cancel(id);
    }

    /** 首次推进时携带但不持久化的 magnet URI。 */
    public record AdvanceRequest(@Size(max=8192) String magnetUri) { }
}
