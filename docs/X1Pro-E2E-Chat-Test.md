# CompanionAI → FastFlowLM end-to-end chat validation

**Status:** `[VALIDATED]` on the X1 Pro, 2026-10-03. 26 of 26 assertions passed. Measurements below.

## Result

```text
=== Result
  passed: 26
  failed: 0

E2E PASSED - CompanionAI generates through FastFlowLM on the NPU,
and /api/chat/stream delivers incrementally.
```

| Measurement | Value |
|---|---|
| Assertions | 26 passed, 0 failed |
| `/api/chat` round trip | 5527 ms |
| `/api/chat` reply | 139 chars, `offline=false`, no fallback marker |
| `/api/chat/stream` deltas | 33 delta events, 34 SSE events total |
| `/api/chat/stream` spread | first delta → last delta **1330 ms** |
| `/api/chat/stream` worst inter-chunk gap | **410 ms** |
| `/api/chat/stream` wall clock | 3938 ms |
| Terminator | `{"done":true,"offline":false}` received |
| Final `/api/stats` | `total:2 streamed:1 jsonReplies:1 offline:0 outputTokens:84` |

The streaming numbers are the load-bearing evidence. A buffered response of the same payload
measures ~3 ms spread; this measured 1330 ms with a 410 ms worst gap, roughly 400× the buffered
baseline. The provider, the transport and the servlet are all confirmed streaming.

`think: false` was sent on both requests and was accepted — that closes the remaining F5
uncertainty recorded in `FastFlowLmProvider`'s JavaDoc.

## What this closes

Part A and Part B of [X1Pro-FastFlowLM-Validation.md](X1Pro-FastFlowLM-Validation.md) probed
FastFlowLM directly. `/api/stats` on CompanionAI proved only that the servlet context starts.
Neither touched the production path:

```text
/api/chat  ·  /api/chat/stream
    -> ModelServlet
    -> LlmProvider
    -> FastFlowLmProvider
    -> OpenAiCompatTransport
    -> FastFlowLM
    -> Qwen / XDNA2
```

Four things could still be wrong in that chain, and all four are invisible to the probes already run:

| Possible fault | Symptom this test would show |
|---|---|
| Provider never resolved to `fastflowlm` | launcher/configuration assertions fail, or `offline=true` |
| `think` field or envelope rejected | transport error, `offline=true`, or a FastFlowLM error body |
| Model tag not what the runtime has | the request **hangs** until timeout (FastFlowLM does not 404) |
| Response buffered instead of streamed | all SSE deltas land within a few ms of each other |

## How to run

On the X1 Pro, with FastFlowLM already serving:

```bash
cd ~/CompanionAI
flm serve &
bash script/e2e-fastflowlm.sh
```

Paste the whole output back. Exit status is `0` only if every assertion passed.

Invoke it through `bash` as shown: git records every file in `script/` as mode `100644`, so a fresh
clone has no executable bit. `chmod +x script/*.sh` also works if you prefer `./script/…`.

The script starts and stops CompanionAI itself, so nothing is left running. It refuses to start if
port 8080 is already occupied rather than interfering with an existing process.

## Why `offline` is the decisive field

`ModelServlet` sets `offline=true` **only** when the provider threw `LlmException` and it fell back
to the canned `ChatRules` reply, which is then tagged `[LLM unavailable - offline reply]`. So:

> non-empty reply **and** `offline=false` ⇒ FastFlowLM generated it. Nothing else can.

The test asserts that, plus the independent `/api/stats` counters (`jsonReplies`, `streamed`,
`outputTokens`, `lastOutputTokens`, and `offline` staying at zero).

## Why the streaming check is trustworthy

Each SSE line is timestamped at the moment `curl` reads it, so the test measures **arrival** times,
not content. The assertions are:

| Assertion | Threshold | Why |
|---|---|---|
| delta events | ≥ 5 | a three-sentence answer must produce many chunks |
| first delta → last delta | ≥ 300 ms | a buffered body arrives with ~0 ms spread |
| largest pause between consecutive deltas | ≥ 80 ms | ditto, worst case |

