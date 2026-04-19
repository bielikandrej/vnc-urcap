# 04 — Gotchas (UR-specifické pasce)

Tieto veci *nie sú* v UR Developer docs napísané takto priamo — naučili sme sa ich cez 2.0.0 → 2.0.2 iterácie alebo z forum postov. Ak riešiš divný bug, začni tu.

## G1. Polyscope Java nebeží ako root

**Symptóm:** UI vyzerá ako že je všetko uložené, ale žiadna zmena sa neprejaví na daemone.

**Príčina:** Polyscope Java proces beží ako user `polyscope`. Každý `File.write` na `/root/...` tíško zlyhá (Swing UI exception vypustí do stderr ktorý nikto necíta).

**Riešenie (od 2.1.0):**
- config file v `/var/lib/urcap-vnc/config`
- adresár vytvára `post-install.sh` (root) s `chown root:polyscope` + `chmod 770`
- súbor s `chmod 660`
- Java zapisuje atomicky: `Files.write(tmp)` → `Files.move(tmp, final, ATOMIC_MOVE)`
- `run-vnc.sh` (root) z neho source-uje

Pozri `adr/004-ui-daemon-bridge.md`.

## G2. RFB protokol tichnutím skracuje heslo na 8 znakov

**Symptóm:** Dám heslo `SuperStrong2026!`, viewer akceptuje prvých 8 znakov, tzn. `SuperStr`. Ostatných 9 znakov sa ignoruje bez chyby.

**Príčina:** RFB 3.x (stále najčastejší v prod VNC viewers) používa DES fixed-length 8 byte key. `x11vnc -storepasswd` zobie prvých 8.

**Dopad:**
- "bezpečnostná" password-strength UI 2026 indicator musí **merať prvých 8 znakov**, nie celý string
- "24-char random" = false security ak použite cez natívny VNC viewer
- Pre skutočne silné heslo potrebujeme TLS wrapper + klient-side password (ale tiež len 8 znakov UNLESS používame VNC Auth v7 alebo VeNCrypt)

**Riešenie v 2.1.0:**
- Password strength live indicator (B5) counts entropy **in first 8 chars only**
- Warning text: *"VNC protokol RFB 3.x obmedzuje heslo na prvých 8 znakov. Použi silné kombo písmená/číslice/symboly v rámci týchto 8."*

**Riešenie v 3.0.0:**
- TLS (C1) vypne tento problém (x11vnc -ssl + client cert alebo session key)

## G3. DataModel lifecycle — čo keď user urobí "Discard"

**Symptóm:** Nastavím IXROUTER_IP, Apply, všetko funguje. Potom robím niečo iné, kliknem Discard Changes → zmeny v installation sa revert-nú → ale config file stále obsahuje starý IXROUTER_IP.

**Príčina:** DataModel má commit/rollback pattern na strane Polyscope. File write je side effect mimo DataModel — Polyscope o ňom nevie.

**Riešenie:**
- Write-to-file len pri explicit "Apply" kliknutí (nie onChange listener)
- Pri load (openView) porovnáme DataModel vs config file — ak sa nezhodujú, zobrazíme banner "Config file je out of sync s installation, klikni Apply na fixnutie"
- `closeView()` nerobí write — len uloží do DataModel (ktorý robí Polyscope commit/rollback sám)

## G4. DaemonContribution idempotency vs lock file

**Symptóm:** UI zobrazuje `state = RUNNING`, ale nic nepočúva na 5900. Alebo: 2 procesy x11vnc bežia, druhý zlyhal s "Address in use", ale lock file ukazuje na mŕtvy PID.

**Príčina:** Polyscope `DaemonContribution.start()` je designed ako idempotentná, ale naše `run-vnc.sh` staršia verzia robila `if [[ -f lockfile ]]; exit`. To znamenalo že po stale lock file daemon nikdy nerestartoval.

**Riešenie (od 2.0.1):**
```bash
if [[ -f "$LOCKFILE" ]]; then
    pid="$(cat "$LOCKFILE")"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
        exit 0  # skutočne beží
    fi
    rm -f "$LOCKFILE"  # stale, premazať
fi
```

## G5. USB mount permissions

**Symptóm:** `.urcap` file na USB, Polyscope ho vidí, ale pri Install hovorí "permission denied" alebo "cannot read file".

