/* Read-only catalogue context. This module never changes photometry, gates or detection tiers. */
(function (root) {
  'use strict';
  const finite = value => (typeof value === 'number' || (typeof value === 'string' && value.trim() !== ''))
    && Number.isFinite(Number(value)) ? Number(value) : NaN;
  const text = value => typeof value === 'string' ? value.trim() : '';
  const escape = value => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;')
    .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  const fmt = (value, digits) => Number.isFinite(value) ? value.toFixed(digits) : 'unknown';
  const marked = (value, limit, uncertainty) => text(limit) + fmt(value, 3) + text(uncertainty);
  const flagged = value => text(value) !== '';
  const array = value => Array.isArray(value) ? value : [];

  function sourceDiscrepancies(match, sources) {
    return array(sources).filter(source => finite(source.oid) === finite(match.oid)
      && (!text(source.vsxType) || text(source.vsxType) === text(match.type))
      && match.minIsAmplitude === true
      && Math.abs(finite(match.minMag) - finite(source.vsxAmplitude)) <= 0.00051
      && Math.abs(finite(match.periodDays) - finite(source.periodDays)) <= 0.000001);
  }

  function evaluate(observation, matches, sources) {
    observation = observation || {};
    matches = array(matches);
    if (!matches.length) return null;
    const match = matches[0] || {};
    const facts = [], qualifications = [];
    const observed = finite(observation.observedRange), noise = finite(observation.expectedScatter);
    const span = finite(observation.spanMinutes), gap = finite(observation.largestGapMinutes);
    const count = finite(observation.usablePoints), period = finite(match.periodDays);
    const filters = array(observation.filters).map(text).filter(Boolean);
    const maxBand = text(match.maxBand), minBand = text(match.minBand);
    const max = finite(match.maxMag), min = finite(match.minMag);
    const legacy = match.legacySharedBand === true;
    const exactSessionBand = filters.length === 1 && observation.missingFilter === false;
    let coverage = 'Coverage: ' + fmt(span, 1) + ' min; ' + fmt(count, 0) + ' usable measurements';
    if (Number.isFinite(gap)) coverage += '; largest gap ' + fmt(gap, 1) + ' min';
    if (period > 0 && !flagged(match.periodLimit)) {
      coverage += '; catalogue period ' + fmt(period * 24, 3) + ' h';
      if (span > 0) {
        const percent = span / (period * 1440) * 100;
        coverage += ' (' + (flagged(match.periodUncertainty) ? 'approximately ' : '')
          + (percent < 0.1 ? '<0.1' : fmt(percent, 1)) + '% time-span coverage)';
      }
    } else if (period > 0) {
      coverage += '; catalogue period ' + text(match.periodLimit) + ' ' + fmt(period * 24, 3) + ' h (limit)';
    } else coverage += '; no catalogue period';
    facts.push(coverage + '.');
    if (!Number.isFinite(span)) qualifications.push('Usable timestamps are incomplete; time-span coverage is unavailable.');
    qualifications.push('Time-span coverage does not establish which phases were sampled. An eclipse can change rapidly within a small part of an orbit.');
    if (flagged(match.periodLimit) || flagged(match.periodUncertainty)) qualifications.push('The catalogue period is limited or uncertain.');

    let sessionBand = filters.length ? filters.join(', ') : 'unknown';
    if (filters.length > 1) sessionBand = 'mixed (' + sessionBand + ')';
    if (observation.missingFilter && filters.length) sessionBand += ' (some filter headers missing)';
    facts.push('Passbands: session ' + sessionBand + '; catalogue '
      + (match.minIsAmplitude ? 'amplitude ' + (minBand || 'unknown') + ', listed magnitude ' + (maxBand || 'unknown')
        : 'bright endpoint ' + (maxBand || 'unknown') + ', faint endpoint ' + (minBand || 'unknown')) + '.');
    if (legacy) qualifications.push('This saved catalogue records a shared passband; separate endpoint bands are unavailable.');
    if (match.comparisonMetadataComplete !== true) qualifications.push('This catalogue response omits some uncertainty/limit metadata; the comparison is provisional.');
    if (/[:|]/.test(text(match.type))) qualifications.push('The catalogue classification is uncertain or offers alternative types.');
    if (matches.length > 1) qualifications.push(matches.length + ' catalogue entries are within the search radius. The closest is shown; identification is ambiguous.');

    let amplitude = NaN, amplitudeBand = '', interpretation = '';
    if (match.minIsAmplitude === true && min >= 0) {
      amplitude = min;
      amplitudeBand = minBand;
      interpretation = 'catalogue amplitude ' + marked(min, match.minLimit, match.minUncertainty) + ' mag';
    } else if (Number.isFinite(max) && Number.isFinite(min)) {
      interpretation = 'catalogue endpoints ' + marked(max, match.maxLimit, match.maxUncertainty) + ' to '
        + marked(min, match.minLimit, match.minUncertainty) + ' mag';
      if (min >= max && maxBand && minBand && maxBand === minBand) {
        amplitude = min - max;
        amplitudeBand = minBand;
        interpretation += ' (range ' + fmt(amplitude, 3) + ' mag)';
      } else qualifications.push('The catalogue endpoints cannot be interpreted as one magnitude range: their passbands are unknown/different or their ordering is unresolved.');
    } else interpretation = 'catalogue amplitude/range unavailable';
    const valueFlags = flagged(match.minLimit) || flagged(match.minUncertainty)
      || (!match.minIsAmplitude && (flagged(match.maxLimit) || flagged(match.maxUncertainty)));
    const sourceNotes = sourceDiscrepancies(match, sources);
    const canCompareAmplitude = amplitude > 0 && Number.isFinite(observed) && observed >= 0
      && exactSessionBand && !!amplitudeBand && !valueFlags && sourceNotes.length === 0 && matches.length === 1;
    let variation = 'Observed variation: ' + fmt(observed, 3) + ' mag (P95–P5); ' + interpretation;
    if (canCompareAmplitude) {
      variation += '; numerical difference ' + (observed - amplitude >= 0 ? '+' : '')
        + fmt(observed - amplitude, 3) + ' mag, ratio ' + fmt(observed / amplitude, 2);
    }
    facts.push(variation + '.');

    if (valueFlags) qualifications.push('A catalogue magnitude/amplitude is a limit or is marked uncertain; the amplitude screen is inconclusive.');
    for (const source of sourceNotes) {
      const depth = finite(source.fluxDepthPercent);
      const converted = depth > 0 && depth < 100 ? -2.5 * Math.log10(1 - depth / 100) : NaN;
      facts.push('Source publication: ' + text(source.record) + ', eclipse flux depth ' + fmt(depth, 3)
        + '% = ' + fmt(converted, 3) + ' mag. VSX reports amplitude ' + fmt(min, 3)
        + ' mag; the units/provenance differ. Original values are retained.');
      if (text(source.classificationNote)) qualifications.push(text(source.classificationNote));
    }
    if (sourceNotes.length) qualifications.push('Review the catalogue source discrepancy before interpreting an amplitude difference.');
    if (!exactSessionBand) qualifications.push('The session filter is unknown or mixed; the amplitude screen is inconclusive.');
    const crossBand = exactSessionBand && amplitudeBand && filters[0] !== amplitudeBand;
    if (crossBand) qualifications.push('The session and catalogue passbands differ. Any excess is a cross-band review prompt, not evidence that the detection is false.');
    if (!amplitudeBand) qualifications.push('The catalogue amplitude passband is unavailable; the amplitude screen is inconclusive.');
    if (!Number.isFinite(noise) || noise < 0) qualifications.push('An empirical noise estimate is unavailable; the amplitude screen is inconclusive.');

    const canScreen = canCompareAmplitude && Number.isFinite(noise) && noise >= 0;
    const review = canScreen && observed > 1.5 * amplitude && observed - amplitude > Math.max(0.03, 3 * noise);
    const limited = !canScreen || crossBand || legacy || match.comparisonMetadataComplete !== true
      || flagged(match.periodLimit) || flagged(match.periodUncertainty) || /[:|]/.test(text(match.type));
    let title = 'Catalogue comparison', status = 'information';
    if (sourceNotes.length) { title += ' — review source metadata'; status = 'review'; }
    else if (review) { title += ' — review amplitude' + (crossBand ? ' (different passbands)' : ''); status = 'review'; }
    else if (limited) { title += ' — limited comparison'; status = 'limited'; }
    if (review) qualifications.push('Screening prompt: observed range exceeds 1.5 times the catalogue amplitude and the excess exceeds max(0.03 mag, 3 times expected scatter). This is a heuristic, not an amplitude significance test.');
    if (canScreen && !review && observed < amplitude) qualifications.push('A smaller session range can reflect partial phase coverage; it is not flagged as a discrepancy.');
    const source = text(match.source) || 'AAVSO VSX via VizieR';
    facts.push('Catalogue source: ' + source + (finite(match.oid) > 0 ? ', record ' + fmt(finite(match.oid), 0) : '')
      + (text(match.fetchedAtUtc) ? '; fetched ' + text(match.fetchedAtUtc) : '') + '.');
    return {title, status, facts, qualifications, sourceNotes, amplitude: Number.isFinite(amplitude) ? amplitude : null,
      reviewAmplitude: review, catalogueUrl: finite(match.oid) > 0 ? 'https://www.aavso.org/vsx/index.php?view=detail.top&oid=' + Math.trunc(finite(match.oid)) : null,
      coveragePercent: span > 0 && period > 0 && !flagged(match.periodLimit) ? span / (period * 1440) * 100 : null};
  }

  function html(result, compact) {
    if (!result) return '';
    const title = '<strong class="' + (result.status === 'review' ? 'comparison-review' : '') + '">' + escape(result.title) + '</strong>';
    let body = '<div class="comparison-body">' + result.facts.map(f => '<span class="comparison-fact">' + escape(f) + '</span>').join('');
    body += '<div class="comparison-qualifications">' + result.qualifications.map(escape).join(' ') + '</div>';
    for (const source of result.sourceNotes) {
      const url = text(source.referenceUrl);
      if (/^https?:\/\//i.test(url)) body += '<a href="' + escape(url) + '" target="_blank" rel="noopener noreferrer">Source publication: ' + escape(text(source.citation)) + '</a> ';
    }
    if (result.catalogueUrl) body += '<a href="' + escape(result.catalogueUrl) + '" target="_blank" rel="noopener noreferrer">VSX source record / references</a>';
    body += '<div class="comparison-qualifications">Catalogue context only; detection confidence and checks are unchanged.</div></div>';
    return compact ? '<details><summary>' + title + '</summary>' + body + '</details>' : title + body;
  }

  let reportData = null, sourceRecords = [], slotsByKey = new Map();
  function parseElement(doc, id) {
    const element = doc.getElementById(id);
    try {
      const parsed = element ? JSON.parse(element.textContent || '{}') : {};
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
    } catch (_) { return {}; }
  }
  function renderKey(key) {
    const entry = reportData && Object.prototype.hasOwnProperty.call(reportData, key) ? reportData[key] : null;
    if (!entry) return;
    const result = evaluate(entry.observation, entry.matches, sourceRecords);
    (slotsByKey.get(key) || []).forEach(slot => {
      slot.hidden = !result;
      slot.innerHTML = html(result, slot.getAttribute('data-compact') === 'true');
    });
  }
  function initialize(doc) {
    reportData = parseElement(doc, 'catalogue-comparison-data').candidates || {};
    sourceRecords = array(parseElement(doc, 'catalogue-source-notes').records);
    slotsByKey = new Map();
    doc.querySelectorAll('[data-catalogue-comparison]').forEach(slot => {
      const key = slot.getAttribute('data-catalogue-comparison');
      if (!slotsByKey.has(key)) slotsByKey.set(key, []);
      slotsByKey.get(key).push(slot);
    });
    Object.keys(reportData).forEach(renderKey);
  }
  function update(key, matches) {
    if (!reportData || !Object.prototype.hasOwnProperty.call(reportData, key)) return;
    reportData[key].matches = array(matches);
    renderKey(key);
  }
  const api = Object.freeze({evaluate, html, initialize, update});
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.SpacePixelsCatalogueComparison = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
