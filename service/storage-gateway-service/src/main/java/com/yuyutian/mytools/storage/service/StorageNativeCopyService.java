package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.model.ErrorCode;
import com.yuyutian.mytools.storage.model.NativeWriteResult;
import com.yuyutian.mytools.storage.model.RemoteContent;
import com.yuyutian.mytools.storage.model.StorageOperation;
import com.yuyutian.mytools.storage.model.StorageProvider;
import com.yuyutian.mytools.storage.repository.StorageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 通过操作专属端点提供原生单对象复制原语。
 */
@Service
public class StorageNativeCopyService {
    private final StorageRepository repository;
    private final ProviderObjectConnectorRegistry connectorRegistry;
    private final long maximumBytes;

    /**
     * 创建原生复制服务。
     *
     * @param repository 存储仓储
     * @param connectorRegistry 连接器注册表
     * @param maximumBytes 单对象最大字节数
     */
    public StorageNativeCopyService(StorageRepository repository,
            ProviderObjectConnectorRegistry connectorRegistry,
            @Value("${storage.native-copy-maximum-bytes:21474836480}") long maximumBytes) {
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException(ErrorCode.REMOTE_CONTENT_TOO_LARGE.code());
        }
        this.repository = repository;
        this.connectorRegistry = connectorRegistry;
        this.maximumBytes = maximumBytes;
    }

    /**
     * 打开操作定义的来源对象。
     *
     * @param operationId 操作标识
     * @return 有界来源内容
     */
    public RemoteContent source(UUID operationId) {
        Context context = context(operationId);
        return connectorRegistry.openContent(context.source(), context.operation().sourcePath(), maximumBytes);
    }

    /**
     * 打开操作定义的目标对象用于复读校验。
     *
     * @param operationId 操作标识
     * @return 有界目标内容
     */
    public RemoteContent target(UUID operationId) {
        Context context = context(operationId);
        return connectorRegistry.openContent(context.target(), context.operation().targetPath(), maximumBytes);
    }

    /**
     * 将请求流写入操作定义的目标并校验精确长度和摘要。
     *
     * @param operationId 操作标识
     * @param content 内容流
     * @param contentLength 声明长度
     * @param expectedSha256 期望摘要
     * @return 写入结果
     */
    public NativeWriteResult writeTarget(UUID operationId, InputStream content, long contentLength,
                                         String expectedSha256) {
        if (contentLength < 0 || contentLength > maximumBytes
                || expectedSha256 == null || !expectedSha256.matches("^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException(ErrorCode.REMOTE_CONTENT_TOO_LARGE.code());
        }
        Context context = context(operationId);
        DigestInputStream digestInput = new DigestInputStream(
                new BoundedInputStream(content, contentLength), sha256());
        CountingInputStream counting = new CountingInputStream(digestInput);
        try {
            boolean created = connectorRegistry.writeContent(context.target(), context.operation().targetPath(),
                    counting, contentLength);
            if (created) {
                repository.markNativeTargetCreated(operationId);
            }
            if (created) {
                String actual = HexFormat.of().formatHex(digestInput.getMessageDigest().digest());
                if (counting.count != contentLength || !actual.equals(expectedSha256)) {
                    throw new IllegalStateException(ErrorCode.CONTENT_MISMATCH.code());
                }
            }
            return new NativeWriteResult(operationId, contentLength, expectedSha256, created);
        } catch (RuntimeException exception) {
            compensate(context);
            throw exception;
        }
    }

    /**
     * 删除操作定义的目标，供取消和失败步骤补偿。
     *
     * @param operationId 操作标识
     */
    public void deleteTarget(UUID operationId) {
        Context context = context(operationId);
        if (repository.ownsNativeTarget(operationId)) {
            connectorRegistry.deleteContent(context.target(), context.operation().targetPath());
            repository.clearNativeTargetCreated(operationId);
        }
    }

    private Context context(UUID operationId) {
        StorageOperation operation = repository.findOperationById(operationId)
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.OPERATION_NOT_FOUND.code()));
        if (!"COPY_OBJECT".equals(operation.operationType()) || !"RUNNING".equals(operation.status())
                || operation.sourcePath().isBlank() || operation.targetPath() == null
                || operation.targetPath().isBlank()) {
            throw new IllegalStateException(ErrorCode.OPERATION_STATE_INVALID.code());
        }
        StorageProvider source = provider(operation.providerId());
        StorageProvider target = provider(operation.targetProviderId());
        return new Context(operation, source, target);
    }

    private StorageProvider provider(UUID id) {
        return repository.findProviderById(id).filter(StorageProvider::enabled)
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.PROVIDER_NOT_FOUND.code()));
    }

    private void compensate(Context context) {
        try {
            if (repository.ownsNativeTarget(context.operation().id())) {
                connectorRegistry.deleteContent(context.target(), context.operation().targetPath());
                repository.clearNativeTargetCreated(context.operation().id());
            }
        } catch (RuntimeException ignored) {
            // 原始失败保持主因，特殊步骤会再次执行幂等补偿。
        }
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(ErrorCode.IO_FAILURE.code(), exception);
        }
    }

    private record Context(StorageOperation operation, StorageProvider source, StorageProvider target) {
    }

    private static final class CountingInputStream extends java.io.FilterInputStream {
        private long count;

        private CountingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }
    }
}
