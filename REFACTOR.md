# Refactor Strategy

Snapshot date: 2026-09-24

Architecture reference:
- Stable facade and subsystem documentation lives in [HIGH_LEVEL_DESIGN.md](HIGH_LEVEL_DESIGN.md).
- Keep this file focused on current refactor targets, extraction order, and verification notes.

Method used:
- Count Java source lines under `src/main/java` on the current worktree.
- Sort descending by line count.
- Re-check the largest classes against the current extraction state, not only against raw size.

## Current largest classes

| Rank | Class | Lines | Current read |
| --- | --- | ---: | --- |
| 1 | `DetectionReportGenerator` | 1576 | Still a report facade plus leftover static rendering/helper functions. Large, but no longer the main architectural problem. |
| 2 | `CreativeTributeRenderer` | 1320 | Large by design after the Signal Weave work. It is self-contained creative rendering, so size alone is not a reason to split it now. |
| 3 | `ImageProcessing` | 1317 | Much smaller than the previous snapshot. Still owns FITS import/preparation, metadata loading, conversion, WCS writeback, preview helpers, and facade methods. |
| 4 | `DetectionConfigurationPanel` | 1228 | The strongest current refactor candidate. It mixes Swing layout, config binding, persistence, runtime visualization settings, constraints, preview, and auto-tune actions. |
| 5 | `TargetVisualizationSectionWriter` | 1056 | Still the largest report section writer. Worth splitting only when target-report feature work resumes. |
| 6 | `MainApplicationPanel` | 967 | GUI workflow coordinator. Better handled after service/facade boundaries are clearer. |
| 7 | `DetectionReportAstrometry` | 877 | Still large, but many originally proposed extractions already happened. Treat as mostly stabilized. |
| 8 | `XisfImageConverter` | 850 | Focused but sizeable. Do not split unless XISF import behavior changes. |

## What changed since the previous snapshot

The old plan is partly obsolete.

- `DetectionReportAstrometry` no longer deserves to be the top refactor target.
  - These extractions already exist:
    - `AstrometryContextFactory`
    - `SolarSystemQueryTargetFactory`
    - `SatCheckerQueryTargetFactory`
    - `JplSbIdentUrlBuilder`
    - `SatCheckerUrlBuilder`
    - `SkyViewerHtmlBuilder`
    - `AstrometryIdentificationHtmlBuilder`
    - `SolarSystemQueryTarget`
    - `SatCheckerQueryTarget`
- `ImageProcessing` has already been reduced materially.
  - Standard pipeline orchestration lives in `StandardDetectionPipelineService`.
  - Iterative pipeline orchestration lives in `IterativeDetectionPipelineService`.
  - Shared pipeline helpers live in `DetectionPipelineSupport`.
  - Headless input preparation lives in `DetectionInputPreparation`.
  - Plate solving has a dedicated `PlateSolveService`.
- The reporting layer grew again because the optional AI creative section was enhanced.
  - `CreativeTributeRenderer` is now one of the largest classes, but it is cohesive and isolated.
  - Do not split it simply because it is large.
- The documentation and report feature set now include richer object-identification links:
  - SkyBoT and JPL for moving-object tracks
  - SatChecker for streak tracks
  - Stellarium Web context links for both

## Revised strategy

The next round should not be line-count driven. Use volatility and responsibility boundaries:

1. Split code that changes for different reasons.
2. Keep self-contained rendering code together unless it blocks a real change.
3. Preserve existing public/report behavior first; move code behind facades before changing behavior.
4. Prefer testable data/binding/persistence seams over cosmetic class splitting.

## Recommended next sequence

### 1. `DetectionConfigurationPanel`

This is the best next target.

Why:
- It is a large Swing form and a controller at the same time.
- It owns widget construction, config-to-widget binding, widget-to-config binding, persistence, optional-field compatibility, runtime visualization preferences, constraints, preview, and auto-tune actions.
- Recent work added more visualization/report preferences, so this class will keep attracting unrelated changes.

Recommended extraction order:

