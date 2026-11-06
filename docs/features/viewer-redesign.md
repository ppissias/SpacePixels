# Preview Frame and Blink redesign (F29)

The agreed design for the frame viewers, from the maintainer's spec of 2026-11-06 and the decisions in [FEATURE-REQUESTS.md F29](../../FEATURE-REQUESTS.md#f29-preview-frame-and-blink-redesign). Same controls, clearer grouping, less clutter: mostly re-layout, with a few new behaviours. Where the spec and a decision differ, the decision is written here.

Mock-up screenshots (placeholders in `[brackets]`, counts illustrative):

- [Preview Frame](../images/viewer-redesign-preview.png)
- [Blink](../images/viewer-redesign-blink.png)

## 1. Shared structure

```
┌ title bar ────────────────────────────────────────────────────────────┐
│ toolbar (one row; does not wrap at 1280 px)                            │
├──────────────┬─────────────────────────────────────┬──────────────────┤
│ (Blink only) │ image viewer                        │ side panel       │
│ frame list   │                                     │ ~312 px, scrolls,│
│ ~248 px      │ (Blink: transport bar under image)  │ can be hidden    │
├──────────────┴─────────────────────────────────────┴──────────────────┤
│ status bar (one line)                                                 │
└───────────────────────────────────────────────────────────────────────┘
```

All frame viewers that share `SequenceViewerFrame` get the side panel: Preview Frame, Full Size and Manual Transient Inspection. Raw detections appears only where the viewer extracts detections.

Side panel sections, in this order:

1. **Raw detections** (viewers with trial detection only)
2. **Report findings**
3. **Catalog annotations**
4. **Display stretch**

Sections 2–4 are shared components, so the viewers cannot drift apart. Sections can be collapsed; Raw detections starts collapsed while its switch is off, so Display stretch stays reachable without scrolling.

### 1.1 Toolbar

| Preview Frame | Blink |
|---|---|
| ◀ · Frame [n] of N · ▶ │ Zoom [Fit · 100 % · 200 % · 400 %] │ … │ Blink ↗ │ panel toggle | Zoom [Fit · 100 % · 200 % · 400 %] │ … │ Export GIF… (G) │ Open in Preview ↗ │ panel toggle |

- Zoom is one segmented control (a `ButtonGroup` of toggle buttons).
- The panel toggle shows or hides the side panel; its visibility and width are remembered.
- "Blink ↗" and "Open in Preview ↗" open the other window on the current frame.

### 1.2 Layer rows

Each overlay layer is one row: `[swatch] Name ……… count  [Aa] [eye]`.

- **Swatch:** an icon painted with the same colour, stroke, dash and shape the viewer draws. One style definition serves both.
- **Aa:** shows or hides that layer's labels only; dimmed (about 35 %) when off.
- **Eye:** shows or hides the layer; when off, a crossed eye, and the swatch and name are dimmed (about 45 %).
- **A disabled row says why,** for example "Tracks — none in report", with both buttons disabled.
- Layer visibility and label choices are shared by all viewers and remembered.

### 1.3 Section contents

**Raw detections**
- Header: swatch, title, on/off switch (key **D**). Sub-line with the counts, for example "90,340 point sources on this frame".
- Trial parameters: Detection σ, Grow σ (hysteresis), Min pixels.
  - Editing any field switches the layer on.
  - A changed field gets an amber border and a `saved: <value>` hint; a "Not saved" badge appears in the header.
  - One hint line: "Previews on this frame only. Typed values apply on Enter."
  - **Revert** (was Reset) and **Save to settings** (was Apply to Settings), enabled only when the values differ from the saved settings.
- The markers keep their current look: green boxes for point sources, red lines for streaks, sized by the marker settings.

**Report findings** (column caption "labels · show")
- Variable stars: solid ring = high confidence, dashed ring = possible (kept; explained in the tooltip). Labels on by default.
- Faint movers: the ring at the exposure midpoint **and** the dashed fitted path (kept). Labels on by default.
- Tracks: T (moving objects) and ST (streak tracks).
- Other findings, expandable; the parent's eye hides all children while each child keeps its own state. Children, by report type:
  - Single streaks & anomalies (S, A)
  - Slow-mover candidates (DS)
  - Local rescue & activity (LR, LC)
  - Camera-fixed & common motion (FC, CM)

  Labels off by default for these. Measured positions stay rings; report references stay dashed squares marked "ref".

**Catalog annotations** (known objects fetched for the field; not user notes)
- Header: title with the **A** key hint and a master switch (key **A**); a small settings menu holds the faintest-star and faintest-variable magnitude limits.
- Hint: "Known objects in this field, from <catalogue sources>".
- Rows: Known stars (crosshair; labels off by default; includes named stars), Known variables (diamond), Deep-sky objects (dashed ellipse), Known asteroids & comets (ring with predicted path).
- All catalogue shapes use one off-white colour (`#F4F1E8`) and differ by shape. Rule: **colour = found by SpacePixels, white = already in a catalogue**.
- "Align markers to corrected plate solution" (key **C**) sits here: it moves the catalogue marks and the cursor RA/Dec, and applies to the next Detect Moving Targets report, as today.

