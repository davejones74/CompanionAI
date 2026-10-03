# X1 Pro + FastFlowLM Phase 0 Validation Runbook

> ## ⚠ No row in this document is `[VALIDATED]`
>
> **Previous drafts incorrectly marked hardware validation as pending in the abstract;** a concrete run was
> performed on **2026-10-03** and recorded in §0.1. The blanket warning above remains the general policy
> of this runbook until all Blocks 1–8 are completed. Nothing here should be treated as runtime/model-
> execution `[VALIDATED]`. The structure below is the plan; §0.1 is the first measurement.
> a placeholder or a vendor claim. This document is a procedure to be executed, not a description of
> a system that exists. Do not cite it as deployment guidance, and do not treat any port, model tag,
> benchmark figure or security conclusion in it as established.

## How to use this document

**Nothing in this document asserts a result.** Every row is either a command to run or a question
that has not yet been answered.

Status markers match `LLM-Provider-Architecture.md`:

| Marker | Meaning |
|---|---|
| `[IMPLEMENTED]` | Written in this repository, compiling, covered by tests. Not confirmed on hardware |
| `[VENDOR]` | Stated by upstream vendor documentation. A claim to be tested, not a measurement |
| `[VALIDATED]` | Confirmed on hardware. **No row currently holds this marker.** |
| `[TBD]` | Unknown. Determined by the commands in this document |

No version, port, model name, benchmark number, API capability or XDNA configuration is assumed
anywhere in this document. Where a value is needed, the document tells you how to discover it rather
than what it is.

### What this document does and does not gate

The implementation of the provider abstraction (Phases 1–3 of `LLM-Provider-Architecture.md` §6) was
**allowed to proceed without this validation**, on operator instruction. That is a deliberate,
documented relaxation: the abstraction is runtime-neutral and its correctness is established by
`OpenAiCompatTransportTest` against a stub, not by this hardware.

This document still gates everything that would become a **claim about the X1 Pro**: performance
figures, the chosen model, the port, firewall and systemd configuration, and any statement that
the deployment is production-ready. See `LLM-Provider-Architecture.md` §6.1.

---

# 0. Running the probes

Part A is committed as a script so it is version-controlled and repeatable. It is **read-only**:
it changes no configuration, downloads no models and starts no services.

```bash
bash docs/x1-validate-part-a.sh 2>&1 | tee x1-a.log
```

It covers Blocks 1–3 and 4.1 (catalogue discovery). Blocks 4–8 are deliberately **not** scripted,
because their commands must embed the port and model tag this run discovers rather than assume them.

---

## 0.1 Part A results — 2026-10-03

`[VALIDATED]` Block 1 passed in full. **The NPU is present and the kernel driver is loaded and
correct.** Block 2 did not "fail" — it established that **no FastFlowLM software is installed at
all**, so Blocks 3–8 cannot run yet. That is a provisioning gap, not a hardware fault.

### Block 1 — hardware, kernel, driver: `[VALIDATED]` PASS

| Signal | Observed | Verdict |
|---|---|---|
| NPU on PCI bus | `c6:00.1 Signal processing controller: Advanced Micro Devices, Inc. [AMD] Strix/Krackan/Strix Halo Neural Processing Unit (rev 10)` | `[VALIDATED]` criterion 1 |
| Device node | `crw-rw----+ 1 root render 261, 0 Oct 2 07:22 accel0` in `/dev/accel/` | `[VALIDATED]` criterion 2 |
| Module loaded | `amdxdna 172032 0`, with `amd_pmf 131072 1 amdxdna` and `gpu_sched 69632 2 amdxdna,amdgpu` | `[VALIDATED]` criterion 3 |
| Driver origin | `/lib/modules/7.0.0-34-generic/kernel/drivers/accel/amdxdna/amdxdna.ko.zst` | **in-tree, not DKMS** |
| `srcversion` | `4612EC552523E4C8FB4B5E5` | recorded verbatim |
| `vermagic` | `7.0.0-34-generic SMP preempt mod_unload modversions` | recorded verbatim |
| `dkms status` | *no output* — DKMS is **not installed** | consistent with in-tree driver |
| Kernel | `7.0.0-34-generic` | `[VENDOR]` ≥ 7.0 ships `amdxdna` in-tree |
| OS | `Ubuntu 26.04.1 LTS (Resolute Raccoon)` | **newer than any supported release** |
| CPU threads | `24` | |
| RAM | `Mem: 59Gi total, 2.3Gi used, 53Gi free, 4.4Gi buff/cache, 57Gi available`, `Swap: 8.0Gi` | usable total is 59 GiB, not the 64 GB the design assumed |
| `ulimit -l` | **`8192`** (8 MiB) | ⚠ **BLOCKER — see below** |

