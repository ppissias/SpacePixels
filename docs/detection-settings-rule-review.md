# Detection settings: field and dependency review

Reviewed 2026-10-21 against SpacePixels `4d8da29` and the adjacent JTransient checkout `6c3f34d` (declared version `1.0.5`, also pinned by SpacePixels). This is a source review, not a real-data experiment or verification of a published binary. No numerical rules, defaults or processing behaviour are changed by this review.

This document records evidence and recommendations for [D10 / shared settings rules](../DESIGN-IMPROVEMENTS.md#d10-describe-settings-and-engine-compatibility-once-with-typed-boundaries). That plan remains the work queue; the recommendations here are proposed, not accepted implementation decisions.

## Scope and interpretation

All **107 instance fields** of JTransient's [DetectionConfig](../../JTransient/src/main/java/io/github/ppissias/jtransient/config/DetectionConfig.java) were inventoried: 75 doubles, 18 integers and 14 booleans. Their consumers were traced through extraction, master preparation, frame quality, tracking, slow movers, residual analysis and photometry. The inventory includes hidden settings. The additional application-owned settings exposed by the panel are covered separately below.

The existing [DetectionSettingsRules](../src/main/java/eu/startales/spacepixels/config/DetectionSettingsRules.java) checks finite floating-point values and six ordered pairs. The panel binds pairs to spinners, performs alignment, explains adjustments, and checks the configuration before saving or starting work. Profile loading and the API do not yet apply that class.

Four different kinds of relationship need different treatment:

| Kind | Appropriate response |
|---|---|
| Impossible configuration for an active operation | Structured validation issue identifying the affected stage and fields. An optional stage becoming unavailable need not prevent unrelated analyses. |
| Intended consistency or conservatism policy | Explain the policy; align only through an explicit, documented caller policy. Preserve previously accepted profiles until rejection changes are agreed. |
| Redundant threshold or reduced capability | Information or an actionable warning, without changing scientific settings. Avoid permanent warnings for harmless default combinations. |
| Dependence on actual frames, stars, noise or timestamps | Readiness/diagnostics using the prepared dataset, rather than a fabricated configuration-only inequality. |

The scalar-domain observations in the inventory are candidates for shared metadata, not a specification that every value outside them must now be rejected. Existing fallbacks and special values take precedence until deliberately changed. Widget bounds such as sigma 20 or velocity tolerance 1 are not automatically engine limits.

## Findings on relationships

### Per-frame sigma and median-master sigma: keep independent

Do **not** introduce `masterSigmaMultiplier <= detectionSigmaMultiplier`, or a numerical ordering between master sigma and per-frame grow sigma.

[SourceExtractor](../../JTransient/src/main/java/io/github/ppissias/jtransient/core/SourceExtractor.java#L229) estimates the background separately on each image and forms thresholds as `backgroundMedian + measuredSigma * multiplier`. [MasterReferenceAnalyzer](../../JTransient/src/main/java/io/github/ppissias/jtransient/core/MasterReferenceAnalyzer.java#L99) extracts on the median stack, also adding a separate local-background pass. Consequently the multipliers do not share a noise scale.

For a hypothetical frame with measured noise 10 ADU, a multiplier of 3 seeds at 30 ADU above its background. A median stack with measured noise 2 ADU and a multiplier of 5 seeds at 10 ADU above its background. The larger master multiplier can therefore still produce the lower absolute signal threshold. This numerical example explains why multiplier ordering alone is insufficient; it does not establish mask coverage for a real dataset.

Footprint coverage also depends on master grow, minimum pixels, per-frame grow, seeing and mask dilation. Background gradients and the local master pass make a simple inequality still less representative. [CalibratedAutoTuner](../../JTransient/src/main/java/io/github/ppissias/jtransient/engine/CalibratedAutoTuner.java#L35) already searches the frame and master settings separately and measures star leakage, false positives, masked area and recovery of injected sources. A fixed cross-image ordering would exclude combinations this measurement-based search deliberately considers.

The appropriate feedback is measured leakage/coverage and recovery, not a warning triggered solely by `masterSigmaMultiplier > detectionSigmaMultiplier`. The same reasoning applies to linking master and per-frame minimum-pixel settings, or slow-mover stack thresholds to single-frame thresholds.

### Quality extraction: one missing grow/seed consistency policy

`qualityGrowSigmaMultiplier <= qualitySigmaMultiplier` is the clearest additional pair to consider. Both values act on the same image and noise estimate. [FrameQualityAnalyzer](../../JTransient/src/main/java/io/github/ppissias/jtransient/quality/FrameQualityAnalyzer.java#L83) clones the configuration, installs the quality grow threshold and calls the same hysteresis extractor. A grow threshold above the seed is contrary to its documented lower-threshold purpose and can split the faint parts of quality stars instead of growing them.

This is a consistency policy rather than a claim that the current extractor cannot execute those inputs. A compatibility detail matters: [FrameQualityAnalyzerTest](../../JTransient/src/test/java/io/github/ppissias/jtransient/quality/FrameQualityAnalyzerTest.java#L23) explicitly exercises seed 5 and quality grow 8 to show independence from detection-side grow. A new blanket rejection or automatic clamp would change accepted behaviour and that test's premise.

Recommendation: propose the pair in the shared engine rules, initially diagnose it, and agree the alignment/rejection policy before enforcing it. If alignment is adopted, lowering quality grow follows the direction of the existing grow/seed pairs. The field remains hidden in the GUI; adding validation is not a reason to expose a new control. Do not require quality sigma to be above detection sigma: they serve independent extraction purposes.

### Geometric point linking: the minimum jump must not exceed the maximum

`maxStarJitter > maxJumpPixels` makes every geometric baseline impossible: [TrackLinker](../../JTransient/src/main/java/io/github/ppissias/jtransient/core/TrackLinker.java#L830) rejects distances below the former and above the latter.

Recommendation: report a geometric-point-linking configuration conflict when this branch will run. The branch is selected by `enableGeometricTrackLinking || !hasTimestamps`, so the checkbox alone is insufficient to decide applicability. The timed point linker does not apply `maxJumpPixels`; an inconsistency in that inactive geometric branch should not block it. Do not automatically shrink the measured jitter or increase the maximum jump without an agreed policy.

Equality is an unusually narrow setting, not established here as an impossible one: initial comparisons accept equality, and later pruning has different strictness. Keep the proposed contradiction check as `>` rather than inventing a strict `<` requirement.

### Photometry star cap: a configuration can guarantee unavailability

When variable-star analysis is enabled, a positive `photometryMaxStars` smaller than `linearityMinStars` guarantees that the selected-star readiness check fails. [VariableStarAnalyzer](../../JTransient/src/main/java/io/github/ppissias/jtransient/photometry/VariableStarAnalyzer.java#L176) performs that check after capped selection.

There is also an engine-owned minimum of 10 usable stars per solved frame in [EnsembleSolver](../../JTransient/src/main/java/io/github/ppissias/jtransient/photometry/EnsembleSolver.java#L25). Thus a positive cap below `max(linearityMinStars, 10)` prevents either readiness or ensemble solving. Passing this necessary check does not guarantee success: isolation, saturation, SNR and frame flags can reduce the usable set further.

Recommendation: explain this conflict before a run, identifying variable-star analysis as unavailable. Do not silently increase the cap or lower the scientific readiness requirement. Zero means uncapped and must bypass the cap comparison; the current selector also treats negative values as uncapped, which needs an explicit compatibility decision before rejecting them. Disabling variable-star analysis leaves these preferences intact and makes this conflict inapplicable to unrelated analyses.

`photometryFitPlane` does not imply a hard requirement of 30 configured stars. The solver deliberately falls back to a zero-point-only solution below its 30-star plane-fit minimum. Report the actual fit capability rather than changing the user's switch.

### Photometry radii: express the effective geometry consistently

The intended geometry is positive apertures, a sky annulus outside all measured apertures, and a non-empty annulus. This concerns `photometryApertureFwhmFactor`, `photometryAnnulusInnerFwhmFactor` and `photometryAnnulusOuterFwhmFactor`.

The current engine already resolves safe measurement radii in [ApertureMeasurer](../../JTransient/src/main/java/io/github/ppissias/jtransient/photometry/ApertureMeasurer.java#L124): it measures fixed comparison apertures as large as 2.5 FWHM as well as the configurable main aperture, places the inner annulus at least one pixel beyond the largest aperture, and gives the annulus at least two pixels of width. Therefore a simple factor-only rule `aperture < inner < outer` is incomplete, and rejecting crossed factors would change inputs that measurement currently repairs.

[PhotometryStarSelector](../../JTransient/src/main/java/io/github/ppissias/jtransient/photometry/PhotometryStarSelector.java#L99) and crossing/edge checks in `VariableStarAnalyzer` also use configured factors directly. This exposes an inconsistency risk when the measured radii have been expanded; this review does not claim a reproduced photometry defect.

Recommendation: resolve effective geometry through one engine policy, apply it consistently to the relevant preparation/measurement checks, and report adjustments. Then decide which positive configured-factor relationships merit warnings or explicit alignment. Effective radii also depend on measured FWHM and pixel margins; preserve that runtime resolution rather than persisting arbitrary rewritten factors.

### Limited-readiness amplitude: a useful scientific policy, not a mathematical necessity

`variableLimitedMinAmplitudeMag >= variableMinAmplitudeMag` expresses the intended conservative policy: less trustworthy photometry should not accept smaller amplitudes. [VariabilityScorer](../../JTransient/src/main/java/io/github/ppissias/jtransient/photometry/VariabilityScorer.java#L279) switches directly between these values, so reversing them really makes the limited mode less strict for this gate.

Recommendation: warn on the inversion and propose the monotonic policy. An eventual explicit alignment could raise the limited threshold. Do not equate this with a proven numerical precondition or change existing profiles silently. Keep both independent of `variableAmplitudeNoiseFactor`: an absolute magnitude-amplitude floor and a multiple of measured noise are different gates.

### Anomaly rescue and size thresholds: report redundancy, not invalidity

[TrackLinker.classifyAnomalyRescue](../../JTransient/src/main/java/io/github/ppissias/jtransient/core/TrackLinker.java#L1197) first applies `anomalyMinPixels`, then accepts the peak-sigma path, then tests the integrated-sigma path.

- If `anomalyMinPeakSigmaFloor >= anomalyMinPeakSigma`, the integrated path cannot accept anything that the peak path has not already accepted. This is a useful warning when the user expects diffuse rescue, not a reason to alter either threshold.
- If `anomalyMinIntegratedPixels < anomalyMinPixels`, its own size threshold is dominated by the common minimum. Equality is meaningful and should be allowed.
- If `streakMinPixels` or `anomalyMinPixels` is below `minDetectionPixels`, the earlier extraction minimum dominates. A lower downstream threshold is redundant, not contradictory.
- For ordinary extracted sources, peak sigma already exceeds the seed multiplier. A downstream peak threshold below the seed adds no extra rejection. This includes `singleStreakMinPeakSigma` and can include the anomaly peak floor. Such combinations can be intentional across profiles; the default anomaly floor is already below the default detection seed.

Use contextual explanations or an effective-threshold readout rather than noisy default warnings. Never compare integrated sigma with peak sigma as if they measured the same quantity.

### Counts and durations: use the actual dataset

Do not add `variableMinPersistenceFrames <= variableMinFrames`. The latter is a minimum amount of usable data, not the maximum permitted light-curve length. A profile requiring at least 20 measurements and persistence of 30 can be useful on a 100-frame dataset. When actual usable measurements are fewer than the persistence requirement, explain that the persistence gate cannot pass. In the current scorer this fails one gate, so a candidate may still be classed Possible; do not describe it as disabling all candidate output.

Likewise, compare `variableMinFrames`, `variableMinSpanMinutes`, `localActivityClusterMinFrames` and required track points with the appropriate actual retained frames, timestamps or per-star measurements. Do not rewrite reusable profiles to fit the dataset currently open. `minFramesForAnalysis` independently controls whether session outlier rejection has sufficient data; it need not be ordered against the photometry or tracking requirements.

`variableNoiseModelNeighbors` need not be below a configured star cap. [PhotometryMath.neighbourStats](../../JTransient/src/main/java/io/github/ppissias/jtransient/photometry/PhotometryMath.java#L225) already uses the smaller actual available set, excluding the current star. A non-positive count produces no useful comparison model (and a negative array length is possible for negative values); this is a scalar-domain concern, not a cross-field ordering.

### Feature switches: applicability without forced rewriting

The following are real activation dependencies. Preserve child preferences when their parent is off, and validate a field when an active consumer actually reads it:

- `enableSlowMoverDetection` controls the slow-mover extraction, shape and evidence settings.
- `enableAnomalyRescue` controls anomaly rescue thresholds.
- `enableFaintStreakFragments` controls `faintStreakLineTolerance`.
- `enableResidualTransientAnalysis` controls local rescue and local activity analysis. Local clustering's radius/count apply when its own switch is also on.
- `enableCommonMotionRejection` and its shift tolerance are independent of the residual-analysis switch. Camera-fixed rejection is independently enabled and requires usable registration evidence.
- `enableVariableStarDetection` controls photometry/readiness/variability settings. The selected operation can still omit that stage, as the existing iterative path does.
- `enableBrightStarEccentricityFilter` controls rejection by the bright-star metric, but the metric is still measured with the quality-star settings when that switch is off.
- Timestamp and registration availability determine whether several timed/registration checks can run. `strictExposureKinematics` requires usable timing/exposure information.

Do not treat every setting under a visually hidden section as inactive. Rhythm consistency also participates in streak checks; geometric linking is forced when timestamps are unavailable.

### Reconsider the existing rhythm/jump pair before extending it

The current pair `rhythmStationaryThreshold <= maxJumpPixels` is a GUI policy, not a universal engine invariant. It should receive a separate compatibility review:

1. `maxJumpPixels` bounds raw links in the geometric point branch, while rhythm settings also participate in multi-frame streak checks without that bound.
2. Even geometric tracks are pruned before the rhythm check. Removing intermediate points can make a retained jump exceed the raw-link maximum.

A source-derived example of the second point uses collinear positions `0, 0.8, 1.3, 2.3`, maximum jump `1` and jitter `0.6`. Raw consecutive jumps are `0.8, 0.5, 1.0`. The pruning code keeps `0, 0.8, 2.3`, whose upper-median jump is `1.5`; a stationary threshold of `1.2` can pass that check even though it is above the raw-link maximum. This illustrates the control-flow issue, not a completed end-to-end regression test.

Recommendation: do not remove or alter the existing rule in this analysis. Before migration, test whether it should be retained as an advisory policy rather than a global rejection/alignment rule. [Pruning and rhythm](../../JTransient/src/main/java/io/github/ppissias/jtransient/core/TrackLinker.java#L894), [streak motion checks](../../JTransient/src/main/java/io/github/ppissias/jtransient/core/TrackLinker.java#L1558).

### Scalar domains: several useful checks are not dependencies

Shared metadata should distinguish true fractions/correlations from unrelated quantities that merely happen to have similar numeric defaults. Examples:

- Overlap fractions and consistency fractions have a meaningful 0–1 range; frame support and stationary likelihood are percentages, 0–100.
- Split-half correlation is signed, -1–1; the configured absolute systematics/local correlation limits are 0–1.
- `maxFwhmRatio` and `maxSurfaceBrightnessRatio` are disabled at zero (currently also for negative values). Positive values below 1 reject every ordinary positive-profile pair because the computed larger/smaller ratio is at least 1. Diagnose that ineffective setting while preserving the disabling convention.
- `variableMaxApertureAmplitudeDifference` is a relative difference and can exceed 1. Velocity tolerances are relative allowances and can also exceed 1; the GUI's upper bound is not a physical validity limit.
- Elongation and the stored quality metric called eccentricity are length ratios, not geometric eccentricities constrained to 0–1. In particular a FWHM elongation cutoff of 1 admits no object under the current strict `<` comparison.
- `bgClippingIterations <= 0` leaves the extractor's background median and sigma at their initial zero values. This deserves a scalar diagnostic; ordering the clipping factor against detection sigma would not solve it.
- `zeroSigmaFallback` must provide a positive scale if it is to prevent a zero MAD sigma. `errorFallbackValue` also acts as an upper FWHM validity sentinel in photometry; there is no universal pair inequality that guarantees it is larger than all real measurements.
- Distinct levels cannot exceed 65,536 for the engine's unsigned-16-bit encoding. A higher `linearityMinDistinctLevels` guarantees failure of that readiness check, rather than expressing a relationship to another configurable threshold.

These checks require the same enabled-operation and compatibility decisions as paired rules. Keep documented automatic values such as master-grow zero, uncapped photometry and track-length fallbacks intact.

## Complete DetectionConfig field inventory

Each instance field appears once in these tables. "Existing" refers to current SpacePixels pair handling, not a claim of uniform API/engine enforcement. Scalar observations are proposed diagnostics/metadata, subject to the compatibility qualifications above. A field with no proposed pair was still checked against its consumers.

### Extraction and streaks (11 fields)

| Field | Relationship assessment |
|---|---|
| `bgClippingIterations` | Positive iteration count needed for a measured background; no ordering with detection thresholds. |
| `bgClippingFactor` | Clipping policy, ordinarily positive; independent of detection/grow sigma. With only one iteration it does not influence a subsequent estimate. |
| `detectionSigmaMultiplier` | Existing grow/seed pair; independent of master, quality and slow-mover multipliers. |
| `growSigmaMultiplier` | Existing `grow <= detection`; influences how much single-frame star-wing emission reaches the veto. |
| `minDetectionPixels` | Positive footprint count; dominates smaller downstream size thresholds without making them invalid. |
| `streakMinElongation` | Shape classification threshold; no ordering against slow-mover axis ratio, which is a different geometry measurement. |
| `streakMinPixels` | Positive count; a value below extraction minimum is redundant, not invalid. |
| `singleStreakMinPeakSigma` | Additional single-frame streak gate; below extraction seed it adds no peak rejection. No forced pair. |
| `enableBinaryStarLikeStreakShapeVeto` | Independent streak-veto policy; does not require enabling fragment assembly or anomaly rescue. |
| `enableFaintStreakFragments` | Activates fragment assembly; independent of ordinary streak classification thresholds. |
| `faintStreakLineTolerance` | Nonnegative line distance; applies to fragment assembly. No required ordering with prediction tolerance or jitter. |

### Master reference and slow movers (14 fields)

| Field | Relationship assessment |
|---|---|
| `masterSigmaMultiplier` | Existing master-grow pair; do not order against per-frame sigma/grow. |
| `masterMinDetectionPixels` | Positive count on a different image; no forced ordering with frame or quality minimum pixels. |
| `masterGrowSigmaMultiplier` | Existing `grow <= master seed`; zero means seed, and the engine currently also resolves non-positive values to seed. |
| `enableSlowMoverDetection` | Parent switch for slow-mover settings; do not clear child preferences when off. |
| `masterSlowMoverMinPixels` | Positive count for both maximum and median extraction; independent of ordinary master/frame minima. |
| `masterSlowMoverSigmaMultiplier` | Existing slow-mover grow/seed pair; independent of single-frame and master-star seeds. |
| `masterSlowMoverGrowSigmaMultiplier` | Existing `slow grow <= slow seed`; no required equality with ordinary grow. |
| `slowMoverMinAxisRatio` | Existing min/max pair; geometric ratio is at least 1. Engine already clamps its effective minimum. |
| `slowMoverMaxAxisRatio` | Existing max >= min; no pair with moment-based streak elongation. |
| `slowMoverMinFillFactor` | Shape-density threshold; ordinary fraction range. Zero disables this gate; no relation to overlap fractions. |
| `slowMoverMedianSupportOverlapFraction` | Existing min/max overlap pair; fraction, zero allows no median support. |
| `slowMoverMedianSupportMaxOverlapFraction` | Existing max >= min overlap; one disables the upper gate. Independent of ordinary master-mask overlap. |
| `slowMoverMinFrameSupport` | Percentage 0–100; zero disables. Runtime denominator is the usable evidence frames, not a configured frame-count minimum. |
| `slowMoverMaxStationaryLikelihood` | Percentage 0–100; 100 disables. Different statistic from support percentage; do not order them. |

### Quality measurements and floors (11 fields)

| Field | Relationship assessment |
|---|---|
| `qualitySigmaMultiplier` | Proposed quality grow/seed consistency policy; no order against detection sigma. |
| `qualityGrowSigmaMultiplier` | Proposed `quality grow <= quality seed`, with explicit compatibility handling. |
| `qualityMinDetectionPixels` | Independent positive count for quality stars; no order against frame/master minimum pixels. |
| `qualityBrightStarPeakSigmaOffset` | An offset, not an absolute sigma; effective bright threshold is already at least the quality seed through `max`. |
| `qualityBrightStarMinStars` | Positive evidence count; compare with measured bright stars, not configured photometry counts. |
| `qualityMaxElongationForFwhm` | Must exceed 1 to admit ordinary shapes under strict `<`; independent of photometry shape selection. |
| `errorFallbackValue` | Missing-quality numeric sentinel and photometry FWHM upper sentinel; diagnose unsuitable values, not via a fixed pair. |
| `minBackgroundDeviationADU` | Nonnegative absolute floor combined with measured noise and MAD; no comparison with sigma multipliers. |
| `minEccentricityEnvelope` | Nonnegative floor on an elongation difference; not a 0–1 eccentricity or a seed threshold. |
| `minBrightStarEccentricityEnvelope` | Independent nonnegative floor for a different star sample; no required order against the ordinary envelope. |
| `minFwhmEnvelope` | Nonnegative absolute FWHM tolerance; combines with measured MAD, not a configuration-only pair. |

### Tracking and mask settings (16 fields)

| Field | Relationship assessment |
|---|---|
| `maxStarJitter` | Nonnegative distance; proposed conflict when greater than max jump in an active geometric point branch. Also used by masking, slow-mover evidence and photometry crossings. |
| `maxMaskOverlapFraction` | Fraction 0–1; one accepts all mask overlaps. Independent of slow-mover median support. |
| `maxJumpPixels` | Geometric raw-link cap; not a timed-link cap or a universal cap on retained/pruned steps. |
| `predictionTolerance` | Nonnegative residual distance, not speed or total displacement; no necessary pair with jitter/max jump. |
| `angleToleranceDegrees` | Angular allowance; ordinary useful range 0–180 degrees. Independent of linear-distance tolerances. |
| `timeBasedVelocityTolerance` | Nonnegative relative allowance for timed points; no mathematical maximum of 1 or order against streak tolerance. |
| `streakTimeConsistencyTolerance` | Nonnegative relative allowance for timed streaks; current engine clamps negatives to zero. Independent of point tolerance. |
| `strictExposureKinematics` | Policy requiring usable timestamps/exposure evidence; does not require the optional geometric linker. |
| `enableGeometricTrackLinking` | Enables an extra branch with timestamps; absence of timestamps forces the branch regardless of this flag. |
| `trackMinFrameRatio` | Runtime count formula; non-positive values deliberately use the default. Do not remove fallback via a new positivity rule. |
| `absoluteMaxPointsRequired` | Effective requirement is at least 3 even when this cap is smaller. Explain effective value; preserve tested fallback. |
| `maxFwhmRatio` | Zero/non-positive currently disables; positive values below 1 make ordinary profile matching impossible. |
| `maxSurfaceBrightnessRatio` | Same disabled/positive-ratio distinction; no order against FWHM ratio, a different measurement. |
| `rhythmStationaryThreshold` | Existing max-jump pair needs review; used by geometric point and untimed streak checks. |
| `rhythmAllowedVariance` | Nonnegative jump deviation; no required ordering with stationary threshold or prediction tolerance. |
| `rhythmMinConsistencyRatio` | Fraction 0–1; also used for timed streak speed consistency even when geometric point linking is off. |

### Session rejection (8 fields)

| Field | Relationship assessment |
|---|---|
| `minFramesForAnalysis` | Runtime evidence minimum for MAD rejection, ordinarily a positive count; fewer frames intentionally skip rejection. |
| `starCountSigmaDeviation` | Nonnegative one-sided tolerance on star-count statistics; no ordering with other rejection sigmas. |
| `fwhmSigmaDeviation` | Nonnegative one-sided focus tolerance; combines with measured statistics and its floor. |
| `eccentricitySigmaDeviation` | Nonnegative one-sided shape tolerance; separate distribution from bright-star metrics. |
| `enableBrightStarEccentricityFilter` | Disables the dedicated rejection gate, not measurement of the bright-star quality metric. |
| `brightStarEccentricitySigmaDeviation` | Applies to that gate when enabled and sufficiently measured; no order against the ordinary shape sigma. |
| `backgroundSigmaDeviation` | Nonnegative background tolerance with measured and absolute floors; no pair with detection sigma. |
| `zeroSigmaFallback` | Positive fallback scale for zero MAD; scalar validation, no meaningful order against a sigma multiplier or mixed-unit floors. |

### Anomaly rescue (6 fields)

| Field | Relationship assessment |
|---|---|
| `enableAnomalyRescue` | Parent switch; independent of residual/local rescue switches. |
| `anomalyMinPixels` | Common minimum for both rescue paths; a smaller value than extraction minimum is redundant. |
| `anomalyMinPeakSigma` | Peak-path gate; at or below integrated-path peak floor it subsumes the integrated path. Warn, do not rewrite. |
| `anomalyMinIntegratedPixels` | Additional diffuse-path count; below common anomaly minimum it is redundant. |
| `anomalyMinIntegratedSigma` | Integrated SNR gate; do not order against peak SNR or a sigma multiplier measured on another image. |
| `anomalyMinPeakSigmaFloor` | Peak prominence safety floor; warn about a subsumed diffuse path when it reaches/exceeds the main peak gate. |

### Residual analyses and artifact rejection (8 fields)

| Field | Relationship assessment |
|---|---|
| `enableResidualTransientAnalysis` | Parent for local rescue/activity stages; not a parent for common-motion or camera-fixed rejection. |
| `enableLocalRescueCandidates` | Effective only with residual analysis; preserve preference while parent is off. |
| `enableLocalActivityClusters` | Effective only with residual analysis; activates its radius and minimum count. |
| `localActivityClusterRadiusPixels` | Nonnegative distance (currently clamped); no required relationship to tracking tolerance or jitter. |
| `localActivityClusterMinFrames` | Positive count (currently clamped to at least 1); compare with actual available frames for capability feedback. |
| `enableCameraFixedRejection` | Independent switch; registration evidence determines whether rejection can operate. |
| `enableCommonMotionRejection` | Independent pre-residual rejection switch; requires the data pattern described by the algorithm, not another feature flag. |
| `commonMotionShiftTolerance` | Nonnegative distance (currently clamped); effective when common-motion rejection runs. No fixed ordering against jitter. |

### Photometry setup (10 fields)

| Field | Relationship assessment |
|---|---|
| `enableVariableStarDetection` | Parent for photometry and variability; readiness remains a measured outcome. |
| `photometryMaxStars` | Zero is unlimited; positive cap below readiness/ensemble minimum guarantees unavailability. Negative values currently also mean uncapped. |
| `photometryMinSnr` | Positive value activates a measured per-frame SNR gate; non-positive currently disables it. No comparison with extraction sigma or variability z-score. |
| `photometryMaxElongation` | Star-selection shape limit, normally >= 1; no ordering against quality FWHM cutoff or streak classification. |
| `photometryApertureFwhmFactor` | Positive intended factor; all measured apertures, including fixed comparison apertures, constrain effective annulus geometry. |
| `photometryAnnulusInnerFwhmFactor` | Intended outside the apertures; measurement already expands the effective radius. Consolidate resolution before new rejection. |
| `photometryAnnulusOuterFwhmFactor` | Intended outside the inner radius; measurement already enforces pixel width. Selection/other consumers need consistent geometry. |
| `photometrySaturationFraction` | Ordinary positive fraction up to 1; applies to measured saturation level, not to detection or amplitude sigma. |
| `photometryFitPlane` | Request with deliberate zero-point-only fallback when too few usable stars; do not force-disable or impose a 30-star profile constraint. |
| `photometryMaxRegistrationSpreadPixels` | Nonnegative measured-spread limit; no necessary relationship to star jitter, which measures/controls different effects. |

### Photometric readiness (9 fields)

| Field | Relationship assessment |
|---|---|
| `linearityMinDistinctLevels` | Encoding/readiness minimum; above 65,536 is unattainable. Zero currently makes the count check vacuous. No paired threshold. |
| `linearityMaxFloorClippedFraction` | Fraction 0–1; limit on measured sky-floor clipping, unrelated to star-mask overlap. |
| `linearityMaxConcentrationDrift` | Nonnegative concentration-change allowance; no order against slope or magnitude-range limits. |
| `linearityMinRangeMag` | Nonnegative measured magnitude span requirement; independent of variable-star amplitude minima. |
| `linearityMinStars` | Positive readiness count; conflicts with a smaller positive photometry cap, but must also be met by actual usable stars. |
| `linearityMaxFrameSlope` | Nonnegative magnitude-response slope allowance; no order against concentration drift despite dimensionless-looking values. |
| `linearityMinZeroPointRangeMag` | Nonnegative measured driver-spread requirement; insufficient spread is intentionally inconclusive. |
| `linearityMaxSlopeTrackingCorrelation` | Absolute correlation threshold, ordinarily 0–1; independent of variability correlations. |
| `linearityMaxFailingFrameFraction` | Fraction 0–1; tolerates failed readiness frames. No fixed order with track coverage or support percentages. |

### Variability scoring (14 fields)

| Field | Relationship assessment |
|---|---|
| `variableMinFrames` | Minimum measured samples, not a cap; session entry already needs at least 3. No upper bound on persistence requirement derived from this field. |
| `variableMinSpanMinutes` | Nonnegative actual time-span gate; no count-to-duration conversion without cadence, and no enforcement without timestamps. |
| `variableNoiseModelNeighbors` | Positive useful comparison count; automatically capped by actual available comparison stars, not a static field ordering. |
| `variableScoreSigma` | Robust variability z-score threshold; independent of extraction and photometry SNR thresholds. |
| `variableMinAmplitudeMag` | Nonnegative absolute amplitude floor; proposed conservative ordering against limited-readiness floor. |
| `variableLimitedMinAmplitudeMag` | Proposed >= ordinary amplitude floor as a scientific policy; warn/align explicitly, not silently on legacy profiles. |
| `variableAmplitudeNoiseFactor` | Nonnegative multiplicative noise gate; independent of absolute amplitude floor and score sigma. |
| `variableMinPersistenceFrames` | Positive useful run length; compare against actual measurements. May validly exceed the minimum frame count. |
| `variableMinSplitHalfCorrelation` | Signed correlation threshold -1–1; no ordering against absolute systematics/local correlations. |
| `variableMaxApertureAmplitudeDifference` | Nonnegative relative difference; values above 1 are meaningful permissive limits, not invalid fractions. |
| `variableMaxSystematicsCorrelation` | Absolute correlation threshold 0–1; works with measured response amplitude, not a required pair with local correlation. |
| `variableSystematicsResponseFactor` | Nonnegative response multiplier; no fixed relation to amplitude-noise factor, which measures a different source of variation. |
| `variableLocalRadiusPixels` | Nonnegative search radius; zero does not universally disable the local gate. Actual eligible comparison stars govern capability. |
| `variableMaxLocalCorrelation` | Absolute correlation threshold 0–1; no required order against global systematics or split-half thresholds. |

## Additional settings in DetectionConfigurationPanel

These values are application-owned, outside the 107 engine fields:

| Setting | Relationship assessment |
|---|---|
| Auto-Tune frame limit | Existing profile normalization requires at least 4 candidate frames. Available pool and memory are runtime constraints; do not tie this to track or variability minimum frames. |
| Variable-star detailed-card limit | Already checked as 1–1000 by `VariableStarReportOptions`; presentation cap, not a detection/measurement count. |
| Variable-star report sort order | Required enum, independently validated; changes presentation only. |
| `streakLineScaleFactor` | Positive annotation scale; independent of streak classification and measured length. |
| `streakCentroidBoxRadius` | Positive display radius; need not be ordered against point-source radius. |
| `pointSourceMinBoxRadius` | Positive display minimum; independent of detection footprint size. |
| `dynamicBoxPadding` | Nonnegative display padding; no required relationship to crop padding. |
| `autoStretchBlackSigma` | Nonnegative display allowance below the reference level; not the lower member of a black/white multiplier ordering. |
| `autoStretchWhiteSigma` | Positive display allowance above the reference level; no need to exceed the black multiplier numerically. |
| `gifBlinkSpeedMs` | Positive animation delay; independent of observing timestamps or exposure duration. |
| `trackCropPadding` | Nonnegative export padding; crop/image clipping belongs to rendering context. |
| `includeAiCreativeReportSections` | Independent report-inclusion preference; no engine-analysis dependency. |

## How this fits the planned shared rules

Implementing any proposal belongs to JTransient's JA-01 and SpacePixels D10, following the published engine release. This review does not expand the initial migration of the existing six pairs into an unreviewed rules change.

The shared contract needs to distinguish numeric errors, consistency-policy warnings, effective adjustments and stage readiness. `validate(config, operation)` can diagnose configuration-only conflicts; prepared-context checks can add actual frames, timing, selected-star counts and effective geometry. Their results should carry stable rule/field/stage identities, leaving messages and focus behaviour to SpacePixels. Explicit `align` should return changes, preserve sentinels, and avoid inventing defaults for scientific trade-offs.

Verification for a future change should target the actual distinctions found here: legacy quality-grow-above-seed inputs; master-grow zero; independent frame/master thresholds; cap zero and too-small caps; plane-fit fallback; resolved annuli across FWHM values; geometric fallback without timestamps; pruned geometric and timed/untimed streak rhythm; disabled optional stages; a valid persistence requirement larger than the minimum frame count; and existing profile round trips. Changes to thresholds or effective radii require the plan's before/after real-data comparisons. No processing tests were run for this documentation-only review; the field inventory and source references were checked mechanically.
