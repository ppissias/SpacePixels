# SpacePixels Release Notes

## 2026-10.02

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

## 2026-10.01

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
