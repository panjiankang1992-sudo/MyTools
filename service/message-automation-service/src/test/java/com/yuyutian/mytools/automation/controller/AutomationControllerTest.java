package com.yuyutian.mytools.automation.controller;

import com.yuyutian.mytools.automation.repository.AutomationRepository;
import com.yuyutian.mytools.automation.service.InternalRequestAuthorizer;
import com.yuyutian.mytools.automation.service.MessageAutomationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 自动化内部控制器测试。
 */
class AutomationControllerTest {

    @Test
    void shouldAcceptOnlyAtomicallyMatchedRecoverableDownloadCompletion() throws Exception {
        MessageAutomationService service = mock(MessageAutomationService.class);
        AutomationRepository repository = mock(AutomationRepository.class);
        InternalRequestAuthorizer authorizer = mock(InternalRequestAuthorizer.class);
        UUID acceptedId = UUID.randomUUID();
        UUID rejectedId = UUID.randomUUID();
        when(repository.redriveRecoverableDownloadCompletion(acceptedId)).thenReturn(true);
        when(repository.redriveRecoverableDownloadCompletion(rejectedId)).thenReturn(false);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new AutomationController(service, repository, authorizer)).build();

        mockMvc.perform(post(
                        "/internal/v1/completion-outbox/{eventId}/recoverable-download-redrive",
                        acceptedId).header("Authorization", "Bearer internal"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post(
                        "/internal/v1/completion-outbox/{eventId}/recoverable-download-redrive",
                        rejectedId).header("Authorization", "Bearer internal"))
                .andExpect(status().isNotFound());

        verify(authorizer, times(2)).requireAuthorized("Bearer internal");
        verify(repository).redriveRecoverableDownloadCompletion(acceptedId);
        verify(repository).redriveRecoverableDownloadCompletion(rejectedId);
    }
}