1. `DetectionSettingsPersistenceService`
   - Own loading/saving:
     - `spacepixels_detection_profile.json`
     - legacy detection profile migration
     - `spacepixels_visualization.json`
   - Keep file-location decisions out of the panel.

2. `DetectionConfigCompatibilityAdapter`
   - Own optional-field reflection helpers.
   - Preserve compatibility with older/newer JTransient configs without scattering reflection through UI code.

3. `DetectionConfigBinder`
   - Own `DetectionConfig -> controls` and `controls -> DetectionConfig`.
   - This is the most valuable seam because it can be tested without full UI workflows.

4. `VisualizationPreferencesBinder`
   - Bind export-only controls to `ExportVisualizationSettings` / visualization preferences.
   - Keep detection profile fields separate from report/export preferences.

5. Tab builders only after binding is clean.
   - Possible classes:
     - `BasicTuningTabBuilder`
     - `ObjectDetectionTabBuilder`
     - `StreakDetectionTabBuilder`
     - `MovingObjectsTabBuilder`
     - `AdvancedVisualizationTabBuilder`

What not to do:
- Do not start by splitting every tab into a class while the binding code is still embedded in the panel.
- Do not change config file formats during this refactor.

Verification:
- `gradlew.bat test`
- Manually verify:
  - load saved detection profile
  - load saved visualization preferences
  - save configuration
  - load defaults
  - preview detection settings
  - auto-tune updates controls

### 2. `ImageProcessing`

This is the best non-UI target after the config panel.

Current state:
- It is no longer responsible for the full standard/iterative pipeline internals.
- It is still the main facade for import, FITS metadata, conversion, WCS persistence, preview rendering, and report triggering.

Recommended extraction order:

1. `FitsSequenceImportService`
   - Move GUI import discovery and validation:
     - `getFitsFilesDetails`
     - GUI metadata loading
     - XISF redirect/import handling
     - compressed FITS checks
     - color/bit-depth readiness decisions

2. `FitsMetadataService`
   - Move metadata extraction and consistency validation:
     - `getFitsfileInformation`
     - `getFitsfileInformationHeadless`
     - `loadFitsMetadataHeadless`
     - timestamp diagnostics

3. `FitsPreparationService`
   - Consolidate GUI preparation with the existing headless `DetectionInputPreparation` behavior.
   - The goal is to reduce duplicate rules for:
     - compressed FITS
     - 32-bit to 16-bit conversion
     - color to mono conversion
     - XISF-only directories

4. `WcsHeaderService`
   - Move WCS header writeback and solve-artifact cleanup:
     - `applyWCSHeader`
     - `updateFitsHeaderWithWCS`
     - `cleanupSolveArtifacts`

5. Keep `ImageProcessing` as a facade.
   - Do not remove the facade while GUI and API callers still depend on it.

What not to do:
- Do not merge GUI import behavior and headless batch behavior in one large rewrite.
- Do not change generated working-directory naming unless there is a compatibility reason.
- Do not move preview/stretch code in the same pass as input-preparation changes.

Verification:
- `gradlew.bat test`
- GUI import checks for:
  - normal 16-bit mono FITS
  - compressed `.fz`
  - 32-bit FITS
  - color FITS
  - XISF-only directory
- Headless API/batch input-preparation tests.

### 3. `DetectionReportGenerator`

Do not treat this as the next major refactor just because it is currently the largest class.

Current state:
- It is mostly a report facade and compatibility surface.
- Some helper clusters still belong elsewhere, but report behavior is sensitive and already has many moving parts.

Reasonable future extractions:

1. `ReportImageAssetRenderer`
   - Move display image, mask overlays, cropped mask overlays, and shared image-writing helpers.

2. `ReportGlobalMapRenderer`
   - Move remaining global-map and diagnostic-map image methods that do not belong in `GlobalMapsSectionWriter`.

3. `KinematicCompassRenderer`
   - Move the Gemini creative compass if that feature changes again.

4. `IterativeIndexReportWriter`
   - Move iterative index export if iterative reporting grows.

