# ADR-008 — TLS cez `x11vnc -ssl SAVE` (vs. stunnel, vs. OpenVPN wrapper)

**Status:** Accepted — 2026-04-17 (v3.0.0)
**Rozhodol:** Andrej Bielik + Claude
**Kontext:** Sprint 3 / feature C1 — wire-level encryption pre VNC traffic medzi IXON cloudom a Polyscope 5.

---

## Problém

V2.2.0 beží VNC plaintext na `0.0.0.0:5900`. Kľúčové stroky (vrátane hesiel) idú po drôte bez šifrovania. IXON cloud tunel je TLS-sealed medzi klientom a IXrouterom, ale **hop z IXroutera k robotovi je LAN plaintext** — pokiaľ niekto nabúra LAN, môže sniffovať celý VNC stream.

Požiadavka: e-Series URCap musí ponúknuť end-to-end TLS tak, že aj LAN hop je šifrovaný. Self-signed cert akceptovateľný, pretože IXON cloud klient môže pin-núť fingerprint pri prvom pripojení.

## Zvažované možnosti

### Option A — `x11vnc -ssl SAVE` (built-in OpenSSL wrap)

x11vnc má natívnu TLS podporu cez libssl. Pri `-ssl SAVE` flagu si daemon sám vygeneruje (ak chýba) self-signed cert do `~/.vnc/certs/server.pem` a obaluje celý RFB stream TLS-om.

**Plus:**
- **Žiadna nová závislosť.** x11vnc je už v image, openssl takisto (pre systemd/dropbear).
- **Single-process deployment** — nemusíme spravovať druhý daemon (stunnel), žiadne race podmienky na porte.
- **Kompatibilný s RealVNC Viewer**, TigerVNC, noVNC (cez websockify) — všetky moderné klientmi podporované od roku ~2015.
- **Fingerprint pinning podporovaný** (SHA-256) — presne to, čo IXON portal potrebuje zobraziť operátorovi.
- **Rovnaký port 5900** — iptables whitelist zostáva beze zmeny, žiadny NAT rebinding.

**Mínus:**
- TightVNC 2.7.x (a staršie Windows verzie bez SChannel fixu) nepodporujú SSL-tunneled RFB. Operátori musia použiť RealVNC alebo TigerVNC.
- x11vnc OpenSSL wrapper je "good enough" crypto (AES-256 CBC), nie state-of-the-art TLS 1.3 handshake. Postačuje pre LAN/IXON tunel, ale nie by sme to chceli pre internet-direct exposed endpoint (ktorý aj tak robiť nesmieme — ADR-002).

### Option B — `stunnel` wrapper pred x11vnc

Klasický pattern: `stunnel` počúva na 5900 s TLS, backe-nduje decrypted stream na `localhost:5901` kde počúva x11vnc iba na loopbacku.

**Plus:**
- Dospelejšia TLS knihovňa (stunnel 5.x vie TLS 1.3, OCSP stapling, mTLS).
- Cert management oddelený od VNC procesu — čisto separated concerns.

**Mínus:**
- **Druhý daemon na spravovanie.** Nový systemd unit, druhá PID file, dva log súbory, dvojakú health probe semantika. Náš model je "URCap = jeden shell supervised process" (ADR-004) — rozbíjať to pre TLS je neúmerne.
- **Dva porty v iptables whitelist** (5900 public, 5901 loopback). Viac plochy na omyl.
- stunnel musí byť v image — UR e-Series base image ho **nemá** a `apt-get install stunnel` vyžaduje apt cache refresh cez IXrouter, čo nie je airgap-friendly.
- Kombinácia `systemctl restart` na dva daemons musí byť sekvenčná (stunnel depends-on x11vnc), čo komplikuje our `runDaemon()` lifecycle.

### Option C — OpenVPN / IPsec tunel medzi robotom a IXroutera

Namiesto TLS na RFB vrstve, postavíme L3 tunel a necháme RFB plaintext — ale tunelovaný.

**Plus:**
- Nezávislé na VNC klientovi (všetci klienti vidia plaintext, krypto je on-the-wire).
- Rovnaký mechanizmus by pomohol aj Modbus/EtherNet-IP traffic.

**Mínus:**
- **Massive overkill pre URCap** — toto je infraštruktúrna vec, nemá byť v URCap scope.
- Vyžaduje kernel modules (OpenVPN tun/tap alebo IPsec XFRM), ktoré na Polyscope 5 base image nemusia byť povolené (robot kernel je custom UR build).
- IXON Cloud už ponúka svoj vlastný L2/L3 tunel cez IXrouter. Robiť druhý paralelný tunel je redundantné.
- Cert management pre VPN je ťažší než RFB TLS (CA hierarchy, OCSP, revocation list).

### Option D — Žiadne TLS, spoľahnúť sa na IXON cloud tunel

Nespraviť nič — argument: "IXON to už rieši medzi cloudom a IXrouterom, LAN je trusted".

