package com.yuyutian.mytools.gateway.service;

import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理租户、生成版本和音色三者绑定的短期样音播放票据。
 */
@Service
public class AudiobookVoicePreviewTicketService {
    private static final long TICKET_TTL_SECONDS = 15 * 60;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    /**
     * 为已审核样音签发十五分钟播放票据。
     *
     * @param ownerId 所有者标识
     * @param generationId 有声书生成运行标识
     * @param provider 音色供应商标识
     * @param voiceType 音色标识
     * @return 票据
     */
    public Ticket issue(long ownerId, UUID generationId, String provider, String voiceType) {
        UUID random = UUID.randomUUID();
        byte[] bytes = new byte[16];
        ByteBuffer.wrap(bytes).putLong(random.getMostSignificantBits()).putLong(random.getLeastSignificantBits());
        String token = HexFormat.of().formatHex(bytes);
        Ticket ticket = new Ticket(token, ownerId, generationId, provider, voiceType,
                Instant.now().plusSeconds(TICKET_TTL_SECONDS));
        tickets.put(token, ticket);
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
        return ticket;
    }

    /**
     * 解析未过期的样音播放票据。
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

    /** 已绑定所有者、运行和音色的样音播放票据。 */
    public record Ticket(String token, long ownerId, UUID generationId, String provider, String voiceType,
                         Instant expiresAt) {
    }
}
