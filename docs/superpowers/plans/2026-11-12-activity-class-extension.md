# ActivityClass Extension (Boost HR Fusion) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the 3-state `ActivityClass` (REST/AEROBIC/RESISTANCE) to a 7-state graded classification driven by a ported Boost-style `HrActivityCalculator` (Karvonen HR zones × steps fusion), with the existing rule classifier kept as fallback when HR is unavailable.

**Architecture:** Three new pure objects in `plugins/aps` commonMain — `HrActivityCalculator` (stateless classifier ported from Boost 6.3.2), `ActivityFusion` (chooses HR fusion vs legacy rule classifier), and an additively extended `ActivityClass` enum with a mapping from exercise states. `OpenAPSAutoISFPlugin` keeps its existing wiring contract (`targetBgOffsetMgdl`, `smbCapFactor`) and only the classifier block changes.

**Tech Stack:** Kotlin (KMP, `commonMain` + `androidHostTest`), JUnit, existing AAPS key/preference system.

**Spec:** Boost 6.3.2 `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSBoost/HrActivityCalculator.kt` (reference repo: `/Users/jetcat/Projects/Dia/Boost-in-AAPS_3.4`, branch `boost-6.3.2`). Reference analysis conversation: porting direction agreed 2026-11-12.

## Global Constraints

