# Refactor Candidates by Class Size

Snapshot date: 2026-04-24

Method used:
- Count Java source lines under `src/main/java` on the current worktree
- Sort descending by line count
- Cross-check the largest classes against the completed reporting refactor work and current verification results

## Top 5 largest classes

| Rank | Class | Lines | Why it matters now |
| --- | --- | ---: | --- |
| 1 | `ImageProcessing` | 2568 | Still mixes FITS I/O, detection orchestration, export coordination, plate solving, and WCS persistence |
| 2 | `DetectionReportAstrometry` | 1580 | Still mixes astrometry math, query-target building, URL generation, and report-facing HTML |
| 3 | `DetectionReportGenerator` | 1348 | Now much smaller, but it still owns report orchestration plus a leftover mix of helper methods |
| 4 | `DetectionConfigurationPanel` | 1044 | Still combines Swing layout, binding, persistence, compatibility logic, and controller behavior |
| 5 | `TargetVisualizationSectionWriter` | 953 | The main remaining large per-section report writer, with streak, moving-target, anomaly, and overlay logic mixed together |

## What changed since the previous snapshot

- The old `ImageDisplayUtils`-centric report note is now obsolete.
  - Report export code now lives under `eu.startales.spacepixels.util.reporting`.
- The report/export subsystem was materially split into focused classes:
  - `DetectionReportDocumentWriter`
  - `ReportClientScriptWriter`
  - `ExportVisualizationSettings`
  - `DetectionReportContext`
  - `DetectionReportSummary`
  - `TrackCropGeometry`
  - `TrackVisualizationRenderer`
  - `TargetVisualizationSectionWriter`
  - `ResidualReviewSectionWriter`
  - `DeepStackReportSectionWriter`
  - `PipelineDiagnosticsSectionWriter`
  - `GlobalMapsSectionWriter`
  - `CreativeTributeRenderer`
  - `ReportLookupProxyServer`
- `DetectionReportGenerator` is down to 1348 lines and is now primarily an orchestration facade instead of the old all-in-one export subsystem.
- Top-level documentation has been added across `util.reporting`.
- Real-data verification was run on 2026-04-24 with `./gradlew realDataTest`.
  - Fresh report bundles matched the previous baseline bundles for all 5 datasets.
  - All non-HTML assets were byte-identical.
  - The only HTML deltas were runtime-dependent `Processing Time` values in 4 datasets.

## Common themes across the remaining large classes

- The biggest remaining classes still mix orchestration with detailed formatting or persistence behavior.
- Report code is in much better shape than before, but astrometry and the main target-visualization section still concentrate too many responsibilities.
- UI classes continue to act as both views and controllers.
- Static runtime settings still exist, but they are now more isolated than they were before the report split.

## 1. Detection report/export subsystem (status: largely complete for this pass)

### Current state

- The first major refactor round here is complete enough to stop.
- The report subsystem now has clear package-level structure and real-data regression coverage.
- `DetectionReportGenerator` still contains some rendering helpers and orchestration glue, but the previous 5000-line export knot is gone.

### Completed extractions

- Report shell and shared document scaffolding:
  - `DetectionReportDocumentWriter`
  - `ReportClientScriptWriter`
- Export settings and shared models:
  - `ExportVisualizationSettings`
  - `DetectionReportContext`
  - `DetectionReportSummary`
- Shared rendering helpers:
  - `TrackCropGeometry`
  - `TrackVisualizationRenderer`
  - `GifSequenceWriter`
- Focused report sections:
  - `TargetVisualizationSectionWriter`
  - `ResidualReviewSectionWriter`
  - `DeepStackReportSectionWriter`
  - `PipelineDiagnosticsSectionWriter`
  - `GlobalMapsSectionWriter`
- Optional creative output:
  - `CreativeTributeRenderer`

### Remaining debt

- `DetectionReportGenerator` is still larger than ideal.
- `DetectionReportAstrometry` remains tightly coupled to report concerns.
- `TargetVisualizationSectionWriter` is now the biggest single report-section class.

### Recommendation

- Do not keep splitting `DetectionReportGenerator` just to chase line count.
- Treat the report refactor as stable for now.
- If report work resumes, the next target should be `DetectionReportAstrometry`, not another small extraction from `DetectionReportGenerator`.

## 2. `DetectionReportAstrometry` (1580 lines)

### Why this is the next target

- It is now the highest-friction class inside the reporting layer.
- It mixes four different change axes:
  - astrometry context creation
  - query-target construction
  - external URL generation
  - report-facing HTML wording and layout
- Any change to lookup strategy currently risks report wording, and vice versa.

### Current responsibility clusters

- Build astrometry context from FITS metadata and app config
- Estimate query radii and track timing windows
- Compute sky rates, apparent motion, and motion classification
- Build SkyBoT, JPL, SatChecker, Stellarium, and sky-viewer URLs
- Format report-facing HTML fragments for identification sections
- Resolve observer-site and observatory-code rules

### Refactor plan

1. Extract astrometry context creation.
   - Suggested class: `AstrometryContextFactory`
   - Move WCS, observer-site, and session-midpoint construction here

2. Extract query-target building.
   - Suggested classes:
     - `SolarSystemQueryTargetFactory`
     - `SatCheckerQueryTargetFactory`

