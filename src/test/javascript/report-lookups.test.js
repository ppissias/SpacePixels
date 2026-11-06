const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/eu/startales/spacepixels/report/report-lookups.js', 'utf8');
const {matchesMarker} = require('../../main/resources/eu/startales/spacepixels/report/report-interactions.js');

function client(responses, count = 1, catalogueStar = '', timers = {}, comparison = {}) {
  const slot = {innerHTML: ''}, status = {textContent: ''}, requests = [], requestOptions = [], buttons = [];
  const knownAs = {innerHTML: '', getAttribute: key => key === 'data-catalogue-star' ? catalogueStar : null,
    querySelector: () => null};
  for (let i = 0; i < count; i++) {
    const attributes = {'data-provider': 'vsx', 'data-target': 'target-' + i, 'data-slot-id': 'slot', 'data-sidecar-file': 'v' + i + '.json'};
    Object.assign(attributes, comparison.attributes || {});
    buttons.push({textContent: 'Look up', getAttribute: key => attributes[key], setAttribute: (key, value) => attributes[key] = value});
  }
  const document = {
    getElementById: id => id === 'slot' ? slot : id === 'observation' ? {textContent: JSON.stringify(comparison.observation || {})}
      : id === 'cache' && comparison.cache ? {textContent: JSON.stringify(comparison.cache)} : null, addEventListener() {},
    querySelector: () => status,
    querySelectorAll: query => query === '.render-live-link' || query === "button[data-identify-all='1']" ? buttons
      : query === '[data-known-as-sidecar] strong' ? Array(9).fill({})
      : query === "[data-known-as-sidecar='v0.json']" ? [knownAs] : []
  };
  const sandbox = {document, window: {location: {href: 'file:///C:/scratch/detection_report.html'}},
    __SPACEPIXELS_PROXY_URL__: 'http://127.0.0.1:47831', __SPACEPIXELS_CACHE_ID__: 'cache',
    SpacePixelsCatalogueComparison: {initialize() {}, update() {}},
    module: {exports: {}}, AbortController, setTimeout, clearTimeout, console,
    fetch: async (url, options) => {
      requests.push(url);
      requestOptions.push(options);
      const response = responses[Math.min(requests.length - 1, responses.length - 1)];
      if (response instanceof Error) throw response;
      if (typeof response === 'function') return response(url, options);
      if (response && response.type) return response;
      return {json: async () => response};
    }, ...timers};
  vm.createContext(sandbox); vm.runInContext(source, sandbox);
  return {api: sandbox.module.exports, buttons, slot, status, requests, requestOptions, knownAs};
}
const success = matches => ({ok: true, provider: 'vsx', persisted: true, normalized: {matches}, payload: {}});
const hit = {name: 'Example', type: 'EA', periodDays: 1, separationArcsec: 1};

const trackAttributes = {'data-provider': 'satchecker', 'data-observation-id': 'observation', 'data-observation-digest': 'digest'};
const trackResponse = () => ({ok: true, provider: 'satchecker', observationDigest: 'digest', persisted: true,
  normalized: {trackComparison: true, comparisonVersion: 1, discoveryComplete: true, searchedExposures: 2, totalExposures: 2,
    candidates: [{displayName: 'Satellite <example>', noradId: 123, comparison: {completeCoverage: true, samplingAdequate: true,
      coveredExposures: 2, medianDistanceArcsec: 3, medianDistancePixels: 2, exposures: []}}], warnings: ['Unknown clock accuracy']}, payload: {}});

test('track comparison posts the saved observation, renders evidence and reuses only its own digest', async () => {
  const c = client([trackResponse()], 1, '', {}, {attributes: trackAttributes, observation: {findingId: 'ST1'}});
  await c.api.loadLiveLookup(c.buttons[0]);
  assert.equal(c.requestOptions[0].method, 'POST');
  assert.equal(JSON.parse(c.requestOptions[0].body).observation.findingId, 'ST1');
  assert.match(c.requests[0], /compare-satellite-track$/);
  assert.match(c.slot.innerHTML, /not a confirmed identity/);
  assert.match(c.slot.innerHTML, /Satellite &lt;example&gt;/);
  await c.api.loadLiveLookup(c.buttons[0]); assert.equal(c.requests.length, 1);
  c.buttons[0].setAttribute('data-observation-digest', 'different');
  await c.api.loadLiveLookup(c.buttons[0]); assert.equal(c.requests.length, 2);
  assert.doesNotMatch(c.slot.innerHTML, /Typical path distance/);
});

