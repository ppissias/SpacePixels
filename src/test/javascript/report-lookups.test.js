const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/eu/startales/spacepixels/util/reporting/report-lookups.js', 'utf8');
const {matchesMarker} = require('../../main/resources/eu/startales/spacepixels/util/reporting/report-interactions.js');

function client(responses, count = 1) {
  const slot = {innerHTML: ''}, status = {textContent: ''}, requests = [], buttons = [];
  for (let i = 0; i < count; i++) {
    const attributes = {'data-provider': 'vsx', 'data-target': 'target-' + i, 'data-slot-id': 'slot', 'data-sidecar-file': 'v' + i + '.json'};
    buttons.push({textContent: 'Look up', getAttribute: key => attributes[key], setAttribute: (key, value) => attributes[key] = value});
  }
  const document = {
    getElementById: id => id === 'slot' ? slot : null, addEventListener() {},
    querySelector: () => status,
    querySelectorAll: query => query === '.render-live-link' || query === "button[data-identify-all='1']" ? buttons
      : query === '[data-known-as-sidecar] strong' ? Array(9).fill({}) : []
  };
  const sandbox = {document, window: {location: {href: 'file:///C:/scratch/detection_report.html'}},
    __SPACEPIXELS_PROXY_URL__: 'http://127.0.0.1:47831', __SPACEPIXELS_CACHE_ID__: 'cache',
    SpacePixelsCatalogueComparison: {initialize() {}, update() {}},
    module: {exports: {}}, AbortController, setTimeout, clearTimeout, console,
    fetch: async url => {
      requests.push(url);
      const response = responses[Math.min(requests.length - 1, responses.length - 1)];
      if (response instanceof Error) throw response;
      return {json: async () => response};
    }};
  vm.createContext(sandbox); vm.runInContext(source, sandbox);
  return {api: sandbox.module.exports, buttons, slot, status, requests};
}
const success = matches => ({ok: true, provider: 'vsx', persisted: true, normalized: {matches}, payload: {}});
const hit = {name: 'Example', type: 'EA', periodDays: 1, separationArcsec: 1};

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
