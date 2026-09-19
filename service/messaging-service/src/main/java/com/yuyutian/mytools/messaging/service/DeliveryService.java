package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.CreateDeliveryRequest;
import com.yuyutian.mytools.messaging.model.CreateInboundMessageRequest;
import com.yuyutian.mytools.messaging.model.DeliveryRecord;
import com.yuyutian.mytools.messaging.model.DeliveryView;
import com.yuyutian.mytools.messaging.model.ExecuteDeliveryResult;
import com.yuyutian.mytools.messaging.model.InboundMessageView;
import com.yuyutian.mytools.messaging.model.InboundMessagePage;
import com.yuyutian.mytools.messaging.model.TaskExecutionFence;
import com.yuyutian.mytools.messaging.provider.DeliveryProvider;
import com.yuyutian.mytools.messaging.repository.MessagingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 标准消息投递和入站消息原子服务。
 */
@Service
public class DeliveryService {

    private final MessagingRepository repository;
    private final TaskSchedulerClient schedulerClient;
    private final TransactionTemplate transactionTemplate;
    private final Map<ChannelType, DeliveryProvider> providers;

    /**
     * 创建标准消息服务。
     */
    public DeliveryService(MessagingRepository repository, TaskSchedulerClient schedulerClient,
                           List<DeliveryProvider> providerList, TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.schedulerClient = schedulerClient;
        this.transactionTemplate = transactionTemplate;
        this.providers = new EnumMap<>(ChannelType.class);
        providerList.forEach(provider -> providers.put(provider.channelType(), provider));
    }

    /**
     * 幂等创建异步投递请求。
     */
    public DeliveryView create(CreateDeliveryRequest request) {
        if (!request.validRecipient()) {
            throw new DeliveryInvalidException();
        }
        if (!providers.containsKey(request.channelType())) {
            throw new ProviderNotConfiguredException(request.channelType());
        }
        DeliveryRecord record = transactionTemplate.execute(status -> repository
                .findDeliveryByIdempotencyKey(request.ownerId(), request.idempotencyKey())
                .orElseGet(() -> createRecord(request)));
        if (record == null) {
            throw new IllegalStateException("Delivery transaction returned no record");
        }
        if (!matches(record, request)) {
            throw new DeliveryInvalidException();
        }
        if (record.taskId() == null) {
            UUID deliveryId = record.id();
            String taskName = record.channelType() == ChannelType.EMAIL
                    ? "message_send_email" : "message_send_channel_message";
            UUID taskId = schedulerClient.createDeliveryTask(deliveryId,
                    new TaskSchedulerClient.ChannelTask(taskName));
            transactionTemplate.executeWithoutResult(status -> repository.bindTask(deliveryId, taskId));
            record = required(deliveryId);
        }
        return view(record);
    }

    /**
     * 查询不包含正文的投递状态。
     */
    public DeliveryView get(UUID id) {
        return view(required(id));
    }

    /** 按所有者查询投递。 @param id 投递 @param ownerId 所有者 @return 投递 */
    public DeliveryView get(UUID id,long ownerId) { return view(requiredOwner(id,ownerId)); }

    /** 请求取消所有者的投递。 @param id 投递 @param ownerId 所有者 @return 投递 */
    public DeliveryView cancel(UUID id,long ownerId) {
        DeliveryRecord record=requiredOwner(id,ownerId);
        if(List.of("DELIVERED","CANCELLED").contains(record.status()))return view(record);
        if(record.taskId()!=null)schedulerClient.cancel(record.taskId());
        transactionTemplate.executeWithoutResult(status->repository.requestDeliveryCancel(id));
        return view(requiredOwner(id,ownerId));
    }

    /**
     * 执行一个由 Scheduler 授权调度的原子投递。
     *
     * @param id 投递标识
     * @param fence 任务执行隔离上下文
     * @return 投递结果
     */
    public ExecuteDeliveryResult execute(UUID id, TaskExecutionFence fence) {
        DeliveryRecord record = required(id);
        requireValidFence(record, id, fence);
        if ("DELIVERED".equals(record.status())) {
            Boolean acquired = transactionTemplate.execute(status -> repository.acquireDeliveredFence(id, fence));
            if (!Boolean.TRUE.equals(acquired)) {
                throw new DeliveryStateInvalidException();
            }
            return new ExecuteDeliveryResult(id, record.status(), record.providerMessageId());
        }
        DeliveryProvider provider = providers.get(record.channelType());
        if (provider == null) {
            throw new ProviderNotConfiguredException(record.channelType());
        }
        Integer acquiredAttempt = transactionTemplate.execute(status -> repository.beginAttempt(id, fence));
        int attempt = acquiredAttempt == null ? 0 : acquiredAttempt;
        if (attempt == 0) {
            throw new DeliveryStateInvalidException();
        }
        try {
            String providerMessageId = provider.deliver(record);
            transactionTemplate.executeWithoutResult(status ->
                    repository.completeDelivery(id, attempt, providerMessageId));
            return new ExecuteDeliveryResult(id, "DELIVERED", providerMessageId);
        } catch (RuntimeException exception) {
            // 仅持久化稳定错误类别，禁止将可能含有凭据的 provider 异常写入数据库。
            transactionTemplate.executeWithoutResult(status ->
                    repository.failDelivery(id, attempt, "PROVIDER_FAILURE"));
            throw exception;
        }
    }

