# WHOOF.md — fork contract and upstream review

Whoof is a **private, intentionally divergent fork** of [ryanbr/noop](https://github.com/ryanbr/noop).
Only `android/` ships. `Strand/` (Swift) is carried but never built — keep it in sync anyway, because
that is what makes future cherry-picks apply.

Upstream's own guidance is in [AGENTS.md](AGENTS.md) and still applies to code style, the protocol
layer and the parity contract. This file governs **what we take from upstream and what we refuse.**

---

## 0. Standing priority — read this before proposing anything

The owner's direction, 2026-09-21, verbatim in substance:

> A more focused, minimalistic tool. Serious improvement is needed on **connectivity** and
> **calculations**. I really need to improve the **backend** for any improvements. On the front end,
> unlikely to want new info displayed, and I don't want to lose my customizations.

What that means in practice:

1. **Backend is where effort goes.** Connectivity (`ble/`, link stability, offload, background policy)
   and correctness of the numbers (`analytics/` — sleep staging, strain, respiratory rate, steps, HRV).
   A change that makes a figure match reality beats any number of screens.
2. **The front end is frozen by default.** The screens are the owner's design. Do not add a card, a
   badge, a banner, a note or a caption. "It only adds one line" is exactly the thing being refused.
   Removing clutter is still welcome; adding is not.
3. **Never regress a customization.** Every fork default and layout listed in §2 is a decision that was
   made deliberately, usually after a complaint. Restoring an upstream default is a regression even
   when upstream is "more correct" in the abstract.
4. **A UI change is allowed only when it fixes a lie.** A warning that fires on healthy nights, a
   number that is stale, a control that does nothing. That is correctness wearing a UI diff, and it
   should land as a *gate* or a *value*, not as new furniture.

So the triage in §2 is ordered: `ble/` and `analytics/` first, everything else needs a reason.

---

## 1. The standing job: review upstream, don't merge it

Never `git merge upstream/main`. The fork rewrote Today, Sleep, Heart, Coach, Settings and
`res/values/strings.xml`; a merge produces hundreds of conflicts and silently restores things the
owner asked to remove (journal, terms gate, Live screen, Apple Health, verbose subtitles).

The only supported flow is **per-commit cherry-pick with a written verdict.**

```bash
cd ~/Dev/Whoof
git remote add upstream https://github.com/ryanbr/noop.git   # once
git fetch --depth 200 upstream main

BASE=$(git merge-base HEAD upstream/main)
git log --oneline "$BASE..upstream/main" -- android/          # candidates
git diff --stat "$BASE..upstream/main" -- android/app/src/main/java
```

Dry-run each candidate honestly — **never** with `--strategy-option=theirs`, which hides every
conflict and reports a false CLEAN:

```bash
git checkout -b upstream-probe
git cherry-pick -n <sha> ; git diff --name-only --diff-filter=U   # empty = clean
git cherry-pick --abort 2>/dev/null; git reset --hard HEAD; git clean -fd
```

Run it roughly **every two weeks**, and always before cutting a release.

---

## 2. Triage rules

**Take (usually):**
- `analytics/`, `protocol/`, `ble/`, `data/`, `ingest/` — the engine and the reverse-engineered
  protocol. This is the reason to track a fork at all; upstream has the straps and the captures.
- Diagnostics and log-format fixes. They cost nothing and they are what makes a field report usable.
- Correctness fixes with a test attached.

**Look twice:**
- Anything in `ui/`. The fork owns its screens. If the substance is worth having, port the *logic*
  into the fork's own composable rather than taking the diff.
- New strings. `res/values/strings.xml` diverged at line 3 (`app_name`, and the
  Charge→Recovery / Effort→Strain / Rest→Sleep rename), so **every** upstream string insert
  conflicts. Resolve by hand; apply the same rename to any copy that lands.

**Refuse:**
- Features the owner deleted: journal / "What Moves You", terms gate, onboarding pages, Live screen,
  Lab Book, Apple Health, nutrition / Mi Band / lifting-log / Oura-Fitbit-Garmin importers,
  screen subtitles, the NOOP wordmark.
- Oura ring work (no ring on this account).
- Anything that would re-enable verbose explainer copy on a main screen.

**Hard no, learned the hard way:** do not restore upstream defaults the fork deliberately changed.
See `BackgroundMode` (fork: `ALWAYS`), `banisterEffort` (fork: `false`), `EffortScale`,
`hcAutoSync`, `KeyMetric.defaultOrder`, `DashboardCard.defaultSelection`.

---

## 3. After a cherry-pick

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
cd android && ./gradlew testFullDebugUnitTest          # full suite, ~6.3k tests
```

Run the **full** suite, never a filtered subset. Whoof 1.5.0 shipped with `StepsTileNavigationTest`
red because only the classes touched that day were run; the break surfaced two releases later, during
an unrelated upstream review. Two `RecoveryDriversTest` failures are long-standing upstream
floating-point cases and are the only expected red.

Then append a row to the log in §5 — **every** reviewed commit, including the refusals. A commit
with no verdict gets re-reviewed forever.

There is **no device and no strap on this machine.** Nothing touching BLE, sleep staging or scoring
can be called verified here; say so in the release note.

---

## 4. Upstream tests are a spec, not an obstacle

When a fork change turns an upstream test red, read the test name before editing the test. It usually
encodes a rule worth keeping. `stepsTileShouldOpenCalibration` was overridden in 1.5.0 so an
estimate-only Steps tile opened calibration; the upstream tests said a tile showing **any** number
opens its trend, and a tile with no calibration prompt has nothing to calibrate. Upstream was right —
wrong step counts are a Health Connect data problem, and calibration is still reachable from
Settings → Steps estimate. The override was reverted.

Edit the test only where the fork's divergence is the point (`BottomBarTabsTest`,
`TodayLayoutPrefsTest`, `HostedCardPrefsTest`), and say so in the test.

---

## 5. Review log

`Verdict`: TAKEN / PORT (substance re-implemented in fork code) / SKIP / WAIT.

### Review 2026-09-21 — base `e02ed9b` (build 521) → upstream `a56840b` (build 531), 30 android commits

| Commit | What | Applies | Verdict |
|---|---|---|---|
| `40e63cf` | Alarm readback refuses to judge a reply to an earlier arm | clean | **TAKEN** |
| `01bfccb` | sleep-detect: split `no-motion` from `no-motion-provided-unused` in the NO-NIGHT log | clean | **TAKEN** — names exactly the 5/MG failure the owner is hitting |
| `6fbd30c` | Diagnostics header reads last sleep/recovery from the *active* strap | clean | **TAKEN** |
| `715ad98` | Link signal strength (RSSI) on the BLE epitaph | conflict: `Tools/parity_twin_map.json` only | **TAKEN** — connectivity diagnostics, §0.1 |
| `f4a6f3f` | "May be incomplete" only when the night actually reads short | conflict: `SleepScreen.kt` | **PORTED** — the note fired on healthy nights, so it was a lie (§0.4). Gate ported as `stageSparseNoteApplies`, wired into `SleepCaveatIcon`. No new UI. |
| `6c84238` | `stagingSparse` asked of every block, not just the main one | conflict: `SleepScreen.kt` | **PORTED** — same gate, correctness half |
| `897adce` | H9 low-confidence note (high efficiency, implausibly little deep+REM) | conflict: `SleepScreen.kt` | **SKIP** — a brand-new warning line, §0.2. Offer before ever taking it. |
| `8f7e2bd` | Health tile says *why* HRV is blank after an R-R over-count | conflict: strings only | **SKIP** — new caption in an empty slot, §0.2. Genuinely answers an open question, so offer it; do not take it unasked. |
| `158a05a` | Optimal Effort band drawn on the Today ring | clean | **SKIP** — a new mark on a hero the owner asked to keep bare, §0.2 |
| `df3634d` | Alarms count down to the next guaranteed wake | conflict: strings | **SKIP** for now — alarms are not in use |
| `dea107f` | Body-clock dial radii + hour labels | clean | **SKIP** — Body clock is a hidden Sleep section here |
| `b97d927` | Softer gauge numerals | conflict: strings | **SKIP** — cosmetic, fork owns its gauges |
| `a693306` `78ea2da` | Lab CSV import, Lab Book marker precision | — | **SKIP** — Lab Book removed from the fork |
| `3659bdf` `654f451` | Oura notification mask, Oura watchdog parity | — | **SKIP** — no ring |
| `946e89b` `5c0ca1d` `a670268` | 5/MG strap rename probe + log redaction | — | **SKIP** — cosmetic on a strap that already works |
| `d8f2ac4` | Changelog wording | — | **SKIP** |
| build bumps ×7 | `build: testing build NNN` | — | **SKIP** — version churn only |