What not to do:
- Do not split `DetectionReportGenerator` in tiny pieces just to reduce line count.
- Do not rename report asset files unless the report output contract is intentionally changing.

Verification:
- `gradlew.bat test`
- `gradlew.bat realDataTest` for report-affecting changes
- Compare generated report bundles against a baseline.

### 4. `CreativeTributeRenderer`

Leave it alone for now.

Why:
- It is large, but the feature is cohesive.
- It is optional, isolated, and intentionally visual.
- Splitting it now would mostly create navigation overhead.

Only split if one of these happens:
- The creative section gets multiple independent themes.
- You need unit tests for metric/interpretation logic separate from Java2D rendering.
- The renderer becomes hard to change without breaking layout.

Possible future split:
- `CreativeSignalSummaryBuilder`
- `CreativeTributeLayout`
- `CreativeTributePainter`
- `CreativeSignalTextBuilder`

Do not do this now unless feature work resumes in that area.

### 5. `TargetVisualizationSectionWriter`

Hold until target-report work resumes.

Why:
- It contains several presentation paths, but they are still one report section.
- Splitting it while no target-report feature is being changed risks churn without payoff.

Split only when needed:
- `SingleStreakSectionWriter`
- `StreakTrackSectionWriter`
- `MovingTargetSectionWriter`
- `AnomalySectionWriter`
- optional `SkyOrientationOverlayRenderer`

Verification:
- `gradlew.bat test`
- `gradlew.bat realDataTest`
- Confirm generated GIFs, crops, identification links, and live-render links still work.

### 6. `MainApplicationPanel`

Do this after `ImageProcessing` is cleaner.

Likely extraction targets:
- import workflow controller
- detection launch controller
- blink/preview action controller
- report-opening/result handling

Do not split it before the service/facade APIs it calls are stable.

## Work I would not prioritize now

- More `DetectionReportAstrometry` splitting.
  - The important seams already exist.
  - Further work should be driven by lookup-feature changes, not size.
- Splitting `CreativeTributeRenderer`.
  - It is large but cohesive.
- Splitting every report section writer.
  - Use feature pressure as the trigger.
- Replacing static helpers broadly.
  - Static package-private helpers are acceptable where they are pure formatting/rendering utilities.

## Suggested near-term plan

If the next goal is maintainability:

1. Refactor `DetectionConfigurationPanel` persistence and binding.
2. Refactor `ImageProcessing` import/metadata/preparation seams.
3. Clean small report helper clusters from `DetectionReportGenerator` only if report work continues.

If the next goal is new report features:

1. Keep `DetectionConfigurationPanel` unchanged.
2. Add the feature in the relevant report writer.
3. Extract only the helper class needed by that feature.
4. Run real-data report comparison.

If the next goal is API/headless robustness:

1. Start with `ImageProcessing` plus `DetectionInputPreparation`.
2. Make GUI and headless preparation rules share the same service-level logic.
3. Add tests around FITS/XISF/compression/bit-depth preparation before touching GUI code.

## Verification expectations

Use the local path bootstrap before Gradle commands, for example:

`cmd /c "call ""C:\Users\Petros Pissias\OneDrive - ESA\Desktop\dev\setpaths.bat"" && cd /d ""C:\Users\Petros Pissias\OneDrive - ESA\Desktop\dev\projects\SpacePixels"" && gradlew.bat test --no-daemon"`

For each refactor round:

- `gradlew.bat compileJava`
- `gradlew.bat test`
- targeted GUI smoke checks for UI refactors
- targeted API/batch checks for input-preparation refactors

For report-affecting refactors:

- `gradlew.bat realDataTest`
- compare fresh real-data reports against the prior baseline bundle
- only runtime-dependent `Processing Time` values should differ when behavior is unchanged
- standard detection report export still works
- iterative report export still works
- SkyBoT/JPL/SatChecker/Stellarium links still render
- live-render actions still work

For settings refactors:

- saved detection profile loads
- legacy detection profile migration still works
- visualization preferences load and save separately
- defaults can be restored without overwriting saved files until explicitly saved
