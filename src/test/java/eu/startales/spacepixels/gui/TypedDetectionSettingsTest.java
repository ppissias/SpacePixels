package eu.startales.spacepixels.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.DetectionSetting;
import eu.startales.spacepixels.config.DetectionSettings;
import eu.startales.spacepixels.config.DetectionSettingsPresentation;
import eu.startales.spacepixels.config.ResidualAnalysisSettings;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO;
import eu.startales.spacepixels.jobs.JobCoordinator;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.session.ApplicationStorage;
import eu.startales.spacepixels.session.ProcessingSession;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.PlateSolveService;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.config.DetectionConfigRules;
import io.github.ppissias.jtransient.engine.AutoTuneProfile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import javax.swing.*;
import java.awt.EventQueue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.prefs.Preferences;
import static org.junit.Assert.*;

/** Exercises typed field ownership, shared engine validation, stable identity and existing shared button models. */
public class TypedDetectionSettingsTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    /** Every definition changes exactly its declared field, preserving hidden configuration and primitive types. */
    @Test public void definitionsMutateOnlyTheirDeclaredField() {
        Set<String> identities = new HashSet<>();
        for (DetectionSetting<?> setting : DetectionSettings.all()) {
            assertTrue(identities.add(setting.id()));
            assertEquals(setting.title(), DetectionSettingsPresentation.title(setting.id()));
            checkOwnership(setting);
        }
        assertEquals(78, identities.size());
    }

    /** Invalid numeric input is rejected before mutation and the engine remains the source of issue identities. */
    @Test public void nonFiniteValuesAndUnsupportedContractsAreExplicit() {
        DetectionConfig config = new DetectionConfig();
        double before = config.localActivityClusterRadiusPixels;
        var setting = ResidualAnalysisSettings.CLUSTER_RADIUS;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> setting.write(config, Double.NaN));
        assertTrue(failure.getMessage().contains(setting.id()));
        assertEquals(before, config.localActivityClusterRadiusPixels, 0);
        config.localActivityClusterRadiusPixels = Double.NaN;
        assertTrue(DetectionConfigRules.validate(config, DetectionConfigRules.Operation.CONFIGURATION).stream()
                .anyMatch(issue -> issue.fields().contains(setting.id()) && issue.ruleId() == DetectionConfigRules.RuleId.NON_FINITE));
        assertThrows(IllegalArgumentException.class, () -> new DetectionSetting<>("enableLocalActivityClusters",
                "Wrong type", "", "Invalid application binding", false, Integer.class,
                source -> source.localActivityClusterMinFrames, (target, value) -> target.localActivityClusterMinFrames = value));
    }

    /** Load/apply uses the original control models and a field ID can be searched independently of its page. */
    @Test public void bindingsPreserveModelsAndDefinitionOwnedVisibility() throws Exception {
        EventQueue.invokeAndWait(() -> {
            DetectionConfig config = new DetectionConfig();
            config.qualityGrowSigmaMultiplier = 1.234;
            JCheckBox detail = new JCheckBox();
            JCheckBox overview = new JCheckBox();
            overview.setModel(detail.getModel());
            var binding = new DetectionSettingBinding<>(ResidualAnalysisSettings.ENABLE_RESIDUAL,
                    detail::isSelected, detail::setSelected);
            config.enableResidualTransientAnalysis = true;
            binding.loadFrom(config);
            assertTrue(overview.isSelected());
            overview.setSelected(false);
            binding.applyTo(config);
            assertFalse(config.enableResidualTransientAnalysis);
            assertEquals(1.234, config.qualityGrowSigmaMultiplier, 0);
            JSpinner input = new JSpinner(new SpinnerNumberModel(10.0, 0.0, 100.0, 0.5));
            SettingRow row = new SettingRow(new JPanel(), new JPanel(), new JLabel("Radius"),
                    ResidualAnalysisSettings.CLUSTER_RADIUS.id(), "Radius",
                    ResidualAnalysisSettings.CLUSTER_RADIUS.help(), true, input);
            row.section = new SettingsNavigator.Section(new JLabel("Common"), "Common", false);
            assertEquals(ResidualAnalysisSettings.CLUSTER_RADIUS.id(), row.id);
            assertTrue(row.matches("localactivityclusterradiuspixels"));
            assertTrue(row.matches("pixels"));
            assertTrue(row.isExpert());
            SettingRow nonExpertInExpertSection = new SettingRow(new JPanel(), new JPanel(), new JLabel("Preset"),
                    "frameRejectionPreset", "Strictness", "Outlier preset", false, new JComboBox<>(new String[]{"Normal"}));
            nonExpertInExpertSection.section = new SettingsNavigator.Section(new JLabel("Expert"), "Expert", true);
            assertFalse(nonExpertInExpertSection.isExpert());
        });
    }

    /** Application values use typed load/apply without changing the control model or accepting non-finite edits. */
    @Test public void applicationBindingsKeepTheirOwnerAndRejectNonFiniteValues() throws Exception {
        EventQueue.invokeAndWait(() -> {
            var preferences = new eu.startales.spacepixels.config.SpacePixelsVisualizationPreferences();
            JSpinner input = new JSpinner(new SpinnerNumberModel(1.0, 0.0, 10.0, 0.1));
            SpinnerModel model = input.getModel();
            var binding = new ApplicationSettingBinding<>("autoStretchBlackSigma", "Auto Stretch Black Sigma", "sigma",
                    "Black point below the image mean.", false, Double.class,
                    () -> preferences.autoStretchBlackSigma, value -> preferences.autoStretchBlackSigma = value,
                    () -> ((Number) input.getValue()).doubleValue(), input::setValue);
            preferences.autoStretchBlackSigma = 2.5;
            binding.load();
            assertEquals(2.5, ((Number) input.getValue()).doubleValue(), 0);
            input.setValue(3.5);
            binding.apply();
            assertEquals(3.5, preferences.autoStretchBlackSigma, 0);
            assertSame(model, input.getModel());
            assertThrows(IllegalArgumentException.class, () -> binding.write(Double.NaN));
            var nonFiniteBinding = new ApplicationSettingBinding<>("autoStretchBlackSigma", "Auto Stretch Black Sigma", "sigma",
                    "Black point below the image mean.", false, Double.class,
                    () -> preferences.autoStretchBlackSigma, value -> preferences.autoStretchBlackSigma = value,
                    () -> Double.NaN, input::setValue);
            assertThrows(IllegalArgumentException.class, nonFiniteBinding::apply);
            assertEquals(3.5, preferences.autoStretchBlackSigma, 0);
            assertTrue(binding.help().contains("sigma"));
        });
    }

    /**
     * Proves binding or alignment failures in applySettingsToMemory fail fast across checkedSettings, Save and run
     * commands without admitting a job, queuing a save or advancing the saved baseline, while invalid editor text
     * continues to return null and report the field problem.
     */
    @Test public void checkedSettingsSaveAndRunFailFastWithoutAdmittingJobsOrAdvancingSavedBaseline() throws Exception {
        String preferenceNode = "eu/startales/spacepixels";
        Preferences root = Preferences.userRoot();
        boolean prefExisted = root.nodeExists(preferenceNode);
        byte[] exportedPrefs = null;
        if (prefExisted) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            root.node(preferenceNode).exportSubtree(out);
            exportedPrefs = out.toByteArray();
        }
        String originalHome = System.getProperty("user.home");
        File tempHome = folder.newFolder("home");
        File frameDir = folder.newFolder("frames");
        System.setProperty("user.home", tempHome.getAbsolutePath());
        ApplicationStorage storage = new ApplicationStorage();
        FitsFileInformation[] frames = new FitsFileInformation[5];
        for (int i = 0; i < frames.length; i++) {
            String name = "frame_" + i + ".fit";
            frames[i] = new FitsFileInformation(new File(frameDir, name).getAbsolutePath(), name, true, 16, 16);
        }
        try (ProcessingSession session = new ProcessingSession(new PlateSolveService(new AppConfig()), frames, null)) {
            java.util.concurrent.atomic.AtomicInteger admittedJobs = new java.util.concurrent.atomic.AtomicInteger();
            JobCoordinator jobs = new JobCoordinator(work -> admittedJobs.incrementAndGet(), EventQueue::invokeLater, () -> false);
            AutoTuneProfile profile = AutoTuneProfile.values()[0];
            EventQueue.invokeAndWait(() -> {
                SessionAccess sessions = new SessionAccess() {
                    @Override public ProcessingSession current() { return session; }
                    @Override public boolean isCurrent(ProcessingSession candidate) { return candidate == session; }
                    @Override public <R> JobCoordinator.OutcomeHandler<R> forCurrent(JobCoordinator.OutcomeHandler<R> handler) { return handler; }
                };
                ActiveSettingsReadout readout = new ActiveSettingsReadout() {
                    @Override public void showActiveDetectionSettings(DetectionConfig config) { }
                    @Override public void showEnabledAnalyses(String enabled) { }
                    @Override public void showStatus(String text) { }
                    @Override public void refreshWorkflowStatus() { }
                    @Override public void showDetectionProblem(JobOutcome<?> outcome) { }
                };
                Action noop = new AbstractAction("Noop") {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) { }
                };
                FrameList frameList = new FrameList() {
                    @Override public FitsFileInformation[] getImportedFiles() { return frames.clone(); }
                    @Override public FitsFileInformation[] getSelectedFilesInformation() { return frames.clone(); }
                    @Override public FitsFileInformation getSelectedFileInformation() { return frames[0]; }
                    @Override public int[] frameSelection() { return new int[]{0, frames.length}; }
                    @Override public void selectFrameOffset(int delta) { }
                    @Override public void selectFirstFileIfNoneSelected() { }
                    @Override public void refreshCommands() { }
                    @Override public Action detectAction() { return noop; }
                    @Override public Action previewAction() { return noop; }
                    @Override public void setAutoTuneCommand(Runnable openOverviewAndRunAutoTune) { }
                };
                DetectionConfigurationPanel panel = new DetectionConfigurationPanel(
                        sessions, jobs, storage, new JFrame(), () -> false, () -> { }, readout, frameList);
                JSpinner spinDetectionSigma = privateField(panel, "spinDetectionSigma", JSpinner.class);
                JSpinner spinGrowSigma = privateField(panel, "spinGrowSigma", JSpinner.class);
                JButton saveButton = privateField(panel, "saveButton", JButton.class);
                JLabel footerMessageLabel = privateField(panel, "footerMessageLabel", JLabel.class);
                JLabel footerStateLabel = privateField(panel, "footerStateLabel", JLabel.class);
                String initialBaseline = privateField(panel, "savedSnapshot", String.class);
                File profileFile = new File(tempHome, SpacePixelsDetectionProfileIO.DEFAULT_FILENAME);

                // 1. Invalid editor text returns null without throwing, queuing a save or admitting a job.
                ((JSpinner.DefaultEditor) spinDetectionSigma.getEditor()).getTextField().setText("invalid-number");
                assertNull(panel.checkedSettings("Detect Moving Targets did not start."));
                assertTrue(footerMessageLabel.getText().startsWith("Detect Moving Targets did not start. Detection Sigma:"));
                saveButton.doClick();
                invokePrivate(panel, "runAutoTuner", new Class<?>[]{AutoTuneProfile.class}, profile);
                invokePrivate(panel, "openStarMaskExplorer", new Class<?>[0]);
                assertEquals(0, admittedJobs.get());
                assertNull(jobs.current());
                assertTrue(saveButton.isEnabled());
                assertFalse(profileFile.exists());
                assertEquals(initialBaseline, privateField(panel, "savedSnapshot", String.class));

                // Restore valid text and make an unsaved edit.
                spinDetectionSigma.setValue(3.6);
                assertTrue(footerStateLabel.getText().contains("Unsaved changes"));
                assertEquals(3.6, panel.checkedSettings("Check").detectionSigmaMultiplier, 0.0);

                // 2. Alignment failure inside applySettingsToMemory fails fast for checkedSettings, Save and runs.
                @SuppressWarnings("unchecked")
                Map<String, JSpinner> detectionInputs = privateField(panel, "detectionInputs", Map.class);
                JSpinner removedGrow = detectionInputs.remove("growSigmaMultiplier");
                assertNotNull(removedGrow);
                boolean[] suppress = new boolean[]{privateField(panel, "suppressAutoApply", Boolean.class)};
                assertFalse(suppress[0]);
                // Set grow sigma above detection sigma while auto-apply is suppressed so checkedSettings triggers alignment.
                setPrivateField(panel, "suppressAutoApply", true);
                spinGrowSigma.setValue(4.2);
                setPrivateField(panel, "suppressAutoApply", false);

                assertThrows(IllegalStateException.class, () -> panel.checkedSettings("Detect Moving Targets did not start."));
                assertThrows(IllegalStateException.class, saveButton::doClick);
                assertThrows(IllegalStateException.class,
                        () -> invokePrivate(panel, "runAutoTuner", new Class<?>[]{AutoTuneProfile.class}, profile));
                assertThrows(IllegalStateException.class,
                        () -> invokePrivate(panel, "openStarMaskExplorer", new Class<?>[0]));
                assertEquals(0, admittedJobs.get());
                assertNull(jobs.current());
                assertTrue(saveButton.isEnabled());
                assertFalse(profileFile.exists());
                assertEquals(initialBaseline, privateField(panel, "savedSnapshot", String.class));
                assertFalse(privateField(panel, "suppressAutoApply", Boolean.class));

                detectionInputs.put("growSigmaMultiplier", removedGrow);
                spinGrowSigma.setValue(2.0);

                // 3. Binding failure after typedBindings have applied still aborts checkedSettings, Save and runs.
                @SuppressWarnings("unchecked")
                List<ApplicationSettingBinding<?>> visualizationBindings = privateField(panel, "visualizationBindings", List.class);
                ApplicationSettingBinding<Double> brokenBinding = new ApplicationSettingBinding<>(
                        "brokenStretchSigma", "Broken Stretch Sigma", "sigma", "Failing test binding.", false,
                        Double.class, () -> 1.0, value -> { }, () -> Double.NaN, value -> { });
                visualizationBindings.add(brokenBinding);
                setPrivateField(panel, "suppressAutoApply", true);
                spinDetectionSigma.setValue(4.1);
                setPrivateField(panel, "suppressAutoApply", false);

                assertThrows(IllegalArgumentException.class, () -> panel.checkedSettings("Detect Moving Targets did not start."));
                assertThrows(IllegalArgumentException.class, saveButton::doClick);
                assertThrows(IllegalArgumentException.class,
                        () -> invokePrivate(panel, "runAutoTuner", new Class<?>[]{AutoTuneProfile.class}, profile));
                assertThrows(IllegalArgumentException.class,
                        () -> invokePrivate(panel, "openStarMaskExplorer", new Class<?>[0]));
                assertEquals(0, admittedJobs.get());
                assertNull(jobs.current());
                assertTrue(saveButton.isEnabled());
                assertFalse(profileFile.exists());
                assertEquals(initialBaseline, privateField(panel, "savedSnapshot", String.class));
                assertEquals(new DetectionConfig().detectionSigmaMultiplier,
                        privateField(panel, "savedConfig", DetectionConfig.class).detectionSigmaMultiplier, 0.0);
                assertFalse(privateField(panel, "suppressAutoApply", Boolean.class));
            });
        } finally {
            try {
                storage.finish();
            } finally {
                System.setProperty("user.home", originalHome);
                if (root.nodeExists(preferenceNode)) {
                    root.node(preferenceNode).removeNode();
                    root.flush();
                }
                if (prefExisted) {
                    Preferences.importPreferences(new ByteArrayInputStream(exportedPrefs));
                    root.flush();
                }
            }
        }
    }

    private static <T> T privateField(Object target, String name, Class<T> type) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setPrivateField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void invokePrivate(Object target, String name, Class<?>[] parameterTypes, Object... args) {
        try {
            var method = target.getClass().getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            method.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compares serialized field changes so accidental cross-field getters/setters cannot pass a round trip. */
    private static <Value> void checkOwnership(DetectionSetting<Value> setting) {
        DetectionConfig config = new DetectionConfig();
        Gson gson = new Gson();
        JsonObject before = gson.toJsonTree(config).getAsJsonObject();
        Object current = setting.read(config);
        Object replacement;
        if (current instanceof Boolean enabled) replacement = !enabled;
        else if (current instanceof Integer count) replacement = count + 1;
        else replacement = ((Number) current).doubleValue() + 1;
        Value value = setting.type().cast(replacement);
        setting.write(config, value);
        assertEquals(value, setting.read(config));
        JsonObject after = gson.toJsonTree(config).getAsJsonObject();
        Set<String> changed = new HashSet<>();
        for (String field : before.keySet()) {
            if (!before.get(field).equals(after.get(field))) changed.add(field);
        }
        assertEquals(Set.of(setting.id()), changed);
    }
}
