# JTransient Slow-Mover Algorithm Impact on SpacePixels

Source reviewed: `../JTransient/SlowMoverAlgorithm.md`

This note maps the proposed JTransient slow-mover redesign onto this SpacePixels repository. The algorithm itself lives in JTransient; SpacePixels mainly supplies configuration, calls `JTransientEngine`, summarizes `PipelineResult`, and renders report artifacts.

## Algorithm Change Summary

JTransient's slow-mover branch is expected to move from a percentile/deep-stack candidate image to a maximum-stack envelope candidate image:

- `maximumStack` proposes the slow-mover candidate footprint.
- `medianStack` is used to build an exact object-footprint mask for stationary/common structure.
- Candidate acceptance uses a median-mask overlap band:
  - minimum: `slowMoverMedianSupportOverlapFraction`
  - maximum: `slowMoverMedianSupportMaxOverlapFraction`
- The old residual-footprint check from `slowMoverStack - medianStack` is retired for the first maximum-stack implementation.
- The old `slowMoverStackMiddleFraction` and strong `slowMoverBaselineMadMultiplier` controls become legacy-only unless JTransient keeps the old branch temporarily.
- New shape controls are expected:
  - `slowMoverMinElongationBaselineMadMultiplier`
  - `slowMoverMaxElongation`

## Dependency Impact

SpacePixels currently depends on:

```gradle
implementation 'io.github.ppissias.jtransient:jtransient:1.0.0'
```

The sibling JTransient repo currently declares version `1.0.1`. After the algorithm lands and is published or installed locally, `build.gradle` in SpacePixels must be updated to the JTransient version that contains the new slow-mover API.

If JTransient keeps compatibility aliases (`slowMoverStackData`, `medianVetoMask`, legacy telemetry fields), SpacePixels can migrate incrementally. If those fields are removed, the files below will fail compilation and must be updated in the same pass as the dependency bump.

## SpacePixels Files Most Affected

### `src/main/java/eu/startales/spacepixels/gui/DetectionConfigurationPanel.java`

This is the main SpacePixels change.

Current UI assumptions:

- The tab text describes "deep-stack slow-mover detection".
- `slowMoverStackMiddleFraction` is exposed as an active setting.
- `slowMoverBaselineMadMultiplier` is exposed as the main elongation gate.
- residual-footprint filtering is exposed through:
  - `enableSlowMoverResidualFootprintFiltering`
  - `slowMoverResidualFootprintMinFluxFraction`
- median support text describes overlap with a median-stack artifact mask for a slow-mover stack footprint.

Required update:

- Keep these controls:
  - enable slow-mover detection
  - `masterSlowMoverSigmaMultiplier`
  - `masterSlowMoverGrowSigmaMultiplier`
  - `masterSlowMoverMinPixels`
  - `slowMoverMedianSupportOverlapFraction`
  - `slowMoverMedianSupportMaxOverlapFraction`
- Add controls for:
  - `slowMoverMinElongationBaselineMadMultiplier`
  - `slowMoverMaxElongation`
- Reword median overlap tooltips so they clearly mean exact median-stack object-mask overlap measured against the maximum-stack candidate footprint.
- Hide, remove, or move to a legacy section:
  - `slowMoverStackMiddleFraction`
  - `slowMoverBaselineMadMultiplier`
  - residual-footprint filtering controls
- Update declarations, `applySettingsToMemory()`, `updateSpinnersFromConfig()`, `normalizeDependentSpinners()`, and tooltip text.
- Use optional reflection for new fields only if SpacePixels needs to run against both old and new JTransient jars during transition. Once the dependency is bumped definitively, direct field access is cleaner.

### `src/main/java/eu/startales/spacepixels/util/DetectionPipelineSupport.java`

Current code mutates the effective config by clamping `slowMoverStackMiddleFraction` so the percentile slow-mover stack stays below the maximum stack.

Required update:

- Remove `clampSlowMoverStackMiddleFraction(...)` and `computeSlowMoverStackOrderIndex(...)` if JTransient no longer uses the percentile slow-mover stack.
- Stop mutating `effectiveConfig.slowMoverStackMiddleFraction` in `createEffectiveDetectionConfig(...)`.
- Update `suppressLatePhaseOutputsWhenTooFewFramesRemain(...)` if the `PipelineResult` constructor or slow-mover compatibility fields change.

