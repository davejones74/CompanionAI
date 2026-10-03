# Qwen Model Evaluation and Bake-Off

## Purpose

Select the Qwen model that CompanionAI will use in production on the Minisforum X1 Pro, and select the production context budget that goes with it.

**This document is a test plan plus the completed development-host API probes (§6). It contains no
X1 Pro results and no benchmark numbers.** The X1 Pro measurements are added only after the Phase 0
hardware validation in `X1Pro-FastFlowLM-Validation.md` is satisfied.

Status markers match `LLM-Provider-Architecture.md`:

| Marker | Meaning |
|---|---|
| `[CURRENT]` | Pre-existing behaviour, read from this repository's source |
| `[IMPLEMENTED]` | In this repository, compiling, test-covered. Not confirmed on hardware |
| `[VENDOR]` | Stated by upstream vendor documentation. A claim to be tested. |
| `[VALIDATED]` | Measured on hardware. |
| `[TBD]` | Unknown. Determined by the harness described here. |

---

# 1. Why a bake-off is required

CompanionAI's LLM workload is **prefill-dominated**, not decode-dominated. `[CURRENT]` On every single turn the application re-sends:

- the system prompt,
- the recent conversation history,
- the RAG-retrieved knowledge-base context.

FastFlowLM's documented server mode is stateless between requests, so there is no KV-cache reuse across turns and the prefill cost is paid again every time. `[VENDOR]` This must be confirmed by measurement.

Two consequences:

1. `[PLANNED]` **Time to first token (TTFT) is the primary user-facing metric**, more important than decode tokens per second.
2. `[PLANNED]` A model with a high tok/s headline number can still be the wrong choice if it prefills slowly. `[TBD]` Which model wins on this specific workload is unknown until measured.

---

# 2. Candidate set

`[TBD]` Required candidates, in the order they will be attempted:

| # | Candidate | Shape | Why included |
|---:|---|---|---|
| 1 | **Qwen3 4B** | dense, small | Fastest prefill expected. Establishes the latency floor and the minimum viable quality bar. |
| 2 | **Qwen3.5 approximately 9B** | dense, mid | Middle point. Tests whether quality gains justify the prefill cost. |
| 3 | **`qwen3.6-moe:35b-a3b`** | MoE, large total / small active | Candidate the user specifically requested. Tests whether a sparse model can beat a dense one on a prefill-heavy workload despite higher total parameter count. |

`[OBSERVED]` **Exact model tags are not assumed.** Tags and version suffixes change between FastFlowLM releases. The catalogue was enumerated on 2026-10-03 against FastFlowLM `1.0.7` with `flm list`; `flm cache list` is unsupported in that release and exits `1`, so `flm list --filter installed` is the equivalent.

## 2.1 Tag resolution

| Candidate | Tag resolved from installed catalogue | Installed locally? |
|---|---|---|
| Qwen2.5 IT 3B | `qwen2.5-it:3b` | **`[VALIDATED]` installed** — the only model pulled |
| `qwen3.6-moe:35b-a3b` | present in the catalogue | `[OBSERVED]` remote (`⏬`), not pulled |
| Qwen3 4B | `[TBD]` | `[TBD]` |
| Qwen3.5 ~9B | `[TBD]` | `[TBD]` |

`[OBSERVED]` **Only `qwen2.5-it:3b` is installed.** Every other catalogue entry is available for download but has not been pulled, so nothing has been measured on a second model. `qwen2.5-it:3b` is small (3B, instruction-tuned), and its one sample in F2 ran at roughly 42 tok/s prefill and 25 tok/s decode — a single non-representative measurement, not a benchmark. No ranking, no tok/s comparison table and no production model choice can be made from this. `[TBD]`

`[TBD]` If a candidate is unavailable in the installed release, record that fact as a finding and evaluate the remaining candidates. Do not substitute a different model silently.

---

# 3. Vendor-reported figures

> **Do not use anything in this section for selection.**
>
> `[VENDOR]` Published FastFlowLM benchmark figures are produced on **Ryzen AI 300-series "Kraken Point"** silicon. The Minisforum X1 Pro HX-370 is a **different part**. AMD publishes no HX-370 rows. These figures are a rough proxy for *relative model ordering at best* and carry **no** predictive value for absolute HX-370 numbers.
>
> `[TBD]` Even the relative ordering is unverified and must be treated as a hypothesis to test, not a finding.

