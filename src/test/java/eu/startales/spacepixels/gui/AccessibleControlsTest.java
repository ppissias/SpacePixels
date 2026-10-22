package eu.startales.spacepixels.gui;

import org.junit.Test;
import javax.swing.*;
import java.awt.EventQueue;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Checks control semantics and saved-state descriptions without launching the application. */
public class AccessibleControlsTest {
    /** Links expose native button actions and row reset remains focusable with a descriptive name. */
    @Test public void linksLabelsAndResetExposeAccessibleState() throws Exception {
        EventQueue.invokeAndWait(() -> {
            AtomicInteger activations = new AtomicInteger();
            JButton link = AccessibleControls.link("Settings ›", "Open extraction settings", activations::incrementAndGet);
            assertTrue(link.isFocusable());
            link.doClick();
            assertEquals(1, activations.get());
            assertEquals("Open extraction settings", link.getAccessibleContext().getAccessibleName());
            JLabel title = new JLabel("Radius (pixels)");
            JSpinner spinner = new JSpinner(new SpinnerNumberModel(3, 0, 100, 1));
            SettingRow row = new SettingRow(new JPanel(), new JPanel(), title, title.getText(),
                    "Radius in <b>pixels</b>.<br>Used by the mask.", spinner);
            row.captureSaved();
            assertSame(spinner, title.getLabelFor());
            JFormattedTextField editor = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
            assertEquals("Radius (pixels)", editor.getAccessibleContext().getAccessibleName());
            assertTrue(editor.getAccessibleContext().getAccessibleDescription().contains("Radius in pixels."));
            spinner.setValue(5);
            assertTrue(row.resetButton().isFocusable());
            assertTrue(row.resetButton().isVisible());
            assertTrue(editor.getAccessibleContext().getAccessibleDescription().contains("saved value: 3"));
            row.resetButton().doClick();
            assertEquals(3, spinner.getValue());
            assertFalse(row.resetButton().isVisible());
            assertFalse(editor.getAccessibleContext().getAccessibleDescription().contains("Changed"));
            FrameView image = new FrameView();
            assertNotNull(image.getAccessibleContext());
            assertEquals(javax.accessibility.AccessibleRole.ICON, image.getAccessibleContext().getAccessibleRole());
        });
    }
}