3. Extract URL builders.
   - Suggested classes:
     - `SkybotUrlBuilder`
     - `JplSbIdentUrlBuilder`
     - `SatCheckerUrlBuilder`
     - `SkyViewerUrlBuilder`

4. Extract report-facing HTML builders.
   - Suggested classes:
     - `AstrometryIdentificationHtmlBuilder`
     - `SkyCoordinateHtmlFormatter`

5. Keep `DetectionReportAstrometry` as a thin package-private facade at first.
   - Minimize churn in existing report code
   - Preserve current behavior while the pieces settle

### Good first extraction order

1. `AstrometryContextFactory`
2. `SolarSystemQueryTargetFactory`
3. `JplSbIdentUrlBuilder` and `SatCheckerUrlBuilder`
4. `AstrometryIdentificationHtmlBuilder`

### Expected payoff

- Safer changes to external lookup rules
- Cleaner separation between astrometry logic and report wording
- Easier testing of URL/query generation without HTML noise

## 3. `ImageProcessing` (2568 lines)

### Current responsibility clusters

- FITS file discovery and metadata loading
- Headless metadata validation
- Standard detection pipeline orchestration
- Iterative detection pipeline orchestration
- Batch mono conversion and batch stretch
- Plate solving
- WCS header writeback and cleanup
- Export coordination
- App-config loading and saving

### Main problems

- This is still multiple services hidden behind one facade.
- FITS I/O, pipeline execution, export triggering, and plate-solving persistence are separate change axes.
- The class is broad enough that unrelated changes still collide here.

### Refactor plan

1. Extract detection pipeline orchestration.
   - Suggested classes:
     - `DetectionPipelineService`
     - `IterativeDetectionService`

2. Extract export coordination.
   - Suggested class: `DetectionExportCoordinator`

3. Extract FITS loading and metadata handling.
   - Suggested classes:
     - `FitsSequenceLoader`
     - `FitsMetadataService`

4. Extract plate-solving behavior.
   - Suggested classes:
     - `PlateSolveService`
     - `WcsHeaderService`

5. Keep `ImageProcessing` as a facade during migration.

### Recommended timing

- This is the best next project-wide target after the reporting layer is left alone.
- It is not the best immediate target if the current work remains report-focused.

## 4. `DetectionConfigurationPanel` (1044 lines)

### Current responsibility clusters

- Build settings tabs and rows
- Bind Swing widgets to `DetectionConfig`
- Load and save detection/visualization preferences
- Apply UI values into runtime export settings
- Handle compatibility logic for optional config fields
- Coordinate preview and auto-tune actions

### Main problems

- The class is both a large Swing form and the binding/persistence layer for that form.
- Compatibility logic and controller behavior are mixed directly into UI code.
- The panel still has too much knowledge of where runtime settings live.

### Refactor plan

1. Extract `DetectionConfigCompatibilityAdapter`
2. Extract `DetectionConfigBinder`
3. Extract `DetectionSettingsPersistenceService`
4. Extract `DetectionConfigConstraintController`
5. Split tab builders only after the binding layer is clearer

## 5. `TargetVisualizationSectionWriter` (953 lines)

### Current responsibility clusters

- Single-streak cards
- Confirmed streak-track cards
- Moving-target cards
- Anomaly cards
- Sky-orientation overlays and summary fragments
- Per-card image export orchestration

### Main problems

- It is now the largest single report section and contains several distinct presentation paths.
- It is still reasonable as a single class for now, but it is the first section writer that will become painful again when new report features land.

### Refactor plan

1. Extract per-target-type writers if this class starts changing again.
   - Suggested classes:
     - `SingleStreakSectionWriter`
     - `StreakTrackSectionWriter`
     - `MovingTargetSectionWriter`
     - `AnomalySectionWriter`

2. Extract sky-orientation overlay logic if it grows further.
   - Suggested class: `SkyOrientationOverlayRenderer`

### Recommendation

- Do not split this yet unless feature work is already touching it.
- Right now, `DetectionReportAstrometry` and `ImageProcessing` are higher-value targets.

## Recommended overall sequence

This is the order I would use for the next actual implementation rounds.

1. `DetectionReportAstrometry`
   - Best next target if we stay in the reporting layer
   - Still has the clearest responsibility split left to make

2. `ImageProcessing`
   - Highest overall LOC
   - Biggest non-report architectural payoff

3. `DetectionConfigurationPanel`
   - Worth doing after export/runtime settings boundaries are more stable

4. `MainApplicationPanel`
   - Best handled after service boundaries are clearer

5. `TargetVisualizationSectionWriter`
   - Only if report feature work resumes and this class starts growing again

## What not to do in the next round

- Do not keep refactoring `DetectionReportGenerator` just to reduce its line count further.
- Do not rename report output files or paths unless there is a concrete bug or compatibility reason.
- Do not mix astrometry-query strategy changes with UI work in the same pass.
- Do not split every section writer unless there is active feature pressure.

## Verification expectations for each refactor round

- `./gradlew.bat compileJava`
- `./gradlew.bat test`
- `./gradlew.bat realDataTest` for report-affecting changes
- Compare fresh real-data reports against the prior baseline bundle for each dataset
  - Only runtime-dependent `Processing Time` values should differ when behavior is unchanged
- Standard detection report export still works
- Iterative report export still works
- Existing report links and live-render actions still work
- Settings load/save still works
