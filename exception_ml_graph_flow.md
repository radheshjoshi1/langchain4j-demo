# Exception ML Graph — Embedded Local Model in a LangGraph Flow

> Companion to `langchain4j_demo_project_guide.md`. Documents the `org.example.exceptions` /
> `org.example.ml` packages: a `langgraph4j` workflow that classifies a payment exception using a
> ML model embedded in-process (ONNX Runtime), modeled on the `CallMlModelNode` pattern from the
> `convaservice-exceptions` design doc, adapted to this demo's banking-support domain.

---

## 1. What counts as an "exception" here

This demo has no dedicated exceptions service, but `PaymentService` already models the right
shape: transactions that are `SUCCESS`, `PENDING`, or `FAILED`. The graph treats any transaction
looked up by ID as a candidate exception and decides whether it can be closed automatically or
needs a human to look at it — the same "auto-resolvable vs. needs deeper review" decision the
original design makes, just against this app's data.

## 2. Graph flow

```mermaid
flowchart TD
    START(["__START__\ninput: transactionId, customerId"]) --> FC[FetchExceptionContextNode]
    FC -->|"ExceptionContext\n(paymentStatus, reasonDetail, accountTier)"| CML[CallMlModelNode]
    CML -->|"LocalMlModelClient.predict()\nin-process, no network call"| ROUTE{ExceptionGraphRouting\ndecideRoute}
    ROUTE -->|"bypassEligible == true\n& no workflow errors"| AR[AutoResolveNode]
    ROUTE -->|"bypassEligible == false,\nprediction missing,\nor inference threw"| MR[ManualReviewNode]
    AR --> END(["__END__"])
    MR --> END
```

Node-by-node, mirroring the source doc's roles:

| Node | Class | Role |
|---|---|---|
| `fetchContext` | `FetchExceptionContextNode` | Calls `PaymentService.checkPaymentStatus` + `AccountService.getAccountTier`, builds an `ExceptionContext` |
| `callMlModel` | `CallMlModelNode` | The embedding point — asks `LocalMlModelClient.predict(context)` for a score. On any `RuntimeException`, catches it and records a workflow error instead of throwing (fail safe) |
| *(conditional edge)* | `ExceptionGraphRouting.decideRoute` | A workflow error always wins → `MANUAL_REVIEW`. Otherwise routes on `prediction.bypassEligible()`, defaulting to `MANUAL_REVIEW` if no prediction is present |
| `autoResolve` | `AutoResolveNode` | Terminal node: writes a resolution string when the model says no review is needed |
| `manualReview` | `ManualReviewNode` | Terminal node: writes a resolution string explaining why review is needed (error, low score, or no prediction) |

State is `ExceptionAgentState` (`org.bsc.langgraph4j.state.AgentState` subclass) with keys
`transactionId`, `customerId`, `exceptionContext`, `mlResult`, `workflowErrors`, `resolution`.

## 3. How this fits alongside the app's other run modes

`Main.main(args)` (`Main.java:31-87`) is the single dispatcher for every flow in this app. It
branches purely on `args[0]`/`args[1]` — nothing else decides which flow runs:

| Invocation | Branch in `Main.main` | What actually runs |
|---|---|---|
| `mvn exec:java -Dexec.args="exceptions"` | `Main.java:36-39` | **This flow.** Returns immediately, before OTel/Langfuse even initialize — `runExceptionDemo()` (`Main.java:95-112`) |
| `mvn exec:java -Dexec.args="console"` | `consoleMode=true`, `backend=""` → `Main.java:67-68` | `AgentFactory.createAgent(sessionId)` (hosted OpenAI-compatible model) → `runConsoleChat` (interactive REPL) |
| `mvn exec:java -Dexec.args="console local"` | `backend="local"` → `Main.java:63-64` | `AgentFactory.createLocalAgent()` (Ollama over HTTP) → `runConsoleChat` |
| `mvn exec:java -Dexec.args="console embedded"` | `backend="embedded"` → `Main.java:65-66` | `AgentFactory.createEmbeddedAgent()` (Jlama, embedded LLM) → `runConsoleChat` |
| `mvn exec:java` (no args) | falls through to `Main.java:81-86` | **Experiment run**: `DatasetItemRunner` pulls a Langfuse dataset and scores the agent's answers against it |
| `mvn test` | not `Main` at all | JUnit runs `StreamingSupportAgentTest` (hits the real hosted agent, scores pass/fail cases) plus this feature's own unit tests |

Every branch except `exceptions` eventually builds a `StreamingSupportAgent` (an LLM chat agent
with tools) and either drives it interactively or batch-scores it. The `exceptions` branch is
structurally separate — it never touches `StreamingSupportAgent`, `AgentFactory`, or Langfuse/OTel
at all.

**Why the flows don't interfere with each other:** the only files the exception flow shares with
the others are `PaymentService`/`AccountService` — and `FetchExceptionContextNode` gets its own
fresh instances of them (constructed in `ExceptionAgentFactory.java:20`), not the ones the chat
agent uses. No shared mutable state, no ordering dependency between flows.

