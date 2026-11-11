# AAPS 4.0.0 + AutoISF 3.2.1 (test version)

---

## ⚠️ READ THIS FIRST – SAFETY NOTICE

**This software can control an insulin pump. Wrong insulin doses can cause severe hypoglycemia,
diabetic ketoacidosis, loss of consciousness or death.**

- **This is an experimental test version.** It is NOT an official AndroidAPS release and is NOT
  supported, reviewed or approved by the AndroidAPS team or the AutoISF authors.
- **This is not a medical device.** It is not approved by any health authority (FDA, CE, MDR or
  any other). It is not medical advice.
- **You use it entirely at your own risk.** You alone are responsible for building it, installing
  it, choosing every setting and every insulin dose that results from it.
- **No warranty of any kind.** The software is provided "as is". The authors and contributors
  accept no liability for any harm, injury, damage or loss, direct or indirect, that results from
  using, building or sharing it (see the license, sections 15 and 16).
- **Test first, without risk:** use the virtual pump or **Open Loop** for several days. Only move
  to Closed Loop when every result has made sense to you – and then start with careful limits
  (max IOB, max SMB, AutoISF min/max).
- **Changes marked ⚠️** in the changelog can change how much insulin is given. Read them before
  you update.
- **Always keep a backup plan:** a blood glucose meter, fast carbs, insulin pen and a way to stop
  the loop at any time.
- **Do not use it** if you do not understand how AndroidAPS, OpenAPS/SMB and AutoISF work, or if
  you cannot check and correct what the loop is doing.
- **Talk to your diabetes team** before you change your therapy.

**If you do not accept these terms, do not build, install or use this software.**

---

## What this is

This fork brings **AutoISF 3.2.1** to **AndroidAPS 4.0.0** and adds features for CGM sensors
that send a value every minute (for example Libre 3 through Juggluco).

- Based on AndroidAPS 4.0.0 and JetFoxy's branch `autoisf-3.2.1` – many thanks to JetFoxy!
- Goal: AutoISF stays **as close to the original as possible**.
- No version expiry: this is not an official AAPS version, so the official version check is off.
  It would otherwise set max IOB to 0 some time after a new official release.
- The version name shows the branch: `4.0.0-aisf3.2.1` (normal), `…-dev`, `…-sport`.
- All changes in detail: [CHANGELOG-autoISF.md](CHANGELOG-autoISF.md) (German)

## Branches – which one is for me?