**Príčina:** USB niekedy mount-nutý s `noexec,nosuid,uid=polyscope,gid=polyscope`. Ak je `.urcap` čítateľný len pre `root`, Polyscope to nevidí.

**Riešenie:**
- Na USB vlastnej workstation pred scp/cp: `chmod 644 stimba-vnc-server-*.urcap`
- FAT32/exFAT nemá permission bity — no-op, funguje
- ext4 USB — `chmod 644` treba

## G6. Polyscope URCap cache vs Bundle-Version

**Symptóm:** Nainštalujem novú verziu `stimba-vnc-server-2.1.0.urcap` — Polyscope reštartuje, ale na UI je stále v2.0.2 chovanie (staré polia).

**Príčina:** Polyscope cache-uje OSGi bundle podľa symbolic name + version. Ak `Bundle-Version` v MANIFEST.MF sa nezhoduje s `pom.xml` version, cache si myslí že je to "rovnaký bundle" a nevymení resources.

**Riešenie:**
- Pred každým buildom overiť `grep Bundle-Version META-INF/MANIFEST.MF == pom.xml version`
- V CI (Docker build) pridať assertion
- Emergency fix na robot: `rm -rf /root/.urcaps-cache/` (alebo podobne, exact path závisí od Polyscope 5.x.y)

## G7. `x11vnc -storepasswd` počas neinteraktívneho boot-u

**Symptóm:** Pri prvom spustení daemona po reboote sa password file nevytvorí, x11vnc spadne s "no auth file".

**Príčina:** Starý `x11vnc -storepasswd PASSWORD FILE` expect-uje TTY. Ak beží cez systemd spawned from Polyscope, niekedy sa to správa divne.

**Riešenie (od 2.0.1):**
```bash
echo -n "$PASSWORD" | x11vnc -storepasswd /root/.vnc/passwd
# namiesto:
# x11vnc -storepasswd "$PASSWORD" /root/.vnc/passwd
```

Rozdiel: stdin redirect eliminuje TTY dependency.

## G8. `apt-get install x11vnc` blokuje prvý štart

**Symptóm:** Prvý štart daemona trvá 30+ sekúnd, UI ukazuje "starting..." dlho.

**Príčina:** Na čistom UR10e x11vnc nie je. `run-vnc.sh` robí `apt-get install` čo trvá (hlavne ak DNS pomalý).

**Riešenie (plánované v 2.1.0):**
- `post-install.sh` pri URCap inštalácii spraví pre-install x11vnc tak, aby prvý start už bol instant
- Alternatíva (2.2.0): static-linked x11vnc binary v URCap resources (ale licenčná komplikácia — GPL)

## G9. IPT rules vs iptables-save/restore

**Symptóm:** Po reboote robota iptables rules zmiznú.

**Príčina:** UR e-Series (Debian base) nemá `iptables-persistent` package default. Naše rules sú in-memory only.

**Riešenie:**
- `run-vnc.sh` pri každom štarte pridá rules idempotentne (check `-C` pred `-I`)
- Polyscope autostart = VNC daemon štartuje pri boote = iptables sa rebuild-ne
- **NEINŠTALOVAŤ `iptables-persistent`** — by persisted aj stale rules z predchádzajúcej config

## G10. DISPLAY=:0 vs :1 vs nič

**Symptóm:** x11vnc štartuje, port 5900 počúva, ale klient vidí čiernu obrazovku alebo "no matching X server".

**Príčina:** UR e-Series beží Polyscope na `DISPLAY=:0`, ale niekedy Polyscope restart zmení na `:1`. x11vnc hardcoded na `:0` potom zobrazuje null display.

**Riešenie (od 2.0.1):**
```bash
DISPLAY_TO_USE="$(ps -eo pid,cmd | grep -oP ':\d+' | head -1)"
DISPLAY_TO_USE="${DISPLAY_TO_USE:-:0}"  # fallback
exec x11vnc -display "$DISPLAY_TO_USE" ...
```

## G11. Apache Felix Bundle-Activator scan

**Symptóm:** Bundle sa deployne, ale žiadne services sa neregistrujú. Polyscope log: "Activator not found".