`[TBD]` **Left intentionally blank.** Re-verify the installed release's own published table during Phase 0, record it here with explicit attribution, and keep it fenced from the measured results in §7. No figures are transcribed at this time because none have been attributed from a verified source.

## 3.1 `[PLANNED]` The hypothesis these figures would test

`[TBD]` The working hypothesis, stated so the bake-off can falsify it:

> A sparse MoE model with a high decode tok/s figure will nonetheless **lose on TTFT** to a smaller dense model, because TTFT is dominated by prefill, and prefill cost scales with total parameter count rather than active parameter count.

If the MoE candidate wins on TTFT, the hypothesis is wrong and the selection criteria in §8 will say so. Either outcome is acceptable; an unexamined assumption is not.

---

# 4. Test harness

`[PLANNED]` Two Gradle tasks, run with an identical suite on **both** hosts so results are directly comparable.

| Task | Purpose |
|---|---|
| `llmBench` | Measures latency, throughput, memory and utilisation |
| `llmParity` | Measures functional and quality parity against the current Ollama baseline |

`[PLANNED]` Both tasks read the active provider through the same configuration as the application (`campanionai.llm.*`), so they exercise the real provider layer rather than a parallel code path.

`[PLANNED]` Both emit machine-readable JSON plus a markdown summary, and write results into this document's §7 tables.

```bash
./gradlew llmBench
./gradlew llmParity
```

`[PLANNED]` `--dry-run` prints the resolved plan — provider, model, base URL, context budget — without contacting the runtime. This makes a misconfigured harness obvious before any timing numbers are recorded.

## 4.1 `[PLANNED]` Repetition and warm-up

| Rule | Value |
|---|---|
| Warm-up requests before measurement | `[TBD]` — enough to load weights and reach steady state |
| Measured repetitions per case | `[TBD]` at least 5 |
| Reported statistic | median and p95, **not** mean |
| Warm-up excluded from results | Yes |
| NPU / GPU clock behaviour during the run | `[TBD]` record; sustained load may downclock |

---

# 5. Measurement dimensions

## 5.1 Latency and throughput

| Metric | Definition | Why it matters |
|---|---|---|
| **TTFT** | request sent → first content delta received | **Primary metric.** Dominates perceived responsiveness. |
| Prefill throughput | prompt tokens ÷ time to first token | Explains TTFT. Determines whether context size is affordable. |
| Decode throughput | content tokens ÷ time after first token | Determines typing smoothness. |
| Total latency | request sent → `[DONE]` | Batch/background jobs. |
| Time to `[DONE]` overhead | total latency − (TTFT + decode) | Detects server-side stalls or per-token overheads. |
| Inter-token gap distribution | — | Detects stutter that a mean tok/s figure hides. |

`[PLANNED]` TTFT must be measured separately for `chat` and `chatStream`, because the streaming path adds its own overhead.

## 5.2 Memory

| Metric | Method |
|---|---|
| Baseline RAM | Block 8 of the validation runbook, before any model loads |
| Loaded RAM | After the model is resident |
| Model weight footprint | loaded − baseline |
| Peak RSS delta during request | Sampled during the longest prompt |
| Swap activity | `[TBD]` verify swap is not silently absorbing pressure |
| OOM behaviour | Deliberately exceed the context budget and record the failure mode |

`[PLANNED]` OOM behaviour is a required test, not an optional one. A model that degrades gracefully is acceptable; one that takes the JVM down with it is not.

## 5.3 Utilisation

| Metric | Source |
|---|---|
| NPU utilisation | FastFlowLM startup banner and runtime output; `[TBD]` exact instrumentation |
| XRT device state | `xrt-smi examine` |
| CPU utilisation | `ps`, `top` sampling |
| Which device actually executed the model | `[TBD]` **must be confirmed** — a silent CPU fallback invalidates the result |

`[TBD]` **Disqualifier.** If a run reports plausible timings but actually executed on CPU, the result is void. The bake-off must detect this explicitly, not assume it.

## 5.4 Context budget

`[PLANNED]` Each candidate is exercised at three context budgets.

| Budget | `maxContextTokens` | `historyTokens` | Rationale |
|---|---|---|---|
| Small | 4000 | 1500 | Proposed production baseline. Fits the smallest candidate. |
| Medium | 8000 | 3000 | Tests headroom. |
| Large | 16000 | 6000 | Upper bound. Tests where prefill cost becomes prohibitive. |

`[PLANNED]` For each budget, vary prompt length in realistic steps rather than only at the ceiling. Suggested ladder:

