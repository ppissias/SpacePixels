package eu.startales.spacepixels.preparation;

import eu.startales.spacepixels.preparation.PreparationInputs.Role;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** DSS file-list settings override processing in memory, with all inputs and intermediates in the owned workspace. */
public final class DssPreparationRecipe {
    /** Generated DSS lists contain only captured working copies, never writable original paths. */
    private DssPreparationRecipe() { }

    /** Generates the selected frame list and explicit linear, star-registration settings in DSS's native format. */
    public static String fileList(PreparationRequest request, Map<Role, List<Path>> copies, String pattern) {
        StringBuilder list = new StringBuilder("DSS file list\nCHECKED\tTYPE\tFILE\n");
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("Stacking/BackgroundCalibration", "false");
        settings.put("Stacking/PerChannelBackgroundCalibration", "false");
        settings.put("Stacking/DarkOptimization", "false");
        settings.put("Stacking/UseDarkFactor", "false");
        settings.put("Stacking/Debloom", "false");
        settings.put("Stacking/HotPixelsDetection", "false");
        settings.put("Stacking/BadLinesDetection", "false");
        settings.put("Stacking/PCS_DetectCleanHot", "false");
        settings.put("Stacking/PCS_DetectCleanCold", "false");
        settings.put("Stacking/CometStackingMode", "0");
        settings.put("Stacking/SaveCometImages", "false");
        settings.put("Stacking/UseWCS", "false");
        settings.put("Stacking/Mosaic", "0"); // Standard Mode: reference-sized canvas with dark registration margins.
        settings.put("Stacking/PixelSizeMultiplier", "1");
        settings.put("Stacking/AlignChannels", "false");
        settings.put("Stacking/AlignmentTransformation", "2");
        settings.put("Stacking/SaveCalibrated", "false");
        settings.put("Stacking/SaveCalibratedDebayered", "false");
        settings.put("Stacking/IntermediateFileFormat", "2");
        settings.put("Stacking/CompressFITS", "false");
        settings.put("RawDDP/Interpolation", "Bilinear");
        settings.put("RawDDP/RawBayer", "false");
        settings.put("RawDDP/SuperPixels", "false");
        settings.put("RawDDP/BlackPointTo0", "false");
        settings.put("RawDDP/CameraWB", "false");
        settings.put("RawDDP/AutoWB", "false");
        settings.put("RawDDP/Brightness", "1");
        settings.put("RawDDP/RedScale", "1");
        settings.put("RawDDP/BlueScale", "1");
        settings.put("FitsDDP/Brightness", "1");
        settings.put("FitsDDP/RedScale", "1");
        settings.put("FitsDDP/BlueScale", "1");
        settings.put("FitsDDP/FITSisRAW", Boolean.toString(request.sensor() == PreparationRequest.Sensor.CFA && pattern != null));
        settings.put("FitsDDP/BayerPattern", switch (pattern == null ? "" : pattern) { case "BGGR" -> "1"; case "GRBG" -> "2"; case "GBRG" -> "3"; case "RGGB" -> "4"; default -> "0"; });
        settings.forEach((key, value) -> list.append("#V5WS#").append(key).append('#').append(value).append('\n'));
        copies.forEach((role, files) -> {
            String type = switch (role) { case LIGHTS -> "light"; case DARKS -> "dark"; case FLATS -> "flat"; case BIASES -> "offset"; case DARK_FLATS -> "darkflat"; };
            for (Path file : files) list.append("1\t").append(type).append('\t').append(file).append('\n');
        });
        return list.toString();
    }
}
