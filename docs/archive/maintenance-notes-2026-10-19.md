> Historical record, archived 2026-10-19. This preserves the review and evidence from its original dates, including superseded statements and decisions. It is not the current specification or work queue. See [AGENTS.md](../../AGENTS.md) for current guidance. Source line numbers and temporary harness paths below refer to the original session and may no longer exist.

# Notes for coding agents

Working notes for AI coding agents (Claude Code and others) on SpacePixels. Read this first. The JTransient repository has a matching `AGENTS.md` for the engine.

## Project layout

- SpacePixels is the Swing desktop app (FlatLaf) plus a Java API and CLI tools. The detection engine is the separate **JTransient** library.
- By default the build uses the published JTransient and JPlateSolve from Maven Central. The composite build with the sibling checkouts (`../JTransient`, `../JPlateSolve`) is opt-in: set the Gradle property `useLocalJTransientBuild=true` (or the environment variable `USE_LOCAL_JTRANSIENT_BUILD=true`) to pick up edits in `../JTransient` directly, with no publishing step. Engine changes need this. JPlateSolve has its own switch, `useLocalJPlateSolveBuild` (`USE_LOCAL_JPLATE_SOLVE_BUILD`).
- Main entry point: `eu.startales.spacepixels.gui.ApplicationWindow`.
- Other docs: `MANUAL.md` (user manual), `API.md`, `HIGH_LEVEL_DESIGN.md`, `REFACTOR.md`, `GUI-enhancements.md`, `PUBLISHING.md`.

## Build and test

- `./gradlew compileJava` and `./gradlew test`. Run the JTransient tests too (`../JTransient`, `./gradlew test`) whenever the engine changes.
- Headless batch run of one dataset: `./gradlew -q batchDetect -PbatchMaxHeap=11g -PbatchArgs="\"<folder>\" \"<profile.json>\""`. It prints one line, `Report file: ...` (the HTML report), plus any problems. Add `-PspacepixelsDebug` (or `-Dspacepixels.debug=true` on a plain `java` command) for the progress, per-frame statistics and the engine's debug output (`DebugOutput`; since 2026-10-17 the console shows only problems by default).
- To build a classpath for small diagnostic programs, run `./gradlew compileJava`, then rebuild the JTransient jar (`./gradlew jar` in JTransient). Put diagnostic code in a scratch folder, never in the repo.

## Rules from the maintainer

- **Never overwrite the user's saved settings**: `spacepixels_detection_profile.json` and `spacepixels_visualization.json` in the user's home directory. To run anything that loads or saves settings, start the JVM with `-Duser.home=<scratch folder>` and copy the profile there.
- The GUI also stores one Java preference, `showExpertSettings`, in node `/eu/startales/spacepixels/gui`. A tool that changes it must restore it.
- Discuss a fix before implementing it, and show its impact on real data (see the regression method below). Engine fixes go one at a time.
- Commit messages:
  - Code commits are descriptive.
  - Documentation commits use a generic message such as "Update manual".
  - Never put private details or locations in a commit message.
  - Don't rewrite history.
- Remove the observing-site location (coordinates, site names) from images and reports before publishing them, including manual screenshots.
- Refer to the maintainer and other people as they/them.
- **Console output (2026-10-17):** print only problems. Diagnostics go through `DebugOutput.println/printf` (shown with `-Dspacepixels.debug=true`), INFO goes to `java.util.logging` (hidden unless debugging), and the engine's prints stay behind `JTransientEngine.DEBUG`. Don't add plain `System.out` prints.

## Settings panel (DetectionConfigurationPanel)

The panel was simplified in October 2026 after the maintainer sorted all 115 settings into core, basic, expert, hidden and "fix".

- **Classes:**
  - `SettingsNavigator`: page list, search, "Show only unsaved changes", and the "Show expert settings" switch.
  - `SettingRow`: one setting, with its changed marker and reset button.
  - `DetectionConfigurationPanel`: builds the pages.
  - `AutoTuneOverviewPanel`: the Overview page with the tuned values.
