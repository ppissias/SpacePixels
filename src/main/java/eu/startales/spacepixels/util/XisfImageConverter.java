package eu.startales.spacepixels.util;

import eu.startales.spacepixels.gui.ApplicationWindow;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.FitsFactory;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCardException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Converts PixInsight XISF images into monochrome FITS files that SpacePixels can ingest through
 * the same batch-import path used for native FITS sequences.
 */
public final class XisfImageConverter {

    private static final byte[] MONOLITHIC_SIGNATURE = "XISF0100".getBytes(StandardCharsets.US_ASCII);
    private static final String GENERATED_DIRECTORY_NAME = "_spacepixels_xisf_mono16";
    private static final Set<String> FITS_EXTENSIONS = Set.of("fit", "fits", "fts", "fz");
    private static final Set<String> XISF_EXTENSIONS = Set.of("xisf");
    private static final Set<String> STRUCTURAL_FITS_KEYS = new HashSet<>(Arrays.asList(
            "SIMPLE", "BITPIX", "NAXIS", "NAXIS1", "NAXIS2", "NAXIS3", "EXTEND",
            "PCOUNT", "GCOUNT", "BZERO", "BSCALE"
    ));

    private XisfImageConverter() {
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int percentage, String message);
    }

    public static File prepareDirectoryForFitsImport(File directory) throws Exception {
        return prepareDirectoryForFitsImport(directory, null);
    }

    public static File prepareDirectoryForFitsImport(File directory, ProgressListener progressListener) throws Exception {
        if (directory == null || !directory.isDirectory()) {
            return directory;
        }

        if (containsFilesWithExtensions(directory, FITS_EXTENSIONS)) {
            return directory;
        }

        File[] xisfFiles = listFilesWithExtensions(directory, XISF_EXTENSIONS);
        if (xisfFiles.length == 0) {
            return directory;
        }

        ApplicationWindow.logger.info("No FITS files found. Converting " + xisfFiles.length + " XISF files to 16-bit monochrome FITS.");
        return convertDirectory(directory, xisfFiles, progressListener);
    }

    private static File convertDirectory(File sourceDirectory, File[] xisfFiles, ProgressListener progressListener) throws Exception {
        File outputDirectory = new File(sourceDirectory, GENERATED_DIRECTORY_NAME);
        Files.createDirectories(outputDirectory.toPath());

        for (int i = 0; i < xisfFiles.length; i++) {
            File xisfFile = xisfFiles[i];
            if (progressListener != null) {
                int percent = xisfFiles.length == 0 ? 0 : (int) ((i * 80.0f) / xisfFiles.length);
                progressListener.onProgress(percent, "Converting XISF " + (i + 1) + " of " + xisfFiles.length + ": " + xisfFile.getName());
            }
            XisfImageData imageData = readImage(xisfFile);
            File outputFile = new File(outputDirectory, stripExtension(xisfFile.getName()) + ".fit");
            writeFits(imageData, outputFile);
        }

        if (progressListener != null) {
            progressListener.onProgress(85, "XISF conversion complete. Preparing FITS import...");
        }

        return outputDirectory;
    }

    private static void writeFits(XisfImageData imageData, File outputFile) throws FitsException, IOException {
        try (Fits fits = new Fits()) {
            BasicHDU<?> hdu = FitsFactory.hduFactory(imageData.monoData);
            fits.addHDU(hdu);

            Header header = hdu.getHeader();
            header.deleteKey("BZERO");
            header.deleteKey("BSCALE");
            header.addValue("BZERO", 32768.0, "offset data range to that of unsigned short");
            header.addValue("BSCALE", 1.0, "default scaling factor");
            applyFitsKeywords(header, imageData.fitsKeywords);

            Files.deleteIfExists(outputFile.toPath());
            fits.write(outputFile);
        } catch (HeaderCardException e) {
            throw new IOException("Failed to write FITS header for " + outputFile.getName(), e);
        }
    }

    private static void applyFitsKeywords(Header header, List<FitsKeyword> fitsKeywords) throws HeaderCardException {
        for (FitsKeyword keyword : fitsKeywords) {
            if (keyword.name == null || keyword.name.isBlank()) {
                continue;
            }

            String name = keyword.name.trim();
            String comment = keyword.comment == null || keyword.comment.isBlank() ? null : keyword.comment;
            String value = keyword.value == null ? "" : keyword.value.trim();

            if ("COMMENT".equals(name)) {
                if (comment != null) {
                    header.insertComment(comment);
                } else if (!value.isEmpty()) {
                    header.insertComment(decodeFitsString(value));
                }
                continue;
            }
            if ("HISTORY".equals(name)) {
                if (comment != null) {
                    header.insertHistory(comment);
                } else if (!value.isEmpty()) {
                    header.insertHistory(decodeFitsString(value));
                }
                continue;
            }
            if (STRUCTURAL_FITS_KEYS.contains(name)) {
                continue;
            }

            if (header.containsKey(name)) {
                header.deleteKey(name);
            }

            if (isFitsStringLiteral(value)) {
                header.addValue(name, decodeFitsString(value), comment);
            } else if ("T".equals(value) || "F".equals(value)) {
                header.addValue(name, "T".equals(value), comment);
            } else if (looksLikeInteger(value)) {
                try {
                    header.addValue(name, Long.parseLong(value), comment);
                } catch (NumberFormatException ex) {
                    header.addValue(name, value, comment);
                }
            } else if (looksLikeFloatingPoint(value)) {
                try {
                    header.addValue(name, Double.parseDouble(value), comment);
                } catch (NumberFormatException ex) {
                    header.addValue(name, value, comment);
                }
            } else if (!value.isEmpty()) {
                header.addValue(name, value, comment);
            } else {
                header.addValue(name, "", comment);
            }
        }
    }

    private static boolean isFitsStringLiteral(String value) {
        return value.length() >= 2 && value.startsWith("'") && value.endsWith("'");
    }

    private static String decodeFitsString(String value) {
        if (!isFitsStringLiteral(value)) {
            return value;
        }
        return value.substring(1, value.length() - 1).replace("''", "'");
    }

    private static boolean looksLikeInteger(String value) {
        return value != null && value.matches("[+-]?\\d+");
    }

    private static boolean looksLikeFloatingPoint(String value) {
        return value != null && value.matches("[+-]?(?:\\d+\\.\\d*|\\d*\\.\\d+|\\d+)(?:[eE][+-]?\\d+)?");
    }

    private static XisfImageData readImage(File xisfFile) throws Exception {
        try (RandomAccessFile input = new RandomAccessFile(xisfFile, "r")) {
            validateMonolithicSignature(input, xisfFile);

            int headerLength = readInt32LittleEndian(input);
            readInt32LittleEndian(input);

            byte[] headerBytes = new byte[headerLength];
            input.readFully(headerBytes);

            Document document = parseXmlHeader(headerBytes);
            Element rootElement = document.getDocumentElement();
            Element imageElement = findPrimaryImageElement(rootElement);
            if (imageElement == null) {
                throw new IOException("No Image element found in XISF file " + xisfFile.getName());
            }

            Geometry geometry = parseGeometry(imageElement.getAttribute("geometry"));
            SampleFormat sampleFormat = SampleFormat.parse(imageElement.getAttribute("sampleFormat"));
            String colorSpace = imageElement.hasAttribute("colorSpace") ? imageElement.getAttribute("colorSpace") : "Gray";
            String pixelStorage = imageElement.hasAttribute("pixelStorage") ? imageElement.getAttribute("pixelStorage") : "Planar";
            ByteOrder byteOrder = "big".equalsIgnoreCase(imageElement.getAttribute("byteOrder")) ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
            SampleScaling scaling = SampleScaling.forImage(sampleFormat, imageElement.getAttribute("bounds"));

            byte[] serializedBlock = readDataBlock(xisfFile, input, imageElement);
            byte[] decodedBlock = decodeDataBlock(serializedBlock, imageElement.getAttribute("compression"));

            short[][] monoData = decodeMonochromeImage(decodedBlock, geometry, sampleFormat, colorSpace, pixelStorage, byteOrder, scaling);
            List<FitsKeyword> fitsKeywords = extractFitsKeywords(rootElement, imageElement);
            return new XisfImageData(monoData, fitsKeywords);
        }
    }

    private static void validateMonolithicSignature(RandomAccessFile input, File xisfFile) throws IOException {
        byte[] signature = new byte[MONOLITHIC_SIGNATURE.length];
        input.readFully(signature);
        if (!Arrays.equals(signature, MONOLITHIC_SIGNATURE)) {
            throw new IOException("Unsupported XISF container for " + xisfFile.getName() + ". Only monolithic XISF files are supported.");
        }
    }

    private static int readInt32LittleEndian(RandomAccessFile input) throws IOException {
        int b0 = input.readUnsignedByte();
        int b1 = input.readUnsignedByte();
        int b2 = input.readUnsignedByte();
        int b3 = input.readUnsignedByte();
        return (b0) | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static Document parseXmlHeader(byte[] headerBytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        try (ByteArrayInputStream input = new ByteArrayInputStream(headerBytes)) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static Element findPrimaryImageElement(Element rootElement) {
        NodeList children = rootElement.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && isNamed((Element) node, "Image")) {
                return (Element) node;
            }
        }

        NodeList descendants = rootElement.getElementsByTagName("*");
        for (int i = 0; i < descendants.getLength(); i++) {
            Node node = descendants.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && isNamed((Element) node, "Image")) {
                return (Element) node;
            }
        }
        return null;
    }

    private static List<FitsKeyword> extractFitsKeywords(Element rootElement, Element imageElement) {
        Map<String, Element> elementsByUid = new HashMap<>();
        NodeList allElements = rootElement.getElementsByTagName("*");
        for (int i = 0; i < allElements.getLength(); i++) {
            Node node = allElements.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) node;
            String uid = element.getAttribute("uid");
            if (!uid.isBlank()) {
                elementsByUid.put(uid, element);
            }
        }

        List<FitsKeyword> fitsKeywords = new ArrayList<>();
        NodeList imageChildren = imageElement.getChildNodes();
        for (int i = 0; i < imageChildren.getLength(); i++) {
            Node node = imageChildren.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }

            Element child = (Element) node;
            if (isNamed(child, "FITSKeyword")) {
                fitsKeywords.add(parseFitsKeyword(child));
                continue;
            }

            if (isNamed(child, "Reference")) {
                Element referencedElement = elementsByUid.get(child.getAttribute("ref"));
                if (referencedElement != null && isNamed(referencedElement, "FITSKeyword")) {
                    fitsKeywords.add(parseFitsKeyword(referencedElement));
                }
            }
        }
        return fitsKeywords;
    }

    private static FitsKeyword parseFitsKeyword(Element element) {
        return new FitsKeyword(
                element.getAttribute("name"),
                element.getAttribute("value"),
                element.getAttribute("comment")
        );
    }

    private static boolean isNamed(Element element, String expectedLocalName) {
        String localName = element.getLocalName();
        if (expectedLocalName.equals(localName)) {
            return true;
        }
        return expectedLocalName.equals(element.getTagName());
    }

    private static Geometry parseGeometry(String geometryText) throws IOException {
        if (geometryText == null || geometryText.isBlank()) {
            throw new IOException("Missing XISF image geometry attribute.");
        }

        String[] parts = geometryText.split(":");
        if (parts.length < 2) {
            throw new IOException("Unsupported XISF geometry: " + geometryText);
        }

        int width = Integer.parseInt(parts[0]);
        int height = Integer.parseInt(parts[1]);
        int channels = parts.length >= 3 ? Integer.parseInt(parts[2]) : 1;
        if (width <= 0 || height <= 0 || channels <= 0) {
            throw new IOException("Invalid XISF geometry: " + geometryText);
        }

        return new Geometry(width, height, channels);
    }

    private static byte[] readDataBlock(File xisfFile, RandomAccessFile input, Element imageElement) throws Exception {
        String location = imageElement.getAttribute("location");
        if (location == null || location.isBlank()) {
            throw new IOException("Missing XISF image data location in " + xisfFile.getName());
        }

        if (location.startsWith("attachment:")) {
            String[] parts = location.split(":");
            if (parts.length != 3) {
                throw new IOException("Unsupported XISF attachment location: " + location);
            }

            long position = Long.parseLong(parts[1]);
            int size = Integer.parseInt(parts[2]);
            byte[] data = new byte[size];
            input.seek(position);
            input.readFully(data);
            return data;
        }

        if (location.startsWith("inline:")) {
            String encoding = location.substring("inline:".length());
            return decodeTextEncodedData(encoding, imageElement.getTextContent());
        }

        if ("embedded".equals(location)) {
            Element dataElement = findFirstChildElement(imageElement, "Data");
            if (dataElement == null) {
                throw new IOException("Embedded XISF image data is missing its Data child element.");
            }
            return decodeTextEncodedData(dataElement.getAttribute("encoding"), dataElement.getTextContent());
        }

        if (location.startsWith("path(")) {
            return readExternalPathBlock(xisfFile, location.substring("path(".length()), true);
        }

        if (location.startsWith("url(")) {
            return readExternalPathBlock(xisfFile, location.substring("url(".length()), false);
        }

        throw new IOException("Unsupported XISF image location: " + location);
    }

    private static Element findFirstChildElement(Element parent, String expectedLocalName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && isNamed((Element) node, expectedLocalName)) {
                return (Element) node;
            }
        }
        return null;
    }

    private static byte[] readExternalPathBlock(File xisfFile, String locationPayload, boolean isPathLocation) throws Exception {
        int closeParen = locationPayload.lastIndexOf(')');
        if (closeParen < 0) {
            throw new IOException("Malformed XISF external block location.");
        }

        String pathSpec = locationPayload.substring(0, closeParen);
        String suffix = locationPayload.substring(closeParen + 1);
        if (!suffix.isBlank()) {
            throw new IOException("Indexed XISF data block files are not supported: " + suffix);
        }

        File targetFile;
        if (!isPathLocation) {
            URI uri = URI.create(pathSpec);
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("Only local file URLs are supported for XISF external data blocks.");
            }
            targetFile = new File(uri);
        } else if (pathSpec.startsWith("@header_dir/")) {
            String relativePath = pathSpec.substring("@header_dir/".length()).replace('/', File.separatorChar);
            targetFile = new File(xisfFile.getParentFile(), relativePath);
        } else {
            targetFile = new File(pathSpec.replace('/', File.separatorChar));
        }

        return Files.readAllBytes(targetFile.toPath());
    }

    private static byte[] decodeTextEncodedData(String encoding, String contents) throws IOException {
        String normalizedContents = contents == null ? "" : contents.replaceAll("\\s+", "");
        if ("base64".equalsIgnoreCase(encoding)) {
            return java.util.Base64.getDecoder().decode(normalizedContents);
        }
        if ("hex".equalsIgnoreCase(encoding)) {
            if ((normalizedContents.length() & 1) != 0) {
                throw new IOException("Malformed hexadecimal XISF data block.");
            }

            byte[] data = new byte[normalizedContents.length() / 2];
            for (int i = 0; i < data.length; i++) {
                int index = i * 2;
                data[i] = (byte) Integer.parseInt(normalizedContents.substring(index, index + 2), 16);
            }
            return data;
        }
        throw new IOException("Unsupported XISF inline encoding: " + encoding);
    }

    private static byte[] decodeDataBlock(byte[] serializedBlock, String compression) throws IOException {
        if (compression == null || compression.isBlank()) {
            return serializedBlock;
        }

        String[] parts = compression.split(":");
        String codec = parts[0].toLowerCase(Locale.ROOT);
        int expectedSize = Integer.parseInt(parts[1]);
        int itemSize = parts.length >= 3 ? Integer.parseInt(parts[2]) : 1;

        byte[] decompressed;
        switch (codec) {
            case "zlib":
                decompressed = inflateZlib(serializedBlock, expectedSize);
                break;
            case "zlib+sh":
                decompressed = unshuffleBytes(inflateZlib(serializedBlock, expectedSize), itemSize);
                break;
            case "lz4":
            case "lz4hc":
                decompressed = inflateLz4(serializedBlock, expectedSize);
                break;
            case "lz4+sh":
            case "lz4hc+sh":
                decompressed = unshuffleBytes(inflateLz4(serializedBlock, expectedSize), itemSize);
                break;
            default:
                throw new IOException("Unsupported XISF compression codec: " + codec);
        }

        if (decompressed.length != expectedSize) {
            throw new IOException("Decoded XISF data block size mismatch. Expected " + expectedSize + " bytes but decoded " + decompressed.length + " bytes.");
        }
        return decompressed;
    }

    private static byte[] inflateZlib(byte[] compressedBlock, int expectedSize) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(compressedBlock);

        byte[] output = new byte[expectedSize];
        try {
            int written = inflater.inflate(output);
            if (!inflater.finished() || written != expectedSize) {
                throw new IOException("Unexpected zlib payload size while decoding XISF data block.");
            }
            return output;
        } catch (DataFormatException e) {
            throw new IOException("Invalid zlib-compressed XISF data block.", e);
        } finally {
            inflater.end();
        }
    }

    private static byte[] inflateLz4(byte[] compressedBlock, int expectedSize) throws IOException {
        byte[] output = new byte[expectedSize];
        int src = 0;
        int dst = 0;

        while (src < compressedBlock.length) {
            int token = compressedBlock[src++] & 0xFF;

            int literalLength = token >>> 4;
            if (literalLength == 15) {
                int extension;
                do {
                    if (src >= compressedBlock.length) {
                        throw new IOException("Malformed LZ4 literal length in XISF data block.");
                    }
                    extension = compressedBlock[src++] & 0xFF;
                    literalLength += extension;
                } while (extension == 255);
            }

            if (src + literalLength > compressedBlock.length || dst + literalLength > output.length) {
                throw new IOException("Malformed LZ4 literals in XISF data block.");
            }

            System.arraycopy(compressedBlock, src, output, dst, literalLength);
            src += literalLength;
            dst += literalLength;

            if (src >= compressedBlock.length) {
                break;
            }

            if (src + 1 >= compressedBlock.length) {
                throw new IOException("Malformed LZ4 match offset in XISF data block.");
            }

            int offset = (compressedBlock[src] & 0xFF) | ((compressedBlock[src + 1] & 0xFF) << 8);
            src += 2;
            if (offset <= 0 || offset > dst) {
                throw new IOException("Invalid LZ4 match offset in XISF data block.");
            }

            int matchLength = token & 0x0F;
            if (matchLength == 15) {
                int extension;
                do {
                    if (src >= compressedBlock.length) {
                        throw new IOException("Malformed LZ4 match length in XISF data block.");
                    }
                    extension = compressedBlock[src++] & 0xFF;
                    matchLength += extension;
                } while (extension == 255);
            }
            matchLength += 4;

            if (dst + matchLength > output.length) {
                throw new IOException("Malformed LZ4 match range in XISF data block.");
            }

            for (int i = 0; i < matchLength; i++) {
                output[dst + i] = output[dst - offset + i];
            }
            dst += matchLength;
        }

        if (dst != expectedSize) {
            throw new IOException("Unexpected LZ4 payload size while decoding XISF data block.");
        }
        return output;
    }

    private static byte[] unshuffleBytes(byte[] shuffledData, int itemSize) {
        if (itemSize <= 1) {
            return shuffledData;
        }

        byte[] output = new byte[shuffledData.length];
        int itemCount = shuffledData.length / itemSize;
        int sourceIndex = 0;

        for (int byteIndex = 0; byteIndex < itemSize; byteIndex++) {
            int destIndex = byteIndex;
            for (int itemIndex = 0; itemIndex < itemCount; itemIndex++) {
                output[destIndex] = shuffledData[sourceIndex++];
                destIndex += itemSize;
            }
        }

        int remainderOffset = itemCount * itemSize;
        if (remainderOffset < shuffledData.length) {
            System.arraycopy(shuffledData, sourceIndex, output, remainderOffset, shuffledData.length - remainderOffset);
        }
        return output;
    }

    private static short[][] decodeMonochromeImage(byte[] rawData,
                                                   Geometry geometry,
                                                   SampleFormat sampleFormat,
                                                   String colorSpace,
                                                   String pixelStorage,
                                                   ByteOrder byteOrder,
                                                   SampleScaling scaling) throws IOException {
        int sampleSize = sampleFormat.byteSize;
        int totalSamples = geometry.width * geometry.height * geometry.channels;
        int expectedSize = totalSamples * sampleSize;
        if (rawData.length != expectedSize) {
            throw new IOException("Unexpected XISF image data size. Expected " + expectedSize + " bytes but found " + rawData.length + " bytes.");
        }

        boolean grayscale = colorSpace == null || colorSpace.isBlank() || "Gray".equalsIgnoreCase(colorSpace);
        boolean rgb = "RGB".equalsIgnoreCase(colorSpace);
        if (!grayscale && !rgb) {
            throw new IOException("Unsupported XISF color space: " + colorSpace);
        }

        boolean planarStorage = pixelStorage == null || pixelStorage.isBlank() || "Planar".equalsIgnoreCase(pixelStorage);
        ByteBuffer buffer = ByteBuffer.wrap(rawData).order(byteOrder);
        short[][] output = new short[geometry.height][geometry.width];
        int usableChannels = grayscale ? 1 : Math.min(3, geometry.channels);

        for (int y = 0; y < geometry.height; y++) {
            for (int x = 0; x < geometry.width; x++) {
                int pixelIndex = (y * geometry.width) + x;
                double sampleValue = 0.0;

                if (grayscale) {
                    sampleValue = readSample(buffer, sampleFormat, geometry, planarStorage, pixelIndex, 0);
                } else {
                    for (int channel = 0; channel < usableChannels; channel++) {
                        sampleValue += readSample(buffer, sampleFormat, geometry, planarStorage, pixelIndex, channel);
                    }
                    sampleValue /= usableChannels;
                }

                int unsignedValue = scaling.toUnsigned16(sampleValue);
                output[y][x] = (short) (unsignedValue - 32768);
            }
        }

        return output;
    }

    private static double readSample(ByteBuffer buffer,
                                     SampleFormat sampleFormat,
                                     Geometry geometry,
                                     boolean planarStorage,
                                     int pixelIndex,
                                     int channel) {
        int sampleIndex;
        if (planarStorage) {
            sampleIndex = (channel * geometry.width * geometry.height) + pixelIndex;
        } else {
            sampleIndex = (pixelIndex * geometry.channels) + channel;
        }

        int byteOffset = sampleIndex * sampleFormat.byteSize;
        switch (sampleFormat) {
            case UINT8:
                return buffer.get(byteOffset) & 0xFF;
            case UINT16:
                return buffer.getShort(byteOffset) & 0xFFFF;
            case UINT32:
                return Integer.toUnsignedLong(buffer.getInt(byteOffset));
            case UINT64:
                long rawLong = buffer.getLong(byteOffset);
                return rawLong >= 0 ? rawLong : (rawLong & Long.MAX_VALUE) + Math.pow(2.0, 63.0);
            case FLOAT32:
                return buffer.getFloat(byteOffset);
            case FLOAT64:
                return buffer.getDouble(byteOffset);
            default:
                throw new IllegalStateException("Unhandled XISF sample format: " + sampleFormat);
        }
    }

    private static File[] listFilesWithExtensions(File directory, Set<String> extensions) {
        File[] files = directory.listFiles(file -> file.isFile() && hasExtension(file.getName(), extensions));
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return files;
    }

    private static boolean containsFilesWithExtensions(File directory, Set<String> extensions) {
        File[] files = directory.listFiles(file -> file.isFile() && hasExtension(file.getName(), extensions));
        return files != null && files.length > 0;
    }

    private static boolean hasExtension(String fileName, Set<String> extensions) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return false;
        }
        String extension = fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        return extensions.contains(extension);
    }

    private static String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private enum SampleFormat {
        UINT8(1),
        UINT16(2),
        UINT32(4),
        UINT64(8),
        FLOAT32(4),
        FLOAT64(8);

        private final int byteSize;

        SampleFormat(int byteSize) {
            this.byteSize = byteSize;
        }

        private static SampleFormat parse(String sampleFormatText) throws IOException {
            if (sampleFormatText == null || sampleFormatText.isBlank()) {
                throw new IOException("Missing XISF sampleFormat attribute.");
            }

            switch (sampleFormatText) {
                case "UInt8":
                case "Byte":
                    return UINT8;
                case "UInt16":
                case "UShort":
                    return UINT16;
                case "UInt32":
                case "UInt":
                    return UINT32;
                case "UInt64":
                    return UINT64;
                case "Float32":
                case "Float":
                    return FLOAT32;
                case "Float64":
                case "Double":
                    return FLOAT64;
                default:
                    throw new IOException("Unsupported XISF sample format: " + sampleFormatText);
            }
        }
    }

    private static final class Geometry {
        private final int width;
        private final int height;
        private final int channels;

        private Geometry(int width, int height, int channels) {
            this.width = width;
            this.height = height;
            this.channels = channels;
        }
    }

    private static final class SampleScaling {
        private final boolean scaleToFull16BitRange;
        private final double lowerBound;
        private final double upperBound;

        private SampleScaling(boolean scaleToFull16BitRange, double lowerBound, double upperBound) {
            this.scaleToFull16BitRange = scaleToFull16BitRange;
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
        }

        private static SampleScaling forImage(SampleFormat format, String boundsText) throws IOException {
            if (boundsText != null && !boundsText.isBlank()) {
                String[] parts = boundsText.split(":");
                if (parts.length != 2) {
                    throw new IOException("Unsupported XISF bounds value: " + boundsText);
                }

                double lower = Double.parseDouble(parts[0]);
                double upper = Double.parseDouble(parts[1]);
                if (upper <= lower) {
                    throw new IOException("Invalid XISF bounds value: " + boundsText);
                }
                return new SampleScaling(true, lower, upper);
            }

            if (format == SampleFormat.FLOAT32 || format == SampleFormat.FLOAT64) {
                return new SampleScaling(true, 0.0, 1.0);
            }
            if (format == SampleFormat.UINT8) {
                return new SampleScaling(true, 0.0, 255.0);
            }
            return new SampleScaling(false, 0.0, 65535.0);
        }

        private int toUnsigned16(double sampleValue) {
            double normalizedValue = sampleValue;
            if (scaleToFull16BitRange) {
                normalizedValue = ((sampleValue - lowerBound) / (upperBound - lowerBound)) * 65535.0;
            }

            if (Double.isNaN(normalizedValue) || Double.isInfinite(normalizedValue)) {
                normalizedValue = 0.0;
            }
            if (normalizedValue < 0.0) {
                normalizedValue = 0.0;
            } else if (normalizedValue > 65535.0) {
                normalizedValue = 65535.0;
            }

            return (int) Math.round(normalizedValue);
        }
    }

    private static final class FitsKeyword {
        private final String name;
        private final String value;
        private final String comment;

        private FitsKeyword(String name, String value, String comment) {
            this.name = name;
            this.value = value;
            this.comment = comment;
        }
    }

    private static final class XisfImageData {
        private final short[][] monoData;
        private final List<FitsKeyword> fitsKeywords;

        private XisfImageData(short[][] monoData, List<FitsKeyword> fitsKeywords) {
            this.monoData = monoData;
            this.fitsKeywords = fitsKeywords;
        }
    }
}
