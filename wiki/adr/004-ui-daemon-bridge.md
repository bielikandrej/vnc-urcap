# ADR-004 — UI→daemon bridge cez `/var/lib/urcap-vnc/config` s group=polyscope

**Dátum:** 2026-04-17
**Status:** Accepted (plánované pre v2.1.0)
**Týka sa:** `VncInstallationNodeContribution.java`, `run-vnc.sh`, NEW `post-install.sh`

## Kontext

Polyscope Java proces (kde beží URCap installation UI) beží ako **user `polyscope`**, nie root. Daemon script `run-vnc.sh` však beží **ako root** (lebo potrebuje iptables, apt-get, /root/.vnc/passwd, x11vnc).

Problém: UI musí niečo napísať do location-u, ktorý daemon čita. Klasické IPC:

**Alternatívy zvažované:**
1. **A — Java píše do `/root/.urcap-vnc.conf`** — zlyhá, polyscope user nemá write. Tiché zlyhanie v UI (Exception do stderr, operátor nevie že Apply nič neurobil) → worst UX.
2. **B — sudo wrapper: polyscope user sudo-uje malý skript na zápis** — vyžaduje sudoers entry; komplikovaná ops, bezpečnostný risk (sudo policy maintenance).
3. **C — Unix socket, daemon listens, UI píše** — overkill, complex for single value bundle.
4. **D — Shared config adresár so group permissions** ✅ ZVOLENÉ
5. **E — Polyscope DataModel → systemd-notify** — žiadne direct IPC, Polyscope ma len start/stop pre DaemonContribution. DataModel je Polyscope-internal, daemon script ju nevidí.

## Rozhodnutie

**Voľba D:**

1. **Adresár:** `/var/lib/urcap-vnc/`
   - Vytvára `post-install.sh` pri URCap install (beží ako root)
   - `chown root:polyscope`
   - `chmod 770`
2. **Config file:** `/var/lib/urcap-vnc/config`
   - Vytvára `post-install.sh` ako prázdny
   - `chown root:polyscope`, `chmod 660`
3. **Java write (polyscope user):**
   ```java
   // Atomic write pattern
   Path tmp = Files.createTempFile(
     Paths.get("/var/lib/urcap-vnc"), "config-", ".tmp");
   Files.writeString(tmp, content);
   Files.move(tmp, Paths.get("/var/lib/urcap-vnc/config"),
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
   ```
   Atomic move v rámci jedného filesystému = POSIX `rename(2)` = žiadny partial write race voči daemonu.
4. **Daemon read (root):**
   ```bash
   CONF="/var/lib/urcap-vnc/config"
   [[ -f "$CONF" ]] && source "$CONF"
   ```

**Prečo `/var/lib/` a nie `/opt/`, `/etc/`:**
- `/var/lib/` je POSIX-štandard pre "variable state data maintained by programs" — perfektný match
- `/etc/` je pre administrator-owned config (SSH-edited), nie pre UI-edited
- `/opt/urcap-vnc/` rezervujeme pre package bin-aries (scripts, certs)

## Dôsledky

**Pozitívne:**
- Clean permission model — root vlastní, polyscope môže editovať, ostatní nič
- Atomic write = daemon nikdy nevidí half-written config
- Žiadne `sudo` entries v `/etc/sudoers.d/` (menej surface pre mis-config)
- `post-install.sh` je single-shot idempotent — ľahký audit

**Negatívne:**
- `post-install.sh` pri URCap inštalácii **musí** bežať ako root. URCap API 1.3.0 to priamo nepodporuje — musíme zvoliť:
  - (a) post-install.sh spustiť z Bundle-Activator cez `Runtime.exec("/bin/sh /path/to/post-install.sh")` — beží ako polyscope user → zlyhá chown
  - (b) Dokumentovať manuálny krok "SSH jednorázovo po inštalácii: `bash /root/.urcaps/<bundle>/post-install.sh`" — ops friction
  - (c) **Chosen:** post-install.sh samo-bootstrap cez `run-vnc.sh` pri prvom štarte (daemon beží ako root, takže môže setup-nuť adresár). Prvé Apply z UI potom prejde.
  
  **Kick-off flow:**
  ```
  1. User installs URCap → Polyscope restart
  2. User goes to Installation → VNC URCap tab (UI loads)
  3. User clicks "Start daemon" → run-vnc.sh (root) self-bootstraps /var/lib/urcap-vnc/
  4. User edits fields, clicks Apply → Java (polyscope) writes /var/lib/urcap-vnc/config
  5. Daemon restart cez UI → reads new config
  ```

- Starting order chicken-and-egg: najprv musí byť daemon started aspoň raz ručne (s defaults) aby `/var/lib/urcap-vnc/` vznikol. Pri zlyhaní Apply (písanie do ešte-neexistujúceho adresára) UI zobrazí: *"Bootstrap needed — klikni Start daemon najprv."*

- Alternatívne: pri `VncInstallationNodeService.onInstallation()` Java spawnuje `run-vnc.sh --bootstrap-only` čo sa vykoná ako root cez DaemonContribution wrapper. Ale to má vlastné risks (Polyscope lifecycle assumptions) — ponecháme dev-time.

## Súvisiace

- Gotcha G1 (polyscope user nie je root)
- Sprint 1 A1 (implementácia)
- ADR-003 (prečo opúšťame `/root/.urcap-vnc.conf`)
