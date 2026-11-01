# Design improvements

This file lists design, refactoring and code-quality work for SpacePixels: changes to how the code is organised, not to what the application does. New or changed functionality is in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md). The current design is described in [DEVELOPER.md](DEVELOPER.md) and [HIGH_LEVEL_DESIGN.md](HIGH_LEVEL_DESIGN.md).

Every item here is a proposal until the maintainer decides on it. When an item is done, describe the result in DEVELOPER.md or HIGH_LEVEL_DESIGN.md and remove it from this file; git history keeps the record.

**How to use the issue reviews (2026-10-24).** Before implementing an item, consider and evaluate the review comments at its end against the current code. Record which comments are accepted, adjusted or rejected, why, and the relevant verification with the change. They are review input, not authorization to implement the proposal or automatic proof of a current bug. The review was static; no runtime or real-data checks were run. Recheck references and caller counts as other work lands.

## Code review of 2026-10-23

A fresh review of the whole application (about 50,000 lines of main code and 17,000 of tests), against the principles in [DEVELOPER.md](DEVELOPER.md#principles): readable code, a clear design with clear boundaries, and a few chosen patterns applied consistently, with no abstraction that lacks a real need.

**Overall.** The architecture is sound. Jobs, commands, the session handover, the viewers and the public API are small, well-bounded and consistent, and should stay as they are. The over-engineering the maintainer suspected is real, but it is concentrated in three kinds of place:

1. **State with more than one owner.** The rendering preferences and the session's frame list each lived in two or three places. Each copy then needed machinery to stay in step: write barriers, version checks, rollback, static caches and "capture" steps. Giving each piece of state one owner removes that machinery rather than adding to it. Both now have one owner (SP-02 and SP-04, 2026-10-24).
2. **Layers kept for compatibility after compatibility stopped mattering.** The libraries are now developed together with the application, and old signatures need not survive. Still left over are chains of overloads that exist for older callers.
3. **Mechanisms heavier than their use.** Examples were two near-identical write queues for small JSON files, now one (SP-09), and a binding framework whose application side wrote to global static fields, now direct bindings without client-property indirection (SP-02 and SP-07).

Line counts alone are not a problem. Large report section writers and panels are acceptable when they read top to bottom as one responsibility.

### What is good and should stay

- **Jobs** ([JobCoordinator](src/main/java/eu/startales/spacepixels/jobs/JobCoordinator.java), `Job`, `JobOutcome`): one admission rule, a sealed typed outcome, exactly one end per job, and no Swing. It is the right size.
- **Commands** ([CommandAvailability](src/main/java/eu/startales/spacepixels/gui/CommandAvailability.java), `CommandAction`): pure rules over a `Facts` record, one Swing `Action` per command, re-checked when invoked. This is a clear and testable pattern; use it for any new command.
- **Session handover** (`SessionOwner.replace(expected, candidate)`): simple, explicit and tested without windows.
- **Viewers** (`SequenceViewerFrame` template method, newest request wins, `FrameView`, `StretchControls`, `ViewerSupport`): good reuse by composition.
- **Typed detection setting definitions** (one table of `DetectionSetting` per settings page): explicit and searchable.
- **Staged file publication** (`StagedFile`, `AtomicReportFile`), and **boundary tests** (`DependencyRulesTest`, the opt-in GUI mechanics suite).
- **The public API** (`SpacePixelsPipelineRequest` / `Result`, `DefaultSpacePixelsPipelineApi`): a request object in, a result object out, with no GUI.

### Recommended changes

**Design-improvements cycle (2026-10-24, branch `feature/design-improvements`).** Done and independently reviewed: SP-20, SP-02, SP-09, SP-04, SP-10, SP-13, SP-06, SP-05, SP-07, SP-08, SP-11, SP-14, SP-16 and SP-19 (completed 2026-10-25). SP-04's last decision, the few-frame rule, then moved into JTransient (2026-10-25). Their results are described in DEVELOPER.md and HIGH_LEVEL_DESIGN.md, and the completed items are removed here. Only the separate dependency follow-ups SP-21 and SP-22 remain from that cycle. SP-24 (2026-11-01) is new: a review of the display stretches, made after the whole-field report pictures moved to an asinh stretch.

| ID | Change | Priority | Size |
|---|---|---|---|
| [SP-21](#sp-21-upgrade-nom-tam-fits-after-the-dither-fix) | Upgrade nom-tam-fits after the dither fix | Low | Small |
| [SP-22](#sp-22-upgrade-gson-to-2150) | Upgrade Gson to 2.15.0 | Low | Small |
| [SP-24](#sp-24-display-stretches-one-robust-automatic-stretch-and-one-curve) | Display stretches: one robust automatic stretch and one curve | Medium | Medium |

Keep dependency maintenance (SP-21 and SP-22) separate from refactorings, according to each item's timing.

Refactorings are intended to preserve behaviour. Evaluate the exceptions raised by the issue reviews, including SP-19's viewer lifetime and settings policy, as explicit behaviour decisions. Check implementation changes with the relevant unit and GUI mechanics tests. Items that touch detection or reports also need byte-identical deterministic `report-data.json`, `detection_config.json` and CSV content on the reference datasets, using the normalization and comparison method in DEVELOPER.md.

### SP-21. Upgrade nom-tam-fits after the dither fix

**Deferred until the upstream fix is released.** Follow-up to the completed [SP-18 upgrade](DEVELOPER.md#dependencies). Upgrade nom-tam-fits to 1.23.1 when available, after confirming the fix for the dither sequence at blank and zero pixels (#908).

With 1.23.0, dithered floating-point `.fz` frames still differ from cfitsio by about one quantization step in tiles that contain blank or zero pixels: 1 ADU after SpacePixels' 16-bit conversion, on a few hundred pixels of a frame. Integer frames are exact.

**Verification.** Coordinate the upgrade across the repositories that use nom-tam-fits, repeat the comparison with cfitsio (through astropy), and run the reference-data regression in DEVELOPER.md. Report any changed pixels and detection results before committing.

### SP-22. Upgrade Gson to 2.15.0

**Deferred to the next dependency update.** Follow-up to the completed [SP-18 upgrade](DEVELOPER.md#dependencies). Upgrade Gson from 2.14.0 to 2.15.0 in all three repositories, reviewing its change of the default `Map` type to `LinkedHashMap`.

**Verification.** Round-trip saved settings files and check that their values and compatibility are preserved; run the relevant tests and reference-data comparisons from DEVELOPER.md.

### SP-24. Display stretches: one robust automatic stretch and one curve

**Proposed 2026-11-01; not decided.** This is a review of every place where the application turns pixel data into a picture. It was made after the whole-field report pictures moved to the enhanced stack with an automatic asinh stretch ([F18 in FEATURE-REQUESTS.md](FEATURE-REQUESTS.md#f18-enhanced-stack)). The code has four kinds of display stretch, three ways to find the black and white points, and several copies of the same curves. The automatic stretch of the report cutouts is thrown off by bright stars and by the empty part of a cutout at the frame edge. Unlike most items here, the proposal changes report pictures on purpose; that is a behaviour decision for the maintainer.

#### Current state

No display stretch changes what the detection or the photometry sees: both always use the linear frames. The only stretch that writes data is **Batch Stretch**.

1. **Viewer stretch, interactive.**
   - *Where:* the Image Stretch tab preview and **Show full size**, Blink and its GIF export, Preview Frame and Manual Transient Inspection. They share one setting (algorithm, two sliders and **Stretch display**), so a change in one shows in all.
   - *How:* four presets.
     - **Asinh** (the default): black at a histogram percentile (0 to 50 %, default 10 %) and white at the 99.9th percentile. The curve is asinh(s·x) / asinh(s), with the strength s from 1 to 50 (default 10).
     - **Enhance Low** and **Enhance High**: repeated brightening of the fainter or of the brighter values.
     - **Extreme**: every value more than a threshold above the mean is drawn at one bright level; the rest stay linear.

     Each frame is stretched from its own histogram. With **Stretch display** off, the view is linear over the full 16-bit range. **Batch Stretch** writes `_stretched` copies of the frames with the selected preset.
   - *Why:* looking at frames by hand; the user tunes it.
   - *Code:* the presets are written twice. `FitsVisualizationRenderer` applies them to the pixels for the tab preview and Batch Stretch. `DisplayStretch` builds a 65,536-entry lookup table per frame from its histogram for the viewers, and is written to give the same grey levels. Both handle 16-bit integer data only; their floating-point branches return nothing.
2. **Report automatic stretch: a square root between mean − 0.5σ and mean + 5σ.**
   - *Settings:* **Auto Stretch Black Sigma** (0.5) and **Auto Stretch White Sigma** (5.0), under Detection Settings → Report.
   - *How:* `DisplayImageRenderer.createDisplayImage` takes the mean and standard deviation of all pixels of the picture. Black is at mean − 0.5σ (not below 0) and white at mean + 5σ (not above the brightest pixel), followed by a square root. Every cutout gets its own levels.
   - *Where in the report:*
     - all finding cutouts and animations: single streaks, streak tracks, moving targets (object-centric, star-centric and wide frames), slow movers, and local rescue and residual reviews;
     - the mask overlays;
     - the median stack (`diagnostics/master_stack.png`) and the dither-corner animation in Pipeline Diagnostics;
     - the common-motion map;
     - the Transient Cluster Map, a crop of the median stack;
     - the whole-field pictures, when Enhanced Stack is off.
   - *Where in the application:* the progress window's live view (its frame and stack thumbnails, with levels from a sample of about 250,000 pixels drawn on a reduced copy, and its finding crops), the Auto-Tune live view and the Test Star Mask window.
   - *Shared levels for comparisons:*
     - The anomaly Before / Event / After pictures and their blink use the event cutout's levels.
     - The Unclassified Transient strips use the detection frame's levels (`stretchStrip`, which has its own copy of the computation).
     - The variable-star brightest and faintest cutouts use the local sky median as black and the brightest cutout's peak for the range; the faintest is scaled for transparency (`sharedStretch`).
   - *Levels per frame:* the track animations (moving targets, streak tracks, slow movers, local rescue and residual reviews) stretch every frame on its own.
   - *Why:* it is automatic and the same in every report, with the contrast set for each cutout.
3. **Whole-field asinh, automatic.**
   - *Where (with Enhanced Stack on):* the session picture, the Map of All Detections, the Exact Footprint Map, the variable-candidate map and the Unclassified Transient Inspector overview (tinted blue-grey there), with their full-size files.
   - *How:* `DisplayImageRenderer.createAsinhDisplayImage` works on the 32-bit enhanced stack.
     - The sky median and noise (1.4826 × MAD) come from up to two million finite pixels.
     - Black is 2 sky sigma below the sky median; white is the brightest pixel.
     - The curve is the same asinh, with its strength solved so that the sky median is drawn at 15 % of white.

     NaN is drawn black. The picture is made once per report and shared.
   - *Why:* the stack shows much fainter detail than a frame. The square-root stretch left it in a flat grey sky or clipped the stars. The Auto Stretch settings do not change this stretch.
4. **Special pictures.** The slow-mover difference map is red where the slow-mover stack exceeds the median stack by more than the difference's mean + 2σ, with a square root above that. The master mask overlay is a red mask on black, with no stretch. Elsewhere "stretch" means something else: star elongation in Night Equipment, and, in JTransient's faint-streak search, a gain about the sky before rounding that is undone afterwards.

#### Findings

1. **Three ways to find black and white.** The viewers use histogram percentiles, the report cutouts and live view the mean and standard deviation, and the whole-field pictures the median and MAD. The mean and standard deviation are not robust:
   - a bright star raises σ and the white point;
   - the empty part of a cutout beyond the frame edge (filled with 0 by `robustEdgeAwareCrop`), or registration fill, pulls the mean down and σ up.

   A worked example from the formulas: a 100 × 100 cutout of sky at 1,000 ADU with 20 ADU noise. The table gives the grey levels (0 to 255) of the sky and of a faint object 3σ above it:

   | Cutout | Mean and σ: sky | Mean and σ: sky + 3σ | Median and MAD: sky | Median and MAD: sky + 3σ |
   |---|---|---|---|---|
   | Sky only | 86 | 226 | 77 | 203 |
   | A quarter beyond the frame edge | 236 | 251 | 96 | 177 |
   | One bright star (peak 40,000 ADU) | 68 | 70 | 72 | 197 |

   Next to a bright star, a faint object is only 2 grey levels above the sky, and at the frame edge the sky is nearly white. These are exactly the findings where the user needs a readable cutout. Not yet measured on real cutouts.
2. **Two looks for the same sky.** A finding's cutout (a frame or the median stack, square root, its own levels) and the whole-field picture around it (the enhanced stack, asinh) look different. The Transient Cluster Map (median stack, square root) sits beside the Exact Footprint Map (enhanced stack, asinh).
3. **Animations differ.** Track animations stretch every frame on its own, while the anomaly and unclassified strips share one stretch. Separate levels keep each frame readable, but they hide a brightness change between frames, such as thin cloud, and make a moving object's brightness harder to compare. Neither the code nor the documentation says whether this is intended.
4. **Duplicated code.**
   - The viewer presets exist twice.
   - The square-root curve at given levels exists four times: `DisplayImageRenderer.createDisplayImageAtLevels`, `UnclassifiedTransientSectionWriter.stretchStrip`, `PhotometryReportSectionWriter.sharedStretch` and the slow-mover difference map.
   - The mean and σ are computed in three places.
   - The asinh curve exists three times (`FitsVisualizationRenderer`, `DisplayStretch` and `DisplayImageRenderer`), each with its own `asinh(double)`. The curves are the same, with the strength s = 1 / softening.
5. **The viewer's Asinh preset cannot draw a stack like the report.** On the enhanced stacks the sky lands at these grey levels:

   | Enhanced stack | Preset, strength 10 | Preset, strength 50 (maximum) | Report | Strength the report uses |
   |---|---|---|---|---|
   | klangwolke-2 | 0.8 | 2.5 | 38 | about 3,260 |
   | Integral-spacecraft-challenge | 8.5 | 26.6 | 38 | about 370 |

   A stack has much less noise than a frame, so its sky lies close to the black point compared with the whole range. The slider's range was made for single frames. The preset also clips the brightest 0.1 % of the pixels, which turns the cores of bright stars into flat discs.

#### Conclusion

Keep two stretches with clear roles, and one implementation of each piece.

- **The interactive viewer stretch, for the user.** Keep the presets, sliders and per-frame histograms; they are for looking at frames by hand. Merge the two implementations of the presets, so that the tab preview, Batch Stretch and the viewers run the same code (the lookup table can be built from it).
- **One automatic display stretch for everything the application draws on its own:** report cutouts and animations, whole-field pictures, the live view and Test Star Mask. It takes its levels from the median and MAD of the picture's valid pixels, leaving out padding, registration fill and NaN, and uses one curve. Do it in two steps:
  1. Robust levels with the current square root and settings. This fixes finding 1 and is a small change in `DisplayImageRenderer.displayLevels`, `stretchStrip` and the difference map.
  2. The asinh curve for the cutouts as well, with the sky at a fixed grey level, so that cutouts and whole-field pictures look alike (finding 2). Then decide whether Auto Stretch Black and White Sigma stay, are replaced by one sky level, or go.
- **One rule for animations.** Share one stretch within an animation, taken from the detection frame as the anomaly and unclassified strips do, unless the maintainer prefers every frame readable on its own (finding 3).
- **The Transient Cluster Map** draws a crop of the whole-field picture, like the Exact Footprint Map beside it.
- **One place for the curves and the level estimators**, in the `fits` package next to `DisplayImageRenderer`. Every copy listed in finding 4 calls it.

Related functionality, not part of this item: an **Auto** setting on the Image Stretch tab that uses the automatic stretch, so that a stack looks the same in the viewers as in the report (finding 5). It belongs in FEATURE-REQUESTS.md if wanted.

**Verification.** Add unit tests for the level estimator: sky only, padding, registration fill, a bright star, NaN and flat data. The pictures change by design, but `report-data.json`, `detection_config.json` and the CSV files must stay byte-identical on the reference datasets. Compare the pictures before and after on the reference datasets: findings at the frame edge and next to bright stars, track animations, anomalies, unclassified strips, variable-star cutouts and the live view. Measure the report time on the 61-megapixel frames.

## Removed from the previous plan (2026-10-23)

- **S16, prepared diagnostic states and re-export.** Dropped. No code path renders a saved result again, so nothing would read the prepared states twice.
- **The rest of D09 and D10.** Done. The selected-release check is part of the release procedure in DEVELOPER.md, and the engine side is JTransient JA-11.
- **D12, shared visual rules.** Reduced to SP-16.
- **The target architecture map, the pattern table, the ownership table and the "constraints" list.** What still applies is in DEVELOPER.md, as principles, patterns and things to avoid.
- **The completed-work archive and the request register.** Removed. Git history keeps them.
- **SP-15, pass the live-view sink with the run.** Dropped (2026-10-25). `RunPreviewScope` stays: the job opens and closes it, a headless run passes no sink, and the report image savers offer a picture without a parameter. Revisit only if report images are written on another thread.
