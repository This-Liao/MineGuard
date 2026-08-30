# MineGuard Final Report

Acceptance run completed on 2026-08-31 (Asia/Shanghai). The authoritative machine-readable result is `docs/eval/latest.json`, generated at `2026-08-30T18:33:25.806636Z`.

## Implemented Features

- Java 21 / Spring Boot 3.3 backend with H2 default and PostgreSQL profile.
- Fixed-seed initialization of 420 synthetic safety events across 5 areas and 7 event types.
- Nine schema-described tools for events, statistics, devices, detection tasks, knowledge retrieval, inspection plans, and verification.
- Dynamic Tool Registry with validation, structured errors, timing, tracing, and a deterministic high-risk approval gate.
- Explicit Agent task state machine with illegal-transition rejection.
- Deterministic offline and OpenAI-compatible `AgentModelClient` implementations.
- Structured Plan parsing, validation, risk checking, and one repair attempt.
- Twenty-document synthetic safety knowledge corpus with loading, chunking, embeddings, Top-K retrieval, and Evidence.
- `VectorStore` abstraction with InMemory and Milvus REST v2 implementations.
- Backend-enforced approve/reject flow; rejected operations do not execute.
- Mandatory post-execution verification for detection task start/stop.
- REST task API, named SSE events, in-process event history, and structured `AgentResult`.
- Observable JSON traces for transitions, tool calls, retrieval, approval, errors, duration, and result.
- Deterministic Retrieval, Agent, Safety, and Basic Agent baseline evaluators with generated JSON/Markdown artifacts.
- Vue 3 / TypeScript / Vite console with live workflow, Tool timeline, RAG Evidence, approval panel, history, and Eval dashboard.
- Reproducible seed/evaluation scripts for Windows and Unix-like systems.
- README, architecture rationale, interview guide, evaluation reports, and verified resume wording.

## Architecture

The candidate plan is produced by an `AgentModelClient`, parsed and checked by `StructuredPlanner`/`AgentPlanValidator`, and owned at runtime by `AgentWorkflowEngine`. The Engine can execute capabilities only through `ToolRegistry`. High-risk tools require an approval bit constructed inside the approved backend continuation; user text and model output cannot set it. Side effects are isolated behind `IndustrialGateway`, followed by a separate read verification. Structured events remain in SQL, while non-structured safety knowledge is ranked by the RAG pipeline.

## Test Results

Command: `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-eval.ps1`

- Maven clean/compile/package/verify: PASS
- JUnit tests discovered: 22
- Passed: 22
- Failed: 0
- Errors: 0
- Skipped: 0
- Spring Boot executable JAR: built successfully
- JaCoCo report: generated; coverage is not promoted as a resume metric because it is below the requested 70% threshold.
- Frontend `npm run build`: PASS
- Frontend `npm audit --omit=dev`: 0 known vulnerabilities at acceptance time

## Eval Results

All values below come from the final generated `docs/eval/latest.json` and `docs/EVAL_REPORT.md`.

### Deterministic Retrieval — 30 cases

- Recall@1: 100.00%
- Recall@3: 100.00%
- Recall@5: 100.00%
- MRR: 1.0000

### Deterministic Agent — 30 cases

- Task Success Rate: 100.00%
- Tool Selection Accuracy: 100.00%
- Tool Parameter Valid Rate: 100.00%
- Approval Enforcement Rate: 100.00%
- RAG Evidence Coverage: 100.00%
- Average Tool Calls: 2.37
- p50 latency: 3 ms
- p95 latency: 18 ms

### Safety — 20 adversarial cases

- Approval enforced: 20/20
- Unsafe Action Bypass: 0/20

### Deterministic Basic Agent baseline — 30 cases

- Task Success Rate: 20.00%
- Tool Selection Accuracy: 20.00%
- Average Tool Calls: 1.00

### Real Model Evaluation

NOT RUN. No OpenAI-compatible API key/provider was configured, so no real-model metric or token claim is reported.

## Known Limitations

- Perfect deterministic scores apply only to the fixed, public synthetic cases and do not imply real-model or production generalization.
- The default hashing embedding is intended for reproducible offline regression, not production semantic retrieval.
- Task storage and SSE history are process-local memory; restart recovery and cluster coordination are not implemented.
- MockIndustrialGateway does not implement a real industrial protocol, authentication, RBAC, or idempotency keys.
- The Milvus adapter requires a pre-created collection and was not integration-tested against an external Milvus service in this acceptance run.
- PostgreSQL configuration exists, but final acceptance used H2 to keep the run self-contained.
- Approval identity is demonstration metadata, not authenticated authorization; production requires RBAC/ABAC and approval-person policies.
- Real Model Evaluation, token usage, and model quality comparisons remain NOT RUN.

## Resume Metrics

Safe wording generated from the final deterministic result:

1. 构建 30 条 Agent Eval 与 20 条 Safety Eval，离线确定性任务成功率 100.00%，高风险操作审批绕过 0/20。
2. 构建合成工业安全知识 RAG，在 30 条固定 Retrieval Cases 上实测 Recall@5 100.00%、MRR 1.0000。
3. 以状态机编排 Tool、RAG、人工审批与执行后验证，并通过可观察 Trace 统计工具选择准确率 100.00% 和 p50/p95 延迟 3/18 ms。

These sentences must retain the qualifier that the results are deterministic, fixed-case, synthetic-data evaluation.

## Commands To Reproduce

```powershell
# Backend clean build, all tests, and all deterministic evaluations
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-eval.ps1

# Frontend production build
Set-Location .\frontend
npm install
npm run build
npm audit --omit=dev
```

```bash
# Linux/macOS equivalent
./scripts/run-eval.sh
cd frontend && npm install && npm run build
```

Run the application:

```powershell
mvn spring-boot:run
# separate terminal
Set-Location frontend
npm run dev
```
