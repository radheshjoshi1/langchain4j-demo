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
