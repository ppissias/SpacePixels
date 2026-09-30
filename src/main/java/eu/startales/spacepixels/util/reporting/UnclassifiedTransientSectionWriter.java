package eu.startales.spacepixels.util.reporting;

import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.core.SourceExtractor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class UnclassifiedTransientSectionWriter {

    private static final String BACKGROUND_FILE = "unclassified_median_background.png";
    private static final String COMPOSITE_FILE = "unclassified_transients.png";
    private static final int INSPECTION_SIZE = 80;

    private UnclassifiedTransientSectionWriter() {
    }

    static List<List<SourceExtractor.DetectedObject>> selectRemaining(
            List<List<SourceExtractor.DetectedObject>> unclassifiedTransients,
            List<ResidualTransientAnalysis.LocalRescueCandidate> localRescueCandidates) {
        if (unclassifiedTransients == null) {
            return Collections.emptyList();
        }

        Set<SourceExtractor.DetectedObject> rescued = Collections.newSetFromMap(new IdentityHashMap<>());
        if (localRescueCandidates != null) {
            for (ResidualTransientAnalysis.LocalRescueCandidate candidate : localRescueCandidates) {
                if (candidate != null && candidate.points != null) {
                    rescued.addAll(candidate.points);
                }
            }
        }

        Set<SourceExtractor.DetectedObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<List<SourceExtractor.DetectedObject>> remaining = new ArrayList<>(unclassifiedTransients.size());
        for (List<SourceExtractor.DetectedObject> frame : unclassifiedTransients) {
            List<SourceExtractor.DetectedObject> kept = new ArrayList<>();
            if (frame != null) {
                for (SourceExtractor.DetectedObject detection : frame) {
                    if (detection != null && !rescued.contains(detection) && seen.add(detection)) {
                        kept.add(detection);
                    }
                }
            }
            remaining.add(kept);
        }
        return remaining;
    }

    static void writeSection(PrintWriter report,
                             DetectionReportContext context,
                             List<List<SourceExtractor.DetectedObject>> allTransients,
                             List<List<SourceExtractor.DetectedObject>> unclassifiedTransients) throws IOException {
        List<List<SourceExtractor.DetectedObject>> remaining = selectRemaining(
                unclassifiedTransients, context.localRescueCandidates);
        int totalCount = countDistinct(allTransients);
        int beforeRescueCount = countDistinct(unclassifiedTransients);
        int remainingCount = countDistinct(remaining);
        int classifiedCount = Math.max(0, totalCount - beforeRescueCount);
        int rescueCount = Math.max(0, beforeRescueCount - remainingCount);

        report.println("<div class='panel' id='unclassified-transient-inspector'>");
        report.println("<h2>Unclassified Transient Inspector</h2>");
        report.println("<p class='astro-note'>Detections left after accepted tracks, standalone anomalies, and local rescue candidates. Local activity clusters remain here because they group detections for review without classifying them. The background uses the median stack when available; colors run from blue for early frames to red for late frames.</p>");
        report.println("<div class='map-legend'>");
        report.println(metric("Post-veto detections", totalCount));
        report.println(metric("Tracks and anomalies", classifiedCount));
        report.println(metric("Local rescue points", rescueCount));
        report.println(metric("Still unclassified", remainingCount));
        report.println("</div>");

        boolean medianAvailable = hasPixels(context.masterStackData);
        short[][] backgroundData = context.masterStackData;
        if (!medianAvailable && !context.rawFrames.isEmpty()) {
            backgroundData = context.rawFrames.get(0);
        }
        if (!hasPixels(backgroundData)) {
            report.println("<p class='astro-note'>No image background is available for the unclassified transient map.</p></div>");
            return;
        }

        BufferedImage background = createMutedBackground(backgroundData, context);
        int width = background.getWidth();
        int height = background.getHeight();
        int markerRadius = Math.max(7, Math.min(28, Math.round(width / 160.0f)));
        List<Marker> markers = collectMarkers(remaining, width, height);
        TrackVisualizationRenderer.saveLosslessPng(background, new File(context.exportDir, BACKGROUND_FILE));
        TrackVisualizationRenderer.saveLosslessPng(createComposite(background, markers, markerRadius),
                new File(context.exportDir, COMPOSITE_FILE));

        report.println("<style>");
        report.println("#unclassified-transient-inspector .unclassified-layout{display:flex;gap:18px;align-items:flex-start;flex-wrap:wrap}");
        report.println("#unclassified-transient-inspector .unclassified-map-column{flex:3 1 560px;min-width:0}");
        report.println("#unclassified-transient-inspector .unclassified-map{position:relative;display:block;width:" + width + "px;max-width:100%;line-height:0;border:1px solid #68607b;border-radius:5px;overflow:hidden}");
        report.println("#unclassified-transient-inspector .unclassified-map img{display:block;width:100%;height:auto;max-width:none;border:0;border-radius:0}");
        report.println("#unclassified-transient-inspector .unclassified-map svg{position:absolute;inset:0;width:100%;height:100%}");
        report.println("#unclassified-transient-inspector .unclassified-footprint{fill-opacity:.85;pointer-events:none}");
        report.println("#unclassified-transient-inspector .unclassified-source:hover .unclassified-footprint,#unclassified-transient-inspector .unclassified-source.selected .unclassified-footprint{fill-opacity:1}");
        report.println("#unclassified-transient-inspector .unclassified-marker{fill:transparent;stroke-width:2.5;cursor:pointer;pointer-events:all;transition:stroke-width .12s}");
        report.println("#unclassified-transient-inspector .unclassified-marker:hover,#unclassified-transient-inspector .unclassified-marker:focus,#unclassified-transient-inspector .unclassified-marker.selected{stroke-width:5;outline:none}");
        report.println("#unclassified-transient-inspector .unclassified-tooltip{position:absolute;z-index:2;display:none;max-width:230px;padding:8px 10px;background:rgba(12,16,28,.96);border:1px solid #8ea4c8;border-radius:5px;color:#fff;font:12px/1.45 sans-serif;white-space:pre-line;pointer-events:none;box-shadow:0 4px 14px #0009}");
        report.println("#unclassified-transient-inspector .unclassified-details{flex:1 1 240px;max-width:340px;background:#292c37;border:1px solid #565b71;border-radius:6px;padding:14px}");
        report.println("#unclassified-transient-inspector .unclassified-details h3{margin:0 0 10px;color:#fff}");
        report.println("#unclassified-transient-inspector .unclassified-details canvas{display:block;width:100%;max-width:320px;height:auto;background:#14121f;border:1px solid #555}");
        report.println("#unclassified-transient-inspector .unclassified-details dl{display:grid;grid-template-columns:auto 1fr;gap:4px 10px;font-size:12px;line-height:1.35}");
        report.println("#unclassified-transient-inspector .unclassified-details dt{color:#9da9c5}");
        report.println("#unclassified-transient-inspector .unclassified-details dd{margin:0;color:#eee;overflow-wrap:anywhere}");
        report.println("#unclassified-transient-inspector .unclassified-time-legend{height:10px;max-width:360px;margin:12px 0 3px;background:linear-gradient(to right,#1500ff,#00ff9a,#ffee00,#ff0000);border-radius:3px}");
        report.println("</style>");
        report.println("<div class='unclassified-layout'>");
        report.println("<div class='unclassified-map-column'>");
        report.println("<div class='unclassified-map' id='unclassified-map'>");
        report.println("<img id='unclassified-background' src='" + BACKGROUND_FILE + "' width='" + width
                + "' height='" + height + "' alt='Subdued " + (medianAvailable ? "median stack" : "first frame") + "' />");
        report.println("<svg viewBox='0 0 " + width + " " + height
                + "' preserveAspectRatio='none' role='img' aria-label='Time-colored unclassified transient markers'>");
        for (Marker marker : markers) {
            report.println(markerHtml(marker, markerRadius, width, height));
        }
        report.println("</svg><div class='unclassified-tooltip' id='unclassified-tooltip'></div></div>");
        report.println("<div class='unclassified-time-legend'></div><div class='astro-note' style='display:flex;justify-content:space-between;max-width:360px;margin-top:0'><span>Early frames</span><span>Late frames</span></div>");
        report.println("<p class='astro-note'>" + markers.size() + " of " + remainingCount
                + " remaining detections have coordinates inside the image. <a href='" + COMPOSITE_FILE
                + "' target='_blank'>Open static composite PNG</a>.</p>");
        report.println("</div>");
        report.println("<aside class='unclassified-details' id='unclassified-details'><h3>Selected transient</h3>");
        report.println("<canvas id='unclassified-cutout' width='320' height='320' aria-label='80 by 80 pixel local inspection cutout'></canvas>");
        report.println("<p class='astro-note'>" + INSPECTION_SIZE + " × " + INSPECTION_SIZE + " pixel "
                + (medianAvailable ? "median-stack" : "first-frame")
                + " cutout. The marker identifies the detected position.</p>");
        report.println("<dl id='unclassified-metadata'><dt>Status</dt><dd>UNCLASSIFIED</dd><dt>Selection</dt><dd>Select a marker to inspect it.</dd></dl></aside>");
        report.println("</div>");
        if (markers.isEmpty()) {
            report.println("<p class='astro-note'>No remaining unclassified detections are available to inspect.</p>");
        }
        report.println("</div>");
        appendInteractionScript(report);
    }

    private static boolean hasPixels(short[][] imageData) {
        return imageData != null && imageData.length > 0 && imageData[0] != null && imageData[0].length > 0;
    }

    private static String metric(String label, int count) {
        return "<div class='legend-pill'><strong>" + count + "</strong> " + label + "</div>";
    }

    private static int countDistinct(List<List<SourceExtractor.DetectedObject>> frames) {
        if (frames == null) {
            return 0;
        }
        Set<SourceExtractor.DetectedObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<SourceExtractor.DetectedObject> frame : frames) {
            if (frame != null) {
                for (SourceExtractor.DetectedObject detection : frame) {
                    if (detection != null) {
                        seen.add(detection);
                    }
                }
            }
        }
        return seen.size();
    }

    private static BufferedImage createMutedBackground(short[][] imageData, DetectionReportContext context) {
        BufferedImage gray = TrackVisualizationRenderer.createDisplayImage(imageData, context.settings);
        BufferedImage muted = new BufferedImage(gray.getWidth(), gray.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < gray.getHeight(); row++) {
            for (int column = 0; column < gray.getWidth(); column++) {
                int value = gray.getRGB(column, row) & 0xff;
                int red = 11 + Math.round(value * 0.39f);
                int green = 11 + Math.round(value * 0.34f);
                int blue = 23 + Math.round(value * 0.47f);
                muted.setRGB(column, row, new Color(red, green, blue).getRGB());
            }
        }
        return muted;
    }

    private static List<Marker> collectMarkers(List<List<SourceExtractor.DetectedObject>> remaining,
                                               int width,
                                               int height) {
        List<Marker> markers = new ArrayList<>();
        int frameCount = remaining.size();
        for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            float ratio = frameCount > 1 ? (float) frameIndex / (frameCount - 1) : 0f;
            Color color = Color.getHSBColor(0.66f - 0.66f * ratio, 1f, 1f);
            for (SourceExtractor.DetectedObject detection : remaining.get(frameIndex)) {
                if (Double.isFinite(detection.x) && Double.isFinite(detection.y)
                        && detection.x >= 0 && detection.x < width && detection.y >= 0 && detection.y < height) {
                    markers.add(new Marker(markers.size() + 1, frameIndex, detection, color));
                }
            }
        }
        return markers;
    }

    private static BufferedImage createComposite(BufferedImage background, List<Marker> markers, int radius) {
        BufferedImage composite = new BufferedImage(background.getWidth(), background.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = composite.createGraphics();
        graphics.drawImage(background, 0, 0, null);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setStroke(new BasicStroke(3f));
        for (Marker marker : markers) {
            int centerX = (int) Math.round(marker.detection.x);
            int centerY = (int) Math.round(marker.detection.y);
            graphics.setColor(marker.color);
            graphics.drawOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
        }
        for (Marker marker : markers) {
            graphics.setColor(marker.color);
            if (marker.detection.rawPixels != null) {
                for (SourceExtractor.Pixel pixel : marker.detection.rawPixels) {
                    if (pixel != null && pixel.x >= 0 && pixel.x < composite.getWidth()
                            && pixel.y >= 0 && pixel.y < composite.getHeight()) {
                        graphics.fillRect(pixel.x, pixel.y, 1, 1);
                    }
                }
            }
        }
        graphics.dispose();
        return composite;
    }

    private static String markerHtml(Marker marker, int radius, int width, int height) {
        SourceExtractor.DetectedObject detection = marker.detection;
        String color = String.format("#%02x%02x%02x", marker.color.getRed(), marker.color.getGreen(), marker.color.getBlue());
        StringBuilder html = new StringBuilder();
        html.append("<g class='unclassified-source'>");
        String path = footprintPath(detection, width, height);
        if (!path.isEmpty()) {
            html.append("<path class='unclassified-footprint' fill='").append(color)
                    .append("' d='").append(path).append("' />");
        }
        html.append("<circle class='unclassified-marker' tabindex='0' role='button' cx='")
                .append(format(detection.x)).append("' cy='").append(format(detection.y))
                .append("' r='").append(radius).append("' stroke='").append(color)
                .append("' data-id='U").append(marker.id)
                .append("' data-frame='").append(marker.frameIndex + 1)
                .append("' data-index='").append(marker.frameIndex)
                .append("' data-time='").append(escape(DetectionReportGenerator.formatUtcTimestamp(detection.timestamp)))
                .append("' data-filename='").append(escape(detection.sourceFilename == null ? "Unknown" : detection.sourceFilename))
                .append("' data-x='").append(format(detection.x))
                .append("' data-y='").append(format(detection.y))
                .append("' data-peak='").append(format(detection.peakSigma))
                .append("' data-integrated='").append(format(detection.integratedSigma))
                .append("' data-flux='").append(format(detection.totalFlux))
                .append("' data-pixels='").append(footprintPixelCount(detection))
                .append("' data-elongation='").append(format(detection.elongation))
                .append("' data-fwhm='").append(format(detection.fwhm))
                .append("' aria-label='Unclassified transient U").append(marker.id)
                .append(" in frame ").append(marker.frameIndex + 1).append("' /></g>");
        return html.toString();
    }

    private static String footprintPath(SourceExtractor.DetectedObject detection, int width, int height) {
        if (detection.rawPixels == null || detection.rawPixels.isEmpty()) {
            return "";
        }
        StringBuilder path = new StringBuilder();
        for (SourceExtractor.Pixel pixel : detection.rawPixels) {
            if (pixel != null && pixel.x >= 0 && pixel.x < width && pixel.y >= 0 && pixel.y < height) {
                path.append('M').append(pixel.x).append(' ').append(pixel.y).append("h1v1h-1z");
            }
        }
        return path.toString();
    }

    private static int footprintPixelCount(SourceExtractor.DetectedObject detection) {
        if (detection.rawPixels != null && !detection.rawPixels.isEmpty()) {
            return detection.rawPixels.size();
        }
        if (Double.isFinite(detection.pixelArea) && detection.pixelArea > 0) {
            return (int) Math.round(detection.pixelArea);
        }
        return Math.max(0, detection.pixelCount);
    }

    private static String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.2f", value) : "Unavailable";
    }

    private static String escape(String value) {
        return DetectionReportGenerator.escapeHtml(value).replace("'", "&#39;");
    }

    private static void appendInteractionScript(PrintWriter report) {
        report.println("<script>(function(){");
        report.println("const panel=document.getElementById('unclassified-transient-inspector');");
        report.println("const map=panel.querySelector('#unclassified-map');");
        report.println("const image=panel.querySelector('#unclassified-background');");
        report.println("const tooltip=panel.querySelector('#unclassified-tooltip');");
        report.println("const metadata=panel.querySelector('#unclassified-metadata');");
        report.println("const canvas=panel.querySelector('#unclassified-cutout');");
        report.println("const markers=Array.from(panel.querySelectorAll('.unclassified-marker'));");
        report.println("let selected=null;");
        report.println("function setDetails(marker){");
        report.println("const data=marker.dataset;");
        report.println("const rows=[['Status','UNCLASSIFIED'],['Transient ID',data.id],['Frame',data.frame+' (index '+data.index+')'],['Timestamp',data.time],['Source file',data.filename],['X',data.x],['Y',data.y],['Peak sigma',data.peak],['Integrated sigma',data.integrated],['Total flux',data.flux],['Pixel count',data.pixels],['Elongation',data.elongation],['FWHM',data.fwhm]];");
        report.println("metadata.replaceChildren();");
        report.println("rows.forEach(([label,value])=>{const term=document.createElement('dt');const detail=document.createElement('dd');term.textContent=label;detail.textContent=value;metadata.append(term,detail);});");
        report.println("}");
        report.println("function drawCutout(marker){");
        report.println("if(!image.complete||!image.naturalWidth)return;");
        report.println("const context=canvas.getContext('2d');const size=80;");
        report.println("const sourceWidth=Math.min(size,image.naturalWidth);const sourceHeight=Math.min(size,image.naturalHeight);");
        report.println("const x=Number(marker.dataset.x);const y=Number(marker.dataset.y);");
        report.println("const left=Math.max(0,Math.min(image.naturalWidth-sourceWidth,Math.round(x-sourceWidth/2)));");
        report.println("const top=Math.max(0,Math.min(image.naturalHeight-sourceHeight,Math.round(y-sourceHeight/2)));");
        report.println("context.clearRect(0,0,canvas.width,canvas.height);context.imageSmoothingEnabled=false;");
        report.println("context.drawImage(image,left,top,sourceWidth,sourceHeight,0,0,canvas.width,canvas.height);");
        report.println("const footprint=marker.parentElement.querySelector('.unclassified-footprint');");
        report.println("if(footprint){context.save();context.setTransform(canvas.width/sourceWidth,0,0,canvas.height/sourceHeight,-left*canvas.width/sourceWidth,-top*canvas.height/sourceHeight);context.fillStyle=marker.getAttribute('stroke');context.globalAlpha=.85;context.fill(new Path2D(footprint.getAttribute('d')));context.restore();}");
        report.println("const markerX=(x-left)*canvas.width/sourceWidth;const markerY=(y-top)*canvas.height/sourceHeight;");
        report.println("context.strokeStyle='#ffffff';context.lineWidth=3;context.beginPath();context.arc(markerX,markerY,13,0,Math.PI*2);context.stroke();");
        report.println("context.strokeStyle=marker.getAttribute('stroke');context.lineWidth=2;context.beginPath();context.arc(markerX,markerY,17,0,Math.PI*2);context.stroke();");
        report.println("}");
        report.println("function select(marker){if(selected){selected.classList.remove('selected');selected.parentElement.classList.remove('selected');}selected=marker;marker.classList.add('selected');marker.parentElement.classList.add('selected');setDetails(marker);drawCutout(marker);}");
        report.println("function positionTooltip(event){const bounds=map.getBoundingClientRect();const left=Math.min(event.clientX-bounds.left+12,bounds.width-tooltip.offsetWidth-8);const top=Math.min(event.clientY-bounds.top+12,bounds.height-tooltip.offsetHeight-8);tooltip.style.left=Math.max(8,left)+'px';tooltip.style.top=Math.max(8,top)+'px';}");
        report.println("function showTooltip(marker,event){const data=marker.dataset;tooltip.textContent=data.id+' · Frame '+data.frame+' · '+data.time+'\\nX '+data.x+', Y '+data.y+' · Peak '+data.peak+'σ · '+data.pixels+' pixels';tooltip.style.display='block';if(event&&typeof event.clientX==='number')positionTooltip(event);}");
        report.println("markers.forEach(marker=>{marker.addEventListener('mouseenter',event=>showTooltip(marker,event));marker.addEventListener('mousemove',positionTooltip);marker.addEventListener('mouseleave',()=>tooltip.style.display='none');marker.addEventListener('focus',()=>showTooltip(marker));marker.addEventListener('blur',()=>tooltip.style.display='none');marker.addEventListener('click',()=>select(marker));marker.addEventListener('keydown',event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();select(marker);}});});");
        report.println("image.addEventListener('load',()=>{if(selected)drawCutout(selected);});");
        report.println("if(markers.length)select(markers[0]);");
        report.println("})();</script>");
    }

    private static final class Marker {
        final int id;
        final int frameIndex;
        final SourceExtractor.DetectedObject detection;
        final Color color;

        Marker(int id, int frameIndex, SourceExtractor.DetectedObject detection, Color color) {
            this.id = id;
            this.frameIndex = frameIndex;
            this.detection = detection;
            this.color = color;
        }
    }
}