test('saved track evidence reopens without a provider or desktop request', async () => {
  const c = client([], 1, '', {}, {attributes: trackAttributes, cache: {entries: {'v0.json': trackResponse()}}});
  assert.match(c.slot.innerHTML, /saved report cache/);
  await c.api.loadLiveLookup(c.buttons[0]); assert.equal(c.requests.length, 0);
});

test('old observations cannot silently reuse a cached comparison of different measurements', () => {
  const c = client([], 1, '', {}, {attributes: {...trackAttributes, 'data-observation-digest': 'changed'}, cache: {entries: {'v0.json': trackResponse()}}});
  assert.equal(c.slot.innerHTML, '');
});

test('cancellation aborts the active comparison and rejects a late successful reply', async () => {
  let finish;
  const pending = new Promise(resolve => { finish = resolve; });
  const c = client([() => pending, {ok: true}], 1, '', {}, {attributes: trackAttributes});
  const running = c.api.loadLiveLookup(c.buttons[0]);
  assert.equal(c.buttons[0].textContent, 'Cancel comparison');
  await c.api.loadLiveLookup(c.buttons[0]);
  assert.equal(c.requestOptions[0].signal.aborted, true);
  assert.equal(c.requestOptions[1].method, 'DELETE');
  finish({json: async () => trackResponse()}); await running;
  assert.match(c.slot.innerHTML, /cancelled or timed out/);
  assert.doesNotMatch(c.slot.innerHTML, /Satellite &lt;example&gt;/);
  assert.equal(c.buttons[0].disabled, false);
});

test('partial searches and failed trajectories remain explicit in the evidence display', () => {
  const r = trackResponse(); r.normalized.discoveryComplete = false;
  r.normalized.candidates[0].unavailableReason = 'No historical orbit';
  const c = client([]);
  const html = c.api.buildSatelliteComparisonHtml(r, 'Comparison');
  assert.match(html, /Search coverage is incomplete/); assert.match(html, /No historical orbit/);
  r.normalized.candidates = [];
  assert.match(c.api.buildSatelliteComparisonHtml(r, 'Comparison'), /does not rule out a satellite/);
});

test('catalogue star remains without a VSX match and a positive VSX result takes precedence', async () => {
  const fallback = 'Catalogue star: <strong>Gaia DR3 3131123006665005568</strong>';
  const c = client([success([]), success([hit]), success([])], 1, fallback);
  await c.api.loadLiveLookup(c.buttons[0]);
  assert.match(c.knownAs.innerHTML, /3131123006665005568/);
  assert.match(c.knownAs.innerHTML, /not in VSX/);
  await c.api.loadLiveLookup(c.buttons[0], true);
  assert.match(c.knownAs.innerHTML, /Example/);
  assert.doesNotMatch(c.knownAs.innerHTML, /3131123006665005568/);
  await c.api.loadLiveLookup(c.buttons[0], true);
  assert.match(c.knownAs.innerHTML, /3131123006665005568/);
});

test('a failed VSX lookup retains the catalogue star name', async () => {
  const c = client([new Error('Connection refused')], 1, 'Catalogue star: <strong>Named star</strong>');
  await c.api.loadLiveLookup(c.buttons[0]);
  assert.match(c.knownAs.innerHTML, /Named star/);
  assert.match(c.knownAs.innerHTML, /lookup unavailable/);
});

