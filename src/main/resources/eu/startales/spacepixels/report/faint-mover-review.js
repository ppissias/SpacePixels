(function (root) {
  'use strict';

  // Binary assets contain original unsigned source samples and processed residual floats, both little endian.
  function decodeSamples(encoded, floatSamples) {
    const bytes = root.atob(encoded);
    const buffer = new ArrayBuffer(bytes.length);
    const view = new DataView(buffer);
    for (let i = 0; i < bytes.length; i++) view.setUint8(i, bytes.charCodeAt(i));
    const stride = floatSamples ? 4 : 2;
    if (bytes.length % stride) throw new Error('Invalid review sample length');
    const samples = floatSamples ? new Float32Array(bytes.length / stride) : new Uint16Array(bytes.length / stride);
    for (let i = 0; i < samples.length; i++) samples[i] = floatSamples ? view.getFloat32(i * stride, true) : view.getUint16(i * stride, true);
    return samples;
  }

  function displayLevels(stats, blackSigma, whiteSigma) {
    return [Math.max(0, stats.sky - blackSigma * stats.noise),
      stats.noise > 0 ? Math.min(stats.maximum, stats.sky + whiteSigma * stats.noise) : stats.maximum];
  }

  function tone(value, black, white, strength) {
    const range = white > black ? white - black : 1;
    const unit = Math.max(0, Math.min(1, (value - black) / range));
    const gray = strength ? Math.asinh(strength * unit) / Math.asinh(strength) : Math.sqrt(unit);
    // Java's source GIF draws a linear-gray BufferedImage into sRGB. Keep Normal on that same display curve.
    const srgb = gray <= 0.0031308 ? 12.92 * gray : 1.055 * Math.pow(gray, 1 / 2.4) - 0.055;
    return Math.round(255 * Math.max(0, Math.min(1, srgb)));
  }

  function sample(pixels, offset, width, height, x, y, fillMaximum) {
    if (x < 0 || y < 0 || x > width - 1 || y > height - 1) return null;
    const x0 = Math.floor(x), y0 = Math.floor(y), x1 = Math.min(width - 1, x0 + 1), y1 = Math.min(height - 1, y0 + 1);
    if (x === x0 && y === y0) {
      const value = pixels[offset + y0 * width + x0];
      return Number.isFinite(value) && (fillMaximum == null || value > fillMaximum) ? value : null;
    }
    const values = [pixels[offset + y0 * width + x0], pixels[offset + y0 * width + x1],
      pixels[offset + y1 * width + x0], pixels[offset + y1 * width + x1]];
    const dx = x - x0, dy = y - y0;
    const weights = [(1 - dx) * (1 - dy), dx * (1 - dy), (1 - dx) * dy, dx * dy];
    let sum = 0;
    for (let i = 0; i < 4; i++) if (weights[i] > 0) {
      if (!Number.isFinite(values[i]) || (fillMaximum != null && values[i] <= fillMaximum)) return null;
      sum += weights[i] * values[i];
    }
    return sum;
  }

  // Native pixels map one image pixel to one CSS pixel; Fit and its multiples keep the existing panel size.
  function displayScale(zoom, width, height) {
    return zoom === 'native' ? 1 : 318 * Number(zoom) / Math.max(width, height);
  }

  const api = {decodeSamples, displayLevels, tone, sample, displayScale};
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  const doc = root.document;
  if (!doc) return;
  const animations = root.SpacePixelsAnimations;
  const viewers = new Map();

  function showFailure(element, reason) {
    element.querySelector('.fm-frame-label').textContent = 'Interactive review unavailable: ' + reason + '. The source GIF and stack remain available.';
  }

  function start(element) {
    if (element.dataset.fmLoading) return;
    element.dataset.fmLoading = 'true';
    const script = doc.createElement('script');
    script.src = element.dataset.fmSrc;
    script.onerror = () => showFailure(element, 'review asset could not be loaded');
    doc.body.appendChild(script);
  }

  function accept(id, data) {
    const element = viewers.get(id);
    if (!element) return;
    try {
      if (data.version !== 1 || !(data.width > 0 && data.width <= 256) || !(data.height > 0 && data.height <= 256)
          || !Array.isArray(data.frames) || !data.frames.length)
        throw new Error('Unsupported review data');
      data.source = decodeSamples(data.source, false);
      data.residual = data.residual ? decodeSamples(data.residual, true) : null;
      const length = data.width * data.height * data.frames.length;
      if (data.source.length !== length || (data.residual && data.residual.length !== length)) throw new Error('Incomplete review pixels');
      install(element, data);
    } catch (error) { showFailure(element, error.message); }
  }
  root.SpacePixelsFaintMovers = {accept};

  function install(element, data) {
    const query = selector => element.querySelector(selector);
    const controls = query('.fm-controls'), sourceCanvas = query('[data-fm-source]'), residualCanvas = query('[data-fm-residual]');
    const frameControl = query('[data-fm-frame]'), stretch = query('[data-fm-stretch]'), black = query('[data-fm-black]'), white = query('[data-fm-white]');
    const strength = query('[data-fm-strength]'), zoom = query('[data-fm-zoom]'), annotations = query('[data-fm-annotations]');
    const centred = query('[data-fm-centred]'), residualRange = query('[data-fm-residual-range]');
    let frameIndex = 0;
    for (const canvas of [sourceCanvas, residualCanvas].filter(Boolean)) { canvas.width = data.width; canvas.height = data.height; }

    function drawCanvas(canvas, pixels, residual, levels, amount) {
      if (!canvas || !pixels) return;
      const context = canvas.getContext('2d'), image = context.createImageData(data.width, data.height);
      const frame = data.frames[frameIndex], offset = frameIndex * data.width * data.height;
      const dx = centred.checked ? frame.x - (data.width - 1) / 2 : 0;
      const dy = centred.checked ? frame.y - (data.height - 1) / 2 : 0;
      // A path-following cutout has its own origin per frame; place the whole path relative to this frame's cutout.
      const px = f => f.x + (data.followsPath ? f.originX - frame.originX : 0) - dx;
      const py = f => f.y + (data.followsPath ? f.originY - frame.originY : 0) - dy;
      for (let y = 0; y < data.height; y++) for (let x = 0; x < data.width; x++) {
        const value = sample(pixels, offset, data.width, data.height, x + dx, y + dy, residual ? null : data.fillMaximum);
        let gray = 0;
        if (value != null) {
          if (residual) {
            const bound = Number(residualRange.value);
            const signed = Math.max(-1, Math.min(1, value / bound));
            gray = Math.round(255 * (0.5 + 0.5 * Math.asinh(3 * signed) / Math.asinh(3)));
          } else gray = tone(value, levels[0], levels[1], amount);
        }
        const index = (y * data.width + x) * 4;
        image.data[index] = image.data[index + 1] = image.data[index + 2] = gray;
        image.data[index + 3] = 255;
      }
      context.putImageData(image, 0, 0);
      if (annotations.checked) {
        context.strokeStyle = '#50e1d2'; context.lineWidth = 0.65; context.setLineDash([2, 2]); context.beginPath();
        const last = data.frames[data.frames.length - 1];
        context.moveTo(px(data.frames[0]), py(data.frames[0]));
        context.lineTo(px(last), py(last));
        context.stroke(); context.setLineDash([]); context.beginPath();
        context.arc(frame.x - dx, frame.y - dy, 6, 0, Math.PI * 2); context.stroke();
      }
      const factor = displayScale(zoom.value, data.width, data.height);
      canvas.style.width = (factor * data.width) + 'px'; canvas.style.height = (factor * data.height) + 'px';
    }

    function signalPlot() {
      const svg = query('[data-fm-signal]');
      if (!svg) return;
      const range = Math.max(1, ...data.frames.map(f => Math.abs(f.signal || 0)));
      let content = '<path d="M24 75H616" stroke="#777"/><text x="24" y="146" fill="#bbb" font-size="11">Searched frame order · cyan unmasked / red masked · yellow current frame</text>';
      for (let i = 0; i < data.frames.length; i++) {
        const f = data.frames[i], x = 24 + 592 * i / Math.max(1, data.frames.length - 1), y = 75 - 60 * f.signal / range;
        const color = i === frameIndex ? '#ffdf6b' : f.unmasked ? '#50e1d2' : '#ff7777';
        content += '<circle cx="' + x + '" cy="' + y + '" r="' + (i === frameIndex ? 4 : 2.5) + '" fill="' + color + '"><title>Frame ' + f.number + ': ' + f.signal.toFixed(4) + (f.unmasked ? ' · unmasked' : ' · masked') + '</title></circle>';
      }
      content += '<text x="2" y="15" fill="#bbb" font-size="10">+' + range.toFixed(2) + '</text><text x="2" y="132" fill="#bbb" font-size="10">−' + range.toFixed(2) + '</text>';
      svg.innerHTML = content;
    }

    function draw() {
      frameControl.value = String(frameIndex);
      const normal = stretch.value === 'normal', custom = stretch.value === 'custom';
      black.disabled = white.disabled = strength.disabled = !custom;
      const blackSigma = normal ? data.normalBlackSigma : custom ? Number(black.value) : 2;
      const whiteSigma = normal ? data.normalWhiteSigma : custom ? Number(white.value) : 6;
      const amount = normal ? 0 : custom ? Number(strength.value) : 5;
      const levels = displayLevels(data.stats, blackSigma, whiteSigma), frame = data.frames[frameIndex];
      drawCanvas(sourceCanvas, data.source, false, levels, amount);
      drawCanvas(residualCanvas, data.residual, true, levels, amount);
      const stackScale = displayScale(zoom.value, data.stackWidth, data.stackHeight);
      element.style.setProperty('--fm-stack-width', (data.stackWidth * stackScale) + 'px');
      element.style.setProperty('--fm-stack-height', (data.stackHeight * stackScale) + 'px');
      element.classList.toggle('fm-no-annotations', !annotations.checked);
      query('.fm-frame-label').textContent = 'Frame ' + frame.number + ' · midpoint ' + frame.midpoint + ' · ' + (frameIndex + 1) + ' / ' + data.frames.length
          + (centred.checked ? ' · motion-centred (bilinear resampling)'
            : data.followsPath ? ' · cutout follows the fitted path' : ' · fixed registered coordinates')
          + (data.residual ? ' · scoring-path centre ' + (frame.unmasked ? 'unmasked' : 'masked') : '');
      let low = 0, high = 0, valid = 0;
      const start = frameIndex * data.width * data.height;
      for (let i = start; i < start + data.width * data.height; i++) if (data.source[i] > data.fillMaximum) {
        valid++; if (data.source[i] <= levels[0]) low++; if (data.source[i] >= levels[1]) high++;
      }
      query('.fm-mapping').textContent = (normal ? 'Normal · square-root' : 'Asinh strength ' + amount) + ' · shared levels '
          + levels[0].toFixed(2) + '–' + levels[1].toFixed(2) + ' (sky −' + blackSigma + 'σ / +' + whiteSigma + 'σ). '
          + 'Current source frame clipped: ' + (valid ? 100 * low / valid : 0).toFixed(1) + '% black / '
          + (valid ? 100 * high / valid : 0).toFixed(1) + '% white; fill excluded. '
          + (data.residual ? 'Residual mapping: signed asinh, ±' + residualRange.value + ' processed units; fixed across frames. ' : '')
          + (zoom.value === 'native' ? 'Native pixels: one image pixel per CSS pixel. ' : 'Fit zoom enlarges each crop to the panel. ')
          + 'Enhancement changes display only; strong stretches also expose noise and artifacts.';
      signalPlot();
    }

    function centreViewports() {
      for (const viewport of element.querySelectorAll('.fm-viewport')) {
        viewport.scrollLeft = Math.max(0, (viewport.scrollWidth - viewport.clientWidth) / 2);
        viewport.scrollTop = Math.max(0, (viewport.scrollHeight - viewport.clientHeight) / 2);
      }
    }

    // Source and residual cutouts share the same geometry, so a pan preserves their registered alignment.
    if (residualCanvas) for (const [from, to] of [[sourceCanvas.parentElement, residualCanvas.parentElement],
      [residualCanvas.parentElement, sourceCanvas.parentElement]]) from.addEventListener('scroll', () => {
      if (to.scrollLeft !== from.scrollLeft) to.scrollLeft = from.scrollLeft;
      if (to.scrollTop !== from.scrollTop) to.scrollTop = from.scrollTop;
    });

    const player = {
      meta: {delayMs: Math.max(10, data.delayMs)}, lastFrameTime: root.performance.now(), visible: true,
      step(delta) { frameIndex = ((frameIndex + delta) % data.frames.length + data.frames.length) % data.frames.length; draw(); }
    };
    animations.registerPlayer(player);
    const pause = query('[data-fm-action=pause]');
    const syncPause = () => { pause.textContent = animations.getPaused() ? 'Play' : 'Pause'; };
    pause.addEventListener('click', () => animations.pauseAnimations(!animations.getPaused()));
    doc.addEventListener('spacepixels-animation-state', syncPause);
    for (const [action, delta] of [['previous', -1], ['next', 1]]) query('[data-fm-action=' + action + ']').addEventListener('click', () => {
      animations.pauseAnimations(true); animations.step(delta);
    });
    frameControl.addEventListener('input', () => {
      animations.pauseAnimations(true); frameIndex = Number(frameControl.value); draw();
    });
    for (const control of [stretch, black, white, strength, annotations, residualRange]) control.addEventListener('input', draw);
    for (const control of [zoom, centred]) control.addEventListener('input', () => { draw(); centreViewports(); });
    for (const details of element.querySelectorAll('details')) details.addEventListener('toggle', () => {
      if (details.open) centreViewports();
    });
    if (!data.residual) residualRange.disabled = true;
    query('.fm-fallback').hidden = true; sourceCanvas.hidden = false; controls.hidden = false;
    for (const enhanced of element.querySelectorAll('.fm-enhancement')) enhanced.hidden = false;
    syncPause(); draw();
    if (root.IntersectionObserver) new root.IntersectionObserver(entries => {
      player.visible = entries.some(entry => entry.isIntersecting);
      if (player.visible) player.lastFrameTime = root.performance.now();
    }).observe(element);
  }

  for (const element of doc.querySelectorAll('.fm-review[data-fm-src]')) {
    viewers.set(element.dataset.fmId, element);
    if (root.IntersectionObserver) {
      const observer = new root.IntersectionObserver(entries => {
        if (entries.some(entry => entry.isIntersecting)) { observer.disconnect(); start(element); }
      }, {rootMargin: '300px'});
      observer.observe(element);
    } else start(element);
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
