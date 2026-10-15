const {test} = require('node:test');
const assert = require('node:assert/strict');
const api = require('../../main/resources/eu/startales/spacepixels/util/reporting/catalogue-comparison.js');
const sources = require('../../main/resources/eu/startales/spacepixels/util/reporting/catalogue-source-notes.json').records;

const observation = overrides => Object.assign({observedRange: 0.161, expectedScatter: 0.02,
  usablePoints: 29, spanMinutes: 74.7216, largestGapMinutes: 7.5, filters: ['L'], missingFilter: false}, overrides);
const match = overrides => Object.assign({oid: 123, name: 'Example', type: 'EA', maxMag: 12,
  minMag: 0.07, minIsAmplitude: true, maxBand: 'G', minBand: 'G', periodDays: 2.178775,
  comparisonMetadataComplete: true}, overrides);
const evaluate = (o, m, refs = []) => api.evaluate(observation(o), [match(m)], refs);

test('short eclipse window does not cap the permitted amplitude', () => {
  const result = evaluate({observedRange: 0.625}, {minMag: 0.749});
  assert.equal(result.reviewAmplitude, false);
  assert.ok(Math.abs(result.coveragePercent - 2.38161) < 0.0001);
  assert.ok(result.qualifications.some(s => s.includes('eclipse can change rapidly')));
});

test('EW orbital period is not used as a sinusoidal brightness constraint', () => {
  const result = evaluate({observedRange: 0.307}, {type: 'EW', minMag: 0.408, periodDays: 0.2984462});
  assert.equal(result.reviewAmplitude, false);
  assert.ok(result.coveragePercent < 18);
});

test('large cross-band excess is a review prompt, not a confidence change', () => {
  const input = Object.freeze(observation({tier: 'POSSIBLE', failedGates: Object.freeze(['PERSISTENCE'])}));
  const before = JSON.stringify(input);
  const result = api.evaluate(input, [Object.freeze(match())], []);
  assert.equal(result.reviewAmplitude, true);
  assert.match(result.title, /different passbands/);
  assert.match(api.html(result, false), /detection confidence and checks are unchanged/);
  assert.equal(JSON.stringify(input), before);
});

test('noisy amplitude excess does not satisfy the review heuristic', () => {
  assert.equal(evaluate({observedRange: 0.334, expectedScatter: 0.054}, {minMag: 0.219}).reviewAmplitude, false);
});

test('small observed range is not rejected even with a long time span', () => {
  const result = evaluate({observedRange: 0.02, spanMinutes: 1000}, {minMag: 0.7, periodDays: 0.1});
  assert.ok(result.coveragePercent > 100);
  assert.equal(result.reviewAmplitude, false);
  assert.ok(result.qualifications.some(s => s.includes('not flagged')));
});

test('unknown or mixed session filters make the screen inconclusive', () => {
  for (const o of [{filters: []}, {filters: ['L', 'V']}, {missingFilter: true}]) {
    const result = evaluate(o, {});
    assert.equal(result.reviewAmplitude, false);
    assert.equal(result.status, 'limited');
  }
});

test('different endpoint passbands and reversed unflagged endpoints cannot form one range', () => {
  for (const m of [{minMag: 12.07, minBand: 'g', maxBand: 'G'}, {minMag: 11.9}, {minMag: null}]) {
    const result = evaluate({}, Object.assign({minIsAmplitude: false}, m));
    assert.equal(result.amplitude, null);
    assert.equal(result.reviewAmplitude, false);
  }
});

test('catalogue limits and uncertainty flags are retained and suppress amplitude screening', () => {
  for (const m of [{minLimit: '>'}, {minUncertainty: ':'}]) {
    const result = evaluate({}, m);
    assert.equal(result.reviewAmplitude, false);
    assert.ok(result.qualifications.some(s => s.includes('limit or is marked uncertain')));
    assert.match(result.facts.join(' '), m.minLimit ? />0.070/ : /0.070:/);
  }
});

test('missing or limited periods do not create a fabricated coverage percentage', () => {
  for (const periodDays of [null, '', false, 'not a number', 0, -1, Infinity]) {
    const result = evaluate({}, {periodDays});
    assert.equal(result.coveragePercent, null);
    assert.doesNotMatch(api.html(result, false), /NaN|Infinity/);
  }
  const result = evaluate({}, {periodLimit: '>'});
  assert.equal(result.coveragePercent, null);
  assert.match(result.facts[0], /limit/);
});