test('failed responses are retryable and successful responses are reused', async () => {
  const c = client([{ok: false, message: 'Temporary error'}, success([hit])]);
  await c.api.loadLiveLookup(c.buttons[0]); assert.match(c.slot.innerHTML, /Retry lookup/);
  const second = await c.api.loadLiveLookup(c.buttons[0]);
  assert.equal(second.ok, true); await c.api.loadLiveLookup(c.buttons[0]);
  assert.equal(c.requests.length, 2); assert.match(c.slot.innerHTML, /Refresh lookup/);
});
test('refresh bypasses browser and persisted sidecar caches', async () => {
  const c = client([success([hit]), success([])]);
  await c.api.loadLiveLookup(c.buttons[0]); await c.api.loadLiveLookup(c.buttons[0], true);
  assert.equal(c.requests.length, 2); assert.match(c.requests[1], /refresh=1/);
});
test('desktop unavailability is distinct from an empty catalogue result', async () => {
  const c = client([new Error('Connection refused'), success([])]);
  await c.api.loadLiveLookup(c.buttons[0]); assert.match(c.slot.innerHTML, /Unavailable/);
  await c.api.loadLiveLookup(c.buttons[0]); assert.equal(c.requests.length, 2);
});
test('identify others counts only this batch, independently of existing matches', async () => {
  const c = client([success([hit])], 19); const trigger = {disabled: false};
  await c.api.identifyAllVsx(trigger);
  assert.match(c.status.textContent, /Checked 19: 19 matched/);
  assert.doesNotMatch(c.status.textContent, /28/); assert.equal(trigger.disabled, false);
});
test('matches, empty results and failures are reported separately', async () => {
  const c = client([success([hit]), success([]), {ok: false, message: 'Timeout'}], 3);
  await c.api.identifyAllVsx({disabled: false});
  assert.match(c.status.textContent, /1 matched, 1 without a match, 1 failed/);
  assert.match(c.status.textContent, /2 results saved/);
});
test('inspector filters compose frame and sigma limits', () => {
  assert.equal(matchesMarker({peak: '12', frame: '3'}, 10, 3), true);
  assert.equal(matchesMarker({peak: '12', frame: '3'}, 10, 4), false);
  assert.equal(matchesMarker({peak: '8', frame: '3'}, 10, 0), false);
});

function stellariumButton(overrides = {}) {
  const statusEl = {style: {}, innerHTML: ''};
  const attributes = {
    'data-time': '1768514400000',
    'data-utc': '2026-01-15 22:00:00 UTC',
    'data-ra': '83.822080',
    'data-dec': '-5.391110',
    'data-fov': '2.0000',
    'data-lat': '37.98380',
    'data-lng': '23.72750',
    'data-elev': '120.0',
    ...overrides
  };
  const button = {
    textContent: 'Stellarium Local',
    disabled: false,
    getAttribute: k => attributes[k] ?? null,
    setAttribute: (k, v) => { attributes[k] = v; },
    closest: () => ({querySelector: () => statusEl}),
    status: statusEl
  };
  return button;
}

const opaqueStellariumResponse = () => ({type: 'opaque', ok: false, status: 0});
const readableStellariumResponse = (status = 200, reply = 'ok') => ({
  type: 'cors', ok: status >= 200 && status < 300, status, text: async () => reply
});

/** Controls the overall deadline without waiting ten real seconds or changing unrelated lookup timers. */
function stellariumDeadline() {
  let expire;
  const handle = {};
  const deadline = {
    milliseconds: null, cleared: false,
    timers: {
      setTimeout(callback, milliseconds) { expire = callback; deadline.milliseconds = milliseconds; return handle; },
      clearTimeout(value) { assert.equal(value, handle); deadline.cleared = true; }
    },
    expire() { assert.equal(deadline.milliseconds, 10000); expire(); }
  };
  return deadline;
}

test('local Stellarium sends the commands directly and marks opaque responses as unconfirmed', async () => {
  const deadline = stellariumDeadline();
  const c = client([opaqueStellariumResponse()], 1, '', deadline.timers);
  const button = stellariumButton();
  const result = await c.api.openLocalStellarium(button);
  assert.equal(result.ok, true);
  assert.equal(result.confirmed, false);
  assert.equal(c.requests.length, 4);
  assert.equal(c.requests[0], 'http://127.0.0.1:8090/api/location/setlocationfields');
  assert.equal(c.requests[1], 'http://127.0.0.1:8090/api/main/time');
  assert.equal(c.requests[2], 'http://127.0.0.1:8090/api/main/focus');
  assert.equal(c.requests[3], 'http://127.0.0.1:8090/api/main/fov');
  assert.match(button.status.innerHTML, /Commands sent to local Stellarium for 2026-01-15 22:00:00 UTC/);
  assert.match(button.status.innerHTML, /Acceptance is unconfirmed/);
  assert.doesNotMatch(button.status.innerHTML, /Slewed|accepted the commands/);
  assert.equal(button.status.style.color, 'var(--sp-warning)');
  assert.ok(c.requestOptions.every(options => options.signal === c.requestOptions[0].signal));
  assert.equal(c.requestOptions[0].signal.aborted, false);
  assert.equal(deadline.cleared, true);
  assert.equal(button.disabled, false);
  assert.equal(button.textContent, 'Stellarium Local');
});