```text
~500 tokens   — short chat, no RAG hits
~1500 tokens  — typical turn with one RAG chunk
~4000 tokens  — long history, several RAG chunks
~8000 tokens  — ceiling of the small budget
~16000 tokens — ceiling of the large budget
```

`[PLANNED]` Remaining budget variables are held at the production profile during measurement: `chunkTokens`, `chunkOverlap`, `live.contextTokens`. See `LLM-Provider-Architecture.md` §4.4 and the profile table in §9.

## 5.5 Quality

`[PLANNED]` Split into three tiers. A candidate must pass Tier 1 to be considered at all.

### Tier 1 — Functional (pass/fail, disqualifying)

| Test | Pass condition |
|---|---|
| Non-streaming reply | Non-empty text at the expected response path |
| Streaming reply | All deltas arrive and concatenate into the same text as the non-streaming reply |
| Stream termination | Clean terminator observed, no hang |
| System role honoured | The system instruction changes the reply |
| Temperature honoured | Distinct settings produce measurably different output |
| Intent classification | Same intent as the rule-based classifier for the full intent set (§5.5.1) |
| Mid-stream failure | Unparseable frame is skipped, not fatal |
| Runtime down | Clean error surfaces as the offline fallback, app stays up |

### Tier 1.1 `[CURRENT]` Intent-parity definition

`[CURRENT]` CompanionAI has two intent classifiers behind one interface, `retrieval/IntentClassifier`: `RuleIntentClassifier` (deterministic) and `LlmIntentClassifier` (model-driven). `[CURRENT]` `LlmIntentClassifier.parse()` extracts JSON using the regex `\{.*?}` (`LlmIntentClassifier.java:21,58-59`).

`[PLANNED]` Parity test: for **every** intent the rule-based classifier recognises, the LLM classifier must return the same intent. The intent list is enumerated from `RuleIntentClassifier` source at test-authoring time rather than hardcoded here, so it cannot drift.

`[TBD]` Current parity status on Ollama is the baseline. It is not yet measured.

### Tier 2 — RAG quality

| Test | Pass condition |
|---|---|
| Answer present in context | The expected fact appears in the reply |
| Answer absent from context | The model says it does not know rather than inventing one |
| Multiple chunks | Correct fact selected when several similar chunks are retrieved |
| Long context | Correct fact still found at the 16000-token budget |

`[CURRENT]` Retrieval itself is keyword scoring (`ModelServlet.scoreChunk()`), not embedding-based, so RAG quality depends only on the LLM's ability to use supplied context, not on retrieval quality. That isolates this test cleanly.

### Tier 3 — Subjective quality

`[PLANNED]` Scored 1–5 against the Ollama baseline, blind, by a fixed prompt set covering general chat, multi-turn context retention, instruction following and prose quality.

| Dimension | Qwen3 4B | Qwen3.5 ~9B | MoE |
|---|---|---|---|
| Instruction following | `[TBD]` | `[TBD]` | `[TBD]` |
| Multi-turn retention | `[TBD]` | `[TBD]` | `[TBD]` |
| Grounding in supplied context | `[TBD]` | `[TBD]` | `[TBD]` |
| Conciseness | `[TBD]` | `[TBD]` | `[TBD]` |
| Overall for CompanionAI's use | `[TBD]` | `[TBD]` | `[TBD]` |

---

# 6. `[PLANNED]` Execution order

Sequence matters. Do not start the bake-off until the Phase 0 gate passes.

| Step | Action |
|---:|---|
| 1 | Complete `X1Pro-FastFlowLM-Validation.md` Blocks 1–8 |
| 2 | Record RAM baseline (Block 8) **before** any model loads |
| 3 | Run `llmBench` and `llmParity` on the **Intel + Ollama** host to establish the baseline |
| 4 | On the X1 Pro, resolve candidate tags (§2.1) |
| 5 | Benchmark Qwen3 4B across all three context budgets |
| 6 | Benchmark Qwen3.5 ~9B across all three context budgets |
| 7 | Benchmark the MoE candidate across all three context budgets |
| 8 | Record which device executed each run (§5.3) |
| 9 | Run Tier 3 subjective scoring against the Ollama baseline |
| 10 | Apply §8 decision criteria and record the selection |
| 11 | Set production context budget from §7.3 |

`[PLANNED]` Benchmark the smallest candidate first. If it already meets every acceptance criterion, the remaining two may be reduced to Tier 1 and Tier 2 at the smallest budget that matters.

---

# 7. Result recording

## 7.1 Latency and throughput