**The in-tree driver is the desired outcome.** `[VENDOR]` FastFlowLM requires the `amdxdna` driver,
"included in kernel 7.0+, or via amdxdna-dkms". At kernel 7.0.0-34 the in-tree module already
satisfies that requirement. **Do not install `amdxdna-dkms`** — on this kernel it would shadow a
working in-tree module and add a rebuild-on-every-kernel-update failure mode for no benefit.

### ⚠ Blocker 1 — memlock limit is 8 MiB, must be unlimited

```
$ ulimit -l
8192
```

`[VENDOR]` The FastFlowLM Linux guide treats this as a hard prerequisite: NPU work requires locked
memory, and `flm validate` is expected to report `Memlock Limit: infinity`. At 8 MiB, device buffer
allocation will fail — most plausibly as `flm run` erroring with `No such device with index '0'`,
which is easily misread as a missing NPU.

Fix before installing:

```bash
echo -e "* soft memlock unlimited\n* hard memlock unlimited" | sudo tee -a /etc/security/limits.conf
sudo reboot
```

Verify afterwards with `ulimit -l` in a **new login session** — `limits.conf` does not affect
already-running shells.

### ⚠ Blocker 2 — no FastFlowLM, no XRT stack

| Component | Probe | Result |
|---|---|---|
| `flm` | `which flm` | not on PATH |
| `xrt-smi` | `xrt-smi examine` | not found, `exit=127` |
| Packages | `dpkg -l \| grep -Ei 'fastflowlm\|amdxdna\|xrt\|lemonade'` | no matches |
| XRT userspace | — | not installed |

`[VENDOR]` XRT is a documented prerequisite, and the DRM/XRT split is real and expected:

> `flm validate` checks the kernel DRM path. `flm run` opens the NPU through XRT.

So the absence of `xrt-smi` is **not** evidence the NPU is broken — it means the userspace half of
the stack is absent. Once XRT is installed, `xrt-smi examine` must list the NPU independently of
`flm validate` passing.

### ⚠ Risk — Ubuntu 26.04 is newer than any documented target

`[VENDOR]` The FastFlowLM Linux guide lists supported distributions as **Ubuntu 24.04 LTS, Ubuntu
25.10, Arch Linux, and "Other (Generic Linux)"**. Ubuntu **26.04** is not listed. The guide's own
firmware note warns that newer kernels can change NPU firmware protocol expectations, so a
26.04-specific failure is plausible and must not be assumed to be a hardware fault.

Mitigation if FastFlowLM misbehaves: retry the runtime test on Ubuntu 25.10 or 24.04 LTS before
concluding anything about the NPU. Re-verify the driver finding above after any such change.

### Firmware — inconclusive; my Part A probe used the wrong path

Part A reported `no /lib/firmware/amdxdna`. `[VENDOR]` Current guidance points at
**`/lib/firmware/amdnpu/`** for recent kernels — a different path. The negative result is therefore
inconclusive rather than a finding. Firmware **1.1.0.0 or later** is a stated prerequisite, so
re-check the correct path:

```bash
ls -la /lib/firmware/amdnpu/ 2>/dev/null || ls -la /lib/firmware/amdxdna/ 2>/dev/null || echo "no NPU firmware dir"
dpkg -l | grep linux-firmware
```

