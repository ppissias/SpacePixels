# JTransient Slow-Mover Algorithm Impact on SpacePixels

Sources reviewed: the current sibling `../JTransient` implementation and SpacePixels call sites.

This note maps the **implemented, currently uncommitted** JTransient slow-mover redesign onto SpacePixels. It describes the sibling JTransient working tree, not the published JAR. SpacePixels currently supplies configuration, calls `JTransientEngine`, summarizes `PipelineResult`, and renders report artifacts; its Java sources have not yet been migrated.

## Algorithm Change Summary

JTransient now uses the maximum stack instead of the percentile/deep-stack image:

- Sources extracted from `maximumStackData` provide candidate **raw-pixel footprints**.
- Sources extracted independently from the median stack provide an exact, undilated `medianMask`; overlap is the fraction of candidate raw pixels inside this mask.
- Filters run in order: minimum pixels, geometric axis-ratio window, optional fill factor, then minimum/maximum median-mask overlap. The defaults are axis ratio **1.35–3.20**, fill factor **0.0** (disabled), and median overlap **0.00–0.80**. The upper overlap bound rejects mostly stationary sources; a zero lower bound does not demand median-stack persistence.
- No percentile slow-mover stack, dynamic MAD elongation baseline, or residual-flux subtraction participates in acceptance.
- These are **morphology candidates**, not temporally confirmed or astrometrically measured slow movers. A one-frame elongated transient can still pass the current morphology filters.

## Main JTransient Interface Changes

### Configuration

| `DetectionConfig` field | Status and meaning |
| --- | --- |
| `slowMoverMinAxisRatio` | **New**, default `1.35`; minimum oriented-footprint `majorExtent / minorExtent`. |
| `slowMoverMaxAxisRatio` | **New**, default `3.20`; upper shape bound, beyond which the object is treated as too streak-like. |
| `slowMoverMinFillFactor` | **New**, default `0.0`; optional minimum connected-pixel count / oriented bounding-box area. |
| `slowMoverMedianSupportOverlapFraction` | Retained; minimum exact-mask overlap, default `0.00`. |
| `slowMoverMedianSupportMaxOverlapFraction` | Retained; maximum exact-mask overlap, default changed to `0.80`. |
| `masterSlowMoverSigmaMultiplier`, `masterSlowMoverGrowSigmaMultiplier`, `masterSlowMoverMinPixels` | Retained; used to extract objects from both maximum and median stacks. |
| `slowMoverStackMiddleFraction`, `slowMoverBaselineMadMultiplier`, `enableSlowMoverResidualFootprintFiltering`, `slowMoverResidualFootprintMinFluxFraction` | Still public and `@Deprecated`, but **ignored** by the new detector. |

`slowMoverMinElongationBaselineMadMultiplier` and `slowMoverMaxElongation` mentioned in the original proposal **were not added**. The primary gate is geometric `axisRatio`, **not** the existing moment-based `DetectedObject.elongation`.

### Stack, Candidate, and Result API

- `SlowMoverAnalyzer.analyze(List<ImageFrame>, short[][] medianStackData, DetectionConfig)` retains its signature but now builds a maximum stack. A new overload accepts a precomputed `short[][] maximumStackData`; `JTransientEngine` uses it so the maximum stack is built once from quality-filtered frames.
- `SlowMoverAnalysis` adds `maximumStackData` and `medianMask`. Its existing `slowMoverStackData` and `medianVetoMask` fields remain **unannotated compatibility aliases** of those new fields. `candidates` remains a list of `SlowMoverCandidateResult`.
- `PipelineResult.maximumStackData` remains the shared maximum stack. With slow-mover detection enabled, `PipelineResult.slowMoverStackData` aliases that same stack, `slowMoverMedianVetoMask` aliases the exact median mask, and `slowMoverCandidates` mirrors the accepted objects. The old `PipelineResult` constructor overload and `JTransientEngine.runPipeline(...)` signatures remain.
- `SourceExtractor.DetectedObject` adds `majorExtent`, `minorExtent`, `axisRatio`, and `fillFactor`; `SourceExtractor.measureBlobGeometry(...)` and `BlobGeometry` expose the measurement. `elongation` and `angle` retain their moment-based meanings.
- `SlowMoverCandidateDiagnostics.medianSupportOverlap` becomes `medianMaskOverlapFraction`; `footprintPixelCount` becomes `pixelCount`. New fields include `momentElongation`, `orientation`, `majorExtent`, `minorExtent`, `axisRatio`, `fillFactor`, `outsideMedianMaskFraction`, `estimatedMotionPixels`, `estimatedMotionDiameters`, and effective shape/mask thresholds. All residual-flux fields and the old seven-argument constructor are **removed**. Estimated motion is a shape proxy, not a measured track.
- `SlowMoverSummaryTelemetry` and `PipelineTelemetry.SlowMoverTelemetry` replace dynamic-elongation/residual metrics with `rejectedBelowMinPixels`, `rejectedBelowMinAxisRatio`, `rejectedAboveMaxAxisRatio`, `rejectedLowFillFactor`, `evaluatedAgainstMedianMask`, the retained low/high median-overlap counters, and `candidatesDetected`. New thresholds are `minAxisRatioThreshold`, `maxAxisRatioThreshold`, and `minFillFactorThreshold`; `avgCandidateAxisRatio`, `avgMedianMaskOverlap`, and motion averages are exported. `candidateAxisRatios`, `candidateMomentElongations`, `candidateFillFactors`, and `candidateMedianMaskOverlaps` expose distributions useful for tuning. `avgMedianMaskOverlap` covers **all mask-evaluated components**, including vetoed ones; `candidateMedianSupportOverlaps` covers accepted candidates.

