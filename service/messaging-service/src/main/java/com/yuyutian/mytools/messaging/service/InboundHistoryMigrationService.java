package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.CreateInboundMessagePart;
import com.yuyutian.mytools.messaging.model.InboundMessageView;
import com.yuyutian.mytools.messaging.model.LegacyInboundMessageItem;
import com.yuyutian.mytools.messaging.model.LegacyInboundMigrationBatch;
import com.yuyutian.mytools.messaging.model.LegacyInboundMigrationResult;
import com.yuyutian.mytools.messaging.repository.MessagingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;

/**
 * 校验并幂等导入历史入站消息，禁止产生实时消息事件。
 */
@Service
public class InboundHistoryMigrationService {
    private final MessagingRepository repository;

    /**
     * 创建历史消息迁移服务。
     *
     * @param repository Messaging 仓储
     */
    public InboundHistoryMigrationService(MessagingRepository repository) {
        this.repository = repository;
    }

    /**
     * 校验或导入一个有界历史消息批次。
     *
     * @param batch 迁移批次
     * @return 数量和摘要报告
     */
    @Transactional
    public LegacyInboundMigrationResult migrate(LegacyInboundMigrationBatch batch) {
        int accepted = 0;
        int skipped = 0;
        int rejected = 0;
        MessageDigest batchDigest = digest();
        Map<String, String> batchIdentities = new HashMap<>();
        for (LegacyInboundMessageItem item : batch.items()) {
            String itemDigest = itemDigest(item);
            update(batchDigest, itemDigest);
            String identity = item.sourceSystem() + "\u0000" + item.legacyMessageId();
            String previousDigest = batchIdentities.putIfAbsent(identity, itemDigest);
            if (previousDigest != null) {
                if (previousDigest.equals(itemDigest)) {
                    skipped++;
                } else {
                    rejected++;
                }
                continue;
            }
            MessagingRepository.HistoryMigrationRecord existing = repository
                    .findHistoryMigration(item.sourceSystem(), item.legacyMessageId()).orElse(null);
            if (existing != null) {
                if (existing.payloadSha256().equals(itemDigest)) {
                    skipped++;
                } else {
                    rejected++;
                }
                continue;
            }
            accepted++;
            if (!batch.dryRun()) {
                InboundMessageView message = repository.saveHistoricalInbound(item.toInboundRequest());
                repository.insertHistoryMigration(new MessagingRepository.HistoryMigrationRecord(
                        batch.migrationKey(), item.sourceSystem(), item.legacyMessageId(), itemDigest,
                        message.id(), Instant.now()));
            }
        }
        return new LegacyInboundMigrationResult(batch.dryRun(), accepted, skipped, rejected,
                HexFormat.of().formatHex(batchDigest.digest()));
    }

    private String itemDigest(LegacyInboundMessageItem item) {
        MessageDigest value = digest();
        update(value, item.sourceSystem(), item.legacyMessageId(), Long.toString(item.ownerId()),
                item.channelType().name(), item.conversationKey(), item.sender(), item.subject(), item.body(),
                item.receivedAt().toString());
        for (CreateInboundMessagePart part : item.parts()) {
            update(value, part.type(), part.text(), part.attachmentType(), part.providerFileId(),
                    part.sourceUrl(), part.fileName(), part.mimeType(),
                    part.declaredSize() == null ? null : part.declaredSize().toString());
        }
        return HexFormat.of().formatHex(value.digest());
    }

    private void update(MessageDigest digest, String... values) {
        for (String value : values) {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