`docs/x1-validate-part-a.sh` has been corrected to check both paths.

---

# 1. Prerequisites

| Item | Status |
|---|---|
| SSH access to the X1 Pro | `[TBD]` |
| Minisforum X1 Pro, HX-370, 64 GB RAM | Given |
| Ubuntu installed | `[TBD]` |
| No app code running during Block 7 RAM baseline | Required |

## 1.1 `[TBD]` Discover the port, do not assume it

```bash
flm port
flm serve --help
```

`[VENDOR]` FastFlowLM documentation describes a default OpenAI-compatible listen port of `52625`, changeable with `--port` / `-p` or an environment variable. `[TBD]` The installed release's actual default is unknown until the commands above are run.

**Throughout this runbook the port is written as `<FLM_PORT>`.** Substitute the value that `flm serve` prints on startup or that `flm port` reports. Later phases of the project currently plan around `52625`; if the installed release differs, every occurrence must be re-checked before implementation.

---

# 2. Block 1 — Hardware, kernel and driver stack

```bash
uname -a
uname -r
lsb_release -a 2>/dev/null || cat /etc/os-release
lspci | grep -Ei 'npu|signal processing|xilinx|amd'
ls -la /dev/accel/
lsmod | grep amdxdna
modinfo -F filename amdxdna
modinfo amdxdna | grep -E '^(filename|version|srcversion|vermagic)'
ulimit -l
free -h
nproc
```

## What to record and check

| Signal | Meaning |
|---|---|
| `lspci` showing an NPU or signal-processing device | `[TBD]` the XDNA2 NPU is visible on the PCI bus |
| `/dev/accel/` containing a render node, typically `/dev/accel/accel0` | `[TBD]` the DRM accelerator device node exists |
| `lsmod \| grep amdxdna` returning a module | `[TBD]` `amdxdna` is loaded |
| `modinfo -F filename amdxdna` | `[TBD]` a path under `updates/dkms` means a DKMS build is active; a path under `kernel/drivers/accel/amdxdna/` means the in-tree driver is in use |
| `ulimit -l` reporting `unlimited` | `[TBD]` the process lock-memory limit is not a blocker |
| `free -h` total and available | Record verbatim; needed for the RAM baseline in Block 7 |

`[TBD]` Record every value verbatim. Do not round, summarise or infer.

---

# 3. Block 2 — Installed component versions

```bash
flm --version
which flm
dpkg -l | grep -Ei 'fastflowlm|amdxdna|xrt|lemonade'
dkms status
ls -la /lib/firmware/amdxdna/ 2>/dev/null || ls -la /lib/firmware/xilinx* 2>/dev/null || echo "no NPU firmware directory found"
```

## Record

| Item | Value |
|---|---|
| FastFlowLM version | `[TBD]` |
| Package name and version | `[TBD]` |
| DKMS module versions | `[TBD]` |
| NPU firmware files present | `[TBD]` |
| Kernel version | `[TBD]` |

`[TBD]` These exact strings must appear in the production deployment documents. Do not paraphrase.

---

# 4. Block 3 — The two decisive checks

```bash
flm validate
echo "--- flm validate exit code: $? ---"

xrt-smi examine
echo "--- xrt-smi examine exit code: $? ---"
```

## Decision gate

`[VENDOR]` FastFlowLM documentation states that `flm validate` and `flm run` exercise **different** paths: `flm validate` checks the kernel DRM device, while `flm run` uses the XRT stack. A failure mode where validation passes but `xrt-smi examine` reports no device is therefore plausible and must not be dismissed as a red herring.

| Outcome | Meaning |
|---|---|
| Both succeed and the NPU is listed | `[VALIDATED]` hardware path is healthy. Proceed to Block 4. |
| Either fails | **STOP.** Do not proceed. Report the verbatim output. |

`[TBD]` Neither outcome has been observed yet.

---

