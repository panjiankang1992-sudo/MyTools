package com.yuyutian.mytools.drive.service;

import com.yuyutian.mytools.drive.model.DriveOpenTarget;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理统一网盘文件的短期只读票据。
 */
@Service
public class DriveTicketService {

    private static final long TICKET_TTL_SECONDS = 300L;
    private final Map<String, TicketBinding> tickets = new ConcurrentHashMap<>();

    /**
     * 签发绑定用户、网盘和文件的票据。
     *
     * @param target 已校验打开目标
     * @return 票据响应
     */
    public TicketResult issue(DriveOpenTarget target) {
        cleanup();
        String ticket = UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = Instant.now().plusSeconds(TICKET_TTL_SECONDS);
        tickets.put(ticket, new TicketBinding(target, expiresAt));
        return new TicketResult(ticket, "/api/app/v1/drive-tickets/" + ticket, expiresAt,
                target.name(), target.mimeType(), target.sizeBytes());
    }

    /**
     * 解析未过期票据。
     *
     * @param ticket 随机票据
     * @return 票据绑定，不存在时返回null
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

    /** 网盘票据响应。 */
    public record TicketResult(String ticket, String streamPath, Instant expiresAt, String name,
                               String mimeType, long sizeBytes) {
    }

    /** 网盘票据绑定。 */
    public record TicketBinding(DriveOpenTarget target, Instant expiresAt) {
    }
}