## 4. Tracing one prediction end-to-end

Take `processor.process("TXN_002", "CUS_002")` — exactly what `runExceptionDemo` does for that
sample transaction:

```
Main.runExceptionDemo()                                    Main.java:106
  → ExceptionAgentProcessor.process("TXN_002","CUS_002")   ExceptionAgentProcessor.java:19
    → graph.invoke({transactionId:"TXN_002", customerId:"CUS_002"})   (langgraph4j CompiledGraph)

    [node: fetchContext]  FetchExceptionContextNode.apply()           FetchExceptionContextNode.java:23
      → paymentService.checkPaymentStatus("TXN_002")   → "FAILED - Invalid HMAC Signature."
      → accountService.getAccountTier("CUS_002")       → "STANDARD"
      → splits the payment result into status="FAILED", reasonDetail="Invalid HMAC Signature."
      → puts ExceptionContext(TXN_002, CUS_002, FAILED, "Invalid HMAC Signature.", STANDARD)
        into state["exceptionContext"]

    [node: callMlModel]  CallMlModelNode.apply()                      CallMlModelNode.java:22
      → THIS IS THE ML CALL SITE:
        mlModelClient.predict(context)                                CallMlModelNode.java:26
          → (real model) OnnxLocalMlModelClient.predict()             OnnxLocalMlModelClient.java:56
            1. context.features()                                     ExceptionContext.java:15
               → {statusCode: 2 (FAILED), accountTierCode: 0 (STANDARD), reasonLength: 24}
               ^ this is "the exception query" - the numeric vector the model actually sees
            2. toFeatureVector() orders it per EXCEPTION_ML_FEATURE_ORDER
               → float[]{2.0, 0.0, 24.0}
            3. ONNX Runtime session.run() - in-process, no network call
            4. reads last value of output tensor as the bypass-eligible probability → 0.079
            5. 0.079 < bypassThreshold(0.5) → MlPrediction(label="MANUAL_REVIEW",
               bypassEligible=false, score=0.079, raw={...})
      → puts that MlPrediction into state["mlResult"]

    [conditional edge]  ExceptionGraphRouting.decideRoute(state)       ExceptionGraphRouting.java:16
      → workflowErrors empty, mlResult.bypassEligible()==false → returns "MANUAL_REVIEW"

    [node: manualReview]  ManualReviewNode.apply()                    ManualReviewNode.java:14
      → builds "ROUTED TO MANUAL REVIEW TXN_002: score=0.079 below threshold"
      → puts it into state["resolution"]

  ← graph.invoke() returns final state
← processor.process() reads state["resolution"] and returns it
```

That's the whole path — one method call (`mlModelClient.predict(context)` at
`CallMlModelNode.java:26`) is the *only* place inference happens; everything before it is
assembling the query (`ExceptionContext`), everything after it is routing on the answer.

**If the model call fails:** `CallMlModelNode.java:24-30` wraps `predict()` in a try/catch. Any
`RuntimeException` (bad file, shape mismatch, ONNX Runtime error) gets caught, logged, and turned
into `state["workflowErrors"]` instead of propagating. `ExceptionGraphRouting.decideRoute` checks
`workflowErrors` *first* (`ExceptionGraphRouting.java:18-20`) — so a failed prediction always
routes to `manualReview`, never silently auto-resolves and never crashes the graph.

**Which model backs `mlModelClient`** is decided once, at graph-build time
(`ExceptionAgentFactory.java:19`, via `MlModelConfig.fromEnvironment()`) — not per call. See the
config table in the next section.

## 5. The embedded model itself

```
Offline, once per model version                    Inside this JVM (langchain4j-demo)
─────────────────────────────                       ────────────────────────────────
Train/hand-craft a model                             ExceptionAgentFactory.createProcessor()
        │                                              → MlModelConfig.fromEnvironment()
        ▼                                              → LocalMlModelClientFactory.create(config)
export/save to ONNX                                       EXCEPTION_ML_ENABLED=false (default)
        │                                                    → PlaceholderLocalMlModelClient
        ▼                                                      (always MANUAL_REVIEW, zero risk)
model file on the classpath                                EXCEPTION_ML_ENABLED=true
(src/main/resources/models/...)  ───────────────────────►      → OnnxLocalMlModelClient
                                                                   (loads the .onnx file once,
                                                                    ONNX Runtime, in-process,
                                                                    no network call per predict())
                                                                 load failure at any point
                                                                   → falls back to placeholder,
                                                                     never blocks startup
```

- `LocalMlModelClient` (`org.example.ml`) — the interface `CallMlModelNode` depends on:
  `predict(ExceptionContext) -> MlPrediction(label, bypassEligible, score, raw)`.
