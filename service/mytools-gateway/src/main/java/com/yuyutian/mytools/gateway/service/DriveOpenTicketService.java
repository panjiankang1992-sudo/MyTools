package com.yuyutian.mytools.gateway.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 管理租户绑定的短期网盘文件票据。 */
@Service
public class DriveOpenTicketService {
    private static final long TICKET_TTL_SECONDS = 15 * 60;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    /** 签发短期只读票据。 @param ownerId 所有者 @param accountId 账户 @param path 路径 @param name 名称 @param mimeType 类型 @param sizeBytes 大小 @return 票据 */
    public Ticket issue(long ownerId, UUID accountId, String path, String name, String mimeType, long sizeBytes) {
        UUID value = UUID.randomUUID();
        byte[] bytes = java.nio.ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
        Ticket ticket = new Ticket(HexFormat.of().formatHex(bytes), ownerId, accountId, path, name,
                mimeType == null ? "" : mimeType, sizeBytes, Instant.now().plusSeconds(TICKET_TTL_SECONDS));
        tickets.put(ticket.token(), ticket);
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
        return ticket;
    }

    /** 解析有效票据。 @param token 票据字符串 @return 票据 */
    public Ticket require(String token) {
        Ticket ticket = tickets.get(token);
        if (ticket == null || ticket.expiresAt().isBefore(Instant.now())) {
            tickets.remove(token);
            throw new GatewayNotFoundException();
        }
        return ticket;
    }

    /** 网盘打开票据。 */
    public record Ticket(String token, long ownerId, UUID accountId, String path, String name,
                         String mimeType, long sizeBytes, Instant expiresAt) { }
}
