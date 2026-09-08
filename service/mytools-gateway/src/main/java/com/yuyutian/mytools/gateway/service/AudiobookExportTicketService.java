package com.yuyutian.mytools.gateway.service;

import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理租户、generation 和 ZIP 导出归档三者绑定的短期下载票据。
 */
@Service
public class AudiobookExportTicketService {
    private static final long TICKET_TTL_SECONDS = 12 * 60 * 60;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    /**
     * 为一个已完成的 ZIP 导出归档签发十二小时下载票据。
     *
     * @param ownerId 所有者标识
     * @param generationId 有声书 generation 标识
     * @param exportId 导出标识
     * @return 票据
     */
    public Ticket issue(long ownerId, UUID generationId, UUID exportId) {
        UUID random = UUID.randomUUID();
        byte[] bytes = new byte[16];
        ByteBuffer.wrap(bytes).putLong(random.getMostSignificantBits()).putLong(random.getLeastSignificantBits());
        String token = HexFormat.of().formatHex(bytes);
        Ticket ticket = new Ticket(token, ownerId, generationId, exportId, Instant.now().plusSeconds(TICKET_TTL_SECONDS));
        tickets.put(token, ticket);
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
        return ticket;
    }

    /**
     * 解析未过期的导出下载票据。
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

    /** 已绑定所有者、generation 和导出归档的下载票据。 */
    public record Ticket(String token, long ownerId, UUID generationId, UUID exportId, Instant expiresAt) {
    }
}
