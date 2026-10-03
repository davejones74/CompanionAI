# X1 Pro deployment

Artifacts for running CompanionAI on the X1 Pro with FastFlowLM on the NPU. Nothing here is
containerised: two systemd units and one Nginx site, in that order.

| File | Purpose |
|---|---|
| `systemd/fastflowlm.service` | NPU inference runtime, loopback only |
| `systemd/companionai.service` | Tomcat app, ordered after the above |
| `companionai.env.example` | root-only configuration, including the auth token |
| `nginx/companionai.conf` | TLS terminator with SSE-safe proxying |

Two things to read before starting:

- **`ProtectHome` and `ProtectSystem` are commented out.** Each unit ships a conservative stage 1 and
  a commented stage 2. Enable one directive at a time and re-verify. Aggressive filesystem or device
  confinement interacts with XRT in ways this host has not yet been asked to prove.
- **`PrivateNetwork` must never be enabled.** It would put the unit in a private network namespace
  where the host's `127.0.0.1:52625` is invisible. CompanionAI would still start and every reply
  would silently degrade to `offline=true`.

## 1. Accounts and directories

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin fastflowlm
sudo useradd --system --no-create-home --shell /usr/sbin/nologin companionai

# Match the groups XRT needs. Compare against your working shell first:
id -a
sudo usermod -aG render fastflowlm      # if `id -a` shows render for a working session
sudo usermod -aG video  fastflowlm

sudo mkdir -p /var/lib/companionai/data
sudo chown -R companionai:companionai /var/lib/companionai

# State directory for the NPU runtime, and HOME for both services. Do not skip this:
# --no-create-home still records /home/fastflowlm in passwd, so systemd sets HOME to a
# path that does not exist and flm exits 1 on its first operation. See Troubleshooting.
sudo mkdir -p /var/lib/fastflowlm
sudo chown -R fastflowlm:fastflowlm /var/lib/fastflowlm
```

### The model must exist as the service user

`flm pull` run in your shell installs into *your* home. The service runs as
`fastflowlm` with `HOME=/var/lib/fastflowlm`, so it looks in a different cache and will
not find it. Populate the service user's cache once, with the service stopped:

```bash
sudo systemctl stop fastflowlm
sudo -u fastflowlm env HOME=/var/lib/fastflowlm flm pull qwen2.5-it:3b
```

Do not use `sudo -u fastflowlm -H` here. `-H` sets `HOME` from the passwd entry, which is
the stale `/home/fastflowlm`, so it reproduces the original permission error. Pass `HOME`
explicitly to match `Environment=HOME=` in the unit.

This is the manual operator step from the network discussion. It is the only part of
FastFlowLM's lifecycle that needs outbound network, which is why it is not automated in
the unit.

The pull is incremental — it fetches only missing files and verifies hashes for each. A
first-time pull of a 3B model is the large transfer; a pull after an interrupted
on-demand fetch may only need a few hundred KB of tokenizer files. Either way, keep the
service stopped while you run it, so no request can race the download.

## 2. Deploy the application

Build a distribution and install it to a path that is not a git working tree, so a redeploy cannot
overwrite live data:

```bash
./gradlew installDist
sudo mkdir -p /opt/companionai
sudo cp -r build/install/CompanionAI/. /opt/companionai/
```

### Relocate the JDK out of your home directory

`companionai.service` needs `JAVA_HOME`, and a systemd unit gets none of your shell's
environment. On this host the toolchain was at `/home/dave/opt/jdk-26.0.2`, which
`User=companionai` cannot traverse and which `ProtectHome=yes` would hide entirely. Move it
somewhere shared:

```bash
sudo mkdir -p /opt/java
sudo mv /home/dave/opt/jdk-26.0.2 /opt/java/jdk-26.0.2
```

You can leave a symlink behind so an existing `JAVA_HOME` in your shell profile keeps
resolving, or just update the profile to point at the new path. Either way the service must
use the real `/opt/java/...` path, never a symlink under a home directory.

Then prove the service user can actually run it, rather than assuming:

```bash
sudo -u companionai /opt/java/jdk-26.0.2/bin/java -version
```

Set `Environment=JAVA_HOME=` in the unit to the real `/opt/java/...` path.

## 3. The environment file, including the auth token

```bash
sudo install -d -o root -g root -m 0755 /etc/companionai
sudo install -o root -g root -m 0600 deploy/companionai.env.example /etc/companionai/companionai.env
sudoedit /etc/companionai/companionai.env
openssl rand -hex 32      # paste into COMPANIONAI_AUTH_TOKEN
```

`0600` root-owned is correct: systemd reads `EnvironmentFile=` as root before dropping to
`User=companionai`, so the service user never reads the file.

The token goes in the environment, not in `JAVA_OPTS`. `-D` arguments are world-readable
in `ps -ef`; environment variables are readable only through `/proc/<pid>/environ`.

## 4. Units

```bash
sudo cp deploy/systemd/fastflowlm.service /etc/systemd/system/
sudo cp deploy/systemd/companionai.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now fastflowlm.service
```

Check `command -v flm` first and correct `ExecStart` if it is not `/usr/bin/flm`.

Verify the runtime before starting the app — this isolates failures:

```bash
systemctl status fastflowlm
flm port
curl -sS http://127.0.0.1:52625/v1/models
ss -ltnp | grep 52625
```

Reading that last line: check the **Local Address:Port** column, which must be
`127.0.0.1:52625`. A `0.0.0.0:*` in the **Peer Address:Port** column is normal and
expected for any listening socket — it is not a bind address and does not mean the port
is exposed. Reject the line only if the local column reads `0.0.0.0:52625` or
`*:*:52625`.

Then the app:

```bash
sudo systemctl enable --now companionai.service
systemctl status companionai

