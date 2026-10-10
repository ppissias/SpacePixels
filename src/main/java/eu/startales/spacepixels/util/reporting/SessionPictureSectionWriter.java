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

import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.util.skycatalog.SkyProjection;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

/**
 * The picture of the session at the top of the report: the median stack at screen size, with switchable layers
 * over it. The star mask is an image; the detections (named as on the detection map) and the catalogue (Messier,
 * NGC, IC and Sharpless objects, and variable stars with names people know; no survey numbers) are drawn as SVG in
 * frame pixels, so they stay sharp at any size. Clicking the picture opens the full-size stack.
 */
final class SessionPictureSectionWriter {

    static final String STACK_FILE = "session_stack.jpg";
    static final String MASK_FILE = "session_star_mask.png";
    /** The width of the picture files; the stack at full size stays in the diagnostics. */
    private static final int PICTURE_WIDTH = 1800;
    private static final Color MASK_COLOR = new Color(255, 70, 70);
    private static final String DEEP_SKY_COLOR = "#ffd746";
    private static final String VARIABLE_COLOR = "#ff5ad2";
    private static final String NAMED_STAR_COLOR = "#ebebff";

    private SessionPictureSectionWriter() {
    }

    static void write(PrintWriter report, DetectionReportContext context, List<TrackLinker.Track> localRescueTracks) {
        short[][] stack = context.masterStackData;
        if (stack == null || stack.length == 0 || stack[0].length == 0) {
            return;
        }
        int height = stack.length;
        int width = stack[0].length;
        double scale = Math.min(1.0, PICTURE_WIDTH / (double) width);
        int pictureWidth = Math.max(1, (int) Math.round(width * scale));
        int pictureHeight = Math.max(1, (int) Math.round(height * scale));
        boolean hasMask;
        try {
            writeJpeg(downscale(DetectionReportGenerator.createDisplayImage(stack), pictureWidth, pictureHeight),
                    new File(context.exportDir, STACK_FILE));
            hasMask = context.masterVetoMask != null && context.masterVetoMask.length == height;
            if (hasMask) {
                ImageIO.write(maskLayer(context.masterVetoMask, width, height, pictureWidth, pictureHeight), "png",
                        new File(context.exportDir, MASK_FILE));
            }
        } catch (IOException | RuntimeException e) {
            report.println("<div class='astro-note'>The session picture could not be written: "
                    + DetectionReportGenerator.escapeHtml(String.valueOf(e.getMessage())) + "</div>");
            return;
        }

        String detections = detectionsLayer(context, localRescueTracks, width, height);
        SkyCatalogue catalogue = SessionCatalogue.of(context).catalogue;
        WcsCoordinateTransformer transformer = context.astrometryContext != null ? context.astrometryContext.getTransformer() : null;
        String catalogueLayer = catalogue != null && transformer != null ? catalogueLayer(catalogue, transformer, width, height) : null;

        report.println("<div class='session-picture'>");
        report.println("<div class='sp-buttons'>");
        report.println("<span class='sp-title'>The session</span>");
        report.println(button("mask", "Star mask", hasMask, false,
                "The star mask in red: what the detection treated as stars. Hover to see it, click to keep it."));
        report.println(button("catalogue", "Catalogue", catalogueLayer != null, false, catalogueLayer != null
                ? "Messier, NGC, IC and Sharpless objects, bright stars with a proper name and named variable stars, from the sky catalogue of the session."
                : "No sky catalogue: Fetch Sky Catalogue in SpacePixels (2 Astrometry) before Detect Moving Targets."));
        report.println(button("detections", "Detections", detections != null, detections != null,
                "Tracks and single-frame events, named as on the detection map."));
        report.println("<a class='sp-full' href='master_stack.png' target='_blank'>Full size</a>");
        report.println("</div>");
        report.println("<div class='sp-frame' data-mask-hover='off'>");
        report.println("<a href='master_stack.png' target='_blank'><img class='sp-base' src='" + STACK_FILE
                + "' width='" + pictureWidth + "' height='" + pictureHeight + "' alt='Median stack of the session' /></a>");
        if (hasMask) {
            report.println("<img class='sp-layer' data-layer='mask' src='" + MASK_FILE + "' alt='' style='display:none;' />");
        }
        if (catalogueLayer != null) {
            report.println(catalogueLayer);
        }
        if (detections != null) {
            report.println(detections);
        }
        report.println("</div>");
        report.println("<div class='sp-caption'>The median stack of the frames kept"
                + (catalogueLayer != null && transformer != null && transformer.isCorrected() ? "; catalogue positions use the plate solution corrected to the stars" : "")
                + ". Click the picture for the full-size stack.</div>");
        report.println("</div>");
    }

