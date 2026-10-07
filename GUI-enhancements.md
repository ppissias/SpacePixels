# GUI Enhancements

Suggestions for the SpacePixels desktop GUI, collected on 2026-10-07. They are based on the main-window screenshot (`docs/images/user-interface-04-04.png`) and on the GUI code; the Detection Settings tabs and the progress dialog were reviewed in code only.

## What already works well

- The dark theme is clean, and the frame table shows what matters before a run: mono or colour, size, UTC time, exposure, solved state.
- Tooltips are thorough: almost every setting explains what it does and in which direction to move it.
- The Standard and Iterative pipelines, the per-frame preview and the manual inspection fit how people explore data.

## Recommendations

Ordered by value, most valuable first.

### 1. Arrange the main window by workflow

**Status: implemented** (2026-10-07) in `MainApplicationPanel`, with `WrapLayout` for wrapping. All four groups have the same height, so the status lines line up. The detect status reads "Ready: Auto-Tune or Detect"; the "Balanced, auto-tuned" settings summary comes with recommendation 3. The status bar does not yet suggest the next step, because the group status lines already do.

**Today:** two rows with eleven equally weighted controls (Plate Solve, two solver checkboxes, Blink Selected, Batch Convert to Mono, Batch Stretch, Detect on Selected Frame, Manual Transient Inspection, the two Detect buttons). A new user cannot tell what comes first or which action matters.

**Suggestion:**

- Group the controls in steps: **1 Prepare** (Convert to Mono, Stretch), **2 Astrometry** (Plate Solve and solver choice), **3 Inspect** (Blink, preview a frame, manual inspection), **4 Detect** (Standard, Iterative).
- Make "Detect Moving Targets" the visually primary button.
- When a button is disabled, say why in its tooltip, for example "import monochrome frames first".

**Proposed layout:** a workflow strip of four labelled groups above the frame table, left to right in the order of use. The existing controls and their enabling rules are reused.

```
┌ 1  Prepare ───────────┐ ┌ 2  Astrometry ────────────────┐ ┌ 3  Inspect ───────────────────────┐ ┌ 4  Detect ─────────────────────────────┐
│ [Convert to Mono]     │ │ [Plate Solve Selected]        │ │ [Blink Selected] [Preview Frame]  │ │ [ ▶ Detect Moving Targets ]  (primary)  │
│ [Batch Stretch]       │ │ Solver: (•) ASTAP ( ) Online  │ │ [Manual Transient Inspection]     │ │ [Detect Iteratively (large datasets)]   │
│ ✓ monochrome          │ │ 2 / 18 solved                 │ │ 3 frames selected                 │ │ Settings: Balanced, auto-tuned  [Edit…] │
└───────────────────────┘ └───────────────────────────────┘ └───────────────────────────────────┘ └─────────────────────────────────────────┘
┌──────────────────────────────────────────────── frame table (as today) ─────────────────────────────────────────────────────────────┐
```

- **1 Prepare:** Convert to Mono and Batch Stretch, with a status line ("✓ monochrome" or "colour frames: convert first"). Convert is greyed out with a reason when the frames are already monochrome.
- **2 Astrometry:** Plate Solve for the selected frame, the solver choice as radio buttons (today two checkboxes for one choice), and a status line ("2 / 18 solved").
- **3 Inspect:** Blink Selected, Preview Frame (today "Detect on Selected Frame", which is a preview) and Manual Transient Inspection, with the number of selected frames.
- **4 Detect:** "Detect Moving Targets" as the primary button (accent colour, larger), "Detect Iteratively" below it as a secondary button, and a one-line summary of the active settings ("Balanced, auto-tuned" or "manual") with an **Edit…** link to the Detection Settings tab (connects with recommendation 3).
- Every disabled button explains why in its tooltip ("select one frame", "select at least two frames", "import monochrome frames first", "already solved").
- The status lines use only what is already known (header data, selection, solved flags); there is no extra scan.
- A right-click menu on the table for the frame actions (Blink, Preview, Plate Solve, open in the stretched viewer).
- Keyboard shortcuts for the frequent actions, for example Ctrl+D Detect, Ctrl+B Blink, Ctrl+P Preview.
- On narrow windows the groups wrap onto two lines instead of being cut off.
- The status bar suggests the next step ("Next: Auto-Tune or Detect"; "Colour frames: convert to mono first").

Implementation: mainly the toolbar layout of `MainApplicationPanel` (four titled panels instead of two rows, same button objects and enabling logic), a helper for disabled-reason tooltips, radio buttons for the solver, and the table context menu. About 200 lines; no change to the processing code.

### 2. Session summary and frame quality on the main screen

**Status: deferred.** The HTML report next to the dataset already holds the frame quality table, the rejected frames with reasons and the drift diagnostics, and the pipeline now rejects blank and failed-registration frames by itself. The only part still worth considering is a one-line summary from the FITS headers already read on import (frame count, size, exposure, time span) with a warning when the timestamps show frames from more than one night, which the pipeline does not catch.

**Original suggestion, today:** the table lists per-frame metadata, but there is no session overview, and frame problems only show up in the report after a long run.

