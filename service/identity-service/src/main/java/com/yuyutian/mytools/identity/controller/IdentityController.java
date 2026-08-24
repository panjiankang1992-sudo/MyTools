package com.yuyutian.mytools.identity.controller;
import com.yuyutian.mytools.identity.config.IdentityProperties;
import com.yuyutian.mytools.identity.model.IdentityModels.*;
import com.yuyutian.mytools.identity.service.IdentityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
/** Identity HTTP API。 */
@RestController
public class IdentityController {
 private final IdentityService service; private final IdentityProperties properties;
 /** 创建控制器。 @param service 服务 @param properties 配置 */ public IdentityController(IdentityService service,IdentityProperties properties){this.service=service;this.properties=properties;}
 /** 登录。 @param request 请求 @return Token */ @PostMapping("/api/v1/identity/login") public TokenPair login(@Valid @RequestBody LoginRequest request){return service.login(request);}
 /** 刷新并轮换令牌。 @param request 请求 @return Token */ @PostMapping("/api/v1/identity/refresh") public TokenPair refresh(@Valid @RequestBody RefreshRequest request){return service.refresh(request);}
 /** 导入用户。 @param authorization 内部授权 @param request 请求 @return 用户 */ @PostMapping("/internal/v1/identity/users") @ResponseStatus(HttpStatus.CREATED)
 public UserView importUser(@RequestHeader("Authorization") String authorization,@Valid @RequestBody ImportUserRequest request){authorize(authorization);return service.importUser(request);}
 /** 批量校验或导入旧用户。 @param authorization 内部授权 @param batch 迁移批次 @return 迁移结果 */ @PostMapping("/internal/v1/migrations/legacy-users")
 public LegacyUserMigrationResult migrateUsers(@RequestHeader("Authorization") String authorization,@Valid @RequestBody LegacyUserMigrationBatch batch){authorize(authorization);return service.migrateUsers(batch);}
 /** 查询目标侧用户迁移证据。 @param authorization 内部授权 @param migrationKey 迁移键 @return 集合证据 */ @GetMapping("/internal/v1/migrations/legacy-users/{migrationKey}/reconciliation")
 public LegacyUserReconciliation reconcileUsers(@RequestHeader("Authorization") String authorization,@PathVariable String migrationKey){authorize(authorization);return service.reconcileUsers(migrationKey);}
 /** 校验令牌及会话。 @param authorization 内部授权 @param request 请求 @return 主体 */ @PostMapping("/internal/v1/identity/tokens/validate")
 public PrincipalView validate(@RequestHeader("Authorization") String authorization,@Valid @RequestBody ValidateRequest request){authorize(authorization);return service.validate(request);}
 /** 撤销会话。 @param authorization 内部授权 @param id 会话 @param reason 原因 */ @PostMapping("/internal/v1/identity/sessions/{id}/revoke") @ResponseStatus(HttpStatus.NO_CONTENT)
 public void revoke(@RequestHeader("Authorization") String authorization,@PathVariable UUID id,@RequestParam(defaultValue="ADMIN_REVOKE") String reason){authorize(authorization);if(!reason.matches("^[A-Z][A-Z0-9_]{0,63}$"))throw new IllegalArgumentException("identity revoke reason is invalid");service.revoke(id,reason);}
 private void authorize(String authorization){byte[] expected=("Bearer "+properties.internalToken()).getBytes(StandardCharsets.UTF_8);if(properties.internalToken().isBlank()||!MessageDigest.isEqual(expected,authorization.getBytes(StandardCharsets.UTF_8)))throw new SecurityException("identity internal authorization failed");}
}
