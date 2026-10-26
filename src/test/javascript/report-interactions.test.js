const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('src/main/resources/eu/startales/spacepixels/report/report-interactions.js', 'utf8');

function createHarness(options = {}) {
  const images = [];
  const eventListeners = {};
  const queryMap = {};

  class MockElement {
    constructor(tagName, id = '', className = '') {
      this.tagName = tagName.toUpperCase();
      this.id = id;
      this.className = className;
      this.classList = {
        contains: cls => (this.className || '').split(' ').includes(cls),
        add: cls => { this.className = (this.className ? this.className + ' ' : '') + cls; },
        remove: cls => { this.className = (this.className || '').replace(cls, '').trim(); },
        toggle: cls => {}
      };
      this.style = {cssText: ''};
      this.dataset = {};
      this.attributes = {};
      this.listeners = {};
      this.children = [];
      this.parentElement = null;
      this.open = true; // details.open
      this.disabled = false;
      this.value = '';
      this.textContent = '';
      this.src = '';
    }

    getAttribute(name) { return this.attributes[name] || null; }
    setAttribute(name, val) { this.attributes[name] = String(val); }
    removeAttribute(name) { delete this.attributes[name]; }
    getBoundingClientRect() { return {height: 50, width: 800, top: 0, left: 0, bottom: 50, right: 800}; }
    addEventListener(event, fn) {
      if (!this.listeners[event]) this.listeners[event] = [];
      this.listeners[event].push(fn);
    }
    removeEventListener(event, fn) {
      if (this.listeners[event]) {
        this.listeners[event] = this.listeners[event].filter(l => l !== fn);
      }
    }
    dispatchEvent(event) {
      const list = this.listeners[event.type || event] || [];
      for (const fn of list) fn(event);
    }
    appendChild(child) {
      this.children.push(child);
      child.parentElement = this;
      return child;
    }
    closest(selector) {
      let cur = this;
      while (cur) {
        if (selector === 'details' && cur.tagName === 'DETAILS') return cur;
        if (selector.includes('sky-orientation-figure') && cur.className.includes('sky-orientation-figure')) return cur;
        if (selector.includes('figure') && cur.tagName === 'FIGURE') return cur;
        if (selector.includes('div') && cur.tagName === 'DIV') return cur;
        cur = cur.parentElement;
      }
      return null;
    }
    querySelector(sel) {
      for (const c of this.children) {
        if (sel === '.animation-gif-link' && c.className && c.className.includes('animation-gif-link')) return c;
        if (sel === 'small, figcaption' && (c.tagName === 'SMALL' || c.tagName === 'FIGCAPTION')) return c;
        const res = c.querySelector(sel);
        if (res) return res;
      }
      return null;
    }
    querySelectorAll(sel) {
      const matches = [];
      for (const c of this.children) {
        if (c.tagName === sel.toUpperCase()) matches.push(c);
        matches.push(...c.querySelectorAll(sel));
      }
      return matches;
    }
    replaceWith(newNode) {
      newNode.parentElement = this.parentElement;
      if (this.parentElement) {
        const idx = this.parentElement.children.indexOf(this);
        if (idx >= 0) this.parentElement.children[idx] = newNode;
      }
      this.replacedWith = newNode;
    }
  }

  const toggleBtn = new MockElement('button', 'report-animation-toggle', 'id-link');
  toggleBtn.textContent = 'Pause animations';
  const speedSelect = new MockElement('select', 'report-animation-speed');
  speedSelect.value = '1';
  const stepPrevBtn = new MockElement('button', 'report-animation-step-prev', 'id-link');
  stepPrevBtn.disabled = true;
  const stepNextBtn = new MockElement('button', 'report-animation-step-next', 'id-link');
  stepNextBtn.disabled = true;

  const animationsScript = new MockElement('script', 'report-animations-data');
  animationsScript.textContent = JSON.stringify(options.metadata || {
    'test.gif': {
      fileName: 'test.gif',
      frameCount: 4,
      columns: 2,
      rows: 2,
      frameWidth: 100,
      frameHeight: 100,
      delayMs: 200
    }
  });

  const observedLoadEntries = [];
  const registeredLoadObservers = [];
  class MockIntersectionObserver {
    constructor(callback) {
      this.callback = callback;
      this.targets = [];
      registeredLoadObservers.push(this);
    }
    observe(target) {
      this.targets.push(target);
      if (options.autoIntersect) {
        this.callback([{target, isIntersecting: true}]);
      }
    }
    disconnect() {
      this.targets = [];
    }
    trigger(target, isIntersecting) {
      this.callback([{target, isIntersecting}]);
    }
  }

  const createdImages = [];
  class MockImage {
    constructor() {
      this._src = '';
      this.onload = null;
      this.onerror = null;
      createdImages.push(this);
    }
    get src() { return this._src; }
    set src(val) {
      this._src = val;
      if (options.autoResolveImages !== false) {
        process.nextTick(() => {
          if (options.shouldFailImage && options.shouldFailImage(val)) {
            if (this.onerror) this.onerror();
          } else {
            if (this.onload) this.onload();
          }
        });
      }
    }
  }

  const doc = {
    getElementById(id) {
      if (id === 'report-animation-toggle') return toggleBtn;
      if (id === 'report-animation-speed') return speedSelect;
      if (id === 'report-animation-step-prev') return stepPrevBtn;
      if (id === 'report-animation-step-next') return stepNextBtn;
      if (id === 'report-animations-data') return animationsScript;
      return null;
    },
    querySelector(sel) {
      if (sel === '.report-toolbar') return new MockElement('div', '', 'report-toolbar');
      return null;
    },
    querySelectorAll(sel) {
      if (sel === 'img[src$=".gif"]') return options.gifs || [];
      if (sel === 'img') return options.gifs || [];
      if (sel === '[data-report-category]') return [];
      if (sel === 'svg[data-report-chart]') return [];
      return [];
    },
    createElement(tag) {
      const el = new MockElement(tag);
      if (tag === 'canvas') {
        el.getContext = () => ({
          clearRect() {},
          drawImage() {}
        });
      }
      return el;
    },
    documentElement: {style: {setProperty() {}}},
    dispatchEvent(e) {
      if (!eventListeners[e.type]) eventListeners[e.type] = [];
      for (const fn of eventListeners[e.type]) fn(e);
    },
    addEventListener(type, fn) {
      if (!eventListeners[type]) eventListeners[type] = [];
      eventListeners[type].push(fn);
    }
  };

  const matchMedia = query => ({
    matches: query.includes('prefers-reduced-motion') && Boolean(options.reducedMotion),
    addEventListener() {}
  });

  const sandbox = {
    document: doc,
    window: {location: {hash: ''}},
    addEventListener: () => {},
    removeEventListener: () => {},
    Image: MockImage,
    IntersectionObserver: MockIntersectionObserver,
    ResizeObserver: class { observe() {} },
    matchMedia: matchMedia,
    performance: { now: () => 1000 },
    requestAnimationFrame: fn => {},
    CustomEvent: class { constructor(type, init) { this.type = type; this.detail = init?.detail; } },
    module: {exports: {}}
  };

  return {sandbox, doc, toggleBtn, speedSelect, stepPrevBtn, stepNextBtn, createdImages, registeredLoadObservers, MockElement};
}

