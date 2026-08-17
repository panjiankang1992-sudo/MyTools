package com.yuyutian.mytools.localfile.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理 App 访问 MyTools 本地媒体文件的短期票据。
 */
@Service
public class LocalMediaTicketService {

    private static final long TICKET_TTL_SECONDS = 300L;
    private final Map<String, TicketBinding> tickets = new ConcurrentHashMap<>();

    /**
     * 为当前登录会话签发本地媒体播放票据。
     *
     * @param userId 用户ID。
     * @param sessionId 会话ID。
     * @param fileId 文件ID。
     * @return 票据描述。
     */
    public TicketResult issue(Long userId, Long sessionId, Long fileId) {
        cleanup();
        String ticket = UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = Instant.now().plusSeconds(TICKET_TTL_SECONDS);
        tickets.put(ticket, new TicketBinding(userId, sessionId, fileId, expiresAt));
        return new TicketResult(ticket, "/api/app/v1/local-media/tickets/" + ticket, expiresAt);
    }

    /**
     * 解析仍在有效期内的票据。
     *
     * @param ticket 票据值。
     * @return 票据绑定，不存在或过期时返回null。
     */
    public TicketBinding resolve(String ticket) {
        if (ticket == null || !ticket.matches("[a-f0-9]{32}")) {
            return null;
        }
        TicketBinding binding = tickets.get(ticket);
        if (binding != null && binding.expiresAt().isAfter(Instant.now())) {
            return binding;
        }
        tickets.remove(ticket);
        return null;
    }

    private void cleanup() {
        Instant now = Instant.now();
        tickets.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    /**
     * 本地媒体票据响应。
     */
    public record TicketResult(String ticket, String streamPath, Instant expiresAt) {
    }

    /**
     * 本地媒体票据与用户会话的绑定。
     */
    public record TicketBinding(Long userId, Long sessionId, Long fileId, Instant expiresAt) {
    }
}
