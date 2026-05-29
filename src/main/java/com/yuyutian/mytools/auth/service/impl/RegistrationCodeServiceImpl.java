package com.yuyutian.mytools.auth.service.impl;

import com.yuyutian.mytools.auth.Model.EmailVerificationCode;
import com.yuyutian.mytools.auth.Model.RegisterCodeRequest;
import com.yuyutian.mytools.auth.Model.RegisterRequest;
import com.yuyutian.mytools.auth.mapper.EmailVerificationCodeMapper;
import com.yuyutian.mytools.auth.service.RegistrationCodeService;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.user.mapper.UserMapper;
import com.yuyutian.mytools.utils.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 注册验证码服务实现类。
 *
 * @author mytools
 * @since 2026-05-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationCodeServiceImpl implements RegistrationCodeService {

    private static final String REGISTER_PURPOSE = "REGISTER";
    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final int CODE_BOUND = 1_000_000;
    private static final int CODE_TTL_HOURS = 1;
    private static final long RESEND_INTERVAL_SECONDS = 60;

    private final EmailVerificationCodeMapper verificationCodeMapper;
    private final UserMapper userMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${registration.mail.from:no-reply@mytools.local}")
    private String mailFrom;

    @Value("${registration.mail.dev-log-code:true}")
    private boolean devLogCode;

    /**
     * 发送注册邮箱验证码。
     *
     * @param request 注册验证码请求
     */
    @Override
    @Transactional
    public void sendRegisterCode(RegisterCodeRequest request) {
        assertRegisterFieldsAvailable(request.getUsername(), request.getEmail());

        LocalDateTime now = LocalDateTime.now();
        EmailVerificationCode latest = verificationCodeMapper.findLatestUnusedByEmail(request.getEmail(), REGISTER_PURPOSE);
        if (latest != null && Duration.between(latest.getCreateTime(), now).getSeconds() < RESEND_INTERVAL_SECONDS) {
            // 发送间隔过短时直接拒绝，避免邮箱被频繁轰炸。
            throw new BusinessException(ErrorCode.USER_012);
        }

        String code = generateCode();
        EmailVerificationCode entity = new EmailVerificationCode();
        entity.setId(snowflakeIdGenerator.nextId());
        entity.setPurpose(REGISTER_PURPOSE);
        entity.setUsername(request.getUsername());
        entity.setEmail(request.getEmail());
        entity.setPhone(request.getPhone());
        entity.setCodeHash(hashCode(code));
        entity.setExpireTime(now.plusHours(CODE_TTL_HOURS));
        entity.setStatus(ACTIVE_STATUS);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);

        // 新验证码生成后，旧验证码统一失效。
        verificationCodeMapper.invalidateUnusedByEmail(request.getEmail(), REGISTER_PURPOSE, now);
        verificationCodeMapper.insert(entity);
        sendMail(request.getEmail(), code);
        log.info("Register verification code created: email={}, expiresAt={}", request.getEmail(), entity.getExpireTime());
    }

    /**
     * 校验注册邮箱验证码。
     *
     * @param request 注册请求
     */
    @Override
    @Transactional
    public void verifyRegisterCode(RegisterRequest request) {
        EmailVerificationCode latest = verificationCodeMapper.findLatestUnused(
                request.getUsername(),
                request.getEmail(),
                request.getPhone(),
                REGISTER_PURPOSE
        );
        if (latest == null) {
            // 找不到对应验证码时按验证码错误处理。
            throw new BusinessException(ErrorCode.USER_010);
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(latest.getExpireTime())) {
            // 已过期验证码需要立即置为失效状态。
            verificationCodeMapper.markExpired(latest.getId(), now);
            throw new BusinessException(ErrorCode.USER_011);
        }

        if (!hashCode(request.getVerificationCode()).equals(latest.getCodeHash())) {
            // 验证码不匹配时不消耗验证码，便于用户重新输入。
            throw new BusinessException(ErrorCode.USER_010);
        }

        verificationCodeMapper.markUsed(latest.getId(), now);
    }

    private void assertRegisterFieldsAvailable(String username, String email) {
        if (userMapper.existsByUsername(username) > 0) {
            // 用户名已存在时不发送验证码。
            throw new BusinessException(ErrorCode.USER_002);
        }
        if (userMapper.existsByEmail(email) > 0) {
            // 邮箱已存在时不发送验证码。
            throw new BusinessException(ErrorCode.USER_007);
        }
    }

    private void sendMail(String email, String code) {
        if (devLogCode) {
            // 开发环境只打印验证码，避免本地未配置授权码时误触发真实邮件。
            log.warn("Register verification code dev log: email={}, code={}", email, code);
            return;
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new BusinessException(ErrorCode.SYS_001);
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(email);
        message.setSubject("MyTools register verification code");
        message.setText("Your MyTools verification code is " + code + ". It expires in 1 hour.");
        mailSender.send(message);
    }

    private String generateCode() {
        int code = secureRandom.nextInt(CODE_BOUND);
        return String.format("%06d", code);
    }

    private String hashCode(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException(ErrorCode.SYS_001);
        }
    }
}
