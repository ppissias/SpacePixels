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
            ".sky-orientation-figure { position: relative; display: inline-block; }",
            ".sky-orientation-overlay { position: absolute; top: 8px; right: 8px; width: 156px; padding: 10px 11px; border-radius: 10px; background: rgba(10, 16, 23, 0.93); border: 1px solid rgba(120, 177, 229, 0.68); color: #edf5ff; box-shadow: 0 8px 22px rgba(0,0,0,0.45); opacity: 0; pointer-events: none; transform: translateY(-4px); transition: opacity 140ms ease, transform 140ms ease; }",
            ".sky-orientation-figure:hover .sky-orientation-overlay { opacity: 1; transform: translateY(0); }",
            ".sky-orientation-title { color: #9ecfff; font-size: 10px; font-weight: bold; text-transform: uppercase; letter-spacing: 0.9px; margin-bottom: 8px; }",
            ".sky-orientation-compass { position: relative; width: 74px; height: 74px; margin: 0 auto 9px auto; border-radius: 50%; border: 1px solid rgba(158, 207, 255, 0.34); background: radial-gradient(circle at 50% 50%, rgba(63, 107, 153, 0.36) 0%, rgba(18, 26, 36, 0.16) 68%, rgba(18, 26, 36, 0.04) 100%); }",
            ".sky-orientation-compass::before { content: ''; position: absolute; left: 50%; top: 8px; bottom: 8px; width: 1px; background: rgba(255,255,255,0.08); transform: translateX(-50%); }",
            ".sky-orientation-compass::after { content: ''; position: absolute; top: 50%; left: 8px; right: 8px; height: 1px; background: rgba(255,255,255,0.08); transform: translateY(-50%); }",
            ".sky-orientation-arrow { position: absolute; left: 50%; top: 50%; width: 0; height: 28px; transform: translate(-50%, -100%) rotate(var(--rotation, 0deg)); transform-origin: 50% 100%; }",
            ".sky-orientation-arrow::before { content: ''; position: absolute; left: -1px; top: 7px; width: 2px; height: 19px; background: currentColor; border-radius: 999px; }",
            ".sky-orientation-arrow::after { content: ''; position: absolute; left: -5px; top: 0; border-left: 5px solid transparent; border-right: 5px solid transparent; border-bottom: 8px solid currentColor; }",
            ".sky-orientation-arrow span { position: absolute; top: -15px; left: -7px; font-size: 10px; font-weight: bold; }",
            ".sky-orientation-arrow.north { color: #a7ef73; }",
            ".sky-orientation-arrow.east { color: #7fc2ff; }",
            ".sky-orientation-center { position: absolute; left: 50%; top: 50%; width: 6px; height: 6px; border-radius: 50%; background: #ffffff; transform: translate(-50%, -50%); box-shadow: 0 0 8px rgba(255,255,255,0.35); }",
            ".sky-orientation-meta { color: #dce8f5; font-size: 11px; line-height: 1.35; margin-top: 2px; white-space: nowrap; }",
            ".native-size-image { max-width: 100%; width: auto; height: auto; display: block; margin: 0 auto; }",
            ".map-legend { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 15px; }",
            ".legend-pill { display: inline-flex; align-items: center; gap: 8px; background: #262626; border: 1px solid #444; border-radius: 999px; padding: 6px 10px; font-size: 12px; color: #d0d0d0; }",
            ".legend-code { display: inline-flex; align-items: center; justify-content: center; min-width: 36px; padding: 4px 8px; border-radius: 999px; color: #101010; font-weight: bold; letter-spacing: 0.4px; }"
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
            ".highlight { color: #44ff44; font-weight: bold; }"
    };

    private DetectionReportDocumentWriter() {
    }

    static void appendDetectionReportStart(PrintWriter report) {
        report.println("<!DOCTYPE html><html><head><title>Detection Report</title><style>");
        for (String line : DETECTION_REPORT_STYLE_LINES) {
            report.println(line);
        }
        report.println("</style></head><body>");
        report.println("<h1>SpacePixels Session Report</h1>");
    }

    static void appendIterativeIndexReportStart(PrintWriter report) {
        report.println("<!DOCTYPE html><html><head><title>Iterative Detection Summary</title><style>");
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