test('deferred loading: closed details defers sheet image creation until opened', async () => {
  const h = createHarness({autoIntersect: false});
  const details = new h.MockElement('details');
  details.open = false; // Closed!
  const img = new h.MockElement('img');
  img.setAttribute('src', 'test.gif');
  img.src = 'test.gif';
  details.appendChild(img);

  h.sandbox.document.querySelectorAll = sel => sel === 'img[src$=".gif"]' || sel === 'img' ? [img] : [];

  vm.createContext(h.sandbox);
  vm.runInContext(source, h.sandbox);

  // Since details is closed, sheet loading must NOT have started
  assert.equal(h.createdImages.length, 0, 'No Image instance must be created while details is closed');

  // Toggle details open
  details.open = true;
  details.dispatchEvent('toggle');

  // Load observer should now observe img
  const observer = h.registeredLoadObservers[0];
  assert.ok(observer, 'Observer must be registered on details open');
  observer.trigger(img, true);

  await new Promise(r => setTimeout(r, 10));
  assert.equal(h.createdImages.length, 1, 'Sheet image load must start after details open and intersection');
  assert.equal(h.createdImages[0].src, 'test.gif.sheet.png');
});

test('failure while paused immediately falls back to .poster.png, not playing GIF', async () => {
  const h = createHarness({
    autoIntersect: true,
    shouldFailImage: url => url.endsWith('.sheet.png') // trigger sheetImg.onerror
  });
  const img = new h.MockElement('img');
  img.setAttribute('src', 'failing.gif');
  img.src = 'failing.gif';
  h.sandbox.document.querySelectorAll = sel => sel === 'img[src$=".gif"]' || sel === 'img' ? [img] : [];

  vm.createContext(h.sandbox);
  vm.runInContext(source, h.sandbox);

  // Click pause animations
  h.toggleBtn.dispatchEvent('click');
  assert.equal(h.sandbox.SpacePixelsAnimations.getPaused(), true);

  await new Promise(r => setTimeout(r, 10));

  // The fallback image must be paused at .poster.png!
  assert.equal(img.src, 'failing.gif.poster.png');
});