Verified against synthetic input: a genuinely incremental stream measures 545 ms spread / 190 ms
worst gap; the same payload delivered buffered measures 3 ms / 1 ms. Both thresholds fail the
buffered case and pass the streamed one.

Thresholds are overridable if the model turns out to be unusually fast:

```bash
MIN_SPREAD_MS=800 MIN_MAXGAP_MS=250 bash script/e2e-fastflowlm.sh
```

Both replies were correct, non-degenerate answers to the prompt, which rules out a canned `ChatRules`
match producing the text.

## What this does not prove

**The intent classifier is not covered.** `ModelServlet` defaults `liveLlmMode=true`, so
`LlmIntentClassifier` issues an extra non-streaming `llm.chat()` call per request whenever
`RuleIntentClassifier` finds no match. `LlmIntentClassifier` catches every exception and returns
`Intent.NONE`, so a classifier failure is invisible to every assertion in this test, and its tokens
are not counted in `/api/stats` (`addOutputTokens` is only called from the two chat handlers).

`liveAttempts: 0` does **not** mean the classifier was skipped — that counter records whether an
external retrieval provider returned items, not whether classification ran. Confirm with:

```bash
# while the stream above is running, count POSTs in the flm serve terminal:
#   2 completions per request == classifier ran (1 classifier + 1 reply)
```

This matters for capacity planning, not for correctness of the model path: every user message may
cost two NPU generations.

## What it does not touch

No production configuration is modified. The app runs with process-local `-D` flags via
`script/run.sh`; no systemd unit, environment file or Nginx config is created or read. Two dev-host
side effects are inherent to any local run: `script/run.sh` writes `script/current-model.txt`, and
the app creates its default `./data` directory.

## Manual equivalent

For eyeballing rather than asserting:

```bash
# non-streaming
curl -sS -X POST http://127.0.0.1:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"In exactly three short sentences, explain what a database index is."}' | jq .

# streaming, watch deltas arrive
curl -sSN -X POST http://127.0.0.1:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"In exactly three short sentences, explain what a database index is."}'
```

Watching the second command is itself the test: text appearing word by word is genuine streaming; a
single lump after a long pause is buffering. `/api/stats` should show `streamed: 1, offline: 0`.

## Troubleshooting

| Failure reading | Likely cause | Check |
|---|---|---|
| `FastFlowLM is not reachable at ...` | nothing is listening, or the listener does not speak HTTP | `flm port`, `ss -ltnp \| grep 52625` |
| `GET /api/stats -> HTTP 4xx/5xx` in the preflight | FastFlowLM does not implement that path | not fatal. The harness falls back to `/v1/models` and skips the counter cross-check |
| Request hangs until timeout | model tag wrong | `flm list --filter installed` — R7/R8: no 404, it hangs |
| `offline=true` | transport rejected the request | app log: `LLM stream failed: ...` carries the FastFlowLM body |
| `No base URL configured for provider` warning | base URL not passed explicitly | use `script/run.sh`, or set `companionai.llm.baseUrl` |
| `port 8080 is already in use` | app already running | stop it, or set `APP_URL=` to target the other instance |
| Deltas arrive but all at once | buffering in front of the app | none expected locally; this is the Nginx check in §15 |

### Note on the FastFlowLM liveness probe

The preflight deliberately does **not** use `curl -f`. FastFlowLM's `/api/stats` answers with a
non-2xx status, and `-f` turns any 4xx/5xx into a curl failure — which made a perfectly healthy
runtime report as `not answering`. Only a transport-level error (connection refused, timeout,
malformed response) counts as down; any HTTP response at all counts as up. To see what the endpoint
actually returns:

```bash
curl -sS -o /dev/null -w 'HTTP %{http_code}\n' http://127.0.0.1:52625/api/stats
curl -sS http://127.0.0.1:52625/v1/models
```

## After it passes

Per the deployment order: §22.6 outbound-network answer → `fastflowlm.service` →
`companionai.service` → Nginx SSE block → auth token via root-only environment file →
persistent `dataDir` → public HTTPS validation → Qwen model/context bake-off.

Only after that does the production model get chosen. This test deliberately uses
`qwen2.5-it:3b` so that deployment work is not blocked on the bake-off.