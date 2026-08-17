package com.yuyutian.mytools.feedback;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.feedback.mapper.FeedbackMapper;
import com.yuyutian.mytools.feedback.model.CreateFeedbackRequest;
import com.yuyutian.mytools.feedback.model.CreateFeedbackResponse;
import com.yuyutian.mytools.feedback.model.Feedback;
import com.yuyutian.mytools.feedback.model.FeedbackUserInfoRequest;
import com.yuyutian.mytools.feedback.service.impl.FeedbackServiceImpl;
import com.yuyutian.mytools.utils.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 问题反馈服务测试。
 */
@ExtendWith(MockitoExtension.class)
class FeedbackServiceImplTest {

    @Mock
    private FeedbackMapper feedbackMapper;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private FeedbackServiceImpl feedbackService;

    @BeforeEach
    void setUp() {
        feedbackService = new FeedbackServiceImpl(feedbackMapper, snowflakeIdGenerator);
    }

    @Test
    void shouldParseAndStoreFeedback() {
        CreateFeedbackRequest request = createRequest();
        when(snowflakeIdGenerator.nextId()).thenReturn(123456789L);
        when(feedbackMapper.insert(any(Feedback.class))).thenReturn(1);

        CreateFeedbackResponse response = feedbackService.createFeedback(request);

        ArgumentCaptor<Feedback> captor = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackMapper).insert(captor.capture());
        Feedback stored = captor.getValue();
        assertEquals("123456789", response.getFeedbackId());
        assertEquals("RECEIVED", response.getStatus());
        assertEquals("alice", stored.getUsername());
        assertEquals("alice@example.com", stored.getEmail());
        assertNull(stored.getPhone());
        assertEquals("FUNCTION", stored.getCategory());
        assertEquals("Search issue", stored.getTitle());
        assertEquals("Search result is empty.", stored.getContent());
    }

    @Test
    void shouldThrowBusinessExceptionWhenInsertFails() {
        CreateFeedbackRequest request = createRequest();
        when(snowflakeIdGenerator.nextId()).thenReturn(123456789L);
        when(feedbackMapper.insert(any(Feedback.class))).thenReturn(0);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> feedbackService.createFeedback(request)
        );

        assertEquals(ErrorCode.FEEDBACK_001.getCode(), exception.getCode());
    }

    private CreateFeedbackRequest createRequest() {
        FeedbackUserInfoRequest userInfo = new FeedbackUserInfoRequest();
        userInfo.setUsername(" alice ");
        userInfo.setEmail(" alice@example.com ");
        userInfo.setPhone(" ");

        CreateFeedbackRequest request = new CreateFeedbackRequest();
        request.setUserInfo(userInfo);
        request.setCategory(" FUNCTION ");
        request.setTitle(" Search issue ");
        request.setContent(" Search result is empty. ");
        return request;
    }
}