test('reduced motion matches initially: pauses animations and keeps poster on failure', async () => {
  const h = createHarness({
    reducedMotion: true,
    autoIntersect: true,
    shouldFailImage: url => true // sheet fails
  });
  const img = new h.MockElement('img');
  img.setAttribute('src', 'reduced.gif');
  img.src = 'reduced.gif';
  h.sandbox.document.querySelectorAll = sel => sel === 'img[src$=".gif"]' || sel === 'img' ? [img] : [];

  vm.createContext(h.sandbox);
  vm.runInContext(source, h.sandbox);

  // Under reduced motion:
  assert.equal(h.sandbox.SpacePixelsAnimations.getPaused(), true);
  assert.equal(h.toggleBtn.textContent, 'Play animations');
  assert.equal(h.stepPrevBtn.disabled, false);
  assert.equal(h.stepNextBtn.disabled, false);

  await new Promise(r => setTimeout(r, 10));
  // On sheet error under reduced motion, image must display .poster.png
  assert.equal(img.src, 'reduced.gif.poster.png');
});

test('speed control and frame stepping when paused', async () => {
  const h = createHarness({autoIntersect: true});
  const img = new h.MockElement('img');
  img.setAttribute('src', 'test.gif');
  img.src = 'test.gif';
  h.sandbox.document.querySelectorAll = sel => sel === 'img[src$=".gif"]' || sel === 'img' ? [img] : [];

  vm.createContext(h.sandbox);
  vm.runInContext(source, h.sandbox);

  await new Promise(r => setTimeout(r, 10));

  const players = h.sandbox.SpacePixelsAnimations.getActivePlayers();
  assert.equal(players.length, 1);
  const player = players[0];
  assert.equal(player.currentFrame, 0);

  // Pause animations
  h.toggleBtn.dispatchEvent('click');
  assert.equal(h.sandbox.SpacePixelsAnimations.getPaused(), true);
  assert.equal(h.stepPrevBtn.disabled, false);
  assert.equal(h.stepNextBtn.disabled, false);

  // Step forward
  h.stepNextBtn.dispatchEvent('click');
  assert.equal(player.currentFrame, 1);

  h.stepNextBtn.dispatchEvent('click');
  assert.equal(player.currentFrame, 2);

  // Step backward
  h.stepPrevBtn.dispatchEvent('click');
  assert.equal(player.currentFrame, 1);

  // Adjust speed
  h.speedSelect.value = '2';
  h.speedSelect.dispatchEvent('change');
  assert.equal(h.sandbox.SpacePixelsAnimations.getSpeed(), 2.0);
});
