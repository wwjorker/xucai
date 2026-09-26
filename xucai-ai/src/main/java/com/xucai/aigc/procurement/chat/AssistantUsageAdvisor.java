package com.xucai.aigc.procurement.chat;

import com.xucai.aigc.procurement.ModelCallException;

import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.jdbc.core.JdbcTemplate;

import reactor.core.publisher.Flux;

import java.util.*;
import java.util.function.BooleanSupplier;

/** One ledger entry per provider attempt. ChatClient context is never added to the prompt. */
final class AssistantUsageAdvisor implements CallAdvisor, StreamAdvisor {
    static final String KEY = "xucai.usageAttempt";

    public String getName() {
        return "AssistantUsageAdvisor";
    }

    public int getOrder() {
        return 0;
    }

    private Attempt attempt(ChatClientRequest request) {
        if (!(request.context().get(KEY) instanceof Attempt attempt))
            throw new IllegalStateException("Missing usage attempt");
        return attempt;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        var attempt = attempt(request);
        attempt.begin();
        try {
            var response = chain.nextCall(request);
            attempt.observe(response.chatResponse());
            attempt.complete();
            return response;
        } catch (RuntimeException e) {
            attempt.failSafely(code(e));
            throw e;
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest request, StreamAdvisorChain chain) {
        var attempt = attempt(request);
        return Flux.defer(
                () -> {
                    attempt.begin();
                    return chain.nextStream(request)
                            .doOnNext(r -> attempt.observe(r.chatResponse()))
                            .doOnComplete(attempt::complete)
                            .doOnError(e -> attempt.failSafely(code(e)))
                            .doOnCancel(() -> attempt.failSafely("MODEL_INTERRUPTED"));
                });
    }

    private static String code(Throwable error) {
        if (error instanceof ModelCallException model) return model.code();
        if (error instanceof org.springframework.web.client.RestClientResponseException http)
            return "MODEL_HTTP_" + http.getStatusCode().value();
        if (error
                instanceof
                org.springframework.web.reactive.function.client.WebClientResponseException http)
            return "MODEL_HTTP_" + http.getStatusCode().value();
        return "MODEL_NETWORK";
    }

    static final class Attempt {
        private final JdbcTemplate jdbc;
        private final String id = UUID.randomUUID().toString(), turn, agent, model;
        private final int round;
        private final BooleanSupplier canceled;
        private boolean started, terminal;
        private Integer input, output;
        private String finish = "";

        Attempt(
                JdbcTemplate jdbc,
                String turn,
                int round,
                String agent,
                String model,
                BooleanSupplier canceled) {
            this.jdbc = jdbc;
            this.turn = turn;
            this.round = round;
            this.agent = agent;
            this.model = model;
            this.canceled = canceled;
        }

        synchronized void begin() {
            if (started || terminal || canceled.getAsBoolean())
                throw new ModelCallException("MODEL_REPLAY", "请求已发送或已停止，未重复调用");
            jdbc.update(
                    "INSERT INTO assistant_model_call(id,turn_id,round_no,agent,model,status)"
                        + " VALUES(?,?,?,?,?,'SENT')",
                    id,
                    turn,
                    round,
                    agent,
                    model);
            started = true;
        }

        synchronized void observe(ChatResponse response) {
            if (terminal || response == null) return;
            var usage = response.getMetadata().getUsage();
            if (usage != null && !(usage instanceof EmptyUsage)) {
                input = usage.getPromptTokens();
                output = usage.getCompletionTokens();
            }
            if (response.getResult() != null) {
                var reason = response.getResult().getMetadata().getFinishReason();
                if (reason != null && !reason.isBlank() && !reason.equalsIgnoreCase("null"))
                    finish = reason.toUpperCase(Locale.ROOT);
            }
        }

        synchronized void complete() {
            if (terminal) return;
            if (canceled.getAsBoolean()) {
                failSafely("MODEL_CANCELED");
                return;
            }
            if (!Set.of("STOP", "TOOL_CALLS").contains(finish)) {
                failSafely("MODEL_OUTPUT");
                return;
            }
            if (!started) throw new IllegalStateException("Attempt was not started");
            jdbc.update(
                    "UPDATE assistant_model_call SET"
                        + " status='DONE',input_tokens=?,output_tokens=?,finished_at=CURRENT_TIMESTAMP(3)"
                        + " WHERE id=? AND status='SENT'",
                    input,
                    output,
                    id);
            terminal = true;
        }

        synchronized void failSafely(String code) {
            if (terminal) return;
            if (!started) {
                terminal = true;
                return;
            }
            try {
                jdbc.update(
                        "UPDATE assistant_model_call SET"
                            + " status='FAILED',input_tokens=?,output_tokens=?,error_code=?,finished_at=CURRENT_TIMESTAMP(3)"
                            + " WHERE id=? AND status='SENT'",
                        input,
                        output,
                        canceled.getAsBoolean() ? "MODEL_CANCELED" : code,
                        id);
                terminal = true;
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(AssistantUsageAdvisor.class)
                        .error("Usage terminal write failed; attempt={} remains unresolved", id);
            }
        }
    }
}
