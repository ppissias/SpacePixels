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
 * Writes the shared HTML scaffolding, styles, and document boundaries for detection reports and index pages.
 */
final class DetectionReportDocumentWriter {

    private static final String[] DETECTION_REPORT_STYLE_LINES = {
            "body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #2b2b2b; color: #cccccc; margin: 0; padding: 30px; } ",
            "h1 { color: #ffffff; border-bottom: 2px solid #4da6ff; padding-bottom: 10px; margin-bottom: 30px; } ",
            "h2 { color: #4da6ff; margin-top: 0; } ",
            ".panel { background-color: #3c3f41; padding: 25px; margin-bottom: 30px; border-radius: 8px; box-shadow: 0 4px 8px rgba(0,0,0,0.3); } ",
            ".flex-container { display: flex; gap: 20px; flex-wrap: wrap; margin-bottom: 15px; }",
            ".metric-box { display: inline-block; background-color: #2b2b2b; padding: 15px 20px; border-radius: 5px; margin: 5px 15px 15px 0; border-left: 4px solid #4da6ff; min-width: 140px; } ",
            ".metric-value { font-size: 26px; font-weight: bold; color: #ffffff; display: block; margin-bottom: 5px; } ",
            ".metric-label { font-size: 11px; color: #999999; text-transform: uppercase; letter-spacing: 1px; } ",
            ".metric-box.compact { padding: 7px 9px; margin: 3px 6px 6px 0; min-width: 92px; border-left-width: 3px; border-radius: 4px; }",
            ".metric-box.compact .metric-value { font-size: 15px; margin-bottom: 2px; line-height: 1.1; }",
            ".metric-box.compact .metric-label { font-size: 9px; letter-spacing: 0.4px; line-height: 1.15; }",
            "table { border-collapse: collapse; width: 100%; margin-top: 15px; font-size: 14px; background-color: #2b2b2b; border-radius: 5px; overflow: hidden; } ",
            "th, td { padding: 12px 15px; text-align: left; } ",
            "th { background-color: #222; color: #4da6ff; font-weight: bold; border-bottom: 2px solid #333; position: sticky; top: 0; } ",
            "tr { border-bottom: 1px solid #333; }",
            "tr:nth-child(even) { background-color: #303030; } ",
            "tr:hover { background-color: #3a3a3a; } ",
            ".alert { color: #ff6b6b; font-weight: bold; }",
            ".config-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 8px; font-size: 12px; }",
            ".config-item { background: #333; padding: 6px 10px; border-radius: 4px; border-left: 3px solid #4da6ff; display: flex; justify-content: space-between; }",
            ".config-item .val { font-family: monospace; color: #4da6ff; font-weight: bold; }",
            ".scroll-box { max-height: 300px; overflow-y: auto; border: 1px solid #444; border-radius: 4px; }",
            ".panel.compact-diagnostics-panel { padding: 18px 20px; }",
            ".compact-note { color: #999999; font-size: 12px; margin-top: -8px; margin-bottom: 12px; line-height: 1.4; }",
            ".compact-threshold-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 6px; margin-bottom: 12px; }",
            ".compact-threshold-grid .config-item { padding: 5px 8px; font-size: 11px; }",
            ".scroll-box.compact-table-box { max-height: 360px; overflow: auto; margin-top: 10px; background-color: #2b2b2b; position: relative; }",
            ".scroll-box.compact-table-box table { margin-top: 0; width: max-content; min-width: 100%; font-size: 12px; border-collapse: separate; border-spacing: 0; overflow: visible; }",
            ".scroll-box.compact-table-box th, .scroll-box.compact-table-box td { padding: 7px 9px; white-space: nowrap; line-height: 1.25; }",
            ".scroll-box.compact-table-box thead th { position: sticky; top: 0; z-index: 3; background-color: #222; box-shadow: 0 1px 0 #333; }",
            ".detection-card { background: #2d2d2d; padding: 20px; margin-bottom: 30px; border-radius: 8px; border-left: 5px solid #4da6ff; }",
            ".detection-title { font-size: 1.2em; font-weight: bold; color: #ffffff; margin-bottom: 15px; }",
            ".streak-title { color: #ff9933; border-left-color: #ff9933;}",
            "img { border: 1px solid #555; border-radius: 4px; background-color: black; object-fit: contain; max-width: 100%; transition: border-color 0.2s ease-in-out; }",
            "a img:hover { border-color: #4da6ff; cursor: pointer; }",
            ".image-container { display: flex; gap: 20px; align-items: flex-start; margin-bottom: 15px; }",
            ".anomaly-card { border-left-color: #b888c9; }",
            ".anomaly-card .detection-title { margin-bottom: 3px; }",
            ".anomaly-subtitle { color: #b9adc2; font-size: 13px; margin-bottom: 14px; }",
            ".anomaly-summary { display: flex; flex-wrap: wrap; gap: 12px 26px; margin: 0 0 14px; font-size: 13px; }",
            ".anomaly-summary dt { color: #999; font-size: 11px; margin-bottom: 3px; }",
            ".anomaly-summary dd { margin: 0; color: #ddd; overflow-wrap: anywhere; }",
            ".anomaly-reason { color: #bbb; font-size: 13px; line-height: 1.5; margin: 0 0 16px; }",
            ".anomaly-reason span { color: #e0c7ed; border-bottom: 1px dotted #8d759a; cursor: help; }",
            ".anomaly-visuals { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 20px; }",
            ".anomaly-comparison { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; flex: 3 1 640px; min-width: 0; }",
            ".anomaly-card figure { margin: 0; min-width: 0; }",
            ".anomaly-card figcaption { color: #aaa; font-size: 12px; text-align: center; margin-top: 6px; line-height: 1.5; }",
            ".anomaly-visuals img { display: block; width: 100%; height: auto; }",
            ".anomaly-event img { border-color: #efc069; }",
            ".anomaly-event figcaption { color: #efc069; }",
            ".anomaly-blink { flex: 1 1 200px; max-width: 320px; }",
            ".anomaly-missing { min-height: 130px; display: flex; align-items: center; justify-content: center; padding: 12px; border: 1px dashed #555; border-radius: 4px; color: #999; font-size: 13px; text-align: center; }",
            ".anomaly-diagnostics { flex-wrap: wrap; margin-top: 12px; }",
            ".anomaly-identification { margin-bottom: 16px; }",
            "@media (max-width: 650px) { .anomaly-comparison { grid-template-columns: minmax(0, 1fr); } }",
            ".source-list { list-style-type: none; padding: 0; display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }",
            ".source-list li { background: #3d3d3d; padding: 8px 10px; border-radius: 4px; font-size: 0.9em; font-family: monospace; color: #aaa; border: 1px solid #555; flex: 1 1 360px; max-width: 520px; line-height: 1.4; }",
            ".source-file { color: #dddddd; margin-bottom: 4px; word-break: break-word; }",
            ".source-metrics { color: #999999; margin-top: 4px; }",
            ".foldable-streak-details { margin-top: 10px; margin-bottom: 12px; }",
            ".foldable-streak-details summary { cursor: pointer; color: #9ecfff; font-weight: bold; list-style: none; }",
            ".foldable-streak-details summary::-webkit-details-marker { display: none; }",
            ".foldable-streak-details summary::before { content: '\\25B6'; color: #9ecfff; display: inline-block; margin-right: 8px; font-size: 11px; transform: translateY(-1px); }",
            ".foldable-streak-details[open] summary::before { content: '\\25BC'; }",
            ".foldable-streak-body { margin-top: 10px; }",
            ".foldable-streak-body .source-list { margin-top: 8px; }",
            ".coord-highlight { color: #4da6ff; font-weight: bold; }",
            ".coord-stack { display: inline-flex; flex-direction: column; align-items: flex-start; gap: 2px; max-width: 100%; }",
            ".coord-line { display: block; white-space: normal; }",
            ".id-links { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }",
            ".id-link { display: inline-block; background: #254a69; color: #d9ecff; padding: 6px 10px; border-radius: 999px; text-decoration: none; font-size: 12px; border: 1px solid #356c97; }",
            ".id-link:hover { background: #2f6288; color: #ffffff; }",
            "button.id-link { cursor: pointer; font-family: inherit; line-height: 1.2; }",
            ".live-result-slot { margin-top: 12px; }",
            ".live-render-panel { background: #262b31; border: 1px solid #37424f; border-radius: 8px; padding: 14px 16px; margin-top: 10px; }",
            ".live-render-panel.error { border-color: #7a3b3b; background: #342525; }",
            ".live-render-heading { display: flex; justify-content: space-between; gap: 12px; align-items: center; margin-bottom: 10px; }",
            ".live-render-title { color: #ffffff; font-weight: bold; font-size: 14px; }",
            ".live-render-meta { color: #92a1b2; font-size: 11px; }",
            ".live-mini-grid { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 12px; }",
            ".live-mini-card { background: #1f2429; border: 1px solid #34414d; border-radius: 6px; padding: 10px 12px; min-width: 120px; }",
            ".live-mini-label { color: #8e9baa; font-size: 10px; text-transform: uppercase; letter-spacing: 0.7px; display: block; margin-bottom: 4px; }",
            ".live-mini-value { color: #ffffff; font-size: 14px; font-weight: bold; }",
            ".live-empty-note { color: #b7c1cc; font-size: 12px; margin: 10px 0 0 0; }",
            ".live-caption { color: #9eb1c4; font-size: 12px; margin: 0 0 10px 0; line-height: 1.45; }",
            ".live-table-box { max-height: 360px; overflow: auto; border: 1px solid #3d4650; border-radius: 6px; margin-top: 10px; }",
            ".live-table-box table { margin-top: 0; width: max-content; min-width: 100%; font-size: 12px; border-collapse: separate; border-spacing: 0; }",
            ".live-table-box th, .live-table-box td { padding: 7px 9px; white-space: nowrap; line-height: 1.25; vertical-align: top; }",
            ".live-table-box thead th { position: sticky; top: 0; z-index: 2; background: #1c2127; }",
            ".live-link-inline { color: #7fc2ff; text-decoration: none; }",
            ".live-link-inline:hover { text-decoration: underline; }",
            ".live-raw-json { margin-top: 12px; }",
            ".live-raw-json summary { cursor: pointer; color: #9ecfff; }",
            ".live-raw-json pre { margin-top: 8px; padding: 12px; background: #16191d; border: 1px solid #303841; border-radius: 6px; color: #cfd8e3; overflow: auto; white-space: pre-wrap; word-break: break-word; }",
            ".astro-note { font-size: 12px; color: #aaaaaa; margin-top: 10px; line-height: 1.45; }",
            ".catalogue-comparison { margin: 7px 0; font-size: 12px; line-height: 1.5; color: #aaaaaa; }",
            ".catalogue-comparison summary { cursor: pointer; }",
            ".catalogue-comparison .comparison-review { color: #e4b66a; }",
            ".comparison-body { border-left: 2px solid #666; padding: 4px 0 4px 9px; margin-top: 5px; white-space: normal; max-width: 900px; }",
            ".catalogue-comparison details .comparison-body { min-width: 250px; max-width: 600px; }",
            ".comparison-fact { display: block; }",
            ".comparison-qualifications { margin-top: 5px; color: #999; }",
            ".sky-orientation-figure { position: relative; display: inline-block; }",
            ".sky-orientation-overlay { position: absolute; bottom: 6px; right: 6px; width: 64px; height: 64px; opacity: 0; pointer-events: none; transition: opacity 120ms ease; }",
            ".sky-orientation-figure:hover .sky-orientation-overlay, .sky-orientation-figure:focus-within .sky-orientation-overlay { opacity: 1; }",
            ".sky-orientation-overlay svg { display: block; width: 100%; height: 100%; overflow: visible; }",
            ".sky-orientation-overlay line { stroke: #ff6666; stroke-width: 1.2; stroke-linecap: round; }",
            ".sky-orientation-overlay text { fill: #ff7777; stroke: rgba(0,0,0,0.85); stroke-width: 2.5px; paint-order: stroke; font: bold 11px sans-serif; text-anchor: middle; dominant-baseline: central; }",
            ".native-size-image { max-width: 100%; width: auto; height: auto; display: block; margin: 0 auto; }",
            ".map-legend { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 15px; }",
            ".legend-pill { display: inline-flex; align-items: center; gap: 8px; background: #262626; border: 1px solid #444; border-radius: 999px; padding: 6px 10px; font-size: 12px; color: #d0d0d0; }",
            ".legend-code { display: inline-flex; align-items: center; justify-content: center; min-width: 36px; padding: 4px 8px; border-radius: 999px; color: #101010; font-weight: bold; letter-spacing: 0.4px; }",
            // Links, navigation and section anchors
            "a { color: #7fc2ff; }",
            "a:visited { color: #a9c9ff; }",
            // Findings that belong to a reported track
            ".track-link-note { margin: 0 0 14px; padding: 10px 14px; background: #20362b; border: 1px solid #3e7a5a; border-left: 4px solid #66d9a3; border-radius: 6px; color: #d5efe1; font-size: 14px; line-height: 1.5; }",
            ".track-link-note strong { color: #ffffff; }",
            ".track-link-note a, .track-link-note a:visited { color: #8ff0bd; font-weight: bold; }",
            "a.track-link-badge, a.track-link-badge:visited { display: inline-block; background: #2f8a5f; color: #ffffff; font-size: 0.7em; font-weight: bold; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle; text-decoration: none; }",
            "a.track-link-badge:hover { background: #3aa673; }",
            "[id] { scroll-margin-top: calc(var(--report-toolbar-height, 120px) + 12px); }",
            ".report-toolbar { position: sticky; top: 0; z-index: 50; margin: -10px -30px 24px; padding: 10px 30px; background: rgba(32, 34, 36, 0.98); border-bottom: 1px solid #444; box-shadow: 0 4px 10px rgba(0,0,0,0.35); }",
            ".report-nav { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }",
            ".report-toolbar .report-controls { margin: 8px 0 0; padding-top: 8px; border-top: 1px solid #3c4248; }",
            ".report-toolbar .report-controls label { display: flex; align-items: center; gap: 6px; min-width: 0; }",
            ".compact-table-box td.rejection-reason { white-space: normal; min-width: 280px; max-width: 480px; line-height: 1.5; }",
            ".rejection-reason small { display: block; margin-top: 4px; color: #d6dee5; font-size: 12px; font-weight: normal; }",
            ".report-nav a { color: #d6e6f5; text-decoration: none; font-size: 13px; padding: 5px 11px; border-radius: 999px; background: #33373b; border: 1px solid #474d53; white-space: nowrap; }",
            ".report-nav a:hover { background: #3d4a57; border-color: #4da6ff; color: #ffffff; }",
            ".report-nav a .nav-count { color: #4da6ff; font-weight: bold; margin-left: 5px; }",
            ".report-nav a.nav-muted { color: #8b949c; }",
            ".report-nav a.nav-muted .nav-count { color: #8b949c; }",
            ".report-nav .nav-title { color: #ffffff; font-weight: bold; font-size: 14px; margin-right: 8px; }",
            // Session header
            ".session-header { display: flex; flex-wrap: wrap; gap: 8px 26px; margin: -14px 0 22px 0; color: #b8c2cc; font-size: 14px; }",
            ".session-header .item { white-space: nowrap; }",
            ".session-header .item b { color: #ffffff; font-weight: 600; }",
            ".session-header .label { color: #8b949c; margin-right: 6px; }",
            // Clickable overview cards and quiet zero counts
            "a.metric-link { text-decoration: none; color: inherit; }",
            "a.metric-link .metric-box { transition: background-color 0.15s ease, border-color 0.15s ease; }",
            "a.metric-link:hover .metric-box { background-color: #34393e; border-left-color: #ffffff; }",
            ".metric-box.quiet { opacity: 0.45; }",
            ".section-lede { color: #b0b8c0; font-size: 14px; margin-top: -10px; margin-bottom: 15px; line-height: 1.5; }",
            ".ne-heading { color: #e6e6e6; font-size: 16px; margin: 22px 0 8px; }",
            ".ne-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 18px 24px; align-items: start; }",
            ".ne-chart-title { font-size: 12px; color: #b0b8c0; margin-bottom: 6px; }",
            ".ne-svg { width: 100%; height: auto; display: block; background: #2b2b2b; border: 1px solid #555; border-radius: 4px; }",
            ".ne-map { max-width: 520px; }",
            ".ne-reading { font-size: 13px; color: #cccccc; line-height: 1.5; margin: 8px 0 0; }",
            ".ne-list { font-size: 13px; line-height: 1.55; padding-left: 18px; margin: 0; }",
            ".ne-list li { margin-bottom: 6px; }",
            ".ne-method { margin-top: 18px; }",
            ".ne-method summary { cursor: pointer; color: #8fc3ff; font-size: 12px; }",
            // Detection image with its footprint overlaid on hover
            ".footprint-figure a { position: relative; display: inline-block; line-height: 0; }",
            ".footprint-figure img.footprint-overlay { position: absolute; left: 0; top: 0; width: 100%; height: 100%; box-sizing: border-box; border: 1px solid transparent; background: transparent; mix-blend-mode: screen; opacity: 0; transition: opacity 0.15s ease; pointer-events: none; }",
            ".footprint-figure a:hover img.footprint-overlay { opacity: 1; }",
            // Collapsible diagnostics group
            "details.report-group { margin-bottom: 30px; }",
            "details.report-group > summary { cursor: pointer; list-style: none; background: #34373a; border: 1px solid #4a4f54; border-radius: 8px; padding: 16px 22px; color: #4da6ff; font-size: 1.5em; font-weight: bold; }",
            "details.report-group > summary::-webkit-details-marker { display: none; }",
            "details.report-group > summary::before { content: '\\25B6'; display: inline-block; font-size: 0.6em; margin-right: 12px; transform: translateY(-2px); }",
            "details.report-group[open] > summary { margin-bottom: 20px; }",
            "details.report-group[open] > summary::before { content: '\\25BC'; }",
            "details.report-group > summary .summary-note { display: block; color: #9aa4ad; font-size: 0.55em; font-weight: normal; margin-top: 4px; }"
    };

