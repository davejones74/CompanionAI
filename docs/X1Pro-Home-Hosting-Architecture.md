# X1 Pro Home Application Hosting Architecture

## CompanionAI Deployment Infrastructure

This document captures the infrastructure established on the Minisforum X1 Pro HX-370 Ubuntu server for hosting applications such as CompanionAI.

The first planned application is [CompanionAI](https://github.com/davejones74/CompanionAI), a Java web application that runs against a local LLM runtime and exposes an embedded Tomcat HTTP server on port 8080. The repository documents `0.0.0.0:8080` as its default cloud-hosting configuration and recommends an access token and `allowPrivateFetch=false` for public deployments.

### Status legend

This document distinguishes what is already true from what is still intended. CompanionAI's local LLM runtime is currently changing from Ollama to FastFlowLM, so several sections below carry both an existing and a planned state.

| Marker | Meaning |
|---|---|
| `[CURRENT]` | Already established. Deployed and verified on the X1 Pro, or read directly from the repository source. |
| `[PLANNED]` | Design decision taken, **not yet implemented or verified**. |
| `[VENDOR]` | Stated by upstream vendor documentation. A claim to be tested, not a measurement. |
| `[VALIDATED]` | Confirmed by measurement on this hardware. |
| `[TBD]` | Unknown. Determined by the Phase 0 runbook. |

**No FastFlowLM version, port, model, benchmark figure or XDNA configuration is assumed anywhere in this document.**

### Companion documents

| Document | Purpose |
|---|---|
| [LLM-Provider-Architecture.md](LLM-Provider-Architecture.md) | The current Ollama coupling, the required OpenAI-compatible feature set, and the planned provider abstraction |
| [X1Pro-FastFlowLM-Validation.md](X1Pro-FastFlowLM-Validation.md) | Phase 0 runbook. Must be executed and reported before any implementation begins |
| [Qwen-Model-Evaluation.md](Qwen-Model-Evaluation.md) | Bake-off plan and result recording for model and context-budget selection |

---

## 1. Architecture

```text
                              INTERNET
                                  |
                         Public IPv4 address
                                  |
                         +--------v--------+
                         |     Sky Hub     |
                         | Home Router     |
                         +--------+--------+
                                  |
                   Port forwarding: 22 / 80 / 443
                                  |
                         +--------v--------+
                         | Minisforum      |
                         | X1 Pro HX-370   |
                         | Ubuntu          |
                         +--------+--------+
                                  |
                 +----------------+----------------+
                 |                |                |
                 v                v                v
              SSH :22         Nginx :80       Nginx :443
                                                   |
                                                   |
                                          TLS termination
                                                   |
                                           Reverse proxy
                                                   |
                                           Application
                                             :8080
                                                   |
                                           +-------v-------+
                                           | CompanionAI  |
                                           | Java/Tomcat  |
+-------+-------+
                                                    |
                                            localhost:11434
                                                    |
                                            +-------v-------+
                                            |    Ollama    |
                                            | Local LLM    |
                                            +---------------+

                     DDNS runs independently:
                          X1 Pro -> GoDaddy API
```

`[CURRENT]` The Ollama path above describes today's application wiring and is verified on the Intel development host.

`[PLANNED]` On the X1 Pro the local LLM is **FastFlowLM on the AMD XDNA2 NPU**, not Ollama. Only the innermost box changes; every layer above it is unaffected:

```text
                                 INTERNET
                                     |
                            Public IPv4 address
                                     |
                            +--------v--------+
                            |     Sky Hub     |
                            +--------+--------+
                                     |
                      Port forwarding: 22 / 80 / 443
                                     |
                            +--------v--------+
                            | Minisforum      |
                            | X1 Pro HX-370   |
                            | Ubuntu          |
                            +--------+--------+
                                     |
                    +----------------+----------------+
                    |                |                |
                    v                v                v
                 SSH :22         Nginx :80       Nginx :443
                                                     |
                                             TLS termination
                                                     |
                                             Reverse proxy
                                                     |
                                             Application
                                               :8080
                                                     |
                                             +-------v-------+
                                             | CompanionAI  |
                                             | Java/Tomcat  |
                                             +-------+-------+
                                                     |
                                            loopback: FLM_PORT
                                                     |
                                             +-------v-------+
                                             |  FastFlowLM  |
                                             |  Local LLM   |
                                             +-------+-------+
                                                     |
                                                 XDNA2 NPU

                     DDNS runs independently:
                          X1 Pro -> GoDaddy API
```

`[TBD]` `FLM_PORT` is **not yet known**. FastFlowLM documentation describes a default of `52625`, but the installed release's actual default must be read from `flm port` or the `flm serve` startup banner before any port is written into a firewall rule, a systemd unit or an Nginx configuration. See [X1Pro-FastFlowLM-Validation.md](X1Pro-FastFlowLM-Validation.md) §1.1.

`[PLANNED]` FastFlowLM has **no authentication**. Its listener must therefore never be reachable from the Internet, and must not be unnecessarily reachable from the LAN. See §22.

### Network model

The X1 Pro has a fixed internal LAN address.

The Sky Hub forwards:

| Public port | Internal port | Purpose |
|---:|---:|---|
| 22 | 22 | SSH |
| 80 | 80 | HTTP / ACME certificate validation |
| 443 | 443 | HTTPS |

`[CURRENT]` **No other port is forwarded.** In particular neither the application port nor the local LLM runtime port appears in the Sky Hub configuration. If a new port appears here, the security model in §18 and §22 has been bypassed.

The internal IP is intentionally fixed because the Sky Hub port-forwarding rules depend on it.

The public IPv4 address is not assumed to be permanent. The DDNS service keeps the GoDaddy DNS record synchronised with the current public IPv4 address.

---

# 2. Domain and DNS

Domain:

```text
runningcode.dev
```

Application/server hostname:

```text
x1pro.runningcode.dev
```

DNS provider:

```text
GoDaddy
```

A record:

```text
x1pro.runningcode.dev -> <current public IPv4>
```

A CAA record is also configured to permit the certificate authority used for HTTPS certificates.

Verify the A record with:

```bash
dig A x1pro.runningcode.dev +short
```

Verify the current public IPv4 with:

```bash
curl -4 https://api.ipify.org
echo
```

These should normally return the same address.

---

# 3. GoDaddy API / Dynamic DNS

## Purpose

Residential ISP public IPv4 addresses can change.

The X1 Pro therefore runs a lightweight DDNS updater which checks the public IPv4 address every 15 minutes.

The updater:

1. Gets the current public IPv4 from `api.ipify.org`.
2. Reads the GoDaddy A record.
3. Extracts the current DNS IP and GoDaddy `recordId`.
4. Compares the IP addresses.
5. Does nothing if they match.
6. Replaces the GoDaddy record if they differ.

GoDaddy's current Domains API supports reading DNS records and replacing an individual record using its stable `recordId`. The replacement operation requires the writable fields `name`, `type`, `data`, and `ttl`. The API uses a bearer PAT and the `domains.dns:update` permission for DNS writes.

## GoDaddy API documentation

- https://developer.godaddy.com/en/docs/api-users/domains/manage/dns
- https://developer.godaddy.com/en/docs/references/rest/domains/v3/replace-dns-record

---

# 4. GoDaddy PAT Configuration

The GoDaddy Personal Access Token is stored locally and must not be committed to source control.

Configuration file:

```text
/etc/godaddy-ddns/config
```

Example:

```bash
GODADDY_DOMAIN="runningcode.dev"
GODADDY_HOST="x1pro"
GODADDY_PAT="YOUR_ACTUAL_PAT"
```

Do not put the real PAT in this document or in Git.

Recommended permissions:

```bash
sudo chown root:root /etc/godaddy-ddns/config
sudo chmod 600 /etc/godaddy-ddns/config
```

Directory:

```bash
sudo chmod 700 /etc/godaddy-ddns
```

The PAT should have the minimum required DNS permissions.

---

# 5. GoDaddy API Read Test

Load the configuration from a root shell:

```bash
sudo -i
source /etc/godaddy-ddns/config
```

Read the A record:

```bash
curl -s \
  -H "Authorization: Bearer $GODADDY_PAT" \
  "https://api.godaddy.com/v3/domains/zones/$GODADDY_DOMAIN/dns-records?type=A&name=$GODADDY_HOST"
```

The response contains:

- `data` — current IP address
- `name` — DNS hostname
- `recordId` — stable GoDaddy record identifier
- `ttl` — DNS TTL
- `type` — record type

Example:

```json
{
  "items": [
    {
      "data": "94.2.13.93",
      "name": "x1pro",
      "recordId": "<record-id>",
      "ttl": 3600,
      "type": "A"
    }
  ]
}
```

---

# 6. GoDaddy DNS Update Test

The current GoDaddy API uses:

```text
PUT /v3/domains/zones/{zone}/dns-records/{recordId}
```

Example:

```bash
curl -fsS -X PUT \
  -H "Authorization: Bearer $GODADDY_PAT" \
  -H "Content-Type: application/json" \
  "https://api.godaddy.com/v3/domains/zones/${GODADDY_DOMAIN}/dns-records/${RECORD_ID}" \
  -d '{"name":"x1pro","type":"A","data":"94.2.13.93","ttl":3600}'
```

The full record must be supplied because this is a full replacement operation.

A successful response returns the updated DNS record.

---

# 7. DDNS Script

Script location:

```text
/usr/local/bin/godaddy-ddns
```

Create/edit with:

```bash
sudo vi /usr/local/bin/godaddy-ddns
```

Current script:

```bash
#!/bin/bash

set -euo pipefail

CONFIG="/etc/godaddy-ddns/config"

source "$CONFIG"

PUBLIC_IP=$(curl -4 -fsS https://api.ipify.org)

RECORD=$(
    curl -fsS \
        -H "Authorization: Bearer $GODADDY_PAT" \
        "https://api.godaddy.com/v3/domains/zones/${GODADDY_DOMAIN}/dns-records?type=A&name=${GODADDY_HOST}"
)

CURRENT_IP=$(echo "$RECORD" | jq -r '.items[0].data')
RECORD_ID=$(echo "$RECORD" | jq -r '.items[0].recordId')
TTL=$(echo "$RECORD" | jq -r '.items[0].ttl')

echo "Public IP : $PUBLIC_IP"
echo "DNS IP    : $CURRENT_IP"
echo "Record ID : $RECORD_ID"

if [[ "$PUBLIC_IP" == "$CURRENT_IP" ]]; then
    echo "No change required."
    exit 0
fi

echo "IP addresses differ."
echo "Updating ${GODADDY_HOST}.${GODADDY_DOMAIN} to $PUBLIC_IP..."

curl -fsS -X PUT \
    -H "Authorization: Bearer $GODADDY_PAT" \
    -H "Content-Type: application/json" \
    "https://api.godaddy.com/v3/domains/zones/${GODADDY_DOMAIN}/dns-records/${RECORD_ID}" \
    -d "[{\"name\":\"${GODADDY_HOST}\",\"type\":\"A\",\"data\":\"${PUBLIC_IP}\",\"ttl\":${TTL}}]"

echo
echo "DNS update successful."
```

Permissions:

```bash
sudo chown root:root /usr/local/bin/godaddy-ddns
sudo chmod 700 /usr/local/bin/godaddy-ddns
```

Required package:

```bash
sudo apt install jq -y
```

`curl` is also required.

---

# 8. DDNS Manual Test

Run:

```bash
sudo /usr/local/bin/godaddy-ddns
```

Normal output when the addresses match:

```text
Public IP : 94.2.13.93
DNS IP    : 94.2.13.93
Record ID : <record-id>
No change required.
```

This confirms that the script can:

- determine the public IP;
- authenticate to GoDaddy;
- read the DNS record;
- obtain the record ID;
- compare the addresses.

The write path was also tested successfully using the GoDaddy `recordId` endpoint.

---

# 9. systemd DDNS Service

Service:

```text
/etc/systemd/system/godaddy-ddns.service
```

Contents:

```ini
[Unit]
Description=Update GoDaddy DNS with current public IPv4
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/bin/godaddy-ddns
```

Reload systemd after changes:

```bash
sudo systemctl daemon-reload
```

Run manually:

```bash
sudo systemctl start godaddy-ddns.service
```

View logs:

```bash
journalctl -u godaddy-ddns.service -n 20 --no-pager
```

---

# 10. systemd DDNS Timer

Timer:

```text
/etc/systemd/system/godaddy-ddns.timer
```

Contents:

```ini
[Unit]
Description=Check GoDaddy DNS every 15 minutes

[Timer]
OnBootSec=2min
OnUnitActiveSec=15min
Persistent=true

[Install]
WantedBy=timers.target
```

Enable it:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now godaddy-ddns.timer
```

Check status:

```bash
systemctl status godaddy-ddns.timer --no-pager
```

Expected:

```text
Active: active (waiting)
```

List scheduled execution:

```bash
systemctl list-timers godaddy-ddns.timer
```

The timer is configured to run every 15 minutes.

---

# 11. DDNS Troubleshooting

Check the timer:

```bash
systemctl status godaddy-ddns.timer --no-pager
```

Check recent executions:

```bash
journalctl -u godaddy-ddns.service -n 50 --no-pager
```

Follow logs:

```bash
journalctl -u godaddy-ddns.service -f
```

Run the updater manually:

```bash
sudo /usr/local/bin/godaddy-ddns
```

Check public IP:

```bash
curl -4 https://api.ipify.org
echo
```

Check DNS:

```bash
dig A x1pro.runningcode.dev +short
```

---

# 12. Nginx Installation

Nginx is the public HTTP/HTTPS entry point.

Install Nginx and Certbot:

```bash
sudo apt update
sudo apt install nginx certbot python3-certbot-nginx -y
```

The architecture uses Nginx as the reverse proxy / TLS termination layer.

Applications should normally listen on an internal port such as:

```text
127.0.0.1:8080
```

or:

```text
0.0.0.0:8080
```

with Nginx being the externally exposed HTTP/HTTPS endpoint.

This means applications do not need to handle public TLS directly.

---

# 13. Initial Nginx Server Block

Configuration:

```text
/etc/nginx/sites-available/x1pro
```

Initial configuration:

```nginx
server {
    listen 80;
    server_name x1pro.runningcode.dev;

    location / {
        return 200 'x1pro Mini PC Online\n';
        add_header Content-Type text/plain;
    }
}
```

Enable it:

```bash
sudo ln -sf /etc/nginx/sites-available/x1pro /etc/nginx/sites-enabled/
```

Remove the default site:

```bash
sudo rm -f /etc/nginx/sites-enabled/default
```

Test:

```bash
sudo nginx -t
```

Restart:

```bash
sudo systemctl restart nginx
```

At this stage:

```text
http://x1pro.runningcode.dev
```

should reach Nginx.

---

# 14. Let's Encrypt Certificate

Once:

- DNS resolves to the current public IP;
- port 80 is forwarded to the X1 Pro;
- Nginx is running;
- the HTTP server block responds correctly;

request the certificate:

```bash
sudo certbot --nginx -d x1pro.runningcode.dev
```

Certbot can obtain the certificate and modify the Nginx configuration to enable HTTPS.

After completion:

```text
https://x1pro.runningcode.dev
```

should be available.

Check certificates:

```bash
sudo certbot certificates
```

Test renewal:

```bash
sudo certbot renew --dry-run
```

The certificate lifecycle should be managed by Certbot rather than manually replacing certificate files.

---

# 15. Nginx Reverse Proxy for Applications

Once an application is deployed, Nginx should normally proxy the public hostname to the application's internal port.

Example:

```nginx
server {
    listen 443 ssl;
    server_name companionai.runningcode.dev;

    ssl_certificate /etc/letsencrypt/live/companionai.runningcode.dev/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/companionai.runningcode.dev/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;

        proxy_http_version 1.1;

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

The exact configuration should be adjusted for the application's requirements.

For applications using streaming responses or Server-Sent Events, additional proxy settings may be required.

---

# 16. CompanionAI

Repository:

https://github.com/davejones74/CompanionAI

CompanionAI is a Java web application using an embedded Tomcat server and a local LLM.

`[CURRENT]` The application currently talks to **Ollama** using Ollama's native API. The repository documents:

```text
JDK 26
Gradle wrapper
Ollama
Default Ollama URL: http://localhost:11434
Default application port: 8080
Default bind address: 0.0.0.0
```

`[PLANNED]` CompanionAI is being made runtime- and model-agnostic. On the X1 Pro the local LLM will be **FastFlowLM on the AMD XDNA2 NPU**, reached over its OpenAI-compatible HTTP API. The same application artefact is expected to serve both the Intel/Ollama development host and the X1 Pro/FastFlowLM production host with no source changes.

`[PLANNED]` The application can therefore fit naturally behind the existing Nginx architecture.

`[CURRENT]` Recommended deployment model, valid today with Ollama:

```text
Internet
   |
HTTPS :443
   |
Nginx
   |
127.0.0.1:8080
   |
CompanionAI
   |
localhost:11434
   |
Ollama
   |
Local GPU
```

`[PLANNED]` Target deployment model on the X1 Pro:

```text
Internet
   |
HTTPS :443
   |
Nginx                <- the only public application entry point
   |
127.0.0.1:8080
   |
CompanionAI
   |
loopback:FLM_PORT
   |
FastFlowLM           <- never exposed; no public /v1/ proxy endpoint
   |
XDNA2 NPU
```

CompanionAI's public-facing configuration should use an authentication token.

`[CURRENT]` Example application properties:

```text
campanionai.host=0.0.0.0
campanionai.port=8080
campanionai.authToken=<SECRET>
campanionai.ollamaUrl=http://localhost:11434
campanionai.allowPrivateFetch=false
```

`[PLANNED]` Planned provider-aware properties. **Not implemented.** The existing properties above continue to work unchanged.

```text
campanionai.llm.provider=fastflowlm
campanionai.llm.baseUrl=http://127.0.0.1:<FLM_PORT>
campanionai.llm.think=false
campanionai.model=<TAG_RESOLVED_DURING_BAKEOFF>
```

`[TBD]` `FLM_PORT` and `TAG` are both unknown until the Phase 0 runbook and the bake-off are complete. See [X1Pro-FastFlowLM-Validation.md](X1Pro-FastFlowLM-Validation.md) and [Qwen-Model-Evaluation.md](Qwen-Model-Evaluation.md).

`[PLANNED]` One naming discrepancy to be resolved during implementation: the existing property namespace is misspelled `campanionai.*` (double `n`), while the planned new keys use the correctly spelled `companionai.llm.*`. The legacy names will be retained. This is recorded in [LLM-Provider-Architecture.md](LLM-Provider-Architecture.md) §4.4.

The repository specifically recommends setting `campanionai.authToken` for public hosting and keeping `campanionai.allowPrivateFetch=false` as an SSRF protection.

Do not place the real authentication token in source control.

---

# 17. CompanionAI DNS

A dedicated hostname can be created:

```text
companionai.runningcode.dev
```

If it points at the same home public IP, it can use the same DDNS mechanism.

The architecture then becomes:

```text
companionai.runningcode.dev
            |
         GoDaddy
            |
       Public IPv4
            |
         Sky Hub
            |
          :443
            |
          Nginx
            |
       127.0.0.1:8080
            |
       CompanionAI
```

The existing DDNS updater currently manages:

```text
x1pro.runningcode.dev
```

If additional hostnames are required, the DDNS strategy should be extended carefully so that the public IP is kept consistent across the required A records.

---

# 18. Security Model

Only the following ports are currently intended to be forwarded from the Sky Hub:

```text
22    SSH
80    HTTP / certificate validation
443   HTTPS
```

Application ports such as:

```text
8080
11434        [CURRENT] Ollama, development host
FLM_PORT     [PLANNED] FastFlowLM, production host. Value unknown.
```

should **not** be forwarded directly from the Internet.

They should remain behind Nginx and/or be bound to localhost where practical.

`[CURRENT]` In particular, Ollama's API should not be exposed directly to the Internet.

`[PLANNED]` The FastFlowLM listener needs a **stronger** rule than "should not be exposed", because `[VENDOR]` it has no authentication whatsoever. Any host able to reach the port can use the model. The required target architecture, the required security properties and the ordered defence-in-depth procedure are in §22. FastFlowLM must be:

- reachable from CompanionAI over loopback;
- **not** reachable from the public Internet;
- **not** unnecessarily reachable from the LAN.

`[PLANNED]` No public `/v1/` proxy endpoint is to be created for FastFlowLM. Nginx exists solely as the public TLS and application reverse proxy for CompanionAI.

### SSH

Public SSH exposure should use:

- SSH keys;
- password authentication disabled where practical;
- root login disabled;
- appropriate firewall rules;
- regular OS/security updates.

A VPN such as Tailscale or WireGuard can be considered if public SSH access is no longer required.

---

# 19. Verification Checklist

## Network

- [ ] X1 Pro has a fixed LAN IP.
- [ ] Sky Hub forwards TCP 22 to the X1 Pro.
- [ ] Sky Hub forwards TCP 80 to the X1 Pro.
- [ ] Sky Hub forwards TCP 443 to the X1 Pro.
- [ ] No application port such as 8080 is directly exposed.

## DNS

- [ ] `runningcode.dev` is using GoDaddy authoritative DNS.
- [ ] `x1pro.runningcode.dev` has an A record.
- [ ] A CAA record permits the intended certificate authority.
- [ ] GoDaddy PAT is stored securely.

## DDNS

- [ ] `/etc/godaddy-ddns/config` exists.
- [ ] PAT is not committed to Git.
- [ ] `/usr/local/bin/godaddy-ddns` exists.
- [ ] Manual DDNS execution succeeds.
- [ ] GoDaddy record update has been tested.
- [ ] `godaddy-ddns.timer` is enabled.
- [ ] Timer is active.
- [ ] Service logs show successful checks.

## Nginx

- [ ] Nginx installed.
- [ ] `/etc/nginx/sites-available/x1pro` configured.
- [ ] Site linked under `sites-enabled`.
- [ ] `nginx -t` succeeds.
- [ ] HTTP endpoint responds.
- [ ] HTTPS certificate issued.
- [ ] Certbot renewal tested.

## CompanionAI

`[CURRENT]` Established platform prerequisites:

- [ ] JDK 26 installed.
- [ ] Ollama installed and running. *(development host)*
- [ ] Required model downloaded. *(development host)*
- [ ] CompanionAI built successfully.
- [ ] CompanionAI listens on internal port 8080.
- [ ] `campanionai.authToken` configured.
- [ ] `campanionai.allowPrivateFetch=false`.
- [ ] Nginx reverse proxy configured.
- [ ] Public HTTPS endpoint tested.
- [ ] Application port 8080 is not exposed through the Sky Hub.

## `[PLANNED]` X1 Pro local LLM (FastFlowLM)

- [ ] Phase 0 runbook completed and output recorded. See [X1Pro-FastFlowLM-Validation.md](X1Pro-FastFlowLM-Validation.md).
- [ ] XDNA2 NPU detected.
- [ ] `flm validate` succeeds.
- [ ] `xrt-smi examine` lists the NPU.
- [ ] A supported Qwen model loads and generates **on the NPU**.
- [ ] FastFlowLM version, driver and firmware versions recorded verbatim.
- [ ] `FLM_PORT` confirmed from `flm port` or the `flm serve` banner.
- [ ] Actual bind address recorded from `ss -ltnp`.
- [ ] CORS default behaviour recorded.
- [ ] Model selected and context budget recorded. See [Qwen-Model-Evaluation.md](Qwen-Model-Evaluation.md).
- [ ] `campanionai.llm.provider` and `campanionai.llm.baseUrl` configured.
- [ ] FastFlowLM started with `--cors 0`.
- [ ] **FastFlowLM is not reachable from the public Internet.**
- [ ] **FastFlowLM is not reachable from a second host on the LAN.**
- [ ] CompanionAI can reach FastFlowLM over loopback.
- [ ] Nginx exposes only the CompanionAI application; no `/v1/` path is proxied to FastFlowLM.
- [ ] Firewall and systemd configuration for the FastFlowLM port recorded in §22.

---

# 20. Operational Commands

### Nginx

```bash
sudo nginx -t
sudo systemctl status nginx
sudo systemctl restart nginx
sudo journalctl -u nginx -n 50 --no-pager
```

### DDNS

```bash
sudo /usr/local/bin/godaddy-ddns
systemctl status godaddy-ddns.timer --no-pager
systemctl list-timers godaddy-ddns.timer
journalctl -u godaddy-ddns.service -n 50 --no-pager
```

### Certificate

```bash
sudo certbot certificates
sudo certbot renew --dry-run
```

### DNS

```bash
dig A x1pro.runningcode.dev +short
curl -4 https://api.ipify.org
```

### Application

```bash
curl http://127.0.0.1:8080/
```

Use the application-specific service/process management mechanism once CompanionAI is deployed.

---

# 21. Architecture Principle

The important architectural separation is:

```text
                PUBLIC INTERNET
                       |
                 GoDaddy DNS
                       |
                Dynamic Public IP
                       |
                   Sky Hub
                       |
                  80 / 443
                       |
                    Nginx
                       |
              +--------+--------+
              |                 |
        TLS termination    Reverse proxy
                                |
                         Application :8080
                                |
                    +-----------+-----------+
                    |                       |
         [CURRENT] Local Ollama      [PLANNED] FastFlowLM
            :11434                      FLM_PORT
                    |                       |
               Local GPU                 XDNA2 NPU
```

DNS, public networking, TLS, reverse proxying, application hosting, and the local AI runtime are deliberately separated.

The local AI runtime is a swappable innermost layer. Replacing Ollama with FastFlowLM does not alter DNS, TLS, Nginx or the application tier.

This allows additional applications to be added without changing the basic home-server architecture.

For example:

```text
planning.runningcode.dev  -> Nginx -> application
companionai.runningcode.dev -> Nginx -> CompanionAI
ai.runningcode.dev -> Nginx -> another service
```

The DDNS layer remains responsible only for keeping the public IP current.

---

# 22. `[PLANNED]` FastFlowLM Listener Security

This section is **intentionally not yet filled in.** The precise configuration depends on measured facts that do not exist yet. What follows is the decision procedure, not the answer.

## 22.1 Why this section exists

`[VENDOR]` FastFlowLM has **no authentication**. Upstream documentation states that a placeholder API key is accepted rather than validated. Consequently:

- any host that can reach the port can use the model;
- any host that can reach the port can cause resource consumption;
- there is no credential to leak, rotate or revoke.

`[TBD]` This makes the listener's bind address and network reachability a **security property** rather than a preference, and it must be verified on this specific machine and this specific release.

## 22.2 Required target architecture

```text
Internet
    |
   443
    |
  Nginx            <- the only public application entry point
    |
  CompanionAI
    |
    | loopback connection only
    v
FastFlowLM        <- must never be exposed
    |
  XDNA2 NPU
```

## 22.3 Required security properties

- [ ] CompanionAI can reach FastFlowLM over loopback.
- [ ] FastFlowLM is **not** reachable from the public Internet.
- [ ] FastFlowLM is **not** unnecessarily reachable from the LAN.
- [ ] Nginx remains the **only** public application entry point.
- [ ] No public `/v1/` proxy endpoint exists for FastFlowLM. There is no requirement for one.

## 22.4 Inputs required before this section can be completed

| Input | Source | Value |
|---|---|---|
| `FLM_PORT` | `flm port`, or the `flm serve` startup banner | `[TBD]` |
| Bind address | `ss -ltnp \| grep <FLM_PORT>` | `[TBD]` |
| Reachable from a second LAN host? | `curl` from another machine | `[TBD]` |
| CORS default | `OPTIONS` preflight probe | `[TBD]` |
| Bind-address flag exists? | `flm serve --help` | `[TBD]` |
| Network requirements of the service | §22.6 | `[TBD]` |

Recording template: [X1Pro-FastFlowLM-Validation.md](X1Pro-FastFlowLM-Validation.md) §6.3 and §11.

## 22.5 Decision tree

Work down this list **in order**, and only after §22.4 is filled in. Do not skip to a later step because an earlier one looks sufficient.

| Step | Action | Adopt when |
|---:|---|---|
| 0 | Restart with `--cors 0`, plus a bind-address restriction if the release provides one | Always. `[VENDOR]` `--cors 0` disables CORS. `[TBD]` A bind-address flag may not exist |
| 1 | **Firewall restriction on the FastFlowLM port**, scoped to loopback | The listener binds to a non-loopback address, or loopback-only binding cannot be confirmed |
| 2 | **systemd service hardening**, directive by directive, each justified against the measured network requirements | Only the directives that provably do not interfere with FastFlowLM networking |
| 3 | **Nginx** | Public TLS and CompanionAI reverse proxy **only**. Never a reason to expose FastFlowLM |

### Explicitly prohibited

`[VENDOR]` **`PrivateNetwork=` must not be introduced automatically.** It places the service in a private network namespace, which would break the loopback path from the CompanionAI JVM to the FastFlowLM socket unless that namespace is deliberately and verifiably shared between the two units. Introducing it before §22.6 is answered risks taking down the model path with no diagnostic benefit.

### 22.5.1 If the listener binds to `0.0.0.0`

`[TBD]` Contingency plan, not yet in force:

1. Restart with `--cors 0` and any bind-address flag the release provides.
2. If no bind-address flag exists, restrict the port with the host firewall so it is reachable only from loopback.
3. Add scoped systemd hardening that does not alter networking.
4. If a loopback-only path still cannot be achieved, prefer **leaving FastFlowLM unstarted** over running it network-exposed, and record the blocker for review.

## 22.6 Establish how FastFlowLM needs to communicate

`[TBD]` Complete this before selecting any hardening directive.

| Question | Value |
|---|---|
| TCP socket only, or is a local UNIX socket used? | `[TBD]` |
| Must the CompanionAI JVM share the same network namespace? | `[TBD]` |
| Is outbound network access required, for example telemetry or model download? | `[TBD]` |
| Does model download happen once manually, or automatically at service start? | `[TBD]` |
| Which hardening directives does the installed release tolerate? | `[TBD]` |

## 22.7 Record the final configuration here

`[TBD]` Populated once §22.4 and §22.6 are complete.

```text
FLM_PORT:              <value>
Bind address:          <observed>
Firewal rule:          <exact rule>
systemd unit:          <path>
Hardening directives:  <each, with justification>
Rejected directives:   <each, with reason>
Verification:          <commands proving loopback-only>
```

---

# 23. `[PLANNED]` CompanionAI Runtime Migration

`[PLANNED]` Recorded here so the deployment view stays consistent with the application view.

| Phase | Content | Gate |
|---:|---|---|
| 0 | Phase 0 documentation and X1 Pro validation | `flm validate` succeeds and one supported Qwen model runs on the NPU |
| 1 | `LlmProvider` abstraction, shared OpenAI-compatible transport, Ollama and FastFlowLM providers | — |
| 2 | Additive provider configuration properties | — |
| 3 | Consumer rewiring | Existing test suite green |
| 4 | Provider tests; `llmBench` / `llmParity`; run on both hosts | Bake-off complete |
| 5 | `script/run.sh` consolidation; service unit and firewall for FastFlowLM | Acceptance criteria met |

`[PLANNED]` Deployment sequencing. FastFlowLM must be proven working on the X1 Pro **before** CompanionAI is pointed at it. If CompanionAI is deployed first, it stays on Ollama until Phase 0 exits.

`[PLANNED]` No Java implementation work begins until the Phase 0 gate is satisfied. The project's working rule applies: do not rewrite the application around a runtime that has not yet been shown to execute a model on the target hardware.

---

## Status

The following infrastructure has been successfully implemented and tested on the X1 Pro:

- Ubuntu host configured.
- Fixed internal IP configured.
- Sky Hub port forwarding configured.
- GoDaddy DNS configured.
- GoDaddy PAT authentication tested.
- GoDaddy A-record read tested.
- GoDaddy A-record write tested.
- Dynamic public IP detection tested.
- DDNS updater installed.
- systemd DDNS service installed.
- systemd DDNS timer enabled and active.
- Nginx installed.
- HTTP server block configured.
- HTTPS/Certbot deployment prepared.
- Architecture ready for CompanionAI deployment.

`[CURRENT]` Nothing above this line has changed. All of it is implemented and tested.

`[PLANNED]` CompanionAI itself has **not** been deployed, and the local LLM runtime has **not** been migrated to FastFlowLM. No FastFlowLM component is installed or verified on this host.

Next deployment work, in order:

1. Complete the Phase 0 runbook in [X1Pro-FastFlowLM-Validation.md](X1Pro-FastFlowLM-Validation.md) and record the output.
2. Select the model and context budget per [Qwen-Model-Evaluation.md](Qwen-Model-Evaluation.md).
3. Implement the provider abstraction per [LLM-Provider-Architecture.md](LLM-Provider-Architecture.md).
4. Package CompanionAI as a managed application/service, configure its secrets and persistent data directory, create its DNS hostname, configure Nginx reverse proxying, obtain its Let's Encrypt certificate, and validate the public HTTPS deployment.
5. Apply the FastFlowLM listener security configuration recorded in §22.
