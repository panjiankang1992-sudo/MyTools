package com.yuyutian.mytools.messaging.service;

import com.yuyutian.mytools.messaging.model.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** 相同飞书 Webhook 的多个绑定共享持久启动间隔，限制跨实例突发。 */
@Component
public class OutboundRateLimiter {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    /** 注入共享配额表。 */
    public OutboundRateLimiter(JdbcTemplate jdbc,TransactionTemplate tx){this.jdbc=jdbc;this.tx=tx;}
    /** 预留一秒一条的启动时刻；积压超过十秒时明确拒绝而不占用新配额。 */
    public long reserve(String address,Instant now) {
        String key;
        try {key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(address.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new OutboundException(ErrorCode.OUTBOUND_DISABLED);}
        try {jdbc.update("INSERT INTO messaging_channel_budget(bucket_key,next_start_ms) VALUES(?,0)",key);}
        catch(DuplicateKeyException ignored){ /* 已存在的桶由下方行锁协调。 */ }
        Long wait=tx.execute(status->{
            long next=jdbc.queryForObject("SELECT next_start_ms FROM messaging_channel_budget WHERE bucket_key=? FOR UPDATE",Long.class,key);
            long start=Math.max(now.toEpochMilli(),next);
            long delay=start-now.toEpochMilli();
            if(delay>10000)throw new OutboundException(ErrorCode.OUTBOUND_PROVIDER_REJECTED);
            jdbc.update("UPDATE messaging_channel_budget SET next_start_ms=? WHERE bucket_key=?",start+1100,key);
            return delay;
        });
        return java.util.Objects.requireNonNull(wait);
    }
}
