package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.preparation.FramePreparationService;
import eu.startales.spacepixels.session.ApplicationStorage;
import org.junit.Test;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Verifies preparation completion/import window ownership without displaying windows or altering saved settings. */
public class PreparationWindowTest {
    /** Wrapping the italic note at a narrow first size must update the section's cached height without a second resize. */
    @Test public void narrowFirstLayoutReservesTheWholePixInsightNote() throws Exception {
        var storage = new ApplicationStorage();
        var owner = new java.util.concurrent.atomic.AtomicReference<PreparationWindow>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var window = new PreparationWindow(null, ApplicationSettings.inMemory(new AppConfig()), storage,
                        () -> false, ignored -> { }, () -> { });
                owner.set(window); window.addNotify(); window.setSize(860, 520); window.validate();
            });
            SwingUtilities.invokeAndWait(() -> { });
            SwingUtilities.invokeAndWait(() -> {
                owner.get().validate();
                var note = findNote(owner.get());
                assertNotNull(note);
                assertTrue("Every wrapped note line is visible", note.getHeight() >= note.getPreferredSize().height);
            });
        } finally {
            if (owner.get() != null) SwingUtilities.invokeAndWait(owner.get()::dispose);
            storage.finish();
        }
    }
    /** A completed preparation or failure keeps the form; successful import disposes it before notification. */
    @Test public void closesOnlyAfterSuccessfulImportAndBeforeNotification() throws Exception {
        var storage = new ApplicationStorage();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var window = new PreparationWindow(null, ApplicationSettings.inMemory(new AppConfig()), storage,
                        () -> false, ignored -> { }, () -> { });
                try {
                    window.addNotify();
                    window.validate();
                    assertTrue(window.isDisplayable());
                    assertFalse(window.isVisible());
                    var path = findSirilPath(window);
                    assertNotNull(path);
                    assertTrue("Installation path is laid out on the first validation", path.getWidth() > 0 && path.getHeight() > 0);
                    java.awt.Rectangle bounds = javax.swing.SwingUtilities.convertRectangle(path.getParent(), path.getBounds(), window.getContentPane());
                    assertTrue("Installation row is inside the first viewport", bounds.x >= 0 && bounds.y >= 0
                            && bounds.x + bounds.width <= window.getContentPane().getWidth());
                    window.setSize(860, 520);
                    window.validate();
                    var viewport = (javax.swing.JViewport) javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JViewport.class, path);
                    var visiblePath = javax.swing.SwingUtilities.convertRectangle(path.getParent(), path.getBounds(), viewport);
                    assertTrue("Installation controls remain reachable at minimum width", visiblePath.x >= 0
                            && visiblePath.x + visiblePath.width <= viewport.getWidth());
                    for (String name : java.util.List.of("Browse…", "Check")) {
                        var button = findButton(path.getParent(), name);
                        assertNotNull(button);
                        var boundsInViewport = javax.swing.SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), viewport);
                        assertTrue(name + " is visible without resizing again", boundsInViewport.x >= 0 && boundsInViewport.y >= 0
                                && boundsInViewport.x + boundsInViewport.width <= viewport.getWidth()
                                && boundsInViewport.y + boundsInViewport.height <= viewport.getHeight());
                    }
                    Path folder = Path.of("prepared");
                    window.completed(new FramePreparationService.Result(folder, folder.resolve("aligned"), 3, "Siril", 1, 0, java.util.List.of()));
                    assertTrue(window.isDisplayable());
                    window.failed("Import declined; the completed files remain available.");
                    assertTrue(window.isDisplayable());
                    AtomicBoolean notified = new AtomicBoolean();
                    window.imported(() -> { assertFalse(window.isDisplayable()); notified.set(true); });
                    assertTrue(notified.get());
                } finally { window.dispose(); }
            });
        } finally { storage.finish(); }
    }

    /** Locates the production installation editor through its accessible label. */
    private static javax.swing.JTextField findSirilPath(java.awt.Container container) {
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof javax.swing.JTextField field && "Siril installation path".equals(field.getAccessibleContext().getAccessibleName())) return field;
            if (child instanceof java.awt.Container nested) {
                var found = findSirilPath(nested);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Finds the actual installation action, including actions inside a fixed-width button group. */
    private static javax.swing.JButton findButton(java.awt.Container container, String name) {
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof javax.swing.JButton button && name.equals(button.getText())) return button;
            if (child instanceof java.awt.Container nested) {
                var found = findButton(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Finds the production explanatory note by its visible content. */
    private static javax.swing.JLabel findNote(java.awt.Container container) {
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof javax.swing.JLabel label && label.getText() != null && label.getText().contains("PixInsight WBPP")) return label;
            if (child instanceof java.awt.Container nested) {
                var found = findNote(nested);
                if (found != null) return found;
            }
        }
        return null;
    }
}
