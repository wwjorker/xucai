package com.xucai.aigc.procurement.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.jdbc.core.JdbcTemplate;

import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

class AssistantUsageAdvisorTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final AtomicBoolean canceled = new AtomicBoolean();
    final AssistantUsageAdvisor.Attempt attempt =
            new AssistantUsageAdvisor.Attempt(jdbc, "turn", 1, "GENERAL", "model", canceled::get);

    ChatResponse response(int input, int output) {
        return new ChatResponse(
                List.of(
                        new Generation(
                                new AssistantMessage("ok"),
                                ChatGenerationMetadata.builder().finishReason("STOP").build())),
                ChatResponseMetadata.builder().usage(new DefaultUsage(input, output)).build());
    }

    @Test
    void cancellationWinsOverLateSuccessAndKeepsObservedUsage() {
        attempt.begin();
        attempt.observe(response(7, 2));
        canceled.set(true);
        attempt.failSafely("MODEL_CANCELED");
        attempt.observe(response(100, 100));
        attempt.complete();
        attempt.failSafely("OTHER");
        verify(jdbc, times(1))
                .update(
                        startsWith("UPDATE assistant_model_call SET status='FAILED'"),
                        eq(7),
                        eq(2),
                        eq("MODEL_CANCELED"),
                        any());
        verify(jdbc, never()).update(contains("status='DONE'"), any(), any(), any());
    }

    @Test
    void cumulativeUsageUsesLastObservationAndCompletesOnlyOnce() {
        attempt.begin();
        attempt.observe(response(7, 2));
        attempt.observe(response(7, 5));
        attempt.complete();
        attempt.complete();
        attempt.failSafely("late");
        verify(jdbc, times(1))
                .update(contains("WHERE id=? AND status='SENT'"), eq(7), eq(5), any());
        assertThrows(RuntimeException.class, attempt::begin);
    }

    @Test
    void cancelBeforeSubscriptionPreventsAnyLedgerOrProviderCall() {
        attempt.failSafely("MODEL_CANCELED");
        assertThrows(RuntimeException.class, attempt::begin);
        verifyNoInteractions(jdbc);
    }

    @Test
    void repeatedSubscriptionNeverCallsProviderTwice() {
        var chain = mock(StreamAdvisorChain.class);
        var request =
                new ChatClientRequest(
                        new Prompt("test"), Map.of(AssistantUsageAdvisor.KEY, attempt));
        when(chain.nextStream(request))
                .thenReturn(Flux.just(new ChatClientResponse(response(2, 1), Map.of())));
        var stream = new AssistantUsageAdvisor().adviseStream(request, chain);
        stream.blockLast();
        assertThrows(RuntimeException.class, stream::blockLast);
        verify(chain, times(1)).nextStream(request);
    }

    @Test
    void unknownUsageIsNotRecordedAsZero() {
        attempt.begin();
        attempt.failSafely("MODEL_INTERRUPTED");
        verify(jdbc)
                .update(
                        contains("status='FAILED'"),
                        isNull(),
                        isNull(),
                        eq("MODEL_INTERRUPTED"),
                        any());
    }
}
