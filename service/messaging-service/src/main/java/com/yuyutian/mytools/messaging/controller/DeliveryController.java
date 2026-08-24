package com.yuyutian.mytools.messaging.controller;

import com.yuyutian.mytools.messaging.model.CreateDeliveryRequest;
import com.yuyutian.mytools.messaging.model.CreateInboundMessageRequest;
import com.yuyutian.mytools.messaging.model.DeliveryView;
import com.yuyutian.mytools.messaging.model.ExecuteDeliveryResult;
import com.yuyutian.mytools.messaging.model.InboundMessageView;
import com.yuyutian.mytools.messaging.model.InboundMessagePage;
import com.yuyutian.mytools.messaging.model.OneBotInboundRequest;
import com.yuyutian.mytools.messaging.model.AttachmentDownloadView;
import com.yuyutian.mytools.messaging.model.ExecuteAttachmentDownloadResult;
import com.yuyutian.mytools.messaging.model.ResolveAttachmentResult;
import com.yuyutian.mytools.messaging.model.EmailPollRequest;
import com.yuyutian.mytools.messaging.model.EmailPollResult;
import com.yuyutian.mytools.messaging.service.DeliveryService;
import com.yuyutian.mytools.messaging.service.InternalRequestAuthorizer;
import com.yuyutian.mytools.messaging.service.OneBotInboundAdapter;
import com.yuyutian.mytools.messaging.service.AttachmentDownloadService;
import com.yuyutian.mytools.messaging.service.EmailIngressService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.UUID;

/**
 * 消息服务内部原子接口。
 */
@RestController
@RequestMapping("/internal/v1")
public class DeliveryController {

    private final DeliveryService service;
    private final InternalRequestAuthorizer authorizer;
    private final OneBotInboundAdapter oneBotInboundAdapter;
    private final AttachmentDownloadService attachmentDownloadService;
    private final EmailIngressService emailIngressService;

    /**
     * 创建消息内部控制器。
     */
    public DeliveryController(DeliveryService service, InternalRequestAuthorizer authorizer,
                              OneBotInboundAdapter oneBotInboundAdapter,
                              AttachmentDownloadService attachmentDownloadService,
                              EmailIngressService emailIngressService) {
        this.service = service;
        this.authorizer = authorizer;
        this.oneBotInboundAdapter = oneBotInboundAdapter;
        this.attachmentDownloadService = attachmentDownloadService;
        this.emailIngressService = emailIngressService;
    }

    /**
     * 创建异步投递请求。
     */
    @PostMapping("/deliveries")
    public ResponseEntity<DeliveryView> create(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreateDeliveryRequest request) {
        authorizer.requireAuthorized(authorization);
        return ResponseEntity.accepted().body(service.create(request));
    }

    /**
     * 查询投递状态。
     */
    @GetMapping("/deliveries/{id}")
    public DeliveryView get(@RequestHeader(name = "Authorization", required = false) String authorization,
                            @PathVariable UUID id,@RequestParam(required=false)Long ownerId) {
        authorizer.requireAuthorized(authorization);
        return ownerId==null?service.get(id):service.get(id,ownerId);
    }

    /** 请求取消所有者的投递。 */
    @PostMapping("/deliveries/{id}/cancel")
    public DeliveryView cancelDelivery(@RequestHeader(name="Authorization",required=false)String authorization,
                                       @PathVariable UUID id,@RequestParam long ownerId) {
        authorizer.requireAuthorized(authorization);return service.cancel(id,ownerId);
    }

    /**
     * 由 Executor 执行一次投递。
     */
    @PostMapping("/deliveries/{id}/execute")
    public ExecuteDeliveryResult execute(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID id) {
        authorizer.requireAuthorized(authorization);
        return service.execute(id);
    }

    /**
     * 接收 provider adapter 标准化后的入站消息。
     */
    @PostMapping("/inbound-messages")
    public InboundMessageView receive(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreateInboundMessageRequest request) {
        authorizer.requireAuthorized(authorization);
        return service.receive(request);
    }

