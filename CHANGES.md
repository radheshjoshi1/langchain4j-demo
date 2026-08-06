# Changelog — LLM App Evaluation Infrastructure

## Date: 2026-07-28

---

## Overview

Added a complete evaluation (eval) layer under `src/main/java/org/example/eval/` to support:
- Offline dataset experiments (batch eval)
- Online / live response scoring while the app is running

---

## New Files

### `DatasetItem.java`
- POJO representing a single evaluation record
- Fields: `id`, `input`, `output`, `expectedOutput`, `score`
- Added constructors, getters/setters, and `toString()`

### `DatasetUploader.java`
- Fetches dataset items from a Langfuse dataset via REST API
  - `GET /api/public/v2/datasets/{datasetName}/items`
- Uploads evaluation scores back to Langfuse
  - `POST /api/public/scores`
- Methods:
  - `fetchDatasetItems(datasetName)` → `List<DatasetItem>`
  - `uploadScore(itemId, runName, scoreName, value, comment)`
  - `uploadScores(items, runName, scoreName)` — batch convenience

### `CodeEvaluator.java`
- Rule-based, deterministic evaluator (no LLM API call)
- Scoring strategy — **binary 0 or 1**:
  - `1` — exact match (case-insensitive) or regex pattern match
  - `0` — no match
- Methods:
  - `evaluate(DatasetItem)` → score
  - `evaluateAll(List<DatasetItem>)` — batch, prints `X/N correct`

### `LLMEvaluator.java`
- LLM-as-a-judge evaluator using the OpenAI-compatible endpoint
- Scoring strategy — **binary 0 or 1**:
  - Prompt instructs the LLM: *"Respond with ONLY 1 if correct, or 0 if incorrect"*
  - Removes ambiguous scale (0–100 was dropped because 50 and 100 felt equivalent)
- Methods:
  - `evaluate(DatasetItem)` → score
  - `evaluateAll(List<DatasetItem>, runName)` — batch + auto-uploads to Langfuse

### `EvalRunner.java`
- Orchestrates the full **offline experiment pipeline**:
  1. Fetch dataset items from Langfuse (`DatasetUploader`)
  2. Run each input through a blocking `OpenAiChatModel` agent
  3. Score outputs with `CodeEvaluator`, `LLMEvaluator`, or both
  4. Upload scores to Langfuse
- `EvaluatorType` enum: `CODE`, `LLM`, `BOTH`
- Usage:
  ```java
  new EvalRunner("my-dataset", "eval-run-v1").run(EvaluatorType.LLM);
  ```

### `LiveEvalService.java`
- Evaluates **every live agent response** while the app is running
- Runs on a single **daemon background thread** — never blocks the SSE stream
- After each complete response, scores it with `LLMEvaluator` and uploads to Langfuse under the `"live-eval"` run label
- Gracefully drains pending jobs on `shutdown()`
- Methods:
  - `submit(userInput, agentOutput)` — non-blocking, queues async eval
  - `shutdown()` — called in JVM shutdown hook

---

## Modified Files

### `SseServer.java`
- Added overloaded constructor accepting optional `LiveEvalService`
  ```java
  new SseServer(port, agent, liveEvalService)
  ```
- Accumulates all streamed tokens into a `StringBuilder`
- After `onCompleteResponse`, calls `liveEvalService.submit(input, fullResponse)` (non-blocking)
- Backward compatible — original constructor (`port`, `agent`) still works with eval disabled

### `Main.java`
- Imports `LiveEvalService`
- Instantiates `LiveEvalService("live-eval")` at startup
- Passes it to `SseServer` constructor
- Added `liveEval.shutdown()` to the JVM shutdown hook alongside OTel flush

---

## Scoring Design Decision

| Metric | Old | New |
|---|---|---|
| Scale | 0 – 100 | 0 or 1 (binary) |
| Reason | Ambiguous — LLM could treat 50 and 100 as similar | Clear binary pass/fail judgment |

---

## Architecture Summary

```
[ Offline Experiment ]
  EvalRunner
    ├── DatasetUploader.fetchDatasetItems()
    ├── OpenAiChatModel (blocking agent)
    ├── CodeEvaluator / LLMEvaluator
    └── DatasetUploader.uploadScores()

[ Live Evaluation ]
  SseServer (onCompleteResponse)
    └── LiveEvalService.submit()          ← non-blocking
          └── (background thread)
                ├── LLMEvaluator.evaluate()
                └── DatasetUploader.uploadScore()
```