    /** The links that open the collapsed diagnostics, with a few key figures. */
    static void writeDiagnosticsLink(PrintWriter report, int framesKept, int framesLoaded) {
        report.println("<div class='astro-note'>Run settings, frame quality checks, the star mask and the tracking diagnostics: "
                + "<a href='#diagnostics' class='open-details'>Diagnostics &darr;</a> (" + framesKept + " of " + framesLoaded
                + " frames kept" + (framesLoaded > framesKept ? ", " + (framesLoaded - framesKept) + " rejected" : "") + ").</div>");
    }

    private static String button(String layer, String label, boolean available, boolean on, String tooltip) {
        return "<button type='button' class='id-link sp-toggle" + (on ? " active" : "") + "' data-layer='" + layer + "'"
                + (available ? "" : " disabled") + " title='" + DetectionReportGenerator.escapeHtml(tooltip) + "'>" + label + "</button>";
    }

    // ==========================================
    // LAYERS
    // ==========================================

    private static String svgStart(String layer, int width, int height, boolean visible) {
        return "<svg class='sp-layer' data-layer='" + layer + "' viewBox='0 0 " + width + " " + height
                + "' preserveAspectRatio='none' style='display:" + (visible ? "block" : "none") + ";' font-family='Segoe UI, sans-serif' font-size='"
                + fontSize(width) + "'>";
    }

    /** Labels about as large as ordinary text when the picture fills the report width. */
    private static int fontSize(int width) {
        return Math.max(12, width / 110);
    }

    /** The detections, with the labels and colours of the detection map; null when there are none. */
    static String detectionsLayer(DetectionReportContext context, List<TrackLinker.Track> localRescueTracks, int width, int height) {
        StringBuilder svg = new StringBuilder();
        double marker = width / 140.0;
        double offset = width / 160.0;
        int count = 0;
        count += tracks(svg, context.streakTracks, DetectionReportGenerator.GLOBAL_MAP_STREAK_TRACK_COLOR, "ST", offset);
        count += tracks(svg, context.suspectedStreakTracks, DetectionReportGenerator.GLOBAL_MAP_SUSPECTED_STREAK_COLOR, "SST", offset);
        count += tracks(svg, context.movingTargets, DetectionReportGenerator.GLOBAL_MAP_MOVING_TARGET_COLOR, "T", offset);
        count += tracks(svg, localRescueTracks, DetectionReportGenerator.GLOBAL_MAP_LOCAL_RESCUE_COLOR, "LR", offset);
        int index = 1;
        for (ResidualTransientAnalysis.LocalActivityCluster cluster : context.localActivityClusters) {
            if (cluster != null && cluster.points != null && !cluster.points.isEmpty()) {
                double x = cluster.metrics != null ? cluster.metrics.centroidX : cluster.points.get(0).x;
                double y = cluster.metrics != null ? cluster.metrics.centroidY : cluster.points.get(0).y;
                double radius = Math.max(marker, (cluster.metrics != null ? cluster.metrics.clusterRadiusPixels : 0) + marker / 2);
                circle(svg, x, y, radius, DetectionReportGenerator.GLOBAL_MAP_LOCAL_ACTIVITY_COLOR, "LC" + index, true);
                count++;
            }
            index++;
        }
        index = 1;
        for (SourceExtractor.DetectedObject candidate : context.slowMoverCandidates) {
            circle(svg, candidate.x, candidate.y, marker, DetectionReportGenerator.GLOBAL_MAP_DEEP_STACK_COLOR, "DS" + index++, false);
            count++;
        }
        index = 1;
        for (TrackLinker.AnomalyDetection anomaly : context.anomalies) {
            if (anomaly.object != null) {
                circle(svg, anomaly.object.x, anomaly.object.y, marker, DetectionReportGenerator.GLOBAL_MAP_ANOMALY_COLOR, "A" + index, false);
                count++;
            }
            index++;
        }
        index = 1;
        for (TrackLinker.Track streak : context.singleStreaks) {
            if (streak.points != null && !streak.points.isEmpty()) {
                SourceExtractor.DetectedObject point = streak.points.get(0);
                circle(svg, point.x, point.y, marker, DetectionReportGenerator.GLOBAL_MAP_SINGLE_STREAK_COLOR, "S" + index, false);
                count++;
            }
            index++;
        }
        if (count == 0) {
            return null;
        }
        return svgStart("detections", width, height, true) + svg + "</svg>";
    }

