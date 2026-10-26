# Agent State — features collaboration

## Collaboration

- Goal: Implement features F10, F11, F13, F14, F15, and F16 from [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md) as authorized by the maintainer on 2026-10-25.
- Specs: [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md), [DEVELOPER.md](DEVELOPER.md), [HIGH_LEVEL_DESIGN.md](HIGH_LEVEL_DESIGN.md), and JTransient's [FEATURE-REQUESTS.md](../JTransient/FEATURE-REQUESTS.md).
- Branch: `feature/features-collaboration`
- Feature baseline: `755580dea91f08d2eb3e9da025bc6068ec241001`
- Agents (ordered): A=maintainer-started Agent A; B=maintainer-started Agent B; C=maintainer-started Agent C. Each session keeps its maintainer-assigned identity. No additional agents are needed.
- Overall: IN_PROGRESS
- Execution: One shared working tree, one committed Owner.
- Restart (2026-10-26): The maintainer asked Agent A to check F16 correctness first, implement necessary corrections, then continue the authorized collaboration. The overnight handoff is explicitly stale and replaced. The 00:50 restriction has expired; use the standard A -> B -> C rotation.
- Scope boundary: Only features F10, F11, F13, F14, F15, and F16. F16 and F10 are independently reviewed. JTransient library changes are authorized where required for F10 (API-1) and F13 (API-2).
- Required checks: `gradlew.bat check`, `apiConsumerTest`; real-dataset and unit tests per feature specification.
- Protocol: [AGENT_COLLABORATION.md](AGENT_COLLABORATION.md) v2.2. Every agent keeps running and checks for handoff commits every 2 minutes.

## Task ledger

| ID | Task / spec | Depends on | Status | Evidence / deviations / next action |
| --- | --- | --- | --- | --- |
| F16 | Uncalibrated and misaligned frame detection with user warning | - | DONE | Reviewed `0b30b64f0b13e444d3434ed5aa54ace6dc34c330` from pre-feature base `87b33b8` (prior F16 `755580d`). Import declines unless Proceed Anyway; Detect and Detect Iteratively ask before heavy work; API and CLI record the warning and the API can fail fast. Unreadable samples fail and cancellation is not turned into a clean result. Deviation: the strict spike rule warns at 5/MP and 30 spikes, because the positive reference has 242 spikes (38.12/MP) and the 17 clean references have at most 15 (1.66/MP); the provisional 35/MP and 300-count pair would miss that positive set. Alignment stays above 2.5 px. Reran FrameCalibrationInspectorTest 12 (0 skipped, all 18 datasets), CalibrationSafetyTest 3, GuiDetectionReportPromptTest 10, DefaultSpacePixelsPipelineApiTest 5, BatchDetectionCliTest 3, and apiConsumerTest 10: PASS. GUI mechanics not rerun (they move the mouse); implementer recorded 3. No code change in review. |
| F10 | Live view: pictures of the running phase, one every 3 s | - | DONE | Reviewed `60ade5b2da8fafb675a259b41749289ec7372e67` (base `260aa9412eefa833e2f47658b9b05f5c765a3cb3`) and JTransient `0c5a38c53e74bcab8db9378a2b9a21dca42d5f74` on main. One 3 s clock including stage changes; pending picture dropped on stage start; in-stage snapshots built only after atomic wantsUpdate check in extraction, tracking and photometry; headless/hidden runs build nothing; detection results byte-identical. Reran gradlew check (543 tests, 7 skipped) and apiConsumerTest (10 tests): PASS. No code change in review. |
| F11 | Animation speed control in the report | - | IN_PROGRESS | A reviewed fix `80138158c94831c6021078e9740eaa21e6b80937`; round-1 registry/streaming/browser findings resolved. Reran writer/export/dependency 17, Node interactions 4, external memory/buffer and local-file browser probes: PASS. FIX round 2/2 requests full-resolution bounded sheets: the new 2048-pixel whole-sheet cap reduces 120 native 512x512 frames to 186x186, erasing a compact marker. Preserve pixels/display size and playback across sheet pages; remeasure final report growth. Internal helper signature changes are permitted by API.md. |
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
