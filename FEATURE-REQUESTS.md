# Feature requests

New or changed functionality for SpacePixels: what the application does, as opposed to how its code is organised. Design and refactoring work is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md). Detection and photometry algorithm proposals belong to JTransient's [feature requests](../JTransient/FEATURE-REQUESTS.md), and solver features to JPlateSolve's [feature requests](../JPlateSolve/FEATURE-REQUESTS.md).

A request is a proposal until the maintainer decides on it. A settled choice is marked **Decision (made)** and kept in the maintainer's words, followed by what that choice resolves. Points that are still open stay listed as decision points. When a request is implemented, describe it in [MANUAL.md](MANUAL.md) (and the developer documentation where relevant) and remove it from this file.

| ID | Request | Status |
|---|---|---|
| [F04](#f04-mcp-agent-services) | MCP agent services over HTTP | Agreed: HTTP. The services table awaits agreement. |
| [F06](#f06-windows-installer) | Windows installer with a bundled Java runtime | Implemented locally; clean Windows acceptance pending. |
| [F09](#f09-consolidated-super-report-for-iterative-runs) | Consolidated "super report" for iterative runs | Agreed. The matching policy is to be proposed. |
| [F12](#f12-newer-flatlaf-features) | Newer FlatLaf features | Proposed; FlatLaf 3.7.2 is in use |
| [F13](#f13-observing-session-preparation) | Observing-session preparation with Siril/DSS | Initial implementation; broader acceptance and follow-ups pending |
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

**Maintainer direction (2026-10-28).** Start implementation using the supplied window mockup. A new button above **Import Aligned Frames** opens a separate window, replacing the earlier tab proposal. Include Siril and DSS installation/status controls, explicit folder mapping and sensor/Bayer choices; keep PixInsight WBPP on the existing import route. Incorporate the maintainer's Siril FITS-header corrections.

The initial implementation and user workflow are described in [MANUAL.md](MANUAL.md#preparing-an-observing-session) and [Preparation implementation](docs/features/preparation-integrations.md). **Prepare Frames…** is the entry point. These acceptance and follow-ups remain:

- Broaden native desktop and scientific acceptance across camera formats, calibration masters/dark-flats and supported operating systems. Initial representative sessions do not establish arbitrary camera/RAW or cross-platform support. Existing reference datasets remain the downstream scientific check; raw calibration recipes need their own suitable data.
- Make DSS registration completely independent of saved desktop preferences. The current generated file list overrides linear-processing and output options, while its saved registration preferences remain applicable and are disclosed. Capture/control the remaining effective settings before promising full recipe reproducibility.
- Consider **Preview one light** to review the selected decode/debayer interpretation. This is a usability aid, not proof of the correct Bayer pattern. Additional mosaics require explicit supported recipes and samples.
- Consider richer folder suggestions or explicitly selected camera presets only after the manual workflow is accepted. Never merge multiple target/session groups or infer missing sensor/calibration facts from extensions.

**Acceptance.** Native workflows must preserve originals, account for every exposure, preserve source timing/site metadata through the supported corrections, produce a valid linear mono16 sequence, cancel owned processes and preserve the open session on failure. Compare with an equivalent manual tool recipe and confirm downstream findings on appropriate known data before widening support.

## F01. Slow-mover and per-frame tuning relation

Proposed investigation only. Compare the maximum-stack slow-mover thresholds with the tuned per-frame thresholds on the same real datasets before changing defaults. Measure recovery, false positives and run time. JTransient's SM-2 (a maximum stack's sigma is not a single-frame sigma) is the engine side, and is deferred.

## F02. Session summary and multiple-night warning

Deferred; revisit only if asked. A multiple-night warning would be a light check of the FITS headers at import, not an expensive quality table before the run.

## F05. Small-star visibility in stretch previews

Deferred. Compare the Image Stretch preview's sampling with the full-size viewer for small stars before changing it.
