package com.xucai.aigc.procurement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.dto.mall.*;

import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.*;

class RequirementSpringAiTest {
    final ModelCallBudget budget = mock(ModelCallBudget.class);
    final ChatModel chat = mock(ChatModel.class);
    final CandidatePlanner planner = mock(CandidatePlanner.class);
    final RequirementDTO input =
            new RequirementDTO(
                    1L,
                    10003L,
                    "test",
                    "用于办公",
                    new RequirementDetails(20000L, 4, List.of(3), "办公", List.of("USB"), List.of()),
                    1L,
                    "OPEN",
                    null,
                    null);
    final DashScopeRequirementAnalyzer analyzer =
            new DashScopeRequirementAnalyzer(
                    budget,
                    mock(Validator.class),
                    new ObjectMapper(),
                    "local-test",
                    planner,
                    DashScopeRequirementAnalyzer.MODEL,
                    chat);
    static final String JSON =
            """
            {"conditions":{"budgetCents":20000,"quantity":4,"categoryIds":[3],"usage":"办公","mustHave":["USB"],"optional":[],"items":null},"questions":[]}
            """;

    ChatResponse response(String text, String finish, boolean usage) {
        var generations =
                List.of(
                        new Generation(
                                new AssistantMessage(text),
                                ChatGenerationMetadata.builder().finishReason(finish).build()));
        return usage
                ? new ChatResponse(
                        generations,
                        ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5, 15)).build())
                : new ChatResponse(generations);
    }

    @Test
    void reservePrecedesFrameworkAndConfirmedFieldsSurvive() {
        when(chat.call(any(Prompt.class)))
                .thenAnswer(
                        i -> {
                            verify(budget)
                                    .reserve("run", input, DashScopeRequirementAnalyzer.MODEL);
                            var options =
                                    (DashScopeChatOptions) ((Prompt) i.getArgument(0)).getOptions();
                            assertTrue(options.getMultiModel());
                            assertFalse(options.getInternalToolExecutionEnabled());
                            assertFalse(options.getEnableThinking());
                            return response(JSON, "STOP", true);
                        });
        assertEquals(input.structured(), analyzer.analyze(input, "run").conditions());
        verify(budget).finish("run", "COMPLETED", 10, 5, null);
        verify(chat, times(1)).call(any(Prompt.class));
    }

    @Test
    void failedReservationNeverSends() {
        doThrow(new ModelCallException("MODEL_LIMIT", "limit"))
                .when(budget)
                .reserve(anyString(), any(), anyString());
        assertThrows(ModelCallException.class, () -> analyzer.analyze(input, "run"));
        // ChatClient may read default options during construction, but must not send a request.
        verify(chat, never()).call(any(Prompt.class));
        verify(chat, never()).stream(any(Prompt.class));
    }

    @Test
    void missingUsageStaysUnknown() {
        when(chat.call(any(Prompt.class))).thenReturn(response(JSON, "STOP", false));
        analyzer.analyze(input, "run");
        verify(budget).finish("run", "COMPLETED", null, null, null);
    }

    @Test
    void truncatedOutputCannotBeAdoptedButKnownUsageIsRecorded() {
        when(chat.call(any(Prompt.class))).thenReturn(response(JSON, "LENGTH", true));
        assertEquals(
                "MODEL_OUTPUT",
                assertThrows(ModelCallException.class, () -> analyzer.analyze(input, "run"))
                        .code());
        verify(budget).finish("run", "FAILED", 10, 5, "MODEL_OUTPUT");
    }

    @Test
    void changedConfirmedBudgetStillRejected() {
        when(chat.call(any(Prompt.class)))
                .thenReturn(response(JSON.replace("20000", "10000"), "STOP", true));
        assertEquals(
                "MODEL_CONFLICT",
                assertThrows(ModelCallException.class, () -> analyzer.analyze(input, "run"))
                        .code());
        verify(budget).finish("run", "FAILED", 10, 5, "MODEL_CONFLICT");
    }

    @Test
    void providerFailureIsNotRetried() {
        when(chat.call(any(Prompt.class))).thenThrow(new IllegalStateException("failure"));
        assertThrows(ModelCallException.class, () -> analyzer.analyze(input, "run"));
        verify(chat, times(1)).call(any(Prompt.class));
        verify(budget).finish("run", "FAILED", null, null, "MODEL_UNAVAILABLE");
    }
}
