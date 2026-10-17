
(function () {
  const REPORT_PROXY_BASE_URL = __SPACEPIXELS_PROXY_URL__;
  const REPORT_PROXY_FETCH_URL = REPORT_PROXY_BASE_URL + '/api/report/fetch';
  const PERSISTED_LOOKUP_CACHE_SCRIPT_ID = __SPACEPIXELS_CACHE_ID__;
  const JPL_MAX_RENDER_ROWS = 250;
  const liveLookupCache = new Map();
  const liveRenderSlotState = new Map();
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
    if (entry.kind === 'loading') {
      html = "<span style='color:#9aa4ad;'>checking...</span>";
    } else if (entry.kind === 'unavailable') {
      html = "<span style='color:#d0a050;' title='Start SpacePixels to run catalogue lookups from the report.'>lookup unavailable</span>";
    } else if (!entry.response || entry.response.ok !== true) {
      html = "<span style='color:#d0a050;'>lookup failed</span>";
    } else {
      const matches = safeArray((entry.response.normalized || {}).matches);
      if (!matches.length) {
        html = "<span style='color:#c9a227;'>not in VSX</span>";
      } else {
        const match = matches[0];
        const name = escapeHtml(match.name || '?');
        const link = match.vsxUrl ? "<a class='live-link-inline' href='" + escapeHtml(match.vsxUrl) + "' target='_blank' rel='noopener noreferrer'>" + name + "</a>" : name;
        html = '<strong>' + link + '</strong> &middot; ' + escapeHtml(match.type || '?') + ' &middot; ' + escapeHtml(formatVsxPeriod(match.periodDays))
          + ' &middot; ' + escapeHtml(formatNumber(match.separationArcsec, 1)) + '&Prime;'
          + (matches.length > 1 ? " <span style='color:#888;'>(+" + (matches.length - 1) + ' more)</span>' : '');
      }
    }
    cells.forEach(function (cell) {
      const hidden = cell.querySelector('button');
      cell.innerHTML = html;
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

  function buildLoadingPanelHtml(title) {
    return "<div class='live-render-panel'><div class='live-render-heading'><div class='live-render-title'>" + escapeHtml(title) + "</div><div class='live-render-meta'>Loading...</div></div><div class='live-caption'>Loading saved or live report data from SpacePixels...</div></div>";
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
      return buildLoadingPanelHtml(entry.renderTitle);
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
      const provider = button.getAttribute('data-provider');
      const target = button.getAttribute('data-target');
      if (provider && target) {
        liveLookupCache.set(provider + '|' + target, persistedResponse);
      }
      upsertSlotEntry(buildEntryState(button, 'response', { response: persistedResponse }));
    });
  }

  function loadLiveLookup(button, refresh) {
    const provider = button.getAttribute('data-provider');
    const target = button.getAttribute('data-target');
    const sidecarFile = button.getAttribute('data-sidecar-file');
    const slotId = button.getAttribute('data-slot-id');
    if (!provider || !target || !slotId) {
      return Promise.resolve();
    }

    const cacheKey = provider + '|' + target;
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
    const timeout = setTimeout(() => controller.abort(), provider === 'jpl' ? 330000 : 90000);
    return fetch(requestUrl, {signal: controller.signal})
      .then(function (httpResponse) {
        return httpResponse.json();
      })
      .then(function (response) {
        if (response && response.ok === true) liveLookupCache.set(cacheKey, response);
        upsertSlotEntry(buildEntryState(button, 'response', { response: response }));
        return response;
      })
      .catch(function (error) {
        upsertSlotEntry(buildEntryState(button, 'unavailable', { error: error }));
        return {ok: false, message: String(error)};
      })
      .finally(function () {
        clearTimeout(timeout);
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
    const button = event.target.closest('.render-live-link');
    if (!button) {
      return;
    }
    event.preventDefault();
    loadLiveLookup(button);
  });

  if (typeof module !== 'undefined' && module.exports) module.exports = {loadLiveLookup, identifyAllVsx};
  seedButtonRenderOrder();
  SpacePixelsCatalogueComparison.initialize(document);
  seedPersistedLookupResults();
})();
