# Feature requests

New or changed functionality for SpacePixels: what the application does, as opposed to how its code is organised. Design and refactoring work is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md). Detection and photometry algorithm proposals belong to JTransient's [feature requests](../JTransient/FEATURE-REQUESTS.md), and solver features to JPlateSolve's [feature requests](../JPlateSolve/FEATURE-REQUESTS.md).

A request is a proposal until the maintainer decides on it. A settled choice is marked **Decision (made)** and kept in the maintainer's words, followed by what that choice resolves. Points that are still open stay listed as decision points. When a request is implemented, describe it in [MANUAL.md](MANUAL.md) (and the developer documentation where relevant) and remove it from this file.

| ID | Request | Status |
|---|---|---|
| [F04](#f04-mcp-agent-services) | MCP agent services over HTTP | Agreed: HTTP. The services table awaits agreement. |
| [F06](#f06-windows-installer) | Windows installer with a bundled Java runtime | Agreed. Decision points listed. |
| [F09](#f09-consolidated-super-report-for-iterative-runs) | Consolidated "super report" for iterative runs | Agreed. The matching policy is to be proposed. |
| [F12](#f12-newer-flatlaf-features) | Newer FlatLaf features | Proposed; FlatLaf 3.7.2 is in use |
| [F13](#f13-manual-transient-inspection-with-trial-per-frame-settings) | Manual Transient Inspection with trial per-frame settings | Decided. Same trial policy as the other viewers. |
| [F14](#f14-matching-backgrounds-in-the-variable-star-cutouts) | Matching backgrounds in the variable-star cutouts | Decided. Same background and same transparency. |
| [F15](#f15-clean-moving-object-animations-to-download-narrow-or-wide) | Clean moving-object animations to download, narrow or wide | Decided. Moving objects only; written with the report. |
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

**Decision points before starting:**

1. **Installer version number.** Windows installers need a version of the form major.minor.build with major and minor at most 255. `2026.10-04` does not fit. Proposal: the installer uses `26.10.4` for `2026.10-04`, and the application keeps showing its own version.
2. **Memory.** The launcher keeps today's `-XX:MaxRAMPercentage=80.0 -XX:+UseG1GC`. A user can change it in the launcher's `.cfg` file. Proposal: keep it.
3. **Command-line tool.** Proposal: also install the batch tool as a console launcher (`spacepixels-batch`), at no extra cost.
4. **Icon.** An `.ico` is needed. Proposal: build it from the existing `spacepixels-16`…`256` PNG icons.
5. **Where the installer is built.** On a Windows machine with WiX: the maintainer's PC, or a Windows GitHub Actions runner. Proposal: a Gradle task on the maintainer's PC first; CI can come later.
6. **Unsigned release.** Windows SmartScreen warns about an unsigned download ("Windows protected your PC" → More info → Run anyway). The manual should say so until signing is added.
7. **Bundled Java runtime.** Decide whether the application and its installer use Java 21 or Java 25. This is an optional runtime upgrade; JTransient and JPlateSolve retain Java 21 compatibility. The packaging details above describe JDK 21 and must be checked against the selected packaging JDK.

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
2. **Text styles instead of local font sizes.** SpacePixels derives fonts in 57 places (`deriveFont`, `new Font`). FlatLaf's style classes (`h1` to `h4`, `large`, `medium`, `small` and `mini`, set with `FlatLaf.styleClass`) follow the base font and the display scaling, which is what [SP-16](DESIGN-IMPROVEMENTS.md#sp-16-check-the-ui-at-larger-display-scaling) needs. Since 3.7, the check-box and radio-button icons scale with them too.
3. **The zooming API** (3.7, FlatLaf PR #1051). It could give a zoom in and zoom out (Ctrl + and Ctrl –) for the whole window, useful on small or high-resolution screens and at the telescope. The changelog gives no details, so check the API before planning on it.
4. **Readability** (3.6). FlatLaf Dark's text is brighter, and scroll-bar thumbs are rounded on Windows. Both came with the upgrade.
5. **Row highlighting.** Rounded table selection (3.5), and alternate row colours (tables already had them; lists and trees since 3.6), for the frame table and Blink's frame list.
6. **A green outline** (`JComponent.outline = success`, 3.6), for example for a value that Auto-Tune set. It would sit next to the red outline already used for invalid text.

**Not useful now.** The title-bar height option, icon-only tabs, JIDE tabs, and the macOS and Linux items. FlatLaf does not follow the Windows light or dark theme. SpacePixels stays dark, which suits night use.

**Behaviour changes checked during the upgrade (2026-10-24):**
- **Selection on focus** (3.6): FlatLaf selects a spinner's whole value when it gains the focus, by Tab or by a click. The maintainer kept the earlier behaviour, where the caret stays where the user puts it: `UiStyle.spinner` sets the select-all policy to never. Selecting the value on focus remains an option.
- **Progress bars** (3.5): FlatLaf would log a warning when an indeterminate progress bar changes off the Swing thread. The GUI mechanics suite, which runs imports and detections, logs none.
- **Rendering on Windows** (3.5.2): FlatLaf turns off Direct3D on-screen rendering, against ghosting on some graphics drivers. A full redraw of a viewer showing a 61-MPix frame in a 1600 × 1000 window takes about 4.8 ms instead of 4.3 ms, which is not noticeable.

## F13. Manual Transient Inspection with trial per-frame settings

**Request (2026-10-23):** "Specifically for the unclassified transient inspection, I have a good idea. I would like to have also there the control for the detection sigma, grow sigma and min pixels, like the preview frame window. It would also show the master sigma relevant values, but we would not be able to change them. But [t]he logic would be the following, that it would re-run the detection, keeping the master mask (as we have not changed the master values) and show the transients with these settings. This is a way for the user to experiment with a stable master map, various detection sigma options and see the effect in transient production visually. So the first time we start the window, it will calculate the master map, but then if we change the detection sigma or detection pixel size, it does not have to do that again. Can you check what needs to be changed in order to achieve this?"

### What the window does today

`ManualTransientInspectionTask` loads every frame and calls `JTransientEngine.detectTransients`, which:
1. extracts the sources of every frame with the per-frame settings;
2. selects the frames by quality;
3. builds the median reference and its stationary stars with the master settings;
4. vetoes each frame's detections against the star mask.

The window receives only the transients of each frame. The reference, the master stars and the mask stay inside the engine and are discarded.

### Why the idea works, and gives exact results

The three per-frame settings (Detection Sigma, Grow Sigma and Min Pixels) are used only by the per-frame extraction:
- frame selection uses its own quality settings;
- the reference and the star mask use the master settings;
- the veto treats each frame on its own.

So, with the frame selection and the mask kept, re-detecting a frame with new per-frame settings gives exactly the transients that a new Manual Transient Inspection with those settings would give. A test should hold the engine to that.

### What changes

- **JTransient** ([API-2](../JTransient/FEATURE-REQUESTS.md#api-requests-from-spacepixels)) needs two calls:
  - The first does what `detectTransients` does, and also returns a kept reference: the kept frames, their data areas, the master stars, the star mask and the master settings used.
  - The second re-detects one frame against that reference with new per-frame settings, using the pipeline's own extraction and veto.

  The veto is internal to the engine today, so this has to be an engine API rather than SpacePixels calling the extractor itself.
- **SpacePixels:**
  - **Controls.** `TransientInspectionFrame` gets the three spinners, re-detecting 300 ms after a change, as Preview Frame does, and Apply to Settings under the same trial policy decided below. Next to them is a read-only line with the values the mask was built with: master sigma, master grow sigma, master minimum pixels, and the star jitter that widens the mask.
  - **What is re-detected.** Only the frame on show is re-detected, in the background; the viewer already prepares frames off the Swing thread, and the newest request wins. Each other frame is re-detected when it is shown, so a change costs one frame's extraction, not the whole set's.
  - **Caching.** Re-detected frames are kept per setting while the window is open, so stepping back is instant.
  - **Count line.** It shows the settings in use, and the frame's transient count updates.
  - **Memory.** The window already holds all frames. The kept reference adds the master stars and the star mask, at one byte per pixel (about 61 MB for a 61-MPix sensor).

**Decision (made):** "for point 1. I remember we have a policy now on ther other previous windows, please use the same policy. I agreee with options 2 and 3."

1. **Trial settings follow the existing viewer policy.** Preview Frame and Star Mask Explorer keep trial values and copy only the edited fields with Apply to Settings. This window uses that same policy.
2. **One frame per change.** Only the frame on show is re-detected.
3. **Master settings changed while the window is open.** The window keeps its reference and says that the Detection Settings tab's master values now differ. Reopening the window rebuilds the reference.

### Acceptance

- With the starting settings, the re-detected transients equal those of the first run (a test).
- With other per-frame settings, they equal those of a new `detectTransients` call with those settings (tests on synthetic frames and on one real dataset).
- The reference is built once per window.
- A new import closes the window (SP-19).

## F14. Matching backgrounds in the variable-star cutouts

**Request (2026-10-25):** "On variable stars, it shows 2 images, the brightest frame and the faintest frame, however these are apparently stretched differently sometimes and it shows a very different background. If we can at least ensure that we do the same stretching on both."

**What the report does today.** Each variable-star card shows the star cut out of its brightest and its faintest usable frame (`PhotometryReportSectionWriter.cutouts`). Both cutouts already get one shared stretch in raw pixel values: the black point is the median of the brightest cutout, the white point its brightest pixel, with a square-root curve. So the stretch is the same, but the frames are not:
- The sky level changes during a night (twilight, moon, haze, altitude, gradients). A frame whose sky lies above or below the shared black point shows a lighter or darker background.
- A less transparent frame also shows every star dimmer.
- The brightest and faintest frames come from the transparency-corrected light curve, so they are often frames with different sky conditions.

This explanation follows from the code; confirm it on a card that shows the problem before changing anything.

**Proposal.** The engine already records, for every frame, the median local sky of the measured stars (`PhotometryFrameStat.skyMedian`) and the ensemble zero point (`zeroPoint`).
1. **Same background.** Set each cutout's black point at its own sky level, and keep one shared span (white minus black) from the brightest frame. Both backgrounds then look the same, and the star's brightness above the sky still compares directly.
2. **Same transparency.** Also scale the faintest cutout by the zero-point difference between the two frames, so that constant stars look alike and only the variable changes. The pictures then show what the corrected light curve says, and the caption says so.

Only the pictures change. Measurements, light curves, CSVs and `report-data.json` stay as they are.

**Decision (made):** "I Agree with both points."

Both steps are in scope. Each cutout's black point is its own sky level, with one shared span from the brightest frame, and the faintest cutout is also scaled by the zero-point difference between the two frames. The caption says so.

**Acceptance.**
- On dc-reg (DK Per, Mis V1395) and klangwolke-2, the two cutouts of each card show the same background level: the cutouts' medians differ by at most a few grey levels.
- With step 2, a constant star cut out the same way from both frames looks the same.
- Apart from these pictures, the reports on the reference datasets stay byte-identical.

## F15. Clean moving-object animations to download, narrow or wide

**Request (2026-10-25):** "Add a function on the report, for moving object detections, to be able to download a star centric animated gif of the area without the overlay (i.e. without the frame information and the circle), and to have the choice to download a narrow animated gif (smaller dimentions) or a mode wide one (larget dimensions) including more sky."

**What the report does today.** Each moving-object card has two animations (`TargetVisualizationSectionWriter`):
- **Object-centric:** the crop follows the object (`trackObjectCentricCropSize`, 200 px by default), with no overlay.
- **Star-centric:** one crop, fixed on the sky, around the whole track (the track's extent plus `trackCropPadding`). It draws a white circle on the detection and a "Frame N · time" label in the corner.

Both use the run's display stretch and GIF speed. There is no clean copy of the star-centric animation, and no wider view.

**Proposal.** Write two more GIFs for each moving-object card. They have the same frames, stretch and speed as the star-centric animation, without the circle and the label:
- **Narrow:** the star-centric crop.
- **Wide:** the same centre, with more sky around it.

The card gets a "Download animation" control with **Narrow** and **Wide** links. A link with the `download` attribute saves the file. Check that this works in a report opened from disk; otherwise the link opens the GIF, and the user saves it from there. Cards past the report's limit of animated detections have no animations, so they get no download either.

**Decision (made):** "1. moving objects only. 2. agreed. 3.write them with the report, we do not have so many moving object tracks."

1. **Which findings.** Moving objects only.
2. **How wide.** Twice the narrow crop's width and height, at least 400 × 400 px and at most 1000 × 1000 px, kept inside the frame.
3. **When they are written.** With the report. Measure the growth on klangwolke-2.

**Acceptance.**
- Each eligible card offers both downloads, and the files show no circle and no label.
- The narrow file's frames equal the star-centric animation's frames apart from the circle and the label.
- The wide file has the same centre and more sky.
- The findings and `report-data.json` are unchanged, and the report's growth in size is measured.

## F01. Slow-mover and per-frame tuning relation

Proposed investigation only. Compare the maximum-stack slow-mover thresholds with the tuned per-frame thresholds on the same real datasets before changing defaults. Measure recovery, false positives and run time. JTransient's SM-2 (a maximum stack's sigma is not a single-frame sigma) is the engine side, and is deferred.

## F02. Session summary and multiple-night warning

Deferred; revisit only if asked. A multiple-night warning would be a light check of the FITS headers at import, not an expensive quality table before the run.

## F05. Small-star visibility in stretch previews

Deferred. Compare the Image Stretch preview's sampling with the full-size viewer for small stars before changing it.