    private static final String[] ITERATIVE_INDEX_STYLE_LINES = {
            "body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #2b2b2b; color: #cccccc; margin: 0; padding: 30px; } ",
            "h1 { color: #ffffff; border-bottom: 2px solid #4da6ff; padding-bottom: 10px; margin-bottom: 30px; } ",
            ".panel { background-color: #3c3f41; padding: 25px; margin-bottom: 30px; border-radius: 8px; box-shadow: 0 4px 8px rgba(0,0,0,0.3); } ",
            "table { border-collapse: collapse; width: 100%; margin-top: 15px; font-size: 14px; background-color: #2b2b2b; border-radius: 5px; overflow: hidden; } ",
            "th, td { padding: 12px 15px; text-align: left; } ",
            "th { background-color: #222; color: #4da6ff; font-weight: bold; border-bottom: 2px solid #333; } ",
            "tr { border-bottom: 1px solid #333; }",
            "tr:nth-child(even) { background-color: #303030; } ",
            "tr:hover { background-color: #3a3a3a; } ",
            "a { color: #4da6ff; text-decoration: none; font-weight: bold; }",
            "a:hover { text-decoration: underline; }",
            ".highlight { color: #44ff44; font-weight: bold; }",
            ".alert { color: #ff6b6b; font-weight: bold; }"
    };

