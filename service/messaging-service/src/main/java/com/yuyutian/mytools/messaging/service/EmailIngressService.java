package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.config.EmailIngressProperties;
import com.yuyutian.mytools.messaging.model.ChannelType;
import com.yuyutian.mytools.messaging.model.CreateInboundMessagePart;
import com.yuyutian.mytools.messaging.model.CreateInboundMessageRequest;
import com.yuyutian.mytools.messaging.model.EmailPollResult;
import com.yuyutian.mytools.messaging.repository.EmailPollCheckpointRepository;
import jakarta.mail.Address;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.MimeUtility;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 通过只读 IMAP 轮询接收邮件的原子服务。
 */
@Service
public class EmailIngressService {
    private static final int MAXIMUM_BODY_CHARACTERS = 10_485_760;
    private static final int MAXIMUM_PARTS = 500;
    private static final long UID_SCAN_WINDOW = 10_000;
    private final EmailIngressProperties properties;
    private final EmailPollCheckpointRepository checkpointRepository;
    private final DeliveryService deliveryService;
    private final AttachmentDownloadService attachmentDownloadService;

    /**
     * 创建邮件入站服务。
     *
     * @param properties 入站配置
     * @param checkpointRepository 检查点仓储
     * @param deliveryService 消息领域服务
     * @param attachmentDownloadService 附件下载编排服务
     */
    public EmailIngressService(EmailIngressProperties properties,
                               EmailPollCheckpointRepository checkpointRepository,
                               DeliveryService deliveryService,
                               AttachmentDownloadService attachmentDownloadService) {
        this.properties = properties;
        this.checkpointRepository = checkpointRepository;
        this.deliveryService = deliveryService;
        this.attachmentDownloadService = attachmentDownloadService;
    }

    /**
     * 轮询一个服务端已配置的账户并幂等接收入站消息。
     *
     * @param accountKey 账户逻辑键
     * @return 轮询结果
     */
    public synchronized EmailPollResult poll(String accountKey) {
        validateConfiguration(accountKey);
        Properties mailProperties = new Properties();
        mailProperties.setProperty("mail.store.protocol", properties.ssl() ? "imaps" : "imap");
        mailProperties.setProperty("mail.imap.ssl.enable", Boolean.toString(properties.ssl()));
        mailProperties.setProperty("mail.imaps.ssl.enable", Boolean.toString(properties.ssl()));
        Store store = null;
        Folder folder = null;
        try {
            Session session = Session.getInstance(mailProperties);
            store = session.getStore(properties.ssl() ? "imaps" : "imap");
            store.connect(properties.host(), properties.port(), properties.username(), properties.password());
            folder = store.getFolder(properties.mailbox());
            folder.open(Folder.READ_ONLY);
            if (!(folder instanceof UIDFolder uidFolder)) {
                throw new IllegalStateException("IMAP UID is unavailable");
            }
            long uidValidity = uidFolder.getUIDValidity();
            long lastUid = checkpointRepository.find(accountKey, properties.mailbox())
                    .filter(value -> value.uidValidity() == uidValidity)
                    .map(EmailPollCheckpointRepository.Checkpoint::lastUid).orElse(0L);
            long uidNext = uidFolder.getUIDNext();
            if (uidNext <= 0 || uidNext <= lastUid + 1) {
                return new EmailPollResult(accountKey, 0, 0, lastUid);
            }
            long scanEnd = Math.min(uidNext - 1, lastUid + UID_SCAN_WINDOW);
            Message[] candidates = uidFolder.getMessagesByUID(lastUid + 1, scanEnd);
            List<UidMessage> messages = new ArrayList<>();
            for (Message candidate : candidates) {
                if (!candidate.isExpunged()) {
                    messages.add(new UidMessage(uidFolder.getUID(candidate), candidate));
                }
            }
            messages = messages.stream().sorted(Comparator.comparingLong(UidMessage::uid))
                    .limit(properties.batchSize()).toList();
            int accepted = 0;
            long processedUid = lastUid;
            for (UidMessage message : messages) {
                long uid = message.uid();
                receive(accountKey, uidValidity, uid, message.message());
                accepted++;
                processedUid = uid;
            }
            if (messages.isEmpty()) {
                // 空 UID 窗口可以安全越过，避免 UID 大间隔导致轮询停滞。
                processedUid = scanEnd;
            }
            if (processedUid > lastUid) {
                checkpointRepository.save(accountKey, properties.mailbox(), uidValidity, processedUid);
            }
            return new EmailPollResult(accountKey, messages.size(), accepted, processedUid);
        } catch (Exception exception) {
            throw new EmailIngressException(exception);
        } finally {
            close(folder, store);
        }
    }

    private void receive(String accountKey, long uidValidity, long uid, Message message) throws Exception {
        int size = message.getSize();
        if (size > properties.maximumMessageBytes()) {
            throw new IllegalArgumentException("IMAP message exceeds maximumMessageBytes");
        }
        ParsedContent content = parse(message, accountKey, uidValidity, uid);
        String messageId = firstHeader(message, "Message-ID");
        String externalId = "imap:" + accountKey + ":" + (messageId == null
                ? uidValidity + ":" + uid : "message:" + digest(messageId));
        String conversation = firstHeader(message, "In-Reply-To");
        if (conversation == null) {
            conversation = lastReference(firstHeader(message, "References"));
        }
        if (conversation == null) {
            conversation = externalId;
        } else {
            conversation = "email-thread:" + digest(conversation);
        }
        Address[] from = message.getFrom();
        String sender = from == null || from.length == 0 ? "unknown" : from[0].toString();
        Instant receivedAt = message.getReceivedDate() == null
                ? message.getSentDate() == null ? Instant.now() : message.getSentDate().toInstant()
                : message.getReceivedDate().toInstant();
        var received = deliveryService.receive(new CreateInboundMessageRequest(properties.ownerId(), ChannelType.EMAIL,
                truncate(externalId, 512), truncate(conversation, 512), truncate(sender, 1024),
                truncate(message.getSubject(), 998), content.body(), receivedAt, content.parts()));
        for (var part : received.parts()) {
            if ("ATTACHMENT".equals(part.type())) {
                // 附件立即进入统一下载链路，轮询重放会复用同一附件任务。
                attachmentDownloadService.create(received.id(), part.id(), properties.ownerId());
            }
        }
    }

