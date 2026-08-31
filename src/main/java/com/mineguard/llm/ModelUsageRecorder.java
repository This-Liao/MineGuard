package com.mineguard.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** 进程内调用额度和用量账本。不保存密钥、提示词、回复文本或模型思维链。 */
public final class ModelUsageRecorder {
    private final int maxCalls;
    private int attempts;
    private int rejected;
    private final List<Call> calls = new ArrayList<>();

    public ModelUsageRecorder(int maxCalls) {
        if (maxCalls < 0) throw new IllegalArgumentException("模型调用上限不能为负数");
        this.maxCalls = maxCalls;
    }

    public synchronized int reserve() {
        if (attempts >= maxCalls) {
            rejected++;
            throw new IllegalStateException("模型调用额度已用完或尚未授权，请设置 MINEGUARD_LLM_MAX_CALLS");
        }
        // 发送前原子占用；网络失败、超时、修复请求均不退还额度，也不自动重试。
        return ++attempts;
    }

    public synchronized void complete(int number, Instant startedAt, long latencyMs,
                                      int httpStatus, String outcome, Tokens tokens) {
        calls.add(new Call(number, startedAt, latencyMs, httpStatus, outcome, tokens));
    }

    public synchronized Snapshot snapshot() {
        int known = (int) calls.stream().filter(c -> c.tokens().complete()).count();
        int succeeded = (int) calls.stream().filter(c -> "SUCCESS".equals(c.outcome())).count();
        return new Snapshot(maxCalls, attempts, calls.size(), attempts - calls.size(), rejected,
                succeeded, calls.size() - succeeded, known, attempts - known,
                attempts > 0 && known == attempts,
                sum(Tokens::promptTokens), sum(Tokens::completionTokens), sum(Tokens::totalTokens),
                sum(Tokens::promptCacheHitTokens), sum(Tokens::promptCacheMissTokens), sum(Tokens::reasoningTokens),
                calls.stream().sorted(java.util.Comparator.comparingInt(Call::number)).toList());
    }

    private Long sum(Function<Tokens, Long> field) {
        List<Long> values = calls.stream().map(Call::tokens).map(field).filter(java.util.Objects::nonNull).toList();
        return values.isEmpty() ? null : values.stream().mapToLong(Long::longValue).sum();
    }

    public record Tokens(Long promptTokens, Long completionTokens, Long totalTokens,
                         Long promptCacheHitTokens, Long promptCacheMissTokens, Long reasoningTokens) {
        public static Tokens unknown() { return new Tokens(null, null, null, null, null, null); }

        public static Tokens from(JsonNode usage) {
            if (!usage.isObject()) return unknown();
            Long prompt = number(usage.path("prompt_tokens"));
            Long completion = number(usage.path("completion_tokens"));
            Long total = number(usage.path("total_tokens"));
            // 不推算缺失值；返回值自相矛盾时也不得算作完整实测用量。
            if (prompt != null && completion != null && total != null && prompt + completion != total) return unknown();
            return new Tokens(prompt, completion, total, number(usage.path("prompt_cache_hit_tokens")),
                    number(usage.path("prompt_cache_miss_tokens")),
                    number(usage.path("completion_tokens_details").path("reasoning_tokens")));
        }

        private static Long number(JsonNode value) {
            return value.isIntegralNumber() && value.canConvertToInt() && value.longValue() >= 0 ? value.longValue() : null;
        }

        public boolean complete() { return promptTokens != null && completionTokens != null && totalTokens != null; }
    }

    public record Call(int number, Instant startedAt, long latencyMs, int httpStatus, String outcome, Tokens tokens) {}
    // recorded* 仅合计有回执的字段；有缺失时不是整轮的总费用或完整 Token 消耗。
    public record Snapshot(int maxCalls, int attempts, int completedAttempts, int pendingAttempts, int rejectedByBudget,
                           int successfulResponses, int failedResponses, int requestsWithUsage, int requestsWithUnknownUsage,
                           boolean usageComplete, Long recordedPromptTokens, Long recordedCompletionTokens, Long recordedTotalTokens,
                           Long recordedPromptCacheHitTokens, Long recordedPromptCacheMissTokens, Long recordedReasoningTokens,
                           List<Call> calls) {}
}
