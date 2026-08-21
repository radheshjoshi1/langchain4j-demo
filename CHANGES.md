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

---

## Date: 2026-08-20

### Local model POC, part 1: call a model running locally (Ollama), as a separate process

Requirement: stand up an ML model running locally and call it from the agent, as a POC,
alongside the existing hosted-endpoint agent (not replacing it).

Chose Ollama since it was already installed on the host with its daemon running
(`localhost:11434`). Pulled `llama3.2:1b` first; it consistently mis-extracted tool-call
arguments (called `checkPaymentStatus` with a null transaction id), so switched to
`llama3.2:3b`, which extracted arguments correctly.

- **`pom.xml` (modified)**: added `dev.langchain4j:langchain4j-ollama:${langchain4j.version}`.
- **`AgentFactory.java` (modified)**: added `createLocalAgent()` — builds an
  `OllamaStreamingChatModel` pointed at `http://localhost:11434` with model name
  `llama3.2:3b`, assembled with the same tools (`PaymentService`, `AccountService`) and
  `MessageWindowChatMemory` as `createAgent()`. No `LangfuseOtelListener` attached: that
  listener hardcodes `gen_ai.system = "openai"` on every span, so wiring a non-OpenAI model
  into it would mislabel traces — left out of scope for a POC path.
- **`Main.java` (modified)**: `console local` now builds the agent via `createLocalAgent()`
  instead of the hosted endpoint.

Verified end-to-end: `console local` correctly drove the full agent loop (streaming, tool
call, tool result) against the local Ollama model — see "How to run and test" below.

### Requirement change: embed the model in the same process, not call it as an endpoint

Requirement changed: the model must run *inside* the agent's own JVM process, not as a
server the agent calls over HTTP — Ollama (part 1 above) is still a separate process on
localhost, so it doesn't satisfy this.

Evaluated two in-process options and asked the user to choose:
- **Jlama** — pure-Java LLM inference engine, official `langchain4j-jlama` integration.
  Requires Java 20+ (uses the `jdk.incubator.vector` module for tensor math). Chosen.
- **java-llama.cpp** (`de.kherud:llama`) — JNI bindings to llama.cpp, bundled native
  `.so`/`.dylib` loaded in-process. Would have kept the project on Java 17, but has no
  official langchain4j module, so it would've meant hand-writing a custom
  `ChatModel`/`StreamingChatModel` adapter. Not chosen.

- **`pom.xml` (modified)**:
  - `maven.compiler.source`/`target` raised from `17` to `21` — required by
    `langchain4j-jlama`. (Built with the JDK 25 already on the host, targeting release 21;
    no separate JDK install needed since `--release`-style compilation across JDKs is
    standard.)
  - Added `dev.langchain4j:langchain4j-jlama:1.16.1-beta26` — the `langchain4j-jlama`
    version aligned to core `langchain4j` 1.16.1 (the module trails core with its own
    `-betaN` suffix; `1.16.1-beta26` is that pairing).
  - `maven-surefire-plugin` now sets `<argLine>--add-modules jdk.incubator.vector</argLine>`,
    so `mvn test` keeps working once Jlama-backed code is on the test classpath — Jlama's
    tensor operations use the Vector API, an incubator module the JVM won't resolve on the
    classpath by default without this flag.
- **`AgentFactory.java` (modified)**: added `createEmbeddedAgent()` — builds a
  `JlamaStreamingChatModel` with `modelName("tjake/Llama-3.2-3B-Instruct-JQ4")` (a
  Jlama-pre-quantized version of the same Llama-3.2-3B family used for the Ollama POC, so
  the two are a fair comparison) and `modelCachePath(~/.jlama/models)`. Same tools/memory
  as the other two agents; no `LangfuseOtelListener`, same reasoning as `createLocalAgent()`.
- **`Main.java` (modified)**: `console local`/`console embedded` are now selected via a
  `backend` string read from `args[1]` (`"local"` / `"embedded"`), replacing the earlier
  single `useLocalModel` boolean now that there are three backends instead of two.

### POC findings (embedded vs. separate-process local model)

