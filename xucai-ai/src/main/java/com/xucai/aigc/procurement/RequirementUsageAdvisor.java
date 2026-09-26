package com.xucai.aigc.procurement;

import com.xucai.api.dto.mall.RequirementDTO;

import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;

/** Records transport usage; business validation owns the final success decision. */
final class RequirementUsageAdvisor implements CallAdvisor {
    static final String KEY = "xucai.requirementUsageAttempt";

    public String getName() {
        return "RequirementUsageAdvisor";
    }

    public int getOrder() {
        return 0;
    }

    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (!(request.context().get(KEY) instanceof Attempt attempt))
            throw new IllegalStateException("Missing requirement usage attempt");
        attempt.begin();
        var response = chain.nextCall(request);
        attempt.observe(response.chatResponse());
        return response;
    }

    static final class Attempt {
        private final ModelCallBudget budget;
        private final String runId, model;
        private final RequirementDTO input;
        private boolean started, terminal;
        private Integer inputTokens, outputTokens;

        Attempt(ModelCallBudget budget, String runId, RequirementDTO input, String model) {
            this.budget = budget;
            this.runId = runId;
            this.input = input;
            this.model = model;
        }

        synchronized void begin() {
            if (terminal || Thread.currentThread().isInterrupted())
                throw new ModelCallException("MODEL_CANCELED", "整理已取消");
            if (started) throw new IllegalStateException("Attempt already sent");
            budget.reserve(runId, input, model);
            started = true;
        }

        synchronized void observe(ChatResponse response) {
            if (terminal || response == null) return;
            var usage = response.getMetadata().getUsage();
            if (usage != null && !(usage instanceof EmptyUsage)) {
                inputTokens = usage.getPromptTokens();
                outputTokens = usage.getCompletionTokens();
            }
        }

        synchronized void complete() {
            if (terminal) throw new ModelCallException("MODEL_CANCELED", "整理已结束");
            if (!started) throw new IllegalStateException("Attempt not sent");
            budget.finish(runId, "COMPLETED", inputTokens, outputTokens, null);
            terminal = true;
        }

        synchronized void fail(String code) {
            if (terminal) return;
            terminal = true;
            if (!started) return;
            try {
                budget.finish(runId, "FAILED", inputTokens, outputTokens, code);
            } catch (org.springframework.dao.DataAccessException e) {
                org.slf4j.LoggerFactory.getLogger(RequirementUsageAdvisor.class)
                        .warn("Requirement usage finalization unresolved for {}", runId);
            }
        }
    }
}
