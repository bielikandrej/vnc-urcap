# 01 — Architecture

## High-level flow

```
┌──────────────────────┐         IXON Cloud (TLS+2FA)        ┌──────────────┐
│ Operátor             │  ───────────────────────────────▶   │  IXrouter    │
│ (IXON portal/Vision) │                                      │ 192.168.0.100│
└──────────────────────┘                                      └──────┬───────┘
                                                                     │ tcp/5900
                                                                     │ (povolené
                                                                     │  iba 100.100)
                                                                     ▼
                                                              ┌──────────────┐
                                                              │   UR10e      │
                                                              │  Polyscope 5 │
                                                              │              │
                                                              │  [iptables]  │
                                                              │  INPUT chain │
                                                              │              │
                                                              │  [x11vnc]    │
                                                              │  DISPLAY :0  │
                                                              │  port 5900   │
                                                              └──────────────┘
```

## Software stack

```
                    Polyscope (UR GUI)
                          │
                  ┌───────┴────────┐
                  │   URCap v2     │   OSGi bundle: sk.stimba.urcap.vnc-server
                  └───────┬────────┘
                          │ Bundle-Activator
                          ▼
    ┌──────────────────────────────────────────────┐
    │  Activator.java                              │
    │    - registers VncDaemonService              │
    │    - registers VncInstallationNodeService    │
    └──────────────────────────────────────────────┘
           │                              │
           ▼                              ▼
┌──────────────────────┐    ┌─────────────────────────────────┐
│ VncDaemonService     │    │ VncInstallationNodeService      │
│   - installResource  │    │   - factory for                 │
│   - getExecutable    │    │     VncInstallationNode-        │
│     = run-vnc.sh     │    │     Contribution                │
└──────────┬───────────┘    └───────────┬─────────────────────┘
           │                            │
           │ DaemonContribution         │
           │   start/stop/getState      │
           │                            │
           ▼                            ▼
    (Polyscope spawns     ┌────────────────────────────────┐
     run-vnc.sh as root)  │ VncInstallationNodeContribution│
           │              │   - DataModel read/write       │
           │              │   - writeConfigFile() (2.1.0+) │
           │              │   - daemon start/stop          │
           │              └───────────┬────────────────────┘
           │                          │
           │                          ▼
           │              ┌────────────────────────────────┐
           │              │ VncInstallationNodeView        │
           │              │ (Swing UI tab)                 │
           │              │   - inputs: port/pwd/IP/...    │
           │              │   - health indicators          │
           │              │   - log viewer (2.2.0+)        │
           │              └────────────────────────────────┘
           ▼
    ┌──────────────────────────────────────┐
    │ run-vnc.sh (bash, root)              │
    │   - source /var/lib/urcap-vnc/config │
    │   - tripwire easybot hash            │
    │   - apt-get install x11vnc           │
    │   - build iptables whitelist         │
    │   - exec x11vnc                      │
    └──────────────────────────────────────┘
                │
                ▼
         (x11vnc running, port 5900, DISPLAY :0)
```

## Kde je čo uložené

| Súbor/adresár | Obsah | Kto zapisuje | Kto číta |
|---|---|---|---|
| `$URCAPS_HOME/.urcaps/*.urcap` | OSGi bundle | admin (SSH/USB) | Polyscope pri boot-e |
| `/root/.vnc/passwd` | hashované VNC heslo (DES) | run-vnc.sh pri prvom štarte | x11vnc pri connect |
| `/var/lib/urcap-vnc/config` | `KEY=VALUE` pre daemon (2.1.0+) | VncInstallationNodeContribution (Java, polyscope user, chmod 660, group=polyscope) | run-vnc.sh + stop-vnc.sh |
| `/root/.urcap-vnc.conf` | legacy override (2.0.2) | root manuálne cez SSH | run-vnc.sh + stop-vnc.sh |
| `/tmp/urcap-vnc.lock` | PID daemonu | run-vnc.sh | run-vnc.sh (double-start check), stop-vnc.sh |
| `/var/log/urcap-vnc.log` | x11vnc + daemon stdout | run-vnc.sh (logger + logappend) | admin, log viewer UI (2.2.0+) |
| `/var/log/urcap-vnc-audit.log` | connection audit JSON-lines | x11vnc `-accept`/`-gone` hook (2.2.0+) | compliance audit |
| `/var/log/urcap-vnc-temp-allowlist` | aktívne dočasné iptables pravidlá s TTL | VncInstallationNodeContribution + temp-allowlist-sweeper.sh | sweeper cron |

## Kontrolné body

- **URCap bundle** musí obsahovať shell skripty pri `sk/stimba/urcap/vnc/impl/daemon/`
  (package-aligned path, inak `installResource` nenájde scripts)
- **Bundle-Version** v MANIFEST.MF MUSÍ match `pom.xml` version, inak Polyscope URCap cache
  môže reagovať divno
- **DaemonContribution.start() je idempotentné** — ale len ak je lock file cleanly handled
- **Java proces Polyscope** beží ako user `polyscope` (nie root). Pre write do `/root/` potrebujeme
  workaround (pozri ADR 004)