- Confirmed working end-to-end: on first run, Jlama downloaded the model (1.9GB) from
  Hugging Face into `~/.jlama/models` and ran inference in-process; on later runs it loads
  straight from that local cache, no network call. The agent's tool-calling loop
  (`checkPaymentStatus`) worked correctly against it.
- **CPU inference is slow without a GPU**: a full turn (tool call + synthesized response)
  took over a minute on a 12-core host with no GPU — expect this, don't take it as a bug.
- **Same prompt-following limitation on both backends**: after a successful tool call, both
  the embedded Jlama model and the separate-process Ollama model (same `Llama-3.2-3B`
  family) tend to recite the system prompt's fixed out-of-scope refusal sentence instead of
  relaying the tool result. Reproduced this directly against Ollama's HTTP API with no
  langchain4j involved, confirming it's a small-model/prompt-following limitation, not a
  bug in either integration.
- Jlama occasionally streamed a raw JSON tool-call fragment
  (`{"name": "checkPaymentStatus", "parameters": {...}}`) as a visible text token just
  before firing the actual structured tool call — a rough edge worth knowing about if this
  path gets used beyond a POC.

### How to run and test

All three backends share the same `StreamingSupportAgent` (tools, memory, system prompt) —
only the model differs.

**Prerequisite for every mode**: `JAVA_HOME` must point at a Java 21+ JDK now (the project
was on 17 before this entry). On this host: `export JAVA_HOME=~/.sdkman/candidates/java/25.0.2-open`
(or any installed 21+ JDK) and put `$JAVA_HOME/bin` on `PATH`.

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25.0.2-open   # any JDK 21+
export PATH="$JAVA_HOME/bin:$PATH"
mvn -q compile
```

**1. Hosted endpoint (default, no local model)**
```bash
mvn exec:java -Dexec.args="console"
```

**2. Local model via Ollama (separate process)**

One-time setup:
```bash
ollama serve &                # if not already running as a daemon
ollama pull llama3.2:3b
```
Run:
```bash
mvn exec:java -Dexec.args="console local"
```

**3. Embedded model via Jlama (same process, no server)**

No separate daemon to start — the model downloads into `~/.jlama/models` on first use
(~1.9GB) and loads from that cache on every run after. Needs
`--add-modules jdk.incubator.vector` on the JVM, which `mvn exec:java` won't forward, so run
it directly with `java` instead:
```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
java --add-modules jdk.incubator.vector \
     -cp "target/classes:$(cat /tmp/cp.txt)" \
     org.example.Main console embedded