# 5. Block 4 — Execute a model on the NPU

This is the decisive hardware gate. A successful `flm validate` is necessary but not sufficient.

### 5.1 Discover the catalogue

```bash
flm --help
flm pull --help
flm list 2>/dev/null || true
flm cache list 2>/dev/null || true
```

`[VENDOR]` FastFlowLM documentation lists a Qwen model catalogue, but tags and exact version suffixes change between releases. `[TBD]` Do not assume any tag resolves. Use the discovery commands above.

`[TBD]` Required candidate set for the bake-off, in priority order:

1. Qwen3 4B
2. Qwen3.5 approximately 9B
3. `qwen3.6-moe:35b-a3b`

### 5.2 Pull and run

```bash
flm pull <MODEL_TAG_FROM_5_1>
flm run <MODEL_TAG_FROM_5_1>
```

Paste the **complete startup banner** and one exchange. The banner typically reports device, driver, firmware and model context length; record all of it.

## Decision gate

| Outcome | Meaning |
|---|---|
| Model loads and generates on the NPU | `[VALIDATED]` FastFlowLM can execute a model on this hardware. **Phase 0 gate satisfied.** Proceed to Block 5. |
| Model loads but runs on CPU, or the NPU utilisation line is absent | **STOP.** Report verbatim. This invalidates the production plan. |
| Any failure | **STOP.** Report verbatim. |

`[TBD]` Unknown at present.

---

# 6. Block 5 — OpenAI-compatibility probes

These probes fill the "verify both runtimes support them" column of `LLM-Provider-Architecture.md` §3.1.

### 6.1 Start the server with **defaults** first

Defaults are probed first so the true bind address and CORS behaviour are observed rather than assumed. Hardened flags are applied only afterwards.

```bash
flm serve <MODEL_TAG_FROM_5_1> &
sleep 25
BASE="http://127.0.0.1:<FLM_PORT>/v1"

echo "=== F4: model discovery (R10) ==="
curl -s -w '\nHTTP %{http_code}\n' "$BASE/models"

echo "=== F1: non-streaming chat (R1, R2, R3, R4, R9) ==="
curl -s -w '\nHTTP %{http_code}\n' "$BASE/chat/completions" \
  -H 'Content-Type: application/json' \
  -d "{\"model\":\"<MODEL_TAG>\",\"messages\":[{\"role\":\"system\",\"content\":\"You are terse.\"},{\"role\":\"user\",\"content\":\"Reply with the single word: ok\"}],\"temperature\":0.7}"

echo "=== F2: streaming (R5, R6) ==="
curl -sN "$BASE/chat/completions" \
  -H 'Content-Type: application/json' \
  -d "{\"model\":\"<MODEL_TAG>\",\"messages\":[{\"role\":\"user\",\"content\":\"Count from 1 to 5\"}],\"stream\":true}" | head -40

echo "=== F3: unknown model (R7, R8) ==="
curl -s -w '\nHTTP %{http_code}\n' "$BASE/chat/completions" \
  -H 'Content-Type: application/json' \
  -d '{"model":"__definitely_not_loaded__","messages":[{"role":"user","content":"hi"}]}'

echo "=== F5: think flag (Q6) ==="
curl -s -w '\nHTTP %{http_code}\n' "$BASE/chat/completions" \
  -H 'Content-Type: application/json' \
  -d "{\"model\":\"<MODEL_TAG>\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"think\":false}"
```

## Required features being tested

| Probe | Tests | Required to hold |
|---|---|---|
| F1 | R1, R2, R3, R4, R9 | Non-streaming reply at `choices[0].message.content`; `system` role accepted; top-level `temperature` accepted; `finish_reason` present |
| F2 | R5, R6 | Frames begin with `data: `; deltas at `choices[0].delta.content`; stream terminates with `data: [DONE]` |
| F3 | R7, R8 | Non-2xx status **and** a parseable error body; server does not hang or crash |
| F4 | R10 | `GET /v1/models` returns a list; confirm the served model tag matches what `flm run` accepted |
| F5 | Q6 | `"think"` field accepted; determine whether reasoning text appears in the response and where |

