# Feature requests

New or changed functionality for SpacePixels: what the application does, as opposed to how its code is organised. Design and refactoring work is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md). Detection and photometry algorithm proposals belong to JTransient's [feature requests](../JTransient/FEATURE-REQUESTS.md), and solver features to JPlateSolve's [feature requests](../JPlateSolve/FEATURE-REQUESTS.md).

A request is a proposal until the maintainer decides on it. A settled choice is marked **Decision (made)** and kept in the maintainer's words, followed by what that choice resolves. Points that are still open stay listed as decision points. When a request is implemented, describe it in [MANUAL.md](MANUAL.md) (and the developer documentation where relevant) and remove it from this file.

| ID | Request | Status |
|---|---|---|
| [F04](#f04-mcp-agent-services) | MCP agent services over HTTP | Agreed: HTTP. The services table awaits agreement. |
| [F06](#f06-windows-installer) | Windows installer with a bundled Java runtime | Implemented locally; clean Windows acceptance pending. |
| [F09](#f09-consolidated-super-report-for-iterative-runs) | Consolidated "super report" for iterative runs | Agreed. The matching policy is to be proposed. |
| [F12](#f12-newer-flatlaf-features) | Newer FlatLaf features | Proposed; FlatLaf 3.7.2 is in use |
| [F13](#f13-observing-session-preparation) | Observing-session preparation with Siril | Initial implementation; broader acceptance and follow-ups pending |
| [F15](#f15-report-themes-and-custom-css) | Report themes and custom CSS | Planned for the next release; not implemented |
| [F17](#f17-faint-streak-search) | Faint-streak search for streaks and streak tracks | Implemented 2026-11-01 with JTransient ST-6, on by default; awaiting the maintainer's review |
| [F18](#f18-enhanced-stack) | Enhanced stack: a less noisy picture of the session | Implemented 2026-11-01, on by default; awaiting the maintainer's review |
| [F19](#f19-report-folder-organisation) | Report folder organisation: one folder per finding category | Implemented 2026-11-01; awaiting the maintainer's review |
| [F20](#f20-custom-plate-solving) | Custom plate solving, coordinated with JPlateSolve | Deferred; current solvers are blind only |
| [F01](#f01-slow-mover-and-per-frame-tuning-relation) | Slow-mover and per-frame tuning relation | Proposed experiment |
| [F02](#f02-session-summary-and-multiple-night-warning) | Session summary and multiple-night warning | Deferred |
| [F05](#f05-small-star-visibility-in-stretch-previews) | Small-star visibility in stretch previews | Deferred |

## F04. MCP agent services

AI agents operate SpacePixels through an MCP server, beginning with what the [Java API](API.md) does. Use the headless application services, with the public API as the starting boundary; never drive `ApplicationWindow`.

**Decision (made):** "I propose to have an http interface. But first we should do a table with the services that we will provide and each service description. After we agree on the services we will proceed with an implementation."

### Proposed services (for agreement)

The server is an MCP server on the Streamable HTTP transport, so an agent connects to one URL and calls these services as MCP tools. Report files are served over the same HTTP server, so a client on another machine can read them. Nothing below is implemented yet.

| Service | What it does | Main inputs | Returns | First version? |
|---|---|---|---|---|
| `server_info` | Describes the server: SpacePixels, JTransient and JPlateSolve versions, the folders it may read, the memory limit, and whether a job is running. | none | versions, allowed folders, limits, busy/idle | Yes |
| `get_settings_schema` | Lists every detection setting an agent may change: field ID, title, unit, range, default, and basic or expert. It comes from the same definitions the settings panel uses, and hidden settings are not listed. | none | one entry per setting | Yes |
| `get_default_profile` | Returns the default detection profile, as saved in a profile file. | none | profile JSON | Yes |
| `inspect_dataset` | Checks a folder without processing it: frame count, size, mono or colour, FITS or XISF, timing, exposure groups, and whether it is ready or needs preparation. Same checks as the import. | folder | readiness, the problems found, and the exposure groups | Yes |
| `start_analysis` | Starts a full analysis of a folder, the same as the Java API's `run`: optional preparation, optional Auto-Tune, detection, then the report. Only one analysis runs at a time; a second request is refused while one runs. | folder, profile or setting overrides, preparation mode, Auto-Tune profile, report on/off | job ID | Yes |
| `start_auto_tune` | Runs only Auto-Tune on a folder and returns the tuned settings. Nothing is detected. | folder, profile, Auto-Tune profile, max candidate frames | job ID | Yes |
| `get_job_status` | Reports a job's state (queued, running, finished, failed or cancelled), its stage and percentage, the last progress message, and the elapsed time. | job ID | status | Yes |
| `cancel_job` | Asks a running job to stop. The job reports "cancelled" once the work has actually stopped. | job ID | accepted or not running | Yes |
| `get_analysis_result` | Summarises a finished analysis: the effective settings, Auto-Tune choice, rejected frames, counts per category, and the findings from `report-data.json` (ID, category, position, sky position, frames, variable-star tier). | job ID | structured summary | Yes |
| `list_artifacts` / artifact URLs | Lists a finished job's files (HTML report, `report-data.json`, CSVs, images) with an HTTP URL for each. The agent or the user opens the report from that URL. | job ID | file list with URLs | Yes |
| `extract_frame_sources` | Runs source extraction on one frame with given sigma, grow sigma and minimum pixels (or a profile), and returns the detections and an annotated preview. This is extraction on that frame alone, before the stationary-star veto. | folder, frame, settings | detections, preview image URL | Later |
| `inspect_frame_transients` | Returns the transients left on one frame after the whole dataset's frame selection, median reference, star mask and filtering, with an overlay. Same as Manual Transient Inspection. | folder, frame, settings | transients, overlay image URL | Later |

**Limits that apply to every service.** The server listens on localhost unless configured otherwise, and needs a token when it listens on a network address. It reads only inside configured root folders, and writes only report folders under the dataset (as the Java API does). Results and artifacts leave out observing-site coordinates and absolute paths. Settings files in the user's home folder are never changed.

**Still to decide after this table.** Whether the first version needs `start_auto_tune` as its own service (it could stay an option of `start_analysis`); how long finished jobs and their artifact URLs are kept; and how the server is started (its own launcher, or an option of the desktop application).

**Acceptance.** An agent submits a folder and a profile, follows the run, and retrieves the same findings and report as an equivalent Java API call. The single-frame services, when added, match Preview Frame and Manual Transient Inspection on the same frame and settings.

## F06. Windows installer

A normal install, launch, upgrade and uninstall on Windows; other platforms can follow. User settings and datasets stay separate from application files, so an upgrade or an uninstall leaves them in place.

**Decision (made):** "The per-windows installer should have an exe format and bundle a java runtime. I would follow best practices for the rest of the questions about shortcuts, app updates, etc. Signing would be a second step, we can start with an unsigned release. If there are other decision points that we need before we start, please let me know. I think java already has such a mechanism natively (at least partly), please confirm."

**Answer.** Yes. The JDK's `jpackage` tool (in JDK 21) builds a Windows `.exe` installer that contains the application and a Java runtime cut down with `jlink`. It needs the WiX Toolset on the build machine (WiX 3 for JDK 21). It covers most of the questions:

- **Install scope:** `--win-per-user-install` installs under the user's local application folder without administrator rights.
- **Shortcuts:** a Start menu entry (`--win-menu`, `--win-menu-group`) and an optional desktop shortcut (`--win-shortcut`).
- **Upgrades:** a fixed `--win-upgrade-uuid` with a higher version replaces the installed version.
- **Several launchers:** `--add-launcher` can add a console launcher for the batch command-line tool next to the desktop application.

It does not include an automatic updater; a new release is installed by running the new installer. A Gradle plugin (for example the Badass Runtime plugin, which suits a non-modular application) or a plain `jpackage` task can build it from the existing distribution.

**Implementation status (2026-10-26).** The recovered `feature/windows-installer` work supplies opt-in `windowsAppImage` and `windowsInstaller` Gradle tasks. The unsigned EXE has been built locally. The implementation uses Java 21, maps `2026.10-04` to `26.10.4`, keeps the current memory options, includes a console batch launcher, generates its ICO from the existing icons, and uses a fixed upgrade UUID with per-user installation and optional shortcuts. Build and release instructions are in [PUBLISHING.md](PUBLISHING.md#windows-installer); user instructions are in [MANUAL.md](MANUAL.md#installing). Signing, CI and a runtime upgrade remain separate work.

**Acceptance.** On a clean Windows machine: install, launch, run a small analysis and open its report, upgrade from a previous version, and uninstall while saved profiles and data stay.

## F09. Consolidated super report for iterative runs

Basic iterative detection is implemented: independent runs over growing subsets of a directory, with an index of the per-pass reports. This request adds one combined report.

**Decision (made):** "Agreed. please also use a similar page layout for the super report (colors, background, ...) as the individual reports."

**Proposal.** Read the completed pass reports and their saved run metadata, recognise findings that are the same physical object or event, and present each once with the best information from the passes that saw it. The individual reports, and their own identities, stay available.

- **Associate findings across passes** using input identities, timestamps, sky and pixel positions, motion and category-specific evidence. Report-local IDs (T, ST, A, V) are not enough. Handle split and merged tracks, missing WCS, ambiguous matches and incompatible datasets explicitly, and show disagreements instead of forcing a merge.
- **Combine evidence with provenance.** A finding may have its strongest detection in one pass, its longest track in another and its best astrometry, photometry or pictures in a third. Say how each measurement is chosen or combined, and link it to its pass and report anchor. Passes that reuse the same frames are correlated evidence, not independent confirmation.
- **Present a useful combined view:** distinct findings, the passes that contributed and their frame counts, coverage and limitations, the best pictures, and links to every source report. Combined counts are shown separately from per-pass counts.
- **Persist enough evidence.** `run-data.json` and the per-pass input manifests already record settings, outcomes, file names, dimensions, exposure and timestamps. `report-data.json` is a findings summary, not a per-frame record. Decide, and version, what per-frame positions, associations, quality flags, WCS and catalogue provenance or light-curve data must be added before promising consolidation of every category.
- **Work from saved data,** with bounded memory, including after a cancelled or failed pass.

**Next step.** Propose the matching and evidence-selection policy for agreement before implementing.

**Acceptance.** One finding seen in several passes, distinct nearby objects, split tracks, conflicting measurements, changed or missing inputs, different fields with matching file names, missing WCS and older summary-only reports. Chosen measurements stay traceable, ambiguous associations stay visible, unique counts are justified, and the individual reports are unchanged.

## F12. Newer FlatLaf features

**Request (2026-10-23):** explore what the newer versions of the libraries offer, "especially flatlaf".

SpacePixels uses FlatLaf 3.7.2 (`FlatDarkLaf`) since the completed [SP-18 upgrade](DEVELOPER.md#dependencies). SpacePixels already uses `FlatLaf.style`, the clear button and placeholder text of text fields, and `JComponent.outline`.

**Worth using, most valuable first:**
1. **Native file dialogs** (`SystemFileChooser`, 3.7). The three `JFileChooser`s would open the Windows Explorer dialog, with its recent places, quick access and search. They choose the import folder, the folder for iterative detection and the ASTAP executable. Glob filters arrived in 3.7.1.
2. **Text styles instead of local font sizes.** Some controls still derive fonts locally (`deriveFont`, `new Font`). FlatLaf's style classes (`h1` to `h4`, `large`, `medium`, `small` and `mini`, set with `FlatLaf.styleClass`) follow the base font and the display scaling. Appearance now supplies the shared body/heading fonts and startup scaling; replacing the remaining local styles is optional cleanup. Since 3.7, the check-box and radio-button icons scale with them too.
3. **The zooming API** (3.7). The implemented Appearance tab uses `UIScale.setZoomFactor` before constructing windows, with a saved 100–300% interface size. Optional live Ctrl + / Ctrl – shortcuts remain a separate proposal; they would need to update custom layout dimensions as well as standard components.
4. **Readability** (3.6). FlatLaf Dark's text is brighter, and scroll-bar thumbs are rounded on Windows. Both came with the upgrade.
5. **Row highlighting.** Rounded table selection (3.5), and alternate row colours (tables already had them; lists and trees since 3.6), for the frame table and Blink's frame list.
6. **A green outline** (`JComponent.outline = success`, 3.6), for example for a value that Auto-Tune set. It would sit next to the red outline already used for invalid text.

**Not useful now.** The title-bar height option, icon-only tabs, JIDE tabs, and the macOS and Linux items. Appearance now offers dark, high-contrast dark and light themes; automatic following of the operating system's theme is not implemented.

**Behaviour changes checked during the upgrade (2026-10-24):**
- **Selection on focus** (3.6): FlatLaf selects a spinner's whole value when it gains the focus, by Tab or by a click. The maintainer kept the earlier behaviour, where the caret stays where the user puts it: `UiStyle.spinner` sets the select-all policy to never. Selecting the value on focus remains an option.
- **Progress bars** (3.5): FlatLaf would log a warning when an indeterminate progress bar changes off the Swing thread. The GUI mechanics suite, which runs imports and detections, logs none.
- **Rendering on Windows** (3.5.2): FlatLaf turns off Direct3D on-screen rendering, against ghosting on some graphics drivers. A full redraw of a viewer showing a 61-MPix frame in a 1600 × 1000 window takes about 4.8 ms instead of 4.3 ms, which is not noticeable.

## F13. Observing-session preparation

**Maintainer direction (2026-10-28).** Start implementation using the supplied window mockup. A new button above **Import Aligned Frames** opens a separate window, replacing the earlier tab proposal. Include Siril installation/status controls, explicit folder mapping and sensor/Bayer choices; keep PixInsight WBPP on the existing import route. Incorporate the maintainer's Siril FITS-header corrections.

The initial implementation and user workflow are described in [MANUAL.md](MANUAL.md#preparing-an-observing-session) and [Preparation implementation](docs/features/preparation-integrations.md). **Prepare Frames…** is the entry point. These acceptance and follow-ups remain:

- Broaden native desktop and scientific acceptance across camera formats, calibration masters/dark-flats and supported operating systems. Initial representative sessions do not establish arbitrary camera/RAW or cross-platform support. Existing reference datasets remain the downstream scientific check; raw calibration recipes need their own suitable data.
- Consider **Preview one light** to review the selected decode/debayer interpretation. This is a usability aid, not proof of the correct Bayer pattern. Additional mosaics require explicit supported recipes and samples.
- Consider richer folder suggestions or explicitly selected camera presets only after the manual workflow is accepted. Never merge multiple target/session groups or infer missing sensor/calibration facts from extensions.

**Acceptance.** Native workflows must preserve originals, account for every exposure, preserve source timing/site metadata through the supported corrections, produce a valid linear mono16 sequence, cancel owned processes and preserve the open session on failure. Compare with an equivalent manual tool recipe and confirm downstream findings on appropriate known data before widening support.

## F15. Report themes and custom CSS

Offer a light report theme alongside the current dark appearance, and make report colours easier to customize through CSS.

**Decision (made, 2026-10-29):** "I will do it on the next release". Deferred from the current release; implementation has not started.

**Proposed scope:**

- Built-in **Dark** and **Light** palettes, with Dark as the default and a selector in the report toolbar. Switching changes the open report immediately, without rerunning detection, and is independent of the desktop application's theme.
- A shared **`report.css`** with named CSS variables for backgrounds, text, borders and accents, replacing hardcoded presentation colours. An optional **`report-custom.css`** provides advanced overrides; editing it only requires refreshing the browser. Stylesheets travel with the report folder and work offline.
- Apply the same themes to ordinary reports and iterative summary/index pages. Review controls, tables, status messages, legends and inline SVG charts for contrast and consistent category meanings in both themes.
- Preserve astronomical image/GIF pixels and their dark viewing areas. Review colours already drawn into diagnostic images separately; changing page CSS does not update those image assets.

A graphical colour editor is outside this proposal. Still to decide: whether custom CSS can also be selected once for future exports, and whether/how the browser remembers a theme selection.

**Acceptance.** Check both themes on representative complete reports and iterative indexes, including charts, warnings, filters and lookup results. Verify custom overrides and offline use after copying the report folder. Theme changes must not change measurements, detection results or exported scientific data.

## F17. Faint-streak search

Finds streaks too faint for the normal extraction, by extracting streaks again on frames smoothed to the size of the stars. The streaks it finds join the normal streak linking, so they can extend or join normal streak tracks, or form new ones.

**Decision (made, 2026-11-01):** "I would use the new faint-streak mode for streak and streak track enhanced detection. This should also be an enabled-by-default option on the spacepixels configuration panels." On oriented line filters: "I would not pursuit line filters at this stage, it seems to work well enough."

The detection belongs to JTransient: [ST-6](../JTransient/FEATURE-REQUESTS.md#st-6-faint-streak-search) holds the evidence, the engine design, how the faint streaks are combined with the normal results, and the engine's decision points. A per-frame asinh stretch was tried first and does not help detection; ST-6 explains why. This request covers the application:

- **Setting.** "Faint Streak Search" on the Streak Detection page, a basic (not expert) setting, on by default, bound to `enableFaintStreakSearch`. The default profile file gets the field; saved profiles without it get the engine default, so the search is on for them too.
- **Report.** Streak tracks and single-frame streaks that the search found or extended say so on their card and in the map legend. Significance figures of faint-search detections are labelled as measured on the smoothed frame. Diagnostics shows the stage's time and counts.
- **Progress and live view** name the new stage.
- **Documentation.** The manual explains what the search finds, its cost, and how to switch it off; API.md mentions the new configuration field.

**Acceptance.** ST-6's acceptance, plus: the setting saves, reloads and is found by the settings search; with the setting off the report is the same as today; the report labels faint-search findings; a GUI mechanics test covers the checkbox.

**Implementation status (2026-11-01).** Implemented with JTransient ST-6 as agreed, including the review responses. **Faint Streak Search** is a basic setting on the Streak Detection page and is on in the default profile. Cards say which streaks the search found or added to, `report-data.json` gives `faintSearchDetections`, and Diagnostics lists the search's counts. The results on the reference data, including the maintainer's acceptance of the Integral-spacecraft-challenge track, are in [ST-6](../JTransient/FEATURE-REQUESTS.md#st-6-faint-streak-search). The settings GUI mechanics tests pass with the new switch. This entry stays until the maintainer has reviewed the results.

### F17 detailed review (2026-11-01)

**Assessment.** The agreed boundary is appropriate: JTransient detects faint streaks; SpacePixels configures the run and presents its results. The proposal still needs the contracts below before implementation. The [ST-6 review](../JTransient/FEATURE-REQUESTS.md#st-6-detailed-review-2026-11-01) identifies additional engine issues beyond the already acknowledged single-frame gate. P1 means a correctness or contract issue to resolve; P2 means a specification or validation gap. These are review recommendations, not changes to the maintainer's decisions.

1. **P1 — A detection-origin flag cannot establish that a track was newly found, extended or merged.** The proposed union is linked once. A mixed normal/faint track does not reveal what normal-only linking would have returned; it can also reorganise an existing chain. [TrackFindingIndex](src/main/java/eu/startales/spacepixels/report/TrackFindingIndex.java) numbers final tracks, without baseline correspondence. Either label the directly supported facts, such as “contains faint-search detections” and “faint-only”, or ask the engine for an explicit baseline comparison when stronger claims are required. SpacePixels should not infer a second detection result from geometry. Provenance must survive consolidation, track copies and normal anomalies joining faint tracks. Added, merged or split tracks can change report-local IDs without losing an observation.

   **Response (2026-11-01):** Agree. The report states facts only: "found by the faint-streak search" when every point came from it, and "includes N detections from the faint-streak search" for a mixed track. Under ST-6's normal-priority linking a mixed track keeps every normal point, but the report still does not claim "extended" or "merged"; SpacePixels infers no baseline. Provenance is per point (the `DetectedObject` field), so it survives consolidation, copies and joins.

2. **P1 — Measurement provenance must cover mixed tracks and exports, not just card captions.** Filtered flux, footprint, width and centroid are also measurements of the filtered image, not only the two sigma fields. [TargetVisualizationSectionWriter](src/main/java/eu/startales/spacepixels/report/TargetVisualizationSectionWriter.java) currently formats flux, FWHM and peak sigma as ordinary measurements. A mixed track must not silently sum or compare the two domains. Specify per-point origin/measurement domain in the Java API and exported measurements, a truthful track-level badge, and how footprint illustrations relate to original-frame crops. [ReportData](src/main/java/eu/startales/spacepixels/report/ReportData.java) has finding identities and frame associations but no faint-search provenance; decide explicitly whether its schema changes. A map legend alone does not identify which findings used the new search.

   **Response (2026-11-01):** Agree. The per-point field marks every measurement of that point (centroid, footprint, flux, width and both sigmas) as made on the smoothed frame. Cards label those figures, and no figure sums or compares points across the two domains. Crops stay from the original frames; a faint point's footprint picture says it is the smoothed footprint. `report-data.json` gets an optional per-finding count of faint-search detections; I propose keeping schema version 1, since existing fields do not change, unless you prefer a version bump.

3. **P1 — Reconcile “unaffected categories” with actual downstream consumers.** [JTransientEngine](../JTransient/src/main/java/io/github/ppissias/jtransient/engine/JTransientEngine.java) passes final tracks into photometric crossing rejection and builds residual candidates from tracking leftovers. New streaks can absorb normal anomalies and alter remaining normal detections. Changed photometric reference exclusions can change the ensemble solution and other stars' differential magnitudes. Distinguish “filtered pixels and faint point candidates never enter ordinary point detection/photometry” from “all other results are identical”; the latter does not follow from the current design. Record the intended downstream effect and compare anomaly ownership, residual groups, photometric flags, ensemble changes and candidate verdicts, not just streak counts.

   **Response (2026-11-01):** Agree; the same contract as ST-6 finding 3. The intended downstream effects are stated, and the regression compares anomaly ownership, residual groups, photometric flags, ensemble results and verdicts, not only streak counts.

4. **P2 — Clarify migration and the two different faint-streak controls.** The new basic switch is distinct from the existing expert [“Assemble Faint Streak Fragments” setting](src/main/java/eu/startales/spacepixels/config/StreakDetectionSettings.java), which works on already extracted normal objects. Explain whether new path-piece joining still runs when the older switch is off; do not silently couple them. Exercise old profiles without the field, explicit `false`, defaults, Save/Reload, Revert, search and unsaved-state tracking. Missing-field default-on is an agreed change to existing profiles' effective behaviour and should be stated in migration/release text. Since inspection/re-detection intentionally stay normal-only, Preview Frame and Manual Transient Inspection must not imply that their overlays demonstrate the enhanced search. Auto-Tune does not calibrate the new correlated-noise streak false-alarm rate either.

   **Response (2026-11-01):** Agree. The faint search is independent of "Assemble Faint Streak Fragments", which keeps working on normal pieces; with ST-6's step 6 deferred, there is no new path-piece joining to couple. The release notes state that profiles without the field get the search. Preview Frame and Manual Transient Inspection say they show the normal extraction only, and the manual says Auto-Tune does not tune the faint search. The profile tests listed are added.

5. **P2 — Progress/live-view integration needs explicit lifetime and counting rules.** Show a filtered crop with candidate positions and label its measurement domain. A filtered frame is temporary: render while it is borrowed or retain only a bounded detached picture, following `PipelineStageObserver` and the shared three-second gate. Hidden views must make no pictures, cancellation/session replacement must discard late delivery, and the UI must not retain every filtered frame. Define monotonic progress and separate faint extraction time from linking/consolidation time. Candidate detections, joined pieces, distinct supported frames and final faint-supported findings are different counts.

   **Response (2026-11-01):** Agree, with a smaller first version: the progress text names the stage and its counts, and the live view shows no filtered-frame picture. A picture added later follows the borrow-or-detach rules described here.

6. **P2 — Define the scope of “off is byte-identical”.** New configuration, provenance and telemetry fields can alter JSON or diagnostics even when the search is off. Require exact old scientific values, ownership, input buffers and detection assets; explicitly list intentional schema/default-field additions and ordinary volatile timings as allowed normalisations. Otherwise a whole-report byte-equality promise is impossible to satisfy, or tests may discard meaningful differences too broadly.

   **Response (2026-11-01):** Agree. With the switch off, the scientific values must be identical: tracks and their points, anomalies, photometry, CSV values, finding identities, input-buffer hashes and detection pictures. The allowed differences are listed: the new field in `detection_config.json`, the new provenance field, the new telemetry counters, and timings.

**Recommended acceptance additions.** Cover normal-only, faint-only and mixed tracks; multiple pieces in one frame versus observations in distinct frames; crossing/merged bright and faint streaks; a normal point source coincident with a filtered candidate; and a rejected middle frame to check original IDs, timestamps, crops and lookup positions. Inspect cards, maps, overlays and exported provenance. Compare added, extended, merged, split and lost physical streaks, not IDs alone. Gate default-on release on ST-6's reference/null validation and measured memory/runtime: the whole-pipeline smoothing experiment is not a benchmark of the proposed streak-only implementation. This was a review against current source and the written evidence; no implementation or new real-data benchmark was performed.

## F18. Enhanced stack

A stack of the session that shows fainter detail with less noise than the median stack the report shows today, as the basis for a picture.

**Decision (made, 2026-11-01):** the goal is "a picture with more details shown and less noise", and "The enhanced stack should also be an enabled-by-default configuration option". Then: "weighted, 32-bit float. If we have enhanced stack enabled, it should be the image used for all images that show the entire field (it should be the input for them, instead of the median stack like we do now). Also the master stack 32-bit generated fit file, should be perhaps in a separate directory, so that it is not "lost" in the many files the report generates. Agreed also for the API."

**Evidence** (scratch experiment, 2026-11-01, on Integral-spacecraft-challenge with 35 frames and Lagoon with 38 frames). Faint-star signal-to-noise against the median stack, measured with synthetic stars of known flux added to every frame and the noise from the difference of the odd- and even-frame stacks:

| Stack | Integral | Lagoon, dark sky | Lagoon, nebula | Outliers kept |
|---|---|---|---|---|
| Median (today's report) | 1.00× | 1.00× | 1.00× | none |
| Median of asinh-stretched frames | 1.00× (the same image) | 1.00× | 1.00× | none |
| Mean of asinh-stretched frames | 1.26× | 1.27× | 1.49× | some |
| Clipped mean, per-pixel sigma | 1.20× | 1.21× | 1.40× | some |
| Plain mean | 1.25× | 1.28× | 1.49× | all |
| **Noise-clipped weighted mean** | **1.27×** | **1.27×** | **1.48×** | **none** |

"Outliers kept" counts the dark-sky pixels where the plain mean lies more than 5 median-stack sigma above the median: hot pixels and trails. The noise-clipped means kept none of them on either dataset. The per-pixel clipped mean and the mean of asinh-stretched frames kept up to a quarter on Integral, which has no dark calibration. A median commutes with a stretch, so stacking stretched frames with a median gives the median stack again; with a mean, the stretch adds nothing measurable over a plain mean.

**Proposal.**

- **Method.** A noise-clipped, noise-weighted mean of the frames the run kept:
  1. Each frame's sky offset against the median stack is removed: the median of the frame minus the median stack over the data area.
  2. Each frame's noise comes from differences between frames, Var(Fi − Fj) = σi² + σj², so stars and nebula cancel and no median is involved (review finding 2). A level-dependent term (variance per ADU above the sky) is fitted from the same differences (review finding 3).
  3. At each pixel, a value is clipped when it lies more than 3 standard deviations from the current centre. The deviation is computed from its frame's noise and the level term at the shared centre. The first centre is the median of the valid samples at that pixel, not the engine's median (review finding 1), and there are two iterations. The kept values are averaged with weights of one over their variance.
  4. Each pixel uses only the frames with image data there; registration fill is missing data, not zero. The stack therefore covers more than the median's data area, and edges built from fewer frames are noisier.
- **Where.** SpacePixels builds it during report export from the frame buffers the run already holds and the engine's median stack; no engine change. Rows are processed in parallel; the only extra memory is the output image (about 240 MB at 61 MP).
- **Output file.** `enhanced_stack/enhanced_stack.fits`, in a folder of its own inside the report folder so it is easy to find among the report's files; the report links to it. 32-bit float, linear, the reference frame's header with `NCOMBINE` and a `HISTORY` card (about 240 MB for klangwolke-2).
- **Pictures of the whole field.** With the setting on, every picture that shows the entire field is drawn from the enhanced stack instead of the median stack: the session picture, the detection map and its background, the unclassified-transient map and the variable candidates across the field. Cutouts that compare a detection with the reference the detection used keep the median stack: anomaly star-mask cutouts and the slow-mover median crops. The residual-review pictures (common-motion field, camera-fixed and local-rescue pictures) also keep the median: they review the detection's leftovers against the detection reference (maintainer's view, 2026-11-01; see review finding 5).
- **Setting.** "Enhanced Stack" on the Report Visualization page, on by default, stored in the visualization preferences. The batch CLI follows the saved preference. API requests follow `VisualizationSettings`; with the built-in default, API runs write the stack too (agreed). The new setting is an API addition and needs a compatibility note.
- **Iterative runs.** Each pass's report gets its own stack of that pass's frames.

- **Diagnostics stay on the median** (decided 2026-11-01, "keep diagnostics on median"). Its two pictures of the detection reference, the median master stack and the star mask drawn over it, document what detection compared against; their captions say they show the median stack.

Per-frame scale (transparency) normalisation is left out; reconsider if a dataset with changing transparency shows the need.

**Acceptance.** On the reference datasets, measure noise and synthetic-star signal-to-noise against the median stack. Check outlier removal (hot pixels, satellite trails, moving objects) visually and by count, and measure the run time and memory cost. The FITS must open correctly in an external tool, with its header, orientation and fill handling right. With the setting off, the report is the same as today.

**Implementation status (2026-11-01).** Implemented as decided, including the review responses: pairwise frame noise, a level term per frame, a centre from the valid samples only, 3σ clipping, 1/variance weights, NaN where no frame has data, and a new FITS header. **Enhanced Stack** is on the Report page and on by default. On klangwolke-2 (28 kept frames of 61 megapixels) the stack took about 10 s to build and under 1 s to save; the FITS file is 243 MB. Read back, the file has BITPIX −32, no BZERO or BSCALE, no site cards, and gives the plate solution, `NCOMBINE` and `EXPTOTAL`. The FITS library's default comment on `SIMPLE` gives the time of writing with the computer's time zone, so it is replaced by a neutral comment. On Integral-spacecraft-challenge, reports with the setting on and off have byte-identical `report-data.json`, `detection_config.json`, diagnostics pictures and finding pictures. Only the six pictures of the whole field differ, and the FITS file and the full-size JPEG are added. Not yet done: a check of orientation and WCS in an external program such as Siril or ASTAP.

**Decision (made, 2026-11-01), display stretch:** "wherever we show the full frame and in generated full size images that show the full frame, to use there the enhanced stack, which has been asinh first (the appropriate amount of asinh). So the user will see in the report many more details of the final target in these images and in the generated full size images saved on disk." The frames are not stretched: the combination stays linear, and so does the FITS file. The finished stack is drawn with an automatic asinh stretch: black point 2 sky sigma below the sky median, white point at the brightest pixel, and the strength chosen so that the sky median is drawn at 15 % of white. On klangwolke-2 and Integral-spacecraft-challenge it shows dust lanes, faint galaxies and faint stars that the earlier square-root stretch left in a flat grey sky. It cost about 2 s of report time on klangwolke-2. The Diagnostics keep the median stack with the earlier stretch, and with the enhanced stack off the report is unchanged.

### F18 detailed review (2026-11-01)

**Assessment.** A separate linear float stack for presentation is a sound boundary. Keeping diagnostic references and detection-comparison cutouts on the median is important. No corresponding JTransient detection-algorithm proposal is needed. The method and integration still need the corrections below; the reported two-dataset experiment is useful evidence, not a complete specification. P1 identifies correctness/contract issues; P2 identifies validation/resource gaps. The agreed weighted, default-on output and median-based diagnostics remain the decisions being reviewed.

1. **P1 — The existing median is an invalid clipping seed where a minority of frames cover a pixel.** [MasterMapGenerator.createMedianMasterStack](../JTransient/src/main/java/io/github/ppissias/jtransient/core/MasterMapGenerator.java) takes the lower median of all input samples, including registration fill. For 35 frames with 25 fill values and ten valid samples of 1000 ADU, that median is zero. With noise 20 ADU and a 3-sigma threshold, all ten valid samples are rejected immediately, despite agreeing perfectly. This defeats the promised wider coverage. Outside the common area, initialise from valid contributors rather than the fill-dominated engine median. Define one/two-contributor and all-rejected outcomes, whether rejected samples can re-enter iteration two, and a distinct representation for missing data versus a valid dark sample.

   **Response (2026-11-01):** Agree, and confirmed in the code: the engine's median counts fill as samples. The builder computes its own first centre at each pixel, the median of valid samples only, and never seeds from the engine's median. A pixel without contributors is NaN. One contributor gives its value and two give their weighted mean, both unclipped, since there is no basis for a robust decision. If every sample is rejected, the valid-sample median is used. Rejected samples are tested again in the second iteration against the new centre, so they can re-enter. "Valid" uses the engine's fill rule (above 8 ADU), so the stack and the detection agree on what is image data.

2. **P1 — Residual noise against a median containing the same frame is not automatically that frame's noise.** For `R_i = F_i - M`, `Var(R_i) = Var(F_i) + Var(M) - 2 Cov(F_i, M)`. The covariance is nonzero, and the residual distribution depends on sample count and unequal frame noise. A scratch illustration using 30,000 independent unit-Gaussian samples per case, the engine's lower-median convention, and `1.4826 * MAD` for residual sigma gave about 0.46, 0.71 and 0.97 for 3, 5 and 35 frames, instead of the input sigma of one. This illustrates one common robust estimator, not an assertion that an unspecified implementation uses MAD. Specify the estimator, reference covariance, and its different roles in clipping and weighting. Test low-N iterative passes and unequal-noise exposures; results from 35–38 frames do not establish correct weights for three/five frames. Define finite positive floors and failure behaviour for flat/quantised data.

   **Response (2026-11-01):** Agree, and confirmed: on synthetic frames with unequal noise, the robust sigma of frame minus median gave 0.36–0.60 of the true noise with 3 frames, 0.69–0.75 with 5 and 0.96–0.97 with 35. Resolution: each frame's noise comes from differences between frames, Var(Fi − Fj) = σi² + σj² (robust sigma over a pixel sample, least squares over neighbouring pairs). Static structure cancels and there is no reference covariance; it recovered the true noise within 1 % with 3, 5 and 35 frames. The noise has a floor of 0.5 ADU; flat or quantised data get equal weights. I also tried each frame's sky sigma as the engine measures it: the same signal-to-noise, but 2–25 % of the outlier pixels survived and it read Lagoon's noise as 56–61 ADU against 23–27 ADU, so I do not recommend it.

3. **P1 — Define the photon-variance model and avoid sample-dependent weight bias.** Specify the ADU domain, sky convention, coefficient units, fit population, exclusion of saturation/variability/seeing changes, and fallback for an unidentifiable or negative fit. Frame-minus-median residuals also contain reference covariance, interpolation correlations and PSF/transparency changes. State whether the photon term is evaluated at a shared predicted centre or each noisy observed sample. The latter can favour downward fluctuations: observations 90 and 110 average to 100, but inverse variances `1/(1 + sample)` give about 99.01. Validate an estimator that avoids this mechanism and accumulates in double precision. Compare with a noise-clipped mean using scalar per-frame noise weights to isolate the photon fit's benefit from clipping's benefit.

   **Response (2026-11-01):** Agree. The level-dependent term is fitted from the same pairwise differences: Var(Fi − Fj) at level L above the sky = σi² + σj² + 2bL, in the frames' ADU after sky offsets are removed, over robust level bins without the top 1 % of levels. L is the current shared centre, never the individual sample, so the downward bias described cannot occur. If b cannot be fitted or comes out negative, b = 0 and clipping is limited to pixels near sky level. Accumulation is in double. Validation compares this with scalar per-frame weights, to separate the benefit of the level term from that of clipping.

4. **P1 — Resolve one checked kept-frame selection for export.** [ReportRequest](src/main/java/eu/startales/spacepixels/report/ReportRequest.java) and [DetectionReportContext](src/main/java/eu/startales/spacepixels/report/DetectionReportContext.java) retain the originally loaded buffers, including subsequently rejected frames. [ReportData](src/main/java/eu/startales/spacepixels/report/ReportData.java) already combines rejection evidence from telemetry. Reuse a checked selection by original sequence identity for stacking, metadata and `NCOMBINE`, including failed-registration exclusions; do not stack the entire raw-frame list or confuse positions in a shortened list with original IDs. `PipelineResult.dataArea` is the common detection area, not individual frame coverage. The extended stack needs per-frame coverage facts and must preserve legitimate zero/negative physical samples. Define the `tooFewFrames` case, which has no median: the quality-control report must still work.

   **Response (2026-11-01):** Agree. The stack uses the engine's kept frames by original sequence index: the same selection `ReportData` uses, failed registrations excluded. `NCOMBINE` is that count, and per-pixel coverage comes from each kept frame's own fill. A `tooFewFrames` run writes no stack and its report is unchanged.

5. **P1 — Keep a separate presentation product instead of replacing the shared median field.** [DetectionReportContext.masterStackData](src/main/java/eu/startales/spacepixels/report/DetectionReportContext.java) also feeds anomaly mask cutouts, slow-mover comparisons and other writers. Replacing it would silently change the documented reference. Deliberately route individual whole-field outputs in `SessionPictureSectionWriter`, `GlobalMapsSectionWriter`, `UnclassifiedTransientSectionWriter`, `VariableStarMapWriter` and the relevant `ResidualReviewSectionWriter` methods; some classes mix maps and local cutouts. The session picture's current “Full size” links open `master_stack.png`, which diagnostics produces as the median. An enhanced session picture needs its own matching full-size asset/link while preserving that diagnostic median. Update captions and retain the meaning of the detection-area outline on an image extending outside searchable coverage.

   **Response (2026-11-01):** Agree. The report context gets a separate presentation image; `masterStackData` stays the median for every existing use. The whole-field writers are routed explicitly: session picture, detection maps and their background, unclassified-transient map, variable-candidate map. The **residual-review pictures stay on the median** (common-motion field, camera-fixed and local-rescue pictures): as the maintainer expected, they review the detection's leftovers against the reference the detection used, like the diagnostics, and gain nothing from a deeper picture. The session picture's full-size link opens its own full-size enhanced picture; the diagnostics keep the median one. Captions say which stack each picture shows, and that the outlined detection area is where detection searched, which the enhanced picture can extend beyond.

6. **P1 — Carry float data through rendering without a hidden 16-bit conversion.** Current map/display helpers accept shifted-unsigned-16-bit `short[][]`. Specify a float-aware presentation/stretcher path, finite-value filtering and missing-data handling; casting back to shorts or rescaling into an unrelated integer range loses linear precision and can obscure faint detail. Preserve canvas size/origin so overlays need no geometric correction. No-contributor pixels must be distinguished from noisy but valid low-coverage edge pixels and excluded from stretch statistics. Specify how variable support is conveyed, whether through count/weight information or another explicit presentation method.

   **Response (2026-11-01):** Agree. A float display path: stretch statistics from finite pixels only, NaN (no data) drawn black, the same canvas size and origin so overlays need no change, no conversion to 16 bits. In the first version, edges built from fewer frames are explained in the caption; there is no separate coverage map.

7. **P1 — Do not copy an exposure's header wholesale into a float product.** Construct a new `BITPIX=-32` image and selectively retain applicable captured metadata. A copied `BZERO=32768` shifts already decoded ADU floats again: FITS readers still apply `BZERO`/`BSCALE` to floating arrays. Set appropriate float scaling, omit integer `BLANK`, use NaN for missing values where chosen, regenerate structural/dimension cards, and remove/recalculate stale range, compression and checksum cards. Define `SPOFFSET` and units without applying the preparation offset twice. Select WCS for the registered canvas: the first exposure need not be the solved reference, and the report may use a captured plate correction. State nominal versus corrected WCS explicitly. Single-exposure date, exposure and gain cards need interpretation for a weighted mean; `NCOMBINE` alone does not describe total exposure or per-pixel support. See the primary FITS guidance on [floating arrays](https://fits.gsfc.nasa.gov/users_guide/users_guide/node28.html) and [scaling/reserved keywords](https://fits.gsfc.nasa.gov/users_guide/users_guide/node22.html).

   **Response (2026-11-01):** Agree. A new header: `BITPIX = -32`, no `BZERO`, `BSCALE` or `BLANK`, NaN for missing data. Applicable cards are copied selectively (object, telescope, instrument, filter, focal length, pixel size, binning, `SPOFFSET` unchanged since the stack keeps the input domain). `DATE-OBS` is the first kept frame's start, `DATE-END` the last frame's end; `NCOMBINE` and the total exposure get cards of their own; a `HISTORY` card describes the method. The WCS is the one the report uses for the registered frames, with the plate correction when the report applies it, and a comment says which; none if there is none. The observing site (`SITELAT`, `SITELONG`, observer) is left out, as the report leaves it out.

8. **P2 — The experiment's “none” does not establish removal of all relevant artifacts or preservation of stellar detail.** Its dark-sky mask is defined from the plain-mean/median difference, so it misses artifacts already in the median, persistent hot pixels, negative defects and some sub-threshold trails. It also does not establish that clipping preserves real stars under seeing changes. Identical synthetic stars in every frame do not exercise varying real PSFs/transparency. Add positive/negative defects, majority-occupancy trails, crowded/nebular regions, low-N edges, near-saturation stars and per-frame-PSF/transparency injections. Keep the agreed deferral of transparency normalisation, but test/document its limits. State whether odd/even noise estimates share a fitted median/model/rejection decisions, since that can couple the halves. Report repeated-seed uncertainty, recovered flux and PSF as well as SNR; 1.27 versus 1.25 alone does not establish a meaningful weighting gain.

   **Response (2026-11-01):** Agree. Validation adds positive and negative defects, trails in most frames, crowded and nebular regions, low-coverage edges, near-saturation stars, and injections with per-frame PSF and transparency changes, all with repeated seeds and with recovered flux and PSF as well as signal-to-noise. The odd and even halves get independent fits. No weighting gain is claimed beyond "never worse, and better when frame noise varies".

9. **P2 — Budget total peak memory and iterative disk cost, not just the output plane.** A 61 MP float plane is about 244 MB in decimal units, a double plane about 488 MB, before robust fitting, coverage, display conversion, FITS writing and parallel scratch. Specify bounded row/tile work, deterministic accumulation, owned/joined workers, cancellation during long passes and release after export. Avoid an N-frame float cube and hidden full-frame copies. Each iterative pass also writes another large FITS, so estimate cumulative storage and cleanup. This matters for an enabled-by-default API feature. The [ccdproc combination documentation](https://docs.astropy.org/projects/ccdproc/en/stable/image_combination.html) is a useful reference for separating masks, clipping, weights and memory budgets; no new dependency is implied.

   **Response (2026-11-01):** Agree. Processing goes by row bands over the run's frames already in memory (no N-frame float cube), with double accumulators per band, one float output plane and a small coverage count, on owned, joined workers that check cancellation; everything is released after export. Disk: 4 bytes per pixel per report (244 MB at 61 MP), and again for each iterative pass; the manual says so and how to switch it off.

10. **P1 — Decide API output and failure/publication semantics.** [SpacePixelsPipelineResult](src/main/java/eu/startales/spacepixels/api/SpacePixelsPipelineResult.java) exposes optional report/export paths, not a separate stack artifact. Does `generateReport(false)` now write an enhanced stack, and if so where, with what returned path/ownership? A report-visualisation option should not create an unspecified filesystem side effect; standalone stack generation needs an explicit request/result contract if desired. Decide whether an optional stack failure falls back to the median with a warning or fails export, preserving completed detection results either way. Stage the FITS, link only a complete artifact, join cancelled workers before cleanup, and keep links relative when reports are moved. Coordinate the path with F19 without making F18's science depend on folder reorganisation. Cover no report, explicit output directory, absent WCS, enhancement off, export failure and cancellation in both API consumers.

   **Response (2026-11-01):** Agree. The stack is part of the report: `generateReport(false)` writes none. In the first version there is no new API accessor; the file has a documented place in the report folder (`enhanced_stack/enhanced_stack.fits`, as in F19). If the stack fails, the report warns, the whole-field pictures fall back to the median, and export continues with the detection results intact. The FITS is staged and linked only when complete; cancellation joins the workers before cleanup; links stay relative. Both API consumers cover the cases listed.

**Recommended acceptance additions.** Require identical engine results, input-buffer hashes, masks, photometry/CSV values and finding identities with enhancement on/off. Separately verify every intended field picture, its full-size link and unchanged median diagnostics/cutouts. Include the coverage counterexample, all-missing/all-clipped pixels, one/two contributors, changing coverage, three/five-frame passes, extreme noise ratios, failed fits, variable PSFs, flat data and negative physical samples. Read the float FITS back numerically for scaling/NaNs and inspect orientation/WCS externally. Verify persistence through `VisualizationSettings`, `SpacePixelsVisualizationPreferences.of/applyTo`, old files with no field, CLI loading and immutable run capture. The numerical examples above were checked in scratch; the original real-data trials were not rerun for this review.


## F19. Report folder organisation

Today every picture and animation of a report lies at the top of its folder: 338 files for klangwolke-2 with the default profile, 2,198 for Eagle-noisy, and over 24,000 in an extreme run. The report itself, its data files and the finding pictures are hard to tell apart.

**Requested (2026-11-01):** "I was thinking to organise the output of the report, like to move the per-categroy generated images to separate folders if you can make a proposal for that it would be great."

**Proposal.**

```
detections_<date>_<time>/
  detection_report.html     the report
  report-data.json          findings summary (schema unchanged)
  detection_config.json     the run's settings
  enhanced_stack/           enhanced_stack.fits (F18)
  field/                    pictures of the whole field: session picture and star mask, detection maps and
                            their background, transient cluster map, unclassified-transient map and background
  moving_targets/           T   moving_track_<n>_*
  streak_tracks/            ST  streak_track_<n>_*
  single_streaks/           S   single_streak_<n>_*
  anomalies/                A   anomaly_<n>_*
  slow_movers/              DS  slow_mover_<n>_*
  local_rescue/             LR  local_rescue_<n>_* (today micro_drift_<n>_*)
  camera_fixed/             FC  camera_fixed_<n>_*
  common_motion/            CM  common_motion_<n>_* and the common-motion field picture
  variable_stars/           V   brightest/faintest cutouts, candidate maps, photometry CSV files
  unclassified_frames/          per-frame strips (as today)
  diagnostics/                  median master stack, its star mask, drift map and corner animation
```

- **One folder per finding category**, named after `FindingCategory`, which already owns each category's prefix, label and anchor. Local activity clusters get a folder when they have pictures of their own.
- **File names stay as they are inside the folders**, so a downloaded picture still says what it shows. The exception is local rescue: its `micro_drift_<n>` files are renamed to match the category.
- **The top level keeps only the entry points:** the report and its two JSON files, plus the enhanced-stack folder (F18).
- **Animation companions** (`.poster.png`, `.sheet*.png`) stay next to their GIF; the report's player already finds them relative to the GIF.
- **One place decides locations.** The report context gives each section writer the file to write and the relative link to use for a category and a name; no writer builds paths itself. Folders are created only when they receive a file, and removal of unfinished output on cancellation covers them.

**Compatibility.**

- Existing reports are not migrated; their links are relative and keep working.
- `report-data.json` holds no file paths, so its schema does not change.
- Finding reports (the latest report, the iterative index) uses only `detection_report.html`, which stays where it is.
- The photometry CSV files move into `variable_stars/`. The manual and API documentation do not name their location today; anyone reading them by path must adjust, so the release notes say so.
- Tests that look for files at the top level of a report folder are updated.

**Decision (made, 2026-11-01):** "field and diagnostics, category folders".

**Decision (made, 2026-11-01):** "CSV files in variable_stars".

**Acceptance.** In a browser, from a copy of the report folder, every picture, animation, poster, frame sheet, lightbox and download works; a link checker finds every local link and image in the HTML. Cancellation leaves no partial folders. The iterative index and the latest-report lookup are unchanged.

**Implementation status (2026-11-01).** Implemented as proposed. `ReportFiles` and `FindingCategory.folder()` decide every location; a writer asks the report context for the file of a link, which creates the folder. Local rescue pictures are now named `local_rescue_<n>_…`. A unit test checks the top level, every local link, picture and frame strip in the HTML, and every animation's metadata, poster and frame sheets. On real Integral-spacecraft-challenge and klangwolke-2 reports a link check found 171 and 269 local links with none missing, no unreferenced file, and only the three entry files at the top. Cancellation removes the whole report folder, subfolders included. Not yet done: the check in a browser.

Two points for the maintainer:

- Saved lookup results (`anomaly_<n>_satchecker_results.json`, `photometry_v<n>_vsx.json` and the track lookups), written when the report's lookup buttons are used through SpacePixels, still go to the top of the report folder. The local lookup server writes only there, a path check against writing outside the report. Moving them into the category folders means changing that check; it is left as is.
- `field/unclassified_median_background.png` keeps its name, although with the enhanced stack it shows that stack.

## F20. Custom plate solving

**Decision (made)** (2026-11-01): "both JPlateSolve and SpacePixels will do only blind solve"; "Then we can freshly re-evaluate custom solve at a later stage."

This keeps custom/near solving as a separate deferred feature; no new controls or automatic pointing/scale hints are enabled now.

The shared solver proposal is [JPlateSolve JP-15](../JPlateSolve/FEATURE-REQUESTS.md#jp-15-custom-solving-in-jplatesolve-and-spacepixels). Design the application inputs, supported constraints, header-hint policy, fallback behavior and public library contract together before implementation. The removed custom API is not the accepted future design.

## F01. Slow-mover and per-frame tuning relation

Proposed investigation only. Compare the maximum-stack slow-mover thresholds with the tuned per-frame thresholds on the same real datasets before changing defaults. Measure recovery, false positives and run time. JTransient's SM-2 (a maximum stack's sigma is not a single-frame sigma) is the engine side, and is deferred.

## F02. Session summary and multiple-night warning

Deferred; revisit only if asked. A multiple-night warning would be a light check of the FITS headers at import, not an expensive quality table before the run.

## F05. Small-star visibility in stretch previews

Deferred. Compare the Image Stretch preview's sampling with the full-size viewer for small stars before changing it.
