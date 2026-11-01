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

**Design-improvements cycle (2026-10-24, branch `feature/design-improvements`).** Done and independently reviewed: SP-20, SP-02, SP-09, SP-04, SP-10, SP-13, SP-06, SP-05, SP-07, SP-08, SP-11, SP-14, SP-16 and SP-19 (completed 2026-10-25). SP-04's last decision, the few-frame rule, then moved into JTransient (2026-10-25). Their results are described in DEVELOPER.md and HIGH_LEVEL_DESIGN.md, and the completed items are removed here. Only the separate dependency follow-ups SP-21 and SP-22 remain from that cycle. SP-24 (2026-11-01) is new: a review of the display stretches, made after the whole-field report pictures moved to an asinh stretch, and is now implemented and kept here for review.

| ID | Change | Priority | Size |
|---|---|---|---|
| [SP-21](#sp-21-upgrade-nom-tam-fits-after-the-dither-fix) | Upgrade nom-tam-fits after the dither fix | Low | Small |
| [SP-22](#sp-22-upgrade-gson-to-2150) | Upgrade Gson to 2.15.0 | Low | Small |
| [SP-24](#sp-24-display-stretches-one-robust-automatic-stretch-and-one-curve) | Display stretches: one robust automatic stretch and one curve (Implemented) | Medium | Medium |

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

**Note: Implemented (2026-11-01; kept here for review).** This is a review of every place where the application turns pixel data into a picture. It was made after the whole-field report pictures moved to the enhanced stack with an automatic asinh stretch ([F18 in FEATURE-REQUESTS.md](FEATURE-REQUESTS.md#f18-enhanced-stack)). The code has four kinds of display stretch, three ways to find the black and white points, and several copies of the same curves. The automatic stretch of the report cutouts is thrown off by bright stars and by the empty part of a cutout at the frame edge. Unlike most items here, the proposal changes report pictures on purpose; that is a behaviour decision for the maintainer.

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

#### Detailed review (Codex, 2026-11-01)

**Assessment.** The duplicated curves and sensitivity of mean/standard-deviation levels are real. Sharing the numerical transforms and improving the ordinary cutout estimator are worthwhile. The proposed universal automatic policy needs further specification before implementation: a common curve does not make all level estimators, image sources and comparison rules interchangeable. The findings below review the proposal against commit `a52ed3d`; they do not approve or implement its behaviour changes. P1 identifies a contract that must be resolved before the affected step; P2 identifies a scope, compatibility or validation gap.

1. **P1 — A fixed sky level is not always achievable with the proposed asinh curve.** In [DisplayImageRenderer.asinhLevels / softening](src/main/java/eu/startales/spacepixels/fits/DisplayImageRenderer.java), black is `median - 2 * noise` and white is the maximum. For normalized input `x`, `asinh(x / a) / asinh(1 / a)` is at least `x` on `[0, 1]`: it lifts intermediate levels. If the sky already lies above 0.15 of that range, adjusting softening cannot bring it down to 0.15. The existing code explicitly chooses an almost linear curve in that case; [DisplayImageRendererTest](src/test/java/eu/startales/spacepixels/fits/DisplayImageRendererTest.java) already acknowledges that fallback.

   A compiled probe of the current renderer used ten valid float samples: three each at 986.51, 1000 and 1013.49 ADU, and one at 1060. The median is 1000 and the estimated noise is approximately 20. Black is approximately 960, white is 1060 and the sky occupies 0.40 of the range. The rendered sky is **102/255**, rather than the proposed **38/255**. This is a relevant case for a small crop without a bright star. A non-flat crop with nine values at 1000 and one at 2000 has MAD zero; its sky renders at **0/255**. These are existing fallback behaviours, not new regressions introduced by SP-24.

   **Required clarification:** define the white-point policy and the fallback for unreachable targets, zero MAD, too few valid samples and source-dominated crops. Either permit a documented fallback or change the range/curve policy deliberately. “Sky at a fixed grey level” cannot be an unconditional acceptance criterion for the current algorithm. Median/MAD is also not necessarily a sky estimate when most of a crop is occupied by a source or a strong gradient.

   **Second review (2026-11-01):** Agree. In `DisplayImageRenderer.asinhLevels`, when `max` is close to `sky` (e.g. a quiet single-frame crop without a bright star where the peak is 5σ above sky), `skyPosition` is 2 / (2 + 5) ≈ 0.286 > 0.15. Because `asinh(x/a)/asinh(1/a)` is concave on [0, 1], `softening` hits its linear ceiling (`1e3`) and renders the sky bright grey instead of 15 %. If Step 2 adopts asinh for cutouts, the white point needs a minimum span in sky sigmas (e.g. `Math.max(actualMax, sky + whiteSigma * noise)`) or cutouts should retain the sigma-bounded range.

2. **P1 — Excluding padding needs an explicit validity contract; changing three estimators is insufficient.** [robustEdgeAwareCrop](src/main/java/eu/startales/spacepixels/report/TrackVisualizationRenderer.java) returns only a `short[][]` and writes `Short.MIN_VALUE` outside the frame. That is **0 in unsigned ADU**, not Java short value 0, which represents 32768 ADU here. Once only the crop array remains, a genuine zero-valued pixel and synthetic padding have the same encoding. Registration fill also includes near-floor values and interpolated boundaries; excluding every zero, or merely every nonfinite value, does not implement the promised distinction. JTransient's existing `FrameCoverage` / `DataArea` logic already distinguishes edge-connected fill from interior dark pixels; any display-side support should use an explicitly agreed interpretation of that coverage without changing the detection area.

   The probe above rendered the same valid samples with twenty additional finite-zero fill samples: the sky moved from **102 to 241**. Marking those twenty samples NaN preserved the original levels and sky value. This demonstrates why a robust estimator alone cannot substitute for validity. Even the proposal's quarter-padding table still changes the median/MAD sky level from 77 to 96; it illustrates robustness, not successful exclusion of padding.

   **Required clarification:** carry crop bounds or equivalent valid support into level estimation and drawing, and specify how registration support is obtained for frames versus stacks. Invalid pixels must remain black even when black is negative. [PreviewThumbnails.frame](src/main/java/eu/startales/spacepixels/detection/PreviewThumbnails.java) also averages fill into boundary blocks today; correct levels alone would leave those blocks artificially dark. Define valid contributors for those averages as part of the change. This is wider than the three local substitutions suggested in step 1 and need not require a full-frame Boolean mask per crop.

   **Second review (2026-11-01):** Agree. `EnhancedStack` already uses `stored > DataArea.FILL_THRESHOLD` (`Short.MIN_VALUE + 8`) to match JTransient's fill threshold; the 16-bit display estimators, `PreviewThumbnails.frame` block averages/sampling, and crop bounds can use the same validity rule and keep invalid pixels at display 0.

3. **P1 — Preserve the distinct comparison contracts when sharing the curve.** [PhotometryReportSectionWriter.matchedCutouts / sharedStretch](src/main/java/eu/startales/spacepixels/report/PhotometryReportSectionWriter.java) subtracts each crop's local sky, uses one span from the brightest crop and multiplies the faintest signal by `10^(0.4 * (Z_faint - Z_bright))` when zero points are usable. Its caption promises exactly that behaviour. Calling a generic automatic stretch independently on both images could make intrinsically different signals look equally bright; sharing the same raw black point could instead reintroduce different sky backgrounds. A new common transform must accept the already normalized signal/shared span, preserving the correction and its unavailable-data fallback. Share any asinh softening across the pair as well as black/white policy. The same principle applies to the existing event-frame reference for anomalies and detection-frame reference for unclassified strips.

   The [slow-mover difference map](src/main/java/eu/startales/spacepixels/report/DeepStackReportSectionWriter.java) is another distinct product: it computes **`max(0, maximumStack - medianStack)`**, then thresholds at that positive-only distribution's mean plus two standard deviations and draws excess in red. It is not a normal sky image or a signed residual image. Replacing its estimator with median/MAD can make the threshold collapse to zero because most pixels are zero. For 90 zeros, nine values of 1 and one value of 40, the current threshold is approximately **8.45** and one pixel is red; median plus twice scaled MAD is **0** and ten pixels become red. That is a change in the diagnostic's meaning, although it does not affect candidate acceptance.

   **Required clarification:** consolidate the curve separately from these normalization/threshold policies. Keep the difference threshold and colour semantics unless their change is separately justified. “One automatic stretch for everything” needs these explicit exceptions; neither difference-map thresholding nor photometric flux normalization is duplicated generic level estimation.

   **Second review (2026-11-01):** Strongly agree. `matchedCutouts` is a calibrated flux comparison (`(val - localSky) * 10^(0.4 * dZ) / sharedSpan`) and `createSlowMoverDifferenceMap` is a positive-excess threshold over a mostly zero difference image. Only the final `[0, 1] -> [0, 255]` tone-curve evaluation should be shared with `matchedCutouts`, and the slow-mover difference map should keep its `mean + 2 * stdDev` threshold (excluding only invalid padding/fill pixels).

4. **P2 — Preserve Batch Stretch's 16-bit data contract during consolidation.** [DisplayStretch.lookupTable](src/main/java/eu/startales/spacepixels/fits/DisplayStretch.java) produces an **8-bit** display LUT. [FitsVisualizationRenderer.stretchFitsImage](src/main/java/eu/startales/spacepixels/fits/FitsVisualizationRenderer.java) writes transformed **16-bit** FITS samples. Reusing the final display LUT for Batch Stretch would reduce its output to at most 256 distinct values. The proposal allows deriving the display LUT from common code, which is the appropriate direction, but should make the precision boundary explicit.

   Share a 16-bit transform or equivalent numerical core, then derive the display LUT with the existing rounding and clipping. Preserve histogram-percentile conventions, repeated-preset quantization and the RGB Extreme rule that takes the maximum across channels. [DisplayStretchTest](src/test/java/eu/startales/spacepixels/fits/DisplayStretchTest.java) verifies mono/RGB **display** agreement across presets and slider boundaries; it does not prove that saved FITS samples and headers remain unchanged. Add that output comparison when implementing this consolidation. Floating-point viewer/Batch support remains outside this proposal unless separately requested.

   **Second review (2026-11-01):** Agree. `DisplayStretch`'s private preset methods (`enhanceHigh`, `enhanceLow`, `extreme`, `asinh`) already compute a 65,536-entry `short[]` 16-bit LUT before `lookupTable` quantizes it to `byte[65536]`. Exposing that 16-bit LUT in `DisplayStretch` lets `FitsVisualizationRenderer` map pixels through it in O(N) time while keeping Batch Stretch 16-bit exact.

5. **P2 — “The detection frame” is undefined for a multi-frame track.** [TargetVisualizationSectionWriter](src/main/java/eu/startales/spacepixels/report/TargetVisualizationSectionWriter.java) renders moving-target and streak-track animations from multiple detected points, each with its own `sourceFrameIndex`. Unlike an anomaly, a track does not supply one unique event frame. Object-centric crops also move through different backgrounds, while star-centric and wide views use other regions. A shared stretch chosen from an unrepresentative crop can hide the target in the remaining frames.

   Specify a deterministic reference selection and a fallback when that reference has little valid sky, is saturated or has zero estimated noise. Define whether the same reference is shared across the track's different views or only within each animation. Freeze the complete transform for an animation, including asinh softening; fixing black/white while solving a new curve per frame would still hide brightness changes. State whether backgrounds/transparency remain raw or are matched. A shared nonlinear display makes comparisons more consistent, but pixel brightness is not a photometric flux measurement.

   **Second review (2026-11-01):** Agree. Because a moving track crosses different background gradients and sky levels across a session, freezing raw ADU black/white levels from one frame risks clipping the sky to black or washing it out in other frames. If track animations share a stretch, subtracting each frame's local sky median while sharing a common span above sky (or keeping per-frame robust levels) is safer than raw ADU locking.

6. **P2 — Separate the cluster map's image-source change from stretch unification.** [ReportMaps.createRainbowClusterMap](src/main/java/eu/startales/spacepixels/report/ReportMaps.java) currently crops the supplied median background and estimates levels on that crop. Cropping the whole-field display changes both the source to the enhanced stack, when enabled, and the estimator's region to the full field. It will intentionally lose some local contrast adaptation; a common curve alone does not make a single frame, median stack and deeper stack show identical detail. Confirm that the cluster map is a presentation overview, while diagnostics and detection/reference comparisons keep the median as [DEVELOPER.md](DEVELOPER.md) and F18 specify. Update any affected captions and explain the fallback with enhancement off.

   Preserve the ownership rule of [DetectionReportContext.fieldDisplayImage](src/main/java/eu/startales/spacepixels/report/DetectionReportContext.java): callers must not draw on its cached picture. A `BufferedImage.getSubimage` shares the parent's raster; the probe confirmed that writing to the crop changes the parent. Draw the selected source rectangle into a separate output before adding coloured detections. Keep the current crop coordinates, aspect ratio, scaling and edge handling independently of the background change.

   **Second review (2026-11-01):** Agree. Drawing the `[minX..maxX, minY..maxY]` source rectangle of `context.fieldDisplayImage(...)` directly into the new `BufferedImage(scaledW, scaledH, BufferedImage.TYPE_INT_RGB)` via `Graphics2D.drawImage` avoids `getSubimage` raster sharing and avoids allocating an intermediate crop buffer.

7. **P2 — Keep the accepted enhanced whole-field policy intact during step 1.** “One automatic stretch” and “current square root and settings” together could be interpreted as routing enhanced whole-field images through the new median/MAD-plus-sigma renderer. That would replace the recently accepted asinh/max-white policy and can clip stellar structure at the sigma white point. Define step 1 as improving the existing mean/standard-deviation paths only; retain the enhanced-stack renderer until step 2 has an agreed policy and visual evidence. Do not replace the shared median buffer or mutate scientific inputs while preparing display samples.

   **Second review (2026-11-01):** Agree. Step 1 should only update the 16-bit mean/σ estimators (`DisplayImageRenderer.displayLevels` and `UnclassifiedTransientSectionWriter.stretchStrip`), leaving `DisplayImageRenderer.createAsinhDisplayImage(float[][])` unchanged.

8. **P2 — Decide settings semantics before implementing the changed estimator.** [VisualizationSettings](src/main/java/eu/startales/spacepixels/config/VisualizationSettings.java) publicly documents the black/white parameters in standard deviations from the **mean**; [SpacePixelsVisualizationPreferences](src/main/java/eu/startales/spacepixels/config/SpacePixelsVisualizationPreferences.java) persists them. Reusing the same values around the median with scaled MAD preserves file shape, but changes their meaning and output. Resolve whether those existing parameters become documented robust-sigma settings, remain a compatibility option, or are replaced/migrated. The asinh white-point decision in finding 1 must also resolve what White Sigma means. This cannot be left until after the new behaviour is implemented. Update UI/help, immutable run capture and old-file round-trip checks together; display changes must not migrate or overwrite detection profiles as a side effect.

   **Second review (2026-11-01):** Agree. In Step 1, interpreting `autoStretchBlackSigma` (0.5) and `autoStretchWhiteSigma` (5.0) as robust sky sigmas (1.4826 × MAD) below and above the sky median is a clean evolution—on uncorrupted Gaussian sky they are numerically equivalent to mean/σ—and requires only Javadoc and UI tooltip updates without settings-file migration.

9. **P2 — Bound the extra work at each caller.** The whole-field estimator currently takes up to two million samples and sorts them twice; thumbnails use a smaller sample of the original image. Do not estimate noise on an averaged thumbnail: averaging changes the noise distribution and would produce different levels simply because thumbnail size changed. Specify sampling of original valid pixels, bounded temporary storage and reuse of immutable levels for a shared comparison, without retaining extra frame buffers. Preserve the 65,536-entry interactive LUT approach rather than evaluating the full image on each slider change.

   [RunPreviewScope.offer](src/main/java/eu/startales/spacepixels/detection/RunPreviewScope.java) asks whether a picture is wanted **before** making it. Keep estimation/rendering inside that admitted work, preserve the live view's existing few-second limit and detached buffers, and keep cancellation responsive. Time a complete report with many crops and animation frames, plus Auto-Tune/live view enabled and hidden; measuring one whole-field render on a 61-megapixel image would miss the potentially dominant repeated work.

   **Second review (2026-11-01):** Agree. Note also that for 16-bit `short[][]` images/crops, there are only 65,536 possible values: exact median and MAD over valid pixels can be found in O(N) from a 65,536-bin histogram (or a small array sort for small cutouts) without sorting large sample arrays.

10. **P2 — Correct the proposed equality check and extend the visual acceptance criteria.** [ReportData](src/main/java/eu/startales/spacepixels/report/ReportData.java) writes `generatedAtUtc`, so `report-data.json` cannot literally remain byte-identical across separate runs. Follow [DEVELOPER.md's real-data comparison rules](DEVELOPER.md#real-data-regression): normalize only explicitly identified volatile fields, then require identical scientific content, finding identities, frame selections, `detection_config.json` and CSV values. Display settings are stored separately; changing them is not a reason to relax detection-output equality. For the curve/preset consolidation alone, require identical rendered pixels and saved Batch Stretch samples too. Only the explicitly selected estimator/curve/source changes should have different images.

   Add cases for more-than-half invalid support, valid interior zero/dark pixels, near-floor registration fill, all-invalid data, zero MAD with a real source, negative finite float values, infinity, gradients, saturated sources and crops with little sky. Assert that adding invalid padding leaves valid-region levels unchanged. Test brightness comparisons with a genuinely varying source, a constant source under changing transparency, unavailable zero points and a poor proposed reference frame. Check PNGs and GIF poster/frame-sheet playback, annotations on independent buffers, enhancement on/off and the saved-settings round trip. Inspect edge and bright-star examples on the relevant reference datasets, including variable-star data, rather than relying only on the illustrative table.

   **Second review (2026-11-01):** Agree.

11. **P2 — Sub-region vs. full-frame statistics in `FitsVisualizationRenderer` previews (Second review, 2026-11-01).** In [FitsVisualizationRenderer](src/main/java/eu/startales/spacepixels/fits/FitsVisualizationRenderer.java), `stretchImageAsinh` builds its histogram over the full `sourceWidth × sourceHeight` frame even when rendering the 350 × 350 preview (`width < sourceWidth`), whereas `stretchImageEnhanceHigh`, `stretchImageEnhanceLow` and `stretchImageEnhanceExtreme` compute `minimumValue`, `maximumValue` and `averageNoiseLevel` only over the top-left `width × height` preview sub-region. Meanwhile, [DisplayStretch.lookupTable](src/main/java/eu/startales/spacepixels/fits/DisplayStretch.java) always uses the full-frame histogram. Consolidating `FitsVisualizationRenderer` onto `DisplayStretch`'s full-frame histogram LUT will make the 350 × 350 Image Stretch preview match the full-size viewer for all four presets, not just Asinh; note this intentional preview alignment when testing 350 × 350 previews on frames larger than 350 × 350.

12. **P2 — `PhotometryReportSectionWriter.localSky` also includes edge padding (Second review, 2026-11-01).** In [PhotometryReportSectionWriter.localSky](src/main/java/eu/startales/spacepixels/report/PhotometryReportSectionWriter.java), the brightest and faintest cutouts come from `robustEdgeAwareCrop`, and `localSky(short[][] crop)` takes the median of all pixels in the cutout, including `-32768` (0 ADU) padding or registration fill when a candidate star lies near the frame border. While keeping the photometric flux-scaling contract of `matchedCutouts` (finding 3), `localSky` and the `white` peak scan should also exclude invalid/fill pixels so border variable-star cutouts keep accurate local sky subtraction.

**Recommended sequence.** First consolidate the interactive transforms and reusable curve evaluation with output equivalence. Then implement validity-aware robust levels for the ordinary automatic paths while preserving their comparison rules and the enhanced whole-field renderer. Decide the asinh fallback/white policy, animation reference and settings contract before the later behaviour changes. Treat the cluster-map background switch as a separate, explicitly reviewed image-source decision. These changes can remain in SpacePixels; no detection-algorithm change in JTransient is justified by this proposal.

**Review evidence and limits.** Source inspection included the renderers, report callers, thumbnail/preview admission, persisted settings and existing display tests. A scratch Java probe compiled the current `DisplayImageRenderer` and checked actual raster values for the valid, padded, NaN and zero-MAD cases above; it also checked shared subimage storage. The positive-only difference example was calculated from the current formula. No runtime code, saved settings or reference datasets were modified. Full real-data timing and before/after visual regressions were not rerun for this proposal review and remain implementation acceptance work.

#### Decision points for the maintainer

1. **Step 1 — Robust levels for ordinary automatic stretches (cutouts, non-enhanced stacks, live view, Test Star Mask):**
   - Replace mean and standard deviation with **sky median and robust noise** (`1.4826 × MAD`), excluding out-of-frame padding and registration fill (`stored <= DataArea.FILL_THRESHOLD`) and keeping invalid pixels black (0).
   - Keep the existing `Auto Stretch Black Sigma` (0.5) and `Auto Stretch White Sigma` (5.0) settings, updating their documentation/tooltips to refer to sky median and robust sky noise instead of mean and standard deviation.
   - Keep the enhanced-stack whole-field `createAsinhDisplayImage` untouched in Step 1.
   - *Decision:* Agreed

2. **Step 2 — Cutout curve (stay with square-root + sigma range, or switch cutouts to asinh):**
   - *Option A (Recommended first):* Keep the square-root curve between `median − blackSigma × noise` and `median + whiteSigma × noise` for cutouts/thumbnails (Step 1), while whole-field enhanced stack pictures keep asinh.
   - *Option B:* Switch cutouts to asinh as well. If chosen, we must also pick the white-point rule so quiet crops without a bright star do not fall back to linear and brighten the sky (e.g. `white = Math.max(actualMax, sky + whiteSigma * noise)`), and decide whether `Auto Stretch Black/White Sigma` stay or are replaced.
   - *Decision:* Lets go with Option A

3. **Track animations (moving targets, streak tracks, slow movers, local rescue, activity clusters):**
   - *Option A:* Keep per-frame automatic levels (each frame of a track animation is stretched from its own valid pixels, so every frame is guaranteed readable even across strong gradients or clouds).
   - *Option B:* Share one stretch across a track animation by subtracting each frame's local valid sky median and sharing one span above sky (e.g. from the median of the frames' noise/spans or the brightest detection frame), so target brightness changes stay visible without background gradients clipping the sky.
   - *Option C:* Freeze one raw ADU black/white range from a single reference frame (specify which frame).
   - *(Note: Anomaly Before/Event/After and Unclassified Transient strips already share their event/detection frame's stretch and will continue to do so.)*
   - *Decision:* Lets go with option B, If I understand correctly we will still have a fixed stretch for all frames, we just discover it in a different way than Option C. Please let me know if this is not the case and ask for an updated decision. *(Clarified and agreed: fixed `[black, white]` ADU levels across all frames of each animation, discovered from the median of the frames' sky medians and robust sky noises.)*

4. **Special comparison and diagnostic images (Variable Stars & Slow-Mover Difference Map):**
   - **Variable-star brightest/faintest cutouts:** Keep their calibrated flux comparison (`(val − localSky) × 10^(0.4 × ΔZ) / sharedSpan`), only excluding invalid padding/fill pixels when computing `localSky` and `white`, and sharing the tone-curve helper.
   - **Slow-mover `Maximum − Median` difference map:** Keep its positive-only `mean + 2 × stdDev` red threshold (excluding only invalid padding/fill pixels), rather than median/MAD which collapses to zero on mostly-zero difference images.
   - *Decision:* Agreed.

5. **Transient Cluster Map background (`ReportMaps.createRainbowClusterMap`):**
   - *Option A:* Crop a copy of `context.fieldDisplayImage(...)` (so it shows the enhanced stack with asinh when Enhanced Stack is on, matching the Exact Footprint Map beside it, and falls back to the median stack when off).
   - *Option B:* Keep it on the locally stretched median-stack crop.
   - *Decision:* Option A

6. **Interactive viewer presets & `FitsVisualizationRenderer` consolidation:**
   - Consolidate the four 16-bit preset transforms (`Asinh`, `Enhance Low`, `Enhance High`, `Extreme`) into `DisplayStretch` so Batch Stretch, the Image Stretch tab preview, and the full-size viewers share one 16-bit implementation, using the full-frame histogram for the 350 × 350 tab preview across all four presets.
   - *Decision:* Agreed

#### Follow-up after the implementation review (2026-11-01)

- **New defaults: Black Sigma 2, White Sigma 20.** On real crops of six reference datasets, the old standard deviation was 2.4 to 25 times the robust sky noise, because it included the stars and gradients. With White Sigma 5 the robust white point clipped most stars to white and spread the sky noise over the grey range. With Black Sigma 0.5 about 31% of the sky was clipped to black, which left a grainy sky even at White Sigma 20; at Black Sigma 2 about 2% is, and faint streaks stay visible. The maintainer chose 2 and 20; the settings page allows White Sigma up to 100. Saved visualization files keep their values; the maintainer decided against migrating them, since the change was not yet released.
- **Track animations hold one crop at a time.** The shared levels are measured from crops made one by one, and each crop is made again to draw it, so a long track with frame-wide crops does not hold all of them.
- **Batch Stretch is pinned** by a checksum test (`DisplayStretchTest.batchStretchKeepsItsOutput`) recorded from the per-pixel code before the stretch tables.

## Removed from the previous plan (2026-10-23)

- **S16, prepared diagnostic states and re-export.** Dropped. No code path renders a saved result again, so nothing would read the prepared states twice.
- **The rest of D09 and D10.** Done. The selected-release check is part of the release procedure in DEVELOPER.md, and the engine side is JTransient JA-11.
- **D12, shared visual rules.** Reduced to SP-16.
- **The target architecture map, the pattern table, the ownership table and the "constraints" list.** What still applies is in DEVELOPER.md, as principles, patterns and things to avoid.
- **The completed-work archive and the request register.** Removed. Git history keeps them.
- **SP-15, pass the live-view sink with the run.** Dropped (2026-10-25). `RunPreviewScope` stays: the job opens and closes it, a headless run passes no sink, and the report image savers offer a picture without a parameter. Revisit only if report images are written on another thread.
