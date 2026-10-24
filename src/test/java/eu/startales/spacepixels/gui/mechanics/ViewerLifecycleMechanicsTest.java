package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.gui.StretchPanel;
import eu.startales.spacepixels.gui.StarMaskLifecycleProbe;
import eu.startales.spacepixels.gui.ViewerLifecycleProbe;
import eu.startales.spacepixels.util.FitsFileInformation;
import org.assertj.swing.edt.GuiActionRunner;
import org.junit.Test;

import javax.swing.DefaultBoundedRangeModel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ViewerLifecycleMechanicsTest extends MechanicsTestCase {
    @Test
    public void closingAHiddenReusableViewerDropsThePendingResultAndDetachesListeners() throws Exception {
        checkClosingWhilePreparing(false);
    }

    @Test
    public void closingTheStarMaskExplorerCancelsItsPendingMask() throws Exception {
        importSyntheticSequence();
        var stack = StarMaskLifecycleProbe.buildStack(application().getSession().getFiles());
        StarMaskLifecycleProbe probe = GuiActionRunner.execute(() ->
                new StarMaskLifecycleProbe(application().getFrame(), stack));
        try {
            assertTrue(probe.awaitStarted());
            GuiActionRunner.execute(() -> {
                probe.viewer().dispose();
                probe.viewer().dispose();
            });
            probe.finishPreparation();
            assertTrue(probe.awaitFinished());
            assertTrue(probe.wasCancelled());
            robot().waitForIdle();
            assertFalse(GuiActionRunner.execute(() -> probe.viewer().isDisplayable()));
            window().button(VisibleText.button("Detect Moving Targets")).requireEnabled();
        } finally {
            probe.finishPreparation();
            GuiActionRunner.execute(() -> probe.viewer().dispose());
        }
    }

    @Test
    public void disposingAViewerDropsThePendingResultAndDetachesListeners() throws Exception {
        checkClosingWhilePreparing(true);
    }

    private void checkClosingWhilePreparing(boolean disposeOnClose) throws Exception {
        importSyntheticSequence();
        StretchPanel stretch = application().getStretchPanel();
        FitsFileInformation[] files = application().getSession().getFiles();
        var catalogue = GuiActionRunner.execute(() -> application().getSession().getSkyCatalogue());
        int catalogueListeners = GuiActionRunner.execute(() -> ViewerLifecycleProbe.catalogueListenerCount(catalogue));
        int stretchListeners = GuiActionRunner.execute(() -> stretchListeners(stretch));
        ViewerLifecycleProbe viewer = GuiActionRunner.execute(() -> {
            ViewerLifecycleProbe created = new ViewerLifecycleProbe(disposeOnClose);
            created.setSkyCatalogueModel(catalogue);
            created.open(files, stretch);
            return created;
        });
        try {
            assertTrue(viewer.awaitStarted());
            assertTrue(GuiActionRunner.execute(() -> ViewerLifecycleProbe.catalogueListenerCount(catalogue)) > catalogueListeners);
            assertTrue(GuiActionRunner.execute(() -> stretchListeners(stretch)) > stretchListeners);
            GuiActionRunner.execute(() -> {
                viewer.closeViewer();
                viewer.closeViewer();
                assertEquals(1, viewer.releaseCount());
                assertFalse(viewer.hasFrameReferences());
                assertFalse(viewer.isShowing());
                assertEquals(catalogueListeners, ViewerLifecycleProbe.catalogueListenerCount(catalogue));
                assertEquals(stretchListeners, stretchListeners(stretch));
            });
            viewer.finishPreparation();
            assertTrue(viewer.awaitFinished());
            robot().waitForIdle();
            GuiActionRunner.execute(() -> {
                assertFalse(viewer.showsObsoleteResult());
                assertFalse(viewer.hasFrameReferences());
                assertEquals(1, viewer.releaseCount());
            });
            window().button(VisibleText.button("Detect Moving Targets")).requireEnabled();
        } finally {
            viewer.finishPreparation();
            GuiActionRunner.execute(() -> {
                viewer.closeViewer();
                viewer.dispose();
            });
        }
    }

    private static int stretchListeners(StretchPanel stretch) {
        return ((DefaultBoundedRangeModel) stretch.getStretchSlider().getModel()).getChangeListeners().length;
    }
}
