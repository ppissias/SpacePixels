# SpacePixels Release Notes

## 2026.10-04

Not released yet.

### Changed

- **Compressed (.fz) frames are decoded more exactly.** nom-tam-fits 1.23.0 fixes the decoding of 32-bit PLIO frames and of quantized floating-point frames: their blank pixels, and the rounding of frames without dithering. Integer frames (RICE, GZIP, HCOMPRESS) decode as before. Frames from 16-bit cameras are unaffected.
- **A dataset folder that cannot be written.** When the plate-solution correction or the sky catalogue cannot be saved there, for example in a read-only folder, it stays in use until SpacePixels closes, in the viewers and in the reports, and the window says it was not saved. Before, a correction that could not be saved was switched back, and a catalogue that could not be saved was missing from the reports. A detection also no longer waits for a pending save before it starts.
- **Libraries:** FlatLaf 3.7.2, nom-tam-fits 1.23.0, Gson 2.14.0 and the Gradle 9.8.2 build. Saved settings files load as before. FlatLaf 3.7.2 shows brighter text in the dark theme and rounded scroll bars on Windows; number fields keep the caret where you click, as before.
- **Runs with fewer than three usable frames.** When quality control keeps fewer than three frames, the detection engine (JTransient) now stops there itself, and the report shows the quality-control results and says why the detection stopped. Before, SpacePixels removed the multi-frame results afterwards, and such a report also showed the median and maximum stacks. A run in which quality control keeps no frame at all now gives this report instead of failing.
- **Every report picture uses the run's display stretch.** The corner mosaic of the pipeline diagnostics and the rainbow cluster map used the built-in stretch in reports from the batch command, even when the saved visualization preferences changed it. They now use the run's stretch, like the other pictures. Reports made in the window look as before.

### Removed

- **The AI report sections.** Codex's **Signal Weave**, Claude's **The Night, Retold** and the **Include AI Creative Report Sections** setting are gone. A saved visualization preferences file that still holds the setting loads as before; the next save drops it.

### Upgrading and API changes

- **The processing facade is gone.** The internal `util.ImageProcessing`, `ApplicationWindow.getImageProcessing()` and `ProcessingSession.getProcessing()` are deleted; the services are used directly.
  - Read a folder with `new FitsImportService(folder, interaction)` and `readFiles()`, then close it; null interaction reads strictly. The exposure-group offer is `FitsImportService.offerToImportLargestExposureGroup`.
  - `ProcessingSession` takes a `PlateSolveService` in place of the processor: `ProcessingSession(solver, files, lastReport[, metadata, storage, ui])`. Build the solver on the application's live `AppConfig`; the session closes it. The session also gives `getPlateSolveService()` and `getBatchService()`, the batch operations on its folder.
  - `FitsImportTask(directory[, storage[, interaction]])` takes no application settings, and its `Result(files, lastReport, metadata)` holds no processor.
  - `BatchConvertMonoTask` and `BatchStretchTask` take a `FitsBatchService`, `PlateSolveTask` and `WriteWcsTask` a `PlateSolveService`, and `GeneratePreviewsTask` a `FitsVisualizationRenderer`.
  - The static helper moved to its owner, `FitsImages.getImageHDU`. `ImageProcessing.MIN_USABLE_FRAMES_FOR_MULTI_FRAME_ANALYSIS` is replaced by JTransient's `JTransientEngine.MIN_KEPT_FRAMES`, and `PipelineResult.tooFewFrames` says when the engine stopped for it.
  - `FitsImportService`, `FitsBatchService`, `FitsVisualizationRenderer`, `PlateSolveService` and `FitsImages` are now public classes for these callers, but stay internal: they are not part of the supported API. The `api` package is unchanged; the API no longer creates a plate solver.