- `OnnxLocalMlModelClient` — real implementation. Reads a `float32[1,N]` tensor built from
  `ExceptionContext.features()` in the order given by `MlModelConfig.featureOrder()`, runs it
  through ONNX Runtime's Java bindings, and reads the last value of the output tensor as the
  bypass-eligible probability, compared against `bypassThreshold`.
- `PlaceholderLocalMlModelClient` — the safe default: always `MANUAL_REVIEW`, `bypassEligible =
  false`. Used until a real model is enabled, or if the real one fails to load.
- `MlModelConfig` — environment-driven toggle, no Spring in this app:

  | Env var | Default | Meaning |
  |---|---|---|
  | `EXCEPTION_ML_ENABLED` | `false` | turns on the real ONNX client |
  | `EXCEPTION_ML_MODEL_PATH` | `models/exception-bypass-classifier.onnx` | classpath (default) or `file:`-prefixed path |
  | `EXCEPTION_ML_FEATURE_ORDER` | `statusCode,accountTierCode,reasonLength` | must match the model's training-time column order |
  | `EXCEPTION_ML_BYPASS_THRESHOLD` | `0.5` | score cutoff for auto-resolve vs. manual review |
  | `EXCEPTION_ML_MODEL_VERSION` | `unversioned` | free-text tag surfaced in `MlPrediction.raw()` |

`ExceptionContext.features()` maps the fetched context to numbers the model can consume:
`statusCode` (`SUCCESS=0, PENDING=1, FAILED=2, other=-1`), `accountTierCode` (`STANDARD=0,
VIP=1, other=-1`), `reasonLength` (length of the status detail text).

## 6. The fixture model used to verify this end-to-end

No trained model exists for this demo (that step is explicitly Data Science's, per the source
doc). To prove the ONNX Runtime wiring actually works — not just the placeholder fallback — a
tiny **hand-crafted, not trained** logistic model was built directly with `onnx.helper`
(`Gemm` + `Sigmoid` over the 3 features above) and checked in at
`src/main/resources/models/exception-bypass-classifier.onnx` (also mirrored under
`src/test/resources/models/` for `OnnxLocalMlModelClientTest`). Weights: `FAILED` status and a
longer reason string push the score down; `VIP` tier nudges it up.

Verified live with `EXCEPTION_ML_ENABLED=true EXCEPTION_ML_MODEL_VERSION=fixture-v1 mvn exec:java
-Dexec.args="exceptions"`:

```
[Exception]: AUTO-RESOLVED TXN_001: score=0.698 (model=fixture-v1) - no manual review needed.
[Exception]: ROUTED TO MANUAL REVIEW TXN_002: score=0.079 below threshold
[Exception]: ROUTED TO MANUAL REVIEW TXN_003: score=0.354 below threshold
[Exception]: AUTO-RESOLVED TXN_999: score=0.924 (model=fixture-v1) - no manual review needed.
```

- `TXN_001` (`SUCCESS`, VIP) and `TXN_002` (`FAILED`, invalid-signature) land on opposite sides
  of the threshold, as designed.
- `TXN_999` (an unknown transaction ID — `PaymentService` returns `TRANSACTION_NOT_FOUND`, no
  " - " separator) scores high and auto-resolves. That's an artifact of the toy weights treating
  an unrecognized status the same as a "clean" one, not a real-model judgment call — a reminder
  that `feature-order`/weight correctness (Step 4 of the source doc) matters, and toy fixtures
  aren't a substitute for the parity check a real trained model needs.
- Also verified the fail-safe path: `EXCEPTION_ML_ENABLED=true` with no model file on the
  classpath logs a load failure and falls back to the placeholder rather than crashing.

## 7. Running it

```bash
# placeholder model (default) - always routes to manual review
mvn exec:java -Dexec.args="exceptions"

# real fixture ONNX model
EXCEPTION_ML_ENABLED=true mvn exec:java -Dexec.args="exceptions"
```

(`sdk env` first if `JAVA_HOME` isn't already pointed at the JDK 21+ toolchain — see
`langchain4j_demo_project_guide.md` / `CHANGES.md` for that quirk.)

## 8. Tests

| Test | Proves |
|---|---|
| `PlaceholderLocalMlModelClientTest` | placeholder always returns `MANUAL_REVIEW` |
| `OnnxLocalMlModelClientTest` | real ONNX Runtime inference against the fixture model - feature vector construction, score extraction, threshold routing |
| `CallMlModelNodeTest` | inference failure is caught and recorded as a workflow error, not thrown; success path stores the prediction |
| `ExceptionGraphRoutingTest` | routing decision table: error → manual review (even with a bypass-eligible prediction), bypass-eligible → auto-resolve, missing prediction → manual review |
| `ExceptionAgentGraphFactoryTest` | the compiled graph runs end-to-end with the placeholder client |

## 9. What's intentionally out of scope

Same boundary as the source design: no real trained model, no parity-check tooling, no
artifact-store pull at startup. `EXCEPTION_ML_ENABLED` defaults to `false` so nothing changes
until a real model is deliberately wired in.