test('local Stellarium connection failures restore the button and clear the deadline', async () => {
  const deadline = stellariumDeadline();
  const button = stellariumButton();
  const failing = client([new Error('Connection refused')], 1, '', deadline.timers);
  const failResult = await failing.api.openLocalStellarium(button);
  assert.equal(failResult.ok, false);
  assert.match(button.status.innerHTML, /Could not complete the commands for local Stellarium on port 8090/);
  assert.equal(button.disabled, false);
  assert.equal(button.textContent, 'Stellarium Local');
  assert.equal(deadline.cleared, true);
});

test('local Stellarium confirms only readable successful command replies', async () => {
  const c = client([readableStellariumResponse()]);
  const button = stellariumButton({'data-lat': null, 'data-lng': null});
  const result = await c.api.openLocalStellarium(button);
  assert.equal(result.ok, true);
  assert.equal(result.confirmed, true);
  assert.equal(c.requests.length, 3);
  assert.match(button.status.innerHTML, /Stellarium accepted the commands/);
  assert.doesNotMatch(button.status.innerHTML, /Slewed/);
  assert.equal(button.status.style.color, 'var(--sp-success)');
});

test('one opaque command prevents confirmation even when the final reply is readable', async () => {
  const c = client([opaqueStellariumResponse(), readableStellariumResponse()]);
  const button = stellariumButton();
  const result = await c.api.openLocalStellarium(button);
  assert.equal(result.ok, true);
  assert.equal(result.confirmed, false);
  assert.match(button.status.innerHTML, /Acceptance is unconfirmed/);
});

for (const [label, response] of [
  ['HTTP 401', readableStellariumResponse(401)],
  ['HTTP 500', readableStellariumResponse(500)],
  ['an error body with HTTP 200', readableStellariumResponse(200, 'error: invalid parameters')]
]) {
  test('local Stellarium rejects ' + label + ' and sends no remaining commands', async () => {
    const deadline = stellariumDeadline();
    const c = client([opaqueStellariumResponse(), response], 1, '', deadline.timers);
    const button = stellariumButton();
    const result = await c.api.openLocalStellarium(button);
    assert.equal(result.ok, false);
    assert.equal(c.requests.length, 2);
    assert.match(button.status.innerHTML, /Could not complete the commands/);
    assert.doesNotMatch(button.status.innerHTML, /Slewed|Commands sent|accepted the commands/);
    assert.equal(button.disabled, false);
    assert.equal(button.textContent, 'Stellarium Local');
    assert.equal(deadline.cleared, true);
  });
}

test('the overall Stellarium deadline aborts a stalled later command and ignores its late response', async () => {
  const deadline = stellariumDeadline();
  let respond;
  const stalled = () => new Promise(resolve => { respond = resolve; });
  const c = client([opaqueStellariumResponse(), stalled], 1, '', deadline.timers);
  const button = stellariumButton();
  const operation = c.api.openLocalStellarium(button);
  await new Promise(setImmediate);
  assert.equal(c.requests.length, 2);
  assert.equal(button.disabled, true);
  deadline.expire();
  const result = await operation;
  assert.equal(result.ok, false);
  assert.equal(c.requestOptions[1].signal.aborted, true);
  assert.match(button.status.innerHTML, /timed out after 10 seconds/);
  assert.match(button.status.innerHTML, /Some commands may already have been applied/);
  assert.equal(button.disabled, false);
  assert.equal(button.textContent, 'Stellarium Local');
  assert.equal(deadline.cleared, true);

  respond(opaqueStellariumResponse());
  await new Promise(setImmediate);
  assert.equal(c.requests.length, 2);
  assert.match(button.status.innerHTML, /timed out/);
});

test('the Stellarium deadline also bounds reading a stalled response body', async () => {
  const deadline = stellariumDeadline();
  let reading = false;
  const response = readableStellariumResponse();
  response.text = () => { reading = true; return new Promise(() => {}); };
  const c = client([response], 1, '', deadline.timers);
  const button = stellariumButton();
  const operation = c.api.openLocalStellarium(button);
  await new Promise(setImmediate);
  assert.equal(reading, true);
  deadline.expire();
  const result = await operation;
  assert.equal(result.ok, false);
  assert.equal(c.requests.length, 1);
  assert.equal(c.requestOptions[0].signal.aborted, true);
  assert.match(button.status.innerHTML, /timed out after 10 seconds/);
  assert.equal(button.disabled, false);
  assert.equal(deadline.cleared, true);
});