**Suggestion:**

- Above the table, a one-line summary, for example: `18 frames · 2604×1738 · 30 s · 2 h 48 m span · 2/18 solved · drift 116 px · 2 blank frames`.
- After a quick quality pass, per-frame columns for FWHM, star count and background, and a flag for blank or failed registrations. Problems such as blank frames (Africano, Eagle-noisy), frames from another night (klangwolke-1) or dark first frames (Apophis, whynot) become visible before detection.
- Hide columns that are almost always empty (Location, Loc Link) until they hold data.

### 3. Make Auto-Tune the front door of Detection Settings

**Today:** 10 tabs with about 100 settings. The tuning profile and tuner combos sit in the bottom bar, mixed with five other buttons, and the tuning result is shown only in a dialog.

**Suggestion:**

- An "Auto-Tune" box at the top of the Basic Tuning tab with the profile, the tuner, the run button, and the **last result shown permanently**: profile, measured false-detection rate, recovered share of synthetic sources, SNR50, and the **expected number of false detections for this session** (budget × megapixels × frames). This figure warns before `Maximum` is used on a large sensor (for example, about 7,000 on a 61-megapixel, 33-frame session).
- Highlight the fields the tuner changed, so the user sees what it decided.
- Keep the other tabs as an advanced area.

**Key point:** one calibrated Auto-Tune run already measures all four profiles (`CalibratedAutoTuner.calibrate()` fills `chosen[]` for Conservative, Balanced, Aggressive and Maximum). The GUI can therefore show all four side by side after a single run, and the user picks one without re-running. This needs a small JTransient change that returns the whole `Calibration`.

**Proposed layout:** the 10 tabs become a navigation list on the left, and its first page, **Overview**, is the front door.

