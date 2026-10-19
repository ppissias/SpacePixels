# Catalogue comparison in variable-star reports

Implemented for SpacePixels 2026.10-02. This is report context, independent of JTransient detection confidence and gates.

## What the reader sees

Every matched candidate has a Catalogue comparison note below Known As in the table. The table note expands on demand; detailed candidate cards show the full note below the catalogue entry. This also covers candidates beyond the 40-card limit. Live VSX lookups update both locations, and persisted lookup results reconstruct the same notes offline.

The note shows usable point count, first-to-last usable time span, largest gap, catalogue period and time-span coverage, available FITS FILTER values, catalogue passbands, the observed P95–P5 range and the catalogue amplitude/range. Time-span coverage does not prove that all phases were sampled. A short part of an orbit can contain a substantial eclipse. No sinusoidal bound or amplitude proportional to span/period is used.

There is no green “verified” result. A numerical match does not establish the physical origin of the variability.

## Review rule and limitations

An amplitude review prompt requires finite observed variation R, positive catalogue amplitude A, a finite nonnegative empirical expected scatter s, one known session filter, a known amplitude passband, an unambiguous catalogue match, and usable catalogue values without limit/uncertainty flags or an identified source discrepancy.

The prompt is shown only when both conditions hold:

```text
R > 1.5 A
R - A > max(0.03 mag, 3 s)
```

This is a conservative heuristic. The empirical expected scatter is single-measurement noise, not uncertainty on P95–P5. A cross-band prompt explicitly says the filters differ and is a reason to review the comparison, not evidence that the detection is false. Unknown or mixed session filters, incompatible endpoint passbands, ambiguous matches and unresolved range ordering make the amplitude screen inconclusive. Smaller measured ranges are not flagged from catalogue amplitude alone.

Missing, limited or uncertain periods are explicit. Magnitude endpoints are subtracted only when they have compatible passbands and ordering. The numerical range remains “as reported”; no photometric calibration or passband transformation is introduced.

## Implementation

- `CatalogueComparisonWriter` builds read-only observation and match payloads. It uses only unflagged, finite measurements in frames retained for photometry. Original frame indices and unique filename fallback map these to FITS headers; ambiguous or missing metadata stays unknown. Incomplete timestamps do not produce a fabricated span.
- `catalogue-comparison.js` is the single evaluator and formatter for saved and live matches. It is embedded in the HTML, uses escaped text and performs no network requests. Slots are indexed once for efficient updates. It never writes to engine results or detection badges.
- `PhotometryReportSectionWriter` emits slots for every row and card, plus one embedded JSON payload. Basic fallback text remains available without JavaScript. `ReportClientScriptWriter` initializes the notes before hydrating the existing lookup cache and updates them after a successful VSX lookup, including row-only candidates.
- `SkyCatalogue.VariableStar` and the VSX normalizer retain separate endpoint passbands, magnitude and period flags, and bibliography availability. The source and record link identify the provenance; fetch time is shown when known.
- Existing version-1 catalogue files remain readable. Their original shared `band` is retained as the recorded band for both quantities, with an explicit provisional/shared-band qualification. Missing uncertainty flags are not represented as verified absence of uncertainty. Newly fetched separate bands are never inferred from each other.
- No DetectionConfig field, profile migration, engine change or additional report-time fetch is involved. Existing photometry CSV fields and candidate ordering are unchanged.

## Verified source discrepancies

`catalogue-source-notes.json` contains five individually verified public CoRoT records from Deleuil et al. (2018). Their published eclipse depths are percentages of flux, while the corresponding VSX amplitude numbers match the fractional flux depths. A note retains the VSX value and shows the original source depth and its magnitude conversion:

```text
delta_mag = -2.5 log10(1 - depth_percent / 100)
```

These are annotations, not catalogue replacements. They are keyed by VSX record ID and guarded by the inspected amplitude and period; the classification-conflict annotation also requires the inspected type. They cease to apply when those record values change. There is no name-prefix rule. A source link is included, and an identified units/provenance discrepancy suppresses the amplitude screen against that value.

- [Column definitions](https://cdsarc.cds.unistra.fr/ftp/J/A+A/619/A97/ReadMe)
- [Detached binaries, Table A.5](https://cdsarc.cds.unistra.fr/ftp/J/A+A/619/A97/tablea5.dat)
- [Contact binaries, Table A.6](https://cdsarc.cds.unistra.fr/ftp/J/A+A/619/A97/tablea6.dat)

## Validation

Java tests cover observation/header alignment, exclusion flags, missing timestamps, ambiguous filenames, legacy catalogue data and preservation of VSX flags. The JavaScript suite covers short eclipses, EW periods, nearly/fully sampled spans, small amplitudes, noise, different bands, limits, missing data, ambiguous matches, guarded source annotations, HTML escaping, and initial/live/offline updates including rows without cards.

The JavaScript tests run through the Java test suite when Node is available. Set `NODE_EXECUTABLE` to the Node executable when it is not on PATH. They can also be run directly:

```text
node --test src/test/javascript/catalogue-comparison.test.js
```
