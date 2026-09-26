package com.xucai.aigc.procurement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;

class RequirementUsageAdvisorTest {
    final ModelCallBudget budget = mock(ModelCallBudget.class);
    final RequirementUsageAdvisor.Attempt attempt =
            new RequirementUsageAdvisor.Attempt(budget, "run", null, "model");

    @Test
    void cancellationBeforeWorkerStartsPreventsSending() {
        attempt.fail("MODEL_CANCELED");
        assertThrows(ModelCallException.class, attempt::begin);
        verifyNoInteractions(budget);
    }

    @Test
    void lateResponseCannotChangeCanceledLedger() {
        attempt.begin();
        attempt.fail("MODEL_CANCELED");
        attempt.observe(
                new ChatResponse(
                        List.of(),
                        ChatResponseMetadata.builder().usage(new DefaultUsage(12, 3, 15)).build()));
        assertThrows(ModelCallException.class, attempt::complete);
        attempt.fail("MODEL_UNAVAILABLE");
        verify(budget).reserve("run", null, "model");
        verify(budget).finish("run", "FAILED", null, null, "MODEL_CANCELED");
        verifyNoMoreInteractions(budget);
    }

    @Test
    void responseWaitsForBusinessValidation() {
        attempt.begin();
        attempt.observe(
                new ChatResponse(
                        List.of(),
                        ChatResponseMetadata.builder().usage(new DefaultUsage(12, 3, 15)).build()));
        verify(budget, never()).finish(any(), any(), any(), any(), any());
        attempt.fail("MODEL_CONFLICT");
        verify(budget).finish("run", "FAILED", 12, 3, "MODEL_CONFLICT");
    }

    @Test
    void attemptCannotBeSentTwice() {
        attempt.begin();
        assertThrows(IllegalStateException.class, attempt::begin);
        attempt.complete();
        attempt.fail("MODEL_CANCELED");
        verify(budget).reserve("run", null, "model");
        verify(budget).finish("run", "COMPLETED", null, null, null);
        verifyNoMoreInteractions(budget);
    }
}
