# Agent State — features collaboration

## Collaboration

- Goal: Implement features F10, F11, F13, F14, F15, and F16 from [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md) as authorized by the maintainer on 2026-10-25.
- Specs: [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md), [DEVELOPER.md](DEVELOPER.md), [HIGH_LEVEL_DESIGN.md](HIGH_LEVEL_DESIGN.md), and JTransient's [FEATURE-REQUESTS.md](../JTransient/FEATURE-REQUESTS.md).
- Branch: `feature/features-collaboration`
- Feature baseline: `755580dea91f08d2eb3e9da025bc6068ec241001`
- Agents (ordered): A=this session (Agent A); B=maintainer-started Agent B; C=maintainer-started Agent C. No additional agents are needed.
- Overall: IN_PROGRESS
- Execution: One shared working tree, one committed Owner.
- Restart (2026-10-26): The maintainer asked Agent A to check F16 correctness first, implement necessary corrections, then continue the authorized collaboration. The overnight handoff is explicitly stale and replaced. The 00:50 restriction has expired; use the standard A -> B -> C rotation.
- Scope boundary: Only features F10, F11, F13, F14, F15, and F16. F16 is completed and committed on baseline. JTransient library changes are authorized where required for F10 (API-1) and F13 (API-2).
- Required checks: `gradlew.bat check`, `apiConsumerTest`; real-dataset and unit tests per feature specification.
- Protocol: [AGENT_COLLABORATION.md](AGENT_COLLABORATION.md) v2.2. Every agent keeps running and checks for handoff commits every 2 minutes.

## Task ledger

| ID | Task / spec | Depends on | Status | Evidence / deviations / next action |
| --- | --- | --- | --- | --- |
| F16 | Uncalibrated and misaligned frame detection with user warning | - | IN_PROGRESS | A checks baseline `755580dea91f08d2eb3e9da025bc6068ec241001` against the decided warning/override, import/pre-flight, reference-data and API/GUI requirements; necessary corrections are authorized. Prior implementer recorded check and consumer PASS; independent verification pending. |
| F10 | Live view: pictures of the running phase, one every 3 s | - | TODO | Overnight turn was interrupted without code changes. Implement JTransient API-1 (stageStarted, wantsUpdate check, thread-safe in-stage snapshots) and the SpacePixels 3-second clock after F16 correctness verification. |
| F11 | Animation speed control in the report | - | TODO | Write image frame sheets alongside GIFs; add single persistent playback speed control (0.25x–4x) and pause frame-stepping to report. |
| F13 | Manual Transient Inspection with trial per-frame settings | - | TODO | Implement JTransient API-2 (re-detect transients against kept reference) and SpacePixels trial spinners / Apply to Settings in TransientInspectionFrame. |
| F14 | Matching backgrounds in the variable-star cutouts | - | TODO | PhotometryReportSectionWriter cutouts: match black point to local skyMedian with shared span, scale faintest cutout by zeroPoint difference. |
| F15 | Clean moving-object animations to download, narrow or wide | - | TODO | TargetVisualizationSectionWriter: export narrow and wide GIFs without circle/label; add Download animation links to report card. |

Statuses: TODO, IN_PROGRESS, REVIEW, DONE, DEFERRED. Independent review is required for DONE.

## Maintainer decisions

- F10: One clock, no exceptions (3 s between pictures). Dropped pending on phase change. Engine checked via cheap wantsUpdate() before building snapshots. Byte-identical detection outputs.
- F11: Option 1 (frame sheets + GIFs). One report-wide control always visible at top; pause frame-stepping.
- F13: Re-detect against kept reference. Viewer trial policy (Apply to Settings, per-frame background re-detect, read-only master values).
- F14: Match sky background per cutout; scale faintest cutout by zero-point difference; update caption.
- F15: Moving objects only. Write narrow and wide (2x, 400–1000px) clean GIFs with report.
- F16: Check hot-pixel density and star alignment on import and pre-flight; warn user with cancel recommendation. Thresholds verified on reference benchmarks.
