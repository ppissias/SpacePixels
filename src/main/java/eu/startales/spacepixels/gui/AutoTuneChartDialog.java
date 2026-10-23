/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */

package eu.startales.spacepixels.gui;

import io.github.ppissias.jtransient.engine.AutoTuneProfile;
import io.github.ppissias.jtransient.engine.CalibratedAutoTuner;
import io.github.ppissias.jtransient.engine.CalibratedAutoTuner.Candidate;
import io.github.ppissias.jtransient.engine.CalibratedAutoTuner.ProfileTradeoff;
import io.github.ppissias.jtransient.engine.CalibratedAutoTuner.SigmaChoice;
import io.github.ppissias.jtransient.engine.CalibratedAutoTuner.Tradeoff;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The two charts that explain a calibrated auto-tune: false detections against test stars found,
 * and the recovery curve of the chosen setting.
 */
final class AutoTuneChartDialog extends JDialog {

    private static final Color WITHIN = new Color(70, 130, 210);
    private static final Color OUTSIDE = new Color(150, 150, 150);
    private static final Color CHOSEN = new Color(214, 122, 38);
    private static final Color BUDGET = new Color(190, 70, 70);

    private final CalibratedAutoTuner.Calibration calibration;
    private final JComboBox<AutoTuneProfile> profileCombo = new JComboBox<>(AutoTuneProfile.values());
    private final JLabel explanation = new JLabel();
    private final TradeoffPanel tradeoffPanel = new TradeoffPanel();
    private final RecoveryPanel recoveryPanel = new RecoveryPanel();

