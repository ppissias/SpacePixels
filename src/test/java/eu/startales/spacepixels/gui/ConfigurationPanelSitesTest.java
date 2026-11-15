package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.config.ObservingSites;
import eu.startales.spacepixels.session.ApplicationStorage;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTextField;
import java.awt.EventQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Astrometry Config's named sites: choosing, renaming, editing, adding and Revert act on the site in use. */
public class ConfigurationPanelSitesTest {

    @SuppressWarnings("unchecked")
    private static <T> T none(Class<T> type) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(ConfigurationPanel panel, String name) throws Exception {
        var field = ConfigurationPanel.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(panel);
    }

    @Test
    public void sitesAreChosenEditedAndReverted() throws Exception {
        AppConfig start = new AppConfig();
        start.observatoryCode = "J95";
        start.siteLat = "37.98";
        start.siteLong = "23.73";
        ApplicationSettings settings = ApplicationSettings.inMemory(start);
        AppConfig config = settings.getConfig();
        ObservingSites.add(config);
        ObservingSites.rename(config, "Dark site");
        config.siteLat = "-24.6";
        ObservingSites.selected(config).siteLat = "-24.6";
        ObservingSites.select(config, "My site");

        try (ApplicationStorage storage = new ApplicationStorage()) {
            EventQueue.invokeAndWait(() -> {
                try {
                    var panel = new ConfigurationPanel(settings, none(SessionAccess.class), none(FrameList.class), storage, () -> false);
                    AtomicInteger readouts = new AtomicInteger();
                    panel.onFetchSettingsChanged(readouts::incrementAndGet);
                    panel.refreshComponents();
                    JComboBox<String> combo = field(panel, "siteCombo");
                    JTextField name = field(panel, "siteNameField");
                    JTextField latitude = field(panel, "latTextField");
                    JLabel nameStatus = field(panel, "siteNameStatus");
                    JLabel state = field(panel, "footerStateLabel");
                    assertEquals(2, combo.getItemCount());
                    assertEquals("My site", combo.getSelectedItem());
                    assertTrue(state.getText(), state.getText().contains("Saved settings in use"));

                    int before = readouts.get();
                    combo.setSelectedItem("Dark site");
                    assertEquals("-24.6", config.siteLat);
                    assertEquals("", config.observatoryCode);
                    assertEquals("-24.6", latitude.getText());
                    assertEquals("Dark site", name.getText());
                    assertTrue("the readout follows the choice", readouts.get() > before);
                    assertTrue(state.getText(), state.getText().contains("Unsaved"));

                    name.setText("Mountain");
                    assertEquals("Mountain", config.observingSite);
                    assertEquals("Mountain", combo.getSelectedItem());
                    assertEquals(2, combo.getItemCount());
                    name.setText("my site");
                    assertEquals("a taken name keeps the last valid one", "Mountain", config.observingSite);
                    assertTrue(nameStatus.getText(), nameStatus.getText().contains("Another site"));
                    name.setText("Mountain");

                    latitude.setText("-30.2");
                    assertEquals("-30.2", ObservingSites.find(config, "Mountain").siteLat);
                    assertEquals("the other site is unchanged", "37.98", ObservingSites.find(config, "My site").siteLat);

                    JButton add = field(panel, "newSiteButton");
                    add.doClick();
                    assertEquals(3, combo.getItemCount());
                    assertEquals(ObservingSites.NEW_SITE_NAME, config.observingSite);
                    assertEquals("", latitude.getText());

                    var revert = ConfigurationPanel.class.getDeclaredMethod("revertSite");
                    revert.setAccessible(true);
                    revert.invoke(panel);
                    assertEquals(2, combo.getItemCount());
                    assertEquals("My site", config.observingSite);
                    assertEquals("37.98", config.siteLat);
                    assertEquals("-24.6", ObservingSites.find(config, "Dark site").siteLat);
                    assertFalse(state.getText(), state.getText().contains("Unsaved"));
                    panel.close();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