# Auth required. curl sends Accept: */*, not text/html, so this takes AuthFilter's API
# path and returns 401 without the token. Expect 401 here if you forget it -- that is
# the filter working, not a broken app.
TOKEN=$(sudo grep COMPANIONAI_AUTH_TOKEN /etc/companionai/companionai.env | cut -d= -f2)
curl -sS -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/stats
```

Expect `[VALIDATED]` stream behaviour locally — deltas arriving word by word:

```bash
curl -sSN -X POST http://127.0.0.1:8080/api/chat/stream \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"In exactly three short sentences, explain what a database index is."}'
```

### Why `After=` and not `Requires=`

`companionai.service` declares `After=fastflowlm.service` for ordering, which is what was asked.
It deliberately does **not** declare `Requires=`, which would additionally couple lifetimes and stop
the app whenever the runtime stops. `ModelServlet` already handles a dead provider by returning a
tagged reply with `offline=true`; coupling lifetimes would take the UI down instead of degrading it.

Change this only if you prefer no UI at all to a UI that cannot answer.

## 5. Nginx

```bash
sudo cp deploy/nginx/companionai.conf /etc/nginx/sites-available/companionai
sudo ln -s /etc/nginx/sites-available/companionai /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
```

`proxy_buffering off` and `gzip off` are the load-bearing directives. With buffering on, Nginx reads
the entire response before forwarding a byte, which converts a word-by-word stream into one lump
arriving at the end.

## 6. DNS and certificate

```bash
# A record for companionai.runningcode.dev -> the X1's public IP, then:
sudo mkdir -p /var/www/acme
sudo certbot --webroot -w /var/www/acme -d companionai.runningcode.dev
```

Use `--webroot`, **not** `--nginx`. The site config already declares
`location /.well-known/acme-challenge/ { root /var/www/acme; }`, and `--nginx` works by
injecting a challenge `location` of its own into the server block. Two matching locations in
one block is a `duplicate location` error, so `nginx -t` fails and the server will not reload.
`--webroot` satisfies the challenge through the location that is already there and never edits
the config, which also keeps renewal from rewriting a file under version control.

Run it only after the port 80 redirect is live, or the challenge request gets a 301 and
certbot reports it as unreachable.

## 7. Full path verification

```bash
# unauthenticated must be rejected (curl sends Accept: */*, not text/html, so this
# takes AuthFilter's API path and must return 401)
curl -sS -o /dev/null -w '%{http_code}\n' https://companionai.runningcode.dev/api/stats