```
(Equivalently, `export JDK_JAVA_OPTIONS="--add-modules jdk.incubator.vector"` once per shell,
then any `java ... org.example.Main console embedded` picks it up automatically.)

**Testing either local mode**: at the `You:` prompt, try `Check the status of transaction
TXN_001` (or `TXN_002` / `TXN_003` — see `PaymentService.java` for the seeded data) and
confirm the console prints `[SYSTEM NOTICE]: LLM is executing 'checkPaymentStatus' tool for
ID: TXN_00x` with the correct id before the final answer — that line is the signal the tool
call round-tripped correctly regardless of what the model says afterward. Type `exit` to
quit.

**Sanity-checking a backend directly (bypassing the agent)**, useful for isolating whether
an odd response is a langchain4j-wiring issue or a raw model behavior:
```bash
curl -s http://localhost:11434/api/chat -d '{
  "model": "llama3.2:3b", "stream": false,
  "messages": [{"role":"user","content":"Say hello in 5 words."}]
}'
```

### New: `LocalModelTool.java` — the embedded model as a tool the hosted agent can call

Follow-up requirement: expose the embedded Jlama model as something the *hosted* agent can
delegate to mid-conversation, not just something you run standalone via `console embedded`.

- **`LocalModelTool.java` (new, `org.example.service`)**: a `@Tool`-annotated
  `askLocalModel(String query)` that lazily builds a blocking `JlamaChatModel` (same
  `EMBEDDED_MODEL_NAME` / `JLAMA_MODEL_CACHE_PATH` as `AgentFactory.createEmbeddedAgent()`,
  now package-private so both share them instead of duplicating the constants) on first call
  and reuses it after. Lazy on purpose: an agent that's built with this tool but never
  invokes it shouldn't pay the model-load cost.
- **`AgentFactory.createAgent()` (modified)**: added `new LocalModelTool()` alongside
  `PaymentService`/`AccountService` in the hosted agent's `.tools(...)` — only the hosted
  agent gets it; `createLocalAgent()`/`createEmbeddedAgent()` are already local-model demos
  in their own right.
- Verified by direct method call (bypassing the LLM's tool-choice) that the tool itself
  works, then verified through the real agent: a prompt naming the tool explicitly
  (`"call the askLocalModel tool..."`) tripped the existing banking-scope refusal — the
  system prompt reads tool-naming language as out-of-scope — while a natural task-shaped
  prompt (*"check the status of TXN_002 and have it drafted into a polite customer message
  using your offline drafting capability"*) reliably chained both tools:
  `checkPaymentStatus` → `askLocalModel`. Added the same `[SYSTEM NOTICE]` console print
  `PaymentService`/`AccountService` already use, since without it there was no way to tell
  "the hosted model drafted this itself" from "it actually delegated."

### New: `LocalModelUnavailableException.java` + try/catch around the local-model tool call

Requirement: the local-model tool call can fail (first-run Hugging Face download errors,
model-load failures, Jlama inference errors) and, unhandled, an exception thrown out of a
`@Tool` method propagates up through the whole `agent.chat(...)` call and fails the entire
turn on `Main`'s `onError` handler — losing the response completely, not just the one tool's
contribution. Wanted a typed exception for this failure mode and one place to resolve it,
same pattern as `HttpStatusException` (already used to branch on Langfuse 429s in
`DatasetItemRunner`).

- **`LocalModelUnavailableException.java` (new, `org.example.util`)**: `RuntimeException`
  subclass carrying the underlying cause, so callers can catch this specific type instead of
  an opaque `RuntimeException` from Jlama's internals.
- **`LocalModelTool.java` (modified)**:
  - `localChatModel()` now wraps `JlamaChatModel.builder()...build()` failures (model
    download/load) in `LocalModelUnavailableException`.
  - `askLocalModel()` now wraps the whole call in try/catch: any `RuntimeException` — the
    typed one from a load failure, or an inference-time failure from `ChatModel.chat()`
    (Jlama maps its own IOExceptions into `dev.langchain4j.exception.*` types, e.g.
    `AuthenticationException` for a 401 — see `JlamaExceptionMapper` — which are themselves
    `RuntimeException`s) — is normalized to `LocalModelUnavailableException` and passed to a
    single `resolve(...)` method: logs the real cause to stderr, then returns a plain-text
    fallback string so the hosted agent still gets a usable tool result and the turn
    completes, instead of the whole response erroring out. `resolve(...)` is the place to
    add retry/circuit-breaker/alerting logic later.
- Verified the failure path directly: pointed a standalone `JlamaChatModel.builder()` at a
  nonexistent Hugging Face repo id and ran it through the same catch logic —
  `dev.langchain4j.exception.AuthenticationException` (HTTP 401) came back wrapped as
  `LocalModelUnavailableException`, with `resolve(...)`'s fallback string correctly
  surfacing the real cause message. Re-ran the working `console` scenario above afterward to
  confirm the try/catch didn't change the happy path.

### `pom.xml` (modified): `mvn exec:java -Dexec.args="..."` was failing outright

`mvn exec:java -Dexec.args="console local"` failed with `The parameters 'mainClass' ... are
missing or invalid`. Root cause: `exec-maven-plugin` was never actually configured in this
project — every `mvn exec:java` usage documented so far (including in the project guide)
relied on also passing `-Dexec.mainClass=org.example.Main` by hand every time, and this
invocation didn't.

- Added an `exec-maven-plugin:3.6.3` block with `<mainClass>org.example.Main</mainClass>`,
  so `mvn exec:java -Dexec.args="..."` now works without also passing `-Dexec.mainClass`.
- Note: `exec:java` run standalone (not via a full `mvn compile exec:java` chain) does not
  compile for you - it runs whatever is already sitting in `target/classes`. Run
  `mvn compile` (or `mvn compile exec:java -Dexec.args="..."`) first if in doubt.
- Known cosmetic quirk, not fixed: after a successful run, `exec:java` prints a
  `NoClassDefFoundError` warning from `Main`'s shutdown hook (`OpenTelemetry...forceFlush()`
  fails to load `CompletableResultCode`). This is `exec-maven-plugin`'s `java` goal running
  the app through its own in-process `URLClassLoader`, which gets torn down before the JVM's
  own shutdown-hook thread runs - a known interaction between that goal and
  `Runtime.getRuntime().addShutdownHook(...)`, pre-existing in `Main.java` and unrelated to
  this session's changes. It's harmless (Maven still reports `BUILD SUCCESS`, the chat
  session itself completes normally) but means `[System]: Shutdown complete.` never prints
  and OTel spans from that run may not get flushed. Doesn't happen when running via
  `java -cp ...` directly (see "How to run and test" above) - use that if a clean shutdown
  log matters.

### `mvn exec:java` failing with `invalid target release: 21` in a fresh shell

The `exec-maven-plugin` fix above was verified in a shell where `JAVA_HOME` had been
exported to the JDK 25 install by hand first. In an ordinary fresh shell, `mvn` picks up
this machine's default JDK (17.0.19, via the `sdkman` "current" symlink /
`/usr/lib/jvm/java-17-openjdk-amd64`), and `maven-compiler-plugin` fails outright:
`error: invalid target release: 21` - javac 17 has no concept of a "21" target.

- **`~/.m2/toolchains.xml` (new, machine-local, not part of the repo)**: registers the
  already-installed JDK 25 (`~/.sdkman/candidates/java/25.0.2-open`) as a toolchain
  satisfying `<jdk><version>21</version></jdk>`, so plugins that consult the active
  toolchain use it regardless of which JDK launched `mvn`. (No existing `toolchains.xml` was
  present on this machine, so nothing was overwritten.)
- **`pom.xml` (modified)**: added `maven-toolchains-plugin:3.1.0`, bound to its default goal
  `toolchain` requesting `<jdk><version>21</version></jdk>` - selects that toolchain before
  compilation runs. Also pinned `maven-compiler-plugin` to `3.13.0` (previously unpinned,
  which was silently resolving to the 2013-era default `3.1` bundled with this Maven
  install - toolchains have been supported since compiler-plugin 2.0.9, so this wasn't the
  actual problem, but pinning it removes one more unpredictable variable given everything
  else here is already version-pinned).
- Verified in a genuinely clean shell (`env -i`, `JAVA_HOME` unset, default system JDK 17 on
  `PATH`): `mvn -q compile` now succeeds without any manual `JAVA_HOME` export.

**This does not fully fix `mvn exec:java` on its own.** The toolchain only redirects
compilation; `exec-maven-plugin`'s `java` goal runs the compiled app *inside Maven's own
JVM process* via reflection (not a forked subprocess), so once classes are compiled to
Java 21 class file version 65, running them still needs Maven's own JVM - i.e. whatever JDK
launched `mvn` - to be 21+. Confirmed this exact failure mode in the same clean shell:
`UnsupportedClassVersionError: ... class file version 65.0 ... this version of the Java
Runtime only recognizes class file versions up to 61.0` (Maven's own JVM was still 17).

- **`.sdkmanrc` (new)**: pins `java=25.0.2-open` for this project directory. Run `sdk env`
  once per shell before any `mvn` command (`exec:java` included) and it switches
  `JAVA_HOME`/`PATH` for that shell to the JDK this project needs - confirmed this fixes
  `mvn exec:java -Dexec.args="console local"` end-to-end in the same clean-shell
  reproduction. (Equivalent to exporting `JAVA_HOME` by hand, just discoverable from the
  project directory instead of needing to be remembered/re-documented per command. Requires
  `sdkman_auto_env` to stay off or be handled deliberately - not changed here, since flipping
  it on would auto-switch the JDK for every project on this machine, not just this one.)
