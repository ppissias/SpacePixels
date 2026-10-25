# Feature requests

New or changed functionality for SpacePixels: what the application does, as opposed to how its code is organised. Design and refactoring work is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md). Detection and photometry algorithm proposals belong to JTransient's [feature requests](../JTransient/FEATURE-REQUESTS.md), and solver features to JPlateSolve's [feature requests](../JPlateSolve/FEATURE-REQUESTS.md).

A request is a proposal until the maintainer decides on it. Decisions are recorded under each request, in the maintainer's words. When a request is implemented, describe it in [MANUAL.md](MANUAL.md) (and the developer documentation where relevant) and remove it from this file.

| ID | Request | Status |
|---|---|---|
| [F04](#f04-mcp-agent-services) | MCP agent services over HTTP | Agreed: HTTP. The services table awaits agreement. |
| [F06](#f06-windows-installer) | Windows installer with a bundled Java runtime | Agreed. Decision points listed. |
| [F09](#f09-consolidated-super-report-for-iterative-runs) | Consolidated "super report" for iterative runs | Agreed. The matching policy is to be proposed. |
| [F10](#f10-live-view-pictures-of-the-running-phase) | Live view: pictures of the running phase, one every 3 s | Direction decided. Design proposed. |
| [F11](#f11-animation-speed-control-in-the-report) | Animation speed control in the report | Proposed. Decision points listed. |
| [F12](#f12-newer-flatlaf-features) | Newer FlatLaf features | Proposed; FlatLaf 3.7.2 is in use |
| [F13](#f13-manual-transient-inspection-with-trial-per-frame-settings) | Manual Transient Inspection with trial per-frame settings | Proposed. Decision points listed. |
| [F01](#f01-slow-mover-and-per-frame-tuning-relation) | Slow-mover and per-frame tuning relation | Proposed experiment |
| [F02](#f02-session-summary-and-multiple-night-warning) | Session summary and multiple-night warning | Deferred |
| [F05](#f05-small-star-visibility-in-stretch-previews) | Small-star visibility in stretch previews | Deferred |

## F04. MCP agent services

AI agents operate SpacePixels through an MCP server, beginning with what the [Java API](API.md) does. Use the headless application services, with the public API as the starting boundary; never drive `ApplicationWindow`.

**Decision:** "I propose to have an http interface. But first we should do a table with the services that we will provide and each service description. After we agree on the services we will proceed with an implementation."

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

**Decision:** "The per-windows installer should have an exe format and bundle a java runtime. I would follow best practices for the rest of the questions about shortcuts, app updates, etc. Signing would be a second step, we can start with an unsigned release. If there are other decision points that we need before we start, please let me know. I think java already has such a mechanism natively (at least partly), please confirm."

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

**Decision:** "Agreed. please also use a similar page layout for the super report (colors, background, ...) as the individual reports."

**Proposal.** Read the completed pass reports and their saved run metadata, recognise findings that are the same physical object or event, and present each once with the best information from the passes that saw it. The individual reports, and their own identities, stay available.

- **Associate findings across passes** using input identities, timestamps, sky and pixel positions, motion and category-specific evidence. Report-local IDs (T, ST, A, V) are not enough. Handle split and merged tracks, missing WCS, ambiguous matches and incompatible datasets explicitly, and show disagreements instead of forcing a merge.
- **Combine evidence with provenance.** A finding may have its strongest detection in one pass, its longest track in another and its best astrometry, photometry or pictures in a third. Say how each measurement is chosen or combined, and link it to its pass and report anchor. Passes that reuse the same frames are correlated evidence, not independent confirmation.
- **Present a useful combined view:** distinct findings, the passes that contributed and their frame counts, coverage and limitations, the best pictures, and links to every source report. Combined counts are shown separately from per-pass counts.
- **Persist enough evidence.** `run-data.json` and the per-pass input manifests already record settings, outcomes, file names, dimensions, exposure and timestamps. `report-data.json` is a findings summary, not a per-frame record. Decide, and version, what per-frame positions, associations, quality flags, WCS and catalogue provenance or light-curve data must be added before promising consolidation of every category.
- **Work from saved data,** with bounded memory, including after a cancelled or failed pass.

**Next step.** Propose the matching and evidence-selection policy for agreement before implementing.

**Acceptance.** One finding seen in several passes, distinct nearby objects, split tracks, conflicting measurements, changed or missing inputs, different fields with matching file names, missing WCS and older summary-only reports. Chosen measurements stay traceable, ambiguous associations stay visible, unique counts are justified, and the individual reports are unchanged.

## F10. Live view: pictures of the running phase

The live view in the progress dialog shows the frames as they load, each engine stage's findings while the detection runs, and the report's pictures as they are written (see the manual and HIGH_LEVEL_DESIGN.md).

**Decision (2026-10-23):** "What I had in mind, was that it shows specific images from the current phase it is currently. So if it does photometry, it should show something related to photometry. There should be a general rule, not to change too frequently the images, so say every 3 seconds a new image from the current phase. So each phase, should send an update every 3 seconds more or less. If we have a small dataset, it is natural not to show anything for some phases, for them to be skipped, if it is less than 3 seconds from the last update. Overall it is working well, I just wanted to make sure it will work like this. I also wanted to check with you, that we implement it efficiently and only send updates when needed from the engine."

This settles the earlier open question about the report phase: the report is one more phase, under the same rule.

### How it works today, and why it loops

- **Pictures arrive when a stage ends.** The engine hands a stage's results to the observer once, when the stage *finishes*. `LiveFindings` turns them into up to 8 slides. The dialog turns the slides every 3 s and starts again after the last (`slideIndex % size`), until the next stage finishes. So while photometry runs, the tracking findings go round. After a stage with few findings, the same two or three pictures come back many times.
- **Stage changes skip the clock.** A stage change bypasses the 3 s rule (`wantsPreview(true)`), so on a small dataset the stages replace each other within a fraction of a second.
- **Long stretches show nothing new.** Source extraction takes 0–40 % of the engine's time, and tracking and photometry run long too, but the engine reports only finished stages. During those stretches nothing new can be shown.

**The efficiency is already good, and the proposal keeps it:**
- The engine calls the observer only when one is given; headless and API runs pass none.
- Each call passes references, not copies.
- `LiveFindings` copies a few positions only when the dialog wants a picture, and renders on its own thread.
- A hidden picture costs nothing.

### Proposed design

1. **One clock, no exceptions.** The dialog accepts at most one picture every 3 s, stage changes included. A picture is made only when it would be shown, so there is no queue of slides and no rotation, and nothing loops. If a phase has nothing new, its last picture stays.
2. **Pictures belong to the running phase.** When a phase starts, pictures still pending from the previous phase are dropped. A phase that ends before the clock allows a picture shows none.
3. **The engine is asked, not told.** At the points where a long stage already reports progress, the engine first asks the observer whether it wants an update. The check is cheap: a time comparison. Only when the answer is yes does the engine hand over a small snapshot. This is the "only send updates when needed" part: on a 33-frame run, the engine builds about one snapshot every 3 s instead of one per frame.
4. **What each phase shows.** Every picture stays marked *provisional*, as now:

| Phase | Picture, each time the clock allows one |
|---|---|
| Loading | The frame just loaded (as now) |
| Source extraction | The frame just processed, with its detected sources ringed |
| Frame selection and reference | The median reference, with the stationary stars |
| Slow movers | A slow-mover candidate on the maximum stack |
| Tracking | The star mask, then the tracks, streaks and anomalies found so far, one at a time |
| Residual analysis | A local-rescue candidate |
| Photometry | The frame being measured, with its stars ringed; at the end, a variable-star candidate with its light curve |
| Report | The report picture just written (as now) |

5. **Engine side.** This needs JTransient's [API-1](../JTransient/FEATURE-REQUESTS.md#api-requests-from-spacepixels): a stage-start notification, and the "do you want an update?" check with snapshots inside source extraction, tracking and photometry. Source extraction runs on worker threads, so the check must be thread-safe and let only one worker through each time the clock allows a picture.

**Remaining checks:**
- the dialog at 150 % and 200 % display scaling;
- cancelling during each stage, on real data;
- the run time with the live view shown, hidden and off, which must stay the same.

**Acceptance.**
- No picture repeats, and no two pictures come within 3 s of each other.
- On klangwolke-2, each long phase (extraction, tracking, photometry and the report) shows pictures of its own.
- On a small dataset, short phases are skipped.
- With the view hidden, and in headless runs, the engine builds no snapshot (counted in a test).
- The results stay byte-identical.

## F11. Animation speed control in the report

**Request (2026-10-23):** "Another functional feature I want to explore, is having an adjustable animation speed in the report. I know we have an adjustable speed in the report generation settings in SpacePixels, but I wanted to check if could override that at the report."

**Why the current files cannot do it.** The report's animations are animated GIFs, written by `GifSequenceWriter`. The frame delay comes from "GIF Blink Speed (ms)" and is written into each file. Browsers play a GIF at its own delays and offer no way to change them. The report's "Pause animations" button only swaps each GIF for its `.poster.png`.

**Options:**
1. **Frame sheets played by the report (recommended).** For each animation, also write its frames as one image sheet: a grid of frames. The report's script plays the sheets, with a speed control next to "Pause animations", for example from 0.25× to 4×.
   - The report can also step through an animation frame by frame while paused, which a GIF cannot do.
   - The setting in SpacePixels becomes the starting speed.
   - It works for a report opened from disk, since the script only shows images and reads no files.
   - Long sequences, such as the corner-drift animation, need the grid layout, because browsers limit very wide images.
2. **Decode the GIFs in the browser** (`ImageDecoder`). This needs no new files. However, the script must read the GIF bytes, which browsers block for a report opened from disk, and browser support is uneven. Not recommended.
3. **Write each GIF at two or three speeds.** Simple, but it multiplies the size of the animations and offers only fixed steps. Not recommended.

**Decision points:**
- **GIFs too, or sheets only?** Keeping the GIFs lets an animation be saved or shared on its own, but the report grows. Playing only sheets keeps it smaller.
- **One speed control, or one per animation?** Proposal: one control for the whole report, remembered by the browser, plus frame stepping per animation while paused.

**Acceptance.**
- The speed changes all animations at once, without reloading the report.
- Pause and the browser's "reduced motion" setting still work.
- The report's growth in size is measured on klangwolke-2.

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
  - **Controls.** `TransientInspectionFrame` gets the three spinners, re-detecting 300 ms after a change, as Preview Frame does. Next to them is a read-only line with the values the mask was built with: master sigma, master grow sigma, master minimum pixels, and the star jitter that widens the mask.
  - **What is re-detected.** Only the frame on show is re-detected, in the background; the viewer already prepares frames off the Swing thread, and the newest request wins. Each other frame is re-detected when it is shown, so a change costs one frame's extraction, not the whole set's.
  - **Caching.** Re-detected frames are kept per setting while the window is open, so stepping back is instant.
  - **Count line.** It shows the settings in use, and the frame's transient count updates.
  - **Memory.** The window already holds all frames. The kept reference adds the master stars and the star mask, at one byte per pixel (about 61 MB for a 61-MPix sensor).

### Decision points

1. **Do the spinners change the session's settings?** Preview Frame edits the Detection Settings directly: its spinners share their models. The Star Mask Explorer is the mirror of this idea, with fixed frames and trial master settings, and it keeps trial values with an "Apply to Settings" button. Proposal: do it the Star Mask Explorer's way, since this window is for experimenting. See [SP-19](DESIGN-IMPROVEMENTS.md#sp-19-finish-the-common-base-of-the-image-viewers) on having one pattern for trial settings.
2. **One frame per change, or all frames?** Proposal: only the frame on show, which is fast, as in Preview Frame. A "Count all frames" action could re-detect every kept frame to give a total, at the cost of a full extraction.
3. **Master settings changed while the window is open** (on the Detection Settings tab). Proposal: the window keeps its reference and says that the tab's master values now differ. Reopening the window rebuilds the reference.

### Acceptance

- With the starting settings, the re-detected transients equal those of the first run (a test).
- With other per-frame settings, they equal those of a new `detectTransients` call with those settings (tests on synthetic frames and on one real dataset).
- The reference is built once per window.
- A new import closes the window (SP-19).

## F01. Slow-mover and per-frame tuning relation

Proposed investigation only. Compare the maximum-stack slow-mover thresholds with the tuned per-frame thresholds on the same real datasets before changing defaults. Measure recovery, false positives and run time. JTransient's SM-2 (a maximum stack's sigma is not a single-frame sigma) is the engine side, and is deferred.

## F02. Session summary and multiple-night warning

Deferred; revisit only if asked. A multiple-night warning would be a light check of the FITS headers at import, not an expensive quality table before the run.

## F05. Small-star visibility in stretch previews

Deferred. Compare the Image Stretch preview's sampling with the full-size viewer for small stars before changing it.