- Target branch: new branch `feature/activity-class-hr-fusion` from `origin/aaps4-autoisf` (https://github.com/blaqone/AAPS-autoISF).
- Preserve CRLF line endings on all edited files.
- User-facing strings: English only, no concatenation, no mutation of localized strings.
- No new inter-module dependencies.
- Existing 3 enum entries (`AEROBIC`, `RESISTANCE`, `REST`) keep their exact values and names — no rename-in-place of persisted semantics.
- Classifier must stay off during a temp target (existing behavior at `OpenAPSAutoISFPlugin.kt:274-307`).
- STRESS and INACTIVE must never reduce SMB (smbCapFactor = 1.0) and must never, by themselves, raise a dose.
- Test command: `./gradlew :plugins:aps:testReleaseUnitTest --tests "app.aaps.plugins.aps.openAPSSMB.*"` (on KSP flake retry once without clean, then `--max-workers=1`).

## Review Focus

- Frozen HR: a window of bit-identical readings must be treated as "HR unavailable" (legacy fallback), never as rest and never as an elevated state → test in Task 1 (`isHrWindowFrozen`).
- Missing HR with HR present in window but NaN average → legacy fallback, never INACTIVE evidence → test in Task 3 (`ActivityFusion` null/empty readings).
- STRESS posture: HR elevated without movement → target offset 15, SMB cap 1.0 (no reduction) → test in Task 1 (`classify` stress branch).
- Resistance dead zone: 30–99 steps + Zone 3–4 must return RESISTANCE, not RESTING → test in Task 1.
- OS activity signal precedence: PHONE_WALKING/RUNNING/BICYCLE short-circuits to legacy `AEROBIC` before fusion runs → test in Task 3.

---

### Task 1: Port `HrActivityCalculator` (pure, commonMain)

**Files:**
- Create: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSSMB/HrActivityCalculator.kt`
- Test: `plugins/aps/src/androidHostTest/kotlin/app/aaps/plugins/aps/openAPSSMB/HrActivityCalculatorTest.kt`

**Interfaces:**
- Consumes: `app.aaps.core.data.model.HR` (fields `isValid: Boolean`, `timestamp: Long`, `beatsPerMinute: Double`, `duration: Long`), `app.aaps.core.interfaces.logging.AAPSLogger`, `LTag`.
- Produces (used by Tasks 2–3):
  - `enum class HrZone { NONE, ZONE_1_VERY_LIGHT, ZONE_2_LIGHT, ZONE_3_MODERATE, ZONE_4_HARD, ZONE_5_MAX }`
  - `enum class Confidence { HIGH, MEDIUM, LOW }`
  - `enum class ExerciseState { VIGOROUS_AEROBIC, MODERATE_AEROBIC, LIGHT_AEROBIC, RESISTANCE, STRESS, RESTING, INACTIVE }`
  - `data class HrClassificationResult(exerciseState: ExerciseState, hrZone: HrZone, averageHrBpm: Double?, hrrPercent: Double?, confidence: Confidence, debugInfo: String)`
  - `fun classifyZone(bpm: Double, hrMax: Int, hrResting: Int): HrZone`
  - `fun averageHrInWindow(readings: List<HR>, nowMillis: Long, windowMinutes: Int): Double?` (duration-weighted)
  - `fun isHrWindowFrozen(readings: List<HR>, nowMillis: Long, windowMinutes: Int): Boolean`
  - `fun classify(hrReadings: List<HR>, nowMillis: Long, hrWindowMinutes: Int, hrMax: Int, hrResting: Int, stepsLast15Min: Int, stressDetection: Boolean, aapsLogger: AAPSLogger? = null): HrClassificationResult`
  - `fun inactivitySuppressedByElevatedHr(result: HrClassificationResult?): Boolean`
  - `fun inactivityRaiseBlockedByHr(result: HrClassificationResult?): Boolean`

Port the Boost implementation verbatim in behavior: Karvonen thresholds (<30 Z1, <40 Z2, <60 Z3, <80 Z4, else Z5); step thresholds 300/100/30 for high/moderate/low; frozen-HR guard (min 4 readings, all bit-identical → unavailable: RESTING state, `hrZone = NONE`, confidence LOW); no-readings → same unavailable result; fusion branches exactly as Boost (including the widened RESISTANCE condition `!moderateSteps && zone >= ZONE_3_MODERATE && zone <= ZONE_4_HARD`); `inactivitySuppressedByElevatedHr` = zone ≥ ZONE_3; `inactivityRaiseBlockedByHr` = null result, zone NONE, or zone ≥ ZONE_2. Skip Boost's `debugInfo`-only extras and the logger is optional (null in tests).

- [ ] **Step 1: Write the failing test** — table-driven cases in `HrActivityCalculatorTest`:
  - zone boundaries: HRR% 29.9→ZONE_1, 30→ZONE_2, 39.9→ZONE_2, 40→ZONE_3, 60→ZONE_3? (per code: <60 → ZONE_3, 60→ZONE_4), 80→ZONE_5, using hrMax=180, hrResting=60.
  - `averageHrInWindow`: duration-weighted mean of two readings (e.g. 60bpm×2min + 100bpm×1min = 73.33), filters by window and validity, null on empty.
  - `isHrWindowFrozen`: 4 identical readings → true; 3 identical → false; 4 differing → false.
  - fusion: 400 steps + zone 3 → VIGOROUS_AEROBIC/HIGH; 150 steps + zone 2 → MODERATE_AEROBIC; 50 steps + zone 3 → RESISTANCE (dead-zone fix); 10 steps + zone 2, stressDetection=true → STRESS/LOW; 10 steps + zone 1 → INACTIVE/HIGH; no readings → RESTING/NONE/LOW.
  - guards: `inactivitySuppressedByElevatedHr(zone3Result)` true; `inactivityRaiseBlockedByHr(null)` true; `inactivityRaiseBlockedByHr(zone2Result)` true; `inactivityRaiseBlockedByHr(zone1Result)` false.
- [ ] **Step 2: Run test, expect FAIL** (class not found): `./gradlew :plugins:aps:testReleaseUnitTest --tests "app.aaps.plugins.aps.openAPSSMB.HrActivityCalculatorTest"`
- [ ] **Step 3: Implement `HrActivityCalculator`** as `object` in commonMain, porting Boost's logic (no DI, no stored state).
- [ ] **Step 4: Run test, expect PASS.**
- [ ] **Step 5: Commit** `feat(aps): port HrActivityCalculator from Boost 6.3.2`

### Task 2: Extend `ActivityClass` additively + state mapping

**Files:**
- Modify: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSSMB/ActivityClass.kt`
- Test: `plugins/aps/src/androidHostTest/kotlin/app/aaps/plugins/aps/openAPSSMB/ActivityClassMappingTest.kt` (new)

**Interfaces:**
- Consumes: `HrActivityCalculator.ExerciseState` (Task 1).
- Produces (used by Tasks 3, 5): new enum entries `LIGHT_AEROBIC(10.0, 0.9)`, `MODERATE_AEROBIC(10.0, 0.8)`, `VIGOROUS_AEROBIC(15.0, 0.7)`, `STRESS(15.0, 1.0)`, `INACTIVE(0.0, 1.0)`; property `val isAerobic: Boolean` (true for AEROBIC, LIGHT_AEROBIC, MODERATE_AEROBIC, VIGOROUS_AEROBIC); `val isRestLike: Boolean` (true for REST, INACTIVE); companion `fun fromHrState(state: HrActivityCalculator.ExerciseState, stressDetection: Boolean): ActivityClass` mapping VIGOROUS/MODERATE/LIGHT_AEROBIC → same-named entry, RESISTANCE → RESISTANCE, STRESS → STRESS (only reachable when stressDetection), RESTING → REST, INACTIVE → INACTIVE.

Value rationale (document in the enum KDoc): graded aerobic intensity gets graded SMB caps (0.9/0.8/0.7) with target offsets 10 (light/moderate) and 15 (vigorous); STRESS raises target only — cap stays 1.0 because acute stress raises resistance; INACTIVE mirrors REST's numbers here (the insulin-adding inactivity raise remains the step monitor's job via `InactivityScaleFactor`, gated by the HR guards in Task 5).

