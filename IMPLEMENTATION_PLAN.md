# MineGuard Implementation Plan

## Delivery principles

- Build a runnable engineering project rather than a chat-only demo.
- Keep LLM planning advisory and make workflow transitions, tool validation, approval, and execution deterministic in backend code.
- Default to an offline, reproducible profile using H2, deterministic planning/embeddings, an in-memory vector store, and a mock industrial gateway.
- Keep PostgreSQL, Milvus, and OpenAI-compatible services behind replaceable interfaces/configuration.
- Generate every reported metric by executable evaluation code. Mark unavailable real-model results as `NOT RUN`.
- Use only synthetic, fixed-seed demo data and label it clearly.

## P0 — runnable backend and safety core

1. Bootstrap Java 21 / Spring Boot / Maven project and configuration profiles.
2. Implement domain models, fixed-seed safety-event repository, and mock industrial gateway.
3. Implement validated tool abstraction, schemas, registry, timing/error handling, and required tools.
4. Implement knowledge loading, chunking, embeddings, in-memory/Milvus vector-store implementations, retrieval, and evidence.
5. Implement structured planning with validation and one repair attempt.
6. Implement task state machine, workflow orchestration, backend-enforced human approval, execution verification, structured results, and observable traces.
7. Implement REST endpoints and SSE task event streams.
8. Cover the state machine, tools, RAG, approval enforcement, workflow, controllers, and SSE with tests.

## P1 — evaluation and documentation

1. Add at least 30 retrieval cases, 30 agent cases, and 20 adversarial safety cases.
2. Implement deterministic retrieval, agent, safety, and baseline evaluation.
3. Add Windows and Unix one-command evaluation scripts.
4. Generate `docs/eval/latest.json`, `docs/eval/retrieval-latest.json`, `docs/EVAL_REPORT.md`, and `docs/RESUME_METRICS.md` from actual runs.
5. Write README, architecture decisions, interview guide, API examples, and limitations.

## P2 — demonstrable web UI

1. Build a lightweight Vue 3 + TypeScript + Vite application.
2. Provide Agent Console, live workflow/SSE timeline, tool calls, evidence, approval panel, task history/detail, and evaluation dashboard.
3. Build the production frontend bundle as part of final verification.

## Final acceptance

1. Run clean backend build and the full unit/integration test suite.
2. Run retrieval, agent, baseline, and safety evaluations.
3. Run frontend production build.
4. Verify all README/report metrics against generated artifacts.
5. Generate `FINAL_REPORT.md`, inspect the complete diff, initialize/commit local Git, and confirm a clean working tree.