The retained names `slowMoverStackData` and `medianVetoMask` are particularly risky: they **compile** but no longer refer to a distinct percentile stack or a dilated veto mask.

## Dependency Impact

SpacePixels currently declares:

```gradle
implementation 'io.github.ppissias.jtransient:jtransient:1.0.0'
```

The sibling JTransient repo declares `1.0.1`, but its working-tree changes are not yet published. Merely running SpacePixels against `1.0.0` will not exercise this implementation. The current SpacePixels `settings.gradle` offers a conditional composite build via `-PuseLocalJTransientBuild=true`; once the change is released, update the dependency to the **actual published version**.

The old telemetry and candidate-diagnostics fields **were removed**, so the report code below would fail to compile against the local JTransient sources. Deprecated config fields and stack aliases do not produce compile errors.

## SpacePixels Files Most Affected

### `src/main/java/eu/startales/spacepixels/gui/DetectionConfigurationPanel.java`

This is the main user-facing configuration change.

Current UI assumptions:

- The tab still describes "deep-stack slow-mover detection".
- `slowMoverStackMiddleFraction` and `slowMoverBaselineMadMultiplier` are presented as active settings, but JTransient ignores both.
- Residual-footprint filtering is presented as active through:
  - `enableSlowMoverResidualFootprintFiltering`
  - `slowMoverResidualFootprintMinFluxFraction`
- Median-support tooltips describe a slow-mover percentile-stack footprint rather than the maximum-stack candidate's raw pixels.

Required update:

- Keep the enable switch, the three `masterSlowMover*` extraction controls, and both median-overlap controls. Explain that the lower overlap may be zero and the upper bound is a stationary-source veto.
- Add controls for **`slowMoverMinAxisRatio`**, **`slowMoverMaxAxisRatio`**, and optional **`slowMoverMinFillFactor`**. Do not add the unimplemented proposal's elongation controls.
- Remove the inactive percentile-stack, MAD-baseline, and residual-footprint controls, or make them visibly legacy-only if old-profile editing is required. Never present them as changing current detection.
- Reword median-overlap tooltips to mean exact median-object raw-pixel mask overlap against maximum-stack candidate raw pixels.
- Update declarations, `applySettingsToMemory()`, `updateSpinnersFromConfig()`, `normalizeDependentSpinners()`, and tooltip text.
- Remove the optional-reflection residual-control access rather than preserving an ineffective UI. Direct field access is appropriate once the new JTransient dependency is selected.

### `src/main/java/eu/startales/spacepixels/util/DetectionPipelineSupport.java`

Current code mutates the effective config by clamping `slowMoverStackMiddleFraction` so a percentile slow-mover stack stays below the maximum stack. JTransient no longer reads that field.

Required update:

- Remove `clampSlowMoverStackMiddleFraction(...)` and `computeSlowMoverStackOrderIndex(...)`; the new JTransient detector no longer uses the percentile slow-mover stack.
- Stop mutating `effectiveConfig.slowMoverStackMiddleFraction` in `createEffectiveDetectionConfig(...)`.
- `suppressLatePhaseOutputsWhenTooFewFramesRemain(...)` does **not** require a constructor change now: both `PipelineResult` overloads and legacy fields remain. Review it only if those aliases are removed later.

### `src/main/java/eu/startales/spacepixels/util/StandardDetectionPipelineService.java`

No SpacePixels-side algorithm work is needed here: `runPipeline(...)` signatures remain stable, and it still passes `ImageFrame` data and `DetectionConfig` to JTransient.

