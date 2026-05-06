package com.yuyutian.mytools.wechat.account.controller;

import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.wechat.account.model.WechatAccount;

import java.util.List;
import com.yuyutian.mytools.wechat.account.model.dto.AccountListResponse;
import com.yuyutian.mytools.wechat.account.model.dto.CreateAccountRequest;
import com.yuyutian.mytools.wechat.account.model.dto.RefreshResultResponse;
import com.yuyutian.mytools.wechat.account.model.dto.UpdateAccountRequest;
import com.yuyutian.mytools.wechat.account.service.WechatAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 微信账号管理 Controller
 */
@RestController
@RequestMapping("/api/wechat/accounts")
@RequiredArgsConstructor
public class WechatAccountController {

    private final WechatAccountService accountService;

    /**
     * 获取所有正常账号（用于下拉选择）
     */
    @GetMapping("/normal")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<List<WechatAccount>>> getNormalAccounts() {
        return ResponseEntity.ok(Result.success(accountService.getNormalAccounts()));
    }

    /**
     * 分页获取账号列表
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<AccountListResponse>> getAccountPage(
            @RequestParam Long page,
            @RequestParam Long pageSize) {
        return ResponseEntity.ok(Result.success(accountService.getAccountPage(page, pageSize)));
    }

    /**
     * 创建账号
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<WechatAccount>> createAccount(@RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.success("创建成功", accountService.createAccount(request)));
    }

    /**
     * 更新账号
     */
    @PutMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Void>> updateAccount(@RequestBody UpdateAccountRequest request) {
        accountService.updateAccount(request);
        return ResponseEntity.ok(Result.success("更新成功"));
    }

    /**
     * 更新账号状态
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Void>> updateStatus(
            @PathVariable Long id,
            @RequestParam Integer status) {
        accountService.updateStatus(id, status);
        return ResponseEntity.ok(Result.success("状态更新成功"));
    }

    /**
     * 删除账号
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<Void>> deleteAccount(@PathVariable Long id) {
        accountService.deleteAccount(id);
        return ResponseEntity.ok(Result.success("删除成功"));
    }

    /**
     * 手动刷新账号下所有任务状态
     */
    @PutMapping("/{id}/refresh")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Result<RefreshResultResponse>> refreshAccountTasks(@PathVariable Long id) {
        RefreshResultResponse result = accountService.refreshAccountTasks(id);
        return ResponseEntity.ok(Result.success("刷新成功", result));
    }
}