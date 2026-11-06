
(function () {
  const REPORT_PROXY_BASE_URL = __SPACEPIXELS_PROXY_URL__;
  const REPORT_PROXY_FETCH_URL = REPORT_PROXY_BASE_URL + '/api/report/fetch';
  const STELLARIUM_DIRECT_BASE_URL = 'http://127.0.0.1:8090';
  const STELLARIUM_MANUAL_URL = 'https://github.com/ppissias/SpacePixels/blob/master/MANUAL.md#annex-local-stellarium-integration';
  const STELLARIUM_TIMEOUT_MS = 10000;
  const PERSISTED_LOOKUP_CACHE_SCRIPT_ID = __SPACEPIXELS_CACHE_ID__;
  const JPL_MAX_RENDER_ROWS = 250;
  const liveLookupCache = new Map();
  const liveRenderSlotState = new Map();
  const activeSatelliteRequests = new Map();
  const persistedLookupCache = readPersistedLookupCache();

  function escapeHtml(value) {
    if (value === null || value === undefined) return '';
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/\"/g, '&quot;');
  }

  function safeArray(value) {
    return Array.isArray(value) ? value : [];
  }

  function formatNumber(value, digits) {
    const number = Number(value);
    if (!Number.isFinite(number)) return 'n/a';
    return number.toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits });
  }

  function parseLooseNumber(value) {
    if (typeof value === 'number') return value;
    if (typeof value !== 'string') return Number.NaN;
    const cleaned = value.replace(/,/g, '').trim();
    if (!cleaned) return Number.NaN;
    const parsed = Number(cleaned);
    return Number.isFinite(parsed) ? parsed : Number.NaN;
  }

  function buildMetricCard(label, value) {
    return "<div class='live-mini-card'><span class='live-mini-label'>" + escapeHtml(label) + "</span><span class='live-mini-value'>" + escapeHtml(value) + "</span></div>";
  }

  function buildRawJsonDetails(payload) {
    return "<details class='live-raw-json'><summary>Show Raw JSON</summary><pre>" + escapeHtml(JSON.stringify(payload, null, 2)) + "</pre></details>";
  }

  function readPersistedLookupCache() {
    const element = document.getElementById(PERSISTED_LOOKUP_CACHE_SCRIPT_ID);
    if (!element) {
      return { entries: {} };
    }
    try {
      const parsed = JSON.parse(element.textContent || '{}');
      if (!parsed || typeof parsed !== 'object') {
        return { entries: {} };
      }
      if (!parsed.entries || typeof parsed.entries !== 'object') {
        parsed.entries = {};
      }
      return parsed;
    } catch (error) {
      return { entries: {} };
    }
  }

  function buildRenderMeta(response) {
    if (response && response.cacheSource === 'saved-report') {
      return response.fetchedAtUtc ? 'Loaded from saved report cache | Original fetch ' + response.fetchedAtUtc : 'Loaded from saved report cache';
    }
    if (response && response.cacheSource === 'sidecar') {
      return response.fetchedAtUtc ? 'Loaded from saved sidecar | Original fetch ' + response.fetchedAtUtc : 'Loaded from saved sidecar';
    }
    return response && response.fetchedAtUtc ? 'Fetched ' + response.fetchedAtUtc : 'Fetched just now';
  }

  function buildDefaultRenderTitle(provider) {
    return provider === 'satchecker' ? 'SatChecker Results' : 'JPL Results';
  }

  function buildEntryKey(provider, target, sidecarFile) {
    return sidecarFile || (provider + '|' + target);
  }

  function buildEntryState(button, kind, payload) {
    const provider = button.getAttribute('data-provider');
    const target = button.getAttribute('data-target');
    const sidecarFile = button.getAttribute('data-sidecar-file');
    const slotId = button.getAttribute('data-slot-id');
    const renderTitle = button.getAttribute('data-render-title') || buildDefaultRenderTitle(provider);
    const renderOrder = Number(button.getAttribute('data-render-order') || '0');
    return {
      key: buildEntryKey(provider, target, sidecarFile),
      kind: kind,
      provider: provider,
      target: target,
      sidecarFile: sidecarFile,
      slotId: slotId,
      renderTitle: renderTitle,
      renderOrder: Number.isFinite(renderOrder) ? renderOrder : 0,
      isTrackComparison: !!button.getAttribute('data-observation-id'),
      response: payload && payload.response ? payload.response : null,
      error: payload && payload.error ? payload.error : null
    };
  }

  function ensureSlotState(slotId) {
    if (!liveRenderSlotState.has(slotId)) {
      liveRenderSlotState.set(slotId, new Map());
    }
    return liveRenderSlotState.get(slotId);
  }

  function updateKnownAs(entry) {
    if (!entry || entry.provider !== 'vsx' || !entry.sidecarFile) {
      return;
    }
    const cells = document.querySelectorAll("[data-known-as-sidecar='" + entry.sidecarFile + "']");
    if (!cells.length) {
      return;
    }
    let html;
    let hasVsxMatch = false;
    if (entry.kind === 'loading') {
      html = "<span style='color:var(--sp-text-muted);'>checking...</span>";
    } else if (entry.kind === 'unavailable') {
      html = "<span style='color:var(--sp-warning);' title='Start SpacePixels to run catalogue lookups from the report.'>lookup unavailable</span>";
    } else if (!entry.response || entry.response.ok !== true) {
      html = "<span style='color:var(--sp-warning);'>lookup failed</span>";
    } else {
      const matches = safeArray((entry.response.normalized || {}).matches);
      if (!matches.length) {
        html = "<span style='color:var(--sp-warning);'>not in VSX</span>";
      } else {
        hasVsxMatch = true;
        const match = matches[0];
        const name = escapeHtml(match.name || '?');
        const link = match.vsxUrl ? "<a class='live-link-inline' href='" + escapeHtml(match.vsxUrl) + "' target='_blank' rel='noopener noreferrer'>" + name + "</a>" : name;
        html = '<strong>' + link + '</strong> &middot; ' + escapeHtml(match.type || '?') + ' &middot; ' + escapeHtml(formatVsxPeriod(match.periodDays))
          + ' &middot; ' + escapeHtml(formatNumber(match.separationArcsec, 1)) + '&Prime;'
          + (matches.length > 1 ? " <span style='color:var(--sp-text-faint);'>(+" + (matches.length - 1) + ' more)</span>' : '');
      }
    }
    cells.forEach(function (cell) {
      const hidden = cell.querySelector('button');
      const fallback = cell.getAttribute('data-catalogue-star') || '';
      cell.innerHTML = (!hasVsxMatch && fallback ? fallback + '<br>' : '') + html;
      if (hidden) {
        cell.appendChild(hidden);
      }
    });
  }

  function identifyAllVsx(trigger) {
    const buttons = Array.from(document.querySelectorAll("button[data-identify-all='1']"));
    const status = document.querySelector('.vsx-identify-status');
    if (!buttons.length) {
      return;
    }
    trigger.disabled = true;
    const total = buttons.length;
    let done = 0;
    let next = 0;
    let known = 0;
    let failed = 0;
    let saved = 0;
    function showProgress() {
      if (status) {
        status.textContent = 'Checked ' + done + ' of ' + total + ' candidates...';
      }
    }
    function worker() {
      if (next >= total) {
        return Promise.resolve();
      }
      const button = buttons[next++];
      return Promise.resolve(loadLiveLookup(button)).then(function (response) {
        if (!response || response.ok !== true) failed++;
        else {
          if (safeArray((response.normalized || {}).matches).length) known++;
          if (response.persisted === true || response.cacheSource === 'saved-report') saved++;
        }
        done++;
        showProgress();
        return worker();
      });
    }
    showProgress();
    return Promise.all([worker(), worker(), worker()]).then(function () {
      if (status) {
        status.textContent = 'Checked ' + total + ': ' + known + ' matched, ' + (total - failed - known) + ' without a match, ' + failed + ' failed. ' + saved + ' results saved in this report. Failed lookups can be retried.';
      }
      trigger.disabled = false;
    });
  }

  function upsertSlotEntry(entry) {
    if (!entry || !entry.slotId || !entry.key) {
      return;
    }
    updateKnownAs(entry);
    if (entry.provider === 'vsx' && entry.kind === 'response' && entry.response && entry.response.ok === true) {
      SpacePixelsCatalogueComparison.update(entry.sidecarFile, safeArray((entry.response.normalized || {}).matches).map(function (match) {
        return Object.assign({ fetchedAtUtc: entry.response.fetchedAtUtc }, match);
      }));
    }
    const slot = document.getElementById(entry.slotId);
    if (!slot) {
      return;
    }
    ensureSlotState(entry.slotId).set(entry.key, entry);
    renderSlotEntries(slot, entry.slotId);
  }

  function renderSlotEntries(slot, slotId) {
    const state = ensureSlotState(slotId);
    const entries = Array.from(state.values()).sort(function (left, right) {
      if (left.renderOrder !== right.renderOrder) {
        return left.renderOrder - right.renderOrder;
      }
      return String(left.renderTitle || '').localeCompare(String(right.renderTitle || ''));
    });
    slot.innerHTML = entries.map(function (entry) {
      const unsaved = entry.response && entry.response.ok === true && entry.response.persisted === false
        ? "<p class='live-caption'>Lookup succeeded, but the report could not be updated on disk. Keep the report in a writable folder to save the result.</p>" : '';
      return buildPanelHtml(entry) + unsaved + (entry.kind === 'loading' ? '' : "<button type='button' class='id-link lookup-refresh' data-lookup-key='" + escapeHtml(entry.key) + "' data-lookup-slot='" + escapeHtml(slotId) + "'>" + (entry.kind === 'response' && entry.response && entry.response.ok ? 'Refresh lookup' : 'Retry lookup') + "</button>");
    }).join('');
  }

  function buildLoadingPanelHtml(title, comparison) {
    const caption = comparison ? 'Loading predictions across the recorded exposures. This can take several minutes; use Cancel comparison to stop it.' : 'Loading saved or live report data from SpacePixels...';
    return "<div class='live-render-panel'><div class='live-render-heading'><div class='live-render-title'>" + escapeHtml(title) + "</div><div class='live-render-meta'>Loading...</div></div><div class='live-caption'>" + caption + "</div></div>";
  }

  function buildLookupUnavailablePanelHtml(provider, error, title) {
    const providerLabel = provider === 'satchecker' ? 'SatChecker' : provider === 'vsx' ? 'VSX' : 'JPL';
    const details = error && error.message ? "<div class='live-caption'>Technical detail: " + escapeHtml(error.message) + "</div>" : '';
    return "<div class='live-render-panel error'><div class='live-render-heading'><div class='live-render-title'>" + escapeHtml(title) + "</div><div class='live-render-meta'>Unavailable</div></div><p class='live-caption'>SpacePixels is not reachable on " + escapeHtml(REPORT_PROXY_BASE_URL) + ". Start SpacePixels to use <strong>Render " + providerLabel + " Results Here</strong>. The browser link above still works.</p>" + details + "</div>";
  }

  function buildErrorResponseHtml(response, title) {
    const message = response && response.message ? response.message : 'The live lookup did not return usable data.';
    let html = "<div class='live-render-panel error'><div class='live-render-title'>Live lookup failed</div><p class='live-caption'>" + escapeHtml(message) + "</p>";
    html = "<div class='live-render-panel error'><div class='live-render-heading'><div class='live-render-title'>" + escapeHtml(title) + "</div><div class='live-render-meta'>Lookup failed</div></div><p class='live-caption'>" + escapeHtml(message) + "</p>";
    if (response && response.upstreamStatus) {
      html += "<div class='live-caption'>Upstream HTTP status: " + escapeHtml(response.upstreamStatus) + "</div>";
    }
    if (response && response.payload !== undefined) {
      html += buildRawJsonDetails(response.payload);
    }
    html += "</div>";
    return html;
  }

  function buildSatCheckerResponseHtml(response, title) {
    if (response.normalized && response.normalized.trackComparison) return buildSatelliteComparisonHtml(response, title);
    const normalized = response.normalized || {};
    const candidates = safeArray(normalized.candidates).slice();
    candidates.sort(function (a, b) {
      const angleDelta = parseLooseNumber(a.minAngleDeg) - parseLooseNumber(b.minAngleDeg);
      if (angleDelta !== 0) {
        return angleDelta;
      }
      const positionDelta = parseLooseNumber(b.positionCount) - parseLooseNumber(a.positionCount);
      if (positionDelta !== 0) {
        return positionDelta;
      }
      const spanDelta = parseLooseNumber(a.maxAngleDeg) - parseLooseNumber(b.maxAngleDeg);
      if (spanDelta !== 0) {
        return spanDelta;
      }
      return String(a.displayName || '').localeCompare(String(b.displayName || ''));
    });

    let html = "<div class='live-render-panel'><div class='live-render-heading'><div class='live-render-title'>" + escapeHtml(title) + "</div><div class='live-render-meta'>" + escapeHtml(buildRenderMeta(response)) + "</div></div>";
    html += "<div class='live-mini-grid'>";
    html += buildMetricCard('Satellites', normalized.totalSatellites !== undefined ? normalized.totalSatellites : candidates.length);
    html += buildMetricCard('Positions', normalized.totalPositionResults !== undefined ? normalized.totalPositionResults : 'n/a');
    html += buildMetricCard('Runtime', normalized.totalTimeSeconds !== undefined ? formatNumber(normalized.totalTimeSeconds, 2) + ' s' : 'n/a');
    html += "</div>";
    html += "<p class='live-caption'>This is the same SatChecker lookup shown by the browser link above, rendered inside the report for quick inspection.</p>";
    html += "<p class='live-caption'>Candidates are ordered heuristically by closest angular separation first, then by how many SatChecker positions were returned for that satellite.</p>";

    if (!candidates.length) {
      html += "<p class='live-empty-note'>SatChecker returned no satellite candidates for this query.</p>";
    } else {
      html += "<div class='live-table-box'><table><thead><tr><th>Name</th><th>NORAD</th><th>Positions</th><th>First UTC</th><th>Last UTC</th><th>Closest Angle (deg)</th><th>Range (km)</th><th>TLE Epoch</th><th>Links</th></tr></thead><tbody>";
      candidates.forEach(function (candidate) {
        const noradText = candidate.noradId ? String(candidate.noradId) : 'n/a';
        const rangeText = candidate.minRangeKm !== undefined ? formatNumber(candidate.minRangeKm, 1) : 'n/a';
        let links = '';
        if (candidate.n2yoUrl) {
          links += "<a class='live-link-inline' href='" + escapeHtml(candidate.n2yoUrl) + "' target='_blank' rel='noopener noreferrer'>N2YO Details</a>";
        }
        if (candidate.celestrakUrl) {
          if (links) { links += " | "; }
          links += "<a class='live-link-inline' href='" + escapeHtml(candidate.celestrakUrl) + "' target='_blank' rel='noopener noreferrer'>CelesTrak SATCAT</a>";
        }
        html += "<tr>" +
          "<td>" + escapeHtml(candidate.displayName || 'Unnamed satellite') + "</td>" +
          "<td>" + escapeHtml(noradText) + "</td>" +
          "<td>" + escapeHtml(candidate.positionCount !== undefined ? candidate.positionCount : 'n/a') + "</td>" +
          "<td>" + escapeHtml(candidate.firstUtc || 'n/a') + "</td>" +
          "<td>" + escapeHtml(candidate.lastUtc || 'n/a') + "</td>" +
          "<td>" + escapeHtml(candidate.minAngleDeg !== undefined ? formatNumber(candidate.minAngleDeg, 4) : 'n/a') + "</td>" +
          "<td>" + escapeHtml(rangeText) + "</td>" +
          "<td>" + escapeHtml(candidate.tleEpochUtc || 'n/a') + "</td>" +
          "<td>" + links + "</td>" +
          "</tr>";
      });
      html += "</tbody></table></div>";
    }

    html += buildRawJsonDetails(response.payload);
    html += "</div>";
    return html;
  }

  // All scientific distances are calculated on the sphere by the proxy. This tangent-plane plot is for inspection.
  function exposureColour(index) { return ['#6ec6ff', '#ffb74d', '#81c784', '#ce93d8', '#ef9a9a', '#80cbc4'][index % 6]; }

  function satelliteOverlay(exposures) {
    const observed = exposures.flatMap(e => safeArray(e.observed));
    if (!observed.length) return '';
    const ra0 = observed[0][0] * Math.PI / 180, dec0 = observed[0][1] * Math.PI / 180;
    function project(p) {
      const ra = p[0] * Math.PI / 180, dec = p[1] * Math.PI / 180;
      const d = Math.sin(dec0) * Math.sin(dec) + Math.cos(dec0) * Math.cos(dec) * Math.cos(ra - ra0);
      if (d <= 0.01) return null;
      return [Math.cos(dec) * Math.sin(ra - ra0) / d, (Math.cos(dec0) * Math.sin(dec) - Math.sin(dec0) * Math.cos(dec) * Math.cos(ra - ra0)) / d];
    }
    const points = exposures.flatMap(e => safeArray(e.observed).concat(safeArray(e.predicted))).map(project).filter(Boolean);
    let xmin = Infinity, xmax = -Infinity, ymin = Infinity, ymax = -Infinity;
    points.forEach(p => { xmin = Math.min(xmin, p[0]); xmax = Math.max(xmax, p[0]); ymin = Math.min(ymin, p[1]); ymax = Math.max(ymax, p[1]); });
    const scale = Math.min(650 / Math.max(1e-6, xmax - xmin), 190 / Math.max(1e-6, ymax - ymin));
    function xy(p) { const v = project(p); return v ? [(380 + (v[0] - (xmin + xmax) / 2) * scale).toFixed(2), (125 - (v[1] - (ymin + ymax) / 2) * scale).toFixed(2)] : null; }
    let svg = "<svg role='img' aria-label='Observed fragment centroids and predicted paths during their exposures' viewBox='0 0 760 260' style='display:block;box-sizing:border-box;width:100%;min-width:640px;max-height:360px;background:#101820;border:1px solid #506070'>";
    exposures.forEach((e, i) => {
      const colour = exposureColour(i);
      // Do not join separately projected path sections across the back of the tangent plane.
      let section = [];
      function flush() { if (section.length > 1) svg += "<polyline fill='none' stroke='" + colour + "' stroke-width='1.5' points='" + section.join(' ') + "'><title>Frame " + escapeHtml(e.frame) + " predicted path</title></polyline>"; section = []; }
      safeArray(e.predicted).forEach(p => { const v = xy(p); if (v) section.push(v.join(',')); else flush(); }); flush();
      safeArray(e.observed).forEach(p => { const v = xy(p); if (v) svg += "<circle cx='" + v[0] + "' cy='" + v[1] + "' r='3' fill='" + colour + "' stroke='#fff' stroke-width='.7'><title>Frame " + escapeHtml(e.frame) + " centroid</title></circle>"; });
    });
    return "<div class='live-table-box' style='max-height:none;overflow-y:hidden'>" + svg + "<text x='12' y='20' fill='#fff' font-size='12'>North ↑ · East →</text><text x='12' y='247' fill='#ddd' font-size='12'>Lines: predicted exposure paths · Points: observed centroids · Colours identify exposures</text></svg></div>";
  }

  function orbitalProvenanceText(p) {
    return (p.source || 'source unavailable') + ' · epoch ' + (p.epoch || 'unavailable')
      + (typeof p.epochAgeDays === 'number' ? ' (' + formatNumber(Math.abs(p.epochAgeDays), 1) + ' days ' + (p.epochAgeDays < 0 ? 'after' : 'before') + ' observations)' : '');
  }

  function buildSatelliteComparisonHtml(response, title) {
    const n = response.normalized || {}, candidates = safeArray(n.candidates);
    let html = "<div class='live-render-panel'><div class='live-render-heading' style='flex-wrap:wrap'><div class='live-render-title'>" + escapeHtml(title) + "</div><div class='live-render-meta' style='overflow-wrap:anywhere'>" + escapeHtml(buildRenderMeta(response)) + "</div></div>";
    html += "<p class='live-caption'>Candidate discovery succeeded for " + escapeHtml(n.searchedExposures) + ' of ' + escapeHtml(n.totalExposures) + ' independent exposures. '
      + (n.discoveryComplete ? 'Search intervals were covered; catalogue completeness is unknown.' : '<strong>Search coverage is incomplete.</strong>') + '</p>';
    if (n.unavailableExposureCount) html += "<p class='live-caption'>" + escapeHtml(n.unavailableExposureCount) + ' additional observed exposures lack usable timing or sky measurements and could not be compared.</p>';
    if (n.followUpComplete === false) html += "<p class='live-caption'><strong>Trajectory follow-up is incomplete.</strong> " + escapeHtml(candidates.length) + ' of ' + escapeHtml(n.totalReturnedCandidates) + ' returned candidates have follow-up results or errors. Inspect coverage and limitations before comparing alternatives.</p>';
    if (!candidates.length) html += "<p class='live-empty-note'>No candidate was returned by the completed searches. This does not rule out a satellite.</p>";
    else {
      html += "<p class='live-caption'>Complete, adequately sampled comparisons come first, ordered by median distance to the predicted exposure paths with equal weight per exposure. Partial or provisional comparisons follow. A close returned fit is an association to inspect, not a confirmed identity or a confidence probability.</p>";
      html += "<div class='live-table-box'><table><thead><tr><th>Candidate / NORAD</th><th>Typical path distance</th><th>Covered exposures</th><th>Motion direction difference</th><th>Predicted / centroid speed (″/s)</th></tr></thead><tbody>";
      candidates.forEach(c => {
        const m = c.comparison || {};
        html += '<tr><td>' + escapeHtml(c.displayName) + ' / ' + escapeHtml(c.noradId) + '</td><td>'
          + (m.medianDistanceArcsec === undefined ? 'Unavailable' : escapeHtml(formatNumber(m.medianDistanceArcsec, 2)) + '″ (' + escapeHtml(formatNumber(m.medianDistancePixels, 1)) + ' px)')
          + '</td><td>' + escapeHtml(m.coveredExposures === undefined ? 0 : m.coveredExposures) + ' / ' + escapeHtml(n.totalExposures)
          + (m.completeCoverage && m.samplingAdequate ? '' : ' · partial or provisional') + '</td><td>' + escapeHtml(formatNumber(m.motionDirectionDifferenceDegrees, 2)) + '°</td><td>'
          + escapeHtml(formatNumber(m.predictedSpeedArcsecSecond, 2)) + ' / ' + escapeHtml(formatNumber(m.centroidSpeedArcsecSecond, 2)) + '</td></tr>';
      });
      html += '</tbody></table></div>';
      candidates.forEach((c, i) => {
        const m = c.comparison || {};
        html += '<details' + (i === 0 ? ' open' : '') + '><summary>' + escapeHtml(c.displayName) + ' — exposure evidence and overlay</summary>';
        if (c.unavailableReason || m.unavailableReason) html += "<p class='live-caption'>Insufficient evidence: " + escapeHtml(c.unavailableReason || m.unavailableReason) + '</p>';
        html += "<p class='live-caption'>Centroid speed uses exposure midpoints as an estimate. The unknown centroid times allow approximately "
          + escapeHtml(formatNumber(m.centroidSpeedLowerArcsecSecond, 2)) + ' to ' + (m.centroidSpeedUpperArcsecSecond === undefined ? 'an unbounded upper rate' : escapeHtml(formatNumber(m.centroidSpeedUpperArcsecSecond, 2)) + ' ″/s')
          + '. Partial trails need not fill the predicted exposure path. Fragment-axis difference (180° ambiguity): ' + escapeHtml(formatNumber(m.axisDifferenceDegrees, 2)) + '°.'
          + ' The UTC/UT1 input approximation corresponds to up to about ' + escapeHtml(formatNumber(m.ut1TimingSensitivityArcsec, 1)) + '″ of along-path timing sensitivity.</p>';
        html += satelliteOverlay(safeArray(m.exposures));
        html += "<div class='live-table-box'><table><thead><tr><th>Frame</th><th>Exposure start UTC / seconds</th><th>Fragments</th><th>Median / p90 distance (″)</th><th>Perpendicular (″)</th><th>Beyond exposure path (″)</th></tr></thead><tbody>";
        safeArray(m.exposures).forEach((e, index) => {
          html += "<tr><td style='color:" + exposureColour(index) + "'>" + escapeHtml(e.frame) + '</td><td>' + escapeHtml(e.startUtc || 'unavailable') + ' / ' + escapeHtml(formatNumber(e.exposureSeconds, 1)) + '</td><td>' + escapeHtml(e.fragmentCount || 0) + '</td>';
          if (e.unavailableReason) html += "<td colspan='3'>" + escapeHtml(e.unavailableReason) + '</td>';
          else html += '<td>' + escapeHtml(formatNumber(e.medianDistanceArcsec, 2)) + ' / ' + escapeHtml(formatNumber(e.p90DistanceArcsec, 2)) + '</td><td>' + escapeHtml(formatNumber(e.perpendicularArcsec, 2)) + '</td><td>' + escapeHtml(formatNumber(e.beyondExposurePathArcsec, 2)) + '</td>';
          html += '</tr>';
        });
        html += '</tbody></table></div>';
        html += "<p class='live-caption'>Prediction orbital data: " + escapeHtml(safeArray(c.predictionOrbitalData).map(orbitalProvenanceText).join('; '))
          + '. Discovery orbital data: ' + escapeHtml(safeArray(c.discoveryOrbitalData).map(orbitalProvenanceText).join('; '))
          + (c.orbitalDataChanged ? '. Orbital epochs differ from candidate discovery; both responses are saved.' : '') + '</p></details>';
      });
    }
    html += "<details><summary>Assumptions and limitations</summary><ul>" + safeArray(n.warnings).map(w => '<li>' + escapeHtml(w) + '</li>').join('') + '</ul></details>';
    return html + buildRawJsonDetails(response.payload) + '</div>';
  }

  function buildJplResponseHtml(response, title) {
    const normalized = response.normalized || {};
    const fields = safeArray(normalized.fields);
    const allRows = safeArray(normalized.rows).slice();
    const distanceFieldIndex = fields.findIndex(function (fieldName) {
      return String(fieldName).indexOf('Dist. from center Norm') !== -1;
    });
    if (distanceFieldIndex >= 0) {
      allRows.sort(function (left, right) {
        const leftValue = parseLooseNumber(safeArray(left.values)[distanceFieldIndex]);
        const rightValue = parseLooseNumber(safeArray(right.values)[distanceFieldIndex]);
        if (!Number.isFinite(leftValue) && !Number.isFinite(rightValue)) return 0;
        if (!Number.isFinite(leftValue)) return 1;
        if (!Number.isFinite(rightValue)) return -1;
        return leftValue - rightValue;
      });
    }

    const displayedRows = allRows.slice(0, JPL_MAX_RENDER_ROWS);
    let html = "<div class='live-render-panel'><div class='live-render-heading'><div class='live-render-title'>" + escapeHtml(title) + "</div><div class='live-render-meta'>" + escapeHtml(buildRenderMeta(response)) + "</div></div>";
    html += "<div class='live-mini-grid'>";
    html += buildMetricCard('Result Set', normalized.resultSetLabel || 'Candidates');
    html += buildMetricCard('Matches', normalized.matchCount !== undefined ? normalized.matchCount : allRows.length);
    html += buildMetricCard('Observer', normalized.observerLocation || 'Topocentric / MPC');
    html += "</div>";
    html += "<p class='live-caption'>This is the same JPL Small-Body Identification lookup shown by the browser link above, rendered inside the report for faster inspection.</p>";

    if (!displayedRows.length) {
      html += "<p class='live-empty-note'>JPL returned no matching small bodies for this query.</p>";
    } else {
      html += "<div class='live-table-box'><table><thead><tr>";
      fields.forEach(function (fieldName) {
        html += "<th>" + escapeHtml(fieldName) + "</th>";
      });
      html += "<th>Links</th></tr></thead><tbody>";
      displayedRows.forEach(function (row) {
        const values = safeArray(row.values);
        html += "<tr>";
        values.forEach(function (value) {
          html += "<td>" + escapeHtml(value) + "</td>";
        });
        let links = '';
        if (row.sbdbLookupUrl) {
          links += "<a class='live-link-inline' href='" + escapeHtml(row.sbdbLookupUrl) + "' target='_blank' rel='noopener noreferrer'>JPL SBDB Lookup</a>";
        }
        if (row.sbdbUrl) {
          if (links) { links += " | "; }
          links += "<a class='live-link-inline' href='" + escapeHtml(row.sbdbUrl) + "' target='_blank' rel='noopener noreferrer'>JPL SBDB Classic</a>";
        }
        html += "<td>" + links + "</td></tr>";
      });
      html += "</tbody></table></div>";
      if (allRows.length > displayedRows.length) {
        html += "<p class='live-empty-note'>Showing the closest " + escapeHtml(displayedRows.length) + " of " + escapeHtml(allRows.length) + " returned rows. Use the browser link above for the full raw response.</p>";
      }
    }

    html += buildRawJsonDetails(response.payload);
    html += "</div>";
    return html;
  }

  function formatVsxRange(match) {
    const maxText = Number.isFinite(parseLooseNumber(match.maxMag)) ? formatNumber(match.maxMag, 2) : '?';
    const band = match.maxBand ? ' ' + match.maxBand.trim() : '';
    const minBand = match.minBand ? ' ' + match.minBand.trim() : '';
    if (!Number.isFinite(parseLooseNumber(match.minMag))) return maxText + band;
    if (match.minIsAmplitude) return maxText + band + ' (amplitude ' + formatNumber(match.minMag, 2) + minBand + ')';
    return maxText + band + ' - ' + formatNumber(match.minMag, 2) + minBand;
  }

  function formatVsxPeriod(days) {
    const value = parseLooseNumber(days);
    if (!Number.isFinite(value) || value <= 0) return 'n/a';
    return value < 2 ? formatNumber(value, 5) + ' d (' + formatNumber(value * 24, 2) + ' h)' : formatNumber(value, 3) + ' d';
  }

  function buildVsxResponseHtml(response, title) {
    const normalized = response.normalized || {};
    const matches = safeArray(normalized.matches);
    let html = "<div class='live-render-panel'><div class='live-render-heading'><div class='live-render-title'>" + escapeHtml(title) + "</div><div class='live-render-meta'>" + escapeHtml(buildRenderMeta(response)) + "</div></div>";
    html += "<div class='live-mini-grid'>";
    html += buildMetricCard('VSX Matches', String(matches.length));
    if (matches.length) {
      html += buildMetricCard('Closest', formatNumber(matches[0].separationArcsec, 1) + ' arcsec');
      html += buildMetricCard('Closest Type', matches[0].type || 'n/a');
    }
    html += "</div>";
    if (!matches.length) {
      html += "<p class='live-empty-note'>No catalogued variable star in the AAVSO VSX catalogue within the search radius. The candidate may be unlisted: check the SIMBAD link above before reporting it.</p>";
    } else {
      html += "<p class='live-caption'>AAVSO International Variable Star Index (VSX), queried through CDS VizieR, closest first. A catalogued match is convincing when the separation is a few arcsec and its type and period fit the light curve above.</p>";
      html += "<div class='live-table-box'><table><thead><tr><th>Name</th><th>Type</th><th>Catalogue range / amplitude (mag)</th><th>Period</th><th>Separation</th><th>Links</th></tr></thead><tbody>";
      matches.forEach(function (match) {
        const link = match.vsxUrl ? "<a class='live-link-inline' href='" + escapeHtml(match.vsxUrl) + "' target='_blank' rel='noopener noreferrer'>VSX page</a>" : '';
        html += "<tr><td>" + escapeHtml(match.name || '') + "</td><td>" + escapeHtml(match.type || '') + "</td><td>" + escapeHtml(formatVsxRange(match)) + "</td><td>" + escapeHtml(formatVsxPeriod(match.periodDays)) + "</td><td>" + escapeHtml(formatNumber(match.separationArcsec, 1)) + " arcsec</td><td>" + link + "</td></tr>";
      });
      html += "</tbody></table></div>";
    }
    html += buildRawJsonDetails(response.payload);
    html += "</div>";
    return html;
  }

  function buildPanelHtml(entry) {
    if (!entry) {
      return '';
    }
    if (entry.kind === 'loading') {
      return buildLoadingPanelHtml(entry.renderTitle, entry.isTrackComparison);
    }
    if (entry.kind === 'unavailable') {
      return buildLookupUnavailablePanelHtml(entry.provider, entry.error, entry.renderTitle);
    }
    if (!entry.response || entry.response.ok !== true) {
      return buildErrorResponseHtml(entry.response || { message: 'The live lookup returned no data.' }, entry.renderTitle);
    }
    if (entry.response.provider === 'satchecker') {
      return buildSatCheckerResponseHtml(entry.response, entry.renderTitle);
    }
    if (entry.response.provider === 'vsx') {
      return buildVsxResponseHtml(entry.response, entry.renderTitle);
    }
    if (entry.response.provider === 'jpl') {
      return buildJplResponseHtml(entry.response, entry.renderTitle);
    }
    return buildErrorResponseHtml({ message: 'Unknown live lookup provider returned by SpacePixels.' }, entry.renderTitle);
  }

  function seedButtonRenderOrder() {
    Array.from(document.querySelectorAll('.render-live-link')).forEach(function (button, index) {
      button.setAttribute('data-render-order', String(index));
    });
  }

  function seedPersistedLookupResults() {
    const entries = persistedLookupCache && persistedLookupCache.entries ? persistedLookupCache.entries : {};
    Array.from(document.querySelectorAll('.render-live-link')).forEach(function (button) {
      const sidecarFile = button.getAttribute('data-sidecar-file');
      if (!sidecarFile || !entries[sidecarFile] || entries[sidecarFile].ok !== true) {
        return;
      }
      const persistedResponse = Object.assign({}, entries[sidecarFile], { cacheSource: 'saved-report' });
      const digest = button.getAttribute('data-observation-digest');
      if (digest && (persistedResponse.observationDigest !== digest || !persistedResponse.normalized || persistedResponse.normalized.comparisonVersion !== 1)) return;
      const provider = button.getAttribute('data-provider');
      const target = button.getAttribute('data-target');
      if (provider && target) {
        liveLookupCache.set(provider + '|' + target + (digest ? '|' + digest : ''), persistedResponse);
      }
      upsertSlotEntry(buildEntryState(button, 'response', { response: persistedResponse }));
    });
  }

  function loadLiveLookup(button, refresh) {
    if (activeSatelliteRequests.has(button)) {
      activeSatelliteRequests.get(button)();
      return Promise.resolve({ok: false, message: 'Comparison cancelled.'});
    }
    const provider = button.getAttribute('data-provider');
    const target = button.getAttribute('data-target');
    const sidecarFile = button.getAttribute('data-sidecar-file');
    const slotId = button.getAttribute('data-slot-id');
    if (!provider || !target || !slotId) {
      return Promise.resolve();
    }

    const observationId = button.getAttribute('data-observation-id');
    const digest = button.getAttribute('data-observation-digest');
    const cacheKey = provider + '|' + target + (digest ? '|' + digest : '');
    if (refresh) liveLookupCache.delete(cacheKey);
    if (liveLookupCache.has(cacheKey)) {
      upsertSlotEntry(buildEntryState(button, 'response', { response: liveLookupCache.get(cacheKey) }));
      return Promise.resolve(liveLookupCache.get(cacheKey));
    }

    const originalLabel = button.getAttribute('data-original-label') || button.textContent;
    button.setAttribute('data-original-label', originalLabel);
    button.disabled = true;
    button.textContent = 'Loading...';
    upsertSlotEntry(buildEntryState(button, 'loading'));

    let requestUrl = REPORT_PROXY_FETCH_URL + '?provider=' + encodeURIComponent(provider) + '&target=' + encodeURIComponent(target);
    if (refresh) requestUrl += '&refresh=1';
    if (sidecarFile) {
      const reportUrl = window.location.href.split('#')[0].split('?')[0];
      requestUrl += '&report=' + encodeURIComponent(reportUrl) + '&sidecar=' + encodeURIComponent(sidecarFile);
    }

    const controller = new AbortController();
    let options = {signal: controller.signal};
    let cancelRequest = () => controller.abort();
    if (observationId) {
      try {
        const element = document.getElementById(observationId);
        const observation = JSON.parse(element ? element.textContent : 'null');
        if (!observation) throw new Error('The saved observation snapshot is unavailable.');
        const requestId = 'track-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
        requestUrl = REPORT_PROXY_BASE_URL + '/api/report/compare-satellite-track';
        options = {signal: controller.signal, method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({
          requestId, observation, report: window.location.href.split('#')[0].split('?')[0], sidecar: sidecarFile, refresh: !!refresh
        })};
        cancelRequest = () => {
          controller.abort();
          fetch(requestUrl, {method: 'DELETE', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({requestId})}).catch(() => {});
        };
        activeSatelliteRequests.set(button, cancelRequest);
        button.disabled = false;
        button.textContent = 'Cancel comparison';
      } catch (error) {
        button.disabled = false; button.textContent = originalLabel;
        const response = {ok: false, message: String(error)};
        upsertSlotEntry(buildEntryState(button, 'response', {response}));
        return Promise.resolve(response);
      }
    }
    const timeout = setTimeout(cancelRequest, observationId ? 310000 : provider === 'jpl' ? 330000 : 90000);
    return fetch(requestUrl, options)
      .then(function (httpResponse) {
        return httpResponse.json();
      })
      .then(function (response) {
        if (controller.signal.aborted) throw new Error('Comparison cancelled or timed out.');
        if (digest && response && response.ok && response.observationDigest !== digest) throw new Error('The returned comparison belongs to different observations.');
        if (response && response.ok === true) liveLookupCache.set(cacheKey, response);
        upsertSlotEntry(buildEntryState(button, 'response', { response: response }));
        return response;
      })
      .catch(function (error) {
        if (observationId && controller.signal.aborted) upsertSlotEntry(buildEntryState(button, 'response', {response: {ok: false, message: 'Satellite comparison cancelled or timed out.'}}));
        else upsertSlotEntry(buildEntryState(button, 'unavailable', { error: error }));
        return {ok: false, message: String(error)};
      })
      .finally(function () {
        clearTimeout(timeout);
        activeSatelliteRequests.delete(button);
        button.disabled = false;
        button.textContent = originalLabel;
      });
  }

  document.addEventListener('click', function (event) {
    const refresh = event.target.closest('.lookup-refresh');
    if (refresh) {
      event.preventDefault();
      const key = refresh.getAttribute('data-lookup-key'), slot = refresh.getAttribute('data-lookup-slot');
      const original = Array.from(document.querySelectorAll('.render-live-link')).find(button =>
        button.getAttribute('data-slot-id') === slot && buildEntryKey(button.getAttribute('data-provider'), button.getAttribute('data-target'), button.getAttribute('data-sidecar-file')) === key);
      if (original) loadLiveLookup(original, true);
      return;
    }
    const identifyAll = event.target.closest('.vsx-identify-all');
    if (identifyAll) {
      event.preventDefault();
      identifyAllVsx(identifyAll);
      return;
    }
    const stellariumButton = event.target.closest('.stellarium-local-link'); if (stellariumButton) { event.preventDefault(); openLocalStellarium(stellariumButton); return; }
    const button = event.target.closest('.render-live-link');
    if (!button) {
      return;
    }
    event.preventDefault();
    loadLiveLookup(button);
  });

  function updateStellariumStatus(button, ok, htmlMessage) {
    const container = button.closest('.astro-note');
    const status = container ? container.querySelector('.stellarium-local-status') : null;
    if (!status) return;
    status.style.display = 'block';
    status.style.color = ok ? 'var(--sp-success)' : 'var(--sp-warning)';
    status.innerHTML = htmlMessage;
  }

  function slewStellariumDirect(timeMillis, raDeg, decDeg, fovDeg, lat, lng, elev, signal) {
    const jd = (Number(timeMillis) / 86400000.0) + 2440587.5;
    const raRad = Number(raDeg) * Math.PI / 180.0;
    const decRad = Number(decDeg) * Math.PI / 180.0;
    const x = Math.cos(decRad) * Math.cos(raRad);
    const y = Math.cos(decRad) * Math.sin(raRad);
    const z = Math.sin(decRad);
    const j2000 = '[' + x.toFixed(8) + ',' + y.toFixed(8) + ',' + z.toFixed(8) + ']';
    let confirmed = true;
    const postForm = function (path, body) {
      if (signal.aborted) return Promise.reject(new Error('Stellarium request aborted.'));
      return fetch(STELLARIUM_DIRECT_BASE_URL + path, {
        method: 'POST',
        mode: 'no-cors',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
        body: body,
        signal: signal
      }).then(function (response) {
        // A direct cross-origin request can be sent, but its opaque response cannot confirm acceptance.
        if (response.type === 'opaque') {
          confirmed = false;
          return;
        }
        if (!response.ok) throw new Error('Stellarium rejected a command (HTTP ' + response.status + ').');
        return response.text().then(function (reply) {
          if (reply.trim() !== 'ok') throw new Error('Stellarium did not accept a command.');
        });
      });
    };
    let chain = Promise.resolve();
    if (lat !== null && lat !== '' && lng !== null && lng !== '') {
      const alt = (elev !== null && elev !== '') ? elev : '0.0';
      chain = chain.then(function () {
        return postForm('/api/location/setlocationfields',
          'latitude=' + encodeURIComponent(lat) + '&longitude=' + encodeURIComponent(lng)
          + '&altitude=' + encodeURIComponent(alt) + '&name=SpacePixels+Observation+Site&planet=Earth');
      });
    }
    return chain
      .then(function () { return postForm('/api/main/time', 'time=' + jd.toFixed(8) + '&timerate=0'); })
      .then(function () { return postForm('/api/main/focus', 'position=' + encodeURIComponent(j2000)); })
      .then(function () { return postForm('/api/main/fov', 'fov=' + encodeURIComponent(fovDeg)); })
      .then(function () { return confirmed; });
  }

  function openLocalStellarium(button) {
    const timeMillis = button.getAttribute('data-time');
    const utcLabel = button.getAttribute('data-utc') || '';
    const raDeg = button.getAttribute('data-ra');
    const decDeg = button.getAttribute('data-dec');
    const fovDeg = button.getAttribute('data-fov');
    const lat = button.getAttribute('data-lat');
    const lng = button.getAttribute('data-lng');
    const elev = button.getAttribute('data-elev');
    if (!timeMillis || !raDeg || !decDeg || !fovDeg) return Promise.resolve();

    const originalLabel = button.getAttribute('data-original-label') || button.textContent;
    button.setAttribute('data-original-label', originalLabel);
    button.disabled = true;
    button.textContent = 'Opening...';

    const failHtml = "Could not complete the commands for local Stellarium on port 8090. Some commands may already have been applied. Check Stellarium and ensure its Remote Control plugin is enabled (see the <a class='live-link-inline' href='"
      + STELLARIUM_MANUAL_URL + "' target='_blank' rel='noopener noreferrer'>setup instructions in the SpacePixels manual</a>).";
    const controller = new AbortController();
    let timedOut = false;
    let timeoutId;
    const timeout = new Promise(function (_, reject) {
      timeoutId = setTimeout(function () {
        timedOut = true;
        controller.abort();
        reject(new Error('Stellarium request timed out.'));
      }, STELLARIUM_TIMEOUT_MS);
    });

    // The deadline covers every command and response body, even if an aborted transport settles late.
    return Promise.race([slewStellariumDirect(timeMillis, raDeg, decDeg, fovDeg, lat, lng, elev, controller.signal), timeout])
      .then(function (confirmed) {
        const target = escapeHtml(utcLabel) + ' (FOV ' + escapeHtml(fovDeg) + '&deg;).';
        updateStellariumStatus(button, confirmed, confirmed
          ? 'Stellarium accepted the commands for ' + target
          : 'Commands sent to local Stellarium for ' + target + ' Acceptance is unconfirmed; check its window to confirm the view.');
        return { ok: true, confirmed: confirmed };
      })
      .catch(function () {
        updateStellariumStatus(button, false, timedOut
          ? 'Local Stellarium request timed out after 10 seconds. Some commands may already have been applied; check Stellarium before retrying.'
          : failHtml);
        return { ok: false };
      })
      .finally(function () {
        clearTimeout(timeoutId);
        button.disabled = false;
        button.textContent = originalLabel;
      });
  }

  if (typeof module !== 'undefined' && module.exports) module.exports = {loadLiveLookup, identifyAllVsx, openLocalStellarium, buildSatelliteComparisonHtml};
  seedButtonRenderOrder();
  SpacePixelsCatalogueComparison.initialize(document);
  seedPersistedLookupResults();
})();