    private static int tracks(StringBuilder svg, List<TrackLinker.Track> tracks, Color color, String prefix, double offset) {
        int count = 0;
        int index = 1;
        for (TrackLinker.Track track : tracks) {
            if (track.points == null || track.points.isEmpty()) {
                index++;
                continue;
            }
            StringBuilder points = new StringBuilder();
            for (SourceExtractor.DetectedObject point : track.points) {
                points.append(String.format(Locale.US, "%.1f,%.1f ", point.x + 0.5, point.y + 0.5));
            }
            String colour = hex(color);
            svg.append("<polyline points='").append(points.toString().trim()).append("' fill='none' stroke='").append(colour)
                    .append("' stroke-width='2' vector-effect='non-scaling-stroke'/>");
            SourceExtractor.DetectedObject first = track.points.get(0);
            label(svg, first.x + 0.5 + offset, first.y + 0.5 - offset, prefix + index, "#ffffff");
            count++;
            index++;
        }
        return count;
    }

    private static void circle(StringBuilder svg, double x, double y, double radius, Color color, String label, boolean dashed) {
        svg.append(String.format(Locale.US, "<circle cx='%.1f' cy='%.1f' r='%.1f' fill='none' stroke='%s' stroke-width='2'%s vector-effect='non-scaling-stroke'/>",
                x + 0.5, y + 0.5, radius, hex(color), dashed ? " stroke-dasharray='6 4'" : ""));
        label(svg, x + 0.5 + radius * 1.1, y + 0.5 - radius * 1.1, label, "#ffffff");
    }

    private static void label(StringBuilder svg, double x, double y, String text, String colour) {
        svg.append(String.format(Locale.US, "<text x='%.1f' y='%.1f' fill='%s' stroke='#000' stroke-width='3' paint-order='stroke' stroke-opacity='0.7'>%s</text>",
                x, y, colour, DetectionReportGenerator.escapeHtml(text)));
    }