- **Core settings:** the seven auto-tuned values plus Base Star Jitter Radius, all on the Overview page (`CORE_SETTING_TITLES`).
- **Basic settings** (visible by default, 14):
  - Single Streak Min Peak Sigma.
  - Anomaly rescue switch and peak sigma.
  - Slow movers: switch, sigma, grow sigma, min pixels.
  - Residual analysis and local rescue switches.
  - Variable-star switch.
  - Report: stretch black and white points, GIF speed, the optional AI sections.
  - Quality Control: the frame-rejection **Strictness** choice.
- **Expert sections** start with `createExpertSectionHeader(...)`; the header carries client property `EXPERT_SECTION`. They are shown only while "Show expert settings" is on, but search and the unsaved-changes filter always find them. Each page shows a hint saying how many expert settings are hidden and how many of them are changed.
- **Hidden settings** have no row in the panel. They stay in `DetectionConfig` and the profile. `applySettingsToMemory` updates the config in place, so hidden fields keep their loaded value; Load Defaults and Revert still reset them. Don't add rows for them back without asking.
- **Strictness** (Lenient / Normal / Strict) sets the five frame-outlier limits together, using the values in `frameRejectionPresetValues`. It shows "Custom" when those limits match none of the presets.
- **Dependent rows:** `showOnlyWhen(toggle, spinners...)` shows rows only while their switch is on. Used for the geometric linker, activity clusters and the bright-star eccentricity limit.
- **Profile loading:** profiles are read with Gson, which ignores unknown keys. `SpacePixelsDetectionProfileIO.needsMigration` compares key sets. When a `DetectionConfig` field is added or removed, the app offers a one-time "Migrate profile" step that keeps a backup. Also update `src/dist/config/default_detection_profile.json`.

## Real-data regression method

