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
sudo -u fastflowlm -H flm pull qwen2.5-it:3b
```

This is the manual operator step from the network discussion. It is the only part of
FastFlowLM's lifecycle that needs outbound network, which is why it is not automated in
the unit.

## 2. Deploy the application

Build a distribution and install it to a path that is not a git working tree, so a redeploy cannot
overwrite live data:

```bash
./gradlew installDist
sudo mkdir -p /opt/companionai
sudo cp -r build/install/CompanionAI/. /opt/companionai/
```

## 3. The environment file, including the auth token

```bash
sudo install -d -o root -g root -m 0755 /etc/companionai
sudo install -o root -g root -m 0600 deploy/companionai.env.example /etc/companionai/companionai.env
sudoedit /etc/companionai/companionai.env
openssl rand -hex 32      # paste into COMPANIONAI_AUTH_TOKEN
```

`0600` root-owned is correct: systemd reads `EnvironmentFile=` as root before dropping to
`User=companionai`, so the service user never reads the file.

The token goes in the environment, not in `COMPANION_AI_OPTS`. `-D` arguments are world-readable in
`ps -ef`; environment variables are readable only through `/proc/<pid>/environ`.

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
ss -ltnp | grep 52625      # must show 127.0.0.1, never 0.0.0.0 or *
```

Then the app:

```bash
sudo systemctl enable --now companionai.service
systemctl status companionai
curl -sS http://127.0.0.1:8080/api/stats
```

Expect `[VALIDATED]` stream behaviour locally — deltas arriving word by word:

```bash
curl -sSN -X POST http://127.0.0.1:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
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
sudo certbot --nginx -d companionai.runningcode.dev
```

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

## Rollback

```bash
sudo systemctl disable --now companionai.service
sudo systemctl disable --now fastflowlm.service
sudo rm /etc/nginx/sites-enabled/companionai && sudo systemctl reload nginx
```

Removing the units and revoking the certificate leaves no trace. `/var/lib/companionai` is
deliberately not touched.