package com.yuyutian.mytools.feedback.service.impl;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.feedback.mapper.FeedbackMapper;
import com.yuyutian.mytools.feedback.model.CreateFeedbackRequest;
import com.yuyutian.mytools.feedback.model.CreateFeedbackResponse;
import com.yuyutian.mytools.feedback.model.Feedback;
import com.yuyutian.mytools.feedback.model.FeedbackUserInfoRequest;
import com.yuyutian.mytools.feedback.service.FeedbackService;
import com.yuyutian.mytools.utils.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 问题反馈服务实现。
 */
@Service
@RequiredArgsConstructor
public class FeedbackServiceImpl implements FeedbackService {

    private static final String RECEIVED_STATUS = "RECEIVED";

    private final FeedbackMapper feedbackMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 接收并存储问题反馈。
     *
     * @param request 创建问题反馈请求
     * @return 创建结果
     */
    @Override
    @Transactional
    public CreateFeedbackResponse createFeedback(CreateFeedbackRequest request) {
        FeedbackUserInfoRequest userInfo = request.getUserInfo();
        LocalDateTime currentTime = LocalDateTime.now();

        // 将外部请求转换为持久化实体，避免直接存储传输对象。
        Feedback feedback = new Feedback();
        feedback.setId(String.valueOf(snowflakeIdGenerator.nextId()));
        feedback.setUsername(userInfo.getUsername().trim());
        feedback.setEmail(userInfo.getEmail().trim());
        feedback.setPhone(trimToNull(userInfo.getPhone()));
        feedback.setCategory(request.getCategory().trim());
        feedback.setTitle(request.getTitle().trim());
        feedback.setContent(request.getContent().trim());
        feedback.setStatus(RECEIVED_STATUS);
        feedback.setCreatedTime(currentTime);
        feedback.setUpdateTime(currentTime);

        // 确认数据确实写入，异常时由事务统一回滚。
        if (feedbackMapper.insert(feedback) != 1) {
            throw new BusinessException(ErrorCode.FEEDBACK_001);
        }
        return new CreateFeedbackResponse(feedback.getId(), feedback.getStatus());
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
