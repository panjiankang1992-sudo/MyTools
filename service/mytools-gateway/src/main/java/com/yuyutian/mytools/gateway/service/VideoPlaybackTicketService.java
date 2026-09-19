package com.yuyutian.mytools.gateway.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理短期、所有者绑定的视频播放票据。
 *
 * 原生播放器（AVPlayer）无法附加登录头，因此像媒体播放一样用 URL 里的随机票据代替鉴权：
 * 票据绑定所有者与资源，两小时后失效，且只能取到该所有者的那一个资源。
 */
@Service
public class VideoPlaybackTicketService {
    private static final long TICKET_TTL_SECONDS = 2 * 60 * 60;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    /**
     * 签发成片或原素材的播放票据。
     *
     * @param ownerId 所有者
     * @param kind 资源类别：VIDEO 成片、SOURCE 原素材
     * @param resourceId 任务标识或素材标识
     * @return 票据
     */
    public Ticket issue(long ownerId, Kind kind, UUID resourceId) {
        byte[] bytes = new byte[16];
        UUID value = UUID.randomUUID();
        java.nio.ByteBuffer.wrap(bytes).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
        String token = HexFormat.of().formatHex(bytes);
        Ticket ticket = new Ticket(token, ownerId, kind, resourceId, Instant.now().plusSeconds(TICKET_TTL_SECONDS));
        tickets.put(token, ticket);
        // 顺带清理过期票据，避免长期运行的网关无限增长。
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
        Ticket ticket = token == null ? null : tickets.get(token);
        if (ticket == null || ticket.expiresAt().isBefore(Instant.now())) {
            if (token != null) tickets.remove(token);
            throw new GatewayNotFoundException();
        }
        return ticket;
    }

    /** 资源类别。 */
    public enum Kind {
        /** 成片。 */
        VIDEO,
        /** 原始素材，用于原片对比。 */
        SOURCE
    }

    /** 播放票据。 */
    public record Ticket(String token, long ownerId, Kind kind, UUID resourceId, Instant expiresAt) {
    }
}
