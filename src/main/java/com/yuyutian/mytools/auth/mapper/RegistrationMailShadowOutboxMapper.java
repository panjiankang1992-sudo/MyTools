package com.yuyutian.mytools.auth.mapper;

import com.yuyutian.mytools.auth.messaging.RegistrationMailShadowOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 注册邮件影子 Outbox 数据访问层。
 */
@Mapper
public interface RegistrationMailShadowOutboxMapper {

    /** 插入 Outbox。 @param record 记录 @return 影响行数 */
    int insert(RegistrationMailShadowOutbox record);

    /** 查询可领取记录。 @param now 当前时间 @param limit 上限 @return 记录列表 */
    List<RegistrationMailShadowOutbox> findReady(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 条件领取。 @param verificationId 验证码标识 @param now 当前时间 @param claimedUntil 领取截止时间 @return 影响行数 */
    int claim(@Param("verificationId") Long verificationId, @Param("now") LocalDateTime now,
              @Param("claimedUntil") LocalDateTime claimedUntil);

    /** 确认投递。 @param verificationId 验证码标识 @param claimedUntil 领取令牌 @param now 当前时间 @return 影响行数 */
    int acknowledge(@Param("verificationId") Long verificationId,
                    @Param("claimedUntil") LocalDateTime claimedUntil, @Param("now") LocalDateTime now);

    /** 记录失败并安排重试。 @param verificationId 验证码标识 @param status 状态 @param attemptCount 尝试次数 @param availableAt 下次时间 @param errorCode 错误码 @param now 当前时间 @return 影响行数 */
    int fail(@Param("verificationId") Long verificationId, @Param("claimedUntil") LocalDateTime claimedUntil,
             @Param("status") String status,
             @Param("attemptCount") int attemptCount, @Param("availableAt") LocalDateTime availableAt,
             @Param("errorCode") String errorCode, @Param("now") LocalDateTime now);
}
