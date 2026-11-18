package eu.startales.spacepixels.detection;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.DetectionRunOptions;
import io.github.ppissias.jtransient.compute.ComputeMode;
import io.github.ppissias.jtransient.compute.ComputeOptions;

import java.io.File;
import java.nio.file.Path;

/**
 * Translates SpacePixels application/run options into JTransient {@link ComputeOptions} and locates the
 * persistent OpenCL binary/benchmark cache folder beside {@code spacepixels_app.json}.
 */
public final class ComputeOptionsMapper {

    /** Folder inside {@code user.home} holding compiled OpenCL binaries and benchmark results. */
    public static final String CACHE_DIR_NAME = "spacepixels_opencl_cache";

    private ComputeOptionsMapper() {
    }

    /** Returns the user-home OpenCL cache directory. */
    public static Path defaultCacheDirectory() {
        return new File(System.getProperty("user.home", ""), CACHE_DIR_NAME).toPath();
    }

    /** Converts {@link AppConfig} GPU settings into engine {@link ComputeOptions}. */
    public static ComputeOptions from(AppConfig config) {
        if (config == null) {
            return ComputeOptions.cpuOnly();
        }
        ComputeMode mode = ComputeMode.parseOrDefault(config.gpuMode(), ComputeMode.AUTO);
        if (mode == ComputeMode.CPU) {
            return ComputeOptions.cpuOnly();
        }
        return new ComputeOptions(mode, config.gpuDeviceId(), defaultCacheDirectory());
    }

    /** Converts captured {@link DetectionRunOptions} into engine {@link ComputeOptions}. */
    public static ComputeOptions from(DetectionRunOptions options) {
        if (options == null) {
            return ComputeOptions.cpuOnly();
        }
        return from(options.applicationConfig());
    }
}