**Mínus:**
- **Nie je pravda** — klasické LAN-side útoky (ARP spoof, switch mirror port, compromised IOT device v rovnakej VLANe) zachytia VNC stream.
- Compliance (ISO 27001 A.13.1, NIS2 čl. 21) výslovne požaduje enkrypciu v pohybe **aj na internej sieti** pre zariadenia s PII/industrial process data.
- Operátori jednotlivých zákazníkov sa aj pýtajú — "je to šifrované?" — a "spolieham sa na IXON" nie je presvedčivá odpoveď v RFI.

---

## Rozhodnutie

**Vybrali sme Option A — `x11vnc -ssl SAVE`.**

### Dôvody

1. **Žiadna nová runtime závislosť** — kritické pre airgap deployment (niektorí customer-i nemajú internet na robote, apt-get je blocknutý).
2. **Jednoduchý lifecycle** — stále jeden daemon, jeden port, jedna health probe. Rešpektuje ADR-004 ("UI/daemon bridge sa bavi s jedným shell procesom").
3. **Fingerprint pinning workflow** — `tls-bootstrap.sh` generuje SHA-256 hash do `/root/.vnc/certs/fingerprint.txt`. UI má "Zobraziť fingerprint" button, ktorý ukáže operátorovi full hex string s RealVNC/TigerVNC inštrukciou ako ho paste-núť do client trust-store na prvom pripojení. Budúce pripojenia zlyhajú keď sa cert zmení (tripwire).
4. **Kompatibilita s moderným viewer stackom** — RealVNC 7.x, TigerVNC 1.13+, noVNC cez websockify. TightVNC 2.7 downgrade na plaintext je akceptovateľný (UI má `TLS_ENABLED=0` opt-out s červeným banner-om, čo logujeme do audit logu).
5. **Cost of reversal je nízky** — pokiaľ v budúcnosti budeme potrebovať TLS 1.3, môžeme prepnúť na Option B (stunnel) bez zmeny API voči UI — iba `tls-bootstrap.sh` + `run-vnc.sh` sa prepíše.

## Implementačné detaily

- **`/root/.vnc/certs/server.pem`** — self-signed cert + key v jednom súbore (x11vnc konvencia). chmod 600, dir 700.
- **`/root/.vnc/certs/fingerprint.txt`** — prvý riadok je `SHA256 Fingerprint=XX:XX:...:XX` z `openssl x509 -fingerprint -sha256`. UI extractuje prvých 47 znakov pre inline preview, full pre dialog.
- **Cert regenerácia** — idempotent bootstrap: ak `server.pem` existuje, no-op. Ak operátor chce regen, musí manuálne `rm -rf /root/.vnc/certs` + restart daemon.
- **`-subj "/CN=stimba-urcap-vnc-$(hostname)"`** — CN odráža hostname robota, ľahko identifikovateľné v trust store klienta.
- **`-days 3650`** — 10-ročný cert, lebo fleet-wide cert rotation na 700 robotoch je ops-intenzívna operácia. Pri compromise sa proceduje manuálne.
- **Eager bootstrap v `post-install.sh`** — cert sa vygeneruje pri inštalácii URCapu, nie pri prvom `run-vnc.sh`. Dôvod: UI "Zobraziť fingerprint" button musí fungovať hneď po otvorení Polyscope inštalačného node-u, ešte pred prvým štartom daemona.

## Kompatibilita

| VNC Client | TLS support | Pozn. |
|---|---|---|
| RealVNC Viewer 7.x | ✅ | "Use encryption: Prefer on" + paste fingerprint pri prvom pripojení |
| TigerVNC 1.13+ | ✅ | `-SecurityTypes VeNCrypt,TLSVnc` |
| TightVNC 2.7.x Windows | ❌ | Needs plaintext fallback (`TLS_ENABLED=0`) |
| noVNC (web) | ✅ cez websockify | websockify s `--cert=server.pem --key=server.pem` |
| IXON Cloud Remote Access | ✅ | IXON portal pinne fingerprint pri enrolmente robota |

## Dôsledky

- **Pozitívne:** End-to-end TLS bez pridania ďalšieho daemon procesu. Fingerprint pinning flow je transparentný operátorovi. Lifecycle x11vnc procesu sa nemení.
- **Negatívne:** Starí TightVNC klienti (Windows XP-era) musia downgradovať. Self-signed cert produces "untrusted" warning v klientovi, kým sa fingerprint pinne (UX friction pri prvom pripojení).
- **Neutrálne:** 3650-dňový cert znamená, že v roku 2036 bude tento ADR treba reopen-núť. Do tej doby máme plán C (switch na stunnel s proper CA chain, napojiť na Let's Encrypt cez Certbot DNS-01 proxy cez IXrouter).

## Referencie

- x11vnc `-ssl SAVE` dokumentácia: https://github.com/LibVNC/x11vnc/blob/master/x11vnc/ssltools.c
- `run-vnc.sh` — TLS fork logika
- `tls-bootstrap.sh` — cert gen + fingerprint extraction
- `post-install.sh` — eager bootstrap invocation
- ADR-002 — iptables whitelist (komplementárny layer k TLS)
- ADR-004 — UI ↔ daemon bridge (prečo single-process lifecycle)