```
┌ Detection Settings ──────────────────────────────────────────────────────────────────────────┐
│ ▸ Overview          │ AUTO-TUNE                                       Tuner: [Calibrated ▾]  │
│                     │ Last run: Africano, 18 frames, 2604×1738 (4.5 MPix), 2 min   [▶ Run]   │
│ DETECTION           │ ┌────────────┬──────────────┬────────────────┬─────────┬──────┬──────┐ │
│   Object extraction │ │ Profile    │ σ / grow / px│ false/MPix/fr  │ expected│recov.│ SNR50│ │
│   Streaks           │ │ Conservat. │ 4.5/3.25/12  │ 0.04           │    3    │ 41 % │ 6.1  │ │
│   Quality control   │ │●Balanced   │ 3.5/2.25/8   │ 0.18           │   15    │ 58 % │ 4.4  │ │
│ MOVING OBJECTS      │ │ Aggressive │ 3.0/1.75/5   │ 0.55           │   45    │ 67 % │ 3.6  │ │
│   Linking           │ │ Maximum    │ 2.75/1.5/3   │ 3.1  ⚠         │  250    │ 74 % │ 3.1  │ │
│   Anomalies         │ └────────────┴──────────────┴────────────────┴─────────┴──────┴──────┘ │
│   Slow movers       │ [Use selected profile]  [Measurement report…]  [Preview on frame…]    │
│   Residual analysis │                                                                        │
│ VARIABLE STARS      │ CORE SETTINGS  (● = set by Auto-Tune)                                   │
│ REPORT              │ Detection sigma [3.50]●  Grow sigma [2.25]●  Min pixels [8]●           │
│                     │ Master sigma [3.00]●     Mask overlap [0.75]●                           │
│                     │                                                                        │
│                     │ ANALYSES IN THIS RUN                                                   │
│                     │ ☑ Moving objects & streaks     always on                    Settings › │
│                     │ ☑ Slow movers                  comets, slow asteroids       Settings › │
│                     │ ☑ Anomaly rescue               single-frame flashes         Settings › │
│                     │ ☐ Residual analysis            leftover local activity      Settings › │
│                     │ ☐ Variable-star photometry     light curves + VSX match     Settings › │
├─────────────────────┴────────────────────────────────────────────────────────────────────────┤
│ ● Unsaved changes (applied to this session)                 [Revert]  [Load defaults]  [Save] │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

(The numbers are illustrative.)

- **Auto-Tune box:** the result stays on the page and is saved with the configuration, so it survives a restart.
  - The **expected** column is the number of false detections for this session: the measured rate × sensor megapixels × frames. It uses the measured rate rather than the budget.
  - ⚠ marks a profile where no setting met the budget, so the cleanest setting was used.
  - The legacy tuner shows a single row.
- **Core settings:** this replaces the Basic Tuning tab. It holds the five values the tuner sets; ● means "set by Auto-Tune", and its tooltip shows the previous value. Editing a value by hand removes the ●, and the box notes "manual changes since last tune".
- **Analyses in this run:** the run shown as features to switch on, each with a one-line purpose and a link to its detailed page.
- **Footer:** recommendation 4 (see below).

**Further improvements to the settings:**

1. Mark values that differ from the defaults, with a reset per field and a "Show only changed" filter.
2. A search box above the navigation list that filters settings by name or tooltip.
3. Expert blocks collapsed by default: the readiness checks A/B/D and the candidate vetting fields in Variable Stars, and the rhythm and velocity fields in Linking.
4. A shorter navigation: 10 tabs become 4 sections. Quality Control moves under Detection, and Advanced Visualization becomes Report.
5. "Preview on frame" sits next to Auto-Tune: tune, then check the result on one frame.
6. Show what a run will skip, for example "skipped in iterative mode" on the photometry line.

**Variable stars and astrometry.** A plate-solved frame gives the report sky positions. With them, moving objects are identified against JPL and SkyBoT, and variable-star candidates are matched against AAVSO VSX. Variable-star detection is off by default today and lives in the 9th tab.

- **Main window, Detect group:** a "Variable-star photometry" checkbox under the Detect button. Until a frame is solved, its status reads "VSX matching needs a plate-solved frame".
- **Main window, Astrometry group:** when nothing is solved, the status says what solving gives you: "Solve a frame to identify asteroids (JPL/SkyBoT) and variable stars (VSX)".
- **Overview page:** the photometry line says what it produces ("light curves of up to N stars, candidates matched against VSX").
- **After the run** (part of recommendation 6): "18 tracks · 2 anomalies · 3 variable-star candidates (1 known VSX variable)", with buttons to open the report or its folder.

**Implementation order:**

1. The Auto-Tune box with the all-profiles table, and the footer (recommendation 4). Split `DetectionConfigurationPanel` along the way. **Done** (2026-10-07):
   - `AutoTuneOverviewPanel` replaces the Basic Tuning tab, and the star-mask settings moved there from Object Detection.
   - JTransient now returns the full `Calibration` and offers `CalibratedAutoTuner.configFor(...)`.
   - The tune result stays in memory for the imported session only. It is not saved with the configuration, because it describes one dataset; a per-dataset history fits recommendation 6.
2. The Analyses list, the main-window photometry checkbox and the astrometry hint. **Done** (2026-10-07):
   - The Overview ends with "Analyses in This Run", in a column beside Core Settings, so the whole page fits without scrolling. Its switches share their `ButtonModel` with the checkboxes on the detailed tabs, so they always agree.
   - The Detect group has a "Variable-star photometry" checkbox, bound to the same setting.
   - The Astrometry group reads "Solve one frame to identify objects" until a frame is solved, with a tooltip that explains what solving enables.
3. Left-hand navigation, changed-value markers, search, and collapsed expert blocks. **Done** (2026-10-07):
   - `SettingsNavigator` and `SettingRow` replace the 10 tabs with a grouped page list.
   - Search box (Ctrl+F) and "Show only changed" filter, both with match counts per page.
   - Settings that differ from their defaults are marked, with a `Reset` button each.
   - Collapsible expert sections. "Moving Objects" is now "Track Linking", and "Advanced Visualization" is now "Report Visualization".
4. The post-run summary.

### 4. Remove friction around Apply and Save

**Today:** Apply shows a modal "Settings Applied Successfully!" every time; Load Defaults uses two modals.

**Suggestion:** apply changes automatically, show a small "unsaved changes" indicator, keep Save explicit, and report confirmations in the status bar instead of pop-ups.

**Status: implemented** (2026-10-07):

- Every edit applies at once, and the Apply button is gone.
- The footer shows "✓ Saved settings in use" or "● Unsaved changes", with Revert, Load Defaults and Save.
- Save, Load Defaults and Auto-Tune no longer open pop-ups; only errors still do.

### 5. Long runs: Cancel, stage names and a time estimate

**Today:** progress now only moves forward, but the progress dialog has no Cancel button, and a large session runs for several minutes.

**Suggestion:** add a Cancel button, show the current stage (extraction, linking, photometry, report), and an estimate of the remaining time. Report generation (90-100%) could also report intermediate steps.

### 6. Results summary and run history

**Today:** after a run, SpacePixels offers to open the HTML report.

**Suggestion:**

- A results summary in the application: tracks found, anomalies, unclassified detections, and the settings or profile used, with buttons to open the report or its folder.
- A per-dataset run history (date, tuner, profile, key counts), so several profiles can be compared directly. This makes a built-in version of the tuner comparison table in `02_spacepixels-test-data/tuner-comparison-2026-10-07.md`.

### 7. Smaller points

- The variable-star photometry switch is in the 9th tab. A checkbox next to the Detect buttons ("also run variable-star photometry"), noting that iterative mode skips it, would make the feature visible.
- The Auto-Tune button tooltip still says it "mathematically sweeps settings to find the optimal signal-to-noise ratio". It should describe the calibrated tuner: it measures false detections and sensitivity and picks the most sensitive settings within the profile's budget.
- `DetectionConfigurationPanel` is about 1,400 lines (also noted in `REFACTOR.md`). Splitting it into one class per tab plus a binding layer would make recommendations 1, 3 and 4 easier to implement.

## Chosen for implementation

Recommendations 1 (main window by workflow), 3 (the Auto-Tune box with the expected-false-detections figure) and 4 (Apply and Save without friction). Recommendation 2 is deferred (see above).
