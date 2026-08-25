package com.yuyutian.mytools.gateway.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理短期一次租户绑定媒体播放票据。
 */
@Service
public class MediaPlaybackTicketService {
    private static final long TICKET_TTL_SECONDS = 12 * 60 * 60;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    /**
     * 签发可覆盖长视频播放的十二小时票据。
     *
     * @param ownerId 所有者
     * @param mediaId 媒体标识
     * @return 票据
     */
    public Ticket issue(long ownerId, UUID mediaId) {
        byte[] bytes = new byte[16];
        UUID value = UUID.randomUUID();
        java.nio.ByteBuffer.wrap(bytes).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
        String token = HexFormat.of().formatHex(bytes);
        Ticket ticket = new Ticket(token, ownerId, mediaId, Instant.now().plusSeconds(TICKET_TTL_SECONDS));
        tickets.put(token, ticket);
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
        return ticket;
    }

    /**
     * 解析未过期票据。
     *
     * @param token 票据
     * @return 票据内容
     */
    public Ticket require(String token) {
        Ticket ticket = tickets.get(token);
        if (ticket == null || ticket.expiresAt().isBefore(Instant.now())) {
            tickets.remove(token);
            throw new GatewayNotFoundException();
        }
        return ticket;
    }

    /** 播放票据。 */
    public record Ticket(String token, long ownerId, UUID mediaId, Instant expiresAt) {
    }
}
