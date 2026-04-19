# ADR-007 — Audit log: x11vnc `-accept`/`-gone` hooks + JSON-lines

**Dátum:** 2026-04-17
**Status:** Accepted (plán pre v2.2.0)
**Týka sa:** `run-vnc.sh`, NEW `vnc-audit-hook.sh`

## Kontext

Sprint 2 B4 vyžaduje audit log: každé pripojenie klienta zapísané s timestamp + zdrojovou IP + duration. Účel:
- Compliance: niektorí zákazníci potrebujú forensic trail
- Diagnostics: "kto sa pripojil včera o 14:00 keď daemon spadol?"
- IXON Cloud má vlastný cloud audit, ale my potrebujeme aj **on-robot** záznam (network failure scenario)

**Otázky:**
1. Akým mechanizmom hookneme connect/disconnect?
2. V akom formáte log?

## Hook mechanizmus — alternatívy

1. **A — pcap sniff na port 5900** — overkill, vyžaduje root + tcpdump
2. **B — iptables LOG target s prefix-om, parse syslog** — funguje, ale chytí aj DROP-nuté pokusy (noise)
3. **C — x11vnc native hooks `-accept` a `-gone`** ✅ ZVOLENÉ
4. **D — Polling `ss -tn '( sport = 5900 )'` v cron** — race conditions, lossy

## Rozhodnutie — hook

**Voľba C:** x11vnc-built-in hooks. x11vnc exportuje environment premenné keď spawne hook:
- `RFB_CLIENT_IP` — peer IP
- `RFB_CLIENT_PORT` — peer port
- `RFB_CLIENT_COUNT` — koľko klientov je connected (vrátane tohto)
- `RFB_HOOK` — `accept` alebo `gone`
- `RFB_X11VNC_PID` — PID hlavného daemona

`run-vnc.sh` zmena:
```bash
exec x11vnc \
  -accept "$AUDIT_HOOK accept" \
  -gone "$AUDIT_HOOK gone" \
  ...
```

`vnc-audit-hook.sh`:
```bash
#!/bin/bash
# vnc-audit-hook.sh accept|gone
ACTION="$1"
TS="$(date -Iseconds)"
IP="${RFB_CLIENT_IP:-unknown}"
PORT="${RFB_CLIENT_PORT:-0}"
COUNT="${RFB_CLIENT_COUNT:-0}"

# Source customer label
[[ -f /var/lib/urcap-vnc/config ]] && source /var/lib/urcap-vnc/config

LOG="/var/log/urcap-vnc-audit.log"

# State file pre duration tracking
STATE="/var/lib/urcap-vnc/sessions/${IP}_${PORT}.start"
mkdir -p /var/lib/urcap-vnc/sessions

if [[ "$ACTION" == "accept" ]]; then
  date +%s > "$STATE"
  printf '{"ts":"%s","event":"connect","ip":"%s","port":%s,"clients_total":%s,"customer":"%s"}\n' \
    "$TS" "$IP" "$PORT" "$COUNT" "$CUSTOMER_LABEL" >> "$LOG"
elif [[ "$ACTION" == "gone" ]]; then
  if [[ -f "$STATE" ]]; then
    START="$(cat "$STATE")"
    DUR=$(( $(date +%s) - START ))
    rm -f "$STATE"
  else
    DUR="null"
  fi
  printf '{"ts":"%s","event":"disconnect","ip":"%s","port":%s,"duration_s":%s,"clients_remaining":%s,"customer":"%s"}\n' \
    "$TS" "$IP" "$PORT" "$DUR" "$COUNT" "$CUSTOMER_LABEL" >> "$LOG"
fi
```

## Format — alternatívy

1. **A — Plain text:** `2026-04-17 14:30:12 connect 100.64.0.5 (1 total)` — human-readable, ale grep-only, žiadny structured query
2. **B — syslog:** zachová systemd integration ale extra layer parsing
3. **C — JSON-lines (.jsonl)** ✅ ZVOLENÉ — `jq` queries, easy to ingest do ELK/Splunk neskôr

## Rozhodnutie — format

**Voľba C: JSON-lines:**
```json
{"ts":"2026-04-17T14:30:12+02:00","event":"connect","ip":"100.64.0.5","port":47821,"clients_total":1,"customer":"STIMBA-internal"}
{"ts":"2026-04-17T15:45:03+02:00","event":"disconnect","ip":"100.64.0.5","port":47821,"duration_s":4491,"clients_remaining":0,"customer":"STIMBA-internal"}
```

Príklady queries:
```bash
# Last 10 connections
jq 'select(.event=="connect")' /var/log/urcap-vnc-audit.log | tail -10

# Long sessions (>1h)
jq 'select(.event=="disconnect" and .duration_s > 3600)' /var/log/urcap-vnc-audit.log

# Unique source IPs in last 7 days
awk -v cutoff="$(date -d '7 days ago' -Iseconds)" '$0 ~ /"event":"connect"/' /var/log/urcap-vnc-audit.log \
  | jq -r 'select(.ts > "'$cutoff'") | .ip' | sort -u
```

## Retention & rotation

`/etc/logrotate.d/urcap-vnc-audit` (inštaluje `post-install.sh`):
```
/var/log/urcap-vnc-audit.log {
    daily
    rotate 90
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
}
```

90 dní rotation = ~1 GB worst-case (1000 sessions/day × ~150B per line × 90).

## Dôsledky

**Pozitívne:**
- Native x11vnc hook = žiadna pridaná surface attack ani extra dependency
- JSON-lines = grep + jq friendly, future SIEM ingest trivial
- Per-IP duration tracking funguje aj pri multiple concurrent clients (state file per IP+port)
- CUSTOMER_LABEL prepojené s log = fleet-wide aggregation per zákazník

**Negatívne:**
- State file `/var/lib/urcap-vnc/sessions/*.start` môže ostať orphaned ak hook -gone neprídee (x11vnc kill -9). Mitigation: `vnc-audit-hook.sh` cleanup-uje state files starší 24h.
- IP `RFB_CLIENT_IP` je peer IP zo strany x11vnc → ak ide cez IXrouter, peer IP je `192.168.0.100` (IXrouter), nie skutočný IXON cloud user. Mitigation: dokumentovať v wiki + pre IXON-side identification audit pozri IXON Cloud Audit Log (mimo scope tohto URCap-u, viď C5 out-of-scope).
- Pri mnoho rapid connections by mohol audit log spomaliť disk (HDD na UR controller je SSD ale nízko-end). Mitigation: 90-day rotate + size-based cap (10MB).

## Súvisiace

- Sprint 2 B4 (implementácia)
- C5 out-of-scope rationale (IXON má vlastný cloud audit log — duplikujeme len on-robot)
