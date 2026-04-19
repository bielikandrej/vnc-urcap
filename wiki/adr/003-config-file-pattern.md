# ADR-003 — `/root/.urcap-vnc.conf` ako per-robot override bridge

**Dátum:** 2026-04-17
**Status:** Accepted (v2.0.2), Deprecated (v2.1.0+ presun na `/var/lib/urcap-vnc/config`)
**Týka sa:** `run-vnc.sh`, `stop-vnc.sh`

## Kontext

v2.0.1 zaviedla `IXROUTER_IP` ako hardcoded default (`192.168.0.10`) v `run-vnc.sh`. Pri fleet deployment sa ukázalo:
- STIMBA internal robots majú `192.168.0.100`
- Zákaznícke LAN-y môžu mať `192.168.1.100`, `10.0.0.50`, atď.

Rebuild URCap per-robot je ops antipattern. Potrebujeme runtime override bez opätovnej inštalácie.

**Alternatívy zvažované:**
1. **A — environment variables** — daemon sa spawne z Polyscope, env scope netriviálny
2. **B — config file na fixnom mieste** ✅ ZVOLENÉ (krátkodobo v `/root/`, dlhodobo v `/var/lib/`)
3. **C — Polyscope installation DataModel** — funguje, ale vyžaduje UI support (v2.1.0) — nestihli sme na 2.0.2
4. **D — DHCP-based discovery IXrouter IP** — zbytočne komplikované, hádzanie black-box

## Rozhodnutie (v2.0.2)

`/root/.urcap-vnc.conf` — shell-sourceable `KEY=VALUE` file. `run-vnc.sh` a `stop-vnc.sh` oba source-ujú cez `[[ -f ... ]] && source "$CONF_FILE"`.

Format:
```bash
# /root/.urcap-vnc.conf — per-robot override
IXROUTER_IP=192.168.0.100
# URCAP_VNC_REQUIRE_STRONG_PWD=1   # optional
```

Write-access: len root cez SSH. Zámerne — operátor v UI toto nemá meniť (nemá relevantné oprávnenie alebo knowledge).

## Evolúcia (v2.1.0+)

UI v 2.1.0 pridá IXROUTER_IP field → hodnota ide do `/var/lib/urcap-vnc/config` (písateľné polyscope userom, viď ADR-004). `run-vnc.sh` source-uje v poradí:

1. `/var/lib/urcap-vnc/config` (UI-owned, current authoritative)
2. `/root/.urcap-vnc.conf` (legacy, len ak UI file neexistuje)
3. Hardcoded defaults (192.168.0.100 atď.)

Legacy `.conf` zostáva podporovaný pre backward-compat, ale v README dokumentujeme že od 2.1.0 sa má nastaviť cez UI.

## Dôsledky

**Pozitívne:**
- 2.0.2 → 2.1.0 nemá breaking change (oba file-y stále source-nuté)
- Možnosť "lock-down" configu cez `chattr +i /root/.urcap-vnc.conf` pre zákazníkov kde netrebáme UI editability
- Simple shell pattern — bash source je dobre pochopiteľný

**Negatívne:**
- Dva configy = potenciál konflikt. Mitigation: UI (v2.1.0) zobrazí warning ak obe file-y existujú a sa líšia (tento health check je v A4)
- Shell source pattern má injection risk ak user napíše `IXROUTER_IP="x; rm -rf /"` do conf file. Mitigation: UI validácia regex pri write; SSH-level config = root už aj tak má full access, injection irelevantná

## Súvisiace

- ADR-004 (prečo migrujeme do `/var/lib/` — polyscope user permissions)
- Sprint 1 A1 (implementácia UI bridge)