    /** Deep-sky objects with their outlines, and variable stars with names people know; null when none is on the frame. */
    static String catalogueLayer(SkyCatalogue catalogue, WcsCoordinateTransformer transformer, int width, int height) {
        StringBuilder svg = new StringBuilder();
        double cross = width / 200.0;
        int count = 0;
        for (SkyCatalogue.DeepSkyObject object : catalogue.deepSky) {
            if (!SkyProjection.isWellKnownDeepSky(object)) {
                continue;
            }
            double[] shape = SkyProjection.deepSkyShape(transformer, object);
            if (shape == null || shape[0] < -shape[2] || shape[1] < -shape[2] || shape[0] > width + shape[2] || shape[1] > height + shape[2]) {
                continue;
            }
            double x = shape[0] + 0.5;
            double y = shape[1] + 0.5;
            String name = SkyProjection.deepSkyLabel(object);
            String title = "<title>" + DetectionReportGenerator.escapeHtml(name + " · " + typeName(object.type)) + "</title>";
            if (shape[2] > cross) {
                svg.append(String.format(Locale.US, "<ellipse cx='%.1f' cy='%.1f' rx='%.1f' ry='%.1f' transform='rotate(%.2f %.1f %.1f)' fill='none' stroke='%s' stroke-width='1.5' vector-effect='non-scaling-stroke'>%s</ellipse>",
                        x, y, shape[2], Math.max(cross / 3, shape[3]), Math.toDegrees(shape[4]), x, y, DEEP_SKY_COLOR, title));
                double reach = Math.min(shape[2], width / 6.0) * 0.72;
                label(svg, x + reach, y - reach, name, DEEP_SKY_COLOR);
            } else {
                svg.append(String.format(Locale.US, "<path d='M%.1f %.1fH%.1fM%.1f %.1fH%.1fM%.1f %.1fV%.1fM%.1f %.1fV%.1f' stroke='%s' stroke-width='1.5' vector-effect='non-scaling-stroke'>%s</path>",
                        x - cross, y, x - cross / 3, x + cross / 3, y, x + cross, x, y - cross, y - cross / 3, x, y + cross / 3, y + cross,
                        DEEP_SKY_COLOR, title));
                label(svg, x + cross * 1.3, y - cross * 1.3, name, DEEP_SKY_COLOR);
            }
            count++;
        }
        double ring = width / 180.0;
        for (SkyCatalogue.NamedStar star : catalogue.namedStars) {
            double[] p = transformer.skyToPixel(star.ra, star.dec);
            if (p == null || p[0] < 0 || p[1] < 0 || p[0] > width || p[1] > height) {
                continue;
            }
            svg.append(String.format(Locale.US, "<circle cx='%.1f' cy='%.1f' r='%.1f' fill='none' stroke='%s' stroke-width='1.5' vector-effect='non-scaling-stroke'/>",
                    p[0] + 0.5, p[1] + 0.5, ring, NAMED_STAR_COLOR));
            label(svg, p[0] + 0.5 + ring * 1.3, p[1] + 0.5 - ring * 0.6, star.name, NAMED_STAR_COLOR);
            count++;
        }
        double diamond = width / 260.0;
        for (SkyCatalogue.VariableStar variable : SkyProjection.notableVariables(catalogue, CatalogueMapPainter.MAX_VARIABLES)) {
            double[] p = transformer.skyToPixel(variable.ra, variable.dec);
            if (p == null || p[0] < 0 || p[1] < 0 || p[0] > width || p[1] > height) {
                continue;
            }
            double x = p[0] + 0.5;
            double y = p[1] + 0.5;
            svg.append(String.format(Locale.US, "<path d='M%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fZ' fill='none' stroke='%s' stroke-width='1.5' vector-effect='non-scaling-stroke'><title>%s</title></path>",
                    x, y - diamond, x + diamond, y, x, y + diamond, x - diamond, y, VARIABLE_COLOR,
                    DetectionReportGenerator.escapeHtml(variable.name + (variable.type != null ? " · " + variable.type : ""))));
            label(svg, x + diamond * 1.6, y - diamond, variable.name, VARIABLE_COLOR);
            count++;
        }
        if (count == 0) {
            return null;
        }
        return svgStart("catalogue", width, height, false) + svg + "</svg>";
    }

    private static String typeName(String type) {
        return SkyProjection.deepSkyTypeName(type);
    }

    // ==========================================
    // IMAGES
    // ==========================================

    /** The mask in red where the star mask covers the sky, transparent elsewhere, at the picture's size. */
    static BufferedImage maskLayer(boolean[][] mask, int width, int height, int pictureWidth, int pictureHeight) {
        int[] covered = new int[pictureWidth * pictureHeight];
        int[] total = new int[pictureWidth * pictureHeight];
        for (int y = 0; y < height; y++) {
            boolean[] row = mask[y];
            int py = Math.min(pictureHeight - 1, (int) ((long) y * pictureHeight / height));
            int rowStart = py * pictureWidth;
            for (int x = 0; x < width && row != null && x < row.length; x++) {
                int index = rowStart + Math.min(pictureWidth - 1, (int) ((long) x * pictureWidth / width));
                total[index]++;
                if (row[x]) {
                    covered[index]++;
                }
            }
        }
        BufferedImage layer = new BufferedImage(pictureWidth, pictureHeight, BufferedImage.TYPE_INT_ARGB);
        int rgb = MASK_COLOR.getRGB() & 0xFFFFFF;
        for (int i = 0; i < covered.length; i++) {
            if (covered[i] > 0) {
                int alpha = (int) Math.round(60 + 160.0 * covered[i] / total[i]);
                layer.setRGB(i % pictureWidth, i / pictureWidth, (alpha << 24) | rgb);
            }
        }
        return layer;
    }