**Príčina:** `Bundle-Activator` v MANIFEST.MF musí byť fully qualified class name **a** ten class musí byť na classpath-e bundle-u (t.j. v `sk/stimba/urcap/vnc/impl/Activator.class`).

**Riešenie:** maven-bundle-plugin `<instructions>` obsahuje `<Bundle-Activator>sk.stimba.urcap.vnc.impl.Activator</Bundle-Activator>`. V 2.0.0 chýbalo — Activator sa nenahrával. Odvtedy fixed.

## G12. `DataModel.get(key, default)` má rôzne generic bounds v rôznych API verziách

**Symptóm (v3.0.0 na PS 5.22 e-Series):**
```
java.lang.NoSuchMethodError:
  com.ur.urcap.api.domain.data.DataModel.get(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;
    @ VncInstallationNodeContribution.isAutostart():240
    @ VncInstallationNodeContribution.applyDesiredDaemonStatus():343
```

Bundle sa NÍZKY level naloadí (classloader prejde, OSGi wiring OK), ale prvý `model.get(KEY, DEFAULT)` volanie pukne.

**Príčina:**
- Skompilovali sme proti lokálnemu URCap API stubu, ktorý deklaroval signatúru `<T> T get(String, T defaultValue)` — unbounded generic `T`, čo v bytecode-e eraseuje na `get(String, Object) → Object`.
- Reálny `DataModel.get` v API provided PS 5.22 (API ~1.17) má bound `<T extends Serializable>`, eraseuje na `get(String, Serializable) → Serializable`.
- Descriptor v bytecode ≠ descriptor v reálnej class = NoSuchMethodError pri prvom volaní, NIE pri load.

**Dôsledok:** Akýkoľvek rebuild ktorý použije typed-default overload má toto riziko. Od API 1.3.0 po 1.18.x UR menil bound 2× (cudzí JAR stub môže reflektovať ktorúkoľvek z nich).

**Riešenie (od v3.0.1):** Vyhnúť sa typed-default overloadu úplne. Všetky getters prepísané na `isSet` + primitívny `Object get(String)` + manuálny cast:
```java
// PRED (zraniteľné):
public boolean isAutostart() {
    return model.get(KEY_AUTOSTART, DEFAULT_AUTOSTART);
}

// PO (stable across API 1.0 → 1.18.x):
public boolean isAutostart() {
    return model.isSet(KEY_AUTOSTART)
        ? (Boolean) model.get(KEY_AUTOSTART)
        : DEFAULT_AUTOSTART;
}
```

`Object get(String)` je v `DataModel` od API 1.0, deterministický descriptor, žiadne generické erasure riziko.

**Prevention:** Pri rebuilde URCap projektov ktoré closure-ujú cez URCap API interfaces, stub by mal presne zrkadliť bound z cieľovej Polyscope API verzie. Ak nevieš aká API sa fakt používa na cieľovom roboti, použi len non-generické overload-y (`Object get(String)`).

**Dôkaz bugy:** `javap -s -p DataModel.class` na skutočnom PS 5.22 api JAR-e ukáže `(Ljava/lang/String;Ljava/io/Serializable;)Ljava/io/Serializable;`; náš stub mal `(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;`. Dva rôzne descriptors = dve rôzne JVM metódy.

**Layer 0 (v3.0.0 hotfix1):** `javax.swing.border` + `javax.swing.text` chýbali v Import-Package. To je iná vrstva bugu (NoClassDefFoundError pri load, nie NoSuchMethodError pri invoke).

**Layer 1 (v3.0.1):** Prvý attempt fixu — prepis `model.get(k, d)` → `isSet ? (Object) get(k) : d`. **NEÚSPECH** — `Object get(String)` v reálnom API neexistuje. Iba primitive-typed overloads sú v interface-i.

**Layer 2 (v3.0.2):** Skutočný fix — prepis na `model.get(KEY, DEFAULT)` s primitive-typed overloads: `int get(String, int)`, `boolean get(String, boolean)`, `String get(String, String)`. Tieto sú priamo v real `DataModel` interface od API 1.3.0 cez 1.16.0+. Descriptor-matched → no NoSuchMethodError.