`[TBD]` F2 is the highest-risk probe. `[IMPLEMENTED]` CompanionAI's shared transport accepts both SSE
`data:` frames and bare JSON lines, so either result is handled without a code change — but the
result still determines which framing is actually in use and must be recorded.

### 6.2 Exposure checks — run with defaults

```bash
echo "=== Q8: bind address ==="
ss -ltnp | grep "<FLM_PORT>"

echo "=== Q9: CORS default ==="
curl -s -D- -o /dev/null -X OPTIONS "$BASE/chat/completions" \
  -H 'Origin: https://evil.example' \
  -H 'Access-Control-Request-Method: POST' | grep -iE '^HTTP|access-control'

echo "=== external reachability from another machine on the LAN ==="
# run FROM A SECOND MACHINE:
curl -s -m 5 -w '\nHTTP %{http_code}\n' "http://<X1PRO_LAN_IP>:<FLM_PORT>/v1/models" \
  || echo "REFUSED/TIMEOUT (expected if bound to loopback)"
```

## 6.3 Record the bind behaviour

`[TBD]` Fill this in once Block 6.2 has run. **This table drives the deployment security design.**

| Question | Observed value |
|---|---|
| Bind address: `127.0.0.1` or `0.0.0.0` / `::` | `[TBD]` |
| Reachable from a second LAN machine | `[TBD]` |
| Reachable from the public Internet | `[TBD]` |
| CORS allowed by default | `[TBD]` |
| Authentication accepted or rejected | `[TBD]` |

---

# 7. Block 6 — Harden and re-verify

```bash
kill %1
flm serve <MODEL_TAG_FROM_5_1> --cors 0 &
sleep 25

echo "=== re-check bind address ==="
ss -ltnp | grep "<FLM_PORT>"

echo "=== re-check CORS ==="
curl -s -D- -o /dev/null -X OPTIONS "http://127.0.0.1:<FLM_PORT>/v1/chat/completions" \
  -H 'Origin: https://evil.example' \
  -H 'Access-Control-Request-Method: POST' | grep -iE '^HTTP|access-control'
```

`[VENDOR]` `--cors 0` disables CORS. `[TBD]` Whether the installed release also offers a bind-address flag is unknown — check `flm serve --help` in Block 1.1 and record it.

---

# 8. Block 7 — Development-host mirror probes

The same required-feature table must be filled from measurement on the **Intel + RTX 4090 + Ollama** machine too, not from documentation.

```bash
BASE="http://localhost:11434/v1"
MODEL="qwen3.6:27b"     # substitute the model actually installed
# ollama list

echo "=== A4: model discovery (R10) ==="
curl -s -w '\nHTTP %{http_code}\n' "$BASE/models"

echo "=== A1: non-streaming chat (R1, R2, R3, R4, R9) ==="
curl -s -w '\nHTTP %{http_code}\n' "$BASE/chat/completions" \
  -H 'Content-Type: application/json' \
  -d "{\"model\":\"$MODEL\",\"messages\":[{\"role\":\"system\",\"content\":\"You are terse.\"},{\"role\":\"user\",\"content\":\"Reply with the single word: ok\"}],\"temperature\":0.7}"

echo "=== A2: streaming (R5, R6) ==="
curl -sN "$BASE/chat/completions" \
  -H 'Content-Type: application/json' \
  -d "{\"model\":\"$MODEL\",\"messages\":[{\"role\":\"user\",\"content\":\"Count from 1 to 5\"}],\"stream\":true}" | head -40

echo "=== A3: unknown model (R7, R8) ==="
curl -s -w '\nHTTP %{http_code}\n' "$BASE/chat/completions" \
  -H 'Content-Type: application/json' \
  -d '{"model":"__definitely_not_loaded__","messages":[{"role":"user","content":"hi"}]}'

echo "=== A5: per-request context (num_ctx) ==="
curl -s -w '\nHTTP %{http_code}\n' "$BASE/chat/completions" \
  -H 'Content-Type: application/json' \
  -d "{\"model\":\"$MODEL\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"options\":{\"num_ctx\":4096}}"
```

