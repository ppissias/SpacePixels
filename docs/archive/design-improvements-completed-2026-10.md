# Design improvements: completed work, October 2026

> Historical record, archived 2026-10-21. These sections were moved out of [DESIGN-IMPROVEMENTS.md](../../DESIGN-IMPROVEMENTS.md) once their work was done, so that the plan holds only pending work and open decisions. They keep the decisions, the evidence and the validation as written at the time, including statements that later changes superseded. Current behaviour is described in [HIGH_LEVEL_DESIGN.md](../../HIGH_LEVEL_DESIGN.md), [DEVELOPER.md](../../DEVELOPER.md) and the [manual](../../MANUAL.md). Where a section names work that was still open when it was archived, that work continues in the plan.

Contents:
- [F11 clarified single-project API and CLI publication](#f11-clarified-single-project-api-and-cli-publication-2026-10-22)
- [F11 original packaging implementation (superseded)](#f11-headless-apicore-maven-central-packaging-2026-10-22)
- [S13 explicit GUI interactions and headless processing boundary](#s13-explicit-gui-interactions-and-headless-processing-boundary-2026-10-22)
- [Plan consistency review](#plan-consistency-review-2026-10-22) and [historical GUI mechanics draft](#gui-mechanics-planning-snapshot-2026-10-22)
- [JA-01 shared settings rules and review integration](#ja-01-shared-settings-rules-and-review-integration-2026-10-22)
- [The 2026-10 review: assessment and scope](#overall-assessment)
- Findings: [D01](#d01-treat-file-replacement-as-a-shared-application-responsibility), [D02](#d02-give-every-background-job-an-owner-and-an-identity) (the parts done), [D03](#d03-extend-the-viewers-latest-request-wins-rule-to-stretch-previews), [D04](#d04-make-cancellation-failure-and-resource-cleanup-explicit) (the parts done), [D05](#d05-build-a-validated-settings-snapshot-instead-of-reading-a-mutable-panel), [D07](#d07-derive-availability-from-state-and-use-real-command-actions)
- [The architecture adoption review](#architecture-adoption-review), with the steps finished by 2026-10-20 (S1–S6, S9, S11)
- [The EventBus decision](#guava-eventbus-keep-or-replace) and [the first job-coordinator slice](#s2-and-s3-first-slice-a-job-coordinator-for-the-detection-jobs)
- [Delivery stages A and B](#delivery-plan-as-of-2026-10-20) and [the earlier completed-work list](#completed-work-removed-from-the-queue)

## F11 clarified single-project API and CLI publication (2026-10-22)

**User clarification.** Maven Central should contain only the headless API, everything it needs and useful command-line tools, under the original `eu.startales.spacepixels:spacepixels:<version>` coordinate. Keep the desktop application build, not a desktop Maven publication. A separate core project is unnecessary. This contract supersedes the original two-module/two-publication layout recorded below; F11 remains completed, not another active backlog item.

- One Gradle project uses complementary `main` (API/config/session/util/tools and report/version resources) and `desktop` (GUI/jobs/tasks/icons) source sets without moving source files or duplicating classes. The standard binary/sources/Javadoc artifacts describe only `main`; an unpublished `desktopJar` and desktop-only FlatLaf retain the application build and distribution launchers.
- The root `build.gradle` owns the single publication, signing, metadata and `mavenCentralBundle` command. It builds a local signed API/CLI ZIP only, never uploads, and whitelists current artifact filenames so old core versions/publications or desktop classifiers cannot enter the bundle from stale staging files. No separate core project, core coordinate or shared two-module publishing script remains.
- JTransient remains `api` because supported request/result types expose its DTOs. JPlateSolve/FITS/Gson remain runtime-transitive `implementation` dependencies. Internal solver compatibility helpers do not expand the supported embedding API; consumers compiling against those helpers must declare the solver themselves.
- `verifyApiPublication` checks headless binary/source/Javadoc ownership, CLI inclusion, report/version assets and dependency metadata. `verifyDesktopArchive` checks disjoint desktop/API ownership and that only the API publication exists. Source dependency rules include the CLI tools. `apiConsumerTest` validates actual Maven-resolved JARs with substitution disabled, in Gradle metadata and POM-only modes: five preparation/detection/Auto-Tune/report cases plus packaged batch CLI execution and injector availability, with explicit engine compile/solver runtime scope checks.
- Desktop `run`, `installDist`, `distZip` and `distTar` retain both application JARs/dependencies. CLI launchers use only the API/runtime graph, without FlatLaf or the desktop companion. The API artifact is not shaded and does not replace a complete desktop installation.

**Executed validation (Java 21, both local composites enabled).** `check :JTransient:check installDist distZip distTar` passes: 388 application tests (four opt-in skips, no failures), unchanged/up-to-date engine checks, API/source/Javadoc/POM/module checks and desktop ownership checks. `apiConsumerTest` passes all seven isolated cases in each Gradle-metadata and POM-only mode (`-PapiConsumerMetadata=pom`), including the actual batch CLI subprocess and injector help. The installed batch launcher help and two CLI regression tests pass.

`mavenCentralBundle` succeeds with the existing local signing/developer configuration. `build/distributions/spacepixels-2026.10-04-central-bundle.zip` contains exactly one API GAV subtree: five primary binary/sources/Javadoc/POM/module files, five PGP signature envelopes and checksum files (50 files total). All 20 SHA-256/SHA-512 checksums are recomputed successfully. Old core artifacts remain in local staging but are excluded from this bundle. Signature envelopes/presence are inspected, not a new external signing-key certification; no upload occurs.

Nine GUI mechanics cases pass against the installed distribution's JARs, not application class directories: main window/import/settings, viewer, normal report prompts and iterative configuration/run behavior. Validation uses the ignored `build/s13-validation.init.gradle` to force headless ordinary tests and avoid running-app JAR locks, plus an ignored installed-GUI classpath init script and `JAVA_TOOL_OPTIONS=--add-opens=java.base/java.util=ALL-UNNAMED` for the existing AssertJ monitor. Generated launchers and installed library contents confirm only desktop launchers require the desktop companion/FlatLaf; CLI launchers exclude both. Documentation validation checks 169 local links/anchors and 14 unique canonical backlog IDs; whitespace checks pass. IDE Gradle re-import and new real-data numerical comparisons are not exercised; source paths and scientific code are unchanged.

**Release limitation.** No Central upload or new release certification is claimed. Java 21 and headless AWT/`java.desktop` are still required. JA-11 must verify that the actual released engine/solver pair supplies the source APIs used here; local composite/staged-source tests do not prove that. Scientific algorithms and S14 run-context work are unchanged. Current contracts: [API.md](../../API.md#dependency), [DEVELOPER.md](../../DEVELOPER.md#build-source-sets-and-artifacts), [PUBLISHING.md](../../PUBLISHING.md), [HIGH_LEVEL_DESIGN.md](../../HIGH_LEVEL_DESIGN.md) and the README.

## F11 headless API/core Maven Central packaging (2026-10-22)

**Historical/superseded layout.** The following records what the initial F11 implementation and its tests actually did. Its core/desktop publication model and commands are not current instructions; use the clarified single-project contract above and the current developer/publishing guides.


**Decision and scope.** Deliver the approved packaging follow-up to S13 as a real headless Java-library module, not an API-classifier JAR filter. Preserve Java package names, supported API signatures, scientific behavior, source paths and the existing desktop coordinate/launchers. No S14 context refactor, engine change, Central upload or published-pair certification is part of F11.

- `:core` owns `api`, `config`, `session` and `util`, including reporting/catalogues, required JS/JSON and expanded version metadata. It publishes `eu.startales.spacepixels:spacepixels-core:2026.10-04`, with Java 21 binary, sources, Javadocs, POM and Gradle metadata. JTransient is an API dependency; FITS/Gson/JPlateSolve are runtime dependencies. There is no GUI/jobs/tasks/tools code, GUI icon or FlatLaf dependency. Headless AWT/`java.desktop` remains required for images/reports. This is a cohesive package-level core, not per-class shrinking of every unused helper.
- Root owns GUI/jobs/tasks/tools and icons. It re-exports core through `api project(':core')`. Complementary source sets use existing directories, compiling/packaging each class once. The existing `spacepixels` coordinate remains usable by Maven/Gradle consumers through its transitive core dependency; direct/manual classpaths must add the core JAR. Desktop and CLI distributions include both automatically. No implementation is copied into a legacy fat JAR.
- `gradle/publishing.gradle` shares publication metadata, credential validation, signing and staging. The core-only ZIP contains only its current-version GAV subtree; the existing root ZIP contains desktop plus core. Both stage locally under `build/repos/central-staging` and never upload. Explicit root Maven Local publication publishes core first. Existing signing/developer configuration remains local/ignored.
- `verifyCorePublication` checks binary/sources/Javadoc contents, report resources, expanded version metadata and dependency scopes/coordinates in POM/Gradle metadata. `verifyDesktopPublication` checks desktop/core ownership, icons, launchable classes and the legacy transitive dependency. Dependency rules now cover every core package, forbidding desktop workflow/GUI/Swing references while permitting headless graphics.
- `coreConsumerTest`, included in root `check`, runs a separate build with project/composite substitution disabled and only core coordinates plus JUnit. Real resolved sibling binaries and their actual generated POMs are staged locally for source-pair testing; no synthetic dependency POM, Maven Local publication or remote upload is used. Groups of disabled siblings are excluded from the consumer's staging repository, so stale source artifacts cannot mask a published-pair check. A POM-only mode verifies consumption without Gradle-module redirection.
- Five isolated consumer cases cover artifact/classpath identity and desktop exclusion, strict refusal, automatic FITS/XISF preparation, detection/exposed DTOs, calibrated Auto-Tune and report JS/JSON/headless PNG resources. The tiny noiseless UI fixture correctly failed calibration; a documented noisy Gaussian-star FITS fixture was added for the successful tuning path, without changing production algorithms. Fixture FITS writers compile against resolved runtime dependencies rather than adding missing production libraries to the consumer.

**Executed validation (Java 21, both local composites enabled).** `check :JTransient:check installDist javadoc sourcesJar` passes: 388 application tests (four opt-in skips), 235 engine tests with unchanged/up-to-date engine inputs, and core/desktop artifact checks. `coreConsumerTest` and `coreConsumerTest -PcoreConsumerMetadata=pom` pass all five isolated cases in both metadata modes. Ordinary checks run with `java.awt.headless=true` via the ignored `build/s13-validation.init.gradle`, which also redirects JAR output away from running-app locks. The two existing `SequenceViewerFrame` Javadoc-link warnings are unchanged.

`:core:mavenCentralBundle mavenCentralBundle` succeeds with the existing local signing/developer configuration. Core ZIP: `core/build/distributions/spacepixels-core-2026.10-04-central-bundle.zip`, only core's GAV subtree, five primary binary/sources/Javadoc/POM/module files and their PGP signature envelopes, with 20 SHA-256/SHA-512 checksums recomputed successfully. Desktop ZIP: `build/distributions/spacepixels-2026.10-04-central-bundle.zip`, exactly desktop plus core, ten primary files/signature envelopes and 40 verified checksums. No upload occurs; signature presence/envelopes and checksums were inspected, not a new external signing-key certification.

`installDist` preserves the wrapper/desktop/tool scripts and includes both JARs. Installed `batchDetect --help` succeeds; two `BatchDetectionCliTest` cases also pass with only installed production JARs plus test/JUnit classes, not main output directories. Nine Window/ReportPrompt/Viewer/Iterative GUI mechanics cases pass against the installed distribution JARs using the ignored `build/f11-installed-gui.init.gradle`; it replaces main production outputs with installed libraries and rejects main class directories. GUI validation uses the existing AssertJ `JAVA_TOOL_OPTIONS=--add-opens=java.base/java.util=ALL-UNNAMED` workaround. No new real-data scientific comparison is claimed for this packaging-only change. Both local sibling flags remain enabled and sibling source trees remain unchanged.

Documentation validation checks 144 local links/anchors and 14 unique remaining canonical backlog IDs, with F11 archived rather than duplicated in the active queue. Whitespace checks pass. IDE Gradle re-import is not exercised; sources retain their existing paths and the documented Gradle source-set ownership remains authoritative.

**Release limitation.** Local staging proves the tested source pair's packaging, not that its required JA-01/cancellation engine APIs are available from Central. JA-11 must validate the actual supplying engine/solver releases and Java contract before distribution. S14–S16 remain separate D09/D10 slices. The corrected current coordinates, build ownership, consumer commands and local bundle paths are documented in [API.md](../../API.md#dependency), [DEVELOPER.md](../../DEVELOPER.md#build-source-sets-and-artifacts), [PUBLISHING.md](../../PUBLISHING.md), [HIGH_LEVEL_DESIGN.md](../../HIGH_LEVEL_DESIGN.md) and the README.

## S13 explicit GUI interactions and headless processing boundary (2026-10-22)

**Decision and scope.** Complete Stage C's remaining boundary, not a new processing algorithm or D09/S14 run-context implementation. Move the existing report-choice panel/confirmation into `gui.GuiDetectionReportPrompt`; inject import/report interactions from the desktop, and remove GUI-owned logging from processing services. Keep existing public signatures, engine inputs, detection measurements, report formats, threshold/checkbox semantics and desktop conversion/exposure-group workflows. Headless AWT image/graphics rendering is deliberately allowed.

- `MainApplicationPanel` supplies report callbacks to normal and independent iterative jobs. The GUI adapter handles worker/EDT callers, asks only above 50 classified detections, and preserves each pass's heading, WCS facts and effective settings. Accepted options replace only `DetectionSummary`'s report inclusion; No/close/failure do not apply selections. Interrupted workers decline, retain their interrupt flag and cancel an unanswered queued question.
- `ApplicationWindow` supplies `GuiFitsImportInteraction` through `FitsImportTask` into the facade and exposure-group decisions. The existing conversions, new-directory redirects, negative-value handling, output names and declined-import session preservation remain. Without an interaction, metadata loading checks sequence consistency before strict readiness and never writes conversion derivatives or opens UI. Existing `getFitsfileInformationHeadless()` behavior is preserved.
- **Compatibility clarification:** factories/tasks no longer instantiate GUI adapters behind the caller's back. Existing facade factories without interaction validate strictly; unsupported input must be prepared first. The deprecated `DetectionSummary.confirmReport(...)` signature remains a noninteractive threshold guard; the deprecated exposure-group signature declines without copying. Detection-task constructors without a callback use the same 50-count guard; an explicit null callback permits all report export. Desktop callers use the new explicit adapters. The supported pipeline API/request/result contracts and its noninteractive export behavior are unchanged.
- `PlateSolveService` and `XisfImageConverter` own `java.util.logging` loggers with unchanged message text/levels. Processing no longer references or initializes `ApplicationWindow` for logging. A recursive dependency rule covers the entire `util` tree, including reporting/catalogue helpers, forbidding `gui`/Swing while retaining headless rendering. Existing stricter jobs/session/FITS boundary rules remain.
- Every new type/method/constructor is documented. The active plan removes S13 from the canonical backlog and architecture mapping, closes Stage C and proposes only the next application-side S14 slice for discussion; no other feature is authorized.

**Verification.** The ordinary composite check and Javadoc generation pass after retaining sequence-consistency error precedence in the facade's newly noninteractive path. Existing report/iterative parity tests compare measurements, IDs, rejection indices, settings and CSVs against independent ordinary runs. Focused additions cover injected normal/per-pass decisions, explicit facade conversion/redirect and strict refusal, report eligibility/counts/selections, worker/EDT confirmation, refusal and interruption. The former reflective util dialog tests now inspect the GUI adapter directly without displaying a window. Desktop checks additionally exercise the report question above a foreground job's modal progress window, Yes/No and report-only options, and real float-import conversion refusal preserving the open session.

**Executed validation:** Java 21, both local composites enabled. `gradlew.bat check :JTransient:check javadoc -I build/s13-validation.init.gradle --console=plain` passes: 387 application tests (four opt-in skips), 235 engine tests, zero failures/errors. The ignored init script forces `java.awt.headless=true` for ordinary test workers and redirects JAR output away from running-app locks. Javadoc was generated in the preceding check and is up-to-date in the final run; its two existing `SequenceViewerFrame` link warnings are unrelated.

`gradlew.bat guiMechanicsTest --tests '*ReportPromptMechanicsTest' --tests '*WindowMechanicsTest' --tests '*IterativeDetectionMechanicsTest' -I build/cancellation-validation.init.gradle --console=plain` passes all eight selected desktop cases, using `JAVA_TOOL_OPTIONS=--add-opens=java.base/java.util=ALL-UNNAMED` for the existing AssertJ monitor. The ordinary adapter tests also check interruption during an open question: a waiting worker declines with its flag intact and a late answer cannot apply choices. An already visible modal question is not forcibly disposed by interrupting its worker. Validation also checks 136 local documentation links/anchors and whitespace. Local validation scripts/logs are ignored, not release/build-policy changes.

This is an application wiring/presentation change: no new scientific reference-data comparison, published dependency-pair certification or engine algorithm change is claimed. Both local sibling flags remain enabled and the sibling checkouts are unchanged.

Current contracts: [DEVELOPER.md](../../DEVELOPER.md#the-packages), [HIGH_LEVEL_DESIGN.md](../../HIGH_LEVEL_DESIGN.md) and [API.md](../../API.md#official-api-surface). S14–S16 remain D09/D10 slices; F07's combined compressed-32-bit conversion remains separate work.

## JA-01 shared settings rules and review integration (2026-10-22)

The coordinated JA-01 implementation resolves D05's outstanding GUI/profile/API rules split and the [107-field dependency review](../detection-settings-rule-review.md#implementation-decisions-ja-01-2026-10-22). Engine-owned `DetectionConfigRules` defines metadata, structured issues and explicit copy-based alignment. SpacePixels owns English presentation, editor validation/focus and widget binding, with no duplicate numeric comparisons. GUI, profiles, API and iterative requests use the same service; `DetectionSettingsRules` remains a deprecated presentation adapter. Requests and operations keep their own config snapshots, and SpacePixels uses detached `tuneSnapshot` results.

The original six GUI pair directions are preserved, including the historical rhythm/jump policy. New quality-grow, geometric jitter/jump, positive star-cap and limited-amplitude conflicts are advisory. **Review rules…** shows severity/stage/rule/field IDs and optionally applies only the listed `REVIEWED_CONSISTENCY` changes; hidden quality values survive Keep settings and can be aligned explicitly without exposing a control. Non-finite persisted numbers are errors; finite legacy profiles, independent image thresholds and automatic/disabled/uncapped sentinels remain accepted. Runtime effective annulus expansion is centralized in JTransient and never persisted as rewritten factors.

Validation: `check :JTransient:check` passes, with 375 application tests (four opt-in skips) and 235 engine tests. Nine settings/viewer GUI mechanics cases pass, covering the new dialog, all six pair directions, explicit hidden alignment/save, invalid-text/revert and delayed/failed preference writes. API/profile equivalence covers all 75 double fields with NaN and both infinities. Targeted engine regressions cover operation capture, success/fallback aliasing, true geometric pruning above the raw cap and expanded annulus selection/crossings/flux. Preview Frame/Blink navigation and annotations also pass.

The read-only `EngineCancellationRealDataIT.main` comparison uses all 153 africano-reg frames (2494 x 1587), engine defaults plus variable-star detection. Pre-JA-01 engine `5f38022`, rules-only, initial-geometry and final snapshots match byte for byte: SHA-256 `f0b49f2887ffe2a318b52ae288d39ef9f56527401057c363824da54ad69e58f4`. Full config/results and numeric telemetry are compared, image/mask arrays hashed, only timing fields excluded. Local logs/snapshots are ignored under `build/ja01-*`. Custom geometry changes are intentionally tested synthetically; this is not a claim of every-profile/every-dataset invariance.

Validation commands use Java 21 and both local composites: `gradlew.bat check :JTransient:build`, and `gradlew.bat guiMechanicsTest --tests '*SharedSettingsRulesMechanicsTest' --tests '*SettingsMechanicsTest' --tests '*SettingsIoMechanicsTest' --tests '*ViewerMechanicsTest'`. GUI validation uses `JAVA_TOOL_OPTIONS=--add-opens=java.base/java.util=ALL-UNNAMED` for the existing AssertJ monitor. The snapshot harness gets an 8 GB heap and `SPACEPIXELS_CANCELLATION_DATASET=C:/astrodata/Subs/africano-reg` only in its dedicated JVM; leave that variable unset for ordinary tests. Local init scripts redirect JAR output away from running-app locks without changing tracked build files.

Current contracts are in [DEVELOPER.md](../../DEVELOPER.md#settings), [API.md](../../API.md#error-handling) and JTransient [PIPELINE.md](../../../JTransient/PIPELINE.md#configuration-snapshots-and-shared-rules). The engine's [completion record](../../../JTransient/docs/archive/design-improvements-closed-2026-10.md#ja-01-configuration-snapshots-and-shared-rules-2026-10-22) preserves the original decisions/acceptance. Both ignored local sibling flags remain enabled. This source pair uses the unreleased `1.0.5` engine line; no publication or old-binary fallback is claimed. Typed bindings/capability handling (D10), richer dataset readiness and published dependency-pair validation (JA-11) remain separate work.

## Plan consistency review (2026-10-22)

This documentation-only review reconciles the active plan with SpacePixels `f504364`, JTransient `6a410ad`, completion records and focused source/test checks. It does not introduce implementation approval, change scientific behavior or claim a fresh runtime/accessibility audit.

- D09 remains applicable: `DetectionReportGenerator` still measures `FieldDepth`/`NightEquipment` during export, `SessionCatalogue` loads lazily and label/category rules remain distributed.
- D10 remains applicable only for remaining typed bindings/capabilities/run options: JA-01 shared rules, captured configs, separate settings I/O and background storage are already delivered.
- D11 remains applicable for unified completed-result access, history/restoration and clearer scope/readiness, not pre-import application preferences or initial overlays. `ApplicationSettings` and session handover exist; Blink consumes cached tracks/candidates and Preview consumes cached variable candidates. Do not rebuild those foundations.
- D12/D13 remain applicable: fixed description geometry, stable-width/unwrapped progress text, mouse-only links, label associations and deliberate focus constraints still need their own layout/keyboard checks. The progress dialog is not universally 420 px wide, and Preview already has a focused numeric-editing path.
- S13 remains an independent boundary item. S14 has completed snapshot/context pieces but remaining standard/API/default/static integration. S15/S16 are D09 implementation slices, not three additional feature findings.
- The GUI coordinator is per window, not a process-wide resource budget for independent API/engine callers. Engine cancellation and one-operation-per-engine integration are complete; remaining JA-02/JA-03 work is resource policy/structured failure context.
- Basic F09, S1–S12 and D01–D08 stay closed. Deferred/experimental features keep their status. The Java 25 bootstrap suggestion remains an installer exploration, not a change to Java 21 development.

The active plan now has one canonical D/F/S13 backlog, an architecture implementation mapping, a dependency-aware delivery view and a current mechanics source inventory. Historical class counts and 'today' path descriptions stay in [the dated architecture review](../architecture-review.md), clearly marked as evidence rather than another queue. The old GUI testing draft below is retained for its accepted harness decisions and review history; it is not the current coverage/status list.

Validation for the documentation change checks 92 local links/anchors and 15 unique canonical backlog IDs, excluding completed IDs and subordinate S14–S16 rows. References were checked against source; no new processing/GUI execution is claimed. The user's pre-existing UI text edits in `ApplicationWindow` and `DetectionConfigurationPanel` are outside this change and their content hashes remain unchanged.

## GUI mechanics planning snapshot (2026-10-22)

The following was removed from the active plan during consistency review. It contains original 2026-10-20/21 decisions, historical pass statements and then-pending suggestions. Current test classes and gaps are described by [the active verification section](../../DESIGN-IMPROVEMENTS.md#gui-interaction-checks); completed items mentioned here must not be re-proposed.

### Original test plan and review notes

**Accepted (2026-10-20).** The suite checks the mechanics of the window: every control can be used, settings can be changed and saved, runs can be started, and a load can be cancelled. It does not check detection results. Those stay on `batchDetect` and the real-data tests.

**Driver.** AssertJ Swing on the existing JUnit 4, dependency `tokyo.northside:assertj-swing-junit:4.0.0-beta-3` (Apache 2.0; the maintained Java 21 fork). If that beta misbehaves, `org.assertj:assertj-swing-junit:3.17.1` has the same API. Jemmy, image search and recorders are not used.

**Lookup is the visible text.** Tests match a showing button or label by the words the user sees, such as `Import Aligned Frames…` or `Detection Sigma`. No `setName` is added for the suite. AssertJ's `button(String)` finds a component name, so the tests do not use it for a caption. When two controls share a caption (`Save`, `Detect Moving Targets`), only the one on the selected tab is showing, and the matcher requires that.

**Where it lives.** The tests and their support are in `src/test/java/eu/startales/spacepixels/gui/mechanics/`. `build.gradle` holds the test dependency and the `guiMechanicsTest` task. Run `./gradlew guiMechanicsTest`. That task is not part of `./gradlew test`: it needs a desktop, it moves the mouse, and it runs one test at a time. `./gradlew test` excludes this package and opens no window. The mechanics task runs again even when the classes have not changed, prints each case name, and brings the window to the front of the main display.

**Isolation.** Each test sets `user.home` to a scratch folder before the window is created, and restores it afterwards. Java preferences are not in that folder. The test exports `/eu/startales/spacepixels` (the window placement, the stretch, the blink speed, the auto-tune profile and `showExpertSettings`) and restores that node after the window is gone. The frame's close operation is set to dispose, so closing it does not exit the JVM. Since D04's Cancel, the modal **Processing...** dialog's close box cancels a job that can be cancelled. Plate solving now joins JPlateSolve's cancellable operation. For the WCS write and Blink it still asks to exit SpacePixels, so the suite must not click it there.

**Data.** Synthetic frames from `SyntheticDatasetFactory`. The reference datasets are not opened. The folder chooser is the real one: the test clicks **Import Aligned Frames…**, selects the synthetic folder and approves the chooser. The approve button's own caption follows the operating-system language, so the test uses the chooser's approve action rather than that caption.

**First tests drafted.** `WindowMechanicsTest` covers four cases:

- Before any import, the workflow buttons are present, detection and blink are unavailable, and the prepare line says `No frames imported`.
- Importing a synthetic monochrome sequence enables the other tabs and shows `Monochrome` with the frame count. The status line then reads `Imported N file(s)` (the session install replaces the job's own `Import finished` text).
- Changing **Detection Sigma**, pressing **Save**, and opening the window again shows the saved value.
- **Blink Selected** can be stopped with **Cancel** on the loading card or **Stop Blinking**, and the status line says `Blinking stopped`.

**Stretch.** `StretchMechanicsTest` imports a synthetic sequence and opens Image Stretch. Moving the sliders changes the stretched preview and leaves the linear one. Unticking **Stretch display** makes both previews the linear frame (`Linear (stretch off)`). **Enable batch stretch**, **Next ▶** steps the preview to frame 2, and **Batch Stretch** on the main screen ends at **Stretch Complete**, where the test clicks **OK**. It then checks that the `_stretched` folder holds the five copies. This case passed on 2026-10-21.

**Viewers.** `ViewerMechanicsTest` builds its own solved session (`CRVAL1` and `CTYPE1`, a `sky_catalogue.json` for that field, and a `plate_correction.json`). Import restores the catalogue and does not fetch or start the plate-solution check. **Preview Frame** steps to frame 3 and closes. **Blink Selected** on three frames shows **Show tracks** disabled, because nothing has been detected. **Annotate** toggles, **Correct plate solution** switches on, Pause and **▶** leave the paused frame, and closing the window says `Blinking stopped`. This case passed on 2026-10-21. Clicking **Show tracks** waits for a later test that has run a detection.

**Settings.** `SettingsMechanicsTest` imports a synthetic sequence and opens Detection Settings on the scratch home. **Show expert settings** is turned on and every page is opened. Typing `abc` into **Detection Sigma** and into the expert field **Streak Min Pixels** outlines the field in red and the footer says what to enter; the stored value stays. Changing those two, **Revert** puts them back. Changing them again and **Save** keeps them when the window is opened again. This case passed on 2026-10-21.

**Still to draft.** The settings search and Load Defaults; plate-solve availability; cancelling the sky-catalogue fetch; the iterative frame-limit question; Cancel in the progress dialog (since D04, 2026-10-20), for example for Auto-Tune and a detection run; **Show tracks** after a detection.

**Review notes (Claude, 2026-10-20).** The draft is a good base: lookup by visible text, the scratch home with the preference snapshot, synthetic data and an opt-in task are the right choices. Suggestions, roughly in order of value:
- **Split availability from wiring after S4.** Once `CommandAvailability` exists, the "which button is enabled when" checks (the first case, Blink with three frames, Convert on colour frames) belong in fast unit tests of the model. Keep one mechanics test per shared command for the wiring: button, shortcut, table menu and Overview button agree, and a shortcut does nothing while its command is unavailable.
- **Port two scratch-harness checks into the suite.** Both catch regressions that unit tests cannot:
  - **Preview Frame:** a value typed into a settings field stays in the field ("1" does not zoom), Enter and Esc give the keys back to the frame, and an arrow click leaves them there.
  - **Report link and Copy details:** **Open last report** appears after importing a folder with reports, and Copy details puts the versions and the error on the clipboard. The clipboard must be saved and restored, as the preferences are.
- **Cancel is hard to time on synthetic frames:** a detection finishes in about a second. Cancel at a progress message from inside the test instead (the job tests do this without a window). In the window, check that Cancel is shown for a cancellable job and hidden otherwise.
- **Matching captions.** The **Open last report** link's caption is HTML with the date, so it needs a "contains" matcher. S4 keeps every button caption as it is today, so `VisibleText.button` keeps working.
- **Speed and robustness.** `pauseForTheEye` adds 1.2 s per test; a system property could turn it off for unattended runs. The robot takes over the mouse for the whole run, which the task description says; a note in the run output to keep hands off the mouse would help.

## Overall assessment

The application has a good foundation: Swing with FlatLaf, a separate detection engine, a usable Java API, shared image viewers, and report section writers. The next improvement should make **ownership, state and failure handling explicit**. Replacing Swing or reorganising every package would deliver less value than making a job, a settings edit and an imported session behave consistently.

The most valuable work is, in order:

1. Protect files during replacement and settings saves.
2. Prevent obsolete previews and overlapping jobs from changing the current UI.
3. Give long operations a clear lifecycle, honest cancellation and reliable cleanup.
4. Validate settings into snapshots, and derive command availability from application state.
5. Improve keyboard access, layout scaling and the distinction between application preferences, the current session and a completed run.

These are engineering priorities, not findings that every possible failure has already occurred. The evidence below distinguishes code that is present from consequences inferred from it.

## Review scope and limits

Reviewed startup and import, workflow buttons and table selection, background tasks and events, preview rendering, frame viewers, settings and persistence, the API and CLI boundary, processing services, report generation and live lookup support. Existing manual screenshots were inspected for visual context; some predate the latest control placements. Source references name methods so the findings remain useful when line numbers change.

**Coverage clarification (2026-10-20).** This is a broad review of subsystems and important execution paths. It is not a completed class-by-class audit against the proposed architecture, and there is no exhaustive inventory of the changes needed to reach it. D01–D13 identify evidenced problems and useful next steps; they are not proof that every remaining dependency and ownership issue has been found. The [architecture adoption review](#architecture-adoption-review) below completed that inventory on 2026-10-20.

This was a source and architecture review, with primary documentation checked for Swing, FlatLaf and Guava. It was **not** a fresh interactive usability study, fault-injection run, accessibility audit or performance benchmark. Statements about races and I/O failures describe reachable design risks inferred from the code; their frequency has not been measured. No application code, user settings, FITS files or JTransient algorithms were changed by this review. M81 false-positive detection remains a separate investigation.

## D01. Treat file replacement as a shared application responsibility

**Implemented (2026-10-19).**
- **One helper.** `util.StagedFile` writes into a unique temporary file beside the target and replaces the target in one atomic move. It falls back to keeping a backup when an atomic move is unavailable, and removes the temporary file on failure.
- **Where it is used:**
  - the plate-solve WCS write, which also reads the copy back first to check the image size and that its data is complete;
  - the settings files (`SpacePixelsSettingsFiles` stages both files before replacing either, and names the file that failed);
  - the application config and profile migration;
  - the session files (Auto-Tune results, plate correction, sky catalogue), report text files, the lookup cache, and converted, decompressed and prepared FITS copies.
- **Settings Save** marks the settings saved only after both files are replaced.
- **Tests** inject failures while writing, during replacement (a folder in the target's place, and on Windows a frame held open), on either settings file, in the non-atomic fallback, and for a truncated FITS copy.
- **Real data:** a WCS write on a copy of a klangwolke-2 frame (121 MB) left the pixels identical and kept the other header cards. A klangwolke-2 batch run wrote the same `report-data.json` findings as before.

The original review follows.

**Evidence.** [PlateSolveService.updateFitsHeaderWithWCS](../../src/main/java/eu/startales/spacepixels/util/PlateSolveService.java) writes `<original>.tmp`, deletes the original, then calls `renameTo`. If deletion succeeds but renaming fails, the original path is gone. A temporary replacement may remain, so this is not proof that all image data has been destroyed, but recovery is now manual. The temporary filename is also fixed rather than unique.

[DetectionConfigurationPanel.savePersistedSettings](../../src/main/java/eu/startales/spacepixels/gui/DetectionConfigurationPanel.java) opens both settings files with `FileWriter` before writing either. Opening the second file can fail after the first has already been truncated. A later write or close can fail too. The saved-state marker is updated inside the try block before both resources have finished closing. [SpacePixelsAppConfigIO.write](../../src/main/java/eu/startales/spacepixels/config/SpacePixelsAppConfigIO.java) also writes directly to its destination. These failure paths were not exercised on user files.

**Proposal.** Extract a small file-publication helper from the pattern already used by `AtomicReportFile`: unique temporary file beside the target, complete and close the write, validate as appropriate, then replace. Prefer an atomic move where supported. Specify a recovery/backup strategy when it is unavailable; a regular replacement fallback is not automatically crash-safe. Never deliberately remove the only original before publishing its replacement.

Stage both settings serialisations before replacing either, and mark the editor saved only after the selected publication policy succeeds. **Two individually atomic file moves are not a two-file transaction.** Either provide rollback/recovery for a partially completed pair or expose which file failed and retain an unsaved state. Keep the current profile formats and migration backups; combining all preferences into a new format is not necessary for this fix.

**Acceptance.** With disposable fixtures, inject failure before replacement, during replacement and on the second settings file. Verify the previous FITS path remains readable, recovery files have an intentional policy, and the UI does not claim a failed save succeeded. This is SpacePixels work; it needs no detection-engine change.

## D02. Give every background job an owner and an identity

**Status (2026-10-20).**
- **Done:** every long job goes through `JobCoordinator` (S2/S3).
  - The coordinator admits a job on the EDT before its worker starts.
  - Each job has a handle with its id, kind and session.
  - Each job ends with one typed outcome, delivered once after the window is unlocked. Cancelled is one of the outcomes (D04).
  - A late progress report from an ended job is dropped.
- **Deliberate exception: the sky-catalogue fetch** (with the plate-solution check that follows it) stays outside the coordinator. It is bounded network work and does not lock the window, so it may run beside a job, as the proposal below asks for network work. It has its own indicator and Cancel. It runs one at a time on its own thread (`MainApplicationPanel.startSkyJob`), and that thread is its identity: a result whose thread is no longer the current one is dropped, so a new import discards the previous session's fetch.
- **Open:** the session in a job handle is the `ImageProcessing` of the import, a placeholder until the session object (S7). The acceptance check "cannot apply A's result to a new session" is complete once S7 exists.

**Evidence.** [MainApplicationPanel](../../src/main/java/eu/startales/spacepixels/gui/MainApplicationPanel.java) starts work from listeners, then reacts to a started event posted by the worker. The lock is therefore acquired asynchronously after submission. `lockUI` stores enabled flags recursively; another lock clears that snapshot, and a finish event calls `unlockUI` without checking which job owns the lock. `EngineProgressUpdateEvent` carries only a percentage and message. `DetectionFinishedEvent` mixes report, quick-preview and count fields; inspection and star-mask work use it to borrow detection's progress handling.

The current modal dialog prevents many user interactions once it is visible. That reduces exposure, but does not establish a synchronous admission rule or make overlapping lifecycle events safe. Sky fetching has its own indicator and does not post this generic progress event, so the earlier example of sky-fetch percentages directly moving the modal bar was too broad.

**Proposal.** Add a small application-owned job coordinator. On the EDT, validate the command and reserve its resource slot **before** launching a worker. Record a job ID, session ID, job kind, stage, progress and terminal outcome. Ignore updates from obsolete IDs. Model heavy image processing separately from bounded network work; decide explicitly whether catalogue fitting, preview loading and full detection may overlap given memory use. Do not put all work into one unrestricted executor, or queue many full-resolution previews.

Use typed outcomes such as completed report, imported session, tuned settings, cancelled and failed. An `Object result`, several nullable fields or `JobFinished<T>` subscriptions on Guava would weaken this: Guava does not support parameterised subscription types. A typed job handle can return its result directly, with an adapter for existing UI lifecycle messages.

**Acceptance.** Starting the same command twice admits only the permitted work. A late progress/finish event from job A cannot unlock job B, change B's status or apply A's result to a new session. Tests should assert this without constructing the entire main window.

## D03. Extend the viewer's “latest request wins” rule to stretch previews

**Implemented (2026-10-19).**
- **One render at a time.** `gui.StretchPreviewRenderer` renders the Image Stretch previews on one `SwingWorker`, as `SequenceViewerFrame.prepareCurrent` prepares frames.
  - A request carries the session (`ImageProcessing`), the frame's file and the stretch.
  - A request made during a render waits, replacing any request already waiting.
  - A result is shown only if its request is still the newest.
  - Before, every change started its own thread that read a whole frame, and every result was shown in the order the threads finished.
- **What makes a result stale:**
  - any change of frame or stretch, already before the 250 ms debounce fires;
  - hiding the tab;
  - another import, which also removes the previews of the previous session.

  While a render is due, the frame line ends in "rendering…". Once a result is shown, the label and the images describe the same request. With no frame selected, the previews are removed.
- **Event removed.** `GeneratePreviewsTask` returns the two images and now closes the frame on failure too. `PreviewGenerationFinishedEvent` is gone.
- **Tests:** `StretchPreviewRendererTest` holds an earlier render until a later request is made. It checks that:
  - only the later request's images and both histograms are shown;
  - waiting requests are replaced;
  - a cancelled render is not shown;
  - one render runs at a time.
- **Window harness**, on four klangwolke-2 frames copied to a scratch folder. It made two frame changes and two stretch changes while previews rendered, hid the tab during a render, and imported again. Each time:
  - the shown images and both histograms were identical to a direct render of the selected frame with the current stretch;
  - the frame label named that frame.
- **Not done:**
  - an abandoned render is not interrupted; it finishes its read and is dropped;
  - preview sampling (F05) is unchanged.

The original review follows.

**Evidence.** [StretchPanel.executePreviewTask](../../src/main/java/eu/startales/spacepixels/gui/StretchPanel.java) starts `GeneratePreviewsTask` after a debounce timer. Debouncing already exists and should stay. However, `PreviewGenerationFinishedEvent` (since removed) has images and success only: no session, frame, stretch parameters or request number. `onPreviewGenerationFinished` accepts every result.

**Consequence inferred from the code.** If request A is still loading when B starts, and A finishes last, A can replace B's images while the frame label or controls describe B. Debouncing limits starts close together; it cannot order jobs already running. There is also no per-preview worker handle to limit in-flight full-frame reads.

**Proposal.** Capture a preview request containing session ID, file identity, stretch snapshot and increasing generation. Allow one in-flight render with at most the newest pending request. Apply a result only if its generation is still current; invalidate it on session changes and disposal. Use cancellation when the underlying read supports it, but keep the identity check even after requesting cancellation. `SequenceViewerFrame.prepareCurrent` is an existing local model for worker/target checks, rather than another framework to introduce.

**Acceptance.** Force A to finish after B and verify the visible image, histogram and frame label all describe B. Include rapid parameter edits, import of another session and closing the view. This requires no JTransient algorithm change.

## D04. Make cancellation, failure and resource cleanup explicit

**Status (2026-10-20).**
- **Done:**
  - **One ending per job (S1).** Every job ends with its outcome or the out-of-memory exit.
  - **Real reasons (S11).** Failures are reported with their real reason.
  - **Typed outcomes (S3):** completed, declined, cancelled and failed. A declined report is not a failure, and a declined or cancelled import keeps the open session.
  - **One out-of-memory policy (`JobFailures`).**
  - **Cancel in the progress dialog.** It shows a stopping state, and the job stops at its next progress report; the job's own clean-up runs as it leaves. Closing the dialog cancels instead of exiting. Plate solving, the WCS write and Blink cannot be cancelled, and their dialog shows no Cancel.
  - **The report reports its progress (2026-10-20).** Measured on klangwolke-2 (33 frames of 61 MPix), the report reported once, at 90 %, and then nothing for 107–118 s, so a cancel there waited up to two minutes. Profiling put two thirds of that time in Unclassified Transients (35 %) and Likely Artifacts (30 %). `ReportProgress` now reports each step and each item of the slow steps. A cancel during the report then stopped in 0.10–0.16 s. The report output is unchanged (klangwolke-1: the same `report-data.json` and files).
  - **Clean-up after a cancel.** A cancelled detection removes the report folder it was writing; a cancelled iterative run keeps its finished passes with an index that says so. Every run gets a folder of its own (`OutputFolders`).
  - **Auto-Tune cancel fixed.** The calibrated tuner reports its progress inside a `try` that catches every exception, so a cancel during tuning came back as a failed result: the status bar said "Auto-Tuning completed successfully." and the Overview "Auto-Tune found no usable settings", naming the cancel exception. A cancel made while the tuner runs now ends as cancelled.
  - **Copy details** in the failure messages (`FailureDialog`): the message, the versions and the stack trace.
  - **Open last report** in the Detect group: the newest report beside the frames, found on import and after each run.
- **Open:**
  - **Engine waits.** Measured on klangwolke-2, a cancel during frame extraction took 26 s: the engine's worker threads finish their frames before the cancel takes effect. Other engine stages waited up to 6–29 s between reports (photometry's ensemble solution the longest), Auto-Tune up to 12.6 s. Shorter waits need a cancellation contract in JTransient; a separate engine discussion.
  - **The viewer check:** closing a viewer while it prepares a frame.

**Evidence.** [ProcessingProgressDialog](../../src/main/java/eu/startales/spacepixels/gui/ProcessingProgressDialog.java) is modal, fixed-size, and its close action exits the application after confirmation. Blink and sky fetching have separate cancellation mechanisms. Declining report generation after detection is represented as failure in [DetectionTask](../../src/main/java/eu/startales/spacepixels/tasks/DetectionTask.java), even though detection completed.

There are inconsistent error paths too. [ManualTransientInspectionTask](../../src/main/java/eu/startales/spacepixels/tasks/ManualTransientInspectionTask.java) calls `engine.shutdown()` only after a successful `detectTransients`; its catch logs an OOM without rethrowing it despite the comment about a global handler. [StarMaskStackTask](../../src/main/java/eu/startales/spacepixels/tasks/StarMaskStackTask.java) does shut down in `finally`, but its OOM branch logs without posting a terminal event or forwarding the error. Consequently, those paths can leave progress handling unfinished; an uncaught handler cannot handle an error swallowed by a catch. This was established from the branches, not by inducing OOM in the running application.

**Proposal.** Use a common job runner for ordinary start/progress/outcome handling and resource cleanup. Put engine shutdown and file closure in `finally`/try-with-resources. Have one fatal-error policy instead of copying JVM exit timers into individual tasks; after a genuine OOM, do not promise the session can safely continue. Distinguish detection succeeded/report skipped, user cancellation, partial export and actual failure.

A Cancel control should request cancellation, show a stopping state and retain resource ownership until work actually ends. For a stage that cannot stop safely, say it will stop after that stage. `Future.cancel` alone does not prove that engine computation or blocking I/O stopped. [SwingWorker's contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/swing/SwingWorker.html) supplies worker/EDT completion mechanics; application and engine code still need a cooperative policy. If JTransient needs a new cancellation contract, discuss that engine change separately before implementing it.

**Acceptance.** Fail each ordinary stage, decline export, request cancellation, and close a viewer while it prepares data. Verify one terminal outcome, resource release and a correct command state. Test fatal-policy routing through an injected policy rather than exhausting real memory. Keep a “Details / Copy error” path for diagnostics and a persistent result link after success.

## D04 engine cancellation and viewer lifecycle (2026-10-21)

The maintainer authorized developing the engine and application together through the sibling JTransient composite build. Engine tasks now bind the job's cancellation flag through `CancellationScope`. JTransient checks computation and worker waits, stops queued work, requests running siblings to stop, and joins their task bodies and cleanup before returning. Interruption remains set; a worker `OutOfMemoryError` takes precedence over cancellation. Calibrated tuning propagates `CancellationException` directly, and `AutoTuneTask` no longer needs its failed-result cancellation workaround. `JobFailures` recognizes the JDK cancellation type through wrapping exceptions.

Closing Preview Frame, Manual Transient Inspection or Show full size invalidates pending preparation and cancels its computation scope. Both disposed and reusable hidden viewers detach catalogue and stretch listeners and clear displayed data; reusable viewers bind fresh stretch controls when reopened. Blink detaches its shared stretch listeners on close. Star Mask Explorer cancels obsolete generations and pending work, stops its debounce timer and clears its stack, image and overlay buffers when disposed.

Validation uses JDK 21.0.10 and the local composite build. Both projects' unit suites pass. Focused engine tests cover extraction, stack rows, tracking, tuning, ensemble photometry, worker/listener failure, interruption, cleanup waits, queued-work suppression, reuse, overlap, close during a run, cause preservation and JSON compatibility. Four viewer mechanics cases pass, including controlled delayed frame/mask preparation, duplicate close, listener cleanup, obsolete-result rejection and restored detection command availability. The existing AssertJ timer monitor emits JDK reflective-access warnings; the mechanics assertions pass.

The read-only `EngineCancellationRealDataIT` comparison uses klangwolke-2 (33 prepared monochrome frames, approximately 61 MPix each), the same default configuration with variable-star detection enabled, and a 12 GiB heap. Tuning uses 15 evenly spaced input frames. Baseline is JTransient `6c3f34d`; comparison is the local working tree. Extraction cancellation is requested at frame 5's progress event. Photometry and tuning cancellation are requested 100 ms after their ensemble/grid progress event. The baseline throws at the next progress callback; the new implementation reads the cancellation flag independently of progress.

| Stage | Baseline cancellation wait | Cooperative cancellation wait |
|---|---:|---:|
| Extraction | 9.963 s | below 0.01 s |
| Photometry ensemble | 6.039 s | below 0.01 s |
| Calibrated tuning | 11.374 s, returned an unsuccessful result | below 0.01 s, `CancellationException` |

These are controlled measurements on this machine, not universal latency guarantees. Full baseline and changed pipeline runs also produce byte-identical scientific-output snapshots: settings, result objects and numerical telemetry, excluding execution timings, plus SHA-256 hashes of stack and veto-mask pixel arrays. Snapshot SHA-256: `f5372525a32cac31e9524277f334819f5bab7e56f22923ee3ab4066dbedc9ea8`. The snapshot mode is `EngineCancellationRealDataIT.main`, with the destination path as its sole argument and `SPACEPIXELS_CANCELLATION_DATASET` naming the prepared input folder. Dataset files and user settings remain unchanged.

Current behavior is documented in the manual, developer overview and architecture document; JTransient's `PIPELINE.md` defines the library contract. No engine release was published. Distribution still requires publishing and validating the supported dependency pair; active development uses the companion local JTransient changes. Stage C session ownership (S7) is next.

## D05. Build a validated settings snapshot instead of reading a mutable panel

**Implemented (2026-10-19), in the settings tab.**
- **One step for saves and runs.** `DetectionConfigurationPanel.checkedSettings(...)` is behind Save, Detect Moving Targets, Detect Iteratively, Manual Transient Inspection, Preview Frame, Auto-Tune and Test Star Mask. It returns a copy of the session `DetectionConfig`, hidden settings included, so later edits cannot change a run. `getJTransientConfig` is gone.
- **Invalid text is visible and stops saves and runs.**
  - `CheckedNumberEditor` takes only text that is one complete number within the setting's range. Before, "4,5" was read as 4, and "4.5" in a whole-number field as 4.
  - Other text stays when the field loses focus; before, it silently went back to the previous value. The field is outlined in red, and the footer says what to enter.
  - A refused save or run names itself, brings the tab forward and puts the cursor into the field. A setting hidden by a filter or by the expert switch is found with a search for its title.
  - A setting whose feature is switched off does not count.
- **Pairs in one place.** `config.DetectionSettingsRules` defines the six ordered pairs and the check for finite numbers. The tab moves the same setting as before when an edit crosses a pair, now only in `applySettingsToMemory` (the spinner listeners are gone), and says so in the footer, for example "Grow Sigma lowered to 4.0 (cannot be above Detection Sigma)".
- **Tests:** `DetectionSettingsRulesTest` and `CheckedNumberEditorTest`.
- **Window harness**, on a copy of the maintainer's profile. Its 43 checks cover refused saves and runs, focus, filters and expert settings, Revert, a NaN in a hidden setting, and a run's copy against later edits. Compared with the previous code:
  - the settings a run receives were byte-identical;
  - all 75 number fields were identical in value, type and text.
- **Not done:** profile loading and the API do not apply `DetectionSettingsRules` yet; they stay unchanged for now. Save keeps its name.
- **Fixed afterwards:** a value with more decimals than its field showed was kept rounded; 4.25 typed where the field showed 5.5 became 4.2. The field first showed the new value with its old number of decimals, and the auto-apply committed that text back. This predated D05. A field now shows every decimal of its value.
- **Preview Frame (decided by the maintainer and implemented, 2026-10-20).** Preview Frame shows Detection Sigma, Grow Sigma and Min Detection Pixels, so they can be tried on the frame:
  - **Shared models.** Their spinners share the models of the tab (`perFrameDetectionSettings()`), so an edit there is an ordinary unsaved edit, pairs included.
  - **Re-detect.** The frame on show is detected again 300 ms after the last change.
  - **A fresh copy per frame.** Every frame is prepared with a copy of the settings in use (`settingsInUse()`), taken on the EDT. Unlike `checkedSettings`, it changes nothing on the tab. Opening the window still goes through `checkedSettings`.
  - **Invalid text** keeps the last valid value, and a note says what to enter.
  - **Locked during jobs,** like the tab.
  - **Keys.** The window's keys do not fire while a text field has the focus (`ViewerSupport.bind`): "1" typed there does not zoom.
  - **Checked** with a window harness on three Apophis frames, with real key and mouse events.
- Before D05, a frame stepped to in an open Preview Frame also used the settings of that moment; from D05 until this change it kept the settings it was opened with.

The original review follows.

**Evidence.** [DetectionConfigurationPanel.commitSpinners](../../src/main/java/eu/startales/spacepixels/gui/DetectionConfigurationPanel.java) catches and ignores `commitEdit` failures. `applySettingsToMemory` changes the live config field by field and logs a broad exception. `getJTransientConfig` returns that same mutable object. Detection and Auto-Tune tasks retain that reference at construction, whereas manual inspection clones it. Standard detection does clone the effective config later; this is useful, but happens after loading, rather than defining one uniform click-time boundary.

Invalid text can therefore be rejected by the editor while a Save or Run path continues using the previous model value. Constraints are also implemented in spinner listeners and normalisation code, while callers of the API do not use those widgets. This is a validation/feedback weakness, not evidence that the engine receives every invalid edit.

**Proposal.** Read the editor into a draft; commit and validate it; then create the run snapshot or update the session settings as one operation. Return structured validation errors, focus the relevant input, and explain any automatic adjustment. Reuse applicable validation in the API and profile-loading boundary, with mode-specific policy where needed. Reject non-finite values and inconsistent pairs explicitly rather than relying only on spinner bounds.

Preserve auto-apply for valid edits and the current hidden-field behaviour. Build on a clone of the loaded configuration; do not rebuild it from visible rows and lose hidden values. All long tasks should receive the same kind of click-time config snapshot, frame identities and export choices. Prefer “Save as startup defaults” wording where that is what Save does; saving is not the step that first activates the current values.

**Acceptance.** Invalid typed input prevents Save/Run with an inline explanation. Editing the UI after a run starts cannot change that run. Revert, defaults, migration, auto-tuned values and hidden fields retain their current documented semantics. Validation that changes detection decisions needs the appropriate real-data comparison and maintainer discussion.

## D07. Derive availability from state and use real command actions

**Implemented (2026-10-20), as S4 in two slices.**
- **Commands:** `gui.CommandAvailability` holds the rules, and `gui.CommandAction` is a shared Swing `Action`. Every workflow button of the Main tab is now a command:
  - Import, Convert to Mono, Batch Stretch;
  - Plate Solve, Fetch Sky Catalogue;
  - Blink, Preview Frame, Manual Transient Inspection;
  - Auto-Tune, Detect Moving Targets, Detect Iteratively.
- **Shared entry points:** button, shortcut, table menu, the Overview's buttons, the settings tab's Ctrl+D and a dropped folder use the same command. No entry point calls `doClick()`, copies another control's enabled state, or follows the Overview's Run button any more.
- **The lock** no longer saves and restores enabled states. While a job runs it disables the panel's other controls and closes the session tabs. Afterwards it enables them again and opens the tabs only with a session. Two locks released once now leave nothing disabled; the saved-state map restored the second lock's disabled states.
- **Tabs** are addressed by their panel, not their position.
- **The policy is unchanged,** except for two places that did not follow it:
  - Plate Solve stayed enabled after the selection was cleared;
  - the Overview's Preview Frame opened on a colour session.

  Also, Ctrl+B now stops a blink, as Stop Blinking does.
- **Checked by:**
  - `CommandAvailabilityTest` (8 cases);
  - a window harness with real key events and a real right-click: 46 checks before an import, on mono and colour sessions, through selection changes, the batch-stretch switch, a blink, a job and a double lock.
- **Not done:** the row-sorter constraint below still applies when sorting is added.

**Evidence.** [MainApplicationPanel](../../src/main/java/eu/startales/spacepixels/gui/MainApplicationPanel.java) keeps enable rules in `lockUI`/`unlockUI`, selection listeners, workflow refresh, import handlers and helper methods. A saved component-state map records what happened to be enabled rather than why. The main Auto-Tune button follows another button's enabled property. Shortcuts call `button.doClick()`, and the table menu copies button checks. These controls are partly synchronised already, but the button hierarchy is serving as the application model.

**Proposal.** Calculate a small immutable availability model from session facts, valid settings, selection and job ownership. Each entry contains an enabled flag and reason. Apply it to shared `Action` objects for commands that have several entry points. The action dispatches a typed request; it does not discover its policy by walking Swing components. Oracle's [Action contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/swing/Action.html) explicitly supports sharing command properties and enabled state across controls; it does not imply every local listener needs a command class.

Keep the useful disabled-button explanations, generated from the same availability result. Remove numeric tab-index assumptions as affected code is touched; address the tab/component by identity. Avoid a state machine containing every possible UI combination: a session lifecycle plus independently computed capabilities is sufficient initially.

**Table enhancement constraint.** The current table has no installed row sorter, so its direct selected-row-to-model indexing is not an existing sorting bug. If sorting/filtering is added, convert view rows to model rows in all selected-file and solve-result paths, and use file/session identity in events instead of a saved visible row number. Return numeric values and column classes before offering numeric sorting.

**Acceptance.** Button, shortcut and menu have identical availability and behaviour after imports, colour conversion, selection changes, errors and job completion. A late event cannot re-enable a prohibited operation. Preserve the current workflow policy first; relaxing locks is a separate product decision.

## Architecture adoption review

**Next step (2026-10-20).** The maintainer wants to move SpacePixels toward this architecture. Before any code changes for it, review the whole codebase and work out the steps that lead there. D01–D13 came from a broad review, not a class-by-class one, so they are a starting point, not the full list.

**Scope.**
- **Packages:** every production package, including the build wiring and the relevant tests:
  - `api`, `config`, `events`, `gui`, `tasks`, `tools`;
  - `util`, including `util.reporting` and `util.skycatalog`.
- **Out of scope:** JTransient and JPlateSolve are external contracts; their internals are not part of this review.
- **Paths to follow through the GUI, the API and the CLI:**
  - import and conversion;
  - plate solve, and catalogue fetch and correction;
  - preview and inspection;
  - Auto-Tune;
  - standard and iterative detection;
  - report and lookup.
- **Outcomes to follow:** cancellation, failure, session replacement and closing, as well as success. Searching the imports helps with the inventory, but it does not show that a path behaves correctly.

**What to record.**
- **Per subsystem:**
  - what it does now, and where its boundary should be;
  - which way its dependencies actually point;
  - who owns its state and resources, and which thread it runs on.
- **Per class:** **retain**, **move responsibility**, **adapt boundary** or **investigate**, with the reason. Propose a change only where the code conflicts with the target.

**Result.** The class inventory, the traced paths and the evidence are kept in [docs/architecture-review.md](../architecture-review.md). The steps they lead to are listed below. Each step has:
- a delivery stage;
- what must stay compatible;
- how the step will be verified;
- a link to D01–D13 where those already cover the work.

Record what the review did not cover and any deliberate exceptions. The review is complete when every production class and every entry path is accounted for.

**Progress.**

| Part | Packages | Status |
|---|---|---|
| 1. Workflow core | `gui`, `tasks`, `events` | Done 2026-10-20 |
| 2. Services and boundaries | `util`, `api`, `tools`, `config` | Done 2026-10-20 |
| 3. Presentation | `util.reporting`, `util.skycatalog` | Done 2026-10-20 |

**The review is complete (2026-10-20).** All 166 production classes are accounted for:
- 114 are retained;
- the 18 event classes retire with the bus;
- 34 change.

The coverage limits and the deliberate exceptions are listed in the [review summary](../architecture-review.md#review-summary).

**Steps from the review.** The review has the details. A step is accepted when the maintainer takes it up; its status is in the table, and the steps without one are still proposals.

| Step | Stage | Work | Covers |
|---|---|---|---|
| S1 | A | **Done 2026-10-20 (`01d983e`).** Every job ends with a finished event or the out-of-memory exit; before, some failures left the UI locked. | D04 (new evidence) |
| S2 | B | **Done for every job, 2026-10-20** (`54e32b6` to `23acc9b`). A job coordinator: admission before the worker starts, and job and session identity. Session identity is a placeholder until S7 | D02 |
| S3 | B | **Done 2026-10-20**, with S2: every job returns a `JobOutcome` (completed, declined or failed; cancelled since D04's Cancel, 2026-10-20). Typed outcomes per job kind | D04 |
| S4 | B | Shared actions and availability: one Detect action; tabs and solve results addressed by identity. Solve results are matched by file since the plate-solve slice (2026-10-20). **Done (2026-10-20):** `CommandAvailability` and one `CommandAction` per workflow command, shared by button, shortcut, table menu, Overview and folder drop; the lock without saved states; tabs by panel | D07 |
| S5 | B | **Done 2026-10-20 (`01d983e`, with S1).** Use `BatchStretchTask` instead of its inline copy | — |
| S6 | B–C | **Done 2026-10-20.** No GUI in `tasks`: their own logger and out-of-memory policy, and results returned instead of windows and dialogs opened. `DependencyRulesTest` keeps `tasks` and `jobs` free of `gui` and Swing | new |
| S7 | C | A session object, and the application config loaded once | D06, D11 |
| S8 | C | The WCS write and session-folder I/O off the EDT. **The WCS write is done (2026-10-20)**: it runs as a job. The session-folder reads and writes (sky catalogue and correction on import, Auto-Tune results, the correction switch) are still on the EDT | D08 |
| S9 | B–C | **Done 2026-10-20 (`23acc9b`)**: all 18 events, the bus and the Guava dependency are removed. Jobs report through the coordinator; a new session reaches the panels through the window's session listeners | EventBus decision |
| S10 | C | Split `ImageProcessing`: its session part into the session object, with batch operations and import as their own services. Keep it as a facade, and remove its dead methods | D06 |
| S11 | A–B | **Done 2026-10-20 (`d255b68`), the message fix.** Report failures as failures. Before, a report-export failure said "aborted by user due to high track count", a failed iterative pass was still listed, and an Astrometry.net failure showed two dialogs. Typed outcomes follow with S3 | D04 (new evidence) |
| S12 | C | One preparation flow for the GUI and the API, with the GUI's questions asked on the EDT. Typed import outcomes are done: since `74bbded` a declined import keeps the open session. The questions are still asked from the import worker | D08, D06, F07 |
| S13 | B–C | No `gui` and no Swing in `util` | new |
| S14 | D | An explicit run context: the site, rendering options and Auto-Tune frame limit go with the run, instead of coming from the home folder and static fields | D09, D10 |
| S15 | D | One source of finding labels and categories. Today T1, ST1, A1… are computed in seven places that agree only by walking the tracks in the same order | D09 |
| S16 | D | Prepared diagnostics with explicit states (*measured*, *unavailable*, *failed*), and one failure policy for optional report sections. Today a failed field-depth measurement reads as "not measured", and other section failures abort the report | D09 |

S1 and S11 were the small correctness fixes that needed nothing else first; both are done.

**Starting points at the time of the review.** Since then the bus and its events are gone (S9), and the tasks no longer build Swing views (S6).
- `ApplicationWindow` owns the shared event bus.
- Task classes post the lifecycle and progress events, and some also build Swing views.
- `ImageProcessing` still asks import questions in Swing dialogs and logs through `ApplicationWindow.logger`.
- The existing findings that cover parts of the work:
  - D02/D04: the job lifecycle;
  - D06/D08: the session and interactive preparation;
  - D09/D10: the report and settings contracts.

**Constraints.**
- Preserve the public API, the settings formats, the detection measurements and the current viewer and report behaviour, unless a separately agreed feature changes them.
- Once a boundary is extracted, protect it with a dependency rule that tests can check, such as "application services do not depend on Swing or `gui`".

## Guava EventBus: keep or replace?

**Implemented (2026-10-20).** The maintainer chose option 2 after the architecture review. The bus and Guava were removed the same day, in `23acc9b`, when the last job moved to the coordinator.

**Accepted (2026-10-20): option 2, retire it gradually.** In practice this meant:
- no separate migration project;
- no new events on the bus;
- each event is removed when its workflow gets a typed job handle or a session notification (S9);
- Guava is removed with the last event.

The question and the evidence follow.

The maintainer asked whether SpacePixels should keep Guava's `EventBus` or move to something else.

**Current use (2026-10-20).**
- **The bus:** `ApplicationWindow` owns one synchronous bus.
- **The events:** 18 event classes in `events`. They carry the start, progress and end of import, conversion, stretch, blink, plate solve, Auto-Tune and detection, plus the blink window's close.
- **Publishers:** 13 classes post these events: the task classes, `BlinkFrame`, `MainApplicationPanel` and `DetectionConfigurationPanel`.
- **Subscribers:** 24 `@Subscribe` methods in four GUI classes receive them: `ApplicationWindow`, `MainApplicationPanel`, `DetectionConfigurationPanel` and `StretchPanel`. All four are registered for the window's lifetime, and each method moves its work to the EDT itself.
- **The dependency:** Guava is used for nothing else, so retiring the bus would also remove the dependency.

**Evidence from part 1 of the review (2026-10-20).**
- 14 of the 18 events have a single subscriber, `MainApplicationPanel`. They carry one job's start, progress and end.
- Only two kinds of message have several listeners:
  - "the session changed" (import, 4 subscribers);
  - "tuning started or ended" (2 subscribers).
- Each use has a natural replacement in the target design:
  - the job messages: a job handle (D02/D04);
  - the session change: a notification from the session model (D06);
  - the tuning state: job state.

This supports option 2. See [the review](../architecture-review.md#the-event-bus-in-part-1).

**For keeping it.**
- It works, and it is small and contained.
- The problems in D02 and D04 do not come from the bus itself:
  - the events carry no job or session identity;
  - the UI lock is taken after the worker has started.

  Replacing the bus alone fixes neither problem.

**For replacing it.**
- Guava's own [guidance](https://github.com/google/guava/wiki/EventBusExplained#avoid-eventbus) advises against using it. Its reasons that matter here:
  - who posts an event and who receives it is not visible in the code;
  - a subscription cannot be generic (no `JobFinished<T>`);
  - the delivery thread cannot be controlled;
  - an exception in a subscriber is only logged and never reaches the poster.
- Typed job handles (D02/D04) would return results and failures directly to whoever started the job.

**Options.**
1. **Keep it as it is.**
2. **Retire it gradually.** When D02/D04 give a workflow a typed job handle, its progress and outcome go through that handle, and its events are removed. Guava goes when the last event does.
3. **Replace it with another event-bus library.** This keeps the same hidden coupling, so it gains little.

**Suggestion made before the decision.** Option 2:
- no separate migration project;
- no new events on the bus in the meantime;
- plain Java (listener interfaces, `SwingWorker`, `PropertyChangeSupport`) covers what remains, so no new library is needed.

To ground the decision, the review should list every publisher, subscriber and registration, including inline workers.

## S2 and S3, first slice: a job coordinator for the detection jobs

**Implemented (2026-10-20), in `54e32b6`, `f945ad8`, `281246f` and `953f3cf`.** The maintainer accepted the design and checks the four buttons in the GUI. Later slices moved the other jobs, all on 2026-10-20:
- Convert to Mono and Batch Stretch;
- Plate Solve;
- Auto-Tune;
- Blink;
- Import.

Every job now runs under the coordinator.

**Scope.** The four jobs that share the detection lock, progress dialog and finished event today:
- Detect Moving Targets;
- Detect Iteratively;
- Manual Transient Inspection;
- the star-mask stack behind Test Star Mask.

The other jobs (import, plate solve, Blink, Auto-Tune, Convert to Mono, Batch Stretch) stay on the bus for now and move one at a time later.

**New types**, in a new package `eu.startales.spacepixels.jobs` with no Swing or `gui` dependency:

| Type | Role |
|---|---|
| `Job<R>` | The work. `run(JobContext)` runs on the worker and returns a `JobOutcome<R>` |
| `JobContext` | What a running job may use: `progress(percent, message)` and its `JobHandle` |
| `JobOutcome<R>` | A sealed type: `Completed(R result)`, `Declined(String reason)` (the user said no, such as the report-size question) or `Failed(String reason, Throwable cause)` |
| `JobHandle` | The job's identity: a number, its `JobKind` and the session it was started for. Until S7 brings a session object, the session is the `ImageProcessing` instance |
| `JobKind` | `DETECTION`, `ITERATIVE_DETECTION`, `TRANSIENT_INSPECTION`, `STAR_MASK_STACK`; more kinds later |
| `JobCoordinator` | Admits, starts and finishes jobs. It is called on the UI thread and given two executors: one for the worker, one for the UI thread (`EventQueue::invokeLater` in the application, a direct executor in tests) |

**How a job runs:**
1. **Admission,** on the EDT, before any thread starts. The coordinator refuses the job, with a reason, when another coordinated job is running or when the window is locked by a job still on the bus. This is today's policy; only the timing changes.
2. **Locking:** an admitted job tells its busy listener at once. `MainApplicationPanel` implements that listener: it locks the window and shows the progress dialog. Today this waits for the worker's start event.
3. **Progress:** reaches the dialog on the EDT, and only while that job is the current one.
4. **Ending:** the coordinator turns the job's outcome into one call of the job's outcome handler, on the EDT. It then releases the lock. A thrown exception or `Error` becomes `Failed` with the reason. Running out of memory is thrown on the worker thread, as S1 established.

| Job | Its outcome handler, at the place that started it |
|---|---|
| Detection | `Completed(report file)` asks whether to open the report. `Declined` and `Failed` show today's messages |
| Iterative | Same, with the results folder. The frame-limit question moves from the worker to the button, before admission |
| Inspection | The job returns its frames and transients; the handler opens `TransientInspectionFrame`. The task no longer opens windows or takes the `StretchPanel` (part of S6) |
| Star-mask stack | The handler fills the master-stack cache and opens the explorer, as the callback does today |

**What is removed:**
- `DetectionStartedEvent` and `DetectionFinishedEvent`, with their bus subscribers. Only these four jobs use them, and this is the first retirement under S9.
- The four unused fields of the finished event go with it.

`EngineProgressUpdateEvent` stays for the jobs still on the bus.

**Ownership:**
- `ApplicationWindow` creates the coordinator.
- `MainApplicationPanel` and `DetectionConfigurationPanel` reach it through the window, as they reach each other today. S4 and S7 change that later.

**Compatibility.** Nothing visible changes, apart from the admission timing:
- the same progress dialog, questions, messages, report prompt and windows;
- the same detection output;
- the API and CLI are unaffected.

**Verification:**
- **Coordinator tests,** with a controllable worker executor and a direct UI executor:
  - a second job is refused while the first runs;
  - the lock is taken before the worker starts;
  - each outcome is delivered once, on the UI executor;
  - progress arriving after the outcome is ignored;
  - an exception and an `Error` both become `Failed`;
  - out of memory leaves the worker thread.
- **Job tests:** the four jobs adapted from `TaskFailureEventsTest`, asserting outcomes instead of events.
- **Full test suite,** and klangwolke-1 with identical `report-data.json`.
- **A GUI check** of the four buttons on a small dataset.

**Delivery, in four commits:**
1. The `jobs` package and its tests, with no users yet.
2. The star-mask stack and inspection on the coordinator.
3. Detection and iterative detection on the coordinator.
4. Remove the two events, and update the architecture and plan documents.

## Delivery plan, as of 2026-10-20

| Stage | Work | Why this order |
|---|---|---|
| Architecture review | Done 2026-10-20: steps S1–S16 above, mapped to the stages below. The EventBus question was decided the same day: retire it gradually | Makes the remaining scope explicit before the architecture work grows. Focused correctness fixes need not wait. |
| A: focused correctness | **Done (2026-10-19).** D01 file publication; D03 preview identity; D05 invalid-editor feedback | Small, concrete failure modes; do not wait for a broad architecture migration. |
| B: workflow foundation | **Mostly done (2026-10-20).** D02 ownership/admission and D04 outcomes/cleanup, for every job rather than only the detection, inspection and star-mask jobs. D07 actions done with S4. Open: the rest of D04 (see its status) | Makes lifecycle explicit before replacing UI locking. |
| C: session boundary | D06 application preferences/session handover; D08 background WCS write and metadata restoration | Reduces UI/service coupling and protects session transitions. |
| D: presentation and polish | D09 prepared report data; D10 one page of typed settings bindings; D11 results/readiness; D12/D13 layout and access | Builds on explicit state; accessibility fixes that are independent can start earlier. |

Reusing `BatchStretchTask` instead of the duplicate inline worker is a useful small change within Stage B, not the main architectural solution. Add one adapter/command at a time and keep the public API and file formats compatible. Do not combine the file-safety fix, an EventBus change and a settings redesign in one commit.

## Completed work removed from the queue

The workflow strip, primary detection action, disabled-reason tooltips, frame context menu and common shortcuts are implemented. So are the Overview with calibrated-profile choices, analysis switches, settings navigation/search, saved-value markers, expert disclosure, auto-apply and explicit Save/Revert/Defaults.

The completed task list also covers report legends and layer guidance, variable-star overlays/maps, compact sky orientation, common-motion explanations, the persistent finding-browser placement, numerical frame-rejection reasons and the updated Signal Weave section. These are maintained as current behavior in the source/user documentation, not scheduled again here. Historical screenshots and mock-up numbers are not acceptance targets for new changes.


## Variable-star field image (F08), completed 2026-10-21

The overall **Variable candidates across the field** image and its full-size version, generated by `VariableStarMapWriter`, now implement the agreed presentation:

- Labels prefer the existing saved VSX match, then a proper name or general Gaia DR3 designation, with the V number as fallback. V identifiers remain in metadata and hover text for table/card associations.
- Full-size light curves use 190 × 84 px overlays, a black background at 18% opacity and one observed delta-magnitude annotation in mag. Axes, ticks, grids and axis names are removed.
- Brighter-up orientation, the existing usable-measurement rules, gaps and confidence remain unchanged. Detailed candidate cards retain their full charts, error bars and comparison stars.

Validation: the report-export tests check proper names (including escaped characters), Gaia identifiers, VSX priority, unnamed candidates and legacy catalogues in both maps. The map tests check all 41 candidates beyond the card limit, flagged gaps, missing times and unchanged analysis data. A scratch re-render of 28 real M81 candidates over 39 frames preserved the analysis byte for byte. Its V7, checked against the recorded Gaia query, appears as Gaia DR3 1071202471832161920 with 0.250 mag observed variation; the native-resolution crop was visually checked. Older saved M81 catalogues contained 4,877 Gaia positions but no identifiers; the GUI tooltip and report now explain the need to fetch again. No saved user catalogue was changed for validation.

## Catalogue correction and colour import, completed 2026-10-21

New fits apply automatically when the median error falls by more than 1 px and the 90th-percentile error also falls. An optional persisted `userEnabled` choice protects a subsequent manual on/off decision across compatible refits. Import restores the saved state; smaller new gains remain optional. The main GUI shows the before/after error and announces automatic application.

Applying that policy in memory to saved real-data residuals enables new fits for M81 (1.14 → 0.07 px), dc-reg (1.37 → 0.13 px) and klangwolke-2 (4.86 → 0.11 px); six smaller gains remain optional. This exercised saved fits, without rerunning or changing the detection engine.

Colour imports now show a persistent information notice explaining Blink, Stretch and Convert to Mono and which analyses require mono. Astrometry remains available. A scratch GUI check imported real RGB FITS files, verified the notice, then imported mono files and verified it disappeared. It also exercised correction completion, persistence, the automatic message and preservation of manual off across a refit. The JVM used a scratch home and in-memory preferences. Normal Java tests: 310 passed, 3 headless skips. The manual also makes post-run Blink and Preview overlays explicit.


## Variable-star research links, completed 2026-10-21

The report now separates VSX identification from **Research this star**. Every candidate card with a sky position has coordinate-based browser links to general Gaia DR3, unrestricted VizieR catalogues and SIMBAD, showing the search radius. Gaia queries do not inherit the downloaded magnitude limit. These links work without a saved sky catalogue or running SpacePixels; results remain external and do not change Known As. VSX's existing cached in-report lookup is preserved, while unsolved sessions explain the need for a plate solution.

A shared, collapsed **Following up an unmatched candidate** note explains positional association, image/photometry checks, independent observations and survey data, and evidence for a possible VSX submission. It links the current [AAVSO guidance](https://vsx.aavso.org/index.php?view=about.notice), distinguishes confidence from discovery, and states that exported instrumental/differential magnitudes are not automatically calibrated submission data. The manual documents the same workflow.

Validation: the report-export test now covers named, Gaia, VSX, legacy, absent-catalogue and unsolved cases; checks coordinates, explicit declination sign, radius, the unrestricted catalogue search and Gaia's lack of a magnitude cut; and verifies the analysis remains unchanged. Live coordinate searches for the real M81 V7 returned HTTP 200 from all three services; the Gaia result contained source 1071202471832161920, and unrestricted VizieR also found that source. A report-style preview using the actual candidate position and measured variation was visually checked. Normal Java suite: 310 passed, 3 headless skips. No engine, profile or user catalogue changes were needed.


## Variable-star card limit and presentation ordering, completed 2026-10-21

Variable Stars has two basic presentation settings: **Maximum Detailed Candidate Cards** (default 40, allowed 1–1,000) and **Candidate Presentation Order** (default Observed changes first; alternatives Shortest known period first, Largest observed variation first and Variability significance). High confidence always precedes possible. Observed changes prioritises up-and-down curves, dips and peaks, then larger observed variation. Periods come from nearest saved VSX matches, with unknown periods last within a tier; no period search was added to the engine.

The settings are SpacePixels-owned profile fields (`variableStarMaxCandidateCards`, `variableStarSortOrder`), with backwards-compatible defaults and the normal profile migration/backup path. Save, Revert, row reset and Load Defaults apply to both. API requests and batch profiles carry immutable options explicitly to the export snapshot, without new global rendering state or JTransient fields.

`VariableStarPresentation` keeps original engine-order V identities and prepares a display order/card selection once per export. Cards, table rows and report navigation follow the selected order; maps, CSVs, catalogue-comparison payloads, saved VSX lookups and live overlays retain those identities. JSON anchors refer to the actual selected cards rather than assuming V1–V40, and JSON records the presentation options. All candidates remain in the table/maps/CSVs.

Validation: a 65-candidate export fixture exercises 40 and 60 cards, confidence grouping, a selected V64 beyond the former cutoff, every HTML/JSON anchor, all map candidates, viewer numbering, unchanged CSV content and unchanged analysis. Further checks cover all four sort choices, known/unknown catalogue periods, old profiles, persistence, migration and invalid values. A GUI check used scratch settings and in-memory preferences to verify the loaded defaults, saving/reloading 60/shortest period, full Revert, Load Defaults and the sort row's changed marker/reset. The native layout was visually checked. A presentation replay of 28 real M81 candidates over 39 frames preserved the analysis byte for byte: observed-changes order begins V1, V2, V3, V8; shortest-period order begins V3, V8, V5, V2; significance retains V1, V2, V3, V4. No original data or saved user settings were changed.

The separate saved-results viewing idea remains **proposed** under D11 in DESIGN-IMPROVEMENTS.md; this work does not implement reopening saved detections in viewers.

Final normal Java validation for this change: 313 passed, 3 headless GUI skips, no failures or errors. The additional GUI persistence/layout checks described above ran separately with isolated settings.

## D04 follow-up: cancellable plate solving with JPlateSolve 1.0.1

Implemented on 2026-10-21 in the local sibling JPlateSolve repository and adopted here. Plate Solve now offers Cancel, reports solver phases and elapsed seconds, and joins the solver's cleanup-aware completion before releasing coordinator admission. Interrupted waiters also request cancellation and join cleanup. Cancellation stops ASTAP and local cloud requests/polling; a remote Astrometry.net job may continue. The existing budgets remain ASTAP 2 minutes and Astrometry.net 15 minutes.

JPlateSolve owns process termination and rollback of unsuccessful local sidecars, preserving pre-existing outputs. SpacePixels no longer deletes those restored files on failed/cancelled solves. Successful header import/cleanup stays in SpacePixels; WCS writing remains non-cancellable. The session's ImageProcessing now closes its cloud client. The build dependency is 1.0.1 and local composite flags remain enabled for development; nothing was published.

Validation: 31 JPlateSolve tests pass. SpacePixels passes 322 normal tests (318 passed, 4 skipped), including admission held through cancellation cleanup, interrupt restoration and cleanup failure reporting. Both composite and isolated jar/POM dependency arrangements pass on Java 21. A representative Java stand-in measured Cancel to confirmed child exit/completion at 24.956 ms; loopback login/upload/polling measured 0.448/11.681/1.376 ms (32 MiB upload). These are synthetic contract timings, not real ASTAP/large-frame measurements; no observer data was uploaded or live solver used. See the [library completion record](../../../JPlateSolve/docs/archive/design-improvements-completed-2026-10.md) for contracts, test scope and remaining request/key improvements. This supersedes the earlier D04 note that plate solving cannot be cancelled.

## S7: explicit session ownership and application preferences (2026-10-21)

**Decision.** Complete S7 and the remaining D02 session-identity work in SpacePixels, without changing solver/detection algorithms, settings formats or the public processing facade. D06's remaining facade/service extraction stays S10; session-folder I/O, preparation questions and explicit per-run context remain S8/S12/S14.

**Application scope.** ApplicationSettings loads the app-level JSON once for the window, before a dataset is imported. Each imported processor receives that same preferences object, including after conversion redirects. Astrometry Config is available before import. Save/Revert still use the existing file format; handover refreshes validation without replacing edited text or saved-value markers. Standalone ImageProcessing/FitsImportTask entry points retain their preference-loading compatibility paths.

**Session scope.** SessionOwner guards replacement and callback admission without Swing. Each ProcessingSession has a UUID, frame metadata references and selection, its own catalogue/correction model, tuning measurements and candidate limit, master-stack cache, last-report reference and owned processing facade. Metadata arrays are protected; large frame/stack buffers are shared by ownership, not deep-copied. The facade still owns its underlying FITS cache and pipeline/overlay services pending S10. The profile/report writer's legacy static candidate-limit fallback remains S14.

Only a successful import can transfer the candidate processor into the current session. Failed, declined or cancelled imports leave it unchanged; stale successful imports close their candidates rather than attaching to a later session. Replacing/closing a session interrupts its owned sky worker and releases the processor, stack cache, selection and tuning results. Closing the window also prevents a late initial import from installing itself.

**Jobs and views.** Handles carry the explicit session. UI completion handlers and progress updates reject the wrong session, in addition to the coordinator's existing job-identity checks. Sky completion keeps both worker and session guards. Star Mask Explorer Apply callbacks also keep their owner. Catalogues are no longer process-wide: viewer annotations bind to a supplied model. Reusable Preview Frame and Full Size windows close and release their old-session data on handover; Blink resets. An independent inspection viewer may keep its old model/buffers deliberately and never adopts the replacement's catalogue. Existing field/solution checks remain in place.

**Validation.**
- JDK 21 normal suite: **331 tests, 327 passed and 4 skipped**, no failures. New tests cover shared unsaved preferences without reloading, independent catalogues/corrections/tuning/selection, defensive metadata containers, stable identity, stale import disposal, delayed progress/result rejection, worker interruption and closure. DependencyRulesTest enforces no Swing or gui references in the new session package.
- Three focused GUI checks pass with isolated home/preferences: application settings are available before import; a real document edit survives two imports without being saved; replacing a solved session with an unsolved one closes the old Preview and removes its catalogue listeners without adopting its catalogue. The idle-startup command/tab availability check also passes. Session lifecycle checks call public application operations on the EDT rather than simulating native input.
- A broader native-input mechanics run was attempted but aborted after desktop automation failed to deliver clicks: the fixture recorded selectedTabIndex=0 (Main) after selecting Astrometry Config, and import chooser lookup timed out. Retrying with a temporary Java module-open/UI-scale override did not fix native input. These failures are not evidence of a production handover defect; the full native-input suite is not claimed as validated here.
- IDE debugging was attempted with non-suspending logpoints. The temporary Gradle debug session ended before useful events/values were captured (empty event buffer and zero-byte run log), so the fallback evidence is the GUI fixture hierarchy plus passing direct-EDT lifecycle checks. Agent breakpoints were removed; user breakpoints were left unchanged.
- Validation uses ignored temporary init scripts to avoid replacing dependency jars held by the running SpacePixels application. Local composite flags stay enabled. No user settings or observer datasets were modified by validation.

**Next.** S8 moves session catalogue/correction and Auto-Tune persistence off the EDT. S10 extracts facade responsibilities. S12 moves import questions onto an explicit EDT adapter. S14 removes the remaining static report/run-context defaults. No multi-window UI was added.

## S8 and S10: background session storage and FITS service boundaries (2026-10-21)

Implemented on 2026-10-21; completion documentation finalized on 2026-10-22. This completes S8 and S10 and the remaining D06 facade/session ownership extraction. The preceding review and S7 records describe their historical state, not the current implementation.

**S8: session metadata I/O.** The window owns a serialized `SessionStorage` worker queue, with a substitutable `SessionMetadataStore` boundary. `FitsImportTask` prepares the saved catalogue, correction and Auto-Tune document before EDT handover, preserving catalogue field, correction solution and tuning frame/dimension checks. Importing an older bulky tuning file compacts it on the worker. Panels apply prepared state without reading session files on the EDT.

Per-session `SessionPersistence` queues saves and guarded UI acknowledgements. Corrections and tuning results are detached before saving, and reimports read behind earlier writes. Correction edits update viewers immediately from memory; the GUI's WCS resolution no longer reads correction files during frame/cursor updates. A failed latest edit reverts to the last successfully saved correction, while an obsolete failure cannot overwrite a newer edit. Catalogue/tuning save failures are reported without discarding the available in-memory result.

Processing jobs wait for pending saves and their UI acknowledgements on the job worker before consuming saved context. Cancelling a run while it waits stops that run, not the independently owned persistence queue. Session closure suppresses obsolete callbacks and prevents correction edits from old viewers, but does not discard queued writes. Normal window close drains storage off the EDT before exiting and reports save failures. The earlier background WCS-write job remains unchanged.

**S10: processing boundaries.** `ImageProcessing` remains a compatibility facade. `FitsImportService` owns interactive/strict-headless metadata loading and its bounded import pool; `FitsBatchService` owns derivative operations; `FitsFiles` and `FitsImages` provide shared discovery and HDU handling. `SessionFrames`, shared by the facade, session, importer and pipeline service, owns metadata references and the last track/variable overlays without deep-copying image buffers. Session closure releases that state.

`FitsImportInteraction` separates questions/progress from file operations. `GuiFitsImportInteraction` creates and handles FITS conversion and exposure-group dialogs on the EDT, leaving conversions on the import worker. Dependency rules enforce no Swing or `gui` references in the extracted FITS services/helpers and session package. Unused private close code is removed. Public `applyWCSHeader` and `onlyStretch` remain deprecated thin batch adapters rather than being deleted, preserving compatibility. Output folders, suffixes, settings formats and conversion mathematics are retained.

**Validation.**
- Java 21 normal suite: **348 tests, 344 passed and 4 opt-in real-data entries skipped**, no failures or errors. Added checks cover deliberately slow stores, EDT painting/editing/navigation, FIFO reads after writes, detached snapshots, stale save failures, rollback across repeated failed edits, session closure and cancellation while waiting for persistence.
- Metadata-store checks preserve field/solution/tuning identity guards and exercise in-memory WCS resolution. FITS tests compare ready interactive/headless metadata, compressed unsigned-16-bit decompression, signed/scaled and negative-value conversion policy, colour/luminance outputs, batch stretching, compatibility output names and unchanged originals. The bounded-pool lifecycle test follows the extracted importer.
- Four focused GUI checks pass: slow restore/save with actual window painting and settings-tab navigation; unsaved preferences surviving two imports; old Preview/listener cleanup without catalogue adoption; idle startup. These use direct EDT operations and isolated settings, not native mouse automation. The full native-input mechanics suite is not claimed as validated.
- A read-only smoke check on `C:\astrodata\Subs\africano-reg` compares interactive/headless ordering, complete header maps, timestamps and dimensions for **153 frames (2494 x 1587)**. It writes no observer data or session files. This is metadata evidence, not a full real-data detection-parity run.
- Validation uses local composite dependencies and ignored init scripts to avoid replacing jars held by the running application. No libraries were published and no user settings were changed.

**Remaining scope.** S12 still shares GUI/API preparation orchestration; combined compressed-32-bit preparation remains F07. Preference writes and ASTAP-directory scans remain D08 work. S13 removes the remaining GUI references in `util`, including the facade's report-size dialog/interactive-adapter creation. S14 replaces legacy static run/report defaults. These are not included in the completed S8/S10 extraction.

## S12: shared GUI/API input preparation (2026-10-22)

**Decision.** Continue the architecture plan with S12, sharing preparation facts and operations while preserving interactive decisions, automatic/refusal policies, public entry points, pixel mathematics and output conventions. This completes the S12 work identified in the preceding S8/S10 record; combined compressed-32-bit GUI preparation remains F07.

**Implementation.** `FitsPreparationService` supplies typed input facts: compression, storage format and scaling, axes, readiness and copied header metadata. Interactive and strict headless import use its inspection through the existing bounded import pool; automatic preparation uses the same inspection serially. `FitsSequenceValidator` checks those facts across the sequence, retaining timing, exposure, extension, dimension and sample-type rules. `FitsFormatChecker` and `FitsPixelConverter` provide the shared format and unsigned-storage definitions. Interactive import no longer repeats preflight and metadata reads through separate loaders.

Both `FitsBatchService` and `DetectionInputPreparation` use `FitsPreparationService.standardizeFile` for pixel normalization, optional colour output, luminance extraction and staged FITS writing. FITS discovery uses `FitsFiles`; XISF discovery/conversion uses `XisfImageConverter` in both paths. The API keeps its `_spacepixels_prepared_mono16_*` output; GUI conversion keeps its existing directory names, suffixes and optional colour copy. Sequence-wide offset versus clipping, `SPOFFSET`, registration fill, timing and WCS/header preservation use the existing conversion rules.

`FitsImportInteraction` and its EDT adapter continue to own GUI questions and progress windows. Strict headless import refuses files requiring preparation; automatic preparation supplies policy without dialogs. Failed, declined and cancelled imports retain their typed job outcomes and session handover rules. Decompression errors and cancellation now propagate rather than falling through the old broad compression-check catch into metadata installation; a regression test checks cancellation, closed progress resources and untouched originals.

**Validation.**
- Java 21 `check`: **354 tests, 350 passed and 4 opt-in real-data checks skipped**, no failures or errors. Both local composite libraries remain enabled. An ignored init script redirects dependency jars to validation output so the running application's jars are not replaced.
- Six new preparation tests compare GUI/API pixels, ordering, timestamps, exposure and WCS/scaling headers across signed/scaled 16-bit, 32-bit integer/float, mono/colour, compressed and XISF inputs. They check offset/clipping, a common offset across frames, original-file preservation, strict refusal, declined conversion and cancelled decompression. Existing FITS service tests protect output names, colour copies and batch operations.
- Three focused GUI checks pass: slow restore/save leaves painting and navigation responsive; unsaved preferences survive two imports; replacing a solved session closes the old Preview without catalogue adoption. These use public operations on the EDT; the full native-input suite was not run.
- Read-only metadata comparison on `C:\astrodata\Subs\africano-reg`: **153 frames, 2494 x 1587**, with identical GUI/headless order, complete header maps, timestamps and dimensions. No observer or session files are written by that check; it does not claim full real-data detection parity.
- Dependency rules now also enforce no Swing/GUI/AWT references in the shared preparation service, automatic preparation facade, sequence validator and format checker.

**Remaining scope.** D08 preference writes and ASTAP-directory scans, S13's remaining GUI references in `util`, S14–S16 run/report context and diagnostics, and F07's combined GUI preparation remain open. No engine algorithm or published dependency version changes are included.

## F09: directory-based iterative detection (2026-10-22)

**Decision.** Implement the basic iterative workflow as sequential ordinary detection runs with different input file lists. The configuration selects a source directory, Start (default/minimum 5), Increase (default 5), and Maximum (default 50; 0 means all available frames). Cap the maximum at the dataset size and always include it, even when the increase does not divide the range. Preview the planned sizes before running. Independently sample each size across the full sequence, include both endpoints, prefer timestamp spacing, and fall back to even index spacing when timestamps are unavailable or sampling would duplicate frames. Samples need not be nested.

**Settings and interaction.** The dialog uses a background directory scan with stale-result guards. It is available before import and leaves an existing session intact. `IterativeDetectionRequest` captures validated current settings, including unsaved edits, an application/site configuration copy and immutable export options. Optional calibrated Auto-Tune runs once on the chosen dataset, with a candidate limit of at least 5 and no greater than the largest pass; its final settings are shared by all passes and do not alter the settings editor. Unsuccessful tuning stops before the passes with its telemetry explanation. Preparation can automatically create mono-16 copies with offset/clipping policy, or require ready input through the shared S12 rules.

The agreed manual-tuning approach is readable italic helper text: import the dataset normally, use Detection Settings and Preview Frame to experiment, then run iteratively with Auto-Tune disabled. No dedicated sample-import/experimental-workspace flow was added.

**Processing.** `IterativeDetectionPlan` owns sizes and selection. `IterativeDetectionPipelineService` supplies each subset to `StandardDetectionPipelineService.run(...)`, which is also behind ordinary detection. Each pass builds its own master, preserves configured analyses and normal readiness checks, uses local frame indices, and exports only its own inputs. Variable-star analysis is no longer forcibly disabled. The retired global master and full-sequence iterative export are removed. Results can therefore differ from the old iterative method; parity is defined against an ordinary run on the same subset and settings.

Each pass has a private `SessionFrames` store that is cleared afterwards. Subsets are planned lazily, pixels/results are released before the next pass, and complete input manifests are written to disk rather than retained in the index. No temporary subset directories or FITS copies are needed for selection. Format preparation retains its normal derivative files. The legacy public `ImageProcessing.detectSlowObjectsIterative(...)` method and task constructor remain as adapters, using Start/Increase 5 and the supplied maximum; fewer than five legacy inputs retain the ordinary-run fallback.

**Outcomes and outputs.** The existing job coordinator and cancellation scope own the whole operation. Current-session identity guards UI callbacks, but the run neither installs a session nor changes its frames, selection, catalogue, overlays or last-report reference. Each pass gets `<count>_frames/detection_report.html` and the ordinary report bundle. `IterativeDetectionIndex` incrementally publishes `index.html`, versioned `run-data.json`, `<count>_inputs.json` with filename/timing/exposure/dimension provenance, and optional `auto_tune.txt`. Shared run settings and lightweight outcomes are retained without private absolute input paths or observing-site coordinates in the manifests. Preview Frame/Blink navigation and annotations also pass.

Failed, report-declined, completed, cancelled and unstarted passes are distinguished. Failure or report refusal allows later passes to continue. Cancellation removes unfinished pass output and retains completed reports with their index; an entirely unfinished run is removed. The GUI can open the independent results index without attaching it to the current session. Per-pass counts are explicitly not unique-object totals across runs.

**Validation.**
- Java 21 `check`: **364 tests, 360 passed and 4 opt-in real-data checks skipped**, no failures or errors. Local JTransient/JPlateSolve composite flags remain enabled; validation jars use the ignored alternate output directory.
- Planner tests cover exact/capped maximum, partial final increments, invalid bounds, integer overflow, endpoints, irregular/duplicate timestamps, absent timing and independently selected subsets.
- Run tests cover request snapshots, unchanged configuration across passes, one tuning invocation with a frozen result, failed/declined passes, partial cancellation, changed source counts and strict preparation refusal. Tuning orchestration uses an injected tuner in its focused test; this does not claim new calibration-algorithm validation.
- A solved synthetic sequence with a moving source and a deliberately poor frame compares each iterative pass with an independent ordinary run on precisely its inputs. Finding records, coordinates, rejected frame indices, included groups, effective profiles and CSVs match, with actual findings and a quality rejection asserted. Variable-star analysis remains enabled and uses normal readiness. This is engine/report parity on synthetic data, not a full real-dataset detection comparison.
- Six focused GUI checks pass: configuration before import and pass preview/manual reminder; running another directory with unsaved settings preserves the open session, selection, catalogue, overlays and report reference; the existing slow-store case; two session lifecycle cases; idle-startup availability. They use direct EDT operations. The full native-input suite was not run; the existing AssertJ monitor emitted Java module-access warnings during the combined run without failing these cases.
- Dependency rules cover the new planner, request and runner as GUI/Swing/AWT-free application code. No engine algorithms, library versions or published API request/result shapes change.

**Follow-up.** The [active F09 enhancement proposal](../../DESIGN-IMPROVEMENTS.md#directory-based-iterative-detection-f09) records the future “super report”: cross-pass association, per-measurement evidence selection, source links and confidence limitations, additional versioned evidence where needed, partial-run support and bounded memory. It is not implemented by this change. Other architecture steps and F07 remain separately tracked.

## D09: shared finding category descriptors, first slice (2026-10-22)

**Implementation.** A shared `FindingCategory` descriptor owns stable report-local prefixes, JSON category text, card anchors, colors, confirmation-row labels/counts, export inclusion, computed-row visibility, representative coordinates and combined-map membership. `ReportData`, report generator inclusion, map legends/category controls and the GUI report prompt reuse it. Compatibility aliases retain existing marker colors. IDs, category text, anchor numbering, variable-card/table fallbacks, report ordering and JSON schema stay unchanged. Core tracks remain always included; photometry readiness stays distinct from report choices; common-motion filtering remains independently visible when residual analysis is disabled.

**Validation.** The reporting suite, GUI report-prompt and detection-summary tests pass with Java 21/local composites, covering HTML/JSON/map/CSV consistency, exclusions, variable presentation, anchors and report-only checkboxes. New category tests check every prefix, S/ST disambiguation, invalid IDs, anchors, original colors, category-specific counts and independent inclusion/visibility. Final `check` passes **430 ordinary tests (426 passed, four opt-in checks skipped)**, API/desktop archive ownership and all seven independent headless artifact-consumer cases. The five focused desktop cases also pass (viewer keys, keyboard settings/reset, typed field search/save/revert, delayed save, workspace scope/output), using scratch settings and restored preferences. Earlier chooser-driven tests timed out before those edited interactions; that native-chooser limitation remains unverified. Processing inputs and algorithms are unchanged. A new real-data/offline-browser comparison has not been claimed. Prepared diagnostics, run-wide context and remaining individual section/CSV identity construction remain under D09/S14–S16.

## D11: visible analysis scope and output folder, first slice (2026-10-22)

**Implementation.** A compact main-window Run line lists enabled analyses and explains whole-sequence detection, selection-only Blink, sequence Preview and Auto-Tune's selection/fallback behavior before starting. Photometry is explicitly subject to runtime readiness; export inclusion is separate from analysis switches. The tuning request and its visible scope share `AutoTuneCandidatePoolBuilder`'s existing four-frame selection threshold. The chosen metadata array is captured without copying pixels. **Open output folder** follows the current session's completed report, restores with its existing last-report reference and clears safely when the report disappears. Unsupported file-manager opening leaves the path in the status line. No detection/extraction job is started by these result controls.

**Validation.** Focused selection/scope/report-path/layout tests pass, covering the four-frame boundary, metadata-array capture, disabled residual subanalyses, readiness wording and missing artifacts. The desktop workspace case passes selection/fallback updates, imported report/output paths, keyboard focusability and deleted-artifact handling with no active engine job. The typed-settings Save/Revert desktop case also passes after the layout addition. Setup uses scratch inputs/settings and restored preferences; no external file manager/browser is launched by the tests. The shared wrapped-label measurement now includes border insets, checked by the layout suite. History, saved all-category overlays and dimension-aware run guidance remain under D11.

## D10: typed residual settings and pinned engine contract, first slice (2026-10-22)

**Implementation.** The seven visible Residual Analysis settings now have application-owned typed definitions with engine IDs, titles, units, guidance, expert disclosure and direct getter/setters. Definition construction checks the pinned engine metadata identity/type. Row creation registers typed load/apply, stable-ID search and visibility; moving one of these rows no longer requires editing the separate apply/load lists. English rule titles resolve through the same definitions. Numeric domains/relationships still come from shared engine validation; non-finite edits fail before mutation, and ordinary GUI range/clamping semantics are preserved. Hidden values and the original shared Overview button models remain owned as before.

All optional field-name reflection/fallback reads and silently ignored setters are replaced by the pinned compile-time engine fields, including the existing streak/geometric controls. The rules service's deliberate metadata-based pair binding is unchanged; it is not an engine-version fallback. Remaining visible-page migrations, processor-factory clarity and published-pair release validation remain under D10/JA-11.

**Validation.** Typed-setting tests compare serialized before/after configurations to prove that each definition mutates exactly its declared field, test hidden-value preservation and shared models, reject invalid contracts/non-finite input, reuse engine diagnostic IDs, and exercise field-ID/unit search and expert disclosure. Focused profile/shared-rule/accessibility/snapshot tests pass. Four desktop cases pass: stable-ID search revealing an expert row plus Save/Revert/profile readback, delayed Save retaining later edits, and both focus-owner keyboard cases. Desktop setup uses public disposable import, scratch home and restored Java preferences. No engine algorithms, defaults or profile formats change. Local composite/artifact validation is distinct from a published Central pair.

## D12: settings layout and shared styles, first slice (2026-10-22)

**Implementation.** Detection and application forms share content-sized rows. HTML descriptions are measured at available width instead of a fixed 85 px height; configuration rows no longer reserve a fixed text column. `UiStyle` derives body/heading fonts and enabled help colors from the look and feel and uses the pinned FlatLaf scaling utility for logical widths/spacing. Numeric/text controls keep native font-dependent height. Overview introduction/analysis guidance and viewer keyboard hints reuse these rules. The existing progress dialog is untouched because F10 is being implemented in parallel.

**Validation.** Focused layout/configuration/accessibility/snapshot tests pass. `SettingsRowLayoutTest` passes at default, 150% and 200% FlatLaf scaling with 13/20/26 logical-pixel base fonts and narrow/wide form bounds, checking reflow, allocated description height and native input height. Both real-key desktop mechanics cases still pass with scratch settings and restored Java preferences. These are layout measurements and focused interaction checks, not a full visual/screen-reader/contrast audit. Main-window/viewer and Overview status/table polish plus coordinated progress wrapping remain under D12.

## D13: keyboard controls and viewer focus (2026-10-22)

**Implementation.** Overview Settings links, main-window Edit settings, the manual/release notice, expert disclosure and ASTAP download use native focusable buttons with accessible names. Settings titles associate with inputs; nested numeric editors receive plain-language descriptions, and changed rows expose their saved value beyond color. Reset is keyboard-focusable and named for its setting. The custom frame now implements Accessible, with an image role, current filename/dimensions/zoom, overlay state and frame-change notification.

Viewer controls retain Tab traversal. Mouse activation of ordinary buttons keeps image focus, while deliberately focused controls own Space/arrows. Shared root bindings only navigate when the image owns focus and no menu is active. Escape from a control returns to the image; Escape on the image closes. Preview's existing Enter/apply and Escape/discard editor bindings remain authoritative. Catalogue and rendering preferences stay independent of scientific settings.

**Validation.** Focused Java 21 accessibility/setting/configuration/annotation tests passed. The new `ViewerKeyboardMechanicsTest` passes both desktop cases with a scratch home and restored Java preferences: actual focused Right/Tab/Space/Escape events cover image stepping, combo/numeric key isolation, focus handback, Overview link activation and per-setting Reset. Setup uses the public import action and direct preview-button invocation; this is not native chooser/mouse coverage. Existing Settings/Viewer mechanics could not reach their native chooser on this desktop and timed out before the edited interactions. A full screen-reader, keyboard-only import/tuning/result-opening and scaling audit remains unverified. The AssertJ monitor uses the existing Java 21 `--add-opens=java.base/java.util=ALL-UNNAMED` workaround.

## F07: combined compressed FITS preparation (2026-10-22)

**Decision and implementation.** Interactive import inspects the decompressed image HDU and offers a single combined preparation for compressed 32-bit integer/float and signed/scaled 16-bit images. The existing shared standardizer reads the compressed source and writes final unsigned 16-bit mono/color output directly. The GUI retains its negative-value choice, sequence-wide offset, registration zeros, metadata, original-file preservation and final-folder load offer. Standard unsigned compressed 16-bit input still only decompresses. Strict headless import still refuses preparation; automatic API preparation retains the same shared numeric rules. No engine algorithms change.

**Validation.** Java 21 with both local composites: 62 focused preparation, pixel conversion, sequence validation, service and import-job tests passed. The parity suite now compares losslessly compressed integer/float mono/color cases under both negative-value policies against API output and the former decompression-then-conversion path, including pixels and WCS/timing/offset headers. It verifies no intermediate directory, original bytes unchanged, one sequence-wide offset, zero padding, validation before writing, decline/cancel and strict rejection. No compressed observing samples were found under `C:\astrodata\Subs`; real compressed-camera and full desktop smoke checks remain unverified. Equivalent prepared pixels and engine inputs are covered; no new scientific threshold was introduced.

## F03: viewer magnitude and name controls (2026-10-22)

**Decision and implementation.** The shared catalogue Layers menu now offers independent star and variable faintest-magnitude limits and a Names and magnitude labels switch. Defaults preserve the existing display (99 means unlimited). Stars use Gaia G or named-star V; variables use their catalogue maximum in its recorded band. Unknown magnitudes remain visible. Marker filtering precedes the existing density thinning; hidden names remain available on hover, and excluded markers cannot leave stale hover targets. Measured candidate overlays and report/detection configuration are unaffected. Java preferences persist choices for subsequently opened viewers; already-open viewers retain their local choices. Storage failure is shown in the viewer. Numeric editing uses a modal dialog rather than intercepting viewer navigation keys.

**Validation.** Java 21 with both local composites: `test --tests eu.startales.spacepixels.gui.ViewerAnnotationPreferencesTest --tests eu.startales.spacepixels.gui.FrameViewHoverTest --tests eu.startales.spacepixels.gui.SkyAnnotationsTest` passed. Tests cover preference round trips/corruption, separate category limits, unknown/boundary magnitudes, renderer-backed hover removal and hover identification with names disabled. No astronomical pixels or processing inputs change. A full desktop/large-font usability pass remains unverified.

**Minor choice for later feedback.** The switch controls all catalogue text, including numeric Gaia labels; independent star/variable name switches could be considered if users need finer control. Unknown magnitudes are deliberately retained rather than implying that their brightness is known.

## D08: background preference writes and ASTAP checks (2026-10-22)

**Decision and scope.** Complete the remaining D08 work after S8/S10 moved session metadata and interactive FITS decisions behind background/EDT boundaries, and S12 shared GUI/API preparation. Move interactive preference writes, profile migration and ASTAP executable/directory checks off the EDT. Preserve immediate in-memory application of valid edits, the existing JSON formats, staged-write/partial-save semantics and the synchronous compatibility methods used outside the GUI. Startup JSON/profile reads remain in the existing initialization path; this is not a claim that all startup I/O is asynchronous. S13 and combined compressed-32-bit preparation (F07) remain separate work.

**Implementation.**

- `ApplicationWindow` owns `config.SettingsStorage`, an ordered single-worker preference queue separate from `SessionStorage`. Actions return typed futures; failures remain visible by target until a successful retry. The queue contains captured values, never live Swing reads. Dependency rules prohibit Swing/GUI references in settings storage and application preferences.
- Detection Save validates and captures the profile, Auto-Tune frame limit, variable-star report options, visualization preferences and row baselines on the EDT. The worker calls the existing `SpacePixelsSettingsFiles` operation. Successful completion advances saved baselines to precisely the captured values, leaving intervening edits unchanged and unsaved. Revert and row Reset use those saved values. Failed or partially successful writes preserve edits and the existing explanation of which file was saved. Save is disabled while pending, not the setting controls. Completion does not clear another session's Auto-Tune markers.
- Application Save and ASTAP/star-depth autosaves capture `AppConfig.copy()` and use the same ordered writer. Their saved-site/default markers reflect the last successful snapshot, including when an autosave also writes valid site edits. Older completions cannot replace newer save status, and failures leave valid defaults in use, visibly unsaved and retryable. Profile migration uses the same queue and a detached profile, keeping its backup rules and success/failure dialogs.
- `AstapInstallationCheck` runs filesystem inspection and the Browse selection's existing `-h` launch check on a `SwingWorker`. It allows one active inspection and at most the newest pending request. Version guards discard superseded successes/failures; only an accepted current selection changes/saves the configured path. Status and errors return on the EDT. Closing drops pending work and cancels/detaches callbacks; a slow filesystem call need not stop immediately, but cannot update a closed window.
- Window-placement writes/flush use the settings queue too, captured once when closing begins. Normal close stops admission, drains settings and session writes on the shutdown thread, reports outstanding failures and preserves the existing wait-or-exit choice on a drain timeout. Delayed UI completions are suppressed after closure. No foreground detection-job slot is needed for these preference operations.

**Validation.**

- Java 21 `check`: **373 tests, 369 passed and 4 opt-in real-data checks skipped**, with local JTransient/JPlateSolve composites enabled. No detection algorithms, dependencies or public pipeline request/result formats change.
- `SettingsStorageTest` gates ordered writes, exercises the EDT while storage is blocked, verifies detached snapshots and unchanged live edits, checks per-target failures/retries and drains/rejection after closure. `SettingsNavigatorSnapshotTest` proves that completion does not mark newer row values saved, and Reset restores the captured value.
- `AstapInstallationCheckTest` checks a blocked scanner with an EDT heartbeat, newest-only pending inspection, obsolete failures, latest-failure delivery/retry, closed-controller suppression and real database discovery without executing a solver. It does not claim validation of an installed ASTAP binary or plate-solving accuracy.
- Focused GUI checks use a scratch home and restored Java preferences. The five D08 cases cover detection/visualization snapshots and Revert, application autosave ordering with newer site edits, application-save failure/retry, partial profile/visualization failure/retry and responsive close with a pending write. Existing session storage/lifecycle and iterative-session-preservation checks also pass. Synthetic setup enters through the application's import method; the initial native-chooser setup timed out before reaching the settings checks, so these tests do not claim native chooser/input coverage.
- Validation supplies `--add-opens=java.base/java.util=ALL-UNNAMED` for the existing AssertJ monitor, without changing project dependencies. The live-report proxy reports its port already in use by another process; these checks do not require it. Real observing datasets and the full native-input suite are not part of this wiring-only change.

**Documentation.** The manual describes background Save/Check behavior and later edits. The developer and high-level design guides describe storage ownership, snapshot baselines, failure/shutdown behavior and the remaining synchronous startup reads. D08 leaves the active queue; Stage C's next architecture step is S13.

## F10 first slice: live view in the progress dialog (2026-10-22)

**Decision and scope.** The maintainer chose F10 as the next feature and agreed to start with the slice that needs no engine change: a live view in the existing `ProcessingProgressDialog` with the pictures SpacePixels already holds. Agreed defaults: shown by default with a remembered Hide/Show picture choice, about 280–300 logical pixels, at most one new picture every 3 s, and the report's automatic stretch. The engine-stage pictures (reference build, star mask, extraction and linking) stay the open second slice, after JTransient JA-09. The progress-dialog part of D12 (wrapping status text) was done with it, as the plan asked.

**Implementation.**

- `util.RunPreview` (a detached image and caption, or a caption-only/cleared update), `util.RunPreviewSink` and `util.RunPreviewScope` (a thread-bound sink, like JTransient's `CancellationScope`). Producers call `RunPreviewScope.offer(stageChange, maker)`; the maker runs only when the sink wants a picture now. Without a scope, as in API, CLI and headless runs, nothing is made. A failing maker is logged at FINE and skipped.
- Producers: `StandardDetectionPipelineService` offers each loaded frame (the first at once), a caption-only "Picture from loading · the detection is running" update before the engine call, and the master stack after it. `TrackVisualizationRenderer.saveLosslessPng`/`saveAnimatedGif` offer report pictures, captioned from the file name; shape and mask diagrams are skipped. `IterativeDetectionPipelineService` clears the view at each pass ("Pass 2 of 3 · 7 frames") and before its Auto-Tune.
- `util.PreviewThumbnails`: frames and stacks are block-averaged to at most 420 px on the long side and stretched with `DisplayImageRenderer`'s levels from a sample of about 250,000 pixels; report pictures are scaled copies. Frame captions carry the frame number and UTC observation time.
- `DetectionTask` and `IterativeDetectionTask` gain `showingPreviews(sink)`; `MainApplicationPanel` passes `ProcessingProgressDialog.previewSink()` for Detect Moving Targets and Detect Iteratively, opens the view in `jobStarted` for those kinds and ends it in `jobEnded`.
- `ProcessingProgressDialog`: a Live view title with a Hide/Show picture button, a 300 × 200 logical-pixel picture box with an accessible name and description, and a two-line caption. The sink throttles at 3 s with stage changes at once, answers before any work, keeps only the newest pending picture, and drops a finished job's pictures by generation. Status and caption text wrap within a UI-scaled width; their reserved two-line height grows during a job but does not shrink until the next one, so the dialog does not jump. The choice is the Java preference `progressDialog.liveView` in node `/eu/startales/spacepixels/gui`, written through `SettingsStorage`. After rebasing onto the D12/D13 work of the same evening, the dialog uses `UiStyle` spacing and help colour, and Hide/Show picture is an `AccessibleControls` link with an accessible name that follows its state.

**Validation.**

- `./gradlew test`: 455 tests after the rebase, 0 failures, 4 opt-in real-data checks skipped, with local JTransient and JPlateSolve composites. New: `RunPreviewScopeTest` (6), `PreviewThumbnailsTest` (5), `LivePreviewPipelineTest` (1), `ProcessingProgressDialogTest` (9, including long messages at 13/20/26 pt base fonts and the keyboard link) and one `IterativeDetectionRunTest` case. `DependencyRulesTest` still passes: `util` and `tasks` use no Swing.
- `LivePreviewPipelineTest` runs a synthetic solved sequence with a moving target and a rejected frame twice, without and with a sink that wants every picture. Findings, rejected frames, included groups, frame count, `detection_config.json` and every CSV are identical. The pictures arrive in order (frames, engine caption, reference, report pictures), with no shape or mask diagrams, and all within 420 px.
- Real application, scratch home and restored Java preferences, through a scratch harness on the mechanics base class (not added to the suite): Africano (18 frames, 5 s), Eagle-noisy (46 frames, 6 s) and klangwolke-1 (10 × 61 MPix, 37 s), with a screenshot of the dialog every second. Loading frames, the "picture from loading" caption, the reference and report pictures (global track map, finding crops) appeared as designed. The first screenshots led to one wording change: the engine-stage caption said "Last loaded frame", although the throttle can leave an earlier frame on view.
- Overhead on klangwolke-1, separate JVMs, live view throttled as in the dialog: 31.1 s with the view and 31.9 s without, within run-to-run variation; peak heap 5.1 GB against 5.5 GB (GC variation); 6 pictures. `report-data.json` findings, rejected frames, included groups and frame count are identical.

**Limitations.** No picture during the engine call; on short runs the report can finish within the 3 s throttle after the reference, so the reference stays. 150/200 % display scaling of the dialog was not inspected; larger base fonts are covered by `ProcessingProgressDialogTest`. No GUI mechanics case runs a detection in the suite; the dialog is covered by unit tests on the event thread and the scratch harness above.

**Documentation.** The manual's Step 4 describes the live view. The high-level design describes the scope, producers, thumbnails, dialog behaviour and preference. The plan keeps F10 in progress for its second slice and updates D12's progress-dialog evidence.

## D11: the last run's tracks in Preview Frame and Manual Transient Inspection (2026-10-23)

**Decision.** The maintainer reduced D11 to one feature: after a run, see its tracks in Preview Frame and Manual Transient Inspection as Blink shows them, and nothing else. Restoring a saved run, a run history, a unified completed-run inspection mode, the session summary, the analysis plan and the memory hint were dropped.

**What changed.**

- `SequenceViewerFrame`, the base of both windows, has Blink's **Show tracks** switch and its T key, beside Show detections and Show variable stars. `setTrackOverlay(overlay, files)` enables it only when a track of the session's last run is in the window's frames, with Blink's tooltips; it is off by default, as in Blink.
- Both windows draw the same `TrackOverlay` as Blink (`ProcessingSession.getTrackOverlay()`): the positions that run measured, drawn by `FrameView` by file name. Nothing is detected again and later settings changes do not change it.
- Preview Frame takes the overlays when it opens and again when a job ends (`DetectionSequenceFrame.showLastRun()`, called from the panel's unlock), so a run that ends while it is open makes its tracks available. Manual Transient Inspection takes them when it opens (`open(TrackOverlay)`).
- Closing a viewer clears its frame view, which drops the overlay. `TrackOverlay` is immutable and holds no listener, so nothing else has to be released.
- Like Blink, the switch follows the run's tracks, which `StandardDetectionPipelineService` stores before the report question; a run whose report was declined still offers its tracks. Iterative runs leave the open session's overlays unchanged, as before.

**Validation.** The new GUI mechanics test `LastRunTracksMechanicsTest` uses real key events on a synthetic session. Before a run, Preview Frame's Show tracks is disabled. A job that leaves a track through three frames, as a detection does, enables it while the window stays open. T then shows the tracks, and the frame's accessible description says "Tracks on.". Manual Transient Inspection, started afterwards, offers the same tracks. The drawing is Blink's unchanged `FrameView` code. The full GUI mechanics suite passes (30 tests), as do the unit tests (460, 4 skipped).

**Documentation.** The manual describes Show tracks for both windows; the high-level design describes the shared overlay and when Preview Frame refreshes it.

## F10 second slice: findings while the detection runs (2026-10-23)

**Decision.** The maintainer agreed to the slice-2 specification (kept below): while the engine runs, show what it has found so far, using the JTransient stage observer (JA-09) rather than progress text.

**What changed.**

- `util.LiveFindings` is the engine's `PipelineStageObserver`. `StandardDetectionPipelineService` creates it only when the run's thread has a live-view sink, so API, CLI and headless runs pass no observer. On the engine thread it copies positions, frame numbers, counts and light-curve points, and only while the sink wants a picture. One daemon worker draws the pictures. When the engine returns, the worker finishes the pictures already handed to it, waiting at most 2 s, then stops; so the last stages appear before the report's pictures.
- Pictures by stage:
  - the stationary-sky reference with its star count;
  - slow-mover crops from the maximum stack;
  - for tracking: the star mask over the reference (tinted in proportion to the masked share of each thumbnail pixel, with the share of the field in the caption), then up to three moving objects, two streaks and the strongest anomalies; a moving object is shown as a strip of its first, middle and last frame;
  - local-rescue candidates;
  - variable-star candidates (high confidence first, then possible; rejected stars are left out, as in the report and the viewers), each beside its light curve.

  Each stage keeps at most 8 slides. Captions say "provisional" and give the real counts so far; a stage without findings says so.
- `util.reporting.LiveFindingPictures` draws the crops with the report's edge-aware crop and automatic stretch.
- `RunPreview` can carry slides. `ProcessingProgressDialog` shows them in turn every 3 s, stops while the picture is hidden, and replaces them with the next preview.
- `RunPreviewScope.labelled` puts "Pass n of m" before every caption of an iterative pass.
- The post-run reference picture of slice 1 is replaced by the observer's reference.

**Validation.**

- Unit tests:
  - `LiveFindingsTest` feeds tracks, streaks, anomalies, a star mask and variable candidates (one rejected) through the observer, and checks that hidden pictures and switched-off stages do nothing.
  - `ProcessingProgressDialogTest` covers the slide rotation, hiding and replacement.
  - `LivePreviewPipelineTest` runs a synthetic detection with and without the live view: the stage pictures come before the report's, and findings, rejected frames, settings and CSVs are identical.
  - Test totals: 460 unit tests (4 skipped) and 30 GUI mechanics tests pass.
- Real data. Each dataset was run three times with variable stars on: without a live view, with the picture hidden, and with it shown (3 s throttle, as in the dialog).

  | Dataset | Off | Hidden | Shown | Pictures shown |
  |---|---|---|---|---|
  | klangwolke-1 | 30.2 s | 27.9 s | 28.1–29.6 s | 8 previews, 9 slides |
  | dc-reg | 9.4 s | 8.2 s | 8.3 s | 3 previews, 2 slides |
  | M81-kumar | 26.6 s | 23.2 s | 24.1 s | 7 previews, 11 slides |
  | klangwolde-2 | 126.8 s | 121.2 s | 128.0 s | 12 previews, 17 slides |

  On all four, `report-data.json` (apart from its generation time), `detection_config.json` and the CSVs are identical across the three runs. Peak heap was within run-to-run variation (klangwolde-2: 9.8 GB off, 9.8 GB shown).
- The rendered slides were inspected. Two problems were found and fixed:
  - pictures of the last stages were dropped when the engine returned;
  - the star mask tinted the whole field, partly because `getRGB` lightens a grey thumbnail.

**Limitations.** On a large run the report's pictures replace the last stage's slides after 3 s, so most of the variable-star slides are not seen; see the open cadence decision in the plan. Cancel during each stage was not exercised on real data; the observer only copies, and cancellation stays with the job's `CancellationScope`. 150/200 % display scaling was not inspected.

### The specification as agreed

#### Slice 2: findings while the detection runs (specified 2026-10-23)

**Goal (maintainer request, 2026-10-23).** While the engine call runs, show what it has found so far, and the reference and star mask as each stage finishes them. Findings here means moving-object tracks, streaks, anomalies, slow-mover candidates, local-rescue candidates and variable-star candidates. Today the view changes only at the start and end of the engine call: it shows "Picture from loading" until the engine returns with the master stack, and on a long run that wait is most of the time.

**Where the pictures come from.** The engine finishes its stages in this order: median reference, star mask, maximum stack and slow movers, tracking (tracks, streaks, anomalies), common motion, residual analysis, then photometry (variable stars). JTransient JA-09 adds an optional stage observer that receives each stage's finished products (see [JA-09's F10 requirement](../../../JTransient/docs/archive/design-improvements-closed-2026-10.md#ja-09-stage-observer-for-the-live-view-2026-10-23)). No progress text is parsed and no extra computation runs. Without that observer, the only source is SpacePixels' own work after the engine returns, which is slice 1.

**What SpacePixels does with them:**

- **On the engine thread, only copy and hand over.** The observer copies the small facts a picture needs: positions, frame numbers, bounding boxes, scores and light-curve points. It hands them to one preview worker and returns. It never keeps engine objects that a later stage could still change. The large images it hands over by reference are the frames SpacePixels loaded for the run, and the reference/mask once JA-09 documents them as final.
- **On the preview worker, choose and render.** Pick a few representative findings for each stage, such as the longest track, the brightest streak, the strongest anomaly and the highest-scored variable candidate. Render each one as a crop from the run's frames with a restrained marker. Reuse the report's crop and marker code (`TrackVisualizationRenderer`, `VariableStarPresentation`) so the live pictures match the report.
- **Show several findings, not just one.** When a stage yields several findings, keep up to 8 thumbnails and rotate through them at the existing 3 s cadence while later stages run; the next stage's set replaces them. A moving object can show a three-crop strip (first, middle and last frame side by side) rather than an animation. A variable star can show its crop beside a small light curve.
- **Caption with the kind and the evidence.** For example: "Moving-object candidate · 7 frames · provisional", "Streak · frame 12 · provisional", "Variable-star candidate · provisional". A caption may give real counts so far ("3 tracks, 1 streak, 12 anomalies so far"), never an invented figure. A stage with no findings says so, e.g. "No moving-object candidates", instead of leaving an older picture unexplained.
- **Mark everything provisional.** Common-motion filtering can still remove findings after tracking, and the report applies its own inclusion choices. A picture is "provisional" until the report includes the finding.
- **Iterative runs.** Clear the set at the start of each pass, and put the pass in the caption.

**Limits.** Keep everything from slice 1: no work while the picture is hidden or in headless/API runs, latest stage wins, and at most 8 thumbnails of at most 420 px retained. Stop the worker and drop every reference before the run releases its frames. A preview that fails is logged at FINE and skipped. The observer can never fail or cancel the run, and its callback only copies and returns; cancellation stays with the job.

**Acceptance:**

- `report-data.json`, the CSVs and the rejected frames are byte-identical with the picture shown, hidden and with no observer attached. Check klangwolke-1, klangwolde-2 and one variable-star dataset.
- Wall time and peak heap stay within slice 1's overhead.
- Cancel responds during every stage.
- Captions say "provisional", nothing is shown after the run ends, and an iterative pass clears the set.
- A GUI test feeds tracks, streaks and variable candidates through a fake observer.

**Order.** The JTransient JA-09 observer comes first, with its own byte-identical check, then this slice. At the same time, revisit the report-phase cadence: on short runs the report can finish within one 3 s interval.

Decision: Agreed for slice 2.