Check after the JTransient update:

- Progress text returned from JTransient may mention maximum-stack slow-mover detection.
- `DetectionPipelineSupport.createEffectiveDetectionConfig(...)` should no longer apply legacy percentile-stack clamping.

### `src/main/java/eu/startales/spacepixels/util/IterativeDetectionPipelineService.java`

The iterative pipeline passes a provided median master stack into JTransient. The new maximum stack is built inside JTransient from each pass's quality-filtered frames, not passed as a new argument.

Check after the JTransient update:

- `runPipeline(spacedSubset, effectiveConfig, scaledListener, providedMasterStack)` still has the same meaning.
- No new maximum-stack parameter is required at this call site. Check only that each pass uses its own retained frames.

### `src/main/java/eu/startales/spacepixels/util/reporting/DetectionReportGenerator.java`

Current code normalizes slow-mover data from:

- `slowMoverAnalysis.slowMoverStackData`
- `slowMoverAnalysis.medianVetoMask`
- `result.slowMoverStackData`
- `result.slowMoverMedianVetoMask`
- legacy residual and dynamic-elongation telemetry fields in `hasMeaningfulSlowMoverTelemetry(...)` (**compile errors**)

Required update:

- Prefer `slowMoverAnalysis.maximumStackData` and `slowMoverAnalysis.medianMask`; fall back to `result.maximumStackData` and the retained mask alias only when needed.
- Update `hasMeaningfulSlowMoverTelemetry(...)` to use the **actual** size, axis-ratio, fill-factor, median-mask, and accepted-candidate counters. Do not look for a dynamic elongation baseline or residual-flux fields.
- Avoid reporting the same maximum-stack pixels twice as separate "maximum" and "slow-mover" products.

### `src/main/java/eu/startales/spacepixels/util/reporting/DetectionReportContext.java`

Current context names are legacy-oriented:

- `slowMoverStackData`
- `slowMoverMedianVetoMask`

Required update:

- Prefer the existing `maximumStackData` plus a `slowMoverMedianMask` field; remove the redundant `slowMoverStackData` context field when the report is migrated.
- Keep old names only for an explicitly supported legacy report path, not as independent image products.

### `src/main/java/eu/startales/spacepixels/util/reporting/DeepStackReportSectionWriter.java`

This report section has the largest semantic change and direct compile failures against the new candidate-diagnostics and summary-telemetry fields.

Current report assumptions:

- Section title is `Deep Stack Anomalies (Ultra-Slow Mover Candidates)`.
- Text says candidates are elongated objects in the master median stack.
- Primary crop is `Slow Mover Stack`, which now duplicates `Maximum Stack`.
- Difference image is `Slow Mover Stack - Median Stack`.
- Telemetry cards include dynamic elongation and residual-footprint metrics.
- Candidate cards print residual footprint flux fields from `SlowMoverCandidateDiagnostics`.

Required update:

- Rename the section to `Maximum-Stack Slow-Mover Candidates` or similar, and explain that these are unconfirmed shape candidates, **not** median-stack detections.
- Use the maximum-stack crop as the one primary candidate-envelope image; avoid duplicate maximum/slow-mover stack cards.
- Keep the median-stack crop and exact median-mask overlay.
- Remove the residual-footprint diff as an **acceptance** diagnostic. If kept, label it `Maximum Stack - Median Stack` and explicitly say it is inspection-only. Update `buildDeepStackMaskAndDiffExplanationHtml()`, which currently implies median support is generally required; the default lower overlap is zero.
- Replace removed cards with `rawCandidatesExtracted`, `rejectedBelowMinPixels`, `rejectedBelowMinAxisRatio`, `rejectedAboveMaxAxisRatio`, `rejectedLowFillFactor`, `evaluatedAgainstMedianMask`, low/high overlap rejects, `candidatesDetected`, and effective axis-ratio/overlap thresholds.
- Display candidate `axisRatio` (distinct from moment `elongation`), `fillFactor`, `pixelCount`, `medianMaskOverlapFraction`, `outsideMedianMaskFraction`, and optionally `estimatedMotionPixels`/`estimatedMotionDiameters` with a **shape-estimate** label.
- Remove all residual-footprint flux/filter cards. Replace `candidateDiagnostics.medianSupportOverlap` with `medianMaskOverlapFraction` and `footprintPixelCount` with `pixelCount`.

### Report Labels and Summary Text

These files do not necessarily need compile fixes, but their wording should be checked so reports do not describe the new branch as a percentile/deep-stack residual analysis:

- `src/main/java/eu/startales/spacepixels/tasks/DetectionTask.java`
- `src/main/java/eu/startales/spacepixels/tasks/IterativeDetectionTask.java`
- `src/main/java/eu/startales/spacepixels/util/reporting/GlobalMapsSectionWriter.java`
- `src/main/java/eu/startales/spacepixels/util/reporting/PipelineDiagnosticsSectionWriter.java`
- `src/main/java/eu/startales/spacepixels/util/reporting/CreativeTributeRenderer.java`
- `src/main/java/eu/startales/spacepixels/util/reporting/DetectionReportSummary.java`

Suggested wording change:

- Replace misleading user-facing "deep-stack anomalies" with "maximum-stack slow-mover candidates" where appropriate.
- Decide whether global-map `DS#` identifiers remain for report/bookmark compatibility or become `SM#`; this is a presentation choice, not a JTransient API requirement.

## Configuration Persistence

`SpacePixelsDetectionProfileIO` serializes the JTransient `DetectionConfig` directly with Gson.

Migration implications:

- Old saved profiles may contain the four deprecated fields listed above. While those fields remain on `DetectionConfig`, Gson continues to read and write them even though the detector ignores them.
- New profiles should contain `slowMoverMinAxisRatio`, `slowMoverMaxAxisRatio`, and `slowMoverMinFillFactor`; missing fields in old profiles should receive the JTransient `DetectionConfig` defaults. **Do not silently translate** an old MAD multiplier or stack fraction into an axis ratio: the thresholds measure different things.
- If JTransient later removes those fields, Gson's default behavior ignores unknown JSON properties on load and omits removed fields on write. Verify this with an old-profile JSON fixture and a round-trip test rather than assuming persisted behavior.

## Public API Impact

`SpacePixelsPipelineResult` exposes the raw JTransient `PipelineResult`. External callers can therefore observe both the new result semantics and removed diagnostics/telemetry members.

SpacePixels should document that:

- Accepted slow-mover candidates are maximum-stack **morphology** footprints, not confirmed moving objects.
- Median support is exact candidate-pixel / median-object-mask overlap, not object-to-object matching.
- `slowMoverStackData`, `slowMoverMedianVetoMask`, and `SlowMoverAnalysis.medianVetoMask` still exist but are compatibility names with changed semantics.

No high-level SpacePixels API signature change is needed because `JTransientEngine.runPipeline(...)` remains unchanged.

## Should the Deprecated API Be Removed?

**For a coordinated SpacePixels migration, yes, removing the four unused config fields and `MasterMapGenerator.createSlowMoverMasterStack(...)` from JTransient would be a reasonable fail-fast cleanup**—provided no other consumers require source/binary compatibility. It would surface direct stale reads/writes in `DetectionConfigurationPanel` and `DetectionPipelineSupport`. Do it as an intentional breaking API change, not merely to make the compiler noisy.

However, removal alone is **not enough**:

- The removed diagnostics and telemetry fields **will** produce compile errors in `DetectionReportGenerator` and `DeepStackReportSectionWriter` when compiled against the new JTransient sources.
- SpacePixels accesses the residual config fields by **reflection** in its UI, so deleting those JTransient fields would **not** produce compiler errors there. Remove the stale UI controls explicitly.
- The unannotated `slowMoverStackData`/`medianVetoMask` aliases continue to compile while changing meaning. Migrate their SpacePixels uses explicitly; if fail-fast discovery is the priority, consider removing these aliases and the old `PipelineResult` constructor in a separate coordinated breaking change.
- If JTransient is used outside SpacePixels, deleting public fields/methods breaks source and binary compatibility. Keep them for a deprecation window, or make the removal part of a versioned breaking release. `-Xlint:deprecation` can expose direct deprecated uses without deleting APIs, but it does not catch reflection or unannotated aliases.

**Recommended sequence:** first compile SpacePixels against the local JTransient sources and fix the already-broken diagnostics/reporting API; then decide whether to remove deprecated config/method/aliases before the final migration, based on whether backward compatibility is required. Either way, search and review the semantic aliases manually.

## Documentation Updates

Update these SpacePixels docs during migration:

- `README.md`
- `MANUAL.md`
- `HIGH_LEVEL_DESIGN.md`
- `API.md` if raw `PipelineResult` slow-mover fields are described there

Specific stale statements already present:

- `MANUAL.md` describes a percentile stack and optional positive residual-flux acceptance.
- `HIGH_LEVEL_DESIGN.md` mentions runtime slow-mover stack fraction clamping.
- `README.md` describes deep integrated stacks for ultra-slow movers.

## Test Impact

Existing SpacePixels tests to review:

- `src/test/java/eu/startales/spacepixels/util/ImageProcessingTest.java`
  - constructs `PipelineResult` and `PipelineTelemetry.SlowMoverTelemetry`; the retained constructor/fields mean these tests may still compile, but their semantics need checking
- `src/test/java/eu/startales/spacepixels/config/SpacePixelsDetectionProfileIOTest.java`
  - should gain an old-profile fixture and new-default/round-trip coverage
- Add report assertions for non-duplicated maximum-stack imagery, exact-mask labels, and new telemetry/cards when report test fixtures are available.

Recommended verification:

```powershell
.\gradlew -PuseLocalJTransientBuild=true compileJava
.\gradlew -PuseLocalJTransientBuild=true test
```

These commands use the conditional composite build in the current `settings.gradle`; without that flag SpacePixels still builds against published JTransient `1.0.0`. Then manually inspect at least one detection/export with slow-mover detection enabled. The migration has **not** been compiled or tested against the local JTransient in this documentation-only change.

## Suggested Migration Order

1. Use the local composite build (or a published version containing the implementation) and fix the existing removed diagnostics/telemetry compile errors.
2. Replace the SpacePixels GUI's inactive controls with axis-ratio/fill-factor controls; remove percentile fraction clamping and verify old-profile loading.
3. Normalize `SlowMoverAnalysis.maximumStackData`/`medianMask` in the report, and rewrite the deep-stack section around the new stage counters and candidate diagnostics.
4. Update report labels and public-facing docs; add profile and report tests.
5. Run the local-composite `compileJava` and `test` tasks, then manually inspect an HTML export.

## Follow-up: Frame Support and Stationary Likelihood Heuristics

JTransient now measures two additional 0–100 heuristics for each maximum-stack slow-mover candidate after the shape and median-mask gates. `DetectionConfig.slowMoverMinFrameSupport` defaults to `0.0`, so a candidate is rejected only when its measured frame-support percentage falls below a positive configured floor. `DetectionConfig.slowMoverMaxStationaryLikelihood` defaults to `100.0`, so a candidate is rejected only when its stationary-likelihood percentage exceeds a lower configured ceiling. Both measurements run with the defaults; neither new filter rejects candidates by default. The thresholds are clamped to 0–100 for use and reported as `minFrameSupportThreshold` and `maxStationaryLikelihoodThreshold`.

Frame support is the percentage of usable, quality-filtered original frames in which a local background-subtracted aperture finds significant localized signal somewhere inside the maximum-stack candidate footprint. The aperture uses a fixed integrated signal-to-noise floor of 5 and can follow the source within that footprint. Stationary likelihood is the largest percentage of supported frame positions clustered near one location, using the configured star-jitter scale. It is a heuristic score, **not a calibrated probability or motion confirmation**; genuine motion below the positional resolution can also score high. Frame support is unavailable with fewer than two usable frames; stationarity is unavailable when fewer than three supported frames span at least half the retained sequence. No three-stage stack is generated.

`SlowMoverCandidateDiagnostics` adds `frameSupportPercentage`, `stationaryLikelihoodPercentage`, `supportedFrameCount`, `usableFrameCount`, `frameSupportAvailable`, `stationaryLikelihoodAvailable`, `minFrameSupportThreshold`, and `maxStationaryLikelihoodThreshold`. SpacePixels should use each availability flag rather than interpreting its numeric percentage when the measurement is unavailable: frame support may have a computed percentage with only one usable frame, while `frameSupportAvailable` is `false`. The stack-only `SlowMoverAnalyzer.analyze(maximumStackData, medianStackData, config)` overload lacks original frames, so both measurements are unavailable and neither new gate vetoes its candidates. `JTransientEngine.runPipeline(...)` passes the retained frames and provides the measurements.

`SlowMoverSummaryTelemetry` and `PipelineTelemetry.SlowMoverTelemetry` add `evaluatedAgainstFrames`, `frameEvidenceUnavailable`, `rejectedLowFrameSupport`, `rejectedHighStationaryLikelihood`, both effective thresholds, and accepted-candidate lists `candidateFrameSupportPercentages`, `candidateStationaryLikelihoodPercentages`, `candidateFrameSupportAvailable`, and `candidateStationaryLikelihoodAvailable`. The lists are ordered like `slowMoverAnalysis.candidates`. `evaluatedAgainstFrames` and `frameEvidenceUnavailable` count candidates with measurable and unavailable frame support respectively; stationarity has its own per-candidate availability flag. SpacePixels can display the two percentages beside each candidate, label unavailable measurements explicitly, and add UI controls for the two new config thresholds when it migrates to this JTransient build. Existing saved profiles without the fields use the disabled defaults.