`[TBD]` Record the A2 output verbatim. `[IMPLEMENTED]` The production replacement for A2 is a shared
transport that tolerates both framings, so the A2 result determines what is actually in use rather
than whether a code change is needed.

---

# 9. Block 8 — RAM baseline

Run with Ubuntu, Nginx and the CompanionAI JVM running, **before** any model is loaded.

```bash
free -h
cat /proc/meminfo | grep -E 'MemTotal|MemAvailable|MemFree|SwapTotal'
ps -eo pid,rss,comm --sort=-rss | head -15
```

`[TBD]` Record verbatim. This baseline is what makes the memory figures in `Qwen-Model-Evaluation.md` interpretable.

---

# 10. Security design for the FastFlowLM listener

## 10.1 Constraints

1. `[VENDOR]` FastFlowLM has **no authentication**. Any host that can reach the port can use the model. This makes bind address and reachability a security property, not a preference.
2. `[TBD]` The installed release's default bind address is unknown. See §6.3.
3. `[TBD]` Whether a bind-address flag exists is unknown. See §7.

## 10.2 Required target architecture

```text
Internet
    |
   443
    |
  Nginx          <- the only public application entry point
    |
  CompanionAI    <- application, Tomcat
    |
    | loopback connection only
    v
FastFlowLM      <- must never be exposed
    |
  XDNA2
```

`[VENDOR]` **No public `/v1/` proxy endpoint is to be added.** There is no requirement for one. Nginx exists solely as the public TLS and application reverse proxy for CompanionAI.

## 10.3 Required security properties

- [ ] CompanionAI can reach FastFlowLM over loopback
- [ ] FastFlowLM is **not** reachable from the public Internet
- [ ] FastFlowLM is **not** unnecessarily reachable from the LAN
- [ ] Nginx remains the **only** public application entry point

## 10.4 Defence-in-depth decision tree

The order below is fixed. Work down it only after §6.3 is filled in.

| Step | Action | Condition to adopt |
|---:|---|---|
| 1 | **Firewall restriction on the FastFlowLM port** | Adopt if the listener binds to a non-loopback address, or if loopback-only binding cannot be confirmed |
| 2 | **systemd service hardening** | Adopt only the directives that do not interfere with FastFlowLM networking. Each directive must be justified against Block 6 results |
| 3 | **Nginx** | Public TLS and CompanionAI reverse proxy **only**. Never a reason to expose FastFlowLM |

### Explicitly prohibited

`[VENDOR]` **`PrivateNetwork=` must not be introduced automatically.** It places the unit in a private network namespace, which would break the loopback path from the CompanionAI JVM to the FastFlowLM socket unless the namespace is deliberately and verifiably shared. FastFlowLM's actual network requirements must be established first — see §10.5.

### 10.5 Establish how FastFlowLM needs to communicate

`[TBD]` Complete this before selecting any hardening directive.

| Question | Value |
|---|---|
| TCP socket only, or does it need a local UNIX socket? | `[TBD]` |
| Does the CompanionAI JVM need to share the same network namespace? | `[TBD]` |
| Does FastFlowLM require any outbound network access (telemetry, model pull)? | `[TBD]` |
| Which directives does the installed release tolerate? | `[TBD]` |

### 10.6 If the listener binds to `0.0.0.0`

`[TBD]` **Contingency plan, not yet in force.** Adopt in this order:

1. Restart with `--cors 0` and any bind-address flag the release provides (confirm via `flm serve --help`).
2. If no bind-address flag exists, restrict the port with the host firewall so it is reachable only from loopback.
3. Add scoped systemd hardening that does not alter networking.
4. If a loopback-only path still cannot be achieved, prefer leaving FastFlowLM unstarted rather than running it network-exposed. Fall back to a manual, documented stopgap and re-open the question.

