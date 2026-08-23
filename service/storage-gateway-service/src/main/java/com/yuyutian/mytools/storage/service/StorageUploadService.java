package com.yuyutian.mytools.storage.service;

import com.yuyutian.mytools.storage.config.StorageProperties;
import com.yuyutian.mytools.storage.model.CreateUploadRequest;
import com.yuyutian.mytools.storage.model.ErrorCode;
import com.yuyutian.mytools.storage.model.UploadRecord;
import com.yuyutian.mytools.storage.model.UploadView;
import com.yuyutian.mytools.storage.repository.StorageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 受管根内流式校验和原子发布服务。
 */
@Service
public class StorageUploadService {

    private final StorageRepository repository;
    private final StorageProperties properties;

    /**
     * 创建存储上传服务。
     *
     * @param repository 存储仓储
     * @param properties 存储配置
     */
    public StorageUploadService(StorageRepository repository, StorageProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * 幂等创建受控上传会话。
     *
     * @param request 创建请求
     * @return 上传视图
     */
    @Transactional
    public UploadView create(CreateUploadRequest request) {
        UploadRecord existing = repository.findByIdempotencyKey(request.idempotencyKey()).orElse(null);
        if (existing != null) {
            if (!existing.rootName().equals(request.rootName())
                    || !existing.relativePath().equals(request.relativePath())
                    || existing.expectedSize() != request.expectedSize()) {
                throw new IllegalArgumentException(ErrorCode.IDEMPOTENCY_CONFLICT.code());
            }
            return view(existing);
        }
        if (request.expectedSize() > properties.maximumUploadBytes()) {
            throw new IllegalArgumentException(ErrorCode.UPLOAD_TOO_LARGE.code());
        }
        var root = repository.findRoot(request.rootName())
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.ROOT_NOT_FOUND.code()));
        String relativePath = safeRelativePath(request.relativePath());
        Instant now = Instant.now();
        UploadRecord record = new UploadRecord(UUID.randomUUID(), root.id(), root.name(), root.basePath(),
                request.idempotencyKey(), relativePath, request.expectedSize(), normalizedHash(request.expectedSha256()),
                null, null, "CREATED", null, null, now, now);
        repository.insertUpload(record);
        return view(record);
    }

    /**
     * 流式接收、校验并原子发布上传内容。
     *
     * @param uploadId 上传标识
     * @param input 输入流
     * @return 完成后的上传视图
     */
    public UploadView upload(UUID uploadId, InputStream input) {
        UploadRecord record = required(uploadId);
        if ("SUCCEEDED".equals(record.status())) {
            return view(record);
        }
        Path root = Path.of(record.basePath()).toAbsolutePath().normalize();
        Path staging = root.resolve(".mytools-staging").normalize();
        Path temporary = staging.resolve(uploadId + ".part").normalize();
        Path target = root.resolve(record.relativePath()).normalize();
        if (!temporary.startsWith(staging) || !target.startsWith(root)) {
            throw new IllegalArgumentException(ErrorCode.PATH_INVALID.code());
        }
        try {
            ensureSafeDirectory(root, staging);
            ensureSafeDirectory(root, target.getParent());
            if (Files.isSymbolicLink(target)) {
                throw new UploadValidationException(ErrorCode.PATH_INVALID.code());
            }
            if (!repository.claim(uploadId, temporary.toString())) {
                throw new IllegalStateException(ErrorCode.UPLOAD_CONFLICT.code());
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = copyBounded(input, temporary, digest, record.expectedSize());
            String sha256 = HexFormat.of().formatHex(digest.digest());
            if (size != record.expectedSize() || record.expectedSha256() != null
                    && !record.expectedSha256().equals(sha256)) {
                throw new UploadValidationException(ErrorCode.CONTENT_MISMATCH.code());
            }
            publish(temporary, target, size, sha256);
            repository.succeed(uploadId, size, sha256, target.toString());
            return view(required(uploadId));
        } catch (UploadValidationException exception) {
            deleteQuietly(temporary);
            repository.fail(uploadId, exception.getMessage());
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            deleteQuietly(temporary);
            repository.fail(uploadId, ErrorCode.IO_FAILURE.code());
            throw new IllegalStateException(ErrorCode.IO_FAILURE.code(), exception);
        }
    }

    /**
     * 查询上传状态。
     *
     * @param uploadId 上传标识
     * @return 上传视图
     */
    public UploadView get(UUID uploadId) {
        return view(required(uploadId));
    }

    private long copyBounded(InputStream input, Path temporary, MessageDigest digest, long expectedSize)
            throws IOException {
        long count = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream file = Files.newOutputStream(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
             DigestOutputStream output = new DigestOutputStream(file, digest)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                count += read;
                if (count > expectedSize || count > properties.maximumUploadBytes()) {
                    throw new UploadValidationException(ErrorCode.UPLOAD_TOO_LARGE.code());
                }
                output.write(buffer, 0, read);
            }
        }
        return count;
    }

    private void publish(Path temporary, Path target, long size, String sha256) throws IOException {
        if (Files.exists(target)) {
            if (Files.size(target) == size && sha256(target).equals(sha256)) {
                Files.deleteIfExists(temporary);
                return;
            }
            throw new UploadValidationException(ErrorCode.TARGET_CONFLICT.code());
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            // 临时文件和目标始终位于同一受管根，回退移动仍不会跨文件系统。
            Files.move(temporary, target);
        }
    }

    private String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            input.transferTo(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String safeRelativePath(String value) {
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || path.getNameCount() == 0 || path.startsWith("..")
                || path.getName(0).toString().equals(".mytools-staging")) {
            throw new IllegalArgumentException(ErrorCode.PATH_INVALID.code());
        }
        return path.toString().replace('\\', '/');
    }

    private void ensureSafeDirectory(Path root, Path directory) throws IOException {
        Path realRoot = root.toRealPath();
        if (!directory.startsWith(root)) {
            throw new UploadValidationException(ErrorCode.PATH_INVALID.code());
        }
        Path current = root;
        for (Path segment : root.relativize(directory)) {
            current = current.resolve(segment);
            if (Files.exists(current) && Files.isSymbolicLink(current)) {
                throw new UploadValidationException(ErrorCode.PATH_INVALID.code());
            }
            Files.createDirectories(current);
        }
        if (!directory.toRealPath().startsWith(realRoot)) {
            throw new UploadValidationException(ErrorCode.PATH_INVALID.code());
        }
    }

    private String normalizedHash(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(ErrorCode.CONTENT_MISMATCH.code());
        }
        return normalized;
    }

    private UploadRecord required(UUID uploadId) {
        return repository.findById(uploadId)
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.UPLOAD_NOT_FOUND.code()));
    }

    private UploadView view(UploadRecord record) {
        String uri = "SUCCEEDED".equals(record.status())
                ? "storage://" + record.rootName() + "/" + record.relativePath() : null;
        return new UploadView(record.id(), record.rootName(), record.relativePath(), record.status(),
                record.expectedSize(), record.actualSize(), record.actualSha256(), uri,
                record.createdAt(), record.updatedAt());
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 失败临时文件由后续清理任务回收。
        }
    }

    private static final class UploadValidationException extends RuntimeException {
        private UploadValidationException(String message) {
            super(message);
        }
    }
}