`[TBD]` No measurements yet. One row per candidate per context budget.

| Model | Budget | Prompt tokens | TTFT median | TTFT p95 | Prefill tok/s | Decode tok/s | Total latency |
|---|---|---|---|---|---|---|---|
| Qwen3 4B | 4000 | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` |
| Qwen3 4B | 8000 | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` |
| Qwen3 4B | 16000 | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` |
| Qwen3.5 ~9B | 4000 | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` |
| Qwen3.5 ~9B | 8000 | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` |
| Qwen3.5 ~9B | 16000 | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` |
| MoE | 4000 | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` |
| MoE | 8000 | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` |
| MoE | 16000 | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` |

## 7.2 Memory and utilisation

| Model | Baseline RAM | Loaded RAM | Weight footprint | Peak RSS delta | Swap used | Executed on |
|---|---|---|---|---|---|---|
| Qwen3 4B | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` |
| Qwen3.5 ~9B | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` |
| MoE | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` |

`[TBD]` "Executed on" must be `NPU` for a valid result. See the §5.3 disqualifier.

## 7.3 Selected context budget

`[TBD]` Filled after §7.1 and §7.2.

| Budget | TTFT acceptable | Memory acceptable | Chosen |
|---|---|---|---|
| 4000 / 1500 | `[TBD]` | `[TBD]` | `[TBD]` |
| 8000 / 3000 | `[TBD]` | `[TBD]` | `[TBD]` |
| 16000 / 6000 | `[TBD]` | `[TBD]` | `[TBD]` |

## 7.4 Selected model

`[TBD]`

| Item | Value |
|---|---|
| Model | `[TBD]` |
| Tag as installed | `[TBD]` |
| Context budget | `[TBD]` |
| Measured TTFT at that budget | `[TBD]` |
| Measured memory footprint | `[TBD]` |
| Tier 1 result | `[TBD]` |
| Rationale | `[TBD]` |

---

# 8. Decision criteria

`[PLANNED]` Applied **in this order**. An earlier criterion that fails eliminates the candidate outright; criteria are not traded off against each other.

| # | Criterion | Rule |
|---:|---|---|
| 1 | **Runs on the NPU** | Hard requirement. A CPU-only run is void, not slow. |
| 2 | **Passes Tier 1 functional tests** | Hard requirement |
| 3 | **Fits in RAM with headroom** | Must leave the OS, Nginx and the CompanionAI JVM room to operate without swapping. `[TBD]` exact headroom threshold to be set from the Block 8 baseline |
| 4 | **TTFT acceptable at the production budget** | `[TBD]` threshold to be agreed once baseline figures exist. A conversational UI degrades sharply as TTFT rises |
| 5 | **Functional quality parity** | No unacceptable regression against the Ollama baseline |
| 6 | **Grounding in supplied context** | Must not hallucinate when the answer is absent. This is a hard requirement, not a score |
| 7 | Decode throughput | Lowest priority. Matters only for typing smoothness once TTFT is acceptable |
| 8 | Subjective quality | Tie-breaker only |

`[PLANNED]` **A larger model is not selected merely because it is larger.** Criteria 1–6 can eliminate the MoE candidate on TTFT or memory alone. That would be a valid outcome.

---

# 9. `[PLANNED]` Production profiles

`[PLANNED]` Follows the existing idiom from `script/run-qwen2.5-1.5b.sh`, which already shrinks the whole context budget bundle for a small model. Final values are `[TBD]` pending §7.