`[TBD]` The precise configuration is deliberately left unwritten. It is recorded in `X1Pro-Home-Hosting-Architecture.md` §22 once Block 6.2 supplies the actual bind behaviour.

---

# 11. Output recording template

Paste completed blocks back into this template. Blank or error entries are themselves findings.

```text
## Block 1 — hardware and driver
Kernel:                     <uname -r>
OS:                         <os-release>
lspci NPU line:             <verbatim>
/dev/accel contents:        <verbatim>
lsmod amdxdna:              <verbatim>
modinfo filename:           <verbatim>
modinfo version/vermagic:   <verbatim>
ulimit -l:                  <verbatim>
free -h:                    <verbatim>
nproc:                      <verbatim>

## Block 2 — versions
flm --version:              <verbatim>
which flm:                  <verbatim>
dpkg -l:                    <verbatim>
dkms status:                <verbatim>
NPU firmware dir:           <verbatim>

## Block 3 — decisive checks
flm validate output:        <verbatim>
flm validate exit code:     <0 / non-zero>
xrt-smi examine output:     <verbatim>
xrt-smi exit code:          <0 / non-zero>

## Block 4 — model execution
Catalogue discovery:        <verbatim>
Model tag pulled:           <verbatim>
Startup banner:             <verbatim>
One exchange:               <verbatim>
Ran on NPU or fell back:    <NPU / CPU / unknown>

## Block 5 — API probes
flm serve banner:           <verbatim>
FLM port observed:          <value>
F1 non-stream:              <verbatim>
F2 streaming frames:        <verbatim>
F3 unknown model:           <verbatim>
F4 /v1/models:              <verbatim>
F5 think flag:              <verbatim>
ss -ltnp bind address:      <verbatim>
CORS preflight response:    <verbatim>
Reachable from 2nd LAN host:<verbatim>

## Block 6 — hardened re-check
Hardened command used:      <verbatim>
Bind address after:         <verbatim>
CORS after:                 <verbatim>

## Block 7 — Ollama mirror (Intel dev host)
Ollama version:             <verbatim>
Model used:                 <verbatim>
A1 non-stream:              <verbatim>
A2 streaming frames:        <verbatim>
A3 unknown model:           <verbatim>
A4 /v1/models:              <verbatim>
A5 num_ctx accepted:        <verbatim>

## Block 8 — RAM baseline
MemTotal:                   <verbatim>
MemAvailable:               <verbatim>
Top RSS processes:          <verbatim>
```

---

# 12. Phase 0 exit criteria

Phase 0 is complete only when **all** of the following are `[VALIDATED]`. Until then, **nothing about
the X1 Pro may be asserted, deployed or benchmarked** — no port, no model, no performance figure, no
security configuration.

This no longer blocks writing the provider abstraction, which was permitted to proceed under the
Gate A / Gate B split described in `LLM-Provider-Architecture.md` §6.1. It continues to block every
claim in the table below.

| # | Criterion | Block |
|---:|---|---|
| 1 | XDNA2 NPU visible on the PCI bus | 1 |
| 2 | `/dev/accel` device node present | 1 |
| 3 | `amdxdna` module loaded and version recorded | 1, 2 |
| 4 | `flm validate` succeeds | 3 |
| 5 | `xrt-smi examine` lists the NPU | 3 |
| 6 | A supported Qwen model loads and generates **on the NPU** | 4 |
| 7 | FastFlowLM OpenAI-compatible API reachable over loopback | 5 |
| 8 | R1–R10 verified or explicitly waived with evidence | 5, 7 |
| 9 | Actual bind address and CORS default documented | 5, 6 |
| 10 | RAM baseline recorded | 8 |
| 11 | Final security configuration selected and recorded | 10 |

If any criterion fails, the outcome is documented and the production runtime decision is revisited. A failed criterion is a valid and useful Phase 0 result; it is not a reason to proceed regardless.