---

## Date: 2026-08-05

The eval layer above has since been reworked: `DatasetUploader`, `CodeEvaluator`,
`LLMEvaluator`, `EvalRunner`, and `LiveEvalService` are gone, replaced by
`DatasetItemRunner` (dataset runs) and `DeterministicEvaluator`. This entry covers today's
changes on top of that current shape.

### `DatasetItemRunner.java` (modified): own the trace per dataset item, add LLM-as-judge scoring

- Each dataset item run now starts its own root span (`dataset-item-run`) and makes it
  current for the whole agent call, instead of using `item.getSourceTraceId()`. This means
  `LangfuseOtelListener`'s per-LLM-call spans are parented under it and share its trace ID,
  so the score/run-item posted to Langfuse points at the trace this run actually produced,
  not whatever historical trace the dataset item was originally captured from.
- Trace-level attributes (`langfuse.trace.name/environment/tags`) are now set on this root
  span, since Langfuse reads trace-level metadata from the root observation only.
- Trace/run-level input and output (`langfuse.observation.input` / `.output`) are now set on
  the root span too — using the user prompt and the full streamed agent response — so the
  Langfuse dataset run list and trace view show non-empty input/output. Previously only the
  nested "chat ..." generation span (from `LangfuseOtelListener`) carried this data, which is
  a child observation, not what Langfuse reads for the trace/run itself.
- Added `LlmJudgeEvaluator` scoring alongside the existing `DeterministicEvaluator`: after
  each item's response completes, the judge scores it and the result is posted to Langfuse
  as an additional score (`llm_judge`) on the same trace.

### New: `org.example.eval.judge` package (LLM-as-judge)

- **`LlmJudgeEvaluator`** — builds an `OpenAiChatModel` (`gpt-4o-mini` via the LangChain4j
  demo endpoint) once and reuses it across items. Scores `actualOutput` against
  `expectedOutput` (or, if none was provided, against the question alone) on a 0.0–1.0 scale,
  clamped and NaN-safe. Judge failures are caught and posted as a `0.0` score with the
  exception message as the rationale, rather than failing the run.
- **`LlmJudgeAssistant`** — LangChain4j `AiServices` interface defining the judge's system
  prompt (strict on factual correctness, ignored questions, contradictions) and structured
  output contract.
- **`JudgeResult`** — structured output DTO (`score`, `rationale`) that the assistant
  populates directly from the model response.

### Known limitation (not fixed by code)