    private DetectionReportDocumentWriter() {
    }

    static void appendDetectionReportStart(PrintWriter report) {
        report.println("<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'><title>SpacePixels Session Report</title><style>");
        for (String line : DETECTION_REPORT_STYLE_LINES) {
            report.println(line);
        }
        report.println(".report-controls{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin:12px 0}.report-controls select{max-width:320px;background:#222;color:#eee;padding:6px}.image-container{flex-wrap:wrap}.image-container>div{min-width:0;max-width:100%}.source-list li{min-width:0;box-sizing:border-box;flex-basis:min(360px,100%)}.detection-card{min-width:0;max-width:100%;box-sizing:border-box}.chart-controls{display:flex;gap:6px;margin:4px 0}.chart-controls button{font-size:11px;padding:3px 8px}.report-map-stage{position:relative;line-height:0}.report-map svg.sp-layer{position:absolute;inset:0;width:100%;height:100%;pointer-events:none}.report-map svg.sp-layer a,.report-map svg.sp-layer text{pointer-events:all}.report-map img{display:block;width:100%}.report-filter{display:flex;flex-wrap:wrap;gap:12px;align-items:center;line-height:1.5;margin-bottom:12px}svg[data-report-chart]{touch-action:pan-y}.map-category-controls{line-height:1.5;margin:8px 0}.map-category-controls button[aria-pressed=false]{opacity:.5}@media(max-width:700px){body{padding:10px}.panel{padding:12px}.report-toolbar{margin-left:-10px;margin-right:-10px;padding-left:10px;padding-right:10px}.report-controls select{max-width:220px}.image-container{gap:10px}} ");
        report.println("</style></head><body>");
        report.println("<h1>SpacePixels Session Report</h1>");
    }

    static void appendIterativeIndexReportStart(PrintWriter report) {
        report.println("<!DOCTYPE html><html><head><meta charset='utf-8'><title>Iterative Detection Summary</title><style>");
        for (String line : ITERATIVE_INDEX_STYLE_LINES) {
            report.println(line);
        }
        report.println("</style></head><body>");
        report.println("<h1>SpacePixels Iterative Detection Summary</h1>");
    }

    static void appendHtmlDocumentEnd(PrintWriter report) {
        report.println("</body></html>");
    }
}