- [ ] **Step 1: Write the failing test**: assert each new entry's `targetBgOffsetMgdl`/`smbCapFactor`; assert `fromHrState` maps all 7 states; assert STRESS/INACTIVE caps are 1.0; assert legacy entries unchanged (AEROBIC 10/0.7, RESISTANCE 15/0.8, REST 0/1.0).
- [ ] **Step 2: Run test, expect FAIL.**
- [ ] **Step 3: Implement the enum extension and mapping** (additive; do not touch existing entries).
- [ ] **Step 4: Run test, expect PASS.**
- [ ] **Step 5: Commit** `feat(aps): extend ActivityClass to 7 graded states with HR-state mapping`

### Task 3: `ActivityFusion` — legacy fallback + OS-signal precedence

**Files:**
- Create: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSSMB/ActivityFusion.kt`
- Test: `plugins/aps/src/androidHostTest/kotlin/app/aaps/plugins/aps/openAPSSMB/ActivityFusionTest.kt` (new)

**Interfaces:**
- Consumes: `HrActivityCalculator.classify(...)`, `ActivityClassifier.classify(...)` (existing, unchanged), `ActivityClass.fromHrState(...)`.
- Produces (Task 5 consumes):
  `fun classifyActivity(hrReadings: List<HR>, nowMillis: Long, hrWindowMinutes: Int, hrMax: Int, hrResting: Int, steps15m: Int, phoneActivity: Int, stressDetection: Boolean, aapsLogger: AAPSLogger?): ActivityClass`

Decision order (pinned):
1. `phoneActivity == PHONE_BICYCLE || PHONE_WALKING || PHONE_RUNNING` → return legacy `AEROBIC` (OS signal wins; unchanged behavior).
2. `HrActivityCalculator.averageHrInWindow(hrReadings, ...) == null` **or** `isHrWindowFrozen(...) == true` → legacy `ActivityClassifier.classify(steps15m, 0.0, phoneActivity, restingHr)` (HR unavailable = unknown, never INACTIVE evidence).
3. Otherwise → `ActivityClass.fromHrState(HrActivityCalculator.classify(...).exerciseState, stressDetection)`.

- [ ] **Step 1: Write the failing test**: OS-signal precedence cases (bicycle/walking/running with garbage HR → AEROBIC); empty readings + 200 steps → legacy path returns AEROBIC, + 10 steps/low HR legacy REST; frozen window → legacy REST even though steps high? (legacy rule 3: 200 steps > 150 → AEROBIC — pin that the fallback is the *legacy* result, not REST); usable HR 120bpm rest 60 max 180 with 5 steps → STRESS (if stressDetection) / RESISTANCE (else — pin that branch: with stressDetection=false, 5 steps + zone 3-4 falls to the widened RESISTANCE branch).
- [ ] **Step 2: Run test, expect FAIL.**
- [ ] **Step 3: Implement `ActivityFusion`.**
- [ ] **Step 4: Run test, expect PASS.**
- [ ] **Step 5: Commit** `feat(aps): ActivityFusion with OS-signal precedence and HR-unavailable fallback`

### Task 4: New preference keys, strings, screen registration

**Files:**
- Modify: `core/keys/src/commonMain/kotlin/app/aaps/core/keys/IntKey.kt` (after `ActivityRestingHr`, ~line 416–422)
- Modify: `core/keys/src/commonMain/kotlin/app/aaps/core/keys/BooleanKey.kt` (after `ApsAutoIsfUseActivityTargetOffset`, ~line 243–252)
- Modify: `core/keys/src/androidMain/res/values/strings.xml` (English strings next to `activity_resting_hr_title`, ~line 436)
- Modify: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAutoISF/OpenAPSAutoISFPlugin.kt` — `PreferenceSubScreenDef(key = "activity_monitor", ...)` item list (append at the end, after `IntKey.ActivityRestingHr`)
- Test: covered by Task 5 compile + `PreferenceKeySnapshotTest` (existing, `app/src/testFull`) — run it in Task 5; no new test file.

