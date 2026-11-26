package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.config.OutboundProperties;
import com.yuyutian.mytools.messaging.model.ErrorCode;
import com.yuyutian.mytools.messaging.model.OutboundModels.*;
import com.yuyutian.mytools.messaging.provider.OutboundProvider;
import com.yuyutian.mytools.messaging.repository.OutboundRepository;
import com.yuyutian.mytools.messaging.utils.outbound.OutboundSecurity;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.validation.Validator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 目的地绑定、持久调度、发送和未知结果核对的领域边界。 */
@Service
public class OutboundService {
    private final OutboundRepository repository;
    private final TransactionTemplate tx;
    private final OutboundProperties properties;
    private final OutboundSecurity security;
    private final OutboundSchedulerClient scheduler;
    private final OutboundProvider provider;
    private final Validator validator;
    /** 注入独立的新协议组件。 */
    public OutboundService(OutboundRepository repository, TransactionTemplate tx, OutboundProperties properties,
            OutboundSecurity security, OutboundSchedulerClient scheduler, OutboundProvider provider, Validator validator) {
        this.repository=repository; this.tx=tx; this.properties=properties; this.security=security;
        this.scheduler=scheduler; this.provider=provider; this.validator=validator;
    }
    /** 管理员已验证所有权后登记不可变目的地，修改地址必须新建绑定。 */
    public Destination bind(Binding binding) {
        valid(binding);
        if (!binding.ownershipVerified()) fail(ErrorCode.OUTBOUND_INVALID);
        if (binding.channel() == Channel.QQ) fail(ErrorCode.CHANNEL_UNAVAILABLE);
        validateAddress(binding);
        Secret secret = new Secret(binding.address(), binding.signingSecret() == null ? "" : binding.signingSecret());
        try {
            return tx.execute(status -> {
                repository.lockCapacity();
                var existing = repository.binding(binding.ownerId(), binding.bindingKey(),true);
                if (existing.isPresent()) return matchBinding(binding, secret, existing.get());
                UUID id = UUID.randomUUID();
                String encrypted = security.encrypt(repository.encode(secret), context(binding.ownerId(), id, binding.channel()));
                repository.bind(id, binding, encrypted, Instant.now());
                return destination(binding.ownerId(), id, false);
            });
        } catch (DuplicateKeyException exception) {
            return matchBinding(binding, secret, repository.binding(binding.ownerId(), binding.bindingKey()).orElseThrow());
        }
    }
    /** 返回本人已验证绑定，不包含地址和密钥。 */
    public List<Destination> destinations(long owner) { owner(owner); return repository.destinations(owner); }
    /** 停用后不能新建或开始投递，进行中的提供方调用仍可能完成。 */
    public Destination disable(long owner, UUID id) {
        return tx.execute(status -> {
            destination(owner, id, true);
            repository.disable(owner, id, Instant.now());
            return destination(owner, id, false);
        });
    }
    /** 幂等写入完整投递意图，调度服务不可用不会丢失请求。 */
    public View create(Create request) {
        valid(request);
        if (!OutboundSecurity.hash(request.content()).equals(request.payloadHash())) fail(ErrorCode.OUTBOUND_INVALID);
        var existing = repository.byKey(request.ownerId(), request.idempotencyKey());
        if (existing.isPresent()) return match(request, existing.get());
        requireEnabled();
        Instant now = Instant.now();
        if (!request.expiresAt().isAfter(now) || request.expiresAt().isAfter(now.plusSeconds(172800))) fail(ErrorCode.OUTBOUND_INVALID);
        validateDeadline(request.contentExpiresAt(),now,true);
        if (request.content().subject().contains("\r") || request.content().subject().contains("\n")) fail(ErrorCode.OUTBOUND_INVALID);
        try {
            return tx.execute(status -> {
                repository.lockCapacity();
                Destination d = destination(request.ownerId(), request.destinationId(), true);
                var current=repository.byKey(request.ownerId(),request.idempotencyKey(),true);
                if(current.isPresent())return match(request,current.get());
                if (!d.enabled()) fail(ErrorCode.OUTBOUND_CONFLICT);
                if (d.channel() == Channel.QQ) fail(ErrorCode.CHANNEL_UNAVAILABLE);
                if (d.channel() == Channel.FEISHU) {
                    try {
                        // 以真实 JSON 序列化大小限制载荷，为签名字段保留空间。
                        if (request.content().html() != null && !request.content().html().isEmpty()
                                || provider.feishuPayload(request.content(), "budget-reserve", 9999999999L).length > 16384) fail(ErrorCode.OUTBOUND_INVALID);
                    } catch (java.security.GeneralSecurityException | com.fasterxml.jackson.core.JsonProcessingException e) { fail(ErrorCode.OUTBOUND_INVALID); }
                }
                // 等待容量或目的地锁后重新检查期限，避免把已到期的新正文持久化。
                Instant admittedAt=Instant.now();
                if(!request.expiresAt().isAfter(admittedAt))fail(ErrorCode.OUTBOUND_INVALID);
                validateDeadline(request.contentExpiresAt(),admittedAt,true);
                UUID id = UUID.randomUUID();
                repository.insert(id, request, d.channel(), admittedAt);
                return view(required(id, false));
            });
        } catch (DuplicateKeyException exception) {
            return match(request, repository.byKey(request.ownerId(), request.idempotencyKey()).orElseThrow());
        }
    }
    /** 查询必须校验调用方所有者。 */
    public View get(long owner, UUID id) { return view(owned(owner, id, false)); }
    /** 用原始冻结请求原子取消或建立取消记录，绝不为了对账创建可发送请求。 */
    public View cancelByKey(Create request) {
        valid(request);
        if(!OutboundSecurity.hash(request.content()).equals(request.payloadHash()))fail(ErrorCode.OUTBOUND_INVALID);
        return cancelByIdentity(new CancelIdentity(request.ownerId(),request.idempotencyKey(),request.destinationId(),request.payloadHash(),
                OutboundSecurity.identityHash(request.content()),request.expiresAt(),request.contentExpiresAt()));
    }
    /** 正文清理后仍按原始身份取消或占用取消键，不创建可发送消息。 */
    public View cancelByIdentity(CancelIdentity request) {
        valid(request);validateDeadline(request.contentExpiresAt(),Instant.now(),false);
        try {request.expiresAt().toEpochMilli();}catch(ArithmeticException exception){fail(ErrorCode.OUTBOUND_INVALID);}
        if(!request.contentIdentityHash().equals(OutboundSecurity.identityHash(request.payloadHash(),false))
                && !request.contentIdentityHash().equals(OutboundSecurity.identityHash(request.payloadHash(),true)))fail(ErrorCode.OUTBOUND_INVALID);
        return tx.execute(status->{
            repository.lockCapacity();
            Destination target=destination(request.ownerId(),request.destinationId(),true);
            var existing=repository.byKey(request.ownerId(),request.idempotencyKey(),true);
            if(existing.isPresent()) {matchIdentity(request,existing.get());return cancel(request.ownerId(),existing.get().id());}
            UUID id=UUID.randomUUID();repository.insertCancellation(id,request,target.channel(),Instant.now());return view(required(id,false));
        });
    }
    /** 开始发送后只能观察真实结果，不能返回虚假的取消成功。 */
    public View cancel(long owner, UUID id) {
        return tx.execute(status -> {
            Delivery d = owned(owner, id, true);
            if (d.status().equals("ACCEPTED")) repository.state(id, "CANCELLED", Instant.now());
            return view(required(id, false));
        });
    }
    /** 已知失败可重试，未知结果必须明确确认可能重复。 */
    public View retry(long owner, UUID id, Retry request) {
        requireEnabled();
        valid(request);
        Delivery initial = owned(owner, id, false);
        return tx.execute(status -> {
            // 与停用入口统一先锁目的地，再锁投递，避免相反锁序造成死锁。
            Destination target = destination(owner, initial.destinationId(), true);
            Delivery d = owned(owner, id, true);
            // 原请求重放只返回已经创建的新一代，即使该代已经结束也不再次发送。
            if (d.generation() == request.expectedGeneration() + 1) return view(d);
            if (d.generation() != request.expectedGeneration()) fail(ErrorCode.OUTBOUND_CONFLICT);
            if(contentExpired(d,Instant.now()))fail(ErrorCode.OUTBOUND_CONTENT_EXPIRED);
            if (d.content()==null || !Set.of("FAILED", "UNKNOWN").contains(d.status()) || d.generation() >= 100 || !d.expiresAt().isAfter(Instant.now())
                    || (d.status().equals("UNKNOWN") && !request.acknowledgeDuplicateRisk())) fail(ErrorCode.OUTBOUND_CONFLICT);
            if (!target.enabled()) fail(ErrorCode.OUTBOUND_CONFLICT);
            repository.retry(d, d.status().equals("UNKNOWN") ? "DUPLICATE_RISK_ACCEPTED" : "RETRY_KNOWN_FAILURE", Instant.now());
            return view(required(id, false));
        });
    }
    /** 独立管理入口根据提供方核对结果完成 UNKNOWN 对账。 */
    public View resolve(long owner, UUID id, Resolution resolution) {
        valid(resolution);
        return tx.execute(status -> {
            Delivery d = owned(owner, id, true);
            if (!d.status().equals("UNKNOWN") || d.attemptId() == null) fail(ErrorCode.OUTBOUND_CONFLICT);
            repository.resolve(d, resolution, Instant.now());
            return view(required(id, false));
        });
    }
    /** 在实时租约验证和数据库独占尝试之后，事务外调用提供方。 */
    public View execute(UUID id, int generation, Proof proof) {
        requireEnabled();
        Grant grant = scheduler.authorize(proof);
        Delivery initial = required(id, false);
        Attempt attempt = tx.execute(status -> {
            // 创建、停用和执行共享目的地优先的锁序。
            Destination destination = destination(initial.ownerId(), initial.destinationId(), true);
            Delivery d = required(id, true);
            if (generation != d.generation() || !id.toString().equals(grant.businessId()) || !OutboundSchedulerClient.key(d).equals(grant.idempotencyKey())
                    || (d.taskId() != null && !d.taskId().equals(grant.taskId())) || grant.fencingToken() < d.lastFence()
                    || grant.fencingToken() <= 0 || !grant.leaseUntil().isAfter(Instant.now().plusSeconds(30))) fail(ErrorCode.OUTBOUND_LEASE_INVALID);
            if (!d.status().equals("ACCEPTED")) return new Attempt(d, null, null);
            if(contentExpired(d,Instant.now())) {repository.contentExpired(id,Instant.now());return new Attempt(required(id,false),null,null);}
            if (!d.expiresAt().isAfter(Instant.now())) { repository.state(id, "EXPIRED", Instant.now()); return new Attempt(required(id, false), null, null); }
            if (!destination.enabled()) { repository.state(id, "CANCELLED", Instant.now()); return new Attempt(required(id, false), null, null); }
            if(d.content()==null)fail(ErrorCode.OUTBOUND_CONFLICT);
            Secret secret = secret(d.ownerId(), destination);
            UUID attemptId = repository.begin(d, grant.taskId(), grant.fencingToken(), Instant.now());
            return new Attempt(d, attemptId, secret);
        });
        if (attempt == null) fail(ErrorCode.OUTBOUND_CONFLICT);
        if (attempt.attemptId() == null) return view(attempt.delivery());
        OutboundProvider.Result result;
        try { result = provider.send(attempt.delivery(), attempt.secret()); }
        catch (RuntimeException exception) { result = new OutboundProvider.Result("UNKNOWN", null, ErrorCode.OUTBOUND_RESULT_UNKNOWN.code()); }
        OutboundProvider.Result outcome = result;
        tx.executeWithoutResult(status -> repository.finish(id, attempt.attemptId(), outcome.status(), outcome.reference(), outcome.errorCode(), Instant.now()));
        return view(required(id, false));
    }
    /** 自动调度仅处理未开始的请求，UNKNOWN 不进入重发队列。 */
    @Scheduled(initialDelayString="${messaging.outbound.relay-initial-delay-ms:5000}", fixedDelayString="${messaging.outbound.relay-delay-ms:5000}")
    public void relay() {
        if (!properties.isEnabled()) return;
        tx.executeWithoutResult(status -> repository.recover(Instant.now()));
        for (UUID id : repository.due(Instant.now())) {
            UUID token = UUID.randomUUID();
            if (!Boolean.TRUE.equals(tx.execute(status -> repository.claim(id, token, Instant.now())))) continue;
            try {
                Delivery d = required(id, false);
                if (d.taskId() == null) {
                    UUID task = scheduler.create(d);
                    tx.executeWithoutResult(status -> repository.task(id, token, task, Instant.now()));
                } else {
                    // 从未进入提供方调用的任务失败，可安全地提供人工重试。
                    String taskStatus = scheduler.status(d);
                    tx.executeWithoutResult(status -> repository.reconcileTask(id, token, taskStatus, Instant.now()));
                }
            } catch (RuntimeException exception) {
                // 原始响应和凭据不能进入日志，保留原幂等键稍后恢复。
                tx.executeWithoutResult(status -> repository.defer(id, token, Instant.now()));
            }
        }
    }
    private Destination matchBinding(Binding b, Secret secret, UUID id) {
        Destination d = destination(b.ownerId(), id, false);
        if (d.channel() != b.channel() || !d.label().equals(b.label()) || !secret.equals(secret(b.ownerId(), d))) fail(ErrorCode.OUTBOUND_CONFLICT);
        return d;
    }
    private void validateAddress(Binding b) {
        if (b.address().contains("\r") || b.address().contains("\n")) fail(ErrorCode.OUTBOUND_INVALID);
        if (b.channel() == Channel.FEISHU && !b.address().matches("https://open\\.feishu\\.cn/open-apis/bot/v2/hook/[A-Za-z0-9_-]{16,128}")) fail(ErrorCode.OUTBOUND_INVALID);
        if (b.channel() == Channel.EMAIL) {
            try {
                InternetAddress address = new InternetAddress(b.address(), true);
                address.validate();
                if (!address.getAddress().equals(b.address()) || address.isGroup() || b.address().length() > 254 || !b.address().contains("@")) fail(ErrorCode.OUTBOUND_INVALID);
            } catch (AddressException exception) { fail(ErrorCode.OUTBOUND_INVALID); }
        }
    }
    private View match(Create r, Delivery d) {
        if (!d.destinationId().equals(r.destinationId()) || !d.payloadHash().equals(r.payloadHash())
                || !(d.content()!=null?Objects.equals(d.content(),r.content()):Objects.equals(d.contentIdentityHash(),OutboundSecurity.identityHash(r.content()))) || d.expiresAt().toEpochMilli() != r.expiresAt().toEpochMilli() || !Objects.equals(epoch(d.contentExpiresAt()),epoch(r.contentExpiresAt()))) fail(ErrorCode.OUTBOUND_CONFLICT);
        return view(d);
    }
    private void matchIdentity(CancelIdentity request,Delivery delivery) {
        String identity=delivery.content()!=null?OutboundSecurity.identityHash(delivery.content()):delivery.contentIdentityHash();
        if(!delivery.destinationId().equals(request.destinationId()) || !delivery.payloadHash().equals(request.payloadHash())
                || !Objects.equals(identity,request.contentIdentityHash()) || delivery.expiresAt().toEpochMilli()!=request.expiresAt().toEpochMilli()
                || !Objects.equals(epoch(delivery.contentExpiresAt()),epoch(request.contentExpiresAt())))fail(ErrorCode.OUTBOUND_CONFLICT);
    }
    private boolean contentExpired(Delivery delivery,Instant now){return delivery.contentExpiresAt()!=null && !delivery.contentExpiresAt().isAfter(now);}
    private Long epoch(Instant value){try{return value==null?null:value.toEpochMilli();}catch(ArithmeticException exception){fail(ErrorCode.OUTBOUND_INVALID);return null;}}
    private void validateDeadline(Instant deadline,Instant now,boolean creating) {
        if(deadline==null)return;
        epoch(deadline);
        // 有期限的新正文必须仍有效，历史取消允许过去的截止时刻但不能无限延长。
        if(deadline.isAfter(now.plusSeconds(730L*86400)) || creating&&!deadline.isAfter(now))fail(ErrorCode.OUTBOUND_INVALID);
    }
    private Secret secret(long owner, Destination d) { return repository.decode(security.decrypt(repository.secret(owner, d.id()), context(owner, d.id(), d.channel())), Secret.class); }
    private String context(long owner, UUID id, Channel channel) { return owner + ":" + id + ":" + channel; }
    private Destination destination(long owner, UUID id, boolean lock) { owner(owner); return repository.destination(owner, id, lock).orElseThrow(() -> new OutboundException(ErrorCode.DESTINATION_NOT_FOUND)); }
    private Delivery required(UUID id, boolean lock) { return repository.get(id, lock).orElseThrow(() -> new OutboundException(ErrorCode.OUTBOUND_NOT_FOUND)); }
    private Delivery owned(long owner, UUID id, boolean lock) { owner(owner); Delivery d = required(id, lock); if (d.ownerId() != owner) fail(ErrorCode.OUTBOUND_NOT_FOUND); return d; }
    private View view(Delivery d) { return new View(d.id(), d.destinationId(), d.channel(), d.payloadHash(), d.status(), d.generation(), d.errorCode(), d.expiresAt(), d.updatedAt(),d.contentExpiresAt()); }
    private void owner(long owner) { if (owner <= 0) fail(ErrorCode.OUTBOUND_INVALID); }
    private void valid(Object value) { if (value == null || !validator.validate(value).isEmpty()) fail(ErrorCode.OUTBOUND_INVALID); }
    private void requireEnabled() { if (!properties.isEnabled()) fail(ErrorCode.OUTBOUND_DISABLED); }
    private void fail(ErrorCode code) { throw new OutboundException(code); }
    /** 尝试结果不允许泄露解密后的配置。 */
    private record Attempt(Delivery delivery, UUID attemptId, Secret secret) {
        /** 屏蔽用于调用提供方的临时凭据。 */
        @Override public String toString() { return "Attempt[redacted]"; }
    }
}
