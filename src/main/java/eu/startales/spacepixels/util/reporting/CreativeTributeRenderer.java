package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.DisplayImageRenderer;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;

/**
 * Renders the optional poster-style "AI perspective" summary image used at the end of exported
 * detection reports.
 */
final class CreativeTributeRenderer {

    private enum CreativeLegendGlyph {
        TRACK,
        STREAK,
        PULSE,
        DIAMOND,
        DUST
    }

    private static class CreativeTributeLayout {
        public final int cropX;
        public final int cropY;
        public final int cropWidth;
        public final int cropHeight;
        public final int outputWidth;
        public final int outputHeight;
        public final int headerHeight;
        public final int canvasHeight;
        public final int plotOffsetY;
        public final double scale;

        private CreativeTributeLayout(int cropX,
                                      int cropY,
                                      int cropWidth,
                                      int cropHeight,
                                      int outputWidth,
                                      int outputHeight,
                                      int headerHeight,
                                      int canvasHeight,
                                      int plotOffsetY,
                                      double scale) {
            this.cropX = cropX;
            this.cropY = cropY;
            this.cropWidth = cropWidth;
            this.cropHeight = cropHeight;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
            this.headerHeight = headerHeight;
            this.canvasHeight = canvasHeight;
            this.plotOffsetY = plotOffsetY;
            this.scale = scale;
        }
    }

    private CreativeTributeRenderer() {
    }