**Dôvod prečo máme 3 verzie G12:** stub drift — hand-rolled stub deklaroval (A) generic `<T> T get(String, T)`, (B) `Object get(String)`, (C) žiadnu z primitive overloads. Reálny API má (C) len. Decompilácia stimba-vnc-server-2.0.0 (postavené Maven-bundle-plugin proti *real* API jar) to potvrdila definitívne.

---

## G13. InstallationNodeContribution.isDefined() — abstract method v real interface

**Symptóm (v3.0.2 na PS 5.22 e-Series):**
```
java.lang.AbstractMethodError: Receiver class
  sk.stimba.urcap.vnc.impl.VncInstallationNodeContribution
  does not define or inherit an implementation of the resolved method
  'abstract boolean isDefined()' of interface
  com.ur.urcap.api.contribution.installation.InstallationNodeContribution.
    @ com.ur.urcap.engine.installation.InstallationNodePanel.open(...)
```

Inštalácia panela otvorí, ale prvá `open()` cesta volá `isDefined()` ktorá abstract = AbstractMethodError.

**Príčina:** Náš reconstructed `InstallationNodeContribution.java` stub deklaroval iba 4 metódy (`openView`, `closeView`, `generateScript`, ... + lifecycle). Reálny interface v API 1.3.0+ obsahuje **aj** `isDefined()` (signalizuje Polyscope či je node "sufficiently configured", podobne ako `isValid()`). Keďže stub ju nemal, naša impl ju neoverride-la, a JVM nenašla žiadnu konkrétnu implementáciu → AbstractMethodError.

**Riešenie (od v3.0.3):**
```java
// V VncInstallationNodeContribution.java, bez @Override
// (náš stub ju stále neobsahuje, ale real interface áno — JVM dispatch funguje):
public boolean isDefined() {
    return true;  // nikdy neblokujeme "Installation je sufficiently configured"
}
```

`@Override` zámerne CHÝBA — pridaním by ECJ ERROR-oval ("method does not override"). Bez @Override sa to compile-uje proti stubu, ale runtime dispatch nájde metódu s descriptorom `()Z` ktorý real interface očakáva.

**Prevention:** Embedovať reálny `urcap-api-1.3.0.jar` do repa a buildnúť voči nemu (task #15). Potom by ECJ vyhlásila error o nepokrytom abstract method PRED build-om.

---

## G14. DaemonService interface má iba 2 metódy (init + getExecutable)

**Zatiaľ sme nedostali runtime error** (v3.0.0 → 3.0.4 všetky boot-ovali s DaemonService bez problémov), ale code review bytecodu odhalil **stub drift**.

**Presný stav:**
- Reálny `DaemonService` (Javadoc API 1.3.0 cez 1.16.0, overené krížom na oficiálnych release notes): **2 metódy** — `void init(DaemonContribution)` + `URL getExecutable()`.
- Náš stub: **3 metódy** — navyše `DaemonContribution getDaemon()`.
- Real source of `getDaemon()`: je to **concrete-class helper** vo FZI ToolComm Forwarder reference URCap (`de.fzi.urcap.toolcomm.daemon.ToolCommDaemonService` má public `getDaemon()` ale ako *triedny* helper, nie interface method). Naše stub skopíroval zlú úroveň.

**Prečo zatiaľ žiadny crash:**
- V našom `VncInstallationNodeContribution.java` voláme iba cez concrete typing — `VncDaemonService daemon = ...; daemon.getDaemon().start();`. JVM dispatch ide proti concrete class (ktorá má metódu), nie proti interface.
- Ak by niekto refactorel na interface typing (`DaemonService s = ...; s.getDaemon()...`), dostal by **AbstractMethodError** alebo kompil error podľa cesty.

**Prevention od Sprint 3.5:**
1. `VncDaemonService.getDaemon()` **nesmie** mať `@Override` — aktuálne nemá. Ak niekto pri code review pridá — rollback.
2. Task #15 (embed real API jar) to zresetuje úplne.
3. **Task #18 DONE (2026-04-19)** — `build-with-docker/regress.sh` + `wiki/public-api-baseline.txt` blokuje CI na akúkoľvek zmenu public metódy pod `sk.stimba.*`. Testovaný round-trip proti 3.0.1, 3.0.0-hotfix1, 3.0.2, 3.0.3 a 3.0.4. Viď [`03-build-deploy.md §Regression gate`](./03-build-deploy.md#regression-gate--public-api-signature-diff).