- **Frame metadata and import ownership.** `ProcessingSession` owns its frame array and returns a copy from `getFiles()`; metadata objects remain shared for in-place WCS updates. `FitsImportService.readFiles()` returns fresh metadata without caching it and replaces `getImportedFiles`, `getFitsfileInformation` and `getFitsfileInformationHeadless`. `SessionFrames` and `getSessionFrames()` are removed, `hasPlateSolvedFrame()` belongs to the session, and `ImageProcessing.RedirectImportException` moves to `util.RedirectImportException`. Detection and inspection jobs now take admitted frame arrays: `DetectionTask(files, config)` uses built-in options, while its full constructor takes `files, config, DetectionRunOptions, SkySnapshot, DetectionSafetyPrompt`; `ManualTransientInspectionTask` takes `files, config`. Capture application options at admission with `DetectionRunOptions.capture(..).withVisualization(..)`. Missing frames still fail during job execution. Every no-interaction import now uses the API/CLI's readiness-first error order; an input failing both readiness and sequence consistency can therefore show a different first error through the former noninteractive `getFitsfileInformation` path. Accepted inputs, interactive conversion choices and supported pipeline request/result contracts are unchanged.

- **Detection without the processing facade.** Detection no longer passes through the internal `ImageProcessing`: its `detectObjects`, `runDetectionPipeline`, `exportDetectionReport`, `captureRunOptions`, `getLastTrackOverlay` and `getLastVariableStarOverlay` are gone. Callers run `util.StandardDetectionPipelineService` with explicit frames (`ProcessingSession.getFiles()` in the window, `FitsImportService.readFiles()` for headless callers), as `DefaultSpacePixelsPipelineApi` does, and capture options with `DetectionRunOptions.capture(..).withVisualization(..)`. The nested types `ImageProcessing.DetectionSummary`, `ImageProcessing.PipelineExecutionData` and `ImageProcessing.DetectionSafetyPrompt` are now the top-level `util.DetectionSummary`, `util.PipelineExecutionData` and `util.DetectionSafetyPrompt`, unchanged. A finished run's overlays (`util.DetectionOverlays`) belong to `ProcessingSession` (`setLastRun`; `getTrackOverlay` and `getVariableStarOverlay` as before), and the session also owns its frame array. The supported pipeline API is unchanged.

- **Desktop storage construction.** The internal `SettingsStorage`, `SessionStorage` and `SessionPersistence` classes are replaced by `session.ApplicationStorage` and the existing `ProcessingSession` lifetime. Embedders constructing desktop/session infrastructure pass the shared storage to `ApplicationWindow` or `FitsImportTask`, and storage plus a UI executor to the metadata-aware `ProcessingSession` constructor. Metadata saving uses `ProcessingSession.saveCatalogue` and `saveAutoTune`.