    /** Halves the image while it is more than twice too large, then scales it to size, which keeps it smooth. */
    static BufferedImage downscale(BufferedImage image, int targetWidth, int targetHeight) {
        BufferedImage current = image;
        while (current.getWidth() / 2 >= targetWidth && current.getHeight() / 2 >= targetHeight) {
            current = resize(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return current.getWidth() == targetWidth && current.getHeight() == targetHeight ? current : resize(current, targetWidth, targetHeight);
    }

    private static BufferedImage resize(BufferedImage image, int width, int height) {
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image, 0, 0, width, height, null);
        g.dispose();
        return resized;
    }

    private static void writeJpeg(BufferedImage image, File file) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.88f);
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(file)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static String hex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    /** Styles and the script of the layer buttons, and of links that open a collapsed section. */
    static void writeAssets(PrintWriter report) {
        report.println("<style>"
                + ".session-picture { margin: 6px 0 14px 0; }"
                + ".sp-buttons { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 8px; }"
                + ".sp-title { color: #ddd; font-weight: 600; margin-right: 6px; }"
                + ".sp-toggle[disabled] { opacity: 0.4; cursor: default; }"
                + ".sp-toggle.active { background: #3a78b0; border-color: #6aa6dd; color: #fff; }"
                + ".sp-full { margin-left: auto; color: #8fc3ff; font-size: 12px; }"
                + ".sp-frame { position: relative; display: inline-block; max-width: 100%; line-height: 0; border: 1px solid #555; border-radius: 4px; overflow: hidden; }"
                + ".sp-base { max-width: 100%; height: auto; display: block; }"
                + ".sp-layer { position: absolute; left: 0; top: 0; width: 100%; height: 100%; pointer-events: none; }"
                + "svg.sp-layer ellipse, svg.sp-layer path { pointer-events: stroke; }"
                + ".sp-caption { color: #888; font-size: 12px; margin-top: 6px; }"
                + "</style>");
        report.println("<script>(function(){"
                + "function layer(frame,name){return frame?frame.querySelector(\"[data-layer='\"+name+\"']\"):null;}"
                + "document.addEventListener('click',function(e){"
                + "var b=e.target.closest?e.target.closest('.sp-toggle'):null;"
                + "if(b&&!b.disabled){var f=b.closest('.session-picture').querySelector('.sp-frame');var l=layer(f,b.getAttribute('data-layer'));"
                + "b.classList.toggle('active');if(l){l.style.display=b.classList.contains('active')?'block':'none';}return;}"
                + "var a=e.target.closest?e.target.closest('a[href^=\"#\"]'):null;"
                + "if(a){var t=document.getElementById(a.getAttribute('href').substring(1));"
                + "for(var n=t;n;n=n.parentElement){if(n.tagName==='DETAILS'){n.open=true;}}}"
                + "});"
                + "document.addEventListener('mouseover',function(e){var b=e.target.closest?e.target.closest(\".sp-toggle[data-layer='mask']\"):null;"
                + "if(b&&!b.disabled&&!b.classList.contains('active')){var l=layer(b.closest('.session-picture').querySelector('.sp-frame'),'mask');if(l){l.style.display='block';}}});"
                + "document.addEventListener('mouseout',function(e){var b=e.target.closest?e.target.closest(\".sp-toggle[data-layer='mask']\"):null;"
                + "if(b&&!b.classList.contains('active')){var l=layer(b.closest('.session-picture').querySelector('.sp-frame'),'mask');if(l){l.style.display='none';}}});"
                + "function openHash(){var h=location.hash.substring(1);var t=h?document.getElementById(h):null;"
                + "for(var n=t;n;n=n.parentElement){if(n.tagName==='DETAILS'){n.open=true;}}}"
                + "window.addEventListener('hashchange',openHash);openHash();"
                + "})();</script>");
    }
}