    ParsedContent parse(Part root, String accountKey, long uidValidity, long uid) throws Exception {
        StringBuilder body = new StringBuilder();
        List<CreateInboundMessagePart> parts = new ArrayList<>();
        collect(root, accountKey, uidValidity, uid, body, parts, new int[]{0});
        String text = body.toString().strip();
        if (text.isEmpty()) {
            text = "(empty email body)";
        }
        if (text.length() > MAXIMUM_BODY_CHARACTERS) {
            throw new IllegalArgumentException("IMAP body exceeds message limit");
        }
        return new ParsedContent(text, List.copyOf(parts));
    }

    private void collect(Part part, String accountKey, long uidValidity, long uid, StringBuilder body,
                         List<CreateInboundMessagePart> parts, int[] sequence) throws Exception {
        if (parts.size() >= MAXIMUM_PARTS) {
            throw new IllegalArgumentException("IMAP message contains too many parts");
        }
        String disposition = part.getDisposition();
        String fileName = part.getFileName();
        boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(disposition) || fileName != null;
        if (attachment) {
            sequence[0]++;
            String providerFileId = "imap:" + accountKey + ":" + uidValidity + ":" + uid + ":" + sequence[0];
            parts.add(new CreateInboundMessagePart("ATTACHMENT", null, attachmentType(part.getContentType()),
                    providerFileId, accountKey, null, decode(fileName), baseMimeType(part.getContentType()),
                    part.getSize() > 0 ? (long) part.getSize() : null));
            return;
        }
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            if (part.isMimeType("multipart/alternative")) {
                Part preferred = preferredAlternative(multipart);
                if (preferred != null) {
                    collect(preferred, accountKey, uidValidity, uid, body, parts, sequence);
                }
                return;
            }
            for (int index = 0; index < multipart.getCount(); index++) {
                collect(multipart.getBodyPart(index), accountKey, uidValidity, uid, body, parts, sequence);
            }
            return;
        }
        if (content instanceof String value && part.isMimeType("text/*")) {
            String normalized = part.isMimeType("text/html") ? value.replaceAll("<[^>]+>", " ") : value;
            appendBody(body, normalized);
            return;
        }
    }

    private Part preferredAlternative(Multipart multipart) throws Exception {
        Part html = null;
        for (int index = 0; index < multipart.getCount(); index++) {
            Part candidate = multipart.getBodyPart(index);
            if (candidate.isMimeType("text/plain")) {
                return candidate;
            }
            if (html == null && candidate.isMimeType("text/html")) {
                html = candidate;
            }
        }
        return html;
    }

    private void appendBody(StringBuilder body, String value) {
        if (!value.isBlank()) {
            if (!body.isEmpty()) {
                body.append('\n');
            }
            body.append(value);
            if (body.length() > MAXIMUM_BODY_CHARACTERS) {
                throw new IllegalArgumentException("IMAP body exceeds message limit");
            }
        }
    }

    private void validateConfiguration(String accountKey) {
        if (!properties.enabled() || !accountKey.equals(properties.accountKey())) {
            throw new EmailIngressDisabledException();
        }
        if (properties.ownerId() <= 0 || blank(properties.host()) || properties.port() <= 0
                || blank(properties.username()) || blank(properties.password()) || blank(properties.mailbox())
                || properties.batchSize() < 1 || properties.batchSize() > 200
                || properties.maximumMessageBytes() < 1) {
            throw new EmailIngressDisabledException();
        }
    }

    private String firstHeader(Message message, String name) throws Exception {
        String[] values = message.getHeader(name);
        return values == null || values.length == 0 ? null : sanitize(values[0]);
    }

    private String lastReference(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] references = value.trim().split("\\s+");
        return references[references.length - 1];
    }

    private String decode(String value) {
        if (value == null) {
            return "attachment";
        }
        try {
            return truncate(MimeUtility.decodeText(value), 1024);
        } catch (Exception exception) {
            return truncate(value, 1024);
        }
    }

    private String attachmentType(String contentType) {
        String mime = baseMimeType(contentType);
        if (mime.startsWith("image/")) {
            return "IMAGE";
        }
        if (mime.startsWith("video/")) {
            return "VIDEO";
        }
        if (mime.startsWith("audio/")) {
            return "RECORD";
        }
        return "FILE";
    }

    private String baseMimeType(String value) {
        if (value == null) {
            return "application/octet-stream";
        }
        return value.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String sanitize(String value) {
        return value == null ? null : value.replace("\r", "").replace("\n", "").trim();
    }

    private String truncate(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value;
        }
        return value.substring(0, maximum);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void close(Folder folder, Store store) {
        try {
            if (folder != null && folder.isOpen()) {
                folder.close(false);
            }
        } catch (Exception ignored) {
            // 只读邮箱关闭失败不覆盖原始结果。
        }
        try {
            if (store != null && store.isConnected()) {
                store.close();
            }
        } catch (Exception ignored) {
            // 连接关闭失败不覆盖原始结果。
        }
    }

    record ParsedContent(String body, List<CreateInboundMessagePart> parts) {
    }

    private record UidMessage(long uid, Message message) {
    }
}