Traces can take up to ~10 minutes to appear in the Langfuse dashboard. This is Langfuse
Cloud's ingestion queue latency on the free/hobby tier, not something controlled by the
exporter config (`OpenTelemetryConfig`'s `BatchSpanProcessor` already flushes every 5s).
Reducing it requires either upgrading to a paid Langfuse Cloud plan or self-hosting Langfuse.

### `HttpUtil.java` (modified): surface non-2xx Langfuse responses instead of swallowing them

- `sendPostRequest` previously returned `response.body()` unconditionally and only threw on
  `IOException`/`InterruptedException` (network-level failure). A Langfuse API rejection
  (e.g. a 4xx on `/api/public/scores`) was never surfaced — the call site logged success
  regardless. Root cause of a real incident: trace `f86441b54ef2b0f9399b65cf0e33fb2b` showed
  only the `execution_success` score in Langfuse even though the console reported all four
  scores (`execution_success`, `latency_within_budget`, `intent_match`, `llm_judge`) posted
  successfully.
- Now throws a `RuntimeException` on any status code `>= 400`, including the status and
  response body in the message, so callers' existing catch blocks actually fire with the real
  Langfuse error instead of silently losing it.

### `DatasetItemRunner.java` (modified): log score-post failures with full RCA context, push a failure marker score

- `postScoreToLangfuse`'s catch block now logs the score name, id, traceId, value, and
  dataType alongside the underlying error (which, after the `HttpUtil` fix, includes the
  Langfuse HTTP status/body) — enough in a single log line to root-cause a failed post without
  reproducing it.
- Added `postScoreFailureMarker`: when a score fails to post, it best-effort posts a companion
  `<scoreName>_post_failed` BOOLEAN score (deterministic id, own try/catch, no retries) to the
  same trace, so the failure is visible directly in Langfuse rather than only in local run
  output that's gone once the console is closed.

### Root cause found via the `HttpUtil` fix: Langfuse Cloud rate limit, not a dataType mismatch

Running the dataset with the fixes above showed the real error for the first time: Langfuse
Cloud enforces **30 requests/60s** on `/api/public/scores` for this key, and a 24-item dataset
run (dataset-run-item + up to 4 scores per item) blows through that quickly, producing
sustained `429 Rate limit exceeded` responses — not the score-config/dataType mismatch
originally suspected from trace `f86441b54ef2b0f9399b65cf0e33fb2b`.

That run also showed the failure-marker mechanism actively working against itself: each failed
score triggered a marker POST to the *same* rate-limited endpoint, which also 429'd, doubling
request volume while already over quota. Fixed with two follow-up changes:

### `HttpStatusException.java` (new) and `HttpUtil.java` (modified): typed status errors + rate limiting

- Added `HttpStatusException` (carries the HTTP status code) so callers can branch on status
  (e.g. 429) instead of string-matching the exception message.
- `sendPostRequest` and `sendGetRequest` now throw `HttpStatusException` on non-2xx (previously
  only `sendPostRequest` checked status, and with a plain `RuntimeException`).
- Added `RateLimiter` (new, sliding-window, `Deque<Instant>`-based) and wired a shared
  `25 requests/60s` instance into both `HttpUtil` methods — under Langfuse's observed 30/60s
  limit with margin — so a dataset run paces itself instead of bursting into 429s.

### `DatasetItemRunner.java` (modified): don't post a failure marker for a 429

- `postScoreToLangfuse`'s catch block now checks for `HttpStatusException` with status 429 and
  skips `postScoreFailureMarker` in that case, logging why instead. Posting a marker for a
  rate-limit failure just spends more of the same exhausted quota and is guaranteed to fail too
  — the `HttpUtil` rate limiter is what actually prevents hitting 429 in the first place.

---

## Date: 2026-08-06

### Bug: a single dataset run showed up as two Langfuse sessions instead of one

A 24-item dataset run appeared in the Langfuse dashboard split across two sessions (22 traces
+ 5 traces), rather than one session of 24. Root cause: two different, unrelated Langfuse
session ids were being stamped onto the same trace.

- `Main.java` generated a process-wide session id and passed it into
  `AgentFactory.createAgent(sessionId)` → `LangfuseOtelListener`, which stamped
  `langfuse.session.id` on every **child** "chat ..." generation span it creates per LLM call
  (`LangfuseOtelListener.onRequest`).
- `DatasetItemRunner.runDataset()` separately generated its own session id per run and stamped
  it on the **root** `dataset-item-run` span for each item (`DatasetItemRunner.run`).

Both spans belong to the same OTel trace, so every trace carried two conflicting
`langfuse.session.id` values. Child generation spans end (and export) as soon as their LLM call
completes, while the root span only ends after scoring/posting finishes — so the two
conflicting values raced to be ingested, and Langfuse inconsistently grouped individual traces
under whichever session id it processed for that trace, some items' extra tool-call round trip
(a second child generation span) increasing the odds of a trace being counted under both.

### `Main.java` (modified): stop passing a conflicting session id into the agent used for dataset runs

- `AgentFactory.createAgent(sessionId)` → `AgentFactory.createAgent()`. The session id
  generated in `main` is no longer passed to the agent/listener, since the active path
  (dataset eval run) already gets its session grouping from `DatasetItemRunner`'s own
  per-run session id on each item's root span. The local `sessionId` variable and comment are
  kept, since `runConsoleChat` (currently commented out) has no other span setting a session
  and would need it passed back in if re-enabled.

### `Main.java` (modified): pick dataset run vs. console chat via a CLI arg instead of commenting code out

- Switching between the dataset eval run and the console chat previously meant editing
  `main()` — commenting one call back in and the other out — and, per the change above, that
  editing also had to remember to move the `sessionId` argument along with it, since only
  console chat needs it (it has no per-turn root span of its own to carry a session id, unlike
  each dataset item's `dataset-item-run` root span).
- Added `boolean consoleMode = args.length > 0 && "console".equalsIgnoreCase(args[0])`. Both
  branches now live in `main()` behind that flag, and the agent is built with
  `AgentFactory.createAgent(sessionId)` when `consoleMode` is true or
  `AgentFactory.createAgent()` (no session id) otherwise — so each mode always gets the right
  session-tagging behavior without hand-editing.
- Usage: `mvn exec:java -Dexec.args="console"` (or `console` as the first program arg) runs the
  console chat; no arg (the default) runs the dataset run, as before.