    static void show(Component parent, CalibratedAutoTuner.Calibration calibration, AutoTuneProfile profile) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        AutoTuneChartDialog dialog = new AutoTuneChartDialog(owner, calibration, profile);
        dialog.setVisible(true);
    }

    private AutoTuneChartDialog(Window owner, CalibratedAutoTuner.Calibration calibration, AutoTuneProfile profile) {
        super(owner, "Auto-Tune chart", ModalityType.MODELESS);
        this.calibration = calibration;
        profileCombo.setSelectedItem(profile == null ? AutoTuneProfile.HIGH : profile);
        profileCombo.addActionListener(e -> showProfile((AutoTuneProfile) profileCombo.getSelectedItem()));
        explanation.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        header.add(explanation, BorderLayout.CENTER);
        JPanel picker = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        picker.add(new JLabel("Profile:"));
        picker.add(profileCombo);
        header.add(picker, BorderLayout.EAST);

        JPanel tradeoffBox = titled("Stars found against false detections", tradeoffPanel);
        JPanel recoveryBox = titled("Test stars recovered by the chosen setting", recoveryPanel);
        JPanel charts = new JPanel(new GridLayout(2, 1, 0, 8));
        charts.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        charts.add(tradeoffBox);
        charts.add(recoveryBox);

        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        footer.add(close);

        getContentPane().add(header, BorderLayout.NORTH);
        getContentPane().add(charts, BorderLayout.CENTER);
        getContentPane().add(footer, BorderLayout.SOUTH);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(760, 720);
        setLocationRelativeTo(owner);
        showProfile((AutoTuneProfile) profileCombo.getSelectedItem());
    }

    private static JPanel titled(String title, JComponent chart) {
        JPanel box = new JPanel(new BorderLayout());
        box.setBorder(BorderFactory.createTitledBorder(title));
        box.add(chart, BorderLayout.CENTER);
        return box;
    }

    private void showProfile(AutoTuneProfile profile) {
        Tradeoff tradeoff = calibration.tradeoff;
        ProfileTradeoff profileTradeoff = tradeoff == null || tradeoff.profiles == null || profile == null
                ? null : tradeoff.profiles[profile.ordinal()];
        Candidate chosen = calibration.chosen == null || profile == null ? null : calibration.chosen[profile.ordinal()];
        boolean within = calibration.withinBudget != null && profile != null && calibration.withinBudget[profile.ordinal()];
        tradeoffPanel.setData(profileTradeoff, chosen, within);
        recoveryPanel.setData(tradeoff == null ? null : tradeoff.peakSnr, chosen);
        explanation.setText("<html>" + explanation(profile, profileTradeoff, chosen, within) + "</html>");
    }

    private static String explanation(AutoTuneProfile profile, ProfileTradeoff tradeoff, Candidate chosen, boolean within) {
        if (profile == null || tradeoff == null) {
            return "This run has no per-sigma summary to draw.";
        }
        String budget = String.format(Locale.US, "%.2f", tradeoff.nominalBudgetPerMpixFrame);
        String choice = chosen == null ? "No setting was chosen for " + profile.displayName() + "."
                : within ? "The marked point is the setting chosen for " + profile.displayName() + "."
                : "No setting stayed inside the budget, so the cleanest one was used for " + profile.displayName() + ".";
        return profile.displayName() + " allows " + budget + " false detections per megapixel per frame. "
                + "Within that, the tuner keeps the settings that find the most test stars. " + choice;
    }

    /** False detections on the horizontal axis, test stars found on the vertical axis. */
    private static final class TradeoffPanel extends ChartPanel {
        private ProfileTradeoff tradeoff;
        private Candidate chosen;
        private boolean chosenWithinBudget;

        void setData(ProfileTradeoff tradeoff, Candidate chosen, boolean chosenWithinBudget) {
            this.tradeoff = tradeoff;
            this.chosen = chosen;
            this.chosenWithinBudget = chosenWithinBudget;
            repaint();
        }

        @Override
        protected void paintChart(Graphics2D g, int x0, int y0, int x1, int y1) {
            marks.clear();
            if (tradeoff == null || tradeoff.bySigma == null) {
                drawMessage(g, "No per-sigma summary for this profile.");
                return;
            }
            double budget = tradeoff.effectiveBudgetPerMpixFrame;
            double xMax = budget > 0 ? budget * 1.15 : 1;
            int hidden = 0;
            for (SigmaChoice choice : tradeoff.bySigma) {
                if (choice == null || !choice.evaluated || !Double.isFinite(choice.falsePositivesPerMpixFrame)) {
                    continue;
                }
                if (choice.withinBudget || (budget > 0 && choice.falsePositivesPerMpixFrame <= budget * 3)) {
                    xMax = Math.max(xMax, choice.falsePositivesPerMpixFrame * 1.08);
                } else {
                    hidden++;
                }
            }
            prepareAxes(g, x0, y0, x1, y1, 0, xMax, 0, 100,
                    "False detections per megapixel per frame", "Test stars found (%)");
            if (budget > 0 && budget <= xMax) {
                int x = xPixel(budget);
                g.setColor(BUDGET);
                g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{5f, 4f}, 0f));
                g.drawLine(x, y0, x, y1);
                g.setFont(getFont().deriveFont(11f));
                g.drawString("budget", x + 4, y0 + 12);
            }
            SigmaChoice chosenChoice = null;
            for (SigmaChoice choice : tradeoff.bySigma) {
                if (choice == null || !choice.evaluated || !Double.isFinite(choice.falsePositivesPerMpixFrame)) {
                    continue;
                }
                boolean show = choice.withinBudget || (budget > 0 && choice.falsePositivesPerMpixFrame <= budget * 3);
                if (!show) {
                    continue;
                }
                boolean isChosen = chosen != null && Math.abs(choice.sigma - chosen.sigma) < 1e-6
                        && Math.abs(choice.recoveredFraction - chosen.recoveredFraction) < 1e-6;
                if (isChosen) {
                    chosenChoice = choice;
                    continue;
                }
                int px = xPixel(choice.falsePositivesPerMpixFrame);
                int py = yPixel(100 * choice.recoveredFraction);
                drawPoint(g, px, py, choice.withinBudget ? WITHIN : OUTSIDE, !choice.withinBudget);
                marks.add(new Mark(px, py, tip(choice, false)));
            }
            if (chosenChoice != null) {
                int px = xPixel(chosenChoice.falsePositivesPerMpixFrame);
                int py = yPixel(100 * chosenChoice.recoveredFraction);
                drawPoint(g, px, py, CHOSEN, !chosenWithinBudget);
                g.setColor(CHOSEN);
                g.setStroke(new BasicStroke(1.6f));
                g.draw(new Ellipse2D.Double(px - 7, py - 7, 14, 14));
                marks.add(new Mark(px, py, tip(chosenChoice, true)));
            }
            if (hidden > 0) {
                g.setColor(UIManager.getColor("Label.disabledForeground"));
                g.setFont(getFont().deriveFont(11f));
                String note = hidden + (hidden == 1 ? " detection sigma was" : " detection sigmas were")
                        + " too noisy to show at this scale.";
                g.drawString(note, x0, getHeight() - 6);
            }
        }

        private static String tip(SigmaChoice choice, boolean chosen) {
            return String.format(Locale.US,
                    "Detection sigma %.2f. Test stars found %.0f%%. False detections %.3f per megapixel per frame (noise %.3f, star leakage %.3f). %s%s",
                    choice.sigma, 100 * choice.recoveredFraction, choice.falsePositivesPerMpixFrame,
                    choice.noisePerMpixFrame, choice.leakagePerMpixFrame,
                    choice.withinBudget ? "Inside the budget." : "Outside the budget; the cleanest setting at this sigma.",
                    chosen ? " Chosen for this profile." : "");
        }
    }

    /** Peak signal-to-noise against the share of synthetic stars the chosen setting recovers. */
    private static final class RecoveryPanel extends ChartPanel {
        private double[] peakSnr;
        private Candidate chosen;

        void setData(double[] peakSnr, Candidate chosen) {
            this.peakSnr = peakSnr;
            this.chosen = chosen;
            repaint();
        }

        @Override
        protected void paintChart(Graphics2D g, int x0, int y0, int x1, int y1) {
            marks.clear();
            double[] completeness = chosen == null ? null : chosen.completeness;
            if (peakSnr == null || completeness == null || completeness.length == 0) {
                drawMessage(g, "The chosen setting has no recovery curve.");
                return;
            }
            int n = Math.min(peakSnr.length, completeness.length);
            double snrMin = peakSnr[0];
            double snrMax = peakSnr[n - 1];
            if (snrMax <= snrMin) {
                snrMax = snrMin + 1;
            }
            prepareAxes(g, x0, y0, x1, y1, snrMin, snrMax, 0, 100, "Peak signal-to-noise of the test star", "Recovered (%)");
            int yHalf = yPixel(50);
            g.setColor(OUTSIDE);
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
            g.drawLine(x0, yHalf, x1, yHalf);
            g.setFont(getFont().deriveFont(11f));
            g.drawString("half found", x0 + 4, yHalf - 4);
            int[] xs = new int[n];
            int[] ys = new int[n];
            for (int i = 0; i < n; i++) {
                xs[i] = xPixel(peakSnr[i]);
                ys[i] = yPixel(100 * completeness[i]);
            }
            g.setColor(WITHIN);
            g.setStroke(new BasicStroke(1.6f));
            for (int i = 1; i < n; i++) {
                g.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i]);
            }
            for (int i = 0; i < n; i++) {
                drawPoint(g, xs[i], ys[i], WITHIN, false);
                marks.add(new Mark(xs[i], ys[i], String.format(Locale.US,
                        "Peak signal-to-noise %.1f: %.0f%% of test stars recovered.", peakSnr[i], 100 * completeness[i])));
            }
        }
    }

    /** Shared axes, point marks and hover text for one chart. */
    private abstract static class ChartPanel extends JPanel {
        final List<Mark> marks = new ArrayList<>();
        private int originX;
        private int originY;
        private int axisRight;
        private int axisTop;
        private double xMin;
        private double xMax;
        private double yMin;
        private double yMax;

        ChartPanel() {
            setOpaque(false);
            setToolTipText("");
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    String tip = null;
                    for (int i = marks.size() - 1; i >= 0; i--) {
                        if (marks.get(i).contains(e.getPoint())) {
                            tip = marks.get(i).text;
                            break;
                        }
                    }
                    setToolTipText(tip);
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            paintChart(g2, 58, 16, getWidth() - 16, getHeight() - 42);
            g2.dispose();
        }

        protected abstract void paintChart(Graphics2D g, int x0, int y0, int x1, int y1);

        void prepareAxes(Graphics2D g, int x0, int y0, int x1, int y1,
                         double xMin, double xMax, double yMin, double yMax, String xLabel, String yLabel) {
            this.originX = x0;
            this.originY = y1;
            this.axisRight = x1;
            this.axisTop = y0;
            this.xMin = xMin;
            this.xMax = xMax <= xMin ? xMin + 1 : xMax;
            this.yMin = yMin;
            this.yMax = yMax <= yMin ? yMin + 1 : yMax;
            Color fg = UIManager.getColor("Label.foreground");
            if (fg == null) {
                fg = Color.DARK_GRAY;
            }
            g.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 50));
            for (int i = 0; i <= 4; i++) {
                int y = yPixel(yMin + (yMax - yMin) * i / 4.0);
                g.drawLine(x0, y, x1, y);
            }
            g.setColor(fg);
            g.drawLine(x0, y0, x0, y1);
            g.drawLine(x0, y1, x1, y1);
            g.setFont(getFont().deriveFont(11f));
            for (int i = 0; i <= 4; i++) {
                double value = yMin + (yMax - yMin) * i / 4.0;
                String label = String.format(Locale.US, "%.0f", value);
                int y = yPixel(value);
                g.drawString(label, x0 - g.getFontMetrics().stringWidth(label) - 6, y + 4);
            }
            g.drawString(xLabel, x0, getHeight() - 22);
            g.drawString(yLabel, x0, 12);
        }

        int xPixel(double value) {
            return originX + (int) Math.round((value - xMin) / (xMax - xMin) * (axisRight - originX));
        }

        int yPixel(double value) {
            return originY - (int) Math.round((value - yMin) / (yMax - yMin) * (originY - axisTop));
        }

        void drawPoint(Graphics2D g, int x, int y, Color color, boolean hollow) {
            g.setColor(color);
            g.setStroke(new BasicStroke(1.4f));
            if (hollow) {
                g.draw(new Ellipse2D.Double(x - 4, y - 4, 8, 8));
            } else {
                g.fill(new Ellipse2D.Double(x - 4, y - 4, 8, 8));
            }
        }

        void drawMessage(Graphics2D g, String message) {
            g.setColor(UIManager.getColor("Label.disabledForeground"));
            g.drawString(message, 16, 28);
        }
    }

    private static final class Mark {
        final int x;
        final int y;
        final String text;

        Mark(int x, int y, String text) {
            this.x = x;
            this.y = y;
            this.text = text;
        }

        boolean contains(Point point) {
            int dx = point.x - x;
            int dy = point.y - y;
            return dx * dx + dy * dy <= 10 * 10;
        }
    }
}
