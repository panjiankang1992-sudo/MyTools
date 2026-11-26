package com.yuyutian.mytools.toolbox.service.admin.curriculum;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.common.BusinessException;
import com.yuyutian.mytools.toolbox.common.ErrorCode;
import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理写操作的幂等回执（设计文档 §5.7、§6）。
 *
 * 规则：
 * - 相同键 + **完全相同**的冻结载荷 → 返回原结果，**即使 `If-Match` 已旧**；
 * - 相同键 + **不同**载荷 → 409，不能把两次不同意图的请求当成一次；
 * - 每次重试**仍然先经过权限校验**（过滤层在控制器之前执行，回执不会绕过鉴权）。
 */
@Service
public class AdminIdempotencyService {

    private final AdminTextbookRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * 创建幂等服务。
     *
     * @param repository 教材仓储
     * @param objectMapper JSON 序列化器
     */
    public AdminIdempotencyService(AdminTextbookRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 以幂等方式执行一次写操作。
     *
     * @param actor  操作者用户标识
     * @param scope  操作范围，如 `textbook.create`／`draft.save`／`textbook.publish`
     * @param key    幂等键；为空时直接执行（由控制器决定是否要求该头）
     * @param payload 参与摘要的冻结载荷
     * @param resultType 结果类型，用于反序列化既有回执
     * @param action 实际执行体
     * @param <T>    结果类型
     * @return 本次结果或既有回执
     *
     * **整段在一个事务里**（F07）。此前 `action` 先提交、回执**另写一次**：
     * 回执写失败时客户端看到失败，**但业务已经落库**——
     * 于是业务状态变了、回执不存在，用同一个键重试会得到
     * `TOOLBOX_ADMIN_004`（键被复用于不同载荷），**永远补不回来**。
     * 持久化探针正是这样复现的：
     *
     * ```
     * REVISION_AFTER_FAILED_RECEIPT=4
     * RECEIPT_EXISTS=false
     * IDENTICAL_RETRY_ERROR=TOOLBOX_ADMIN_004
     * ```
     *
     * 加上 `@Transactional` 之后，回执写失败会让**业务一起回滚**，
     * 客户端可以放心用同一个键重试，且重试时业务尚未发生。
     */
    @Transactional
    public <T> T execute(String actor, String scope, String key, Object payload,
                         Class<T> resultType, Supplier<T> action) {
        if (key == null || key.isBlank()) {
            return action.get();
        }
        String digest = digestOf(payload);
        Optional<Map<String, Object>> existing = repository.findOperation(actor, scope, key);
        if (existing.isPresent()) {
            Map<String, Object> receipt = existing.get();
            String storedDigest = String.valueOf(receipt.get("request_digest"));
            if (!MessageDigest.isEqual(storedDigest.getBytes(StandardCharsets.UTF_8),
                    digest.getBytes(StandardCharsets.UTF_8))) {
                // 同一个键被用于不同载荷：这是两次不同意图的请求，不能复用回执。
                throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_004, Map.of("reason", "idempotencyKeyReused"));
            }
            try {
                return objectMapper.readValue(String.valueOf(receipt.get("result_json")), resultType);
            } catch (Exception failure) {
                throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_009, Map.of("reason", "receiptUnreadable"));
            }
        }
        T produced = action.get();
        try {
            repository.recordOperation(actor, scope, key, digest, objectMapper.writeValueAsString(produced));
        } catch (Exception failure) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_009, Map.of("reason", "receiptWriteFailed"));
        }
        return produced;
    }

    /**
     * 计算载荷摘要。
     *
     * @param payload 冻结载荷
     * @return SHA-256 十六进制摘要
     */
    private String digestOf(Object payload) {
        try {
            byte[] normalized = objectMapper.writeValueAsBytes(payload);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(normalized);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception failure) {
            throw new BusinessException(ErrorCode.TOOLBOX_ADMIN_005, Map.of("reason", "payloadUnserializable"));
        }
    }
}
