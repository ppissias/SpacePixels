package eu.startales.spacepixels.util;

import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.engine.PipelineStage;
import io.github.ppissias.jtransient.model.AnomalyDetection;
import io.github.ppissias.jtransient.model.AnomalyType;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.photometry.PhotometricReadiness;
import io.github.ppissias.jtransient.photometry.StarLightCurve;
import io.github.ppissias.jtransient.photometry.VariabilityTier;
import io.github.ppissias.jtransient.photometry.VariableStarAnalysis;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/** Feeds engine stages through the live-view observer and checks the pictures and captions it hands over. */
public class LiveFindingsTest {

    private static final int SIZE = 160;

    @Test
    public void trackingFindingsBecomeSlidesWithProvisionalCaptionsAndRealCounts() throws Exception {
        List<RunPreview> previews = new CopyOnWriteArrayList<>();
        LiveFindings findings = new LiveFindings(sink(previews, true), frames(5));
        findings.stageFinished(new PipelineStage.MasterReference(image(), List.of(star(20, 20), star(60, 80))));
        Track moving = new Track();
        for (int frame = 0; frame < 5; frame++) {
            moving.addPoint(detection(30 + 10 * frame, 40 + 5 * frame, frame, 6));
        }
        Track streak = new Track();
        streak.isStreakTrack = true;
        streak.addPoint(detection(100, 100, 2, 4));
        List<AnomalyDetection> anomalies = List.of(new AnomalyDetection(detection(50, 120, 1, 12), AnomalyType.PEAK_SIGMA),
                new AnomalyDetection(detection(140, 30, 3, 7), AnomalyType.INTEGRATED_SIGMA));
        boolean[][] starMask = new boolean[SIZE][SIZE];
        for (int y = 0; y < SIZE / 4; y++) {
            for (int x = 0; x < SIZE; x++) {
                starMask[y][x] = true;
            }
        }
        findings.stageFinished(new PipelineStage.Tracking(List.of(streak, moving), anomalies, starMask));
        findings.finishPictures();

        assertEquals(2, previews.size());
        assertTrue(previews.get(0).caption().startsWith("Stationary-sky reference"));
        assertTrue(previews.get(0).caption().contains("2 stationary stars"));
        RunPreview tracking = previews.get(1);
        assertEquals(5, tracking.slides().size());
        for (RunPreview slide : tracking.slides()) {
            assertNotNull(slide.image());
            assertTrue(slide.image().getWidth() <= 420 && slide.image().getHeight() <= 420);
            assertTrue(slide.caption(), slide.caption().endsWith("1 moving object, 1 streak, 2 anomalies so far"));
        }
        // The star mask comes first: a quarter of the field.
        assertTrue(tracking.slides().get(0).caption(), tracking.slides().get(0).caption()
                .startsWith("Star mask: stationary stars cover 25.0 % of the field"));
        for (RunPreview slide : tracking.slides().subList(1, 5)) {
            assertTrue(slide.caption(), slide.caption().contains("provisional"));
        }
        assertTrue(tracking.slides().get(1).caption().startsWith("Moving-object candidate · 5 frames"));
        assertTrue(tracking.slides().get(2).caption().startsWith("Streak · frame 3"));
        // The strongest anomaly comes first.
        assertTrue(tracking.slides().get(3).caption().startsWith("Anomaly (sharp glint) · frame 2"));
        assertTrue(tracking.slides().get(4).caption().startsWith("Anomaly (broad flash) · frame 4"));
        assertTrue("A moving object is a three-crop strip", tracking.slides().get(1).image().getWidth()
                > tracking.slides().get(2).image().getWidth());
    }

    @Test
    public void aStageWithoutFindingsSaysSoAndKeepsThePicture() throws Exception {
        List<RunPreview> previews = new CopyOnWriteArrayList<>();
        LiveFindings findings = new LiveFindings(sink(previews, true), frames(3));
        findings.stageFinished(new PipelineStage.Tracking(List.of(), List.of(), new boolean[SIZE][SIZE]));
        findings.stageFinished(new PipelineStage.SlowMovers(true, image(), List.of()));
        findings.finishPictures();
        assertEquals(2, previews.size());
        assertTrue(previews.get(0).keepImage());
        assertTrue(previews.get(0).caption().startsWith("No moving objects, streaks or anomalies found"));
        assertTrue(previews.get(1).caption().startsWith("No slow-mover candidates"));

        // With the reference known, the empty stage shows the star mask and says that nothing was found.
        previews.clear();
        LiveFindings withReference = new LiveFindings(sink(previews, true), frames(3));
        withReference.stageFinished(new PipelineStage.MasterReference(image(), List.of()));
        withReference.stageFinished(new PipelineStage.Tracking(List.of(), List.of(), new boolean[SIZE][SIZE]));
        withReference.finishPictures();
        RunPreview tracking = previews.get(1);
        assertNotNull(tracking.image());
        assertTrue(tracking.caption(), tracking.caption().startsWith("Star mask: stationary stars cover 0.0 % of the field"));
        assertTrue(tracking.caption(), tracking.caption().endsWith("No moving objects, streaks or anomalies found"));
    }

