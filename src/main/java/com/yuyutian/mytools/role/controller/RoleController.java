package com.yuyutian.mytools.role.controller;

import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.role.model.RoleRequest;
import com.yuyutian.mytools.role.model.RoleResponse;
import com.yuyutian.mytools.role.service.RoleService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 角色控制器。
 *
 * @author mytools
 * @since 2026-04-27
 */
@Slf4j
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final RoleService roleService;
    private final JwtUtils jwtUtils;

    @Autowired
    public RoleController(RoleService roleService, JwtUtils jwtUtils) {
        this.roleService = roleService;
        this.jwtUtils = jwtUtils;
    }

    /**
     * 获取角色列表。
     *
     * @param authHeader Authorization头
     * @return 角色列表
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<List<RoleResponse>>> getRoleList(
            @RequestHeader("Authorization") String authHeader) {
        extractUserIdFromToken(authHeader);
        List<RoleResponse> roles = roleService.getRoleList();
        return ResponseEntity.ok(Result.success(roles));
    }

    /**
     * 创建角色（管理员）。
     *
     * @param request 创建角色请求
     * @param authHeader Authorization头
     * @return 角色响应
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<RoleResponse>> createRole(
            @Valid @RequestBody RoleRequest request,
            @RequestHeader("Authorization") String authHeader) {
        Long adminUserId = extractUserIdFromToken(authHeader);
        RoleResponse response = roleService.createRole(request, adminUserId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.success("创建成功", response));
    }

    /**
     * 更新角色（管理员）。
     *
     * @param id 角色ID
     * @param request 更新角色请求
     * @param authHeader Authorization头
     * @return 角色响应
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<RoleResponse>> updateRole(
            @PathVariable("id") Long id,
            @Valid @RequestBody RoleRequest request,
            @RequestHeader("Authorization") String authHeader) {
        Long adminUserId = extractUserIdFromToken(authHeader);
        RoleResponse response = roleService.updateRole(id, request, adminUserId);
        return ResponseEntity.ok(Result.success("更新成功", response));
    }

    /**
     * 删除角色（管理员）。
     *
     * @param id 角色ID
     * @param authHeader Authorization头
     * @return 成功响应
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<Void>> deleteRole(
            @PathVariable("id") Long id,
            @RequestHeader("Authorization") String authHeader) {
        Long adminUserId = extractUserIdFromToken(authHeader);
        roleService.deleteRole(id, adminUserId);
        return ResponseEntity.ok(Result.success("删除成功", null));
    }

    /**
     * 从Authorization头中提取用户ID。
     */
    private Long extractUserIdFromToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            return jwtUtils.getUserIdFromToken(token);
        }
        throw new BusinessException(ErrorCode.AUTH_002);
    }
}
