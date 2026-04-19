# ADR-006 — Health panel cez client-side polling, nie server-push

**Dátum:** 2026-04-17
**Status:** Accepted (plán pre v2.1.0)
**Týka sa:** `VncInstallationNodeView.java`, NEW `health-probe.sh`

## Kontext

Health panel (A4) musí ukazovať 5 stavov real-time:
1. Daemon running?
2. iptables rules correct?
3. Port 5900 listening?
4. DISPLAY :0 accessible?
5. IXrouter reachable (ping)?

**Otázka:** Ako sa UI dostane k týmto dátam?

**Alternatívy zvažované:**
1. **A — Server-side push:** daemon posiela JSON events na Unix socket / local HTTP endpoint, UI subscribe-uje
2. **B — Client-side polling:** UI timer každých N sekúnd spustí `health-probe.sh`, parse-uje JSON output
3. **C — Direct Java probes:** UI priamo volá `Socket.connect(localhost, 5900)`, `new File("/tmp/urcap-vnc.lock").exists()`, atď.

## Rozhodnutie

**Voľba B:** Client-side polling, Swing Timer, spawn shell probe.

**Implementácia:**

`health-probe.sh` (samostatný skript, testovateľný aj z SSH):
```bash
#!/bin/bash
# health-probe.sh — one-shot JSON output

daemon_state() {
  [[ -f /tmp/urcap-vnc.lock ]] || { echo fail; return; }
  local pid; pid="$(cat /tmp/urcap-vnc.lock)"
  kill -0 "$pid" 2>/dev/null && echo ok || echo fail
}

iptables_state() {
  local count; count="$(iptables -L INPUT -n | grep -c ':5900')"
  [[ "$count" -ge 3 ]] && echo ok || echo fail
}

port_state() {
  ss -tln 2>/dev/null | grep -q ':5900' && echo ok || echo fail
}

display_state() {
  xdpyinfo -display :0 >/dev/null 2>&1 && echo ok || echo fail
}

ixrouter_state() {
  local ip; ip="$(source /var/lib/urcap-vnc/config 2>/dev/null; echo "$IXROUTER_IP")"
  ip="${ip:-192.168.0.100}"
  ping -c 1 -W 2 "$ip" >/dev/null 2>&1 && echo ok || echo warning
}

printf '{"daemon":"%s","iptables":"%s","port":"%s","display":"%s","ixrouter":"%s"}\n' \
  "$(daemon_state)" "$(iptables_state)" "$(port_state)" "$(display_state)" "$(ixrouter_state)"
```

**Java integration:**
```java
private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
private final Timer refreshTimer = new Timer(5000, e -> pollHealthAsync());

private void pollHealthAsync() {
  scheduler.submit(() -> {
    try {
      Process p = new ProcessBuilder("/opt/urcap-vnc/health-probe.sh").start();
      String json = new String(p.getInputStream().readAllBytes(), UTF_8);
      p.waitFor(10, TimeUnit.SECONDS);
      HealthState state = HealthState.fromJson(json);
      SwingUtilities.invokeLater(() -> updateDotsUI(state));
    } catch (Exception ex) {
      log.warn("health probe failed", ex);
    }
  });
}
```

## Prečo nie A (server-push)?

- Vyžaduje daemon-side HTTP server alebo Unix socket listener — daemon = `x11vnc` hlavný proces, wrapping pridáva fragility
- x11vnc beží ako root, UI ako polyscope — socket permissions = extra friction
- Observability benefit "real-time" je iluzórny — health check-y s interval 5s sú dostatočné pre operátor UX

## Prečo nie C (direct Java probes)?

- **Portability:** `health-probe.sh` funguje aj z SSH (`ssh root@robot 'bash /opt/urcap-vnc/health-probe.sh'`) — useful pre remote diagnostics bez Polyscope
- **Testability:** shell script je priamo testable s `bats` alebo mock-unutými fixtures, Java method musíme unit test-ovať v Swing context
- **Consistency:** `diag-bundle.sh` (Sprint 2 B2) tiež volá health-probe — jeden source of truth

## Dôsledky

**Pozitívne:**
- Clean separation: shell = "what are the facts", Java = "how do I render those facts"
- Refresh interval je tunable v jednom mieste (Swing Timer 5000ms)
- Spawn overhead (~20ms) is fine for 5s interval
- Refresh *zanikne* keď user navigate-uje preč z Installation tab-u (Swing Timer auto-stops) — šetrí CPU

**Negatívne:**
- Spawn latency môže viditeľne "blip-núť" CPU každých 5s — na embedded UR controller toto treba overiť v prod (ale x11vnc samotný je väčší hit)
- JSON parsing ručné bez dependency — musíme overiť že shell nevypľuje null bytes / weird escape-y. Mitigation: explicit `printf`, fixed set hodnôt `ok|warning|fail`.
- Nezachytí rapid-fire state changes (daemon crash + restart medzi dvoma probe-mi). Akceptovateľné trade-off.

## Súvisiace

- Sprint 1 A4 (implementácia)
- Sprint 2 B2 (diag-bundle.sh vola health-probe.sh → jeden zdroj)