    @Test
    public void variableStarCandidatesShowACropBesideTheirLightCurve() throws Exception {
        List<RunPreview> previews = new CopyOnWriteArrayList<>();
        LiveFindings findings = new LiveFindings(sink(previews, true), frames(4));
        findings.stageFinished(new PipelineStage.MasterReference(image(), List.of()));
        StarLightCurve star = lightCurve(70, 90, 4);
        star.tier = VariabilityTier.HIGH_CONFIDENCE;
        star.deltaMag = new double[]{0.0, -0.2, Double.NaN, 0.3};
        StarLightCurve rejected = lightCurve(30, 40, 4);
        rejected.tier = VariabilityTier.REJECTED;
        // The engine lists rejected stars among its candidates; the live view leaves them out, as the report does.
        VariableStarAnalysis analysis = new VariableStarAnalysis(new PhotometricReadiness(), List.of(rejected, star),
                new PipelineTelemetry.PhotometryTelemetry());
        findings.stageFinished(new PipelineStage.Photometry(true, analysis));
        findings.finishPictures();
        RunPreview photometry = previews.get(previews.size() - 1);
        assertEquals(1, photometry.slides().size());
        assertTrue(photometry.caption().startsWith("Variable-star candidate · high confidence · provisional"));
        assertTrue(photometry.caption().endsWith("1 variable-star candidate so far"));
        assertTrue("The light curve sits beside the crop", photometry.image().getWidth() > photometry.image().getHeight());
    }

    @Test
    public void nothingIsCopiedOrDrawnWhileThePictureIsHiddenOrForSwitchedOffStages() throws Exception {
        List<RunPreview> previews = new CopyOnWriteArrayList<>();
        LiveFindings hidden = new LiveFindings(sink(previews, false), frames(3));
        hidden.stageFinished(new PipelineStage.MasterReference(image(), List.of()));
        hidden.stageFinished(new PipelineStage.Tracking(List.of(), List.of(), new boolean[SIZE][SIZE]));
        hidden.finishPictures();
        assertTrue(previews.isEmpty());

        LiveFindings shown = new LiveFindings(sink(previews, true), frames(3));
        shown.stageFinished(new PipelineStage.SlowMovers(false, image(), List.of()));
        shown.stageFinished(new PipelineStage.CommonMotion(true, List.of()));
        shown.stageFinished(new PipelineStage.ResidualAnalysis(false, ResidualTransientAnalysis.empty()));
        shown.stageFinished(new PipelineStage.Photometry(false, VariableStarAnalysis.empty()));
        shown.finishPictures();
        assertTrue(previews.isEmpty());
    }

    @Test
    public void afterCloseLaterStagesAreIgnored() {
        List<RunPreview> previews = new CopyOnWriteArrayList<>();
        LiveFindings findings = new LiveFindings(sink(previews, true), frames(3));
        findings.close();
        findings.stageFinished(new PipelineStage.MasterReference(image(), List.of()));
        assertTrue(previews.isEmpty());
    }

    @Test
    public void aPassLabelPrefixesEveryCaption() {
        RunPreview slides = RunPreview.slides(List.of(RunPreview.captionOnly("one"), RunPreview.captionOnly("two")));
        RunPreview labelled = slides.withCaptionPrefix("Pass 2 of 3 · ");
        assertEquals("Pass 2 of 3 · one", labelled.caption());
        assertEquals("Pass 2 of 3 · two", labelled.slides().get(1).caption());
    }

    private static RunPreviewSink sink(List<RunPreview> previews, boolean wanted) {
        return new RunPreviewSink() {
            @Override
            public boolean wantsPreview(boolean stageChange) {
                return wanted;
            }

            @Override
            public void preview(RunPreview preview) {
                previews.add(preview);
            }
        };
    }

    private static List<short[][]> frames(int count) {
        List<short[][]> frames = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            frames.add(image());
        }
        return frames;
    }

    /** Sky with a gentle gradient and a few bright pixels, so the stretch has something to work with. */
    private static short[][] image() {
        short[][] pixels = new short[SIZE][SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                pixels[y][x] = (short) (-31000 + x + y + ((x * 31 + y * 17) % 23));
            }
        }
        pixels[40][30] = (short) 20000;
        return pixels;
    }

    private static DetectedObject detection(double x, double y, int frame, double peakSigma) {
        DetectedObject detection = new DetectedObject(x, y, 1000, 9);
        detection.pixelArea = 9;
        detection.sourceFrameIndex = frame;
        detection.peakSigma = peakSigma;
        return detection;
    }

    private static DetectedObject star(double x, double y) {
        return detection(x, y, -1, 50);
    }

    private static StarLightCurve lightCurve(double x, double y, int frames) throws Exception {
        Constructor<StarLightCurve> constructor = StarLightCurve.class.getDeclaredConstructor(int.class, double.class,
                double.class, double.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(1, x, y, 1000, frames);
    }
}
