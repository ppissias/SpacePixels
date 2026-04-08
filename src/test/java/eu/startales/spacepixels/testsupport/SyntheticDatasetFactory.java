package eu.startales.spacepixels.testsupport;

import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO;
import io.github.ppissias.jtransient.config.DetectionConfig;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SyntheticDatasetFactory {

    public static final int DEFAULT_FRAME_COUNT = 5;
    public static final int DEFAULT_WIDTH = 96;
    public static final int DEFAULT_HEIGHT = 96;

    private static final int[][] STAR_CENTERS = {
            {14, 14}, {30, 18}, {48, 20}, {70, 14}, {82, 26},
            {18, 46}, {40, 40}, {62, 50}, {78, 66}, {28, 76}
    };
    private static final int[][] STAR_KERNEL = {
            {0, 1, 2, 1, 0},
            {1, 3, 5, 3, 1},
            {2, 5, 9, 5, 2},
            {1, 3, 5, 3, 1},
            {0, 1, 2, 1, 0}
    };

    private SyntheticDatasetFactory() {
    }

    public static Path createMono16FitsSequence(Path rootDirectory, String directoryName, int frameCount) throws Exception {
        Path sequenceDirectory = Files.createDirectories(rootDirectory.resolve(directoryName));
        for (int i = 0; i < frameCount; i++) {
            writeFits(sequenceDirectory.resolve(frameFilename(i)), createMono16Frame(), standardHeaderValues(i));
        }
        return sequenceDirectory;
    }

    public static Path createMono32FloatFitsSequence(Path rootDirectory, String directoryName, int frameCount) throws Exception {
        Path sequenceDirectory = Files.createDirectories(rootDirectory.resolve(directoryName));
        for (int i = 0; i < frameCount; i++) {
            writeFits(sequenceDirectory.resolve(frameFilename(i)), createMonoFloatFrame(), standardHeaderValues(i));
        }
        return sequenceDirectory;
    }

    public static Path createColor16FitsSequence(Path rootDirectory, String directoryName, int frameCount) throws Exception {
        Path sequenceDirectory = Files.createDirectories(rootDirectory.resolve(directoryName));
        for (int i = 0; i < frameCount; i++) {
            writeFits(sequenceDirectory.resolve(frameFilename(i)), createColor16Frame(), standardHeaderValues(i));
        }
        return sequenceDirectory;
    }

    public static Path createColor32FloatFitsSequence(Path rootDirectory, String directoryName, int frameCount) throws Exception {
        Path sequenceDirectory = Files.createDirectories(rootDirectory.resolve(directoryName));
        for (int i = 0; i < frameCount; i++) {
            writeFits(sequenceDirectory.resolve(frameFilename(i)), createColorFloatFrame(), standardHeaderValues(i));
        }
        return sequenceDirectory;
    }

    public static Path createGrayFloatXisfSequence(Path rootDirectory, String directoryName, int frameCount) throws Exception {
        Path sequenceDirectory = Files.createDirectories(rootDirectory.resolve(directoryName));
        for (int i = 0; i < frameCount; i++) {
            writeMonolithicXisf(
                    sequenceDirectory.resolve(frameFilename(i).replace(".fit", ".xisf")),
                    DEFAULT_WIDTH + ":" + DEFAULT_HEIGHT + ":1",
                    "Float32",
                    "Gray",
                    "Planar",
                    "0:1",
                    "",
                    toFloatBytes(createMonoFloatFrame()),
                    fitsKeywordXml(i));
        }
        return sequenceDirectory;
    }

    public static DetectionConfig createSyntheticDetectionConfig() {
        DetectionConfig config = new DetectionConfig();
        config.enableSlowMoverDetection = false;
        config.enableAnomalyRescue = false;
        config.minFramesForAnalysis = 3;
        config.minDetectionPixels = 5;
        config.qualityMinDetectionPixels = 5;
        return config;
    }

    public static Path writeDetectionProfile(Path rootDirectory, String filename, DetectionConfig detectionConfig) throws Exception {
        Path configPath = rootDirectory.resolve(filename);
        try (Writer writer = Files.newBufferedWriter(configPath)) {
            SpacePixelsDetectionProfileIO.write(
                    writer,
                    detectionConfig,
                    SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES);
        }
        return configPath;
    }

    public static int countFitsFiles(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> stream = Files.list(directory)) {
            return (int) stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String lower = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return lower.endsWith(".fit") || lower.endsWith(".fits") || lower.endsWith(".fts");
                    })
                    .count();
        }
    }

    private static String frameFilename(int frameIndex) {
        return String.format(java.util.Locale.US, "frame_%02d.fit", frameIndex + 1);
    }

    private static Map<String, Object> standardHeaderValues(int frameIndex) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("DATE-OBS", String.format(java.util.Locale.US, "2026-04-08T00:00:%02d", frameIndex));
        values.put("EXPTIME", 30.0d);
        return values;
    }

    private static short[][] createMono16Frame() {
        short[][] frame = new short[DEFAULT_HEIGHT][DEFAULT_WIDTH];
        fill(frame, (short) 640);
        stampStars(frame, 620);
        return frame;
    }

    private static float[][] createMonoFloatFrame() {
        float[][] frame = new float[DEFAULT_HEIGHT][DEFAULT_WIDTH];
        fill(frame, 0.02f);
        stampStars(frame, 0.085f);
        return frame;
    }

    private static short[][][] createColor16Frame() {
        short[][][] frame = new short[3][DEFAULT_HEIGHT][DEFAULT_WIDTH];
        fill(frame[0], (short) 600);
        fill(frame[1], (short) 680);
        fill(frame[2], (short) 760);
        stampStars(frame[0], 660);
        stampStars(frame[1], 600);
        stampStars(frame[2], 540);
        return frame;
    }

    private static float[][][] createColorFloatFrame() {
        float[][][] frame = new float[3][DEFAULT_HEIGHT][DEFAULT_WIDTH];
        fill(frame[0], 0.020f);
        fill(frame[1], 0.024f);
        fill(frame[2], 0.028f);
        stampStars(frame[0], 0.095f);
        stampStars(frame[1], 0.082f);
        stampStars(frame[2], 0.070f);
        return frame;
    }

    private static void stampStars(short[][] frame, int amplitude) {
        for (int[] center : STAR_CENTERS) {
            stampStar(frame, center[0], center[1], amplitude);
        }
    }

    private static void stampStars(float[][] frame, float amplitude) {
        for (int[] center : STAR_CENTERS) {
            stampStar(frame, center[0], center[1], amplitude);
        }
    }

    private static void stampStar(short[][] frame, int centerX, int centerY, int amplitude) {
        for (int ky = 0; ky < STAR_KERNEL.length; ky++) {
            for (int kx = 0; kx < STAR_KERNEL[ky].length; kx++) {
                int x = centerX + kx - 2;
                int y = centerY + ky - 2;
                if (x < 0 || y < 0 || x >= DEFAULT_WIDTH || y >= DEFAULT_HEIGHT) {
                    continue;
                }
                frame[y][x] = (short) Math.min(12000, frame[y][x] + (STAR_KERNEL[ky][kx] * amplitude));
            }
        }
    }

    private static void stampStar(float[][] frame, int centerX, int centerY, float amplitude) {
        for (int ky = 0; ky < STAR_KERNEL.length; ky++) {
            for (int kx = 0; kx < STAR_KERNEL[ky].length; kx++) {
                int x = centerX + kx - 2;
                int y = centerY + ky - 2;
                if (x < 0 || y < 0 || x >= DEFAULT_WIDTH || y >= DEFAULT_HEIGHT) {
                    continue;
                }
                frame[y][x] = Math.min(0.95f, frame[y][x] + (STAR_KERNEL[ky][kx] * amplitude));
            }
        }
    }

    private static void fill(short[][] frame, short value) {
        for (short[] row : frame) {
            java.util.Arrays.fill(row, value);
        }
    }

    private static void fill(float[][] frame, float value) {
        for (float[] row : frame) {
            java.util.Arrays.fill(row, value);
        }
    }

    private static void writeFits(Path targetPath, Object data, Map<String, Object> headerValues) throws Exception {
        Files.createDirectories(targetPath.getParent());
        try (Fits fits = new Fits()) {
            BasicHDU<?> hdu = Fits.makeHDU(data);
            Header header = hdu.getHeader();
            for (Map.Entry<String, Object> entry : headerValues.entrySet()) {
                addHeaderValue(header, entry.getKey(), entry.getValue());
            }
            fits.addHDU(hdu);
            Files.deleteIfExists(targetPath);
            fits.write(targetPath.toFile());
        }
    }

    private static void addHeaderValue(Header header, String key, Object value) throws Exception {
        if (value instanceof Integer) {
            header.addValue(key, (Integer) value, null);
            return;
        }
        if (value instanceof Long) {
            header.addValue(key, (Long) value, null);
            return;
        }
        if (value instanceof Float) {
            header.addValue(key, (Float) value, null);
            return;
        }
        if (value instanceof Double) {
            header.addValue(key, (Double) value, null);
            return;
        }
        header.addValue(key, value.toString(), null);
    }

    private static byte[] toFloatBytes(float[][] data) {
        ByteBuffer buffer = ByteBuffer.allocate(DEFAULT_WIDTH * DEFAULT_HEIGHT * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] row : data) {
            for (float value : row) {
                buffer.putFloat(value);
            }
        }
        return buffer.array();
    }

    private static String fitsKeywordXml(int frameIndex) {
        return "<FITSKeyword name=\"DATE-OBS\" value=\"'" + String.format(java.util.Locale.US, "2026-04-08T00:00:%02d", frameIndex)
                + "'\" comment=\"Observation time\" />\n"
                + "    <FITSKeyword name=\"EXPTIME\" value=\"30.0\" comment=\"Seconds\" />";
    }

    private static void writeMonolithicXisf(Path targetPath,
                                            String geometry,
                                            String sampleFormat,
                                            String colorSpace,
                                            String pixelStorage,
                                            String bounds,
                                            String extraAttributes,
                                            byte[] payload,
                                            String childXml) throws IOException {
        String boundsAttribute = bounds == null ? "" : " bounds=\"" + bounds + "\"";
        String imageTemplate = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<xisf xmlns=\"http://www.pixinsight.com/xisf\" version=\"1.0\">\n"
                + "  <Image geometry=\"" + geometry + "\" sampleFormat=\"" + sampleFormat + "\" colorSpace=\"" + colorSpace + "\""
                + " pixelStorage=\"" + pixelStorage + "\"" + boundsAttribute + "%s location=\"attachment:%d:%d\">\n"
                + "    %s\n"
                + "  </Image>\n"
                + "</xisf>\n";

        String extra = extraAttributes == null ? "" : extraAttributes;
        String xml = String.format(imageTemplate, extra, 0, payload.length, childXml == null ? "" : childXml);
        int payloadPosition = 16 + xml.getBytes(StandardCharsets.UTF_8).length;

        while (true) {
            String candidate = String.format(imageTemplate, extra, payloadPosition, payload.length, childXml == null ? "" : childXml);
            int candidatePosition = 16 + candidate.getBytes(StandardCharsets.UTF_8).length;
            if (candidatePosition == payloadPosition) {
                xml = candidate;
                break;
            }
            payloadPosition = candidatePosition;
        }

        byte[] headerBytes = xml.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("XISF0100".getBytes(StandardCharsets.US_ASCII));
        output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(headerBytes.length).array());
        output.write(new byte[4]);
        output.write(headerBytes);
        output.write(payload);
        Files.write(targetPath, output.toByteArray());
    }
}
