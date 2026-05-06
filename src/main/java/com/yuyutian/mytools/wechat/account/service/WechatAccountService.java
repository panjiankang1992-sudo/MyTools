package com.yuyutian.mytools.wechat.account.service;

import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.wechat.account.mapper.WechatAccountMapper;
import com.yuyutian.mytools.wechat.account.model.AccountStatus;
import com.yuyutian.mytools.wechat.account.model.RefreshFrequency;
import com.yuyutian.mytools.wechat.account.model.WechatAccount;
import com.yuyutian.mytools.wechat.account.model.dto.AccountListResponse;
import com.yuyutian.mytools.wechat.account.model.dto.CreateAccountRequest;
import com.yuyutian.mytools.wechat.account.model.dto.RefreshResultResponse;
import com.yuyutian.mytools.wechat.account.model.dto.UpdateAccountRequest;
import com.yuyutian.mytools.wechat.moments.mapper.MomentsTaskMapper;
import com.yuyutian.mytools.wechat.moments.mapper.RefreshLogMapper;
import com.yuyutian.mytools.wechat.moments.model.MomentsTask;
import com.yuyutian.mytools.wechat.moments.model.OperateType;
import com.yuyutian.mytools.wechat.moments.model.RefreshLog;
import com.yuyutian.mytools.wechat.moments.model.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 微信账号服务层
 */
@Service
@RequiredArgsConstructor
public class WechatAccountService {

    private final WechatAccountMapper accountMapper;
    private final MomentsTaskMapper taskMapper;
    private final RefreshLogMapper refreshLogMapper;

    /**
     * 获取所有正常账号（用于下拉选择）
     */
    public List<WechatAccount> getNormalAccounts() {
        return accountMapper.selectByStatus(AccountStatus.NORMAL.getCode());
    }

    /**
     * 分页获取账号列表
     */
    public AccountListResponse getAccountPage(Long page, Long pageSize) {
        Long offset = (page - 1) * pageSize;
        List<WechatAccount> list = accountMapper.selectPage(offset, pageSize);
        Long total = accountMapper.count();
        AccountListResponse response = new AccountListResponse();
        response.setList(list);
        response.setTotal(total);
        return response;
    }

    /**
     * 创建账号
     */
    public WechatAccount createAccount(CreateAccountRequest request) {
        // 检查微信ID是否已存在
        WechatAccount existing = accountMapper.selectByWechatId(request.getWechatId());
        if (existing != null) {
            throw new ServiceException(ErrorCode.ACCOUNT_001);
        }
        WechatAccount account = new WechatAccount();
        account.setWechatId(request.getWechatId());
        account.setNickname(request.getNickname());
        account.setRemark(request.getRemark());
        account.setStatus(AccountStatus.NORMAL.getCode());
        account.setRefreshFrequency(RefreshFrequency.TWICE_DAILY.getCode()); // 默认每天两次
        accountMapper.insert(account);
        return account;
    }

    /**
     * 更新账号
     */
    public void updateAccount(UpdateAccountRequest request) {
        WechatAccount account = accountMapper.selectById(request.getId());
        if (account == null) {
            throw new ServiceException(ErrorCode.ACCOUNT_002);
        }
        if (request.getNickname() != null) {
            account.setNickname(request.getNickname());
        }
        if (request.getRemark() != null) {
            account.setRemark(request.getRemark());
        }
        // 更新刷新频率
        if (request.getRefreshFrequency() != null) {
            if (!RefreshFrequency.ONCE_DAILY.getCode().equals(request.getRefreshFrequency())
                && !RefreshFrequency.TWICE_DAILY.getCode().equals(request.getRefreshFrequency())) {
                throw new ServiceException(ErrorCode.ACCOUNT_004);
            }
            account.setRefreshFrequency(request.getRefreshFrequency());
        }
        accountMapper.update(account);
    }

