const {test} = require('node:test');
const assert = require('node:assert/strict');
const {decodeSamples, displayLevels, tone, sample} = require('../../main/resources/eu/startales/spacepixels/report/faint-mover-review.js');

test('offline binary assets preserve unsigned 16-bit intensities and signed residuals', () => {
  const source = Buffer.alloc(8);
  [0, 9, 32768, 65535].forEach((v, i) => source.writeUInt16LE(v, i * 2));
  assert.deepEqual(Array.from(decodeSamples(source.toString('base64'), false)), [0, 9, 32768, 65535]);
  const residual = Buffer.alloc(12);
  [-2.5, 0, 3.125].forEach((v, i) => residual.writeFloatLE(v, i * 4));
  assert.deepEqual(Array.from(decodeSamples(residual.toString('base64'), true)), [-2.5, 0, 3.125]);
  assert.throws(() => decodeSamples('AA==', false), /length/);
});

test('shared levels use captured report preferences and do not adapt to each frame', () => {
  const stats = {sky: 1000, noise: 10, maximum: 1500};
  assert.deepEqual(displayLevels(stats, 2, 15), [980, 1150]);
  assert.deepEqual(displayLevels(stats, 2, 6), [980, 1060]);
  assert.deepEqual(displayLevels({sky: 100, noise: 0, maximum: 100}, 3, 12), [100, 100]);
});

test('stretch recovers differences from native source values, with explicit clipping', () => {
  const native = [1000, 1001, 1002, 1100];
  const normal = native.map(v => tone(v, 980, 1300, 0));
  const enhanced = native.map(v => tone(v, 980, 1060, 5));
  assert.ok(enhanced[2] - enhanced[0] > normal[2] - normal[0]);
  assert.equal(tone(900, 995, 1040, 20), 0);
  assert.equal(tone(1100, 995, 1040, 20), 255);
  assert.ok(Number.isFinite(tone(100, 100, 100, 20)));
});

test('motion-centred bilinear sampling preserves signs and never interpolates fill as signal', () => {
  const pixels = new Float32Array([-2, 2, -4, 4]);
  assert.equal(sample(pixels, 0, 2, 2, 0.5, 0.5, null), 0);
  assert.equal(sample(pixels, 0, 2, 2, -0.1, 0, null), null);
  assert.equal(sample(new Uint16Array([0, 1000, 1000, 1000]), 0, 2, 2, 0.5, 0.5, 8), null);
  assert.equal(sample(new Uint16Array([900, 1000, 1100, 1200]), 0, 2, 2, 0.5, 0.5, 8), 1050);
  assert.equal(sample(new Uint16Array([1000, 0, 0, 0]), 0, 2, 2, 0, 0, 8), 1000);
});