    static BufferedImage createCreativeTributeImage(short[][] backgroundData,
                                                    List<List<SourceExtractor.DetectedObject>> allTransients,
                                                    List<TrackLinker.AnomalyDetection> anomalies,
                                                    List<TrackLinker.Track> singleStreaks,
                                                    List<TrackLinker.Track> streakTracks,
                                                    List<TrackLinker.Track> suspectedStreakTracks,
                                                    List<TrackLinker.Track> movingTargets,
                                                    List<SourceExtractor.DetectedObject> slowMoverCandidates,
                                                    PipelineTelemetry pipelineTelemetry) {
        CreativeTributeLayout layout = createCreativeTributeLayout(
                backgroundData,
                allTransients,
                anomalies,
                singleStreaks,
                streakTracks,
                suspectedStreakTracks,
                movingTargets,
                slowMoverCandidates
        );

        short[][] croppedBackground = TrackVisualizationRenderer.robustEdgeAwareCrop(
                backgroundData,
                layout.cropX + (layout.cropWidth / 2),
                layout.cropY + (layout.cropHeight / 2),
                layout.cropWidth,
                layout.cropHeight
        );
        BufferedImage grayBg = DisplayImageRenderer.createDisplayImage(croppedBackground);
        BufferedImage tribute = new BufferedImage(layout.outputWidth, layout.canvasHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = tribute.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g2d.setColor(new Color(5, 8, 14));
        g2d.fillRect(0, 0, layout.outputWidth, layout.canvasHeight);
        g2d.setPaint(new GradientPaint(
                0, 0, new Color(12, 15, 24),
                0, layout.headerHeight, new Color(18, 22, 32)
        ));
        g2d.fillRect(0, 0, layout.outputWidth, layout.headerHeight);
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.28f));
        g2d.drawImage(grayBg, 0, layout.plotOffsetY, layout.outputWidth, layout.outputHeight, null);
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));

        g2d.setPaint(new GradientPaint(
                0, layout.plotOffsetY, new Color(32, 58, 96, 90),
                layout.outputWidth, layout.canvasHeight, new Color(96, 28, 72, 20)
        ));
        g2d.fillRect(0, layout.plotOffsetY, layout.outputWidth, layout.outputHeight);

        drawCreativeTransientDust(g2d, allTransients, layout);

        for (TrackLinker.Track track : movingTargets) {
            drawGlowingTrack(g2d, track, layout, new Color(77, 166, 255), 2.6f, 8.5f);
        }
        for (TrackLinker.Track track : streakTracks) {
            drawGlowingTrack(g2d, track, layout, new Color(255, 204, 102), 2.8f, 9.5f);
        }
        for (TrackLinker.Track track : suspectedStreakTracks) {
            drawGlowingTrack(g2d, track, layout, new Color(255, 128, 128), 2.4f, 8.2f);
        }
        for (TrackLinker.AnomalyDetection anomaly : anomalies) {
            if (anomaly != null && anomaly.object != null) {
                drawCreativePulse(g2d, anomaly.object, layout, new Color(255, 102, 204));
            }
        }
        for (TrackLinker.Track track : singleStreaks) {
            if (track.points != null && !track.points.isEmpty()) {
                drawCreativeMeasuredStreak(g2d, track.points.get(0), layout, new Color(255, 153, 51), 1.10);
            }
        }
        if (slowMoverCandidates != null) {
            for (SourceExtractor.DetectedObject candidate : slowMoverCandidates) {
                drawCreativeDiamond(g2d, candidate, layout, new Color(186, 122, 255), 16);
            }
        }

        float vignetteRadius = (float) (Math.max(layout.outputWidth, layout.outputHeight) * 0.82);
        RadialGradientPaint vignette = new RadialGradientPaint(
                new Point(layout.outputWidth / 2, layout.plotOffsetY + (layout.outputHeight / 2)),
                vignetteRadius,
                new float[]{0.0f, 0.70f, 1.0f},
                new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 55), new Color(0, 0, 0, 180)}
        );
        g2d.setPaint(vignette);
        g2d.fillRect(0, layout.plotOffsetY, layout.outputWidth, layout.outputHeight);

        g2d.setColor(new Color(76, 96, 132, 70));
        g2d.setStroke(new BasicStroke(1.2f));
        g2d.drawLine(0, layout.plotOffsetY, layout.outputWidth, layout.plotOffsetY);

        int panelPadding = Math.max(18, Math.min(34, layout.outputWidth / 35));
        int interPanelGap = Math.max(14, panelPadding / 2);
        int leftPanelWidth = Math.max(340, (int) Math.round(layout.outputWidth * 0.50));
        int maxLegendWidth = layout.outputWidth - leftPanelWidth - (panelPadding * 2) - interPanelGap;
        int legendWidth = Math.max(250, Math.min((int) Math.round(layout.outputWidth * 0.28), maxLegendWidth));
        if (legendWidth > maxLegendWidth) {
            legendWidth = maxLegendWidth;
            leftPanelWidth = layout.outputWidth - legendWidth - (panelPadding * 2) - interPanelGap;
        }
        int leftPanelHeight = layout.headerHeight - (panelPadding * 2);
        g2d.setColor(new Color(10, 10, 16, 190));
        g2d.fillRoundRect(panelPadding, panelPadding, leftPanelWidth, leftPanelHeight, 24, 24);
        g2d.setColor(new Color(150, 120, 255, 140));
        g2d.setStroke(new BasicStroke(1.6f));
        g2d.drawRoundRect(panelPadding, panelPadding, leftPanelWidth, leftPanelHeight, 24, 24);

        Font titleFont = new Font("Segoe UI", Font.BOLD, Math.max(26, Math.min(38, layout.outputWidth / 34)));
        Font subtitleFont = new Font("Segoe UI", Font.PLAIN, Math.max(13, Math.min(19, layout.outputWidth / 75)));
        Font detailFont = new Font("Consolas", Font.PLAIN, Math.max(12, Math.min(17, layout.outputWidth / 90)));

        int textX = panelPadding + 24;
        int y = panelPadding + 42;
        g2d.setFont(titleFont);
        g2d.setColor(Color.WHITE);
        g2d.drawString("Skyprint of the Session", textX, y);

        y += 28;
        g2d.setFont(subtitleFont);
        g2d.setColor(new Color(210, 190, 255));
        g2d.drawString("Creative tribute by Codex", textX, y);

        int rawTransientCount = countTotalTransientDetections(allTransients);
        int confirmedTrackCount = movingTargets.size() + streakTracks.size();
        int suspectedStreakCount = suspectedStreakTracks == null ? 0 : suspectedStreakTracks.size();
        int deepStackHintCount = slowMoverCandidates == null ? 0 : slowMoverCandidates.size();
        double longestPath = computeLongestTrackPathPx(streakTracks, movingTargets);
        String dominantMotion = computeDominantMotionLabel(movingTargets, streakTracks);

        y += 32;
        g2d.setFont(detailFont);
        g2d.setColor(new Color(220, 220, 220));
        String framesLine;
        if (pipelineTelemetry != null) {
            framesLine = "Frames kept/rejected: " + pipelineTelemetry.totalFramesKept + " / " + pipelineTelemetry.totalFramesRejected;
        } else {
            framesLine = "Frames kept/rejected: n/a";
        }
        g2d.drawString(framesLine, textX, y);
        y += 24;
        g2d.drawString("Raw transients: " + rawTransientCount + " | Confirmed tracks: " + confirmedTrackCount, textX, y);
        y += 24;
        g2d.drawString("Suspected streak tracks: " + suspectedStreakCount + " | Anomalies: " + anomalies.size(), textX, y);
        y += 24;
        g2d.drawString("Single streaks: " + singleStreaks.size() + " | Deep-stack hints: " + deepStackHintCount, textX, y);
        y += 24;
        g2d.drawString("Dominant confirmed motion: " + dominantMotion + " | Longest confirmed path: " + String.format(Locale.US, "%.1f px", longestPath), textX, y);

        int legendHeight = Math.min(layout.headerHeight - (panelPadding * 2), 204);
        int legendX = panelPadding + leftPanelWidth + interPanelGap;
        int legendY = panelPadding;
        g2d.setColor(new Color(10, 10, 16, 175));
        g2d.fillRoundRect(legendX, legendY, legendWidth, legendHeight, 22, 22);
        g2d.setColor(new Color(77, 166, 255, 120));
        g2d.drawRoundRect(legendX, legendY, legendWidth, legendHeight, 22, 22);

        g2d.setFont(new Font("Segoe UI", Font.BOLD, Math.max(14, Math.min(18, layout.outputWidth / 85))));
        g2d.setColor(Color.WHITE);
        g2d.drawString("What the colors and symbols mean", legendX + 18, legendY + 28);

        int legendRowY = legendY + 52;
        Font legendFont = new Font("Segoe UI", Font.PLAIN, Math.max(12, Math.min(15, layout.outputWidth / 95)));
        drawCreativeLegendRow(g2d, legendX + 18, legendRowY, new Color(66, 210, 255), "Moving object track (line + nodes)", legendFont, CreativeLegendGlyph.TRACK);
        legendRowY += 24;
        drawCreativeLegendRow(g2d, legendX + 18, legendRowY, new Color(255, 204, 102), "Confirmed streak track / single streak", legendFont, CreativeLegendGlyph.STREAK);
        legendRowY += 24;
        drawCreativeLegendRow(g2d, legendX + 18, legendRowY, new Color(255, 128, 128), "Suspected streak grouping", legendFont, CreativeLegendGlyph.TRACK);
        legendRowY += 24;
        drawCreativeLegendRow(g2d, legendX + 18, legendRowY, new Color(255, 102, 204), "Anomaly pulse (circle + crosshair)", legendFont, CreativeLegendGlyph.PULSE);
        legendRowY += 24;
        drawCreativeLegendRow(g2d, legendX + 18, legendRowY, new Color(186, 122, 255), "Deep-stack hint (diamond)", legendFont, CreativeLegendGlyph.DIAMOND);
        legendRowY += 24;
        drawCreativeLegendRow(g2d, legendX + 18, legendRowY, new Color(150, 220, 255), "Transient dust time map (cyan -> magenta)", legendFont, CreativeLegendGlyph.DUST);

        g2d.dispose();
        return tribute;
    }

    private static void drawCreativeTransientDust(Graphics2D g2d,
                                                  List<List<SourceExtractor.DetectedObject>> allTransients,
                                                  CreativeTributeLayout layout) {
        if (allTransients == null || allTransients.isEmpty()) {
            return;
        }

        int totalTransientCount = countTotalTransientDetections(allTransients);
        if (totalTransientCount == 0) {
            return;
        }

        int stride = Math.max(1, totalTransientCount / 9000);
        int sampledIndex = 0;
        int totalFrames = allTransients.size();

        for (int frameIndex = 0; frameIndex < totalFrames; frameIndex++) {
            List<SourceExtractor.DetectedObject> frameTransients = allTransients.get(frameIndex);
            if (frameTransients == null || frameTransients.isEmpty()) {
                continue;
            }

            float ratio = totalFrames > 1 ? (float) frameIndex / (float) (totalFrames - 1) : 0f;
            Color timeColor = Color.getHSBColor(0.62f - (0.55f * ratio), 0.85f, 1.0f);
            Color haloColor = new Color(timeColor.getRed(), timeColor.getGreen(), timeColor.getBlue(), 42);
            Color coreColor = new Color(timeColor.getRed(), timeColor.getGreen(), timeColor.getBlue(), 78);

            for (SourceExtractor.DetectedObject transientPoint : frameTransients) {
                if ((sampledIndex++ % stride) != 0) {
                    continue;
                }

                if (!isInsideCreativeLayout(transientPoint.x, transientPoint.y, layout)) {
                    continue;
                }

                int x = creativeX(transientPoint.x, layout);
                int y = creativeY(transientPoint.y, layout);
                g2d.setColor(haloColor);
                g2d.fillOval(x - 2, y - 2, 6, 6);
                g2d.setColor(coreColor);
                g2d.fillOval(x - 1, y - 1, 3, 3);
            }
        }
    }

    private static void drawGlowingTrack(Graphics2D g2d,
                                         TrackLinker.Track track,
                                         CreativeTributeLayout layout,
                                         Color color,
                                         float coreWidth,
                                         float glowWidth) {
        if (track == null || track.points == null || track.points.isEmpty()) {
            return;
        }

        float strokeScale = (float) Math.max(0.9, Math.min(1.8, layout.scale));
        Color glowColor = new Color(color.getRed(), color.getGreen(), color.getBlue(), 60);
        g2d.setStroke(new BasicStroke(glowWidth * strokeScale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.setColor(glowColor);
        for (int i = 0; i < track.points.size() - 1; i++) {
            SourceExtractor.DetectedObject p1 = track.points.get(i);
            SourceExtractor.DetectedObject p2 = track.points.get(i + 1);
            g2d.drawLine(creativeX(p1.x, layout), creativeY(p1.y, layout), creativeX(p2.x, layout), creativeY(p2.y, layout));
        }

        g2d.setStroke(new BasicStroke(coreWidth * strokeScale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.setColor(color);
        for (int i = 0; i < track.points.size() - 1; i++) {
            SourceExtractor.DetectedObject p1 = track.points.get(i);
            SourceExtractor.DetectedObject p2 = track.points.get(i + 1);
            g2d.drawLine(creativeX(p1.x, layout), creativeY(p1.y, layout), creativeX(p2.x, layout), creativeY(p2.y, layout));
        }

        int markerRadius = Math.max(4, (int) Math.round(5 * strokeScale));
        for (SourceExtractor.DetectedObject point : track.points) {
            int x = creativeX(point.x, layout);
            int y = creativeY(point.y, layout);
            g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 80));
            g2d.fillOval(x - markerRadius, y - markerRadius, markerRadius * 2, markerRadius * 2);
            g2d.setColor(Color.WHITE);
            g2d.fillOval(x - 2, y - 2, 4, 4);
        }
    }

    private static void drawCreativePulse(Graphics2D g2d,
                                          SourceExtractor.DetectedObject detection,
                                          CreativeTributeLayout layout,
                                          Color color) {
        int cx = creativeX(detection.x, layout);
        int cy = creativeY(detection.y, layout);
        int outerRadius = Math.max(22, (int) Math.round(30 * Math.max(0.9, Math.min(1.7, layout.scale))));
        int innerRadius = Math.max(14, (int) Math.round(18 * Math.max(0.9, Math.min(1.7, layout.scale))));

        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 42));
        g2d.fillOval(cx - outerRadius, cy - outerRadius, outerRadius * 2, outerRadius * 2);
        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 145));
        g2d.setStroke(new BasicStroke(2.0f));
        g2d.drawOval(cx - innerRadius, cy - innerRadius, innerRadius * 2, innerRadius * 2);
        g2d.drawLine(cx - outerRadius + 2, cy, cx + outerRadius - 2, cy);
        g2d.drawLine(cx, cy - outerRadius + 2, cx, cy + outerRadius - 2);
        g2d.setColor(Color.WHITE);
        g2d.fillOval(cx - 3, cy - 3, 6, 6);
    }

    private static void drawCreativeMeasuredStreak(Graphics2D g2d,
                                                   SourceExtractor.DetectedObject detection,
                                                   CreativeTributeLayout layout,
                                                   Color color,
                                                   double lengthScale) {
        if (detection == null || !isInsideCreativeLayout(detection.x, detection.y, layout)) {
            return;
        }

        int cx = creativeX(detection.x, layout);
        int cy = creativeY(detection.y, layout);
        double elongation = Math.max(1.0, detection.elongation);
        double semiMajorAxis = detection.pixelArea > 0
                ? TrackCropGeometry.computeFootprintRadius(detection.pixelArea, true, elongation)
                : 9.0;
        double measuredLength = Math.max(18.0, semiMajorAxis * 2.0);
        double length = measuredLength * lengthScale * Math.max(0.95, Math.min(1.25, layout.scale));
        double maxLength = Math.max(42.0, Math.min(layout.outputWidth, layout.outputHeight) * 0.10);
        length = Math.min(length, maxLength);
        double angleRad = Double.isFinite(detection.angle) ? detection.angle : 0.0;
        int dx = (int) Math.round(Math.cos(angleRad) * length * 0.5);
        int dy = (int) Math.round(Math.sin(angleRad) * length * 0.5);

        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 55));
        g2d.setStroke(new BasicStroke(9.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.drawLine(cx - dx, cy - dy, cx + dx, cy + dy);

        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.drawLine(cx - dx, cy - dy, cx + dx, cy + dy);
        g2d.setColor(Color.WHITE);
        g2d.fillOval(cx - 2, cy - 2, 4, 4);
    }

    private static void drawCreativeDiamond(Graphics2D g2d,
                                            SourceExtractor.DetectedObject detection,
                                            CreativeTributeLayout layout,
                                            Color color,
                                            int radius) {
        int cx = creativeX(detection.x, layout);
        int cy = creativeY(detection.y, layout);
        int scaledRadius = Math.max(12, (int) Math.round(radius * Math.max(0.9, Math.min(1.5, layout.scale))));

        Polygon diamond = new Polygon(
                new int[]{cx, cx + scaledRadius, cx, cx - scaledRadius},
                new int[]{cy - scaledRadius, cy, cy + scaledRadius, cy},
                4
        );

        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 34));
        g2d.fillPolygon(diamond);
        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(2.2f));
        g2d.drawPolygon(diamond);
        g2d.setColor(Color.WHITE);
        g2d.fillOval(cx - 2, cy - 2, 4, 4);
    }

    private static CreativeTributeLayout createCreativeTributeLayout(short[][] backgroundData,
                                                                     List<List<SourceExtractor.DetectedObject>> allTransients,
                                                                     List<TrackLinker.AnomalyDetection> anomalies,
                                                                     List<TrackLinker.Track> singleStreaks,
                                                                     List<TrackLinker.Track> streakTracks,
                                                                     List<TrackLinker.Track> suspectedStreakTracks,
                                                                     List<TrackLinker.Track> movingTargets,
                                                                     List<SourceExtractor.DetectedObject> slowMoverCandidates) {
        int imageWidth = backgroundData[0].length;
        int imageHeight = backgroundData.length;

        double[] bounds = new double[]{Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        includeCreativeAnomalyBounds(bounds, anomalies);
        includeCreativeTrackBounds(bounds, singleStreaks);
        includeCreativeTrackBounds(bounds, streakTracks);
        includeCreativeTrackBounds(bounds, suspectedStreakTracks);
        includeCreativeTrackBounds(bounds, movingTargets);
        includeCreativeDetectionBounds(bounds, slowMoverCandidates);

        if (bounds[0] == Double.MAX_VALUE) {
            includeCreativeTransientBounds(bounds, allTransients);
        }

        int cropX = 0;
        int cropY = 0;
        int cropWidth = imageWidth;
        int cropHeight = imageHeight;

        if (bounds[0] != Double.MAX_VALUE) {
            int minX = Math.max(0, (int) Math.floor(bounds[0]));
            int minY = Math.max(0, (int) Math.floor(bounds[1]));
            int maxX = Math.min(imageWidth - 1, (int) Math.ceil(bounds[2]));
            int maxY = Math.min(imageHeight - 1, (int) Math.ceil(bounds[3]));
            int spanWidth = Math.max(1, maxX - minX + 1);
            int spanHeight = Math.max(1, maxY - minY + 1);
            int padding = Math.max(140, (int) Math.round(Math.max(spanWidth, spanHeight) * 0.28));

            cropX = Math.max(0, minX - padding);
            cropY = Math.max(0, minY - padding);
            int cropMaxX = Math.min(imageWidth - 1, maxX + padding);
            int cropMaxY = Math.min(imageHeight - 1, maxY + padding);
            cropWidth = Math.max(1, cropMaxX - cropX + 1);
            cropHeight = Math.max(1, cropMaxY - cropY + 1);
        }

        int maxDim = Math.max(cropWidth, cropHeight);
        double scale = maxDim > 1600 ? (1600.0 / maxDim) : 1.0;
        if (maxDim < 1000) {
            scale = Math.min(2.1, 1000.0 / maxDim);
        }

        int outputWidth = Math.max(720, (int) Math.round(cropWidth * scale));
        int outputHeight = Math.max(480, (int) Math.round(cropHeight * scale));
        scale = Math.min((double) outputWidth / cropWidth, (double) outputHeight / cropHeight);
        outputWidth = Math.max(1, (int) Math.round(cropWidth * scale));
        outputHeight = Math.max(1, (int) Math.round(cropHeight * scale));
        int headerHeight = Math.max(270, Math.min(320, outputWidth / 3));
        int canvasHeight = outputHeight + headerHeight;
        int plotOffsetY = headerHeight;

        return new CreativeTributeLayout(cropX, cropY, cropWidth, cropHeight, outputWidth, outputHeight, headerHeight, canvasHeight, plotOffsetY, scale);
    }

    private static void includeCreativeTrackBounds(double[] bounds, List<TrackLinker.Track> tracks) {
        if (tracks == null) {
            return;
        }

        for (TrackLinker.Track track : tracks) {
            if (track == null || track.points == null) {
                continue;
            }
            for (SourceExtractor.DetectedObject point : track.points) {
                includeCreativePoint(bounds, point.x, point.y);
            }
        }
    }

    private static void includeCreativeAnomalyBounds(double[] bounds, List<TrackLinker.AnomalyDetection> anomalies) {
        if (anomalies == null) {
            return;
        }

        for (TrackLinker.AnomalyDetection anomaly : anomalies) {
            if (anomaly == null || anomaly.object == null) {
                continue;
            }
            includeCreativePoint(bounds, anomaly.object.x, anomaly.object.y);
        }
    }

    private static void includeCreativeDetectionBounds(double[] bounds, List<SourceExtractor.DetectedObject> detections) {
        if (detections == null) {
            return;
        }

        for (SourceExtractor.DetectedObject detection : detections) {
            includeCreativePoint(bounds, detection.x, detection.y);
        }
    }

    private static void includeCreativeTransientBounds(double[] bounds, List<List<SourceExtractor.DetectedObject>> allTransients) {
        if (allTransients == null) {
            return;
        }

        for (List<SourceExtractor.DetectedObject> frameTransients : allTransients) {
            if (frameTransients == null) {
                continue;
            }
            for (SourceExtractor.DetectedObject transientPoint : frameTransients) {
                includeCreativePoint(bounds, transientPoint.x, transientPoint.y);
            }
        }
    }

    private static void includeCreativePoint(double[] bounds, double x, double y) {
        if (x < bounds[0]) {
            bounds[0] = x;
        }
        if (y < bounds[1]) {
            bounds[1] = y;
        }
        if (x > bounds[2]) {
            bounds[2] = x;
        }
        if (y > bounds[3]) {
            bounds[3] = y;
        }
    }

    private static boolean isInsideCreativeLayout(double x, double y, CreativeTributeLayout layout) {
        return x >= layout.cropX
                && x < layout.cropX + layout.cropWidth
                && y >= layout.cropY
                && y < layout.cropY + layout.cropHeight;
    }

    private static int creativeX(double x, CreativeTributeLayout layout) {
        return (int) Math.round((x - layout.cropX) * layout.scale);
    }

    private static int creativeY(double y, CreativeTributeLayout layout) {
        return layout.plotOffsetY + (int) Math.round((y - layout.cropY) * layout.scale);
    }

    private static void drawCreativeLegendRow(Graphics2D g2d,
                                              int x,
                                              int y,
                                              Color color,
                                              String label,
                                              Font font,
                                              CreativeLegendGlyph glyph) {
        switch (glyph) {
            case TRACK -> drawCreativeLegendTrackGlyph(g2d, x, y, color);
            case STREAK -> drawCreativeLegendStreakGlyph(g2d, x, y, color);
            case PULSE -> drawCreativeLegendPulseGlyph(g2d, x, y, color);
            case DIAMOND -> drawCreativeLegendDiamondGlyph(g2d, x, y, color);
            case DUST -> drawCreativeLegendDustGlyph(g2d, x, y);
        }
        g2d.setFont(font);
        g2d.setColor(new Color(225, 225, 225));
        g2d.drawString(label, x + 34, y + 2);
    }

    private static void drawCreativeLegendTrackGlyph(Graphics2D g2d, int x, int y, Color color) {
        Stroke previousStroke = g2d.getStroke();
        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 70));
        g2d.setStroke(new BasicStroke(6.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.drawLine(x, y, x + 18, y - 3);
        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.drawLine(x, y, x + 18, y - 3);
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x - 2, y - 2, 4, 4);
        g2d.fillOval(x + 16, y - 5, 4, 4);
        g2d.setStroke(previousStroke);
    }

    private static void drawCreativeLegendStreakGlyph(Graphics2D g2d, int x, int y, Color color) {
        Stroke previousStroke = g2d.getStroke();
        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 60));
        g2d.setStroke(new BasicStroke(8.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.drawLine(x, y + 4, x + 18, y - 4);
        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.drawLine(x, y + 4, x + 18, y - 4);
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x + 8, y - 2, 4, 4);
        g2d.setStroke(previousStroke);
    }

    private static void drawCreativeLegendPulseGlyph(Graphics2D g2d, int x, int y, Color color) {
        Stroke previousStroke = g2d.getStroke();
        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 38));
        g2d.fillOval(x - 2, y - 10, 20, 20);
        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(1.8f));
        g2d.drawOval(x + 1, y - 7, 14, 14);
        g2d.drawLine(x - 1, y, x + 17, y);
        g2d.drawLine(x + 8, y - 9, x + 8, y + 9);
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x + 6, y - 2, 4, 4);
        g2d.setStroke(previousStroke);
    }

    private static void drawCreativeLegendDiamondGlyph(Graphics2D g2d, int x, int y, Color color) {
        Polygon diamond = new Polygon(
                new int[]{x + 8, x + 16, x + 8, x},
                new int[]{y - 8, y, y + 8, y},
                4
        );
        Stroke previousStroke = g2d.getStroke();
        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 34));
        g2d.fillPolygon(diamond);
        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(2.0f));
        g2d.drawPolygon(diamond);
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x + 6, y - 2, 4, 4);
        g2d.setStroke(previousStroke);
    }

    private static void drawCreativeLegendDustGlyph(Graphics2D g2d, int x, int y) {
        Color[] dustColors = new Color[]{
                new Color(150, 220, 255),
                new Color(100, 255, 190),
                new Color(255, 214, 112),
                new Color(255, 120, 210)
        };
        int[] offsets = new int[]{0, 6, 12, 18};
        for (int i = 0; i < dustColors.length; i++) {
            Color color = dustColors[i];
            int cx = x + offsets[i];
            g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 42));
            g2d.fillOval(cx - 3, y - 3, 8, 8);
            g2d.setColor(color);
            g2d.fillOval(cx - 1, y - 1, 4, 4);
        }
    }

    static int countTotalTransientDetections(List<List<SourceExtractor.DetectedObject>> allTransients) {
        if (allTransients == null || allTransients.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (List<SourceExtractor.DetectedObject> frameTransients : allTransients) {
            if (frameTransients != null) {
                total += frameTransients.size();
            }
        }
        return total;
    }

    static double computeLongestTrackPathPx(List<TrackLinker.Track> streakTracks, List<TrackLinker.Track> movingTargets) {
        double longest = 0.0;

        if (movingTargets != null) {
            for (TrackLinker.Track track : movingTargets) {
                double pathLength = computeTrackPathLength(track);
                if (pathLength > longest) {
                    longest = pathLength;
                }
            }
        }
        if (streakTracks != null) {
            for (TrackLinker.Track track : streakTracks) {
                double pathLength = computeTrackPathLength(track);
                if (pathLength > longest) {
                    longest = pathLength;
                }
            }
        }

        return longest;
    }

    private static double computeTrackPathLength(TrackLinker.Track track) {
        if (track == null || track.points == null || track.points.size() < 2) {
            return 0.0;
        }

        double total = 0.0;
        for (int i = 0; i < track.points.size() - 1; i++) {
            SourceExtractor.DetectedObject p1 = track.points.get(i);
            SourceExtractor.DetectedObject p2 = track.points.get(i + 1);
            total += Math.hypot(p2.x - p1.x, p2.y - p1.y);
        }
        return total;
    }

    static String computeDominantMotionLabel(List<TrackLinker.Track> movingTargets, List<TrackLinker.Track> streakTracks) {
        double sumDx = 0.0;
        double sumDy = 0.0;
        int contributors = 0;

        if (movingTargets != null) {
            for (TrackLinker.Track track : movingTargets) {
                if (track != null && track.points != null && track.points.size() >= 2) {
                    SourceExtractor.DetectedObject first = track.points.get(0);
                    SourceExtractor.DetectedObject last = track.points.get(track.points.size() - 1);
                    sumDx += last.x - first.x;
                    sumDy += last.y - first.y;
                    contributors++;
                }
            }
        }
        if (streakTracks != null) {
            for (TrackLinker.Track track : streakTracks) {
                if (track != null && track.points != null && track.points.size() >= 2) {
                    SourceExtractor.DetectedObject first = track.points.get(0);
                    SourceExtractor.DetectedObject last = track.points.get(track.points.size() - 1);
                    sumDx += last.x - first.x;
                    sumDy += last.y - first.y;
                    contributors++;
                }
            }
        }

        if (contributors == 0) {
            return "not established";
        }

        String vertical = "";
        String horizontal = "";

        if (sumDy < -5.0) {
            vertical = "north";
        } else if (sumDy > 5.0) {
            vertical = "south";
        }

        if (sumDx > 5.0) {
            horizontal = "east";
        } else if (sumDx < -5.0) {
            horizontal = "west";
        }

        if (!vertical.isEmpty() && !horizontal.isEmpty()) {
            return vertical + "-" + horizontal;
        }
        if (!horizontal.isEmpty()) {
            return horizontal;
        }
        if (!vertical.isEmpty()) {
            return vertical;
        }
        return "mixed / stationary";
    }
}
