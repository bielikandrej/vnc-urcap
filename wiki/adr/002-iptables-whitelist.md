# ADR-002 — iptables source-IP whitelist ako primárna access control

**Dátum:** 2026-04-16
**Status:** Accepted (v2.0.1)
**Týka sa:** `run-vnc.sh`, `stop-vnc.sh`

## Kontext

VNC protokol RFB má password-based auth s **8-znakovým** truncation (viď Gotcha G2). Pri 8 znakoch a charset ~72 (alfanum + symboly) je key space ~70 bit, ale v praxi viewers akceptujú weak passwords, a user-chosen passwords rarely dosahujú plnú entropiu.

Bez ďalšej vrstvy je VNC port 5900 triviálny cieľ pre brute-force na internal LAN.

**Alternatívy zvažované:**
1. **A — spoliehať sa len na VNC heslo** — insufficient (viď vyššie)
2. **B — SSH tunel s kľúč-based auth** — operácia: zákazník musí mať SSH client, distribuovať kľúče, DevOps hlavolam
3. **C — kernel-level iptables source-IP whitelist** ✅ ZVOLENÉ
4. **D — VPN (WireGuard)** — duplikuje IXON Cloud funkcionalitu, extra tunel

## Rozhodnutie

**Voľba C:** iptables INPUT chain na porte 5900:
```
iptables -I INPUT -i lo -p tcp --dport 5900 -j ACCEPT                    # loopback
iptables -I INPUT -p tcp --dport 5900 -s $IXROUTER_IP -j ACCEPT          # IXrouter
iptables -A INPUT -p tcp --dport 5900 -j DROP                             # default drop
```

Poradie `-I INPUT -i lo` ako prvé, `-I INPUT -s $IXROUTER_IP` ako druhé, `-A INPUT ... DROP` na koniec.

Defense in depth:
- **L3 (IP):** iptables — kto sa vôbec dostane k TCP handshake
- **L7 (RFB):** VNC password — kto sa autentizuje na aplikačnej úrovni
- **L7 (TLS, v3.0.0):** session key + cert fingerprint — kto má kryptografický prístup

## Dôsledky

**Pozitívne:**
- Portscanner z internej LAN vidí port 5900 ako `filtered` (DROP bez reject response — port je "invisible")
- Brute-force útok z LAN-u sa nikdy nedostane k RFB auth handshake
- Komplement k IXON Cloud (TLS + 2FA ako outer tunnel) — aj keby IXON bol bypass-nutý, ostáva iptables bariéra

**Negatívne:**
- Každá zmena IXROUTER_IP vyžaduje daemon reštart (stop-vnc.sh najprv musí odstrániť staré rules, inak zostanú "orphan" allows)
- Debug ťažší — ak niečo nejde, nie je hneď zrejmé že iptables drop-ne
- Temp allowlist (v2.2.0) pridáva komplexitu — iptables rules + expiry sweeper cron

**Monitoring:** Health panel A4 kontroluje `iptables -L INPUT -n | grep 5900` — očakávame presne 3 matches. Deviacia → warning.

## Súvisiace

- ADR-001 (prečo binding 0.0.0.0)
- Sprint 2 B3 (temp allowlist s TTL)
- Gotcha G9 (iptables nepersistuje cez reboot)
