package com.yuyutian.mytools.appmarket.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yuyutian.mytools.appmarket.dto.*;
import com.yuyutian.mytools.appmarket.entity.AppMarket;
import com.yuyutian.mytools.appmarket.entity.AppVersion;
import com.yuyutian.mytools.appmarket.service.AppMarketService;
import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 应用市场 Controller。
 *
 * @author mytools
 * @since 2026-05-16
 */
@Slf4j
@RestController
@RequestMapping("/api/market/apps")
@RequiredArgsConstructor
public class AppMarketController {

    private final AppMarketService appMarketService;
    private final JwtUtils jwtUtils;

    /**
     * 分页获取应用列表。
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<IPage<AppMarketListResponse>>> listApps(
            @RequestParam int page,
            @RequestParam int pageSize,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String name) {
        IPage<AppMarketListResponse> result = appMarketService.listApps(type, name, page, pageSize);
        return ResponseEntity.ok(Result.success(result));
    }

    /**
     * 获取应用详情。
     */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<AppMarketDetailResponse>> getAppDetail(
            @PathVariable String id,
            @RequestHeader("Authorization") String authHeader) {
        Long userId = jwtUtils.getUserIdFromToken(extractToken(authHeader));
        AppMarketDetailResponse detail = appMarketService.getAppDetail(id, userId);
        return ResponseEntity.ok(Result.success(detail));
    }

    /**
     * 上架新应用。
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<AppMarket>> createApp(
            @RequestPart("data") @Valid AppMarketCreateRequest request,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestPart(value = "thumbnail", required = false) MultipartFile thumbnail,
            @RequestHeader("Authorization") String authHeader) throws IOException {
        Long userId = jwtUtils.getUserIdFromToken(extractToken(authHeader));
        AppMarket app = appMarketService.createApp(request, file, thumbnail, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.success("上架成功", app));
    }

    /**
     * 编辑应用（自动保存历史版本）。
     */
    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<AppMarket>> updateApp(
            @PathVariable String id,
            @RequestPart("data") @Valid AppMarketUpdateRequest request,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestPart(value = "thumbnail", required = false) MultipartFile thumbnail,
            @RequestHeader("Authorization") String authHeader) throws IOException {
        Long userId = jwtUtils.getUserIdFromToken(extractToken(authHeader));
        AppMarket app = appMarketService.updateApp(id, request, file, thumbnail, userId);
        return ResponseEntity.ok(Result.success("更新成功", app));
    }

    /**
     * 删除应用（含文件清理）。
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Void>> deleteApp(
            @PathVariable String id,
            @RequestHeader("Authorization") String authHeader) {
        Long userId = jwtUtils.getUserIdFromToken(extractToken(authHeader));
        appMarketService.deleteApp(id, userId);
        return ResponseEntity.ok(Result.success("删除成功", null));
    }

    /**
     * 下架应用。
     */
    @PutMapping("/{id}/offline")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Void>> offlineApp(
            @PathVariable String id,
            @RequestHeader("Authorization") String authHeader) {
        Long userId = jwtUtils.getUserIdFromToken(extractToken(authHeader));
        appMarketService.offlineApp(id, userId);
        return ResponseEntity.ok(Result.success("下架成功", null));
    }

    /**
     * 获取历史版本列表。
     */
    @GetMapping("/{id}/versions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<java.util.List<AppVersion>>> getVersions(@PathVariable String id) {
        java.util.List<AppVersion> versions = appMarketService.getVersions(id);
        return ResponseEntity.ok(Result.success(versions));
    }

    /**
     * 获取某版本详情。
     */
    @GetMapping("/{id}/versions/{vid}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<AppVersion>> getVersionDetail(
            @PathVariable String id,
            @PathVariable String vid) {
        AppVersion version = appMarketService.getVersionDetail(id, vid);
        return ResponseEntity.ok(Result.success(version));
    }

    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return authHeader;
    }
}
