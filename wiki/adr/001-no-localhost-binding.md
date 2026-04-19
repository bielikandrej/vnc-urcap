# ADR-001 — Žiadny `-localhost` binding, namiesto toho 0.0.0.0 + iptables whitelist

**Dátum:** 2026-04-16
**Status:** Accepted (v2.0.1)
**Týka sa:** `run-vnc.sh`

## Kontext

v2.0.0 použil `x11vnc -localhost`, čo bind-uje len na `127.0.0.1:5900`. Logika: "IXON routuje cez loopback tunnel, netreba by byť reachable z LAN-u".

**Problém:** IXrouter v IXON Cloud architektúre routuje traffic z cloud brány do local LAN a prichádza na port 5900 **z IXrouter IP (192.168.0.100)**, nie z loopback. Preto `-localhost` filtruje *všetko* okrem lokálnych procesov na samom robote → IXON connection failne.

**Alternatívy zvažované:**
1. **A — ponechať `-localhost`, spraviť SSH tunnel z IXrouter** — komplikovaný, vyžaduje SSH key deployment na IXrouter, nie naša ops doména
2. **B — bind 0.0.0.0, iptables whitelist iba IXrouter** ✅ ZVOLENÉ
3. **C — bind 0.0.0.0 bez whitelist-u, spoliehať sa na VNC heslo** — heslo je 8-char max, nestačí proti brute force z internej LAN

## Rozhodnutie

**Voľba B:** `x11vnc` bind-uje `0.0.0.0:5900`. Kernel-level iptables INPUT chain:
- ACCEPT loopback (pre `vnc-test.sh` localhost probe)
- ACCEPT source IP = `$IXROUTER_IP`
- DROP everything else na porte 5900

Heslo VNC je druhá obrana. iptables whitelist je prvá.

## Dôsledky

**Pozitívne:**
- IXON Cloud funguje out-of-box
- Zákazník môže robiť aj lokálny test priamo z workstation na LAN-e — stačí pridať IP cez "temp allowlist" (v2.2.0)
- Security posture lepšia než otvorený port (DROP default)

**Negatívne:**
- Závislosť na iptables persistence (UR e-Series nemá iptables-persistent → rules len in-memory, musí sa rebuild-ovať pri každom run-vnc.sh spawn-e)
- Ak IXrouter zmení IP (DHCP lease, network reconfigure), VNC prestane fungovať. Mitigation: per-robot override config v 2.0.2+

**Risk monitoring:** Pri každom health probe (A4) kontrolujeme že iptables má očakávané 3 rules. Ak nie → červený dot + log warning.

## Súvisiace

- ADR-002 (iptables whitelist rationale detail)
- Gotcha G9 (iptables-save / restore nefunguje, musíme rebuild každý štart)
