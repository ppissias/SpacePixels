package eu.startales.spacepixels.gui;

import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.EventQueue;

import static org.junit.Assert.*;

public class SettingsNavigatorSnapshotTest {
    @Test
    public void savedMarkersUseTheSaveTimeValuesAndResetDoesNotLoseLaterEdits() throws Exception {
        EventQueue.invokeAndWait(() -> {
            SettingsNavigator navigator = new SettingsNavigator();
            JSpinner input = new JSpinner(new SpinnerNumberModel(5, 0, 100, 1));
            SettingRow row = new SettingRow(new JPanel(), new JPanel(), new JLabel("Value"), "Value", "", input);
            navigator.registerRow(row);
            navigator.markAllSaved();
            input.setValue(6);
            Runnable saved = navigator.captureSavedState();
            input.setValue(7);
            saved.run();
            assertTrue(row.isChanged());
            assertEquals(7, input.getValue());
            row.resetToSaved();
            assertEquals(6, input.getValue());
            assertFalse(row.isChanged());
        });
    }
}
