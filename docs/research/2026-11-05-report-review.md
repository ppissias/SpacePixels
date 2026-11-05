# Report review: field inventory and faint movers

Date: 2026-11-05. This investigation concerns report presentation and catalogue coverage. The detector, scientific filters, fitted candidates and saved prediction files are unchanged.

## Klangwolke-2: 11 saved objects, nine in the image

The saved magnitude-19 catalogue contains 11 objects. A read-only audit of all 33 original exposure midpoints, using the corrected WCS and the 9544 × 6361 image boundary, finds nine nominal positions inside the image. Two objects remain outside throughout the sampled session:

| Saved object | In-field midpoints | Nearest distance outside the image | Edge |
|---|---:|---:|---|
| 2606 Odessa (1976 GX2) | 0 / 33 | 274.2 px | Right |
| 4332 Milton (1983 RC) | 0 / 33 | 72.0 px | Bottom |

The [machine-readable field audit](2026-11-05-report-review-field-audit.json) preserves all 11 pixel-coordinate ranges, coverage counts and source-prediction/correction hashes without observing-site metadata.

`SolarSystemClient` intentionally retains a 5% spatial border and ten minutes around the observing span. Its conservative saved inventory can therefore exceed the number visible on the frame. Previously, `KnownSolarSystemReport` displayed the entire inventory under “in the field”, including both of these border objects.

The HTML table now selects `inFieldFrames > 0`, evaluated across the session rather than at one reference time. It states the in-field count and number of omitted saved entries. The complete inventory, coverage, track comparisons and clock evidence remain in `report-data.json`; raw predictions are not rewritten. Coverage samples exposure midpoints, so it does not establish continuous visibility throughout an exposure or completeness for fast crossings.

The nine include the nominal prediction for (2010 DR82), whose supplied uncertainty exceeds the field diagonal. Its existing uncertainty warning and exclusion from ordinary-track identification/clock inference remain necessary. Nine predicted objects is not a claim of nine confirmed detections.

## Faint-mover identification and query depth

FM cards show explicit **Possible identification** links, with all competing names preserved as ambiguous. These use the saved designation to open JPL object details. The **Unverified** status remains: catalogue position/motion compatibility is a heuristic comparison, not a confirmed identity.

The cards also reuse the ordinary moving-track SkyBoT, JPL and Stellarium controls. Queries use the fitted reference position at its own reference UTC epoch, not an invented individual-frame detection or an unrelated session midpoint. Each FM has its own live-result slot and lookup sidecar names. Missing WCS, epoch or observer information uses the shared unavailable-state handling.

The saved field fetch defaults to predicted magnitude 19 and permits settings from 8 to 23. A magnitude-20.5 detection can be absent from a magnitude-19 fetch. Increase **Asteroid and Comet Depth** and fetch again to update the saved catalogue. The detection score is not calibrated magnitude. The report's finding-specific JPL and SkyBoT URL builders do not send the saved fetch's magnitude cutoff; online provider coverage still limits identification. No depth default or bound was changed here.

The service documentation was checked on the audit date: [JPL SB-Ident](https://ssd-api.jpl.nasa.gov/doc/sb_ident.html) lists no default `vmag-lim`, and [SkyBoT cone search](https://ssp.imcce.fr/webservices/skybot/api/conesearch/) does not expose a magnitude cutoff in these URL parameters. SkyBoT does apply a default 120-arcsecond position-uncertainty filter; that is a separate provider restriction, not a brightness threshold. A missing online match therefore still cannot establish that an object is uncatalogued.

## Display scale and overlays

The former FM **1×** meant “scale the longest crop dimension to 318 CSS pixels”. It already enlarged the 31 × 31 score stack by about 10.3 times. That view is now **Fit** and remains the default. **2× Fit** and **4× Fit** preserve the previous enlargements. The additional **Native pixels (1:1)** maps one source, residual or score pixel to one CSS pixel, with stack dimensions recorded independently from source-crop dimensions. It does not widen the cutout or add sky context.

The session overview and all-detections map retain category colours, glyphs, identifiers and links, with annotation opacity reduced to 65%. The downloadable annotated PNG uses the same opacity. Variable-candidate field maps use that opacity and smaller labels, including their full-size light-curve map. Background intensity mappings and desktop viewer overlays are unchanged.

## Validation

Validation covers the ordinary application checks and report JavaScript, explicit ambiguity/reference-epoch links, native-versus-fit pixel scaling, later-entering field objects and preservation of the complete JSON inventory. Real-data comparisons use the accepted 18-field baseline plus the enabled faint-mover Klangwolke-2 run, with isolated settings and scratch inputs. Numerical evidence, source/stack assets and intended presentation changes are checked separately.

- Application `check`, both API consumers (Gradle metadata and Maven POM), and 47 report JavaScript tests passed. The application suite ran 791 tests with zero failures and seven skips.
- All 19 real-data comparisons passed: scientific result snapshots, numeric report data and stack/mask hashes are unchanged. Only generation time and the declared photometry/faint-search elapsed times are excluded from numeric equality.
- 5,754 existing report assets are byte-identical. The 32 intended asset differences comprise 17 annotated detection maps, four variable-map SVGs and 11 FM review scripts adding stack dimensions. Source/residual samples in those scripts remain identical; the score PNGs, source GIFs, fitted CSVs and image backgrounds remain unchanged.
- Klangwolke-2 again completed all 247 tiles and retained the same 11 faint movers. Its field table and navigation show nine nominal in-field predictions; all 11 saved predictions remain in JSON.
- Exported maps were rendered and visually compared. The session overlays retain their geometry and links. The populated variable maps retain their background image, candidate coordinates and names. Live browser interaction was not checked because the browser host was unavailable; offline JavaScript and export tests provide the control checks.