    /**
     * 手动刷新账号下所有任务状态
     */
    public RefreshResultResponse refreshAccountTasks(Long accountId) {
        WechatAccount account = accountMapper.selectById(accountId);
        if (account == null) {
            throw new ServiceException(ErrorCode.ACCOUNT_002);
        }
        if (AccountStatus.DISABLED.getCode().equals(account.getStatus())) {
            throw new ServiceException(ErrorCode.ACCOUNT_005);
        }
        // 查询该账号下所有非删除状态的任务
        List<MomentsTask> tasks = taskMapper.selectByAccountId(accountId, TaskStatus.DELETED.getCode());
        int successCount = 0;
        int failCount = 0;
        for (MomentsTask task : tasks) {
            // 跳过已删除的任务
            if (TaskStatus.DELETED.getCode().equals(task.getStatus())) {
                continue;
            }
            // 只重启已执行的任务（status=3），已失效(status=4)的任务不操作
            if (!TaskStatus.SUCCESS.getCode().equals(task.getStatus())) {
                continue;
            }
            try {
                // 重启任务：将状态重置为"待执行"
                Integer newStatus = TaskStatus.PENDING.getCode();
                taskMapper.updateStatus(task.getId(), newStatus);
                successCount++;
            } catch (Exception e) {
                failCount++;
            }
        }
        // 记录刷新日志
        RefreshLog log = new RefreshLog();
        log.setAccountId(accountId);
        log.setOperateType(OperateType.MANUAL.getCode());
        log.setSuccessCount(successCount);
        log.setFailCount(failCount);
        log.setOperateTime(LocalDateTime.now());
        refreshLogMapper.insert(log);
        // 构建响应
        RefreshResultResponse response = new RefreshResultResponse();
        response.setSuccessCount(successCount);
        response.setFailCount(failCount);
        response.setTotalCount(successCount + failCount);
        return response;
    }

    /**
     * 刷新指定刷新频率的所有账号下的任务
     */
    public void refreshAccountsWithFrequency(Integer refreshFrequency) {
        List<WechatAccount> accounts = accountMapper.selectByRefreshFrequency(refreshFrequency);
        for (WechatAccount account : accounts) {
            try {
                refreshAccountTasksInternal(account.getId(), OperateType.AUTOMATIC);
            } catch (Exception e) {
                // 记录异常但继续处理其他账号
            }
        }
    }

    /**
     * 内部方法：刷新账号下所有任务（供定时任务调用）
     */
    private void refreshAccountTasksInternal(Long accountId, OperateType operateType) {
        WechatAccount account = accountMapper.selectById(accountId);
        if (account == null || AccountStatus.DISABLED.getCode().equals(account.getStatus())) {
            return;
        }
        List<MomentsTask> tasks = taskMapper.selectByAccountId(accountId, TaskStatus.DELETED.getCode());
        int successCount = 0;
        int failCount = 0;
        for (MomentsTask task : tasks) {
            if (TaskStatus.DELETED.getCode().equals(task.getStatus())) {
                continue;
            }
            try {
                Integer newStatus = simulateWechatApiCall();
                taskMapper.updateStatus(task.getId(), newStatus);
                successCount++;
            } catch (Exception e) {
                failCount++;
            }
        }
        // 记录刷新日志
        RefreshLog log = new RefreshLog();
        log.setAccountId(accountId);
        log.setOperateType(operateType.getCode());
        log.setSuccessCount(successCount);
        log.setFailCount(failCount);
        log.setOperateTime(LocalDateTime.now());
        refreshLogMapper.insert(log);
    }

    /**
     * 模拟微信API调用
     * 实际项目中需要调用真实的微信API
     */
    private Integer simulateWechatApiCall() {
        return TaskStatus.SUCCESS.getCode();
    }

    /**
     * 更新账号状态
     */
    public void updateStatus(Long id, Integer status) {
        WechatAccount account = accountMapper.selectById(id);
        if (account == null) {
            throw new ServiceException(ErrorCode.ACCOUNT_002);
        }
        accountMapper.updateStatus(id, status);
    }

    /**
     * 删除账号
     */
    public void deleteAccount(Long id) {
        WechatAccount account = accountMapper.selectById(id);
        if (account == null) {
            throw new ServiceException(ErrorCode.ACCOUNT_002);
        }
        accountMapper.deleteById(id);
    }

    /**
     * 业务异常类
     */
    public static class ServiceException extends RuntimeException {
        private final ErrorCode errorCode;
        public ServiceException(ErrorCode errorCode) {
            super(errorCode.getMessage());
            this.errorCode = errorCode;
        }
        public ErrorCode getErrorCode() {
            return errorCode;
        }
    }
}