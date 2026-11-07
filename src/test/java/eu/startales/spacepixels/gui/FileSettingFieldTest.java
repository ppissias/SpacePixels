package eu.startales.spacepixels.gui;

import org.junit.Test;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

import static org.junit.Assert.assertEquals;

/** Checks the file setting's buttons. */
public class FileSettingFieldTest {

    /** Choose… has the text height of its neighbour and the width of a number field, also when a long name is shown. */
    @Test
    public void chooseButtonIsAsTallAsClear() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FileSettingField field = new FileSettingField("Choose", new FileNameExtensionFilter("CSS", "css"));
            JButton choose = (JButton) field.getComponent(0);
            JButton clear = (JButton) field.getComponent(1);
            assertEquals("Choose…", choose.getText());
            assertEquals(clear.getPreferredSize().height, choose.getPreferredSize().height);
            assertEquals(UiStyle.space(UiStyle.NUMBER_INPUT_WIDTH), choose.getPreferredSize().width);

            field.setPath("C:/styles/a-very-long-style-file-name-for-my-reports.css");
            assertEquals(clear.getPreferredSize().height, choose.getPreferredSize().height);
            assertEquals(UiStyle.space(UiStyle.NUMBER_INPUT_WIDTH), choose.getPreferredSize().width);
        });
    }
}
