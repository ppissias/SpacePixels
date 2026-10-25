package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.FitsInput;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.execution.CancellationScope;
import nom.tam.fits.Fits;

import javax.swing.JFrame;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class StarMaskLifecycleProbe {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch proceed = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final StarMaskExplorerFrame viewer;

    public StarMaskLifecycleProbe(Window owner, JTransientEngine.MasterStackResult stack) {
        DetectionConfig config = SyntheticDatasetFactory.createSyntheticDetectionConfig();
        viewer = new StarMaskExplorerFrame(owner, "Lifecycle test", stack, config, VisualizationSettings.defaults(),
                new StarMaskExplorerFrame.Settings(config.masterSigmaMultiplier, config.masterGrowSigmaMultiplier,
                        config.masterMinDetectionPixels, config.maxStarJitter), settings -> { },
                (pixels, settings, maskSettings) -> {
                    started.countDown();
                    try {
                        if (!proceed.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("The test did not release mask preparation.");
                        }
                        CancellationScope.check();
                        throw new AssertionError("A closed explorer must cancel its mask computation.");
                    } catch (CancellationException stopped) {
                        cancelled.set(true);
                        throw stopped;
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    } finally {
                        finished.countDown();
                    }
                });
        viewer.setVisible(true);
    }

    public JFrame viewer() {
        return viewer;
    }

    public boolean awaitStarted() throws InterruptedException {
        return started.await(10, TimeUnit.SECONDS);
    }

    public boolean awaitFinished() throws InterruptedException {
        return finished.await(10, TimeUnit.SECONDS);
    }

    public void finishPreparation() {
        proceed.countDown();
    }

    public boolean wasCancelled() {
        return cancelled.get();
    }

    public static JTransientEngine.MasterStackResult buildStack(FitsFileInformation[] files) throws Exception {
        List<ImageFrame> frames = new ArrayList<>();
        for (int index = 0; index < files.length; index++) {
            FitsFileInformation info = files[index];
            try (Fits fits = new Fits(info.getFilePath())) {
                frames.add(new ImageFrame(index, info.getFileName(), (short[][]) FitsInput.getImageHDU(fits).getKernel(),
                        info.getObservationTimestamp(), info.getExposureDurationMillis()));
            }
        }
        try (JTransientEngine engine = new JTransientEngine()) {
            return engine.generateMasterStackWithDetails(frames, SyntheticDatasetFactory.createSyntheticDetectionConfig(), null);
        }
    }
}