    private void requireValidFence(DeliveryRecord record, UUID id, TaskExecutionFence fence) {
        if (fence == null || !fence.valid() || !"send_email".equals(fence.stepName())
                || !id.toString().equals(fence.businessKey())
                || record.taskId() == null || !record.taskId().equals(fence.taskInstanceId())) {
            throw new DeliveryStateInvalidException();
        }
    }

    /**
     * 幂等接收入站标准消息并写入 Outbox。
     */
    @Transactional
    public InboundMessageView receive(CreateInboundMessageRequest request) {
        return repository.saveInbound(request);
    }

    /**
     * 原子接收含合并转发引用的 OneBot 消息，延后实时事件直至展开完成。
     *
     * @param request 标准消息
     * @param rawEvent 原始 OneBot 事件
     * @param accountKey OneBot 账户标识
     * @param forwardIds 合并转发引用
     * @param truncated 是否因安全预算截断
     * @return 已持久化消息
     */
    @Transactional
    public InboundMessageView receiveOneBot(CreateInboundMessageRequest request, String rawEvent,
                                            String accountKey, List<String> forwardIds, boolean truncated) {
        return repository.saveOneBotInbound(request, rawEvent, accountKey, forwardIds, truncated);
    }

    /**
     * 查询自动化服务所需的标准入站消息。
     *
     * @param id 消息标识
     * @return 入站消息
     */
    public InboundMessageView inbound(UUID id) {
        return repository.findInbound(id).orElseThrow(() -> new InboundMessageNotFoundException(id));
    }

    /**
     * 查询所有者的入站消息。
     *
     * @param ownerId 所有者
     * @param afterId 游标
     * @param limit 页大小
     * @return 消息页
     */
    public InboundMessagePage listInbound(long ownerId, UUID afterId, int limit) {
        if (ownerId <= 0 || limit < 1 || limit > 100) {
            throw new DeliveryInvalidException();
        }
        if (afterId != null) {
            inbound(afterId, ownerId);
        }
        return repository.listInbound(ownerId, afterId, limit);
    }

    /**
     * 查询所有者的指定入站消息。
     *
     * @param id 消息标识
     * @param ownerId 所有者
     * @return 消息
     */
    public InboundMessageView inbound(UUID id, long ownerId) {
        InboundMessageView value = inbound(id);
        if (value.ownerId() != ownerId) {
            throw new InboundMessageNotFoundException(id);
        }
        return value;
    }

    private DeliveryRecord createRecord(CreateDeliveryRequest request) {
        Instant now = Instant.now();
        DeliveryRecord record = new DeliveryRecord(UUID.randomUUID(), request.ownerId(), request.idempotencyKey(),
                request.channelType(), request.accountId(), request.recipient(), request.subject(), request.body(),
                "ACCEPTED", null, null, null, now, now);
        repository.insertDelivery(record);
        return record;
    }

    private DeliveryRecord required(UUID id) {
        return repository.findDelivery(id).orElseThrow(() -> new DeliveryNotFoundException(id));
    }

    private DeliveryRecord requiredOwner(UUID id,long ownerId) {
        DeliveryRecord record=required(id);if(record.ownerId()!=ownerId)throw new DeliveryNotFoundException(id);return record;
    }

    private boolean matches(DeliveryRecord record,CreateDeliveryRequest request) {
        return record.channelType()==request.channelType()&&java.util.Objects.equals(record.accountId(),request.accountId())
                &&record.recipient().equals(request.recipient())&&java.util.Objects.equals(record.subject(),request.subject())
                &&record.body().equals(request.body());
    }

    private DeliveryView view(DeliveryRecord record) {
        return new DeliveryView(record.id(), record.channelType(), record.recipient(), record.status(),
                record.taskId(), record.providerMessageId(), record.lastErrorCode(),
                record.createdAt(), record.updatedAt());
    }
}