**Display stretch**
- Header: title and the Stretch switch; when off, the controls below are disabled.
- Function (combo), the algorithm's two parameters as slider plus spinner on one model, and **Reset display**. Parameter names follow the selected algorithm.
- Mini histogram with the black point marked: optional, after the rest.

### 1.4 Finding detail card (optional, later)

Clicking a finding shows a selection ring and a card: swatch, ID, type, close; position, amplitude, frames seen in, catalogue match; "Open in report", "Show crop". Hover tooltips remain meanwhile.

### 1.5 Status bar

`Frame n of N · <file> · <time> · <counts> …… x, y · RA, Dec · value   [? Shortcuts]`

- RA/Dec only when a plate solution is available.
- The shortcut footer goes; each key is in its control's tooltip, and "? Shortcuts" (key `?`) opens the full list.

## 2. Preview Frame

- Frame stepper: ◀, a number field (Enter jumps), "of N", ▶. ← and → still step.

## 3. Blink

### 3.1 Frame list

- Header "Frames" and "n of N in loop". Rows: checkbox (in the loop), #, short name, time offset; the shown row highlighted; excluded rows grey with strikethrough.
- A gap row ("5 min 17 s gap") where the interval to the previous frame exceeds 1.5 × the median interval.
- Footer: "Untick or press S to leave a frame out of the loop." The "Skip this frame" checkbox goes.

### 3.2 Transport bar (under the image)

`◀  ▶/❚❚  ▶ │ timeline │ 17 / 33 +37:17 │ Speed [slider] 10 fps · 100 ms`

- Play/Pause is the primary button (Space). The step buttons skip excluded frames.
- **Arrow keys:** ← and → step. While paused, ↑ and ↓ also step (previous and next, as in the list); while playing, they change the speed.
- **Timeline** by capture time: filled ticks, hollow ticks for excluded frames, a larger accent tick and a light fill up to the current frame; clicking a tick jumps to it; the scale shows `+0:00`, the total and "↔ gap" labels.
- Speed in frames per second, **0.5–30 fps**, with milliseconds in small print.
- A frame tag in the image's top-left: `17 / 33 · +37:17`.

### 3.3 Export GIF mode

The toolbar toggle "Export GIF… (G)" enters region selection: the image is dimmed outside the dashed selection rectangle, and a floating bar reads "Drag a rectangle on the image, then export" with **Cancel (Esc)** and **Export GIF**. This replaces "Select GIF region" and its instruction label.

## 4. Old → new control map

| Current control | New place |
|---|---|
| Show detections | Raw detections switch (D) |
| Show tracks | Report findings › Tracks |
| Show variable stars / faint movers / other findings | Report findings rows |
| Annotate checkbox and Layers ▾ menu | Catalog annotations switch (A), its rows and settings menu |
| Correct plate solution | Catalog annotations › Align markers to corrected plate solution (C) |
| Zoom Fit / 100 / 200 / 400 | Toolbar segmented control |
| Detection Sigma / Grow Sigma / Min Detection Pixels | Raw detections › Trial parameters |
| Reset (detection) / Apply to Settings | Revert / Save to settings |
| Stretch display / algorithm / sliders / Reset | Display stretch section; Reset → Reset display |
| Blink Play, ◀ ▶, Speed | Transport bar |
| Skip this frame | removed (list checkbox and S) |
| Select GIF region | Export GIF… mode (G) |
| Shortcut footer | Tooltips and "? Shortcuts" |

All existing shortcuts keep working: ← → ↑ ↓ D T V A C F 1 Space S G Ctrl+E Esc.

## 5. Visual style

- Colours of findings come from the report's finding types (`FindingCategory`), so the viewers and the HTML report match. Catalogue objects are `#F4F1E8`.
- The application's theme setting (Appearance) decides the chrome; no hard-coded dark palette. The image well stays dark.

## 6. Order of work

1. Shared layer styles, layer rows and side-panel sections, with shared remembered layer state; the viewers use the side panel instead of their checkbox rows.
2. Preview Frame: single-row toolbar, Raw detections section with Revert / Save to settings.
3. Blink: frame list with gap rows, transport bar with the time-proportional timeline and the arrow keys, Export GIF mode.
4. Status bar, "? Shortcuts" window, key hints in tooltips.
5. Optional: the finding detail card and the mini histogram.

Each step is tested, checked on screen and committed separately.

## 7. Acceptance checks

- At 1280 px wide, neither toolbar wraps.
- Every legend swatch matches its marker on the image.
- A layer switched in Preview Frame shows the same state when Blink opens.
- Save to settings is disabled until a trial value changes; Revert restores the saved values.
- Blink stepping skips excluded frames, and the Klangwolke-2 frame list and timeline show the gap after frame 18.
- All previous shortcuts still work.