# authenticated streaming through Nginx: watch it arrive incrementally.
# AuthFilter reads the "Authorization" header and requires a "Bearer " prefix;
# a browser will instead hold the session cookie set by the login page.
TOKEN=$(sudo grep COMPANIONAI_AUTH_TOKEN /etc/companionai/companionai.env | cut -d= -f2)
curl -sSN -X POST https://companionai.runningcode.dev/api/chat/stream \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"In exactly three short sentences, explain what a database index is."}'
```

Reproduce the measured thresholds from `script/e2e-fastflowlm.sh` through the public endpoint: at
least 5 delta events, at least 300 ms from first to last, and at least 80 ms worst-case gap. A
buffered response lands within a few milliseconds and fails both.

Confirm the runtime stayed private from an external host:

```bash
curl -sS --max-time 5 http://<X1-PUBLIC-IP>:52625/v1/models    # must time out
```

## Troubleshooting

### `filesystem error: cannot create directories: Permission denied [/home/fastflowlm/.config/flm]`

`User=fastflowlm` cannot create its own config directory. Cause is the account, not the
unit's file permissions: `useradd --no-create-home` writes a passwd entry whose home is
`/home/fastflowlm`, systemd exports that as `$HOME`, and the path does not exist and is
not creatable by that user. `flm` fails its very first operation and systemd retries every
10 seconds forever.

Create the directory and point `HOME` at it, as in step 1. To see the real error without
the restart loop:

```bash
sudo systemctl stop fastflowlm
sudo -u fastflowlm env HOME=/var/lib/fastflowlm /usr/bin/flm serve --cors 0
```

### `Warning: could not raise memlock limit to 512 MB`

XRT locks memory for the NPU. `LimitMEMLOCK=infinity` in the unit resolves it. It is only
a warning, so if inference works end to end you can leave it, but do not assume the
default is unlimited — it derives from `DefaultLimitMEMLOCK` in `systemd/system.conf`
and is commonly 64K.

### FastFlowLM restarts every 10 seconds

`Restart=on-failure` with `RestartSec=10` is intentional, but it means a permanently
fatal configuration error presents as an infinite loop rather than one clean failure.
Read the actual cause once with `journalctl -u fastflowlm -n 20` instead of watching
`systemctl status` scroll.

### Model "not found" after fixing the home directory

Expected once, and not a bug. `flm pull` as a normal user installs into that user's
cache. Pull again as the service user (see step 1) or the runtime will start but every
request will fail.

### `/v1/chat/completions` hangs with no output

This is the symptom of a missing model, and it is the worst-behaved failure in this
stack: `flm serve` does not return 404 or any error. It attempts to fetch the weights
on demand from inside the serving process and waits, so the request simply never
completes. `systemctl status` shows no restart, so nothing logs an error either.

Confirm by watching for the download rather than the request:

```bash
journalctl -u fastflowlm -f              # in a second terminal, then retry the curl
sudo du -sh /var/lib/fastflowlm          # a growing size confirms it
```

Prefer the explicit pull in step 1 over letting the server discover this. It prints
progress, fails loudly on a network problem, and does not hold a request open while it
does it.

Note for the network discussion: `flm serve` **will** initiate outbound network when a
requested model is absent from its cache. A run that only ever exercised an
already-installed model would not have shown this.

### Do not use `/v1/models` to check whether a model is installed

`/v1/models` returns the full static catalog of models FastFlowLM supports — around 40
entries including ones that are certainly not on this host. Every entry reports an
identical `created` timestamp, which is the giveaway: these are compile-time constants,
not install records. It answered normally while the model was demonstrably absent, so it
is worthless as an availability check.

Check the filesystem instead:

```bash
sudo ls -l /var/lib/fastflowlm/.config/flm/models/
```

### `invalid UTF-8 byte at index N` from `/v1/chat/completions`

A 500 from the parser, but **not** a parser problem and not a config problem. The real
signature is in the journal: the request appears to succeed all the way through the
NPU, then fails.

```
[🟢 ]  NPU Locked!
[FLM]  Loading model: .../Qwen2.5-3B-Instruct-NPU2
[FLM]  Prefill chunk 1/1 with 31 tokens
[FLM]  Start generating...
[FLM]  Model RAW Output:
[9B blob data]                      <-- binary: the weights are corrupt
[🔵 ]  NPU Lock Released!
```

The model loaded and ran, so `config.json` and the tokenizer are fine. It emitted
non-textual bytes because the weights are truncated, and the failure surfaces later when
FastFlowLM tries to interpret that output — which is why the error names JSON and UTF-8
and sends you looking in entirely the wrong place. `[9B blob data]` under
`Model RAW Output:` is the actual diagnostic.

Cause is normally an interrupted or killed download. `flm pull` lists already-present
files but **only hash-verifies the ones it actually downloads**, so a truncated file
survives every subsequent pull and reports "All files verified successfully".

Compare the weights against a known-good copy rather than re-pulling blind:

```bash
D=/var/lib/fastflowlm/.config/flm/models/Qwen2.5-3B-Instruct-NPU2
sudo ls -l  "$D"/model.q4nx ~/.config/flm/models/Qwen2.5-3B-Instruct-NPU2/model.q4nx
sudo md5sum "$D"/*; md5sum ~/.config/flm/models/Qwen2.5-3B-Instruct-NPU2/*
```

A smaller file is a truncated download. Delete it and pull again — the pull will not
replace it on its own, because it is present and therefore considered done:

```bash
sudo systemctl stop fastflowlm
sudo rm -v "$D"/model.q4nx
sudo -u fastflowlm env HOME=/var/lib/fastflowlm flm pull qwen2.5-it:3b
sudo systemctl start fastflowlm
```

This is the full-size weights download, not the few-hundred-KB tokenizer fetch.

### `ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH`

The Gradle start script exits 1 on its first line. systemd does not source any shell
profile, so `JAVA_HOME` and `PATH` are not inherited the way they are in your terminal.
Set `Environment=JAVA_HOME=` in `companionai.service` to a shared JDK — see step 2 for why
it must not point into a developer's home directory.

Symptom shape to recognise: exit 1 at ~11 ms CPU and ~3 MB peak memory. Tomcat never
starts, so nothing about the app, the ports or FastFlowLM is implicated.

### `nginx: [emerg] unknown directive "http2"`

The config uses the standalone `http2 on;` form, which needs nginx 1.25.1 or newer. On an
older release, fold it into the listen directive instead:

```nginx
listen 443 ssl http2;
```

### `duplicate location /.well-known/acme-challenge/`

A previous `certbot --nginx` run injected a challenge location next to the one already in
`deploy/nginx/companionai.conf`. Delete the injected copy and use `--webroot` from step 6,
which never touches the config.

## Rollback

```bash
sudo systemctl disable --now companionai.service
sudo systemctl disable --now fastflowlm.service
sudo rm /etc/nginx/sites-enabled/companionai && sudo systemctl reload nginx
```

Removing the units and revoking the certificate leaves no trace. `/var/lib/companionai` is
deliberately not touched.