| Profile | Provider | Model | `numCtx` | `maxContextTokens` | `historyTokens` | `chunkTokens` | `chunkOverlap` | `live.contextTokens` |
|---|---|---|---|---|---|---|---|---|
| ollama (default, unchanged) | ollama | `qwen3.6:27b` | *(unset)* | 20000 | 8000 | 1500 | 200 | 4000 |
| ollama + small model | ollama | *model* | 2048 | 1024 | 512 | 512 | 64 | 512 |
| **fastflowlm (production)** | fastflowlm | `[TBD]` | n/a | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` | `[TBD]` |

`[CURRENT]` Row 1 and row 2 are transcribed from existing scripts and must not drift. `[TBD]` Row 3 is entirely pending this bake-off.

`[PLANNED]` `numCtx` is `n/a` for FastFlowLM because `[VENDOR]` context length is fixed at `flm serve` time via `--ctx-len` and is not a per-request field. The serving command must therefore match the selected budget.

---

# 10. Acceptance criteria for the selection

- [ ] Every candidate's execution device recorded and confirmed as NPU
- [ ] Latency recorded at all three context budgets for every candidate
- [ ] Memory recorded against a pre-load baseline
- [ ] Tier 1 functional tests pass for the selected model
- [ ] Tier 2 grounding tests pass for the selected model
- [ ] Tier 3 scoring completed against the Ollama baseline
- [ ] Selection rationale written in §7.4
- [ ] Production profile written in §9
- [ ] `script/run.sh` profile matches the recorded selection
- [ ] Deployment document updated with the selected model tag and context budget

## Disqualifiers

Any of the following voids the result entirely:

- Model executed on CPU rather than the NPU
- Baseline RAM not recorded before the model loaded
- Fewer than the required repetitions, or warm-up included in the statistics
- Results collected before the Phase 0 hardware gate passed
---

# 6. `[VALIDATED]` Development-host API probes — Ollama

These are the **A1–A5** probes from `X1Pro-FastFlowLM-Validation.md` Block 7, run against the
development host. They are genuine measurements: recorded from command output, not inferred from
documentation. They are the first `[VALIDATED]` rows in this document, and they validate the
**development** runtime only.

## 6.1 Environment

| Item | Value |
|---|---|
| Date | 2026-10-03 |
| Ollama version | `ollama version is 0.35.0` |
| Endpoint | `http://127.0.0.1:11434/v1` |
| Listen address | `127.0.0.1` — **loopback only**, not `0.0.0.0` |
| Models present | `qwen2.5:1.5b`, `qwen2.5:14b`, `qwen2.5-coder:14b`, `qwen3.6:27b`, `gemma4:12b`, `deepseek-r1:14b`, `deepseek-r1:32b`, `deepseek-r1:latest`, `llama3.1:8b` |

## 6.2 Probe results

| Probe | Tests | Result | Verdict |
|---|---|---|---|
| A4 | R10 | `GET /v1/models` → HTTP 200, 9 models listed | `[VALIDATED]` |
| A1 | R1, R2, R3, R4, R9 | HTTP 200; reply `Ok.` at `choices[0].message.content`; `finish_reason: "stop"`; `system` role accepted; top-level `temperature` accepted | `[VALIDATED]` |
| A2 | R5, R6 | **SSE**: 11/11 frames prefixed `data: `, delta at `choices[0].delta.content`, terminated by `data: [DONE]` | `[VALIDATED]` |
| A3 | R7, R8 | **HTTP 404** with parseable body `{"error":{"message":"model '__definitely_not_loaded__' not found",...}}`; server did not hang | `[VALIDATED]` |
| A5 | Ollama-only | `options.num_ctx: 4096` → HTTP 200, accepted | `[VALIDATED]` |

## 6.3 End-to-end check through the real provider

Beyond raw `curl`, `OllamaProvider` was driven directly against the live endpoint:

| Call | Output |
|---|---|
| `chat(...)` with `system` + `user` | `Ok.` |
| `chatStream(...)` | `1, 2, 3, 4, 5` — all 5 deltas reassembled in order |
| `chat(...)` with unknown model | `LlmException: Ollama returned HTTP 404: {"error":{"message":"model '__definitely_not_loaded__' not found",...}}` |

## 6.4 What these results changed

1. **R5/R6 are settled for Ollama, and the answer is SSE — not NDJSON.** The tolerant parser is
   therefore *not* masking a mismatch on the development path: Ollama's `/v1` surface is genuinely
   OpenAI-compatible. The NDJSON tolerance remains as defence for FastFlowLM, whose framing is still
   `[TBD]`.
2. **Ollama's `/v1` error convention is stricter than the old native API.** The native endpoint
   could return `{"error": "..."}` with HTTP **200**; the `/v1` surface returns a proper non-2xx
   status. `OpenAiCompatTransport` still checks both, so it is correct either way, but the
   status-code path is the one that actually fires here.
3. **Ollama binds loopback only.** Recorded because it is the useful contrast case for the
   FastFlowLM bind-address question, which is `[TBD]`. A loopback-only Ollama requires no firewall
   rule; a `0.0.0.0` FastFlowLM would require one.

## 6.5 Not validated here

- Anything about the X1 Pro, FastFlowLM, or the NPU.
- Throughput, latency or memory. The probes above are **correctness** checks on request and
  response shape. `llmBench` does not exist yet and no timing figure has been measured.
- Whether `options.num_ctx` was *honoured* as a context length, as opposed to merely accepted —
  confirming that needs a long-context request, not a `hi`.