| Branch | What it is | For whom |
|---|---|---|
| ⭐ [`aaps4-autoisf`](https://github.com/blaqone/AAPS-autoISF/tree/aaps4-autoisf) | **Normal version – recommended.** AutoISF 3.2.1 on AAPS 4.0.0, as close to the original as possible. Only features that were tested. | **Everyone who wants to use AutoISF. Start here.** |
| [`aaps4-autoisf-dev`](https://github.com/blaqone/AAPS-autoISF/tree/aaps4-autoisf-dev) | **Test version.** The normal version plus features that are still being tested. Changes often. | Only for testers. |
| [`aaps4-autoisf-sport`](https://github.com/blaqone/AAPS-autoISF/tree/aaps4-autoisf-sport) | **Sport version – my personal mode.** The normal version plus my changes for sport: steps from a Garmin watch for the activity monitor, SMB threshold, ketoacidosis protection. More will follow. | Mainly for me. Use it only if you know what these changes do. |

How new things get in:

1. A new feature or fix is made on its own branch (`feature/...` or `fix/...`), starting from the
   normal version.
2. It is tested in `aaps4-autoisf-dev`.
3. Only when it works well, it goes into `aaps4-autoisf` (and `aaps4-autoisf-sport`).

Changes for sport are made only in `aaps4-autoisf-sport`. They do not go into the normal or the dev version.

The dev version is never merged into the normal version as a whole, so the normal version only
gets features that were tested.

## Guide

**New to AutoISF on AAPS 4.0.0?** Read the guide: [docs/AutoISF-v4-Guide.md](docs/AutoISF-v4-Guide.md) –
what is different from AutoISF on AAPS 3.x, where the settings are and how to use the new parts.

For AutoISF itself see the original documents: the AutoISF Quick Guide and BerNie's guide for Full Closed
Loop in [ga-zelle/autoISF](https://github.com/ga-zelle/autoISF), and the official AutoISF branch
[T-o-b-i-a-s/AndroidAPS 3.4.2.6+aisf3.2.1](https://github.com/T-o-b-i-a-s/AndroidAPS/tree/3.4.2.6+aisf3.2.1).

<img src="docs/images/main-screen.png" alt="Main screen with the AutoISF chip opened" width="400">

*Screenshot from the app, AutoISF chip opened. The two parabola lines at the bottom of the chip (raw and
smoothed) are only in the dev version.*

## Sport version – what is different

This is the **sport branch** (`aaps4-autoisf-sport`), my personal mode. It is the normal version plus:

- **Steps from a Garmin watch** for the AutoISF activity monitor. The watch face sends its steps with the
  requests it already makes. Setting *Steps from*: phone, watch or automatic (watch when it sent steps in
  the last 10 minutes, else phone).
- **Night for the activity monitor**: setting *Night is* – night hours, state "Sleeping" or both.
- ⚠ **SMB threshold** (on, 140 mg/dl): below it no SMB is given. Comes in addition to the even/odd target rule.
- ⚠ **Ketoacidosis protection** (off by default, 5 %): instead of a zero or very low temp basal the loop
  gives a small one – at least what the pump really gives (Dana 0.10 U/h, Medtrum 0.05 U/h), never more than
  the profile basal.

All of them are in the AutoISF settings. ⚠ = can change the insulin given. Planned next: an activity mode
with big buttons, extending running scenes, a summary after an activity.

## Features

### AutoISF 3.2.1
- acce_ISF, bg_ISF, pp_ISF, dura_ISF, iobTH – as in the original
- can be enabled without engineering mode
- activity detection (activity monitor / classifier) with safety limits: activity never gives more
  insulin than autosens, inactivity never overrules an autosens that says "more sensitive"
- AutoISF values are stored in the loop result, no own database table

### One-minute values and loop interval
- the loop uses the **newest reading**, not the 5-minute grid
- UKF filter with the real one-minute readings
- **loop interval 1–10 minutes** (default 5) – at 1 the loop runs on every new value
- the **SMB interval** stays a separate setting
- BG display, trend arrow, widgets and bolus wizard use the newest value

### Parabola (acce_ISF)
- default as in the original: parabola fit on raw values
- switch: parabola on **UKF-smoothed values** (calmer, but later)
- slider: **shortest parabola** 5–20 minutes (default 10)
- when the switch is on, the APS tab shows which values the parabola uses
- *dev version only:* raw and smoothed fit side by side on the main screen, in the APS tab and as
  a graph

### CGM correction
- **Only slope and offset – no calibration, no extra smoothing**
- `BG = value × slope + offset` for every value that comes in through xDrip (also Juggluco)
- example: the sensor always reads 30 mg/dL too low → offset +30
- set in the xDrip source settings or in the AutoISF settings; it always applies and is also
  stored and uploaded (Nightscout)

### Scenes and automations
- scenes can change **AutoISF on/off, acce weight and iobTH** while they run –
  the old values come back automatically at the end
- automation actions: enable/disable AutoISF, set acce weight, set iobTH
- automation triggers: acce weight, iobTH (compatible with rules from AutoISF 3.2.1)

### Display
- BG age in seconds
- AutoISF chip on the main screen, under the BG: closed one line with the values you pinned, a tap
  opens all values (pin up to 4), a long press opens the AutoISF screen with the calculation
- pink BG parabola on the BG graph, as in AutoISF 3.2.1: solid over the fitted minutes, dashed
  20 minutes ahead
- holding a point of the BG graph shows the smoothed value and the raw value in brackets
- coloured BG dots every minute with a 1 minute CGM and UKF smoothing (display only)
- graphs for the AutoISF factors and iobTH (also in the IOB graph)

Everything that only concerns AutoISF (graph lines, scene section, automations, main screen
lines) is hidden when AutoISF is not the active APS plugin.

## Building

1. Install Android Studio, clone this repository, check out the branch you want –
   **normally `aaps4-autoisf`**
   (see [Branches](#branches--which-one-is-for-me))
2. Build `fullDebug` (for testing) or a signed `fullRelease` with your own keystore
3. Copy the APK to the phone and install it

No ready-made APK is provided.

## Before your first test

- export the settings of your current app
- best use a second phone or the virtual pump
- start in **Open Loop**; move to Closed Loop only after several days that made sense
- choose careful limits (max IOB, max SMB, AutoISF min/max)

## Feedback

Bugs and ideas are welcome as an [issue](https://github.com/blaqone/AAPS-autoISF/issues) –
ideally with a screenshot of the APS tab and the time.

## License

Same as AndroidAPS: GNU Affero General Public License v3.0 (AGPL-3.0).
This program comes with ABSOLUTELY NO WARRANTY, to the extent permitted by applicable law.

---

<!-- changelog:start -->
## Changelog (German)

The newest changes. ⚠ = this change can affect insulin dosing.

### 2026-11-11 (29) – Update direkt von AutoISF 3.4.2.6 möglich

Bisher ist die App beim Update über **AAPS 3.4.2.6 + AutoISF 3.2.1** bei jedem Start abgestürzt.

- **Datenbank-Version 32 doppelt belegt:** AutoISF 3.4 nutzt Version 32 für seine Tabelle `autoIsfValues`,
  AAPS 4 für die Spalte `carbInsulin` in den Tagesdosen (TDD). **Fix:** Fehlt die Spalte, wird der TDD-Cache
  geleert (wird neu berechnet) und die Spalte angelegt; die alte Tabelle `autoIsfValues` wird gelöscht.
  Bei einer normalen AAPS-4-Datenbank passiert nichts.
- **Unbekannte Einträge im Benutzerprotokoll** (`AUTOISF_ENABLED`, `AUTOISF_DISABLED`, `IOB_TH_SET`,
  `ACCE_WEIGHT_SET`) werden zu „UNKNOWN“, und jeder unbekannte Eintrag wird künftig als „UNKNOWN“ gelesen
  statt abzustürzen.
- Die übrige Historie bleibt erhalten. Vorher trotzdem **Einstellungen exportieren**; gleicher
  Signaturschlüssel nötig. Zurück auf 3.4 geht nicht ohne Verlust der Datenbank.
- Am besten zuerst auf einem **Zweithandy** testen.

### 2026-11-11 (27) – Sport: Fixe aus dev übernommen (nur sport)

- ⚠ **Activity Classifier:** nimmt die Summe der Schritte der letzten 15 Minuten (vorher nur einen
  5-Minuten-Block von vor 10–15 min). Neue Einstellung **„ActivityClassifier sensitivity“**:
  hoch 450/90, **mittel 600/45 (Standard)**, niedrig 750/30 (Schritte in 15 min für Ausdauer / Grenze
  für Kraftsport). Die Schritte kommen aus derselben Quelle wie beim Activity Monitor (Handy oder Uhr).
- **IOB-Graph:** Legende für IOB und iobTH, wenn iobTH eingeschaltet ist. iobTH ist nur noch im
  IOB-Graph wählbar, nicht mehr in den Graphen 2–5.
- **Automation „Schritte“ (AAPS #5499 von Milos):** liest die neueste Zeile, löst also auch mit
  Handy-Schritten aus. Von der Wear-Uhr sind 10 und 15 min jetzt Summen. **Eigene Automationen mit
  Schritten bitte prüfen.**
- **Logdateien** in `Documents/aapsLogs` wie bei AutoISF 3.4 (stündliches Zip, 30 Tage).
- ⚠ **Uhr-Schritte, Wear OS:** Eine Wear-Uhr schreibt 6 Zeilen gleichzeitig. Jetzt zählt nur die neueste
  und längste davon, also nichts doppelt – auch mit einer älteren Wear-App.
- Activity Monitor selbst unverändert (Grenzen wie im Original AutoISF).

### 2026-11-11 (26) – Doku: Version 4.0.0 genannt, echter Screenshot

- README und Anleitung nennen jetzt genau **AAPS 4.0.0** statt nur „AAPS 4“.
- Die gezeichneten Beispielbilder sind entfernt. Stattdessen gibt es einen **echten Screenshot** des
  Hauptbildschirms mit geöffnetem AutoISF-Chip.
- Nur Doku, keine Änderung an der App.

### 2026-11-11 (24) – Sport-Version: Garmin-Schritte, SMB-Schwelle, Ketoazidose-Schutz (nur sport)

- **Version** heißt `4.0.0-aisf3.2.1-sport`.
- **Garmin:** Das Watchface kann mit seinen Abfragen die Schritte mitschicken (als Zeitfenster oder als
  Tagessumme). Nach einer Lücke von über 15 Minuten oder nach Mitternacht wird nur neu angefangen, damit
  alte Schritte nie wie „gerade aktiv“ aussehen.
- ⚠ **Activity Monitor – „Schritte von“:** Handy / Uhr / automatisch (Standard: Uhr, wenn sie in den
  letzten 10 Minuten Schritte geschickt hat, sonst Handy). Mit der Uhr entfällt die Prüfung
  „Handy wird nicht getragen“.
- ⚠ **Activity Monitor – „Nacht ist“:** Nachtstunden / State „Sleeping“ / beides (Standard beides).
  Früher wurden die Nachtstunden ignoriert, sobald es den State gab; jetzt zählen beide – Inaktivität
  gibt also nie in mehr Fällen mehr Insulin als vorher.
- ⚠ **SMB-Schwelle** (Standard an, 140 mg/dl): Liegt der BZ darunter, gibt es keine SMBs. TBRs bleiben.
  Gilt zusätzlich zur Regel gerade/ungerade.
- ⚠ **Ketoazidose-Schutz** (Standard aus, 5 %, 1–40 %): Statt einer TBR von 0 oder einer sehr niedrigen TBR
  gibt der Loop mindestens diesen Anteil der Basalrate – nie weniger als die Pumpe wirklich gibt (Dana
  0,10 U/h, Medtrum 0,05 U/h) und nie mehr als die Basalrate selbst. Gilt auch, wenn ein Unterzucker
  vorhergesagt ist; nicht bei kaputten CGM-Daten.
- Exercise Mode und Aktivitätserkennung: Der Activity Monitor ist bei jedem TT aus, also auch, wenn
  Exercise wirkt – keine doppelte Senkung.

### 2026-11-11 (23) – Farbige BZ-Punkte jede Minute, Versionsprüfung aus

- **Farbige BZ-Punkte jede Minute** (in dev getestet): Mit 1-Minuten-CGM und UKF-Glättung zeigt der
  BZ-Graph jede Minute einen farbigen Punkt (geglätteter Wert), nicht nur alle 5 Minuten. Gilt mit jedem
  APS. Reine Anzeige, die Berechnung bleibt auf dem 5-Minuten-Raster.
- **Versionsprüfung aus:** Diese Version ist keine offizielle AAPS-Version. Die Prüfung hätte sie mit den
  offiziellen Versionen verglichen und einige Zeit nach einer neuen offiziellen Version Max IOB auf 0
  gesetzt („Anwendung abgelaufen“) – das hätte den geschlossenen Loop beendet.

All changes: [CHANGELOG-autoISF.md](CHANGELOG-autoISF.md)
<!-- changelog:end -->
