package eu.startales.spacepixels.util;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class XisfImageConverterTest {

    @Test
    public void prepareDirectoryForFitsImportConvertsFloatGrayXisfTo16BitMonoFits() throws Exception {
        Path sourceDirectory = Files.createTempDirectory("spacepixels-xisf-gray");

        byte[] pixelBytes = ByteBuffer.allocate(4 * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(0.0f)
                .putFloat(0.5f)
                .putFloat(1.0f)
                .putFloat(0.25f)
                .array();

        writeMonolithicXisf(
                sourceDirectory.resolve("gray.xisf"),
                "2:2:1",
                "Float32",
                "Gray",
                "Planar",
                "0:1",
                "",
                pixelBytes,
                "<FITSKeyword name=\"DATE-OBS\" value=\"'2026-04-06T01:02:03'\" comment=\"Observation time\" />");

        File importDirectory = XisfImageConverter.prepareDirectoryForFitsImport(sourceDirectory.toFile());

        assertTrue(!sourceDirectory.toFile().equals(importDirectory));
        Path outputFits = importDirectory.toPath().resolve("gray.fit");
        assertTrue(Files.exists(outputFits));

        try (Fits fits = new Fits(outputFits.toFile())) {
            BasicHDU<?> hdu = ImageProcessing.getImageHDU(fits);
            Header header = hdu.getHeader();
            short[][] data = (short[][]) hdu.getKernel();

            assertEquals("2026-04-06T01:02:03", header.getStringValue("DATE-OBS"));
            assertEquals(32768.0, header.getDoubleValue("BZERO"), 0.0);
            assertEquals(1.0, header.getDoubleValue("BSCALE"), 0.0);

            assertEquals(-32768, data[0][0]);
            assertEquals(0, data[0][1]);
            assertEquals(32767, data[1][0]);
            assertEquals(-16384, data[1][1]);
        }
    }

    @Test
    public void prepareDirectoryForFitsImportConvertsCompressedRgbXisfToMonochromeFits() throws Exception {
        Path sourceDirectory = Files.createTempDirectory("spacepixels-xisf-rgb");

        ByteBuffer rawPixels = ByteBuffer.allocate(2 * 2 * 3).order(ByteOrder.LITTLE_ENDIAN);
        rawPixels.putShort((short) 0xFFFF).putShort((short) 0x0000); // R plane
        rawPixels.putShort((short) 0x0000).putShort((short) 0xFFFF); // G plane
        rawPixels.putShort((short) 0xFFFF).putShort((short) 0xFFFF); // B plane

        byte[] shuffled = shuffleBytes(rawPixels.array(), 2);
        byte[] compressed = compressZlib(shuffled);

        writeMonolithicXisf(
                sourceDirectory.resolve("rgb.xisf"),
                "1:2:3",
                "UInt16",
                "RGB",
                "Planar",
                null,
                " compression=\"zlib+sh:12:2\"",
                compressed,
                "<FITSKeyword name=\"EXPTIME\" value=\"30.5\" comment=\"Seconds\" />");

        File importDirectory = XisfImageConverter.prepareDirectoryForFitsImport(sourceDirectory.toFile());
        Path outputFits = importDirectory.toPath().resolve("rgb.fit");
        assertTrue(Files.exists(outputFits));

        try (Fits fits = new Fits(outputFits.toFile())) {
            BasicHDU<?> hdu = ImageProcessing.getImageHDU(fits);
            Header header = hdu.getHeader();
            short[][] data = (short[][]) hdu.getKernel();

            assertEquals(30.5, header.getDoubleValue("EXPTIME"), 0.0);
            assertNotNull(data);
            assertEquals(2, data.length);
            assertEquals(1, data[0].length);
            assertEquals(10922, data[0][0]);
            assertEquals(10922, data[1][0]);
        }
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

    private static byte[] compressZlib(byte[] input) throws IOException {
        Deflater deflater = new Deflater();
        deflater.setInput(input);
        deflater.finish();

        byte[] buffer = new byte[256];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (!deflater.finished()) {
            int written = deflater.deflate(buffer);
            output.write(buffer, 0, written);
        }
        deflater.end();
        return output.toByteArray();
    }

    private static byte[] shuffleBytes(byte[] input, int itemSize) {
        byte[] output = new byte[input.length];
        int itemCount = input.length / itemSize;
        int destinationIndex = 0;

        for (int byteIndex = 0; byteIndex < itemSize; byteIndex++) {
            int sourceIndex = byteIndex;
            for (int itemIndex = 0; itemIndex < itemCount; itemIndex++) {
                output[destinationIndex++] = input[sourceIndex];
                sourceIndex += itemSize;
            }
        }

        if ((itemCount * itemSize) < input.length) {
            System.arraycopy(input, itemCount * itemSize, output, destinationIndex, input.length - (itemCount * itemSize));
        }
        return output;
    }
}
