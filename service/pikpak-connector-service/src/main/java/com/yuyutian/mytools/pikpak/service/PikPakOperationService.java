package com.yuyutian.mytools.pikpak.service;

import static com.yuyutian.mytools.pikpak.model.PikPakModels.*;
import static com.yuyutian.mytools.pikpak.common.ErrorCode.*;

import com.yuyutian.mytools.pikpak.repository.PikPakRepository;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PikPak 离线操作应用服务与可恢复状态机。 */
@Service
public class PikPakOperationService {
    private static final Pattern INFO_HASH = Pattern.compile(
        "(?:^|&)xt=(?:urn:btih:|urn%3Abtih%3A)(?:[0-9a-fA-F]{40}|[A-Z2-7]{32})(?:&|$)",
        Pattern.CASE_INSENSITIVE);
    private final PikPakRepository repository;
    private final RclonePikPakClient connector;
    private final Clock clock;
    private final boolean enabled;
    private final Duration stableWindow;

    /** 创建操作服务。 @param repository 仓储 @param connector rclone 连接器 @param enabled 总开关 @param stableSeconds 稳定秒数 */
    @Autowired
    public PikPakOperationService(PikPakRepository repository, RclonePikPakClient connector,
        @Value("${pikpak.enabled:false}") boolean enabled,
        @Value("${pikpak.stable-seconds:120}") long stableSeconds) {
        this(repository, connector, enabled, stableSeconds, Clock.systemUTC());
    }

    PikPakOperationService(PikPakRepository repository, RclonePikPakClient connector,
                           boolean enabled, long stableSeconds, Clock clock) {
        this.repository = repository;
        this.connector = connector;
        this.enabled = enabled;
        this.stableWindow = Duration.ofSeconds(Math.max(1, stableSeconds));
        this.clock = clock;
    }

    /** 登记服务端账户路由。 @param request 请求 @return 账户 */
    public AccountView registerAccount(RegisterAccountRequest request) {
        validateRoot(request.offlineRoot());
        validateRoot(request.readyRoot());
        if (request.offlineRoot().equals(request.readyRoot())) {
            throw new IllegalArgumentException(ACCOUNT_ROOT_CONFLICT.code());
        }
        Account account = repository.registerAccount(request);
        return new AccountView(account.id(), account.externalKey(), account.storageProviderId(), account.enabled());
    }

    /** 幂等创建离线操作且不持久化原始 magnet。 @param request 请求 @return 操作视图 */
    @Transactional
    public OperationView create(CreateOperationRequest request) {
        requireEnabled();
        Account account = repository.requireAccount(request.accountId());
        if (!account.enabled()) {
            throw new IllegalStateException(ACCOUNT_DISABLED.code());
        }
        validateMagnet(request.magnetUri());
        String inputDigest = sha256(request.magnetUri());
        var existing = repository.findOperationByKey(request.idempotencyKey());
        if (existing.isPresent()) {
            Operation operation = existing.get();
            if (!operation.accountId().equals(request.accountId())
                    || !operation.businessType().equals(request.businessType())
                    || !operation.businessId().equals(request.businessId())
                    || !operation.inputSha256().equals(inputDigest)) {
                throw new IllegalStateException(OPERATION_IDEMPOTENCY_CONFLICT.code());
            }
            return view(operation);
        }
        UUID id = UUID.randomUUID();
        String token = "operation-" + id.toString().replace("-", "");
        Operation operation = new Operation(id, request.accountId(), request.idempotencyKey(),
            request.businessType(), request.businessId(), inputDigest, token, "CREATED", null,
            null, null, null, 0);
        return view(repository.insertOperation(operation));
    }

    /** 推进一次有界状态转换。 @param id 操作标识 @param magnetUri 仅 CREATED 首次提交时提供 @return 视图 */
    @Transactional
    public OperationView advance(UUID id, String magnetUri) {
        requireEnabled();
        Operation operation = repository.requireOperation(id);
        Account account = repository.requireAccount(operation.accountId());
        Operation updated = switch (operation.phase()) {
            case "CREATED" -> submit(operation, account, magnetUri);
            case "SUBMITTED", "OBSERVING" -> observe(operation, account);
            case "STABLE" -> startMove(operation, account);
            case "MOVING" -> reconcileMove(operation);
            case "CANCELLING" -> reconcileCancellation(operation);
            case "READY", "FAILED", "CANCELLED" -> operation;
            default -> throw new IllegalStateException(OPERATION_STATE_INVALID.code());
        };
        return view(updated);
    }

    /** 查询脱敏操作状态。 @param id 操作标识 @return 视图 */
    public OperationView get(UUID id) {
        return view(repository.requireOperation(id));
    }

