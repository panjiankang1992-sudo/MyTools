package com.yuyutian.mytools.task.executor.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskErrorDocumentTest {

    @Test
    void shouldRequireCategoryAndRetryableFlagToAgree() {
        assertTrue(new TaskErrorDocument("REMOTE_TEMPORARY_FAILURE",
                TaskErrorDocument.Category.TRANSIENT, true, "retry later").valid());
        assertFalse(new TaskErrorDocument("REMOTE_TEMPORARY_FAILURE",
                TaskErrorDocument.Category.TRANSIENT, false, "do not retry").valid());
        assertFalse(new TaskErrorDocument("bad-code",
                TaskErrorDocument.Category.PERMANENT, false, null).valid());
    }
}
