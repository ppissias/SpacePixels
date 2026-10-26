(function (root) {
  'use strict';
  function matchesMarker(data, sigma, frame) {
    return (sigma <= 0 || Number(data.peak) >= sigma) && (!frame || Number(data.frame) === frame);
  }
  if (typeof module !== 'undefined' && module.exports) module.exports = {matchesMarker};
  const doc = root.document;
  if (!doc) return;
  const toolbar = doc.querySelector('.report-toolbar');
  function sizeToolbar() {
    if (toolbar) doc.documentElement.style.setProperty('--report-toolbar-height', toolbar.getBoundingClientRect().height + 'px');
  }
  if (toolbar && root.ResizeObserver) new root.ResizeObserver(sizeToolbar).observe(toolbar);
  root.addEventListener('resize', sizeToolbar);
  sizeToolbar();
  const select = doc.getElementById('report-finding-select');
  function syncFinding() {
    if (!select) return;
    const anchor = root.location.hash.substring(1);
    const option = Array.from(select.options).find(o => o.value === anchor);
    if (option) select.value = anchor;
    const previous = doc.getElementById('report-previous');
    const next = doc.getElementById('report-next');
    if (previous) previous.disabled = select.options.length < 2 || select.selectedIndex === 1;
    if (next) next.disabled = select.options.length < 2 || select.selectedIndex === select.options.length - 1;
  }
  function jump() {
    if (select && select.value) {
      const target = doc.getElementById(select.value);
      for (let node = target; node; node = node.parentElement) if (node.tagName === 'DETAILS') node.open = true;
      root.location.hash = select.value;
      if (target) target.scrollIntoView({block: 'start'});
      syncFinding();
    }
  }
  root.addEventListener('hashchange', syncFinding);
  syncFinding();
  if (select) select.addEventListener('change', jump);
  for (const [id, step] of [['report-previous', -1], ['report-next', 1]]) {
    const button = doc.getElementById(id);
    if (button) button.addEventListener('click', () => {
      if (!select || select.options.length < 2) return;
      select.selectedIndex = Math.max(1, Math.min(select.options.length - 1, select.selectedIndex + step));
      jump();
    });
  }

  // Animation metadata and controls
  const animationsDataEl = doc.getElementById('report-animations-data');
  let animationsMeta = {};
  if (animationsDataEl) {
    try {
      animationsMeta = JSON.parse(animationsDataEl.textContent || '{}') || {};
    } catch (e) {
      animationsMeta = {};
    }
  }

  const speedSelect = doc.getElementById('report-animation-speed');
  let currentSpeed = speedSelect ? (parseFloat(speedSelect.value) || 1.0) : 1.0;
  if (speedSelect) {
    speedSelect.addEventListener('change', () => {
      currentSpeed = parseFloat(speedSelect.value) || 1.0;
      doc.dispatchEvent(new CustomEvent('spacepixels-animation-speed', {detail: {speed: currentSpeed}}));
    });
  }

  const stepPrevBtn = doc.getElementById('report-animation-step-prev');
  const stepNextBtn = doc.getElementById('report-animation-step-next');

  const animations = Array.from(doc.querySelectorAll('img[src$=".gif"]'));
  const toggle = doc.getElementById('report-animation-toggle');
  let paused = false;
  const activePlayers = [];
  const fallbackGifs = [];

  function pauseAnimations(value) {
    paused = value;
    if (toggle) {
      toggle.textContent = paused ? 'Play animations' : 'Pause animations';
      toggle.setAttribute('aria-pressed', String(paused));
    }
    if (stepPrevBtn) stepPrevBtn.disabled = !paused;
    if (stepNextBtn) stepNextBtn.disabled = !paused;

    for (const img of fallbackGifs) {
      if (!img.dataset.animationSource) img.dataset.animationSource = img.getAttribute('src');
      img.src = paused ? img.dataset.animationSource + '.poster.png' : img.dataset.animationSource;
    }

    if (!paused) {
      const now = root.performance ? root.performance.now() : Date.now();
      for (const p of activePlayers) {
        p.lastFrameTime = now;
      }
    }
    doc.dispatchEvent(new CustomEvent('spacepixels-animation-state', {detail: {paused, speed: currentSpeed}}));
  }
  if (toggle) toggle.addEventListener('click', () => pauseAnimations(!paused));

  if (stepPrevBtn) {
    stepPrevBtn.addEventListener('click', () => {
      if (!paused) return;
      for (const p of activePlayers) p.step(-1);
    });
  }
  if (stepNextBtn) {
    stepNextBtn.addEventListener('click', () => {
      if (!paused) return;
      for (const p of activePlayers) p.step(1);
    });
  }

  for (const img of animations) {
    const rawSrc = img.getAttribute('src');
    if (!rawSrc) continue;
    const fileName = rawSrc.split('/').pop().split('?')[0];
    const meta = animationsMeta[fileName] || animationsMeta[rawSrc];

    // Ensure a clear GIF download link exists near the animation
    const container = img.closest('.sky-orientation-figure, figure, div');
    if (container) {
      const hasGifLink = container.querySelector('.animation-gif-link') ||
                         (container.parentElement && container.parentElement.querySelector('.animation-gif-link'));
      if (!hasGifLink) {
        const link = doc.createElement('a');
        link.href = rawSrc;
        link.target = '_blank';
        link.className = 'animation-gif-link';
        link.textContent = 'GIF';
        link.title = 'Open or save animated GIF';
        const caption = container.parentElement ? container.parentElement.querySelector('small, figcaption') : null;
        if (caption) {
          caption.appendChild(link);
        } else {
          container.appendChild(link);
        }
      }
    }

    if (!meta || !meta.columns || !meta.rows || !meta.frameCount) {
      fallbackGifs.push(img);
      continue;
    }

    const sheetUrl = rawSrc + '.sheet.png';
    const sheetImg = new root.Image();
    sheetImg.onload = function () {
      const canvas = doc.createElement('canvas');
      canvas.width = meta.frameWidth;
      canvas.height = meta.frameHeight;
      canvas.className = 'animation-sheet-canvas' + (img.className ? ' ' + img.className : '');
      if (img.style.cssText) canvas.style.cssText = img.style.cssText;
      if (img.id) canvas.id = img.id;
      canvas.setAttribute('role', 'img');
      const alt = img.getAttribute('alt') || 'Animation';
      canvas.setAttribute('aria-label', alt);
      if (img.title) canvas.title = img.title;

      const ctx = canvas.getContext('2d');
      const player = {
        canvas: canvas,
        ctx: ctx,
        sheet: sheetImg,
        meta: meta,
        currentFrame: 0,
        lastFrameTime: root.performance ? root.performance.now() : Date.now(),
        visible: true,
        drawFrame: function (frameIndex) {
          this.currentFrame = ((frameIndex % meta.frameCount) + meta.frameCount) % meta.frameCount;
          const col = this.currentFrame % meta.columns;
          const row = Math.floor(this.currentFrame / meta.columns);
          const sx = col * meta.frameWidth;
          const sy = row * meta.frameHeight;
          this.ctx.clearRect(0, 0, meta.frameWidth, meta.frameHeight);
          this.ctx.drawImage(this.sheet, sx, sy, meta.frameWidth, meta.frameHeight, 0, 0, meta.frameWidth, meta.frameHeight);
        },
        step: function (delta) {
          this.drawFrame(this.currentFrame + delta);
        }
      };

      player.drawFrame(0);
      img.replaceWith(canvas);
      activePlayers.push(player);

      if (root.IntersectionObserver) {
        player.visible = false;
        const observer = new root.IntersectionObserver(entries => {
          for (const entry of entries) {
            if (entry.target === canvas) {
              player.visible = entry.isIntersecting;
              if (player.visible) {
                player.lastFrameTime = root.performance ? root.performance.now() : Date.now();
              }
            }
          }
        });
        observer.observe(canvas);
      }
    };
    sheetImg.onerror = function () {
      fallbackGifs.push(img);
    };
    sheetImg.src = sheetUrl;
  }

  function tick(timestamp) {
    if (!paused) {
      const now = timestamp || (root.performance ? root.performance.now() : Date.now());
      for (const p of activePlayers) {
        if (!p.visible) continue;
        const effectiveDelay = p.meta.delayMs / currentSpeed;
        const elapsed = now - p.lastFrameTime;
        if (elapsed >= effectiveDelay) {
          const frames = Math.floor(elapsed / effectiveDelay);
          p.lastFrameTime = now - (elapsed % effectiveDelay);
          p.step(frames);
        }
      }
    }
    if (root.requestAnimationFrame) {
      root.requestAnimationFrame(tick);
    }
  }
  if (root.requestAnimationFrame) {
    root.requestAnimationFrame(tick);
  }

  for (const img of doc.querySelectorAll('img')) {
    if (!img.classList.contains('sp-base')) img.loading = 'lazy';
    img.decoding = 'async';
  }
  if (root.matchMedia) {
    const motionQuery = root.matchMedia('(prefers-reduced-motion: reduce)');
    if (motionQuery.matches) pauseAnimations(true);
    if (motionQuery.addEventListener) {
      motionQuery.addEventListener('change', e => {
        if (e.matches) pauseAnimations(true);
      });
    }
  }

  // Per-category switches operate on the vector layers, leaving the measured image unchanged.
  for (const button of doc.querySelectorAll('[data-report-category]')) {
    button.addEventListener('click', () => {
      const show = button.getAttribute('aria-pressed') !== 'true';
      button.setAttribute('aria-pressed', String(show));
      const map = button.closest('.report-map');
      const category = button.dataset.reportCategory;
      // A category click also enables its parent layer if the broad Detections/Variables switch was off.
      const layerName = category === 'V' ? 'variables' : 'detections';
      const layerToggle = map.querySelector('.sp-toggle[data-layer="' + layerName + '"]');
      if (show && layerToggle && !layerToggle.classList.contains('active')) layerToggle.click();
      if (layerToggle && category === 'V') {
        layerToggle.classList.toggle('active', show);
        layerToggle.setAttribute('aria-pressed', String(show));
      }
      for (const layer of map.querySelectorAll('[data-category="' + category + '"]')) layer.style.display = show ? '' : 'none';
    });
  }

  const inspector = doc.getElementById('unclassified-transient-inspector');
  if (inspector) {
    const markers = Array.from(inspector.querySelectorAll('.unclassified-marker'));
    const sigma = inspector.querySelector('#unclassified-min-sigma');
    const frame = inspector.querySelector('#unclassified-frame-filter');
    const count = inspector.querySelector('#unclassified-visible-count');
    function filter() {
      let visible = 0;
      for (const marker of markers) {
        const show = matchesMarker(marker.dataset, Number(sigma.value), Number(frame.value));
        marker.parentElement.style.display = show ? '' : 'none';
        if (show) visible++;
      }
      if (count) count.textContent = visible + ' of ' + markers.length + ' detections shown';
    }
    if (sigma && frame) {
      sigma.addEventListener('input', filter); frame.addEventListener('change', filter); filter();
      inspector.addEventListener('keydown', event => {
        if (!['ArrowLeft', 'ArrowRight'].includes(event.key) || !event.target.classList.contains('unclassified-marker')) return;
        const visible = markers.filter(m => m.parentElement.style.display !== 'none');
        const index = visible.indexOf(event.target) + (event.key === 'ArrowRight' ? 1 : -1);
        if (visible[index]) { event.preventDefault(); visible[index].focus(); visible[index].dispatchEvent(new MouseEvent('click', {bubbles: true})); }
      });
    }
  }

  // SVG zoom keeps the native data-point tooltips and includes keyboard-accessible reset controls.
  for (const svg of doc.querySelectorAll('svg[data-report-chart]')) {
    const initial = svg.getAttribute('viewBox').split(/\s+/).map(Number);
    let view = initial.slice();
    const controls = doc.createElement('div'); controls.className = 'chart-controls';
    for (const [label, factor] of [['Zoom in', .7], ['Zoom out', 1 / .7], ['Reset', 0]]) {
      const button = doc.createElement('button'); button.type = 'button'; button.className = 'id-link'; button.textContent = label;
      button.addEventListener('click', () => {
        if (!factor) view = initial.slice();
        else {
          const width = Math.max(initial[2] / 8, Math.min(initial[2], view[2] * factor));
          view = [Math.max(0, Math.min(initial[2] - width, view[0] + (view[2] - width) / 2)), 0, width, initial[3]];
        }
        svg.setAttribute('viewBox', view.join(' '));
      }); controls.appendChild(button);
    }
    svg.before(controls);
    let drag = null;
    svg.addEventListener('pointerdown', e => { if (view[2] < initial[2]) { drag = [e.clientX, view[0]]; svg.setPointerCapture(e.pointerId); } });
    svg.addEventListener('pointermove', e => {
      if (!drag) return;
      view[0] = Math.max(0, Math.min(initial[2] - view[2], drag[1] - (e.clientX - drag[0]) * view[2] / svg.getBoundingClientRect().width));
      svg.setAttribute('viewBox', view.join(' '));
    });
    svg.addEventListener('pointerup', () => { drag = null; });
    svg.addEventListener('pointercancel', () => { drag = null; });
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