- **API change: `VisualizationSettings`.** `ExportVisualizationSettings` (package `util.reporting`) is replaced by `eu.startales.spacepixels.config.VisualizationSettings`, which `DetectionRunOptions` now carries. It also holds the detection marker sizes and the most animated detections of the report. Start from `VisualizationSettings.defaults()` and change values with its `with…` methods; the positional constructors are gone. `DetectionRunOptions.capture(AppConfig, int)` takes only the observing site and uses the built-in visualization settings, and `AppConfig` no longer has `variableStarReportOptions`. See [Run options and isolation](API.md#run-options-and-isolation).

## 2026.10-03

This release searches each session only where every frame has image data, sets hot pixels and dust aside before tracking, and builds a better star mask on frames with a sky gradient. It also makes SpacePixels safer with your files and settings. Detection engine: **JTransient 1.0.4**.

### Highlights

- **Detection keeps to the session data area.** Registration fills the parts of a shifted frame that have no data with black. Before, a fixed border and darkness probes around each object kept detection away from that fill, and on drifted sessions they left 10–25 % of the frame unsearched.
  - The engine now measures where every kept frame has image data and searches only there.
  - Sky levels, frame quality, slow movers, photometry and both auto-tuners use the same area.
  - Frames with some fill no longer have their noise overestimated, so they are searched as deeply as the others.
  - The report draws the area as a blue dashed outline on the Overview picture and the detection maps. It says how much of the frame the area covers and which frame limits it most. See [Overview](MANUAL.md#overview).
- **Hot pixels and dust are set aside before tracking.** Some defects stay at one place on the camera while the dither moves the sky. Detections within 2 px of one camera position in at least 3 frames, while those frames moved at least 4 px, are now removed before tracks are linked, so they no longer become tracks.
  - The report lists them as camera-fixed groups (FC1, FC2…) with positions and animations.
  - The switch is **Reject Camera-Fixed Sources** on the Track Linking page, on by default.
- **Likely Artifacts.** The camera-fixed groups and the common-motion groups are folded into one collapsed **Likely Artifacts** group after the variable stars. Their trail and animation pictures are drawn in colour.
- **A better star mask on frames with a sky gradient.** The master star map also looks for stars against the local sky. A gradient no longer hides the faint stars on its darker side, which used to come back from the frames as many stationary leftovers.
- **Variable stars across the field.** A new picture circles every reported variable candidate on the median stack, with full-size light curves. Blink and Preview Frame can mark the candidates of the last run with **Show variable stars** (key V). See [Light curves and exports](MANUAL.md#light-curves-and-exports).

### Detection changes

- **Suspected streaks are gone.** Anomalies of one frame that lie on a line are kept as a streak only when they complete a confirmed streak, for example the pieces of a broken satellite trail. Otherwise they stay separate anomalies; on noisy sessions such groups were mostly noise. The suspected-streak category, its report sections and the **Suspected Streak Line Tolerance** setting are removed.
- **Streak checks.**
  - A streak seen in several frames that does not move is no longer confirmed as a streak track.
  - Streak motion is compared in pixels, so a tiny step between distant fragments no longer merges unrelated streaks.
- **Quality control.** The sky and the frame-quality statistics are measured inside the data area. A frame that alone shrinks the data area by 5 % of the frame or more is left out as "Shifted away from the other frames", at most a tenth of the frames.
- **Residual analysis** counts gaps and coverage over the kept frames, so a frame left out by quality control no longer counts as a gap.
- **Point tracks** always need at least three points, whatever the profile says.
- **Auto-Tune.**
  - The auto-tuners count noise rates per searchable pixel and can recover test stars near the edge of their crops.
  - The Legacy tuner scores the same star mask that the detection run applies.
- The **edge margin and void settings** are removed; the data area replaces them.

### Import

- **One exposure time per session.** Frames whose exposure time differs by more than 2 % (at least 0.1 s) are refused. SpacePixels names the odd frames and offers to copy the largest set into a subfolder and import that. The batch command and the API refuse the folder with the same list.
- **Signed 16-bit FITS.** Frames not stored as unsigned 16 bit (`BZERO` 32768) were read 32768 ADU too high.
  - The window offers to convert them, the batch command refuses them with a clear message, and the API's input preparation converts them.
  - Pixels below zero either get the same offset in every frame (recommended; recorded as `SPOFFSET`) or are set to 0.
  - See [Tips and troubleshooting](MANUAL.md#9-tips-and-troubleshooting).
- **Sequence checks before conversion.** XISF folders are checked before conversion: every frame must have the same size, channels, colour space and sample format, and either all frames have timestamps or none do. FITS sequences must share `BZERO` and `BSCALE`.

### The settings tab and the application

- **Invalid input is shown, not silently replaced.**
  - Text that is not a number, or is outside a setting's range, stays in the field, outlined in red. The line at the bottom says what to enter.
  - Save and the runs do not start until it is corrected; SpacePixels shows you the setting instead.
  - A value such as "4,5" where "4.5" is expected is no longer read as 4.
- **A run uses the settings as they are when it starts.** Editing the settings during a run no longer affects that run. Preview Frame keeps the settings it was opened with until you open it again.
- **Typed values keep their decimals.** Before, 4.25 typed into a field showing one decimal was saved as 4.2.
- **Pairs of settings are explained.** When a change crosses a pair, such as Grow Sigma above Detection Sigma, the bottom line says which setting was moved. See [Detection settings in brief](MANUAL.md#8-detection-settings-in-brief).
- **Image Stretch preview.** The preview always shows the last frame and stretch you chose, however quickly you change them, and renders one frame at a time.
- **A quiet console.** By default only problems are printed. The progress and diagnostics appear with `-Dspacepixels.debug=true` (Gradle: `-PspacepixelsDebug`).
- **Report details.**
  - The report records the SpacePixels and JTransient versions.
  - Navigation and the explanations of detections and rejected frames are clearer.
  - Groups left out of a large report are also left out of the Unclassified Transient Inspector.

### Fixes

- **Files are replaced safely.** Writing a plate solution into a frame no longer deletes the original first. The updated frame is written beside it, read back to check it, and swapped in; if anything fails, the original is kept.
  - The settings, session files, report files and converted copies are written the same way.
  - A settings Save that fails says which file was not saved and keeps showing unsaved changes.
- Manual Transient Inspection could change the saved detection settings; it now works on a copy.
- The **In report** checkboxes of the high-detection-count summary now control the right groups.

### Upgrading and API changes

- **Saved settings and profiles.** Your saved detection settings are kept. Profiles with the removed fields (`suspectedStreakLineTolerance`, `edgeMarginPixels`, `voidThresholdFraction`, `voidProximityRadius`) or without the new `enableCameraFixedRejection` trigger the existing one-time profile migration, with a backup.
- Java 21 or newer is still required. Detection engine: **JTransient 1.0.4**.
- **API changes.**
  - `PipelineResult.dataArea` and `PipelineResult.cameraFixedGroups` are new, and the telemetry reports `dataAreaFraction` and `dataAreaLimitingFrames`.
  - Input preparation takes `NegativeValueHandling`, and a folder with mixed exposure times fails with `MixedExposureException` as the cause. See [Input preparation modes](API.md#input-preparation-modes).
  - The engine throws `IllegalArgumentException`, naming the frames, when frames differ in size, only some frames have timestamps or exposure times, or exposure times differ.
  - `Track.isSuspectedStreakTrack` is removed.

## 2026.10-02

This release adds **Night & Equipment** diagnostics, remembers each session's Auto-Tune results, improves faint-streak and common-motion handling, and strengthens the variable-star checks. The report now explains where processing time went and how a candidate's measured variation compares with its catalogue entry.

### Highlights

- **Night & Equipment.** A new report section measures star size, relative transparency and, when catalogue calibration is available, sky brightness through the session. It also shows pixel scale, field of view, camera angle, focal length and sampling, with maps of star size, shape, coma, star brightness and sky background across the field. These help investigate focus drift, tilt, optical aberrations, gradients and flat-field problems. Temperature is shown when recorded in the FITS headers. See [Night & Equipment](MANUAL.md#night--equipment).
- **Saved Auto-Tune results and charts.** Results are saved beside the frames in `auto_tune.json` and restored when that session is imported again. Calibrated and Legacy results are kept separately. **Show Auto-Tune Chart…** shows the noise-versus-sensitivity trade-off for each profile, its budget and chosen setting, and the recovery of test stars by brightness. A compact summary keeps the chart available without retaining every tested combination. See [Auto-Tune](MANUAL.md#step-3-auto-tune).
- **Choose what a large report includes.** The high-detection-count summary now has **In report** checkboxes for optional result groups. Leave out a noisy group without repeating the detection: its section, crops and animations are omitted from that report. Moving-object tracks, streak tracks, single streaks and suspected streak groupings remain included. The detection settings are unchanged. See [Detect](MANUAL.md#step-4-detect).
- **Catalogue comparison for variable stars.** Each matched candidate gets a note showing observation coverage, available filters and catalogue passbands, and any amplitude or source-metadata discrepancy that needs review. These notes provide context independently of detection confidence: catalogue disagreement alone never rejects a candidate. See [Variable stars](MANUAL.md#variable-stars).

### Detection changes

- **Faint streak fragments:** aligned pieces in the same frame can extend a confirmed streak or establish a new single-frame streak. This helps collect broken satellite trails without lowering the ordinary streak-detection thresholds.
- **Anomalies near streaks:** standalone anomalies close to a confirmed same-frame streak are retained with a low-confidence note, so possible trail fragments are easier to distinguish from isolated flashes.
- **Common-motion filtering:** separated compact sources with matching displacement across at least three shared frames are grouped as likely hot-pixel or registration artifacts. The filter runs before local rescue and activity clustering, so those observations are not rescued again. Associations must be unambiguous, and an observation cannot be counted in multiple groups. Common motion is a reason for review, not proof of an instrumental origin.
- Common-motion filtering has its own switch, **Reject Common Motion**. Turning off **Enable Residual Transient Analysis** skips local rescue and activity clustering while leaving common-motion filtering available. New expert settings control faint-fragment assembly and common-motion matching tolerances.

### Variable-star photometry

- **More reliable local comparisons.** The local variability check uses sufficiently precise neighbouring stars, including other candidates. It can catch a shared local brightness fault even when that fault makes several neighbours appear variable; correlation alone is not enough without a substantial shared change.
- **Spatially uneven frames are excluded from photometry.** After ensemble correction, populated regions of a 3 × 3 field grid are compared with the centre. A residual brightness offset of at least 0.10 mag excludes that frame, and the ensemble is recomputed before scoring. Both compared regions need at least 30 usable stars. The report and CSV retain the exclusion reason. These exclusions do not remove frames from moving-object detection.
- **Clearer measurements and checks.** The report labels the measured P95–P5 range as **Observed variation in this session**, separately from catalogue ranges and full-cycle amplitudes. Period coverage is stated numerically; a short part of an orbital period can still contain a substantial eclipse. Scientific measures keep their names, with plain explanations of the checks and their outcomes.
- **Qualified catalogue comparisons.** Notes show usable measurement count, time span, largest gap, period coverage and passbands, including candidates beyond the detailed-card limit. Amplitude review prompts account for the measured noise and identify cross-band limitations. Missing or uncertain metadata is explicit, and a smaller observed variation is not flagged merely for being below the catalogue amplitude.
- **Source metadata stays visible.** Selected, independently verified CoRoT catalogue discrepancies are annotated with the publication, original values and relevant unit conversion. The notes do not replace the catalogue values or change detection confidence.
- Comparison notes update after successful VSX lookups and restore from saved report data offline. Existing sky catalogues remain readable; older entries with shared passbands or missing uncertainty metadata are marked provisional. Fetching the sky catalogue again supplies the additional metadata when available.
- A **Not ready** photometry result keeps its readiness explanation and CSV exports while omitting the empty detailed charts, candidate section and frame table.

### The report and application

- **Detection Stage Timings**, under Diagnostics, shows elapsed time and the share of engine time for each major detection step. Faint-fragment assembly is shown within tracking, where its time is already included. Image loading, Auto-Tune and report generation are outside the engine total. Diagnostics now starts expanded.
- **Common Motion** has a field map and per-group illustrations and animations. Group counts appear in the detection summary, and the Unclassified Transient Inspector distinguishes points assigned to these groups from the remaining detections. Streak fragments, anomalies and residual candidates have clearer explanations.
- The Main tab shows the active detection and star-mask settings beside **Detect Moving Targets**, plus the saved Auto-Tune profile and run date. Variable-star photometry is switched on from the Detection Settings Overview or Variable Stars page.
- **Preview Frame** on the Auto-Tune Overview now opens the same preview window as the Main tab.
- The [User Manual](MANUAL.md) has updated screenshots and instructions, including the frame limit needed to reduce memory use in iterative mode and its omission of variable-star photometry.

### Fixes

- A frame plate-solved after import now supplies its solution to the detection run and report immediately, without importing the folder again.
- Plate-solution correction uses relative validation error when choosing the polynomial order, avoiding unnecessary loss of accuracy on already precise solutions. No correction is offered when the header solution is already good or a fitted correction brings no meaningful improvement.
- Missing VSX magnitude and period values are no longer formatted as zero. Catalogue amplitude flags and separate endpoint passbands are retained for the comparison.

### Upgrading and API changes

- Your saved detection settings are kept. The four new faint-fragment and common-motion fields trigger the existing one-time profile migration, with a backup. Both new passes are enabled by default and can be switched off in the expert settings.
- Existing matching Auto-Tune files remain usable. Older files containing the full calibration sweep are rewritten in compact form when imported; chosen settings and chart summaries are retained. **Run Auto-Tune** still performs a new measurement.
- Java 21 or newer is still required. Detection engine: **JTransient 1.0.3**.
- API users can inspect ordered per-stage wall-clock timings in `PipelineTelemetry.stageTimeMs`, faint-fragment time within tracking, spatial photometry exclusion counts, anomaly low-confidence flags and common-motion groups. Common-motion groups remain available even when residual analysis is disabled. See [Detection diagnostics](API.md#detection-diagnostics).

## 2026.10-01

This release adds variable-star photometry, a new auto-tuner that measures your own frames, a sky catalogue for the viewers and the report, and a reworked window, report and settings panel. It comes with a new [User Manual](MANUAL.md).

### Highlights

- **Variable-star photometry.** An optional analysis that measures the brightness of the field stars in every frame and reports stars that change, with light curves, comparison stars and a verdict on whether the data is fit for photometry. Candidates can be looked up in the AAVSO Variable Star Index (VSX) from the report. See [Variable stars](MANUAL.md#variable-stars).
- **Calibrated Auto-Tune.** The new default tuner measures how many noise detections each setting produces and how faint a test star it still finds, then offers a ready set of settings for four profiles: **Low**, **Medium**, **High** (default) and **Maximum**. One run fills the table for all four; switch profiles without running it again. The previous tuner is still available as **Legacy**. See [Auto-Tune](MANUAL.md#step-3-auto-tune).
- **Sky catalogue and Annotate.** **Fetch Sky Catalogue** downloads the Gaia stars, deep-sky objects, bright named stars and VSX variable stars of the field. The viewers can draw them on your frames (**Annotate**, key A), and the report uses them. See [Annotate](MANUAL.md#annotate-stars-deep-sky-objects-and-variable-stars).
- **Correct plate solution.** SpacePixels checks the plate solution in the FITS headers against the Gaia stars and offers a correction for the session. On the reference sessions it brought the stars from 0.3–4.9 px down to 0.07–0.3 px (median). When switched on (key C), the viewers, the cursor RA/Dec, the report and its JPL, SkyBoT, SatChecker and VSX lookups all use it. Your FITS files are never changed. See [Correct plate solution](MANUAL.md#correct-plate-solution).
- **New Blink window.** Speed control, live stretch, pause and step, zoom and pan, a frame list to leave out cloudy frames, the tracks of the last detection, and Annotate. Preview Frame, Manual Transient Inspection and Show full size now work the same way. See [Blink Selected](MANUAL.md#blink-selected).
- **Star Mask Explorer.** See the star mask exactly as a detection run builds it, over the median stack, and try other mask settings before you run. See [Star Mask Explorer](MANUAL.md#star-mask-explorer).

### The report

- Results come first: a session line (field, time span, frames, exposure, camera, plate solution), a **Jump to** bar with counts, and an **Overview** with a card for each kind of result. The processing diagnostics are collected at the end, collapsed.
- **The session picture:** the median stack with switchable layers for the detections, the star mask and the catalogue (Messier, NGC, IC and Sharpless objects with their outlines and common names, bright named stars and the best-known variable stars).
- **Field depth:** with the sky catalogue, the report says how faint the detection reached, as a Gaia G magnitude, for the session and frame by frame.
- The **Map of All Detections** names the well-known objects of the field.
- Variable-star candidates are matched with the session's catalogue when the report is written, and each candidate card says what VSX and Gaia know about the star: type, range, period, and how much of the period the session covers.
- The **Unclassified Transient Inspector** plays a short animation of the frames around each detection.
- Plainer wording throughout, and the configuration lists the settings that differ from the defaults.
- AI sections: Claude's **The Night, Retold** replaces Gemini's sections; Codex's **Signal Weave** stays.

### The application

- The **Main** tab follows the workflow in four groups (**1 Prepare**, **2 Astrometry**, **3 Inspect**, **4 Detect**), each with a status line. A greyed-out button's tooltip says why it is disabled. **Auto-Tune Settings**, to the left of **Detect Moving Targets**, opens the Detection Settings and runs Auto-Tune. Shortcuts: Ctrl+D (detect), Ctrl+T (Auto-Tune), Ctrl+B (blink), Ctrl+P (preview).
- **Import Aligned Frames…** replaces the File menu, and you can drag a folder onto the window. Import checks that all frames of the folder belong together (same type, bit depth, size and layout).
- The window can be resized and remembers its size and position.
- **Detection Settings** has pages with search (Ctrl+F) and **Show only unsaved changes**. Settings are basic or expert: the fine-tuning settings are shown with **Show expert settings**. Changed settings are marked, with a reset button for each. Settings that only matter while a feature is on appear when you switch it on.
- **Quality Control** has a **Strictness** choice (Lenient, Normal, Strict) for leaving out frames that differ from the rest of the session.
- **Astrometry Config:** the ASTAP check also looks for the star database, the observing site is checked as you type, and **Fill Site from FITS Header** fills the fields. Astrometry.net submissions are private.
- **Image Stretch:** the preview shows the whole frame with zoom and histograms, and the stretch is shared with every viewer.
- A **User Manual** link and, when a newer release exists, a **New version** button at the right end of the tab bar.
- A new application icon for the windows, the taskbar and the macOS Dock.

### Detection changes

- Blank frames and failed registrations are left out at the quality stage, also in **Detect Iteratively**.
- Frames are no longer rejected for a shift of the sky background smaller than the frame noise. This happened on very stable nights and on sessions whose sky levels were equalised during stacking.
- The slow-mover settings no longer follow the detection settings; they are set on their own.
- **Edge Margin** is no longer a visible setting: the void check already keeps detections away from the frame edges.
- Plate solutions with TAN-SIP distortion terms are read in full. Before, stars in the corners of such fields could be placed several pixels off.

### Fixes

- Importing 32-bit FITS frames.
- **Fill Site from FITS Header** no longer crashes.
- The frame numbers in the Unclassified Transient Inspector are right when frames were rejected.
- Blink uses the session's plate solution when the blinked frames are not solved themselves.
- The command-line tool always exits when it finishes.
- On Linux and macOS, the `batchDetect` and `injectStars` launchers can be run directly (they were not marked executable).
- Preview Frame, Manual Transient Inspection, the Star Mask Explorer and Correct plate solution read frames whose image is not in the first part of the FITS file, as import and detection already did.
- If an optional part of the report (session picture, field depth, catalogue names on the map) cannot be made, the rest of the report is still written.

### Upgrading

- **Your saved detection settings** are kept. Because new settings were added, SpacePixels offers a one-time **Migrate profile** step when it loads them; it keeps a backup of the old file.
- **Profile names** changed: conservative, balanced and aggressive are now **Low**, **Medium** and **High**, and **Maximum** is new. The command line accepts both the new and the old names.
- **Command line:** `--auto-tune <low|medium|high|maximum>` and the new `--tuner <calibrated|legacy>`. The bundled `config/default_detection_profile.json` now lists every setting.
- Java 21 or newer is still required.

### Under the hood

- Detection engine: JTransient 1.0.2.
- Java API: `SpacePixelsPipelineRequest` takes the auto-tune algorithm (`autoTuneAlgorithm`). The Maven artifact now brings JTransient to API users at compile time, so the examples in [API.md](API.md) build with the SpacePixels dependency alone.
- The release archives include the license.

<sub>Gaia data: ESA/Gaia/DPAC. SIMBAD and VizieR: CDS, Strasbourg. VSX: AAVSO.</sub>
