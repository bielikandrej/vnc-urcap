# ADR-005 — easybot tripwire s `URCAP_VNC_REQUIRE_STRONG_PWD` toggle

**Dátum:** 2026-04-17
**Status:** Accepted (v2.0.1 implementácia, v2.1.0 UI exposure)
**Týka sa:** `run-vnc.sh`, `VncInstallationNodeView.java`

## Kontext

UR e-Series ship-uje s default root password **`easybot`**. Je to verejne známy fact, v ktoromkoľvek UR forum thread-e. Každý zákazník, ktorý dostane UR10e, MÁ túto pasáž. Väčšina ju **nikdy nezmení**.

**Dôsledok bez zmeny:** Akýkoľvek network access = root access = úplný kontrol robota + keystrokes + filesystem.

Náš URCap pridáva VNC access cez IXON → ak root heslo ostalo easybot, útočník ktorý obíde IXON (alebo má vendor access) má root. VNC je len predník.

**Povinnosť URCapu:** Zabrániť deploy-u na unhardened robote ako aspoň **varovanie** (nie spontánne, ale ako explicit sign-off flow).

**Alternatívy zvažované:**
1. **A — Nothing, len dokumentovať v README** — nikto README nečíta
2. **B — Hard refuse ak easybot** (daemon sa nespustí) — príliš aggresive, zablokuje legitímny dev/test
3. **C — Environment toggle, default=on, explicit opt-out pre dev** ✅ ZVOLENÉ
4. **D — Auto-change password pri prvom štarte** — bez user consent žiaden security practice; naopak, stráca legitimate ops

## Rozhodnutie

Tripwire v `run-vnc.sh`:

```bash
if [[ "${URCAP_VNC_REQUIRE_STRONG_PWD:-1}" == "1" ]]; then
  # Known easybot hash (compare against /etc/shadow entry pre root)
  EASYBOT_HASH='$6$ASoNEPn.$qTVV4ULrlJpfiaBjDCEVmPDDLP7XLUzPKrltDQnedJyA3oQgBV0NRFHgxFdOXLLE/Z3nPXCmeuKBdfN4sQrpP.'
  CURRENT_HASH="$(getent shadow root | awk -F: '{print $2}')"
  if [[ "$CURRENT_HASH" == "$EASYBOT_HASH" ]]; then
    echo "REFUSING: default easybot password detected on root account." >&2
    echo "Change password via: ssh root@... 'passwd root'" >&2
    echo "Or set URCAP_VNC_REQUIRE_STRONG_PWD=0 in /var/lib/urcap-vnc/config" >&2
    echo "(not recommended for production)." >&2
    exit 2
  fi
fi
```

**UI exposure (v2.1.0):**
- `JCheckBox "Vyžadovať silné root heslo (easybot tripwire)"` (default checked)
- Pri uncheck → modal confirmation: *"Naozaj? Toto dovolí daemon štart aj pri neochránenom root account. Pre production NEODPORÚČANÉ."*
- Hodnota ide do config: `URCAP_VNC_REQUIRE_STRONG_PWD=1` alebo `=0`
- Ak daemon zlyhá s exit code 2, UI pokazuje red banner: *"Daemon refusal: easybot default password. SSH: passwd root"*

## Dôsledky

**Pozitívne:**
- Accidental deploy na unhardened robot = daemon refuses → operátor si uvedomí = hardening nastane
- Non-zero exit code 2 je distinctne rozlíšiteľný od iných failures (1 = generic, 2 = hardening fail, 3 = config fail, ...)
- UI-visible flag = dokumentuje sa v diagnose bundle (B2) = audit trail

**Negatívne:**
- EASYBOT_HASH je hardcoded — ak UR vydá e-Series s iným default (hypotetický) nebude detect-nuté. Mitigation: build-time check posledných známych UR release notes; health-probe môže skúšať `echo easybot | su root -c 'exit'` (ale to je ugly).
- Zákazník ktorý má "dobré" heslo ale iné tento exact easybot hash si pomyslí, že je bezpečný — **HESLO INÉ AKO EASYBOT ≠ silné heslo**. V UI text explicit: *"tento test LEN detekuje default easybot. Slabé custom heslo (napr. '123456') neblokne."*
- False sense of security risk. Mitigation: health panel A4 by mohol (v future) volať password-strength probe cez `pam_cracklib`.

## Súvisiace

- Sprint 1 A5 (UI banner + toggle)
- README — Security Hardening Checklist (passwd root, apt-get update, firewall)