    /** 取消未完成操作。 @param id 操作标识 @return 视图 */
    @Transactional
    public OperationView cancel(UUID id) {
        Operation operation = repository.requireOperation(id);
        if (List.of("READY", "FAILED", "CANCELLED").contains(operation.phase())) {
            return view(operation);
        }
        Account account = repository.requireAccount(operation.accountId());
        if ("CANCELLING".equals(operation.phase())) {
            return view(reconcileCancellation(operation));
        }
        if (operation.remoteJobId() != null) {
            connector.stop(operation.remoteJobId());
        }
        if ("MOVING".equals(operation.phase())) {
            return view(repository.transition(operation, "CANCELLING", operation.stableSignature(),
                operation.stableSince(), operation.remoteJobId(), null));
        }
        if (!"CREATED".equals(operation.phase())) {
            connector.purge(account.remoteKey(), workPath(account.offlineRoot(), operation.workToken()));
        }
        return view(repository.transition(operation, "CANCELLED", operation.stableSignature(),
            operation.stableSince(), operation.remoteJobId(), null));
    }

    private Operation submit(Operation operation, Account account, String magnetUri) {
        validateMagnet(magnetUri);
        if (!sha256(magnetUri).equals(operation.inputSha256())) {
            throw new IllegalArgumentException(INPUT_DIGEST_CONFLICT.code());
        }
        connector.addUrl(account.remoteKey(), workPath(account.offlineRoot(), operation.workToken()), magnetUri);
        return repository.transition(operation, "SUBMITTED", null, null, null, null);
    }

    private Operation observe(Operation operation, Account account) {
        List<RemoteItem> items = connector.list(account.remoteKey(),
            workPath(account.offlineRoot(), operation.workToken()));
        if (items.isEmpty()) {
            return repository.transition(operation, "OBSERVING", null, null, null, null);
        }
        String signature = signature(items);
        Instant now = clock.instant();
        if (!signature.equals(operation.stableSignature())) {
            repository.replaceItems(operation.id(), items);
            return repository.transition(operation, "OBSERVING", signature, now, null, null);
        }
        if (operation.stableSince() != null
                && !now.isBefore(operation.stableSince().plus(stableWindow))) {
            repository.replaceItems(operation.id(), items);
            return repository.transition(operation, "STABLE", signature, operation.stableSince(), null, null);
        }
        return repository.transition(operation, "OBSERVING", signature, operation.stableSince(), null, null);
    }

    private Operation startMove(Operation operation, Account account) {
        long jobId = connector.startMove(account.remoteKey(),
            workPath(account.offlineRoot(), operation.workToken()),
            workPath(account.readyRoot(), operation.workToken()));
        return repository.transition(operation, "MOVING", operation.stableSignature(),
            operation.stableSince(), jobId, null);
    }

    private Operation reconcileMove(Operation operation) {
        RemoteJob job = connector.job(operation.remoteJobId());
        if (!job.finished()) {
            return operation;
        }
        return repository.transition(operation, job.success() ? "READY" : "FAILED",
            operation.stableSignature(), operation.stableSince(), operation.remoteJobId(),
            job.success() ? null : MOVE_FAILED.code());
    }

    private Operation reconcileCancellation(Operation operation) {
        RemoteJob job = connector.job(operation.remoteJobId());
        if (!job.finished()) {
            return operation;
        }
        return repository.transition(operation, job.success() ? "READY" : "CANCELLED",
            operation.stableSignature(), operation.stableSince(), operation.remoteJobId(), null);
    }

    private OperationView view(Operation operation) {
        List<RemoteItem> items = List.of("STABLE", "MOVING", "CANCELLING", "READY").contains(operation.phase())
            ? repository.listItems(operation.id()) : List.of();
        return new OperationView(operation.id(), operation.phase(), operation.errorCode(),
            List.of("READY", "FAILED", "CANCELLED").contains(operation.phase()) ? 0 : 10, items);
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException(CONNECTOR_DISABLED.code());
        }
    }

    private void validateMagnet(String value) {
        if (value == null || value.isBlank() || value.length() > 8192) {
            throw new IllegalArgumentException(MAGNET_INVALID.code());
        }
        int separator = value.indexOf('?');
        String scheme = separator < 0 ? "" : value.substring(0, separator);
        String query = separator < 0 || separator == value.length() - 1 ? null : value.substring(separator + 1);
        if (!"magnet:".equalsIgnoreCase(scheme) || query == null || !INFO_HASH.matcher(query).find()) {
            throw new IllegalArgumentException(MAGNET_INVALID.code());
        }
    }

    private void validateRoot(String value) {
        workPath(value, "probe");
    }

    private String workPath(String root, String token) {
        String normalized = root.strip().replace('\\', '/').replaceAll("/+$", "");
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.contains("\u0000")) {
            throw new IllegalArgumentException(ACCOUNT_ROOT_INVALID.code());
        }
        for (String part : normalized.split("/", -1)) {
            if (part.isBlank() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException(ACCOUNT_ROOT_INVALID.code());
            }
        }
        return normalized + "/" + token;
    }

    private String signature(List<RemoteItem> items) {
        MessageDigest digest = digest();
        items.stream().sorted(Comparator.comparing(RemoteItem::relativePath)
            .thenComparing(RemoteItem::remoteFileId)).forEach(item -> {
                update(digest, item.remoteFileId());
                update(digest, item.relativePath());
                update(digest, Long.toString(item.sizeBytes()));
                update(digest, item.modifiedAt());
            });
        return HexFormat.of().formatHex(digest.digest());
    }

    private String sha256(String value) {
        MessageDigest digest = digest();
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