The reference datasets are in `C:\Users\petro\Desktop\astrophotography\02_spacepixels-test-data\`. Ignore the older date-named folders there unless asked. The datasets:

- Original seven:
  - klangwolke-1, and klangwolde-2 (use `_16bit_converted`): good data with known asteroids; klangwolde-2 is the main dataset.
  - Iwamoto-huge, Eagle-noisy, whynot-nocalibration-wrongdrift, Apophis, Africano.
- Variable-star batch:
  - dc-reg, helix-reg, ic-5070-reg, m101-reg, m31-b/g/red-reg, m5-reg, m81-reg.
  - M81-kumar-L-dataset-registered, "UG 6524-kumar-L-dataset-registeed" (spelled that way on disk).

To check a change:

1. Run every dataset with `batchDetect` on the base commit and on the change, using the same profile.
2. Compare the frames rejected, the tracks and anomalies, the variable-star verdicts and candidates star by star, and the timings.
3. Report the differences to the maintainer before committing.

Scripts from the 2026-10-10 session (the session scratchpad, which may have been cleaned up): `C:\Users\petro\AppData\Local\Temp\claude\C--dev-projects-SpacePixels\0a4a08d7-2129-4137-b6cc-22cfdf72037a\scratchpad\`.

- `runds.sh <dataset> <log>`: runs one dataset with `photo_profile.json`.
- `regress18.sh <prefix>`: runs all 18 datasets.
- `summ.mjs <report.html>`: summarises the photometry readiness.
- `diag/BgFloorDiag.java`: prints the sky level and noise of each frame; the output is in `bgfloor.txt`.
- `regress-bgb.out`/`bgb-*.log` (before, at 0c608ed) and `regress-bga.out`/`bga-*.log` (after, with the background-floor fix): a full regression pair.
- `cmpbg.mjs <scratchpad>`: compares the reports of the two runs line by line.

Diagnostic programs use the classpath in `runtime-cp.txt`, with the scratch folder passed as a Windows path (`cygpath -w`).

## Open work (as of 2026-10-10)

- **Settings "fix" items (all three done and pushed):**
  - Edge Margin is hidden: the void check already keeps detections 20 px or more from the edges. (Since 2026-10-18 the edge and void settings are gone altogether: detection keeps to the session data area.)
  - The unused `anomalySuspectedStreakMinElongation` is removed.
  - `minBackgroundDeviationADU`: the allowed sky shift is now never below the median frame noise (done 2026-10-10, regression clean).
- **Import rules (2026-10-17, committed in 8fe0bd0; exposure check in 3e4519e):**
  - `XisfImageConverter.validateSequence` checks XISF headers before conversion: same size, channels, colour space, sample format, and all-or-none timestamps.
  - `FitsSequenceValidator` also compares `BZERO`/`BSCALE`.
  - Only unsigned 16-bit (`BZERO` 32768, `BSCALE` 1) reaches the engine, which reads stored + 32768. The GUI gatekeeper offers to convert signed/scaled 16-bit (mono → `_16bit_converted`, colour → `_16bit_color` + `_16bit_mono`), the headless loader (`FAIL_IF_NOT_READY`, batchDetect) refuses it, `AUTO_PREPARE` converts it.
  - Pixels below zero (signed 16-bit or 32-bit conversions): `FitsPixelConverter.scanNegativeValues`, then the GUI asks **Add an Offset (Recommended)** / Set to 0 / Cancel; the API has `NegativeValueHandling` (default `ADD_OFFSET`). The offset is the same for every frame, leaves the zero-run registration padding at each row's ends at 0 (the engine finds padding by its near-zero level), and is recorded as `SPOFFSET` (summed if already present). `NightEquipment` measures the sky above `SPOFFSET` and leaves out boxes with a pixel at or below it.
  - Evidence (session scratchpad ffcaa988…/bzero): `BZERO` 0 copies of Africano/m5-reg broke the padding detection and Night & Equipment; signed copies with the sky subtracted (43–49 % below zero) give the control result with the offset, and with Set to 0 every frame counts as a failed registration and the run fails ("masterStackData must be non-empty").
  - All 20 reference folders are unsigned 16-bit and still import (dc-large is colour, refused in batch as before).
  - **One exposure time (2026-10-17, committed in 3e4519e):** `ExposureGroup` groups frames by `EXPTIME`/`EXPOSURE` (same within 2 %, at least 0.1 s: dc-light-2 records 60.0/60.1/60.2 s; no exposure time is a group of its own). `FitsSequenceValidator.validateExposures` (in `validate` and `validateAndSort`, so all three import paths) and `XisfImageConverter.validateSequence` throw `MixedExposureException` (an `IOException`, message lists the groups and names the odd frames). `FitsImportTask` catches it: `ImageProcessing.offerToImportLargestExposureGroup` warns ("Different Exposure Times", buttons "Import the N images of X s" / Cancel), copies the largest group into `<folder>/_exposure_60s` (existing files kept, they may carry a plate solution) and redirects the import there; when every group has one image it is just an error. Checked with `ExposureImportHarness` (session scratchpad ffcaa988…/exposure, Apophis copy with 2 frames at 120 s; screen capture, both buttons) and the batch CLI (refused). The JTransient `code-review.md` import rules and PT-9 (now excluded for SpacePixels) were updated.
  - The JTransient `code-review.md` was re-assessed against these rules; the maintainer is filling in its per-issue comment blocks.
- **JTransient code review (2026-10-18):** every decision in JTransient `code-review.md` is implemented; the "Implementation status" table there lists the commits, and the JTransient `AGENTS.md` has the engine facts, the per-step real-data results and the regression kit (session scratchpad ffcaa988…/reg).
  - SpacePixels side: Manual Transient Inspection works on a settings copy (fd98a9a); the report draws the session data area ("Data area" button on the Overview picture, `DataAreaPresentation`) and says where the sky and stars are measured; the suspected streak category (SST) is gone; `edgeMarginPixels`, `voidThresholdFraction` and `voidProximityRadius` are removed from the profile, and single-frame searches (Preview, candidate pool, plate correction) use `DataArea.ofFrame` (e752869). Old profiles get the migration offer.
  - The maintainer's build uses the local JTransient (gitignored `gradle.properties`, `useLocalJTransientBuild=true`). Until the engine is published, a build without it does not compile against these changes.
  - Another session worked in this repo at the same time (0553e58 "Report enhancements", plus uncommitted MANUAL.md, TODO.md, AutoTuneOverviewPanel, DetectionConfigurationPanel, ImageProcessing, DetectionSummaryTest, ReportExportConsistencyTest). Stage only your own files.
- **M81-kumar false positives (analysed 2026-10-18; step (a) done in JTransient ffa60aa; hot pixels following the dither handled by the camera-fixed check, JTransient 4b443d7 + SpacePixels 8ce5d99, 2026-10-19: LR 274 → 84 → 10, CM 52 → 5 → 3, 19 FC groups; step (b) deferred by the maintainer; details in the JTransient AGENTS.md):** the 274 local rescues and 52 common-motion groups (414 sources) of the HIGH auto-tune run are stationary faint sources (position spread 0.4-0.5 px), mostly in frames 1-12 (darkest sky: frame sigma 313 → 539 ADU by frame 41) and in the right/lower-right half. Cause: one global sky level and sigma per image (code review XC-3). The median stack's sky gradient spans about 16 true-noise units, so its global sigma is 338 ADU against a local noise of about 68; master sigma 2 is then 1.4 local sigma on the bright left (noise flood, 1,300 "stars" per box) and about 17 on the dark right. 543 of 546 frame-1 leftovers have no master star but sit at a median 7.4 local sigma in the stack. The tuner (1024 px crops) sees almost no gradient, so its master sigma 2 means about 2.5 local sigma. Experiment (scratchpad ffcaa988…/m81, `M81Diag.java`, output `diag3.txt`): subtract a 256 px box sky map from the master: sigma 90, 17,035 stars, mask 2.1 %, frame-1 leftovers 546 → 25, 410 of 414 CM sources and 200 of 274 LR positions covered. Proposed order: (a) sky map for master-star extraction, (b) maybe for frames, (c) a check of leftovers against the median stack with a local sky before LR/CM.
- Batch regressions need a lot of memory (each `batchDetect` JVM uses up to 11 GB, and the machine has 15.5 GB). Run them with other programs closed, or in smaller groups.
- **Possible follow-ups:**
  - The void band grows with the measured drift on all four edges, even edges without padding.
  - Slow-mover sigma could follow the tuned detection sigma. The maintainer will test this by hand first.
  - **After the 2026.10-01 release, sky labels in the viewers:**
    - Proposed: a "Faintest" magnitude slider (stars by Gaia G, variables by maximum brightness) and a Names switch (key N) next to Annotate, saved in the preferences like the stretch.
    - The density limits in `FrameView.drawSkyMarks` stay as a safety net.
    - Open question to the maintainer: whether the clutter comes from the star magnitudes or the variable names. If it's the variables, they get their own limit.
  - **After the release, agent services:** an MCP server over a shared `results.json` summary. The proposal is in the maintainer's doc "SpacePixels Agent Services Proposal" (claude.ai), with open questions for review.
  - Manual app screenshots (`ui-start`, `ui-imported`, `ui-astrometry`, `ui-stretch`, `ui-autotune-running/done`) were retaken on 2026-10-11 with `ManualAppShots.java` (session scratchpad af288ebd…/tune): klangwolde-2, default preferences (node backed up, cleared, restored), painted at the screen scale, Location column blanked and the Astrometry Config site hints reworded in the app before capture. The **Auto-Tune Settings** button (left of Detect Moving Targets, Ctrl+T) follows the Overview's Run Auto-Tune button via `MainApplicationPanel.bindAutoTuneButton`.
- **Blink window (2026-10-10):** `BlinkImagesTask` only reads frames into a `BlinkSequence` (16-bit data, per-channel histogram, averaged half-size copies). `BlinkView` draws the visible part through `DisplayStretch` lookup tables, which `DisplayStretchTest` proves identical to `FitsVisualizationRenderer`. The stretch controls share their models with `StretchPanel`. Tracks come from `ImageProcessing.getLastTrackOverlay()` (set by the standard detection run, T/ST numbering as in the report). A window harness that drives the real `BlinkFrame` must back up and restore the preference node `/eu/startales/spacepixels` (the stretch and `blink.frameMillis` are saved there). The window opens on Blink Selected with a loading card (`showLoading`/`showLoadProgress`), then `open` shows the frame list (a JTable with ticks, editable only while paused) and the view. Only `BlinkView` may take the keyboard focus, and the split pane's arrow-key bindings are removed: a focused button takes Space and the split pane takes the arrows. Test keys by dispatching real `KeyEvent`s to the focus owner; calling the actions directly misses these.
- **Frame viewers (2026-10-10):** Blink, Preview Frame (`DetectionSequenceFrame`) and Manual Transient Inspection (`TransientInspectionFrame`) share `FrameView` (formerly `BlinkView`; it also draws detection markers sized by the `RawImageAnnotator` settings, and hides them with a note when there are more than one per 400 screen pixels), `StretchControls` (the shared stretch row; `detach()` it when a viewer is disposed, or the Image Stretch tab keeps the window and its frames alive) and `ViewerSupport` (zoom buttons, status bar, frame text, cursor readout, keys). Preview, Inspection and Show full size (`StretchedSequenceFrame`, reads frames with `BlinkSequence.load`, so colour works) extend `SequenceViewerFrame`, which prepares one frame at a time on a `SwingWorker`; its `showsDetections` flag adds the Show detections switch. Only ← → step in all viewers. Preview no longer uses `DetectionTask` or the EventBus; `DetectionTask` is now only the full detection. The 32-bit check in Preview only shows a message: import already refuses unconverted 32-bit frames. `GenerateFullSizeTask`, the `FullSizeGeneration*` events and `ImageVisualizerComponent` are removed. Harnesses in the session scratchpad: `ViewerHarness.java` (keys, stepping, stretch detach) and `ManualShotsHarness.java` (the manual's `ui-blink/preview/inspection/fullsize.jpg`, on Africano with the legacy-conservative settings that find the comet; it sets a darker stretch and restores the preferences).
- **Sky catalogue and Annotate (2026-10-10, step 1 of 4 agreed with the maintainer; online only, no bundled or ASTAP catalogues):**
  - `util/skycatalog`: `SkyCatalogueClient` runs three TAP queries on the frame outline (`SkyField`, an ADQL polygon with a 3 % margin; corner order does not matter to the services). SIMBAD deep-sky objects with a classic catalogue name (M, NGC, IC, `SH` = Sharpless, Cl Collinder…, one-word numbers only, so "NGC 2251 103" cluster stars are dropped) plus galaxies ≥ 1′ (`otype='G..'` includes subtypes); VSX on VizieR (`V < 2`, max ≤ depth + 1); Gaia DR3 on VizieR `I/355/gaiadr3` (about 2× faster than ESA, which times out with HTTP 408 on larger queries), ESA archive as fallback. A failed source goes to `problems`; interrupting the thread cancels. Saved as `sky_catalogue.json` in the session folder (`SkyCatalogueStore`), restored on import when the field matches.
  - GUI: **Fetch Sky Catalogue** in 2 Astrometry (`MainApplicationPanel`), enabled with one solved frame, background thread, progress at the right of the status bar, button becomes Cancel; the Astrometry status has a second line and the details in its tooltip. `SkyCatalogueModel.shared()` holds the catalogue; `SkyAnnotations` (per viewer: Annotate switch, key A, Layers menu, "ready" note) never fetches and never changes the frame when a catalogue arrives. `FrameView.drawSkyMarks` clips to the frame and thins marks per screen area (note at the bottom left). Star depth: `AppConfig.skyCatalogueStarMagnitude` (Astrometry Config tab, default 15, saved at once).
  - Checked live on 3 klangwolde-2 frames copied to the scratchpad (`SkyCatalogueHarness`, `SkyManualShot` for `docs/images/manual/ui-annotate.jpg`): 23,722 stars / 17 deep-sky / 2,485 variables in about 20–25 s. Java `skyToPixel` equals astropy to 0.001 px; the marks are off by about 1.3/1.0 px on average (median 4.4 px from detected centres) because of the stored plate solution, so **step 2 is a plate-solution re-fit against Gaia**. Then step 3: the report (VSX matches for variable candidates, transients on stars or in galaxies), step 4: photometric calibration.
- **SIP and plate-solution correction (2026-10-10, steps A and B):**
  - Step A (9871584): `WcsCoordinateTransformer` applies TAN-SIP A/B terms (sky→pixel: AP/BP first guess, then exact iteration; matches astropy). Most variable-star datasets and klangwolke-1 are TAN-SIP; ignoring SIP put stars up to 17.8 px off (M81-kumar corners).
  - Step B: `PlateCorrectionFitter` (util/skycatalog) matches Gaia stars to `SourceExtractor` stars in every frame (vote for the first offset, then iterative nearest matching, isolated stars only), fits a global polynomial displacement of order 1–3 (lowest within 3 % of the best on held-out frames; it was 0.02 px until 2026-10-13, which picked order 1 on a 0.09 px header solution and made it worse) plus a shift or linear term per frame; no correction is made when the header solution already has 90 % of the stars within 0.5 px (`GOOD_ENOUGH_P90_PX`; whynot 0.29, Double Cluster 0.36, the reference datasets 1.2 px and more) or is within 3 % of the best order (`global` null, `isNeeded()` false, the C switch stays off), and fetches stars to G 17/18.5 when the field has < 300. Result `PlateSolutionCorrection` (util) in `plate_correction.json`, with `enabled`; `WcsSolutionResolver.resolve` applies it when enabled (per frame for a given file, global for `resolve(null, …)`, so the report uses the global part), `resolveUncorrected` for the fit. The correction only applies to the header solution it was fitted to (`solutionKey`) and the same frame size.
  - Viewers must resolve plate solutions over all frames of the session, not only the ones shown: `BlinkFrame.open` takes `sessionFiles` for this (daa18c9; before, blinking only unsolved frames gave no labels and no RA/Dec). `BlinkSubsetHarness` (scratchpad, Apophis copy in `aposession`) checks it through the real Blink Selected button.
  - GUI: the fit runs in the same background job after a fetch, or on import when a catalogue but no matching correction exists; starts off, a refit keeps the on/off setting. Viewers: **Correct plate solution** (C) next to Annotate; `SkyCatalogueModel` shares it and saves the file at once.
  - Study (`scratchpad/refit`, prototype `refit.RefitStudy`): median today 0.26–4.86 px → 0.07–0.28 px on 14 solved datasets; frame-to-frame deltas 0.02–0.10 px except Apophis (0.18, only 94 stars). Regression: detections and photometry identical; M81-kumar VSX matches about 3″ → 0.1–1.1″, dc-reg 2.7/5.0″ → 1.0/0.4″. Harness `CorrectionHarness` (scratchpad). The regression runs left `detections_20261010_15*/16*` folders in Apophis, klangwolke-1, dc-reg and M81-kumar.
- **Report and the sky catalogue (2026-10-10, step 3; the maintainer dropped photometric calibration and transient/anomaly cross-checks):**
  - `SessionCatalogue` (reporting) loads the session's `sky_catalogue.json` when its field matches. `SessionPictureSectionWriter`: the Overview picture (median stack downscaled to 1800 px JPEG, star mask as a transparent PNG, Detections and Catalogue layers as SVG in frame pixels; buttons with hover for the mask) plus the Diagnostics link; its script also opens a collapsed `<details>` when a `#link` points inside it. `CatalogueMapPainter` paints the same names on `global_track_map.png`.
  - Names on the report: only `SkyProjection.isWellKnownDeepSky(object)` (M/NGC/IC with a plain number, Sh 2-, or a common name / a proper name without digits), bright named stars (SIMBAD "NAME" ids, V < 5, `properName` rule: no digits, a real word, not "… in …"), and `notableVariables` (letter or Greek-letter GCVS names, brightest 15, none on a named star). The viewers still show everything. Common names come from `namedObjectQuery` (any G/ISM/Cl*/GrG/ClG object with a NAME id; molecular clouds and cores left out), which also adds famous objects without a number (Horsehead = Barnard 33).
  - Variable candidates: matched with the catalogue's VSX stars at report time within the live lookup radius; matched rows are filled statically and "Identify" becomes "Look up the N others in VSX" (only unmatched rows have the hidden `data-identify-all` buttons). Cards get `catalogueEntryHtml` (VSX type in words, range, period, period coverage, Gaia G and BP−RP as catalogue values).
  - `FieldDepth`: Gaia to G 20.5 in four 12′ patches (halfway to each corner), detection on each patch cut-out with the run's thresholds but `patchConfig` (void radius and edge margin back to defaults; the drift-grown void radius rejected every star of a cut-out), patches skipped when fewer than half of their 10 brightest stars are found (galaxy, nebula, cluster core), stars grouped by magnitude (at least 20 per group), depth = where the found share falls to half of the best share. Results: klangwolke-1 G 16.9, dc-reg 13.9, m31-red 14.7, M81-kumar 14.4 (shallow because of min 10 px at 2.93″/px; sparse field, ±0.4 per frame).
  - Regression: detections and photometry byte-identical before and after on klangwolke-1, dc-reg and M81-kumar. Catalogues fetched before these changes have no names or depth patches; fetch again.
