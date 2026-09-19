package com.yuyutian.mytools.storage.controller;

import com.yuyutian.mytools.storage.repository.StorageExecutionFenceRepository;
import com.yuyutian.mytools.storage.service.ExecutionFenceConflictException;
import com.yuyutian.mytools.storage.service.InternalAuthorizer;
import com.yuyutian.mytools.storage.service.StorageMoveService;
import com.yuyutian.mytools.storage.service.StorageNativeCopyService;
import com.yuyutian.mytools.storage.service.StorageOperationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StorageOperationFenceControllerTest {

    @Test
    void shouldRequireFenceHeadersAndRejectBeforeRemoteMoveSideEffect() throws Exception {
        StorageOperationService operationService = mock(StorageOperationService.class);
        InternalAuthorizer authorizer = mock(InternalAuthorizer.class);
        StorageMoveService moveService = mock(StorageMoveService.class);
        StorageNativeCopyService nativeCopyService = mock(StorageNativeCopyService.class);
        StorageExecutionFenceRepository fenceRepository = mock(StorageExecutionFenceRepository.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new StorageOperationController(operationService,
                        authorizer, moveService, nativeCopyService, fenceRepository))
                .setControllerAdvice(new StorageExceptionHandler()).build();
        UUID operationId = UUID.randomUUID();
        String endpoint = "/api/internal/v1/storage/operations/" + operationId + "/move/advance";

        mockMvc.perform(post(endpoint).header("Authorization", "Bearer token"))
                .andExpect(status().isBadRequest());

        doThrow(new ExecutionFenceConflictException()).when(fenceRepository).acquire(any());
        mockMvc.perform(post(endpoint)
                        .header("Authorization", "Bearer token")
                        .header("X-Task-Instance-Id", UUID.randomUUID())
                        .header("X-Task-Step-Name", "move")
                        .header("X-Task-Business-Key", operationId)
                        .header("X-Task-Fencing-Token", 6))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORAGE_030"));

        verify(moveService, never()).advance(operationId);
    }
}
