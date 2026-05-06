package com.yuyutian.mytools.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Twitter雪花算法ID生成器。
 * 生成全局唯一的64位ID。
 *
 * ID结构（从高位到低位）：
 * - 1位符号位（固定为0）
 * - 41位时间戳（毫秒）
 * - 5位数据中心ID
 * - 5位工作机器ID
 * - 12位序列号
 *
 * @author mytools
 * @since 2026-04-22
 */
@Slf4j
@Component
public class SnowflakeIdGenerator {

    // ============================ 位分配常量 ============================
    private static final long TIMESTAMP_BITS = 41L;
    private static final long DATACENTER_BITS = 5L;
    private static final long WORKER_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    // ============================ 最大值常量 ============================
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_BITS);
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_BITS);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);

    // ============================ 位移常量 ============================
    private static final long TIMESTAMP_LEFT_SHIFT = DATACENTER_BITS + WORKER_BITS + SEQUENCE_BITS;
    private static final long DATACENTER_LEFT_SHIFT = WORKER_BITS + SEQUENCE_BITS;
    private static final long WORKER_LEFT_SHIFT = SEQUENCE_BITS;

    /** Twitter原始epoch: 2010-11-04 01:42:54.657 UTC */
    private static final long TWITTER_EPOCH = 1288834974657L;

    // ============================ 实例状态 ============================
    private final long datacenterId;
    private final long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;
    private final ReentrantLock lock = new ReentrantLock();
    private static final long CLOCK_BACKWARD_THRESHOLD_MS = 100L;

    /**
     * 构造函数。
     *
     * @param datacenterId 数据中心ID (0-31)
     * @param workerId 工作机器ID (0-31)
     */
    public SnowflakeIdGenerator(long datacenterId, long workerId) {
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException(
                String.format("数据中心ID必须介于0和%d之间，当前值: %d", MAX_DATACENTER_ID, datacenterId));
        }
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                String.format("工作机器ID必须介于0和%d之间，当前值: %d", MAX_WORKER_ID, workerId));
        }
        this.datacenterId = datacenterId;
        this.workerId = workerId;
    }

    /**
     * 生成下一个唯一ID。
     *
     * @return 64位唯一ID
     */
    public long nextId() {
        lock.lock();
        try {
            return generateIdUnsafe();
        } finally {
            lock.unlock();
        }
    }

    private long generateIdUnsafe() {
        long currentTimestamp = currentTimeMillis();

        if (currentTimestamp < lastTimestamp) {
            return handleClockBackward(currentTimestamp);
        }

        if (currentTimestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                currentTimestamp = waitForNextTimestamp(currentTimestamp);
            }
        } else {
            sequence = 0;
        }

        lastTimestamp = currentTimestamp;

        return ((currentTimestamp - TWITTER_EPOCH) << TIMESTAMP_LEFT_SHIFT)
                | (datacenterId << DATACENTER_LEFT_SHIFT)
                | (workerId << WORKER_LEFT_SHIFT)
                | sequence;
    }

    private long handleClockBackward(long currentTimestamp) {
        long clockOffset = lastTimestamp - currentTimestamp;

        if (clockOffset < CLOCK_BACKWARD_THRESHOLD_MS) {
            long waitStart = System.currentTimeMillis();
            while (currentTimestamp < lastTimestamp) {
                if (System.currentTimeMillis() - waitStart > 1000) {
                    log.warn("时钟回拨等待超时，采用随机补偿策略。offset={}ms", clockOffset);
                    return generateWithRandomCompensate(currentTimestamp);
                }
                currentTimestamp = currentTimeMillis();
            }
        } else {
            log.warn("检测到严重时钟回拨，采用随机补偿。offset={}ms", clockOffset);
            return generateWithRandomCompensate(currentTimestamp);
        }

        return generateIdUnsafe();
    }

    private long generateWithRandomCompensate(long currentTimestamp) {
        long randomSequence = ThreadLocalRandom.current().nextLong(MAX_SEQUENCE + 1);
        return ((currentTimestamp - TWITTER_EPOCH) << TIMESTAMP_LEFT_SHIFT)
                | (datacenterId << DATACENTER_LEFT_SHIFT)
                | (workerId << WORKER_LEFT_SHIFT)
                | randomSequence;
    }

    private long waitForNextTimestamp(long currentTimestamp) {
        while (currentTimestamp <= lastTimestamp) {
            currentTimestamp = currentTimeMillis();
        }
        return currentTimestamp;
    }

    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * 创建默认生成器（datacenterId=1, workerId=1）。
     */
    public static SnowflakeIdGenerator createDefault() {
        return new SnowflakeIdGenerator(1, 1);
    }
}
