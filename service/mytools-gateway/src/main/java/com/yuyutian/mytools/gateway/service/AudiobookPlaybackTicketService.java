package com.yuyutian.mytools.gateway.service;

import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理租户、生成版本和章节三者绑定的短期有声书播放票据。
 */
@Service
public class AudiobookPlaybackTicketService {
    private static final long TICKET_TTL_SECONDS = 12 * 60 * 60;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    /**
     * 为已就绪章节签发十二小时播放票据。
     *
     * @param ownerId 所有者标识
     * @param generationId 有声书生成运行标识
     * @param chapterIndex 章节序号
     * @return 票据
     */
    public Ticket issue(long ownerId, UUID generationId, int chapterIndex) {
        UUID random = UUID.randomUUID();
        byte[] bytes = new byte[16];
        ByteBuffer.wrap(bytes).putLong(random.getMostSignificantBits()).putLong(random.getLeastSignificantBits());
        String token = HexFormat.of().formatHex(bytes);
        Ticket ticket = new Ticket(token, ownerId, generationId, chapterIndex,
                Instant.now().plusSeconds(TICKET_TTL_SECONDS));
        tickets.put(token, ticket);
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
        return ticket;
    }

    /**
     * 解析未过期的播放票据。
     *
     * @param token 票据值
     * @return 票据上下文
     */
    public Ticket require(String token) {
        Ticket ticket = tickets.get(token);
        if (ticket == null || ticket.expiresAt().isBefore(Instant.now())) {
            tickets.remove(token);
            throw new GatewayNotFoundException();
        }
        return ticket;
    }

    /** 已绑定所有者、运行和章节的播放票据。 */
    public record Ticket(String token, long ownerId, UUID generationId, int chapterIndex, Instant expiresAt) {
    }
}