**Interfaces:**
- Consumes: existing `IntKey`/`BooleanKey` DSL patterns (see `ActivityRestingHr(key=..., defaultValue=..., min=..., max=..., title=KeysStrings...)`, `ApsAutoIsfUseActivityTargetOffset(... dependency = ApsAutoIsfUseActivityClassifier ...)`, `KeysStrings` generation).
- Produces (Task 5 consumes): `IntKey.ActivityHrMax` (`"activity_hr_max"`, default 180, min 100, max 220, title `activity_hr_max_title`, summary `activity_hr_max_summary`); `BooleanKey.ApsAutoIsfActivityStressDetection` (`"autoisf_activity_stress_detection"`, default **false**, title/summary `aps_autoisf_activity_stress_*`, dependency = `ApsAutoIsfUseActivityClassifier`, defaultedBySM + Cold/Bidirectional sync matching neighbors).

Strings (English only): "Maximum heart rate (bpm)" / "HRmax used for exercise zones (220 − age is a common estimate)"; "Detect stress from heart rate" / "Elevated heart rate without movement raises the target instead of reducing insulin".

- [ ] **Step 1: Add both keys + strings + screen items** (one edit set; no test-first split possible since these are declarative).
- [ ] **Step 2: Compile keys + aps modules**: `./gradlew :core:keys:assemble :plugins:aps:assembleRelease` (or `compileReleaseKotlin`), expect SUCCESS.
- [ ] **Step 3: Commit** `feat(keys): ActivityHrMax and stress-detection prefs for activity classifier`

### Task 5: Wire `OpenAPSAutoISFPlugin` to the fused classifier + full verification

**Files:**
- Modify: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAutoISF/OpenAPSAutoISFPlugin.kt:270-307` (classifier block) and the debug-log line ~286
- Test: `./gradlew :plugins:aps:testReleaseUnitTest --tests "app.aaps.plugins.aps.openAPSSMB.*"` + `./gradlew :app:testFullReleaseUnitTest --tests "app.aaps.keys.PreferenceKeySnapshotTest"`

**Interfaces:**
- Consumes: `ActivityFusion.classifyActivity(...)` (Task 3), `IntKey.ActivityHrMax`, `IntKey.ActivityRestingHr`, `BooleanKey.ApsAutoIsfActivityStressDetection` (Task 4), existing `persistenceLayer.getHeartRatesFromTime(...)`.
- Produces: unchanged external contract — `smbCapFactor` still scales `maxSMBBasalMinutes`/`maxUAMSMBBasalMinutes` (lines ~357-358); `targetBgOffsetMgdl` still lifts `targetBg`/`minBg`/`maxBg` (cap 180.0).

Changes inside the `if (useActivityClassifier && !isTempTarget)` block:
- Replace `ActivityClassifier.classify(...)` with `ActivityFusion.classifyActivity(hrReadings = persistenceLayer.getHeartRatesFromTime(now - T.mins(15).msecs()), ..., hrWindowMinutes = 15, hrMax = preferences.get(IntKey.ActivityHrMax), hrResting = preferences.get(IntKey.ActivityRestingHr), steps15m = recentSteps15Minutes, phoneActivity = ActivityClassifier.phoneActivity, stressDetection = preferences.get(BooleanKey.ApsAutoIsfActivityStressDetection))`.
- `stepActivityDetected = activityClass.isAerobic || activityRatio < 1` (STRESS does **not** count as step activity).
- `stepInactivityDetected = !stepActivityDetected && activityClass.isRestLike && activityRatio > 1`.
- When the fused result came from HR (non-legacy path) and is INACTIVE: additionally require `!HrActivityCalculator.inactivityRaiseBlockedByHr(result)` before `stepInactivityDetected` can be set true — keep the `HrClassificationResult` from the fusion call to pass into the guard. If that forces a new public result accessor on `ActivityFusion` (e.g. a small `data class FusionResult(activityClass: ActivityClass, hrResult: HrClassificationResult?)`), prefer adding it now over re-classifying.
- Debug log becomes: `"ActivityClass: $activityClass zone=... conf=... steps15m=... hr=..."` — extend the existing line, do not add new log tags.
- The AEROBIC-ratio-override branch (`activityClass == ActivityClass.AEROBIC && activityRatio >= 1`) becomes `activityClass.isAerobic && activityRatio >= 1` so graded aerobic states also get the activity monitor ratio.

- [ ] **Step 1: Implement the plugin wiring** (no new unit test file: the decision logic lives in Task 3's tested object; the plugin block is configuration).
- [ ] **Step 2: Run module tests** (command above), expect PASS — including existing `ActivityClassifierTest` (legacy classifier untouched) and the new tests from Tasks 1–3.
- [ ] **Step 3: Run key-snapshot test**, expect PASS.
- [ ] **Step 4: Full compile**: `./gradlew :plugins:aps:assembleRelease`, expect SUCCESS.
- [ ] **Step 5: Commit** `feat(autoisf): wire HR-fused activity classifier into OpenAPSAutoISFPlugin`
