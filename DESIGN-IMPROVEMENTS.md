# Design improvements

This file lists design, refactoring and code-quality work for SpacePixels: changes to how the code is organised, not to what the application does. New or changed functionality is in [FEATURE-REQUESTS.md](FEATURE-REQUESTS.md). The current design is described in [DEVELOPER.md](DEVELOPER.md) and [HIGH_LEVEL_DESIGN.md](HIGH_LEVEL_DESIGN.md).

Every item here is a proposal until the maintainer decides on it. When an item is done, describe the result in DEVELOPER.md or HIGH_LEVEL_DESIGN.md and remove it from this file; git history keeps the record.

**How to use the issue reviews (2026-10-24).** Before implementing an item, consider and evaluate the review comments at its end against the current code. Record which comments are accepted, adjusted or rejected, why, and the relevant verification with the change. They are review input, not authorization to implement the proposal or automatic proof of a current bug. The review was static; no runtime or real-data checks were run. Recheck references and caller counts as other work lands.

## Code review of 2026-10-23

A fresh review of the whole application (about 50,000 lines of main code and 17,000 of tests), against the principles in [DEVELOPER.md](DEVELOPER.md#principles): readable code, a clear design with clear boundaries, and a few chosen patterns applied consistently, with no abstraction that lacks a real need.

**Overall.** The architecture is sound. Jobs, commands, the session handover, the viewers and the public API are small, well-bounded and consistent, and should stay as they are. The over-engineering the maintainer suspected is real, but it is concentrated in three kinds of place:

1. **State with more than one owner.** The rendering preferences and the session's frame list each lived in two or three places. Each copy then needed machinery to stay in step: write barriers, version checks, rollback, static caches and "capture" steps. Giving each piece of state one owner removes that machinery rather than adding to it. Both now have one owner (SP-02 and SP-04, 2026-10-24).
2. **Layers kept for compatibility after compatibility stopped mattering.** The libraries are now developed together with the application, and old signatures need not survive. Still left over are chains of overloads that exist for older callers.
3. **Mechanisms heavier than their use.** Examples were two near-identical write queues for small JSON files, now one (SP-09), and a binding framework whose application side wrote to global static fields, now direct bindings without client-property indirection (SP-02 and SP-07).

Line counts alone are not a problem. Large report section writers and panels are acceptable when they read top to bottom as one responsibility.

### What is good and should stay

- **Jobs** ([JobCoordinator](src/main/java/eu/startales/spacepixels/jobs/JobCoordinator.java), `Job`, `JobOutcome`): one admission rule, a sealed typed outcome, exactly one end per job, and no Swing. It is the right size.
- **Commands** ([CommandAvailability](src/main/java/eu/startales/spacepixels/gui/CommandAvailability.java), `CommandAction`): pure rules over a `Facts` record, one Swing `Action` per command, re-checked when invoked. This is a clear and testable pattern; use it for any new command.
- **Session handover** (`SessionOwner.replace(expected, candidate)`): simple, explicit and tested without windows.
- **Viewers** (`SequenceViewerFrame` template method, newest request wins, `FrameView`, `StretchControls`, `ViewerSupport`): good reuse by composition.
- **Typed detection setting definitions** (one table of `DetectionSetting` per settings page): explicit and searchable.
- **Staged file publication** (`StagedFile`, `AtomicReportFile`), and **boundary tests** (`DependencyRulesTest`, the opt-in GUI mechanics suite).
- **The public API** (`SpacePixelsPipelineRequest` / `Result`, `DefaultSpacePixelsPipelineApi`): a request object in, a result object out, with no GUI.

### Recommended changes

**Design-improvements cycle (2026-10-24, branch `feature/design-improvements`).** Done and independently reviewed: SP-20, SP-02, SP-09, SP-04, SP-10, SP-13, SP-06, SP-05, SP-07, SP-08 and SP-11 (completed 2026-10-25). SP-04's last decision, the few-frame rule, then moved into JTransient (2026-10-25). Their results are described in DEVELOPER.md and HIGH_LEVEL_DESIGN.md, and the completed items are removed here. Pending: SP-16 and SP-19, followed by the mechanical SP-14 package move. Their proposals and issue reviews below still apply.

| ID | Change | Priority | Size |
|---|---|---|---|
| [SP-14](#sp-14-package-names-that-say-what-they-hold) | Package names that say what they hold | Low | Medium, mechanical |
| [SP-16](#sp-16-check-the-ui-at-larger-display-scaling) | Check the UI at larger display scaling | Low | Small |
| [SP-19](#sp-19-finish-the-common-base-of-the-image-viewers) | Finish the common base of the image viewers | Medium | Medium |
| [SP-21](#sp-21-upgrade-nom-tam-fits-after-the-dither-fix) | Upgrade nom-tam-fits after the dither fix | Low | Small |
| [SP-22](#sp-22-upgrade-gson-to-2150) | Upgrade Gson to 2.15.0 | Low | Small |

**Suggested order.**

1. Finish SP-19; inspect scaling (SP-16) after the viewer controls settle. The catalogue controller, panel collaborators, direct settings rows, application I/O queue, session-owned services, report boundaries and FITS input helpers are in place.
2. Do SP-14 last: it is mechanical, and renaming first would only make the other changes harder to review.

Keep dependency maintenance (SP-21 and SP-22) separate from refactorings, according to each item's timing.

Refactorings are intended to preserve behaviour. Evaluate the exceptions raised by the issue reviews, including SP-19's viewer lifetime and settings policy, as explicit behaviour decisions. Check implementation changes with the relevant unit and GUI mechanics tests. Items that touch detection or reports also need byte-identical deterministic `report-data.json`, `detection_config.json` and CSV content on the reference datasets, using the normalization and comparison method in DEVELOPER.md.

### SP-14. Package names that say what they hold

**Problem.** `util` (55 classes) is the application's core, not a set of utilities. It holds the services, domain types (`PlateSolutionCorrection`, `TrackOverlay`, `VariableStarOverlay`, `ExposureGroup`, `BlinkSequence`), FITS input/output and WCS. `util.reporting` (52 classes) is a subsystem, not a utility.

**Proposal.** Domain-named packages: `fits` (input, preparation, conversion, WCS), `detection` (pipeline services, run options, overlays), `platesolve`, `report` (with a `report.lookup` subpackage for the SatChecker/JPL/SkyBoT proxy and builders) and `skycatalog`. Do it last, as a mechanical move, and keep `DependencyRulesTest` in step.

**Issue review (2026-10-24; evaluate before implementation).**
- Update `build.gradle` source-set and resource include patterns as well as Java imports and `DependencyRulesTest`; the headless artifact currently explicitly includes `util/**`.
- `FitsFileInformation` and visualization/run-option types are exposed through public request/result signatures. Moving them changes consumer APIs even if the implementation move is mechanical. Identify those breaks, update API examples and release documentation, and run `check` plus the isolated `apiConsumerTest` against the staged artifact.

### SP-16. Check the UI at larger display scaling

What remains of the earlier visual-rules item. Inspect the empty and loaded main window, the settings pages, the Astrometry Config tab, the viewers and the progress dialog at 100, 150 and 200 % display scaling and with a larger base font. Fix the local widths and font sizes found in the Overview status, the table HTML and the viewer controls. Layout tests cover forms and base fonts; display scaling of the whole window has not been inspected.

**Issue review (2026-10-24; evaluate before implementation).**
- No additional architectural blocker was identified. Retain the real keyboard/focus checks while inspecting clipping, wrapping, scroll access and controls at each scale; visual consolidation must not alter the existing text-field and viewer-key rules.
- Keep local layout fixes separate from changing viewer settings policy in SP-19, so their effects can be assessed independently.

### SP-19. Finish the common base of the image viewers

**Request (2026-10-23):** "I have another idea, related to the preview windows. To create a common base for all of them. Maybe they already have common mechanisms, but please check what we can have once, common for all of them."

**Implementation.** SP-19A uses `FrameView` for the stretch previews and the star-mask stack, including the mask overlay, and moves `bindStretch` and the dataset-folder name into `ViewerSupport`. Blink stays its own window. SP-19B and SP-19C remain.

**What is shared already.**
- `SequenceViewerFrame` is the base of Preview Frame, Manual Transient Inspection and the Image Stretch tab's sequence viewer (`StretchedSequenceFrame`). It is a template method that provides the window, stepping through frames, background preparation where the newest request wins, the overlays, and closing and releasing.
- `FrameView` (zoom, pan, overlays and the cursor readout), `StretchControls` and `ViewerSupport` (key bindings, zoom controls, status bar and cursor readout) are used by those three windows and by Blink.

**What is not shared:**

| Window | Window class | Image component | Closed when the session changes |
|---|---|---|---|
| Preview Frame | `SequenceViewerFrame` | `FrameView` | Yes, by the main panel |
| Manual Transient Inspection | `SequenceViewerFrame` | `FrameView` | **No** |
| Image Stretch sequence | `SequenceViewerFrame` | `FrameView` | Yes, by the stretch panel |
| Blink | its own `JFrame` (764 lines) | `FrameView` | Yes, by the main panel |
| Star Mask Explorer | its own `JFrame` (443 lines) | `ZoomableImageView` | **No**; its Apply button checks the session |
| Image Stretch tab preview | a panel | `ZoomableImageView` | Not applicable |

**Findings and proposals:**
1. **Two image components.** `ZoomableImageView` (288 lines) repeats `FrameView`'s zoom, pan and image pyramid (`levelFor` and the halved copies) for the Star Mask Explorer and the Image Stretch tab. Proposal: use `FrameView` everywhere, with an image overlay layer added for the star mask, and remove `ZoomableImageView`. Every image view then has the same keys, cursor readout and fit/100 % toggle.
2. **Two ways to try settings.**
   - Preview Frame's spinners edit the Detection Settings directly.
   - The Star Mask Explorer keeps trial values, with an "Apply to Settings" button.

   Each re-runs 300 ms after a change, with its own spinner code, and F13 would add a third window of this kind. Proposal:
   - one small trial-settings row, used by all three windows: the spinners, the delayed re-run, the note about invalid text, Enter and Esc, and disabling while a job runs;
   - one rule for whether a window edits the settings or offers "Apply to Settings". This is a decision point; F13 proposes Apply.
3. **Each owner closes its own windows.** Preview Frame and Blink are closed by the main panel, and the Image Stretch sequence by the stretch panel. Manual Transient Inspection and the Star Mask Explorer are not closed at all: after a new import they stay open on the old session, and the inspection window keeps that session's frames in memory until it is closed by hand. Proposal: every viewer registers with one owner, the application window's session listeners, which closes them all on a session change and at exit.
4. **Small duplicates.** `bindStretch` is identical in `SequenceViewerFrame` and `BlinkFrame`, and the session's name (its folder's name) is computed in three places. Move both into `ViewerSupport`.
5. **Blink stays its own window.** It preloads every frame for smooth playback and has the frame list with its skip boxes, which does not fit the one-frame-at-a-time `SequenceViewerFrame`. Forcing it onto the base would add hooks for a single subclass. Blink keeps using the shared parts by composition, as now, and gains items 3 and 4. The Star Mask Explorer shows a single image and also stays its own window, gaining item 1.

**In short:** the common base exists already. The work is to finish it, not to add another layer.

**Order:**
1. Item 3 first, since it changes what the user sees.
2. Then items 1 and 4.
3. Item 2 together with F13.

**Issue review (2026-10-24; evaluate before implementation).**
- **Viewer lifetime is a product decision.** DEVELOPER.md deliberately permits independent inspection windows to retain the old session's data. Closing every viewer on import changes that workflow; decide the desired policy explicitly and update the manual and lifecycle tests. Do not describe the existing choice as an accidental leak solely because buffers remain while a window is open.
- The Preview Frame live-edit versus Apply policy is also a behaviour decision. Keep it separate from sharing image components and controls, and preserve real key/focus tests for each viewer.
- A central viewer owner must unregister disposed windows and preserve cancellation, buffer release and listener detachment. Merely hiding all windows does not establish resource release. Keeping Blink separate by composition remains appropriate.

### SP-21. Upgrade nom-tam-fits after the dither fix

**Deferred until the upstream fix is released.** Follow-up to the completed [SP-18 upgrade](DEVELOPER.md#dependencies). Upgrade nom-tam-fits to 1.23.1 when available, after confirming the fix for the dither sequence at blank and zero pixels (#908).

With 1.23.0, dithered floating-point `.fz` frames still differ from cfitsio by about one quantization step in tiles that contain blank or zero pixels: 1 ADU after SpacePixels' 16-bit conversion, on a few hundred pixels of a frame. Integer frames are exact.

**Verification.** Coordinate the upgrade across the repositories that use nom-tam-fits, repeat the comparison with cfitsio (through astropy), and run the reference-data regression in DEVELOPER.md. Report any changed pixels and detection results before committing.

### SP-22. Upgrade Gson to 2.15.0

**Deferred to the next dependency update.** Follow-up to the completed [SP-18 upgrade](DEVELOPER.md#dependencies). Upgrade Gson from 2.14.0 to 2.15.0 in all three repositories, reviewing its change of the default `Map` type to `LinkedHashMap`.

**Verification.** Round-trip saved settings files and check that their values and compatibility are preserved; run the relevant tests and reference-data comparisons from DEVELOPER.md.

## Removed from the previous plan (2026-10-23)

- **S16, prepared diagnostic states and re-export.** Dropped. No code path renders a saved result again, so nothing would read the prepared states twice.
- **The rest of D09 and D10.** Done. The selected-release check is part of the release procedure in DEVELOPER.md, and the engine side is JTransient JA-11.
- **D12, shared visual rules.** Reduced to SP-16.
- **The target architecture map, the pattern table, the ownership table and the "constraints" list.** What still applies is in DEVELOPER.md, as principles, patterns and things to avoid.
- **The completed-work archive and the request register.** Removed. Git history keeps them.
- **SP-15, pass the live-view sink with the run.** Dropped (2026-10-25). `RunPreviewScope` stays: the job opens and closes it, a headless run passes no sink, and the report image savers offer a picture without a parameter. Revisit only if report images are written on another thread.
