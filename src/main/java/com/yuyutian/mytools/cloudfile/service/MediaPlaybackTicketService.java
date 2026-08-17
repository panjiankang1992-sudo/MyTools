package com.yuyutian.mytools.cloudfile.service;

import com.yuyutian.mytools.cloudfile.model.MediaPlaybackTicket;
import com.yuyutian.mytools.cloudfile.model.MediaPlaybackMetrics;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 管理仅绑定单个远程媒体资源的短期播放票据。
 */
@Service
public class MediaPlaybackTicketService {

    private static final Duration TICKET_LIFETIME = Duration.ofHours(2);
    private final Map<String, TicketBinding> tickets = new ConcurrentHashMap<>();
    private final Map<String, TicketMetricsState> metrics = new ConcurrentHashMap<>();
    private final Clock clock;

    /**
     * 使用系统时钟创建票据服务。
     */
    public MediaPlaybackTicketService() {
        this(Clock.systemUTC());
    }

    MediaPlaybackTicketService(Clock clock) {
        this.clock = clock;
    }

    /**
     * 为已完成授权检查的远程文件签发播放票据。
     *
     * @param userId 当前用户ID
     * @param sessionId 登录会话令牌记录ID
     * @param accountId 远程账号ID
     * @param path 远程文件路径
     * @return 播放票据
     */
    public MediaPlaybackTicket issue(Long userId, Long sessionId, Long accountId, String path) {
        pruneExpired();
        String ticket = UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = clock.instant().plus(TICKET_LIFETIME);
        tickets.put(ticket, new TicketBinding(userId, sessionId, accountId, path, expiresAt));
        metrics.put(ticket, new TicketMetricsState());
        return new MediaPlaybackTicket(ticket, "/api/app/v1/media/tickets/" + ticket, expiresAt);
    }

    /**
     * 撤销指定登录会话签发的全部播放票据。
     *
     * @param sessionId 登录会话令牌记录ID
     */
    public void revokeSession(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        tickets.entrySet().removeIf(entry -> {
            boolean matches = sessionId.equals(entry.getValue().sessionId());
            if (matches) {
                metrics.remove(entry.getKey());
            }
            return matches;
        });
    }

    /**
     * 解析仍然有效的播放票据。
     *
     * @param ticket 随机票据
     * @return 票据绑定；无效或过期时返回空值
     */
    public TicketBinding resolve(String ticket) {
        TicketBinding binding = tickets.get(ticket);
        if (binding == null) {
            return null;
        }
        if (!binding.expiresAt().isAfter(clock.instant())) {
            tickets.remove(ticket);
            metrics.remove(ticket);
            return null;
        }
        return binding;
    }

    /**
     * 标记一个票据流开始输出。
     *
     * @param ticket 播放票据
     */
    public void streamStarted(String ticket) {
        TicketMetricsState state = metrics.get(ticket);
        if (state != null) {
            state.activeStreams.incrementAndGet();
        }
    }

    /**
     * 记录实际写给播放器的字节数。
     *
     * @param ticket 播放票据
     * @param byteCount 本次输出字节数
     */
    public void recordTransfer(String ticket, int byteCount) {
        if (byteCount <= 0) {
            return;
        }
        TicketMetricsState state = metrics.get(ticket);
        if (state != null) {
            state.transferredBytes.addAndGet(byteCount);
            state.lastTransferTime.set(clock.millis());
        }
    }

    /**
     * 标记一个票据流停止输出。
     *
     * @param ticket 播放票据
     */
    public void streamFinished(String ticket) {
        TicketMetricsState state = metrics.get(ticket);
        if (state != null) {
            state.activeStreams.updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    /**
     * 获取仍有效票据的流量快照。
     *
     * @param ticket 播放票据
     * @return 流量指标；票据无效时返回空值
     */
    public MediaPlaybackMetrics getMetrics(String ticket) {
        if (resolve(ticket) == null) {
            return null;
        }
        TicketMetricsState state = metrics.get(ticket);
        if (state == null) {
            return new MediaPlaybackMetrics(0, 0, 0);
        }
        return new MediaPlaybackMetrics(state.transferredBytes.get(), state.activeStreams.get(),
                state.lastTransferTime.get());
    }

    private void pruneExpired() {
        Instant now = clock.instant();
        tickets.entrySet().removeIf(entry -> {
            boolean expired = !entry.getValue().expiresAt().isAfter(now);
            if (expired) {
                metrics.remove(entry.getKey());
            }
            return expired;
        });
    }

    /**
     * 播放票据绑定的授权上下文。
     *
     * @param userId 用户ID
     * @param sessionId 登录会话令牌记录ID
     * @param accountId 远程账号ID
     * @param path 远程文件路径
     * @param expiresAt 过期时间
     */
    public record TicketBinding(Long userId, Long sessionId, Long accountId, String path, Instant expiresAt) {
    }

    private static final class TicketMetricsState {
        private final AtomicLong transferredBytes = new AtomicLong();
        private final AtomicInteger activeStreams = new AtomicInteger();
        private final AtomicLong lastTransferTime = new AtomicLong();
    }
}
