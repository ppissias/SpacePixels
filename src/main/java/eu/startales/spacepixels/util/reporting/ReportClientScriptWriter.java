/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */

package eu.startales.spacepixels.util.reporting;

import java.io.PrintWriter;

/**
 * Emits the client-side JavaScript embedded in reports for live lookup rendering and persisted sidecar-cache
 * hydration.
 */
final class ReportClientScriptWriter {

    private ReportClientScriptWriter() {
    }

    static void appendLiveReportRenderingScript(PrintWriter report) {
        report.println(ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_START_MARKER);
        report.println("<script type='application/json' id='"
                + escapeHtml(ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_SCRIPT_ID)
                + "'>{\"version\":1,\"entries\":{}}</script>");
        report.println(ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_END_MARKER);
        report.println("<script>");
        report.println("(function () {");
        report.println("  const REPORT_PROXY_BASE_URL = '" + escapeJsString(ReportLookupProxyServer.getServiceBaseUrl()) + "';");
        report.println("  const REPORT_PROXY_FETCH_URL = REPORT_PROXY_BASE_URL + '/api/report/fetch';");
        report.println("  const PERSISTED_LOOKUP_CACHE_SCRIPT_ID = '" + escapeJsString(ReportLookupProxyServer.PERSISTED_LOOKUP_CACHE_SCRIPT_ID) + "';");
        report.println("  const JPL_MAX_RENDER_ROWS = 250;");
        report.println("  const liveLookupCache = new Map();");
        report.println("  const liveRenderSlotState = new Map();");
        report.println("  const persistedLookupCache = readPersistedLookupCache();");
        report.println("");
        report.println("  function escapeHtml(value) {");
        report.println("    if (value === null || value === undefined) return '';");
        report.println("    return String(value)");
        report.println("      .replace(/&/g, '&amp;')");
        report.println("      .replace(/</g, '&lt;')");
        report.println("      .replace(/>/g, '&gt;')");
        report.println("      .replace(/\\\"/g, '&quot;');");
        report.println("  }");
        report.println("");
        report.println("  function safeArray(value) {");
        report.println("    return Array.isArray(value) ? value : [];");
        report.println("  }");
        report.println("");
        report.println("  function formatNumber(value, digits) {");
        report.println("    const number = Number(value);");
        report.println("    if (!Number.isFinite(number)) return 'n/a';");
        report.println("    return number.toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits });");
        report.println("  }");
        report.println("");
        report.println("  function parseLooseNumber(value) {");
        report.println("    if (typeof value === 'number') return value;");
        report.println("    if (typeof value !== 'string') return Number.NaN;");
        report.println("    const cleaned = value.replace(/,/g, '').trim();");
        report.println("    const parsed = Number(cleaned);");
        report.println("    return Number.isFinite(parsed) ? parsed : Number.NaN;");
        report.println("  }");
        report.println("");
        report.println("  function buildMetricCard(label, value) {");
        report.println("    return \"<div class='live-mini-card'><span class='live-mini-label'>\" + escapeHtml(label) + \"</span><span class='live-mini-value'>\" + escapeHtml(value) + \"</span></div>\";");
        report.println("  }");
        report.println("");
        report.println("  function buildRawJsonDetails(payload) {");
        report.println("    return \"<details class='live-raw-json'><summary>Show Raw JSON</summary><pre>\" + escapeHtml(JSON.stringify(payload, null, 2)) + \"</pre></details>\";");
        report.println("  }");
        report.println("");
        report.println("  function readPersistedLookupCache() {");
        report.println("    const element = document.getElementById(PERSISTED_LOOKUP_CACHE_SCRIPT_ID);");
        report.println("    if (!element) {");
        report.println("      return { entries: {} };");
        report.println("    }");
        report.println("    try {");
        report.println("      const parsed = JSON.parse(element.textContent || '{}');");
        report.println("      if (!parsed || typeof parsed !== 'object') {");
        report.println("        return { entries: {} };");
        report.println("      }");
        report.println("      if (!parsed.entries || typeof parsed.entries !== 'object') {");
        report.println("        parsed.entries = {};");
        report.println("      }");
        report.println("      return parsed;");
        report.println("    } catch (error) {");
        report.println("      return { entries: {} };");
        report.println("    }");
        report.println("  }");
        report.println("");
        report.println("  function buildRenderMeta(response) {");
        report.println("    if (response && response.cacheSource === 'saved-report') {");
        report.println("      return response.fetchedAtUtc ? 'Loaded from saved report cache | Original fetch ' + response.fetchedAtUtc : 'Loaded from saved report cache';");
        report.println("    }");
        report.println("    if (response && response.cacheSource === 'sidecar') {");
        report.println("      return response.fetchedAtUtc ? 'Loaded from saved sidecar | Original fetch ' + response.fetchedAtUtc : 'Loaded from saved sidecar';");
        report.println("    }");
        report.println("    return response && response.fetchedAtUtc ? 'Fetched ' + response.fetchedAtUtc : 'Fetched just now';");
        report.println("  }");
        report.println("");
        report.println("  function buildDefaultRenderTitle(provider) {");
        report.println("    return provider === 'satchecker' ? 'SatChecker Results' : 'JPL Results';");
        report.println("  }");
        report.println("");
        report.println("  function buildEntryKey(provider, target, sidecarFile) {");
        report.println("    return sidecarFile || (provider + '|' + target);");
        report.println("  }");
        report.println("");
        report.println("  function buildEntryState(button, kind, payload) {");
        report.println("    const provider = button.getAttribute('data-provider');");
        report.println("    const target = button.getAttribute('data-target');");
        report.println("    const sidecarFile = button.getAttribute('data-sidecar-file');");
        report.println("    const slotId = button.getAttribute('data-slot-id');");
        report.println("    const renderTitle = button.getAttribute('data-render-title') || buildDefaultRenderTitle(provider);");
        report.println("    const renderOrder = Number(button.getAttribute('data-render-order') || '0');");
        report.println("    return {");
        report.println("      key: buildEntryKey(provider, target, sidecarFile),");
        report.println("      kind: kind,");
        report.println("      provider: provider,");
        report.println("      target: target,");
        report.println("      sidecarFile: sidecarFile,");
        report.println("      slotId: slotId,");
        report.println("      renderTitle: renderTitle,");
        report.println("      renderOrder: Number.isFinite(renderOrder) ? renderOrder : 0,");
        report.println("      response: payload && payload.response ? payload.response : null,");
        report.println("      error: payload && payload.error ? payload.error : null");
        report.println("    };");
        report.println("  }");
        report.println("");
        report.println("  function ensureSlotState(slotId) {");
        report.println("    if (!liveRenderSlotState.has(slotId)) {");
        report.println("      liveRenderSlotState.set(slotId, new Map());");
        report.println("    }");
        report.println("    return liveRenderSlotState.get(slotId);");
        report.println("  }");
        report.println("");
        report.println("  function upsertSlotEntry(entry) {");
        report.println("    if (!entry || !entry.slotId || !entry.key) {");
        report.println("      return;");
        report.println("    }");
        report.println("    const slot = document.getElementById(entry.slotId);");
        report.println("    if (!slot) {");
        report.println("      return;");
        report.println("    }");
        report.println("    ensureSlotState(entry.slotId).set(entry.key, entry);");
        report.println("    renderSlotEntries(slot, entry.slotId);");
        report.println("  }");
        report.println("");
        report.println("  function renderSlotEntries(slot, slotId) {");
        report.println("    const state = ensureSlotState(slotId);");
        report.println("    const entries = Array.from(state.values()).sort(function (left, right) {");
        report.println("      if (left.renderOrder !== right.renderOrder) {");
        report.println("        return left.renderOrder - right.renderOrder;");
        report.println("      }");
        report.println("      return String(left.renderTitle || '').localeCompare(String(right.renderTitle || ''));");
        report.println("    });");
        report.println("    slot.innerHTML = entries.map(buildPanelHtml).join('');");
        report.println("  }");
        report.println("");
        report.println("  function buildLoadingPanelHtml(title) {");
        report.println("    return \"<div class='live-render-panel'><div class='live-render-heading'><div class='live-render-title'>\" + escapeHtml(title) + \"</div><div class='live-render-meta'>Loading...</div></div><div class='live-caption'>Loading saved or live report data from SpacePixels...</div></div>\";");
        report.println("  }");
        report.println("");
        report.println("  function buildLookupUnavailablePanelHtml(provider, error, title) {");
        report.println("    const providerLabel = provider === 'satchecker' ? 'SatChecker' : 'JPL';");
        report.println("    const details = error && error.message ? \"<div class='live-caption'>Technical detail: \" + escapeHtml(error.message) + \"</div>\" : '';");
        report.println("    return \"<div class='live-render-panel error'><div class='live-render-heading'><div class='live-render-title'>\" + escapeHtml(title) + \"</div><div class='live-render-meta'>Unavailable</div></div><p class='live-caption'>SpacePixels is not reachable on \" + escapeHtml(REPORT_PROXY_BASE_URL) + \". Start SpacePixels to use <strong>Render \" + providerLabel + \" Results Here</strong>. The browser link above still works.</p>\" + details + \"</div>\";");
        report.println("  }");
        report.println("");
        report.println("  function buildErrorResponseHtml(response, title) {");
        report.println("    const message = response && response.message ? response.message : 'The live lookup did not return usable data.';");
        report.println("    let html = \"<div class='live-render-panel error'><div class='live-render-title'>Live lookup failed</div><p class='live-caption'>\" + escapeHtml(message) + \"</p>\";");
        report.println("    html = \"<div class='live-render-panel error'><div class='live-render-heading'><div class='live-render-title'>\" + escapeHtml(title) + \"</div><div class='live-render-meta'>Lookup failed</div></div><p class='live-caption'>\" + escapeHtml(message) + \"</p>\";");
        report.println("    if (response && response.upstreamStatus) {");
        report.println("      html += \"<div class='live-caption'>Upstream HTTP status: \" + escapeHtml(response.upstreamStatus) + \"</div>\";");
        report.println("    }");
        report.println("    if (response && response.payload !== undefined) {");
        report.println("      html += buildRawJsonDetails(response.payload);");
        report.println("    }");
        report.println("    html += \"</div>\";");
        report.println("    return html;");
        report.println("  }");
        report.println("");
        report.println("  function buildSatCheckerResponseHtml(response, title) {");
        report.println("    const normalized = response.normalized || {};");
        report.println("    const candidates = safeArray(normalized.candidates).slice();");
        report.println("    candidates.sort(function (a, b) {");
        report.println("      const angleDelta = parseLooseNumber(a.minAngleDeg) - parseLooseNumber(b.minAngleDeg);");
        report.println("      if (angleDelta !== 0) {");
        report.println("        return angleDelta;");
        report.println("      }");
        report.println("      const positionDelta = parseLooseNumber(b.positionCount) - parseLooseNumber(a.positionCount);");
        report.println("      if (positionDelta !== 0) {");
        report.println("        return positionDelta;");
        report.println("      }");
        report.println("      const spanDelta = parseLooseNumber(a.maxAngleDeg) - parseLooseNumber(b.maxAngleDeg);");
        report.println("      if (spanDelta !== 0) {");
        report.println("        return spanDelta;");
        report.println("      }");
        report.println("      return String(a.displayName || '').localeCompare(String(b.displayName || ''));");
        report.println("    });");
        report.println("");
        report.println("    let html = \"<div class='live-render-panel'><div class='live-render-heading'><div class='live-render-title'>\" + escapeHtml(title) + \"</div><div class='live-render-meta'>\" + escapeHtml(buildRenderMeta(response)) + \"</div></div>\";");
        report.println("    html += \"<div class='live-mini-grid'>\";");
        report.println("    html += buildMetricCard('Satellites', normalized.totalSatellites !== undefined ? normalized.totalSatellites : candidates.length);");
        report.println("    html += buildMetricCard('Positions', normalized.totalPositionResults !== undefined ? normalized.totalPositionResults : 'n/a');");
        report.println("    html += buildMetricCard('Runtime', normalized.totalTimeSeconds !== undefined ? formatNumber(normalized.totalTimeSeconds, 2) + ' s' : 'n/a');");
        report.println("    html += \"</div>\";");
        report.println("    html += \"<p class='live-caption'>This is the same SatChecker lookup shown by the browser link above, rendered inside the report for quick inspection.</p>\";");
        report.println("    html += \"<p class='live-caption'>Candidates are ordered heuristically by closest angular separation first, then by how many SatChecker positions were returned for that satellite.</p>\";");
        report.println("");
        report.println("    if (!candidates.length) {");
        report.println("      html += \"<p class='live-empty-note'>SatChecker returned no satellite candidates for this query.</p>\";");
        report.println("    } else {");
        report.println("      html += \"<div class='live-table-box'><table><thead><tr><th>Name</th><th>NORAD</th><th>Positions</th><th>First UTC</th><th>Last UTC</th><th>Closest Angle (deg)</th><th>Range (km)</th><th>TLE Epoch</th><th>Links</th></tr></thead><tbody>\";");
        report.println("      candidates.forEach(function (candidate) {");
        report.println("        const noradText = candidate.noradId ? String(candidate.noradId) : 'n/a';");
        report.println("        const rangeText = candidate.minRangeKm !== undefined ? formatNumber(candidate.minRangeKm, 1) : 'n/a';");
        report.println("        let links = '';");
        report.println("        if (candidate.n2yoUrl) {");
        report.println("          links += \"<a class='live-link-inline' href='\" + escapeHtml(candidate.n2yoUrl) + \"' target='_blank' rel='noopener noreferrer'>N2YO Details</a>\";");
        report.println("        }");
        report.println("        if (candidate.celestrakUrl) {");
        report.println("          if (links) { links += \" | \"; }");
        report.println("          links += \"<a class='live-link-inline' href='\" + escapeHtml(candidate.celestrakUrl) + \"' target='_blank' rel='noopener noreferrer'>CelesTrak SATCAT</a>\";");
        report.println("        }");
        report.println("        html += \"<tr>\" +");
        report.println("          \"<td>\" + escapeHtml(candidate.displayName || 'Unnamed satellite') + \"</td>\" +");
        report.println("          \"<td>\" + escapeHtml(noradText) + \"</td>\" +");
        report.println("          \"<td>\" + escapeHtml(candidate.positionCount !== undefined ? candidate.positionCount : 'n/a') + \"</td>\" +");
        report.println("          \"<td>\" + escapeHtml(candidate.firstUtc || 'n/a') + \"</td>\" +");
        report.println("          \"<td>\" + escapeHtml(candidate.lastUtc || 'n/a') + \"</td>\" +");
        report.println("          \"<td>\" + escapeHtml(candidate.minAngleDeg !== undefined ? formatNumber(candidate.minAngleDeg, 4) : 'n/a') + \"</td>\" +");
        report.println("          \"<td>\" + escapeHtml(rangeText) + \"</td>\" +");
        report.println("          \"<td>\" + escapeHtml(candidate.tleEpochUtc || 'n/a') + \"</td>\" +");
        report.println("          \"<td>\" + links + \"</td>\" +");
        report.println("          \"</tr>\";");
        report.println("      });");
        report.println("      html += \"</tbody></table></div>\";");
        report.println("    }");
        report.println("");
        report.println("    html += buildRawJsonDetails(response.payload);");
        report.println("    html += \"</div>\";");
        report.println("    return html;");
        report.println("  }");
        report.println("");
        report.println("  function buildJplResponseHtml(response, title) {");
        report.println("    const normalized = response.normalized || {};");
        report.println("    const fields = safeArray(normalized.fields);");
        report.println("    const allRows = safeArray(normalized.rows).slice();");
        report.println("    const distanceFieldIndex = fields.findIndex(function (fieldName) {");
        report.println("      return String(fieldName).indexOf('Dist. from center Norm') !== -1;");
        report.println("    });");
        report.println("    if (distanceFieldIndex >= 0) {");
        report.println("      allRows.sort(function (left, right) {");
        report.println("        const leftValue = parseLooseNumber(safeArray(left.values)[distanceFieldIndex]);");
        report.println("        const rightValue = parseLooseNumber(safeArray(right.values)[distanceFieldIndex]);");
        report.println("        if (!Number.isFinite(leftValue) && !Number.isFinite(rightValue)) return 0;");
        report.println("        if (!Number.isFinite(leftValue)) return 1;");
        report.println("        if (!Number.isFinite(rightValue)) return -1;");
        report.println("        return leftValue - rightValue;");
        report.println("      });");
        report.println("    }");
        report.println("");
        report.println("    const displayedRows = allRows.slice(0, JPL_MAX_RENDER_ROWS);");
        report.println("    let html = \"<div class='live-render-panel'><div class='live-render-heading'><div class='live-render-title'>\" + escapeHtml(title) + \"</div><div class='live-render-meta'>\" + escapeHtml(buildRenderMeta(response)) + \"</div></div>\";");
        report.println("    html += \"<div class='live-mini-grid'>\";");
        report.println("    html += buildMetricCard('Result Set', normalized.resultSetLabel || 'Candidates');");
        report.println("    html += buildMetricCard('Matches', normalized.matchCount !== undefined ? normalized.matchCount : allRows.length);");
        report.println("    html += buildMetricCard('Observer', normalized.observerLocation || 'Topocentric / MPC');");
        report.println("    html += \"</div>\";");
        report.println("    html += \"<p class='live-caption'>This is the same JPL Small-Body Identification lookup shown by the browser link above, rendered inside the report for faster inspection.</p>\";");
        report.println("");
        report.println("    if (!displayedRows.length) {");
        report.println("      html += \"<p class='live-empty-note'>JPL returned no matching small bodies for this query.</p>\";");
        report.println("    } else {");
        report.println("      html += \"<div class='live-table-box'><table><thead><tr>\";");
        report.println("      fields.forEach(function (fieldName) {");
        report.println("        html += \"<th>\" + escapeHtml(fieldName) + \"</th>\";");
        report.println("      });");
        report.println("      html += \"<th>Links</th></tr></thead><tbody>\";");
        report.println("      displayedRows.forEach(function (row) {");
        report.println("        const values = safeArray(row.values);");
        report.println("        html += \"<tr>\";");
        report.println("        values.forEach(function (value) {");
        report.println("          html += \"<td>\" + escapeHtml(value) + \"</td>\";");
        report.println("        });");
        report.println("        let links = '';");
        report.println("        if (row.sbdbLookupUrl) {");
        report.println("          links += \"<a class='live-link-inline' href='\" + escapeHtml(row.sbdbLookupUrl) + \"' target='_blank' rel='noopener noreferrer'>JPL SBDB Lookup</a>\";");
        report.println("        }");
        report.println("        if (row.sbdbUrl) {");
        report.println("          if (links) { links += \" | \"; }");
        report.println("          links += \"<a class='live-link-inline' href='\" + escapeHtml(row.sbdbUrl) + \"' target='_blank' rel='noopener noreferrer'>JPL SBDB Classic</a>\";");
        report.println("        }");
        report.println("        html += \"<td>\" + links + \"</td></tr>\";");
        report.println("      });");
        report.println("      html += \"</tbody></table></div>\";");
        report.println("      if (allRows.length > displayedRows.length) {");
        report.println("        html += \"<p class='live-empty-note'>Showing the closest \" + escapeHtml(displayedRows.length) + \" of \" + escapeHtml(allRows.length) + \" returned rows. Use the browser link above for the full raw response.</p>\";");
        report.println("      }");
        report.println("    }");
        report.println("");
        report.println("    html += buildRawJsonDetails(response.payload);");
        report.println("    html += \"</div>\";");
        report.println("    return html;");
        report.println("  }");
        report.println("");
        report.println("  function buildPanelHtml(entry) {");
        report.println("    if (!entry) {");
        report.println("      return '';");
        report.println("    }");
        report.println("    if (entry.kind === 'loading') {");
        report.println("      return buildLoadingPanelHtml(entry.renderTitle);");
        report.println("    }");
        report.println("    if (entry.kind === 'unavailable') {");
        report.println("      return buildLookupUnavailablePanelHtml(entry.provider, entry.error, entry.renderTitle);");
        report.println("    }");
        report.println("    if (!entry.response || entry.response.ok !== true) {");
        report.println("      return buildErrorResponseHtml(entry.response || { message: 'The live lookup returned no data.' }, entry.renderTitle);");
        report.println("    }");
        report.println("    if (entry.response.provider === 'satchecker') {");
        report.println("      return buildSatCheckerResponseHtml(entry.response, entry.renderTitle);");
        report.println("    }");
        report.println("    if (entry.response.provider === 'jpl') {");
        report.println("      return buildJplResponseHtml(entry.response, entry.renderTitle);");
        report.println("    }");
        report.println("    return buildErrorResponseHtml({ message: 'Unknown live lookup provider returned by SpacePixels.' }, entry.renderTitle);");
        report.println("  }");
        report.println("");
        report.println("  function seedButtonRenderOrder() {");
        report.println("    Array.from(document.querySelectorAll('.render-live-link')).forEach(function (button, index) {");
        report.println("      button.setAttribute('data-render-order', String(index));");
        report.println("    });");
        report.println("  }");
        report.println("");
        report.println("  function seedPersistedLookupResults() {");
        report.println("    const entries = persistedLookupCache && persistedLookupCache.entries ? persistedLookupCache.entries : {};");
        report.println("    Array.from(document.querySelectorAll('.render-live-link')).forEach(function (button) {");
        report.println("      const sidecarFile = button.getAttribute('data-sidecar-file');");
        report.println("      if (!sidecarFile || !entries[sidecarFile]) {");
        report.println("        return;");
        report.println("      }");
        report.println("      const persistedResponse = Object.assign({}, entries[sidecarFile], { cacheSource: 'saved-report' });");
        report.println("      const provider = button.getAttribute('data-provider');");
        report.println("      const target = button.getAttribute('data-target');");
        report.println("      if (provider && target) {");
        report.println("        liveLookupCache.set(provider + '|' + target, persistedResponse);");
        report.println("      }");
        report.println("      upsertSlotEntry(buildEntryState(button, 'response', { response: persistedResponse }));");
        report.println("    });");
        report.println("  }");
        report.println("");
        report.println("  function loadLiveLookup(button) {");
        report.println("    const provider = button.getAttribute('data-provider');");
        report.println("    const target = button.getAttribute('data-target');");
        report.println("    const sidecarFile = button.getAttribute('data-sidecar-file');");
        report.println("    const slotId = button.getAttribute('data-slot-id');");
        report.println("    if (!provider || !target || !slotId) {");
        report.println("      return;");
        report.println("    }");
        report.println("");
        report.println("    const cacheKey = provider + '|' + target;");
        report.println("    if (liveLookupCache.has(cacheKey)) {");
        report.println("      upsertSlotEntry(buildEntryState(button, 'response', { response: liveLookupCache.get(cacheKey) }));");
        report.println("      return;");
        report.println("    }");
        report.println("");
        report.println("    const originalLabel = button.getAttribute('data-original-label') || button.textContent;");
        report.println("    button.setAttribute('data-original-label', originalLabel);");
        report.println("    button.disabled = true;");
        report.println("    button.textContent = 'Loading...';");
        report.println("    upsertSlotEntry(buildEntryState(button, 'loading'));");
        report.println("");
        report.println("    let requestUrl = REPORT_PROXY_FETCH_URL + '?provider=' + encodeURIComponent(provider) + '&target=' + encodeURIComponent(target);");
        report.println("    if (sidecarFile) {");
        report.println("      const reportUrl = window.location.href.split('#')[0].split('?')[0];");
        report.println("      requestUrl += '&report=' + encodeURIComponent(reportUrl) + '&sidecar=' + encodeURIComponent(sidecarFile);");
        report.println("    }");
        report.println("");
        report.println("    fetch(requestUrl)");
        report.println("      .then(function (httpResponse) {");
        report.println("        return httpResponse.json();");
        report.println("      })");
        report.println("      .then(function (response) {");
        report.println("        liveLookupCache.set(cacheKey, response);");
        report.println("        upsertSlotEntry(buildEntryState(button, 'response', { response: response }));");
        report.println("      })");
        report.println("      .catch(function (error) {");
        report.println("        upsertSlotEntry(buildEntryState(button, 'unavailable', { error: error }));");
        report.println("      })");
        report.println("      .finally(function () {");
        report.println("        button.disabled = false;");
        report.println("        button.textContent = originalLabel;");
        report.println("      });");
        report.println("  }");
        report.println("");
        report.println("  document.addEventListener('click', function (event) {");
        report.println("    const button = event.target.closest('.render-live-link');");
        report.println("    if (!button) {");
        report.println("      return;");
        report.println("    }");
        report.println("    event.preventDefault();");
        report.println("    loadLiveLookup(button);");
        report.println("  });");
        report.println("");
        report.println("  seedButtonRenderOrder();");
        report.println("  seedPersistedLookupResults();");
        report.println("})();");
        report.println("</script>");
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String escapeJsString(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
