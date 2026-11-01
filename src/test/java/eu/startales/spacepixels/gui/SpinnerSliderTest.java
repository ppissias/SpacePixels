package eu.startales.spacepixels.gui;

import org.junit.Test;

import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** A setting's slider and number field move together, and typing moves the slider without committing the value. */
public class SpinnerSliderTest {

    @Test public void sliderAndFieldMoveTogether() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JSpinner spinner = UiStyle.spinner(new DecimalSpinnerModel(20.0, 0.1, 100.0, 0.1));
            spinner.setEditor(new CheckedNumberEditor(spinner, "#0.0", () -> { }));
            JPanel row = new JPanel();
            row.add(spinner);
            AtomicInteger changes = new AtomicInteger();
            JSlider slider = SpinnerSlider.attach(spinner, "White Sigma", changes::incrementAndGet);
            assertSame(slider, row.getComponent(0));
            assertEquals(999, slider.getMaximum());
            assertEquals(199, slider.getValue());

            // Dragging sets the value at each notch, in decimal.
            slider.setValue(354);
            assertEquals(35.5, (Double) spinner.getValue(), 0.0);
            assertTrue(changes.get() > 0);

            // A value between notches stays as it is; the slider takes the nearer notch.
            spinner.setValue(2.37);
            assertEquals(23, slider.getValue());
            assertEquals(2.37, (Double) spinner.getValue(), 0.0);

            // Typing moves the slider and the preview but commits nothing, also after the editor is replaced.
            spinner.setEditor(new CheckedNumberEditor(spinner, "#0.00", () -> { }));
            int before = changes.get();
            ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setText("50");
            assertEquals(499, slider.getValue());
            assertEquals(2.37, (Double) spinner.getValue(), 0.0);
            assertEquals(50.0, SpinnerSlider.typedOrValue(spinner), 0.0);
            assertTrue(changes.get() > before);

            // Text that is not a value of the setting leaves the slider and the value alone.
            ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setText("500");
            assertEquals(2.37, SpinnerSlider.typedOrValue(spinner), 0.0);
            assertEquals(23, slider.getValue());
        });
    }
}
