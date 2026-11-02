# Feature requests

New or changed functionality for SpacePixels: what the application does, as opposed to how its code is organised. Design and refactoring work is in [DESIGN-IMPROVEMENTS.md](DESIGN-IMPROVEMENTS.md). Detection and photometry algorithm proposals belong to JTransient's [feature requests](../JTransient/FEATURE-REQUESTS.md), and solver features to JPlateSolve's [feature requests](../JPlateSolve/FEATURE-REQUESTS.md).

A request is a proposal until the maintainer decides on it. A settled choice is marked **Decision (made)** and kept in the maintainer's words, followed by what that choice resolves. Points that are still open stay listed as decision points. Remove requests when implemented or closed; describe implemented behaviour in [MANUAL.md](MANUAL.md) and the developer documentation, and use git history for the proposal and its reviews.

| ID | Request | Status |
|---|---|---|
| [F04](#f04-mcp-agent-services) | MCP agent services over HTTP | Agreed: HTTP. The services table awaits agreement. |
| [F09](#f09-consolidated-super-report-for-iterative-runs) | Consolidated "super report" for iterative runs | Retained for future work; the matching policy is to be proposed. |
| [F15](#f15-report-themes-and-custom-css) | Report themes and custom CSS | Planned for the next release; not implemented |
| [F01](#f01-slow-mover-and-per-frame-tuning-relation) | Slow-mover and per-frame tuning relation | Proposed experiment |

<!-- root-toc:start -->
## Contents

- [F04. MCP agent services](#f04-mcp-agent-services)
  - [Proposed services (for agreement)](#proposed-services-for-agreement)
- [F09. Consolidated super report for iterative runs](#f09-consolidated-super-report-for-iterative-runs)
- [F15. Report themes and custom CSS](#f15-report-themes-and-custom-css)
- [F01. Slow-mover and per-frame tuning relation](#f01-slow-mover-and-per-frame-tuning-relation)
<!-- root-toc:end -->

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

## F09. Consolidated super report for iterative runs

Basic iterative detection is implemented: independent runs over growing subsets of a directory, with an index of the per-pass reports. This request adds one combined report.

**Decision (made):** "Agreed. please also use a similar page layout for the super report (colors, background, ...) as the individual reports."

**Maintainer priority (2026-11-02).** Keep this request: the combined report will be useful at a later stage. The matching and evidence-selection policy still needs agreement before implementation.

**Proposal.** Read the completed pass reports and their saved run metadata, recognise findings that are the same physical object or event, and present each once with the best information from the passes that saw it. The individual reports, and their own identities, stay available.

- **Associate findings across passes** using input identities, timestamps, sky and pixel positions, motion and category-specific evidence. Report-local IDs (T, ST, A, V) are not enough. Handle split and merged tracks, missing WCS, ambiguous matches and incompatible datasets explicitly, and show disagreements instead of forcing a merge.
- **Combine evidence with provenance.** A finding may have its strongest detection in one pass, its longest track in another and its best astrometry, photometry or pictures in a third. Say how each measurement is chosen or combined, and link it to its pass and report anchor. Passes that reuse the same frames are correlated evidence, not independent confirmation.
- **Present a useful combined view:** distinct findings, the passes that contributed and their frame counts, coverage and limitations, the best pictures, and links to every source report. Combined counts are shown separately from per-pass counts.
- **Persist enough evidence.** `run-data.json` and the per-pass input manifests already record settings, outcomes, file names, dimensions, exposure and timestamps. `report-data.json` is a findings summary, not a per-frame record. Decide, and version, what per-frame positions, associations, quality flags, WCS and catalogue provenance or light-curve data must be added before promising consolidation of every category.
- **Work from saved data,** with bounded memory, including after a cancelled or failed pass.

**Next step.** Propose the matching and evidence-selection policy for agreement before implementing.

**Acceptance.** One finding seen in several passes, distinct nearby objects, split tracks, conflicting measurements, changed or missing inputs, different fields with matching file names, missing WCS and older summary-only reports. Chosen measurements stay traceable, ambiguous associations stay visible, unique counts are justified, and the individual reports are unchanged.

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

## F01. Slow-mover and per-frame tuning relation

Proposed investigation only. Compare the maximum-stack slow-mover thresholds with the tuned per-frame thresholds on the same real datasets before changing defaults. Measure recovery, false positives and run time. A maximum stack's sigma is not a single-frame sigma; the current distinction is described in JTransient's [algorithm reference](../JTransient/ALGORITHM.md). This remains a comparison of existing behaviour; any algorithm or default change needs a separate maintainer decision.