### `src/main/java/eu/startales/spacepixels/util/StandardDetectionPipelineService.java`

No SpacePixels-side algorithm work is expected here if JTransient keeps `runPipeline(...)` signatures stable. It should continue to pass `ImageFrame` data and `DetectionConfig` to JTransient.

Check after the JTransient update:

- Progress text returned from JTransient may mention maximum-stack slow-mover detection.
- `DetectionPipelineSupport.createEffectiveDetectionConfig(...)` should no longer apply legacy percentile-stack clamping.

### `src/main/java/eu/startales/spacepixels/util/IterativeDetectionPipelineService.java`

The iterative pipeline passes a provided median master stack into JTransient. The new maximum-stack slow-mover image should be generated inside JTransient from each pass's clean frames, not from the global median master.

Check after the JTransient update:

- `runPipeline(spacedSubset, effectiveConfig, scaledListener, providedMasterStack)` still has the same meaning.
- If JTransient adds a maximum-stack parameter, update this call site and make sure the maximum stack is pass-local, not the global master-stack sample.

### `src/main/java/eu/startales/spacepixels/util/reporting/DetectionReportGenerator.java`

Current code normalizes slow-mover data from:

- `slowMoverAnalysis.slowMoverStackData`
- `slowMoverAnalysis.medianVetoMask`
- `result.slowMoverStackData`
- `result.slowMoverMedianVetoMask`
- legacy residual and dynamic-elongation telemetry fields

Required update:

- Prefer new `SlowMoverAnalysis` fields if JTransient adds them, likely `maximumStackData` and `medianMask`.
- Treat old `slowMoverStackData`/`medianVetoMask` as compatibility aliases only.
- Update `hasMeaningfulSlowMoverTelemetry(...)` for new fields:
  - minimum elongation threshold
  - maximum elongation threshold
  - rejected-below-min-elongation count
  - rejected-above-max-elongation count
  - baseline source, if exposed
  - median-mask overlap averages and thresholds
- Remove residual-footprint telemetry checks when the old branch is gone.

### `src/main/java/eu/startales/spacepixels/util/reporting/DetectionReportContext.java`

Current context names are legacy-oriented:

- `slowMoverStackData`
- `slowMoverMedianVetoMask`

Required update:

- Rename or add context fields for the new products:
  - `slowMoverMaximumStackData`
  - `slowMoverMedianMask`
- Keep old names only if needed for compatibility with an old JTransient jar or a retained legacy report path.

### `src/main/java/eu/startales/spacepixels/util/reporting/DeepStackReportSectionWriter.java`

This report section has the largest semantic change.

Current report assumptions:

- Section title is `Deep Stack Anomalies (Ultra-Slow Mover Candidates)`.
- Text says candidates are objects in the master median stack or deep stack.
- Primary crop is `Slow Mover Stack`.
- Difference image is `Slow Mover Stack - Median Stack`.
- Telemetry cards include dynamic elongation and residual-footprint metrics.
- Candidate cards print residual footprint flux fields from `SlowMoverCandidateDiagnostics`.

Required update:

- Rename the section to something like `Maximum-Stack Slow-Mover Candidates`.
- Make the maximum-stack crop the primary candidate-envelope image.
- Keep the median-stack crop and exact median-mask overlay.
- Remove the residual-footprint diff as an acceptance diagnostic. If a diff image remains useful, relabel it as inspection-only, for example `Maximum Stack - Median Stack`.
- Update telemetry cards to show the new shape window:
  - raw maximum-stack candidates
  - below minimum elongation
  - above maximum elongation
  - median-mask stage
  - rejected low median-mask overlap
  - rejected high median-mask overlap
  - final candidates
- Update candidate stats to show:
  - median-mask overlap
  - outside-median-mask fraction
  - footprint pixels
  - candidate elongation and pixel area
  - min/max elongation thresholds if exported
- Remove direct use of residual diagnostics:
  - `residualFootprintFluxFraction`
  - `residualFootprintFlux`
  - `slowMoverFootprintFlux`
  - `medianFootprintFlux`
  - `residualFootprintFilteringEnabled`

### Report Labels and Summary Text

These files do not necessarily need compile fixes, but their wording should be updated so reports do not describe the new branch as a percentile/deep-stack residual analysis:

- `src/main/java/eu/startales/spacepixels/tasks/DetectionTask.java`
- `src/main/java/eu/startales/spacepixels/tasks/IterativeDetectionTask.java`
- `src/main/java/eu/startales/spacepixels/util/reporting/GlobalMapsSectionWriter.java`
- `src/main/java/eu/startales/spacepixels/util/reporting/PipelineDiagnosticsSectionWriter.java`
- `src/main/java/eu/startales/spacepixels/util/reporting/CreativeTributeRenderer.java`
- `src/main/java/eu/startales/spacepixels/util/reporting/DetectionReportSummary.java`

Suggested wording change:

- Replace user-facing "deep-stack candidates" with "maximum-stack slow-mover candidates" or simply "slow-mover candidates".
- Decide whether global-map labels should remain `DS#` for compatibility or become `SM#`.

## Configuration Persistence

`SpacePixelsDetectionProfileIO` serializes the JTransient `DetectionConfig` directly.

Migration implications:

- Old saved profiles may contain retired fields:
  - `slowMoverStackMiddleFraction`
  - `slowMoverBaselineMadMultiplier`
  - `enableSlowMoverResidualFootprintFiltering`
  - `slowMoverResidualFootprintMinFluxFraction`
- New profiles should include:
  - `slowMoverMinElongationBaselineMadMultiplier`
  - `slowMoverMaxElongation`
- If JTransient removes old fields, Gson should ignore unknown JSON fields on load, but the UI must stop writing them once the controls are removed.
- Add a profile IO test that loads old slow-mover JSON and verifies the new defaults are available after deserialization.

## Public API Impact

`SpacePixelsPipelineResult` exposes the raw JTransient `PipelineResult`. Any external caller reading slow-mover fields from that object will see JTransient's API change directly.

SpacePixels should document that:

- accepted slow-mover candidates are now maximum-stack candidate envelopes
- median support values mean exact median-mask overlap, not object-to-object matching
- old `slowMoverStackData` and `slowMoverMedianVetoMask` are compatibility names if they still exist

No change is expected to the high-level SpacePixels API method signatures unless JTransient changes `JTransientEngine.runPipeline(...)`.

## Documentation Updates

Update these docs after implementation:

- `README.md`
- `MANUAL.md`
- `HIGH_LEVEL_DESIGN.md`
- `API.md` if raw `PipelineResult` slow-mover fields are described there

Specific stale statements already present:

- `MANUAL.md` describes the slow-mover branch as a specialized stack from the upper end of a middle band of sorted pixel values.
- `MANUAL.md` describes optional positive residual flux in `slowMoverStack - medianStack`.
- `HIGH_LEVEL_DESIGN.md` mentions runtime slow-mover stack fraction clamping.
- `README.md` describes deep integrated stacks for ultra-slow movers.

## Test Impact

Existing SpacePixels tests likely affected:

- `src/test/java/eu/startales/spacepixels/util/ImageProcessingTest.java`
  - constructs `PipelineResult`
  - checks `slowMoverStackData`
  - checks `slowMoverMedianVetoMask`
  - creates `PipelineTelemetry.SlowMoverTelemetry`
- `src/test/java/eu/startales/spacepixels/config/SpacePixelsDetectionProfileIOTest.java`
  - should gain old-to-new slow-mover profile migration coverage
- report tests, if added later, should assert the new report labels and telemetry fields

Recommended verification:

```powershell
.\gradlew test
```

Then run at least one manual detection/export on a sequence with slow-mover detection enabled to inspect the generated HTML crops and labels.

## Suggested Migration Order

1. Update the JTransient dependency to the version containing the new slow-mover API.
2. Fix compile breaks around `DetectionConfig`, `SlowMoverAnalysis`, `SlowMoverSummaryTelemetry`, `SlowMoverCandidateDiagnostics`, and `PipelineResult`.
3. Update `DetectionConfigurationPanel` controls and profile persistence behavior.
4. Remove SpacePixels' percentile-stack fraction clamping from `DetectionPipelineSupport`.
5. Update report data normalization in `DetectionReportGenerator` and `DetectionReportContext`.
6. Rewrite `DeepStackReportSectionWriter` around maximum-stack envelopes and median-mask overlap.
7. Update report labels, safety-prompt wording, README, manual, and high-level design docs.
8. Update tests and run `.\gradlew test`.