test('ambiguous matches do not apply an amplitude screen to an uncertain identity', () => {
  const result = api.evaluate(observation(), [match(), match({oid: 456})], []);
  assert.equal(result.reviewAmplitude, false);
  assert.ok(result.qualifications.some(s => s.includes('identification is ambiguous')));
});

test('legacy shared bands are explicitly provisional', () => {
  const result = evaluate({}, {legacySharedBand: true, comparisonMetadataComplete: false});
  assert.equal(result.reviewAmplitude, true);
  assert.ok(result.qualifications.some(s => s.includes('shared passband')));
  assert.ok(result.qualifications.some(s => s.includes('provisional')));
});

test('verified source discrepancies are tied to record and values and do not replace VSX', () => {
  for (const source of sources) {
    const result = evaluate({observedRange: 0.9}, {oid: source.oid, type: source.vsxType || 'EA', minMag: source.vsxAmplitude, periodDays: source.periodDays}, sources);
    assert.equal(result.sourceNotes.length, 1);
    assert.equal(result.amplitude, source.vsxAmplitude);
    assert.equal(result.reviewAmplitude, false);
    assert.match(result.title, /review source metadata/);
    assert.doesNotMatch(result.facts.join(' '), /numerical difference|ratio /, 'incompatible source units cannot support an amplitude ratio');
  }
  const result = evaluate({}, {oid: 684845, minMag: 0.749}, sources);
  assert.equal(result.sourceNotes.length, 0, 'a corrected catalogue value must not inherit a stale source warning');
  assert.equal(evaluate({}, {oid: 686523, type: 'EA', minMag: 0.378, periodDays: 5.143685}, sources).sourceNotes.length, 0,
    'a changed classification must not inherit a stale statement about ROT:');
  assert.equal(evaluate({}, {name: 'CoRoT something else'}, sources).sourceNotes.length, 0);
  const eclipse = evaluate({}, {oid: 684845, minMag: 0.499, periodDays: 2.178775}, sources);
  assert.match(eclipse.facts.join(' '), /49.851% = 0.749 mag/);
});

test('catalogue and FITS text cannot inject HTML', () => {
  const result = evaluate({filters: ['<img src=x onerror=alert(1)>']}, {source: '</script><script>alert(1)</script>', minLimit: '<b>'});
  const html = api.html(result, false);
  assert.doesNotMatch(html, /<img|<script|<b>/);
  assert.match(html, /&lt;img/);
});

test('initial, live and offline cached matches use the same evaluator, including rows without cards', () => {
  const key = 'photometry_v1_vsx.json', rowOnly = 'photometry_v41_vsx.json';
  const slots = [slot(key, true), slot(key, false), slot(rowOnly, true)];
  const entries = {[key]: {observation: observation(), matches: []},
    [rowOnly]: {observation: observation(), matches: [match()]}};
  const doc = documentFor(entries, slots);
  api.initialize(doc);
  assert.equal(slots[0].hidden, true);
  assert.equal(slots[2].hidden, false);
  api.update(key, [match()]);
  assert.equal(slots[0].hidden, false);
  assert.match(slots[0].innerHTML, /<details>/);
  assert.match(slots[1].innerHTML, /review amplitude/);
  const liveHtml = slots[1].innerHTML;
  entries[key].matches = [match()];
  api.initialize(documentFor(entries, slots));
  assert.equal(slots[1].innerHTML, liveHtml, 'reopened saved data must reproduce the same note without a fetch');
  api.update(key, []);
  assert.equal(slots[0].hidden, true);
  assert.equal(slots[1].hidden, true);
});

function slot(key, compact) {
  return {hidden: false, innerHTML: '', getAttribute: name => name === 'data-compact' ? String(compact) : key};
}
function documentFor(entries, slots) {
  return {querySelectorAll: () => slots, getElementById: id => ({textContent: JSON.stringify(id === 'catalogue-comparison-data'
    ? {candidates: entries} : {records: []})})};
}

test('missing or damaged optional comparison data cannot break report initialization', () => {
  for (const value of ['null', '[]', 'not json', '{}']) {
    assert.doesNotThrow(() => api.initialize({querySelectorAll: () => [], getElementById: () => ({textContent: value})}));
  }
});