    /**
     * 接收并标准化 OneBot 11 消息事件。
     */
    @PostMapping("/adapters/onebot/events")
    public InboundMessageView receiveOneBot(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody OneBotInboundRequest request) {
        authorizer.requireAuthorized(authorization);
        return oneBotInboundAdapter.receive(request);
    }

    /**
     * 由 Executor 轮询一个服务端已配置的 IMAP 账户。
     */
    @PostMapping("/adapters/email/poll")
    public EmailPollResult pollEmail(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody EmailPollRequest request) {
        authorizer.requireAuthorized(authorization);
        return emailIngressService.poll(request.accountKey());
    }

    /**
     * 查询标准化入站消息供自动化服务处理。
     */
    @GetMapping("/inbound-messages/{id}")
    public InboundMessageView inbound(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID id, @RequestParam(required = false) Long ownerId) {
        authorizer.requireAuthorized(authorization);
        return ownerId == null ? service.inbound(id) : service.inbound(id, ownerId);
    }

    /**
     * 分页查询所有者的标准化入站消息。
     */
    @GetMapping("/inbound-messages")
    public InboundMessagePage inboundMessages(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam long ownerId, @RequestParam(required = false) UUID afterId,
            @RequestParam(defaultValue = "50") int limit) {
        authorizer.requireAuthorized(authorization);
        return service.listInbound(ownerId, afterId, limit);
    }

    /**
     * 幂等创建消息附件下载处理任务。
     */
    @PostMapping("/inbound-messages/{messageId}/parts/{partId}/download")
    public ResponseEntity<AttachmentDownloadView> createAttachmentDownload(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID messageId, @PathVariable UUID partId,
            @RequestParam(required = false) Long ownerId) {
        authorizer.requireAuthorized(authorization);
        return ResponseEntity.accepted().body(ownerId == null ? attachmentDownloadService.create(messageId, partId)
                : attachmentDownloadService.create(messageId, partId, ownerId));
    }

    /**
     * 查询消息附件下载处理任务。
     */
    @GetMapping("/attachment-downloads/{jobId}")
    public AttachmentDownloadView attachmentDownload(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID jobId, @RequestParam(required = false) Long ownerId) {
        authorizer.requireAuthorized(authorization);
        return ownerId == null ? attachmentDownloadService.get(jobId) : attachmentDownloadService.get(jobId, ownerId);
    }

    /** 请求取消所有者的附件下载任务。 */
    @PostMapping("/attachment-downloads/{jobId}/cancel")
    public AttachmentDownloadView cancelAttachmentDownload(
            @RequestHeader(name="Authorization",required=false)String authorization,@PathVariable UUID jobId,
            @RequestParam long ownerId) { authorizer.requireAuthorized(authorization);return attachmentDownloadService.cancel(jobId,ownerId); }

    /**
     * 由 Executor 解析渠道 provider 文件引用。
     */
    @PostMapping("/attachment-downloads/{jobId}/resolve")
    public ResolveAttachmentResult resolveAttachment(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID jobId) {
        authorizer.requireAuthorized(authorization);
        return attachmentDownloadService.resolve(jobId);
    }

    /**
     * 向受控下载执行器流式转发 provider 内容。
     */
    @PostMapping("/attachment-downloads/{jobId}/content")
    public ResponseEntity<StreamingResponseBody> streamAttachment(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID jobId) {
        authorizer.requireAuthorized(authorization);
        StreamingResponseBody body = output -> attachmentDownloadService.stream(jobId, output);
        return ResponseEntity.ok().header("Content-Type", "application/octet-stream").body(body);
    }

    /**
     * 由 Executor 创建实际附件下载子任务。
     */
    @PostMapping("/attachment-downloads/{jobId}/execute")
    public ExecuteAttachmentDownloadResult executeAttachmentDownload(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID jobId) {
        authorizer.requireAuthorized(authorization);
        return attachmentDownloadService.execute(jobId);
    }
}