- **Auto-Tune Overview (9c01b5a):** the legacy tuner sets only detection sigma/grow/min px, mask overlap and star jitter. Its table shows only those columns and only those settings get the ● marker (`addCoreSetting(..., setByLegacyTuner)`). Applying a legacy result copies just those five onto the current settings.
- **Night & Equipment report section (2026-10-12, after the 2026.10-01 release; ideas in the uncommitted `TODO.md`, which the maintainer will delete after validation):**
  - `StarShape` (reporting): one star on a cut-out: adaptive Gaussian-weighted second moments with the weight removed (exact for a Gaussian) for FWHM and ellipticity, aperture flux at 2.2 × the star's own FWHM (a fixed aperture made the large stars of a tilted side look dimmer), and the coma offset (plain light-weighted centre minus weighted core).
  - `NightEquipment` (reporting): up to 2,000 isolated master stars measured in every kept frame (4 frames in parallel); per frame seeing (centre third), relative transparency, Gaia zero point and sky brightness (needs the catalogue and a linearity slope within 0.12 of 1); field maps (rows 5, columns by aspect) of relative size, ellipticity minus the frame median (tracking), coma offsets, star brightness against Gaia (per-frame line, so a slight non-linearity does not show as a pattern), sky background boxes; bowl fits (a + b u + c v + d r²) for curvature/tilt, flats and background; coma fit offset = k (r − r0). PIERSIDE minority frames are turned 180°. Pixel scale, camera angle (FITS +y east of north) and focal length from the plate solution and XPIXSZ; temperature from FOCTEMP or AMBTEMP.
  - `NightEquipmentSectionWriter`: section `night-equipment` after Variable stars, nav item, Overview "Seeing" card. Thresholds: curvature 5/15 %, tilt 4/8 %, coma 5/10 % of the star size (collimation hint only at ≥ 10 % with the zero point inside the frame and ≥ 20 % from the centre), flats 3 %.
  - Cost with frames in memory: 0.5–4.4 s (klangwolde-2 4.4 s in a real run), so no checkbox. Harness `NeHarness` and scripts `run.sh`/`runall.sh`/`batch.sh`/`cut.py`/`shot.sh` (headless Chrome screenshot of the section) in the session scratchpad af288ebd…/ne.
  - Findings on the reference data: klangwolde-2 stars 31 % larger on the right side (tilt or sag; klangwolke-1, camera turned 180°, larger on the left), M81-kumar focus drift with FOCTEMP, Africano vignetting not removed. Sky brightness assumes calibrated frames without pedestal; M81-kumar (17.8) probably still has an offset.
