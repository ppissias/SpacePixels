package eu.startales.spacepixels.config;

import eu.startales.spacepixels.detection.ComputeOptionsMapper;
import io.github.ppissias.jtransient.compute.ComputeMode;
import io.github.ppissias.jtransient.compute.ComputeOptions;
import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

/** Verifies OpenCL compute configuration normalization, legacy option defaults, and engine option mapping. */
public class GpuConfigAndMapperTest {

    @Test
    public void appConfigNormalizesAndCopiesGpuSettings() {
        AppConfig cfg = new AppConfig();
        assertEquals("AUTO", cfg.gpuMode());
        assertEquals("", cfg.gpuDeviceId());

        cfg.gpuMode = " gpu ";
        cfg.gpuDeviceId = "  NVIDIA-0  ";
        assertEquals("GPU", cfg.gpuMode());
        assertEquals("NVIDIA-0", cfg.gpuDeviceId());

        AppConfig copy = cfg.copy();
        assertEquals("GPU", copy.gpuMode());
        assertEquals("NVIDIA-0", copy.gpuDeviceId());

        cfg.gpuMode = "unknown";
        assertEquals("AUTO", cfg.gpuMode());
        cfg.gpuMode = "cpu";
        assertEquals("CPU", cfg.gpuMode());
    }

    @Test
    public void detectionRunOptionsPreserveLegacyCpuDefaultsAndAppCapture() {
        DetectionRunOptions legacy5 = new DetectionRunOptions("500", "38.0", "23.7", VisualizationSettings.defaults(), 10);
        assertEquals("CPU", legacy5.gpuMode());
        assertEquals("", legacy5.gpuDeviceId());
        DetectionRunOptions legacy6 = new DetectionRunOptions("500", "38.0", "23.7", "100", VisualizationSettings.defaults(), 10);
        assertEquals("CPU", legacy6.gpuMode());
        assertEquals("CPU", DetectionRunOptions.defaults().gpuMode());

        AppConfig app = new AppConfig();
        app.gpuMode = "GPU";
        app.gpuDeviceId = "opencl:0:NVIDIA:RTX5060";
        DetectionRunOptions captured = DetectionRunOptions.capture(app, 25);
        assertEquals("GPU", captured.gpuMode());
        assertEquals("opencl:0:NVIDIA:RTX5060", captured.gpuDeviceId());

        DetectionRunOptions updated = captured
                .withAutoTuneMaxCandidateFrames(12)
                .withVisualization(VisualizationSettings.defaults())
                .withGpuMode("auto");
        assertEquals("AUTO", updated.gpuMode());
        assertEquals("opencl:0:NVIDIA:RTX5060", updated.gpuDeviceId());
        assertEquals("dev-2", updated.withGpuDeviceId(" dev-2 ").gpuDeviceId());
    }

    @Test
    public void computeOptionsMapperTranslatesModesAndCacheDirectory() {
        assertEquals(ComputeMode.CPU, ComputeOptionsMapper.from((AppConfig) null).mode());

        AppConfig cpu = new AppConfig();
        cpu.gpuMode = "CPU";
        cpu.gpuDeviceId = "ignored";
        assertEquals(ComputeMode.CPU, ComputeOptionsMapper.from(cpu).mode());

        AppConfig gpu = new AppConfig();
        gpu.gpuMode = "GPU";
        gpu.gpuDeviceId = "dev-1";
        ComputeOptions mapped = ComputeOptionsMapper.from(gpu);
        assertEquals(ComputeMode.GPU, mapped.mode());
        assertEquals("dev-1", mapped.deviceId());
        assertEquals(Path.of(System.getProperty("user.home"), ComputeOptionsMapper.CACHE_DIR_NAME), mapped.cacheDirectory());

        DetectionRunOptions opts = DetectionRunOptions.capture(gpu, 10);
        assertEquals(ComputeMode.GPU, ComputeOptionsMapper.from(opts).mode());
        assertEquals("dev-1", ComputeOptionsMapper.from(opts).deviceId());
    }
}
