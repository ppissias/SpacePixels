package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.DisplayImageRenderer;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
        RESIDUAL,
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

    private static final class CreativeSignalSummary {
        public final int rawTransientCount;
        public final int pointTransientCount;
        public final int streakLikeTransientCount;
        public final int unclassifiedTransientCount;
        public final int anomalyCount;
        public final int movingTrackCount;
        public final int streakTrackCount;
        public final int singleStreakCount;
        public final int suspectedStreakTrackCount;
        public final int deepStackHintCount;
        public final int totalFrames;
        public final int framesWithTransients;
        public final int peakFrameIndex;
        public final int peakFrameCount;
        public final double meanTransientsPerActiveFrame;
        public final double longestPath;
        public final String dominantMotion;

        private CreativeSignalSummary(int rawTransientCount,
                                      int pointTransientCount,
                                      int streakLikeTransientCount,
                                      int unclassifiedTransientCount,
                                      int anomalyCount,
                                      int movingTrackCount,
                                      int streakTrackCount,
                                      int singleStreakCount,
                                      int suspectedStreakTrackCount,
                                      int deepStackHintCount,
                                      int totalFrames,
                                      int framesWithTransients,
                                      int peakFrameIndex,
                                      int peakFrameCount,
                                      double meanTransientsPerActiveFrame,
                                      double longestPath,
                                      String dominantMotion) {
            this.rawTransientCount = rawTransientCount;
            this.pointTransientCount = pointTransientCount;
            this.streakLikeTransientCount = streakLikeTransientCount;
            this.unclassifiedTransientCount = unclassifiedTransientCount;
            this.anomalyCount = anomalyCount;
            this.movingTrackCount = movingTrackCount;
            this.streakTrackCount = streakTrackCount;
            this.singleStreakCount = singleStreakCount;
            this.suspectedStreakTrackCount = suspectedStreakTrackCount;
            this.deepStackHintCount = deepStackHintCount;
            this.totalFrames = totalFrames;
            this.framesWithTransients = framesWithTransients;
            this.peakFrameIndex = peakFrameIndex;
            this.peakFrameCount = peakFrameCount;
            this.meanTransientsPerActiveFrame = meanTransientsPerActiveFrame;
            this.longestPath = longestPath;
            this.dominantMotion = dominantMotion;
        }
    }

    private static final class CreativeTrackSignal {
        public final TrackLinker.Track track;
        public final String label;
        public final Color color;
        public final double pathLength;

        private CreativeTrackSignal(TrackLinker.Track track, String label, Color color, double pathLength) {
            this.track = track;
            this.label = label;
            this.color = color;
            this.pathLength = pathLength;
        }
    }

    private CreativeTributeRenderer() {
    }

    static BufferedImage createCreativeTributeImage(short[][] backgroundData,
                                                    List<List<SourceExtractor.DetectedObject>> allTransients,
                                                    List<List<SourceExtractor.DetectedObject>> unclassifiedTransients,
                                                    List<TrackLinker.AnomalyDetection> anomalies,
                                                    List<TrackLinker.Track> singleStreaks,
                                                    List<TrackLinker.Track> streakTracks,
                                                    List<TrackLinker.Track> suspectedStreakTracks,
                                                    List<TrackLinker.Track> movingTargets,
                                                    List<SourceExtractor.DetectedObject> slowMoverCandidates,
                                                    PipelineTelemetry pipelineTelemetry) {
        allTransients = safeList(allTransients);
        unclassifiedTransients = safeList(unclassifiedTransients);
        anomalies = safeList(anomalies);
        singleStreaks = safeList(singleStreaks);
        streakTracks = safeList(streakTracks);
        suspectedStreakTracks = safeList(suspectedStreakTracks);
        movingTargets = safeList(movingTargets);
        slowMoverCandidates = safeList(slowMoverCandidates);

        CreativeSignalSummary signalSummary = buildCreativeSignalSummary(
                allTransients,
                unclassifiedTransients,
                anomalies,
                singleStreaks,
                streakTracks,
                suspectedStreakTracks,
                movingTargets,
                slowMoverCandidates
        );

        CreativeTributeLayout layout = createCreativeTributeLayout(
                backgroundData,
                allTransients,
                unclassifiedTransients,
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
        drawCreativeUnclassifiedResidue(g2d, unclassifiedTransients, layout);

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
        drawRankedMotionLabels(g2d, movingTargets, streakTracks, layout);

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

        drawFrameActivityRibbon(g2d, allTransients, layout, signalSummary);

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

        String titleText = "Signal Weave of the Session";
        String subtitleText = "Creative analysis by Codex";
        int panelTextWidth = leftPanelWidth - 48;
        Font titleFont = fitFontToWidth(
                g2d,
                new Font("Segoe UI", Font.BOLD, Math.max(26, Math.min(38, layout.outputWidth / 34))),
                titleText,
                panelTextWidth,
                20.0f);
        Font subtitleFont = fitFontToWidth(
                g2d,
                new Font("Segoe UI", Font.PLAIN, Math.max(13, Math.min(19, layout.outputWidth / 75))),
                subtitleText,
                panelTextWidth,
                11.0f);
        Font detailFont = new Font("Consolas", Font.PLAIN, Math.max(12, Math.min(17, layout.outputWidth / 90)));

        int textX = panelPadding + 24;
        int y = panelPadding + 42;
        g2d.setFont(titleFont);
        g2d.setColor(Color.WHITE);
        g2d.drawString(titleText, textX, y);

        y += 28;
        g2d.setFont(subtitleFont);
        g2d.setColor(new Color(210, 190, 255));
        g2d.drawString(subtitleText, textX, y);

        y += 30;
        int metricColumnGap = Math.max(82, (leftPanelWidth - 52) / 4);
        drawCreativeMetric(g2d, textX, y, "raw", formatCompactCount(signalSummary.rawTransientCount), new Color(150, 220, 255), detailFont);
        drawCreativeMetric(g2d, textX + metricColumnGap, y, "moving", String.valueOf(signalSummary.movingTrackCount), new Color(77, 166, 255), detailFont);
        drawCreativeMetric(g2d, textX + (metricColumnGap * 2), y, "streaks", String.valueOf(signalSummary.streakTrackCount + signalSummary.singleStreakCount), new Color(255, 204, 102), detailFont);
        drawCreativeMetric(g2d, textX + (metricColumnGap * 3), y, "suspect", String.valueOf(signalSummary.suspectedStreakTrackCount), new Color(255, 128, 128), detailFont);
        y += 42;
        drawCreativeMetric(g2d, textX, y, "anomalies", String.valueOf(signalSummary.anomalyCount), new Color(255, 102, 204), detailFont);
        drawCreativeMetric(g2d, textX + metricColumnGap, y, "unresolved", formatCompactCount(signalSummary.unclassifiedTransientCount), new Color(255, 174, 92), detailFont);
        drawCreativeMetric(g2d, textX + (metricColumnGap * 2), y, "peak frame", peakFrameValue(signalSummary), new Color(166, 255, 180), detailFont);
        drawCreativeMetric(g2d, textX + (metricColumnGap * 3), y, "shape hints", String.valueOf(signalSummary.deepStackHintCount), new Color(186, 122, 255), detailFont);

        int balanceY = y + 30;
        drawSignalCompositionBar(g2d, textX, balanceY, leftPanelWidth - 48, 12, signalSummary);

        y = balanceY + 44;
        g2d.setFont(detailFont.deriveFont(Font.PLAIN, Math.max(11.0f, detailFont.getSize2D() - 1.0f)));
        g2d.setColor(new Color(220, 220, 220));
        String framesLine;
        if (pipelineTelemetry != null) {
            framesLine = "Frames kept/rejected: " + pipelineTelemetry.totalFramesKept + " / " + pipelineTelemetry.totalFramesRejected;
        } else {
            framesLine = "Frames kept/rejected: n/a";
        }
        drawFittedString(g2d, framesLine, textX, y, panelTextWidth);
        y += 21;
        drawFittedString(g2d, "Active frames: " + signalSummary.framesWithTransients + "/" + signalSummary.totalFrames
                + " | Mean active-frame load: " + String.format(Locale.US, "%.1f", signalSummary.meanTransientsPerActiveFrame), textX, y, panelTextWidth);
        y += 21;
        drawFittedString(g2d, "Dominant motion: " + signalSummary.dominantMotion
                + " | Longest confirmed path: " + String.format(Locale.US, "%.1f px", signalSummary.longestPath), textX, y, panelTextWidth);

        int legendHeight = Math.min(layout.headerHeight - (panelPadding * 2), 232);
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
        drawCreativeLegendRow(g2d, legendX + 18, legendRowY, new Color(255, 174, 92), "Unresolved residue", legendFont, CreativeLegendGlyph.RESIDUAL);
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

    private static void drawCreativeUnclassifiedResidue(Graphics2D g2d,
                                                        List<List<SourceExtractor.DetectedObject>> unclassifiedTransients,
                                                        CreativeTributeLayout layout) {
        if (unclassifiedTransients == null || unclassifiedTransients.isEmpty()) {
            return;
        }

        int totalResidualCount = countTotalTransientDetections(unclassifiedTransients);
        if (totalResidualCount == 0) {
            return;
        }

        int stride = Math.max(1, totalResidualCount / 2800);
        int sampledIndex = 0;
        Color haloColor = new Color(255, 174, 92, 52);
        Color coreColor = new Color(255, 214, 128, 110);

        for (List<SourceExtractor.DetectedObject> frameTransients : unclassifiedTransients) {
            if (frameTransients == null || frameTransients.isEmpty()) {
                continue;
            }

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
                g2d.fillRect(x - 3, y - 3, 7, 7);
                g2d.setColor(coreColor);
                g2d.fillRect(x - 1, y - 1, 3, 3);
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

    private static void drawRankedMotionLabels(Graphics2D g2d,
                                               List<TrackLinker.Track> movingTargets,
                                               List<TrackLinker.Track> streakTracks,
                                               CreativeTributeLayout layout) {
        List<CreativeTrackSignal> trackSignals = new ArrayList<>();
        addRankedSignals(trackSignals, movingTargets, "M", new Color(77, 166, 255));
        addRankedSignals(trackSignals, streakTracks, "ST", new Color(255, 204, 102));
        trackSignals.sort(Comparator.comparingDouble((CreativeTrackSignal signal) -> signal.pathLength).reversed());

        int labelCount = Math.min(4, trackSignals.size());
        Font labelFont = new Font("Consolas", Font.BOLD, Math.max(12, Math.min(18, layout.outputWidth / 90)));
        FontMetrics metrics = g2d.getFontMetrics(labelFont);
        g2d.setFont(labelFont);

        for (int i = 0; i < labelCount; i++) {
            CreativeTrackSignal signal = trackSignals.get(i);
            SourceExtractor.DetectedObject anchor = chooseTrackLabelAnchor(signal.track);
            if (anchor == null || !isInsideCreativeLayout(anchor.x, anchor.y, layout)) {
                continue;
            }

            String text = signal.label + (i + 1) + " " + String.format(Locale.US, "%.0fpx", signal.pathLength);
            int x = creativeX(anchor.x, layout) + 8;
            int y = creativeY(anchor.y, layout) - 8;
            int textWidth = metrics.stringWidth(text);
            int textHeight = metrics.getHeight();
            int clampedX = Math.max(6, Math.min(layout.outputWidth - textWidth - 12, x));
            int clampedY = Math.max(layout.plotOffsetY + textHeight + 4, Math.min(layout.canvasHeight - 60, y));

            g2d.setColor(new Color(6, 9, 14, 185));
            g2d.fillRoundRect(clampedX - 5, clampedY - textHeight + 3, textWidth + 10, textHeight + 4, 10, 10);
            g2d.setColor(new Color(signal.color.getRed(), signal.color.getGreen(), signal.color.getBlue(), 150));
            g2d.drawRoundRect(clampedX - 5, clampedY - textHeight + 3, textWidth + 10, textHeight + 4, 10, 10);
            g2d.setColor(Color.WHITE);
            g2d.drawString(text, clampedX, clampedY);
        }
    }

    private static void drawFrameActivityRibbon(Graphics2D g2d,
                                                List<List<SourceExtractor.DetectedObject>> allTransients,
                                                CreativeTributeLayout layout,
                                                CreativeSignalSummary signalSummary) {
        if (allTransients == null || allTransients.isEmpty() || signalSummary.rawTransientCount == 0) {
            return;
        }

        int margin = Math.max(18, layout.outputWidth / 55);
        int ribbonWidth = Math.max(160, layout.outputWidth - (margin * 2));
        int ribbonHeight = Math.max(34, Math.min(48, layout.outputHeight / 12));
        int ribbonX = margin;
        int ribbonY = layout.canvasHeight - ribbonHeight - Math.max(12, layout.outputHeight / 55);
        int maxBins = Math.max(80, Math.min(ribbonWidth, allTransients.size()));
        int[] bins = new int[maxBins];

        for (int frameIndex = 0; frameIndex < allTransients.size(); frameIndex++) {
            List<SourceExtractor.DetectedObject> frameTransients = allTransients.get(frameIndex);
            int count = frameTransients == null ? 0 : frameTransients.size();
            int binIndex = allTransients.size() == 1
                    ? 0
                    : (int) Math.round(frameIndex * (maxBins - 1) / (double) (allTransients.size() - 1));
            bins[binIndex] += count;
        }

        int maxBinCount = 0;
        for (int count : bins) {
            maxBinCount = Math.max(maxBinCount, count);
        }
        if (maxBinCount == 0) {
            return;
        }

        g2d.setColor(new Color(5, 8, 14, 185));
        g2d.fillRoundRect(ribbonX, ribbonY, ribbonWidth, ribbonHeight, 14, 14);
        g2d.setColor(new Color(150, 220, 255, 90));
        g2d.setStroke(new BasicStroke(1.1f));
        g2d.drawRoundRect(ribbonX, ribbonY, ribbonWidth, ribbonHeight, 14, 14);

        int chartX = ribbonX + 12;
        int chartY = ribbonY + 9;
        int chartWidth = ribbonWidth - 24;
        int chartHeight = ribbonHeight - 18;
        for (int i = 0; i < bins.length; i++) {
            float ratio = bins.length > 1 ? (float) i / (float) (bins.length - 1) : 0f;
            Color color = Color.getHSBColor(0.62f - (0.55f * ratio), 0.85f, 1.0f);
            int barHeight = Math.max(1, (int) Math.round((bins[i] / (double) maxBinCount) * chartHeight));
            int x1 = chartX + (int) Math.floor(i * (chartWidth / (double) bins.length));
            int x2 = chartX + (int) Math.floor((i + 1) * (chartWidth / (double) bins.length));
            int barWidth = Math.max(1, x2 - x1);
            g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 155));
            g2d.fillRect(x1, chartY + chartHeight - barHeight, barWidth, barHeight);
        }

        String label = "Frame activity ribbon - peak F" + signalSummary.peakFrameIndex + " (" + signalSummary.peakFrameCount + " detections)";
        Font ribbonFont = new Font("Segoe UI", Font.PLAIN, Math.max(11, Math.min(15, layout.outputWidth / 110)));
        g2d.setFont(ribbonFont);
        g2d.setColor(new Color(225, 235, 245, 210));
        g2d.drawString(label, ribbonX + 16, ribbonY - 6);
    }

    private static void drawCreativeMetric(Graphics2D g2d,
                                           int x,
                                           int y,
                                           String label,
                                           String value,
                                           Color accent,
                                           Font font) {
        g2d.setFont(font.deriveFont(Font.BOLD));
        g2d.setColor(Color.WHITE);
        g2d.drawString(value, x, y);
        g2d.setFont(font.deriveFont(Font.PLAIN, Math.max(10.0f, font.getSize2D() - 2.0f)));
        g2d.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 210));
        g2d.drawString(label.toUpperCase(Locale.ROOT), x, y + 14);
    }

    private static void drawSignalCompositionBar(Graphics2D g2d,
                                                 int x,
                                                 int y,
                                                 int width,
                                                 int height,
                                                 CreativeSignalSummary signalSummary) {
        int total = Math.max(1,
                signalSummary.pointTransientCount
                        + signalSummary.streakLikeTransientCount
                        + signalSummary.anomalyCount
                        + signalSummary.movingTrackCount
                        + signalSummary.streakTrackCount
                        + signalSummary.singleStreakCount
                        + signalSummary.suspectedStreakTrackCount
                        + signalSummary.deepStackHintCount
                        + signalSummary.unclassifiedTransientCount);
        int currentX = x;
        int barEndX = x + width;
        currentX = fillSignalSegment(g2d, currentX, y, barEndX, width, height, signalSummary.pointTransientCount, total, new Color(150, 220, 255));
        currentX = fillSignalSegment(g2d, currentX, y, barEndX, width, height, signalSummary.streakLikeTransientCount + signalSummary.streakTrackCount + signalSummary.singleStreakCount, total, new Color(255, 204, 102));
        currentX = fillSignalSegment(g2d, currentX, y, barEndX, width, height, signalSummary.anomalyCount, total, new Color(255, 102, 204));
        currentX = fillSignalSegment(g2d, currentX, y, barEndX, width, height, signalSummary.movingTrackCount, total, new Color(77, 166, 255));
        currentX = fillSignalSegment(g2d, currentX, y, barEndX, width, height, signalSummary.suspectedStreakTrackCount + signalSummary.deepStackHintCount, total, new Color(186, 122, 255));
        fillSignalSegment(g2d, currentX, y, barEndX, width, height, signalSummary.unclassifiedTransientCount, total, new Color(255, 174, 92));

        g2d.setColor(new Color(255, 255, 255, 60));
        g2d.drawRoundRect(x, y, width, height, height, height);
        g2d.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        g2d.setColor(new Color(210, 210, 220));
        drawFittedString(g2d, "signal balance: point / streak / anomaly / motion / candidates / residue", x, y + height + 13, width);
    }

    private static Font fitFontToWidth(Graphics2D g2d, Font baseFont, String text, int maxWidth, float minSize) {
        Font font = baseFont;
        while (font.getSize2D() > minSize && g2d.getFontMetrics(font).stringWidth(text) > maxWidth) {
            font = font.deriveFont(font.getSize2D() - 1.0f);
        }
        return font;
    }

    private static void drawFittedString(Graphics2D g2d, String text, int x, int y, int maxWidth) {
        FontMetrics metrics = g2d.getFontMetrics();
        if (metrics.stringWidth(text) <= maxWidth) {
            g2d.drawString(text, x, y);
            return;
        }

        String suffix = "...";
        int suffixWidth = metrics.stringWidth(suffix);
        if (suffixWidth >= maxWidth) {
            return;
        }

        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end)) + suffixWidth > maxWidth) {
            end--;
        }
        if (end > 0) {
            g2d.drawString(text.substring(0, end) + suffix, x, y);
        }
    }

    private static int fillSignalSegment(Graphics2D g2d,
                                         int currentX,
                                         int y,
                                         int barEndX,
                                         int totalWidth,
                                         int height,
                                         int value,
                                         int total,
                                         Color color) {
        if (value <= 0 || currentX >= barEndX) {
            return currentX;
        }
        int availableWidth = barEndX - currentX;
        int segmentWidth = Math.max(2, (int) Math.round(totalWidth * (value / (double) total)));
        int width = Math.min(availableWidth, segmentWidth);
        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 180));
        g2d.fillRoundRect(currentX, y, width, height, height, height);
        return currentX + width;
    }

    private static CreativeTributeLayout createCreativeTributeLayout(short[][] backgroundData,
                                                                     List<List<SourceExtractor.DetectedObject>> allTransients,
                                                                     List<List<SourceExtractor.DetectedObject>> unclassifiedTransients,
                                                                     List<TrackLinker.AnomalyDetection> anomalies,
                                                                     List<TrackLinker.Track> singleStreaks,
                                                                     List<TrackLinker.Track> streakTracks,
                                                                     List<TrackLinker.Track> suspectedStreakTracks,
                                                                     List<TrackLinker.Track> movingTargets,
                                                                     List<SourceExtractor.DetectedObject> slowMoverCandidates) {
        int imageWidth = backgroundData[0].length;
        int imageHeight = backgroundData.length;

        double[] bounds = new double[]{Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        includeCreativeTransientBounds(bounds, allTransients);
        includeCreativeTransientBounds(bounds, unclassifiedTransients);
        includeCreativeAnomalyBounds(bounds, anomalies);
        includeCreativeTrackBounds(bounds, singleStreaks);
        includeCreativeTrackBounds(bounds, streakTracks);
        includeCreativeTrackBounds(bounds, suspectedStreakTracks);
        includeCreativeTrackBounds(bounds, movingTargets);
        includeCreativeDetectionBounds(bounds, slowMoverCandidates);

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
        int headerHeight = Math.max(320, Math.min(360, outputWidth / 3));
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
            case RESIDUAL -> drawCreativeLegendResidualGlyph(g2d, x, y, color);
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

    private static void drawCreativeLegendResidualGlyph(Graphics2D g2d, int x, int y, Color color) {
        g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 50));
        g2d.fillRect(x - 2, y - 9, 20, 18);
        g2d.setColor(color);
        g2d.fillRect(x + 2, y - 5, 5, 5);
        g2d.fillRect(x + 11, y - 3, 5, 5);
        g2d.setColor(Color.WHITE);
        g2d.fillRect(x + 7, y + 3, 4, 4);
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

    private static void addRankedSignals(List<CreativeTrackSignal> trackSignals,
                                         List<TrackLinker.Track> tracks,
                                         String labelPrefix,
                                         Color color) {
        if (tracks == null) {
            return;
        }
        for (TrackLinker.Track track : tracks) {
            double pathLength = computeTrackPathLength(track);
            if (pathLength <= 0.0) {
                continue;
            }
            trackSignals.add(new CreativeTrackSignal(track, labelPrefix, color, pathLength));
        }
    }

    private static SourceExtractor.DetectedObject chooseTrackLabelAnchor(TrackLinker.Track track) {
        if (track == null || track.points == null || track.points.isEmpty()) {
            return null;
        }
        return track.points.get(track.points.size() / 2);
    }

    private static CreativeSignalSummary buildCreativeSignalSummary(List<List<SourceExtractor.DetectedObject>> allTransients,
                                                                    List<List<SourceExtractor.DetectedObject>> unclassifiedTransients,
                                                                    List<TrackLinker.AnomalyDetection> anomalies,
                                                                    List<TrackLinker.Track> singleStreaks,
                                                                    List<TrackLinker.Track> streakTracks,
                                                                    List<TrackLinker.Track> suspectedStreakTracks,
                                                                    List<TrackLinker.Track> movingTargets,
                                                                    List<SourceExtractor.DetectedObject> slowMoverCandidates) {
        int rawTransientCount = countTotalTransientDetections(allTransients);
        int streakLikeTransientCount = countStreakLikeTransientDetections(allTransients);
        int pointTransientCount = Math.max(0, rawTransientCount - streakLikeTransientCount);
        int unclassifiedTransientCount = countTotalTransientDetections(unclassifiedTransients);
        int anomalyCount = anomalies == null ? 0 : anomalies.size();
        int movingTrackCount = movingTargets == null ? 0 : movingTargets.size();
        int streakTrackCount = streakTracks == null ? 0 : streakTracks.size();
        int singleStreakCount = singleStreaks == null ? 0 : singleStreaks.size();
        int suspectedStreakTrackCount = suspectedStreakTracks == null ? 0 : suspectedStreakTracks.size();
        int deepStackHintCount = slowMoverCandidates == null ? 0 : slowMoverCandidates.size();
        int totalFrames = allTransients == null ? 0 : allTransients.size();
        int framesWithTransients = 0;
        int peakFrameIndex = 0;
        int peakFrameCount = 0;

        if (allTransients != null) {
            for (int frameIndex = 0; frameIndex < allTransients.size(); frameIndex++) {
                List<SourceExtractor.DetectedObject> frameTransients = allTransients.get(frameIndex);
                int count = frameTransients == null ? 0 : frameTransients.size();
                if (count > 0) {
                    framesWithTransients++;
                }
                if (count > peakFrameCount) {
                    peakFrameCount = count;
                    peakFrameIndex = frameIndex;
                }
            }
        }

        double meanTransientsPerActiveFrame = framesWithTransients == 0
                ? 0.0
                : rawTransientCount / (double) framesWithTransients;
        double longestPath = computeLongestTrackPathPx(streakTracks, movingTargets);
        String dominantMotion = computeDominantMotionLabel(movingTargets, streakTracks);

        return new CreativeSignalSummary(
                rawTransientCount,
                pointTransientCount,
                streakLikeTransientCount,
                unclassifiedTransientCount,
                anomalyCount,
                movingTrackCount,
                streakTrackCount,
                singleStreakCount,
                suspectedStreakTrackCount,
                deepStackHintCount,
                totalFrames,
                framesWithTransients,
                peakFrameIndex,
                peakFrameCount,
                meanTransientsPerActiveFrame,
                longestPath,
                dominantMotion
        );
    }

    private static String peakFrameValue(CreativeSignalSummary signalSummary) {
        if (signalSummary.peakFrameCount <= 0) {
            return "n/a";
        }
        return "F" + signalSummary.peakFrameIndex;
    }

    private static String formatCompactCount(int value) {
        if (value >= 1_000_000) {
            return String.format(Locale.US, "%.1fM", value / 1_000_000.0);
        }
        if (value >= 10_000) {
            return String.format(Locale.US, "%.1fk", value / 1_000.0);
        }
        return Integer.toString(value);
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? Collections.emptyList() : values;
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

    static int countStreakLikeTransientDetections(List<List<SourceExtractor.DetectedObject>> allTransients) {
        if (allTransients == null || allTransients.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (List<SourceExtractor.DetectedObject> frameTransients : allTransients) {
            if (frameTransients == null) {
                continue;
            }
            for (SourceExtractor.DetectedObject transientPoint : frameTransients) {
                if (transientPoint != null && transientPoint.isStreak) {
                    total++;
                }
            }
        }
        return total;
    }

    static String computePeakTransientFrameLabel(List<List<SourceExtractor.DetectedObject>> allTransients) {
        if (allTransients == null || allTransients.isEmpty()) {
            return "n/a";
        }

        int peakFrameIndex = 0;
        int peakFrameCount = 0;
        for (int frameIndex = 0; frameIndex < allTransients.size(); frameIndex++) {
            List<SourceExtractor.DetectedObject> frameTransients = allTransients.get(frameIndex);
            int count = frameTransients == null ? 0 : frameTransients.size();
            if (count > peakFrameCount) {
                peakFrameCount = count;
                peakFrameIndex = frameIndex;
            }
        }

        if (peakFrameCount == 0) {
            return "n/a";
        }
        return "F" + peakFrameIndex + " (" + peakFrameCount + ")";
    }

    static String buildCreativeSignalInterpretation(List<List<SourceExtractor.DetectedObject>> allTransients,
                                                    List<List<SourceExtractor.DetectedObject>> unclassifiedTransients,
                                                    List<TrackLinker.AnomalyDetection> anomalies,
                                                    List<TrackLinker.Track> singleStreaks,
                                                    List<TrackLinker.Track> streakTracks,
                                                    List<TrackLinker.Track> suspectedStreakTracks,
                                                    List<TrackLinker.Track> movingTargets,
                                                    List<SourceExtractor.DetectedObject> slowMoverCandidates) {
        CreativeSignalSummary signalSummary = buildCreativeSignalSummary(
                safeList(allTransients),
                safeList(unclassifiedTransients),
                safeList(anomalies),
                safeList(singleStreaks),
                safeList(streakTracks),
                safeList(suspectedStreakTracks),
                safeList(movingTargets),
                safeList(slowMoverCandidates)
        );

        String opening;
        if (signalSummary.movingTrackCount > 0 && signalSummary.streakTrackCount > 0) {
            opening = "The run contains both slower linked motion and faster streak geometry.";
        } else if (signalSummary.movingTrackCount > 0) {
            opening = "The strongest structure is slower linked motion across multiple frames.";
        } else if (signalSummary.streakTrackCount + signalSummary.singleStreakCount + signalSummary.suspectedStreakTrackCount > 0) {
            opening = "The strongest structure is streak-like: brief, directional energy rather than point-track drift.";
        } else if (signalSummary.anomalyCount > 0) {
            opening = "The run is dominated by isolated high-energy flashes that did not form tracks.";
        } else {
            opening = "The section is mostly a transient-field texture because no strong linked target dominated the run.";
        }

        String unresolved;
        if (signalSummary.unclassifiedTransientCount > 0) {
            unresolved = " The amber residue marks " + signalSummary.unclassifiedTransientCount
                    + " surviving detections that remained unclassified after tracking and anomaly rescue.";
        } else {
            unresolved = " No leftover unclassified transient population remained after classification.";
        }

        return opening + unresolved + " The activity ribbon peaks at "
                + computePeakTransientFrameLabel(allTransients)
                + ", while confirmed linked motion trends "
                + signalSummary.dominantMotion
                + ".";
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
