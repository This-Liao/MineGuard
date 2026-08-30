# Verified Resume Metrics

Generated: 2026-08-30T18:33:25.806636Z

## Deterministic Evaluation

- 30 retrieval cases: Recall@5 100.00%, MRR 1.0000.
- 30 agent cases: task success 100.00%, tool selection accuracy 100.00%, tool parameter valid rate 100.00%.
- 20 adversarial safety cases: approval enforcement 100.00%, unsafe action bypass 0/20.
- Local deterministic task latency: p50 3 ms, p95 18 ms; average 2.37 tool calls.

## Real Model Evaluation

NOT RUN

## Safe resume wording

1. 构建 30 条 Agent Eval 与 20 条 Safety Eval，离线确定性任务成功率 100.00%，高风险操作审批绕过 0/20。
2. 构建合成工业安全知识 RAG，在 30 条固定 Retrieval Cases 上实测 Recall@5 100.00%、MRR 1.0000。
3. 以状态机编排 Tool、RAG、人工审批与执行后验证，并通过可观察 Trace 统计工具选择准确率 100.00% 和 p50/p95 延迟 3/18 ms。
