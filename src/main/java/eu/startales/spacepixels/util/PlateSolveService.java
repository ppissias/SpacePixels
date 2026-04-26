/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.gui.ApplicationWindow;
import io.github.ppissias.jplatesolve.PlateSolveResult;
import io.github.ppissias.jplatesolve.astap.ASTAPInterface;
import io.github.ppissias.jplatesolve.astrometrydotnet.AstrometryDotNet;
import io.github.ppissias.jplatesolve.astrometrydotnet.SubmitFileRequest;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;
import nom.tam.fits.HeaderCardException;
import nom.tam.util.Cursor;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * Handles plate-solving execution together with WCS header import and solve-artifact cleanup.
 */
final class PlateSolveService {

    @FunctionalInterface
    interface FitsWriteHandler {
        void write(File fitsFile, Fits fits) throws IOException, FitsException;
    }

    private static final Set<String> FIXED_WCS_HEADER_KEYS = Set.of(
            "WCSAXES",
            "RADESYS",
            "RADECSYS",
            "LONPOLE",
            "LATPOLE",
            "EQUINOX",
            "EPOCH",
            "WCSNAME",
            "A_ORDER",
            "B_ORDER",
            "AP_ORDER",
            "BP_ORDER",
            "A_DMAX",
            "B_DMAX",
            "AP_DMAX",
            "BP_DMAX");
    private static final String[] APPLY_WCS_HEADER_PREFIXES = {
            "CTYPE", "CUNIT1", "CUNIT2", "WCSAXES", "IMAGEW", "IMAGEH",
            "A_ORDER", "B_ORDER", "AP_ORDER", "BP_ORDER",
            "CRPIX", "CRVAL", "CDELT", "CROTA", "CD1_", "CD2_",
            "EQUINOX", "LONPOLE", "LATPOLE", "A_", "B_", "AP_", "BP_"
    };

    private final AppConfig appConfig;
    private final AstrometryDotNet astrometryNetInterface;

    PlateSolveService(AppConfig appConfig) {
        this(appConfig, new AstrometryDotNet());
    }

    PlateSolveService(AppConfig appConfig, AstrometryDotNet astrometryNetInterface) {
        this.appConfig = appConfig;
        this.astrometryNetInterface = astrometryNetInterface;
    }

    public Future<PlateSolveResult> solve(String fitsFileFullPath, boolean astap, boolean astrometry)
            throws FitsException, IOException {
        ApplicationWindow.logger.info("trying to solve image astap=" + astap + " astrometry=" + astrometry);

        if (astap) {
            String astapPath = appConfig.astapExecutablePath;
            if (astapPath != null && (!"".equals(astapPath))) {
                File astapPathFile = new File(astapPath);
                if (astapPathFile.exists()) {
                    return ASTAPInterface.solveImage(astapPathFile, fitsFileFullPath);
                }
            }
            throw new IOException("ASTAP executable path is not correct:" + astapPath);
        } else if (astrometry) {
            try {
                astrometryNetInterface.login();
                SubmitFileRequest typicalParamsRequest = SubmitFileRequest.builder()
                        .withPublicly_visible("y")
                        .withScale_units("degwidth")
                        .withScale_lower(0.1f)
                        .withScale_upper(180.0f)
                        .withDownsample_factor(2f)
                        .withRadius(10f)
                        .build();
                return astrometryNetInterface.customSolve(new File(fitsFileFullPath), typicalParamsRequest);
            } catch (IOException | InterruptedException e) {
                JOptionPane.showMessageDialog(
                        new JFrame(),
                        "Could not solve image with astrometry.net :" + e.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
        return null;
    }

    public void applyWCSHeader(String wcsHeaderFile, File[] fitsFileInformation, FitsWriteHandler writeHandler)
            throws IOException, FitsException {
        try (Fits wcsHeaderFits = new Fits(wcsHeaderFile)) {
            Header wcsHeader = ImageProcessing.getImageHDU(wcsHeaderFits).getHeader();
            for (File fitsFile : fitsFileInformation) {
                try (Fits fits = new Fits(fitsFile)) {
                    Header targetHeader = ImageProcessing.getImageHDU(fits).getHeader();
                    copyWcsHeaderCards(wcsHeader, targetHeader);
                    writeHandler.write(fitsFile, fits);
                }
            }
        }
    }

    public Map<String, String> updateFitsHeaderWithWCS(String fitsFileFullPath, Map<String, String> wcsData)
            throws Exception {
        if (wcsData == null || wcsData.isEmpty()) {
            throw new IOException("No WCS solution metadata was provided.");
        }

        File originalFile = new File(fitsFileFullPath);
        File tempFile = new File(fitsFileFullPath + ".tmp");
        Map<String, String> updatedHeader;

        try (Fits fits = new Fits(originalFile)) {
            BasicHDU<?> hdu = ImageProcessing.getImageHDU(fits);
            if (hdu == null || hdu.getHeader() == null) {
                throw new IOException("Could not locate an image header in " + originalFile.getName());
            }

            Header header = hdu.getHeader();
            clearExistingWcsHeader(header);
            clearLegacySolveMetadata(header);

            List<HeaderCard> wcsHeaderCards = resolveWcsHeaderCards(wcsData);
            for (HeaderCard card : wcsHeaderCards) {
                header.addLine(card.copy());
            }

            writeSolveProvenance(header, wcsData);
            updatedHeader = extractHeaderMap(header);
            fits.write(tempFile);
        }

        if (originalFile.delete()) {
            if (!tempFile.renameTo(originalFile)) {
                throw new IOException("Failed to rename temporary FITS file back to original.");
            }
        } else {
            throw new IOException("Failed to delete original FITS file to overwrite it.");
        }

        return updatedHeader;
    }

    public void cleanupSolveArtifacts(String fitsFileFullPath, PlateSolveResult result) {
        if (fitsFileFullPath == null || fitsFileFullPath.isEmpty()) {
            return;
        }

        deleteFileQuietly(getLegacySolveResultFile(fitsFileFullPath));
        deleteFileQuietly(getLegacyWcsSidecarFile(fitsFileFullPath, ".wcs"));
        deleteFileQuietly(getLegacyWcsSidecarFile(fitsFileFullPath, ".WCS"));
        deleteFileQuietly(getLegacyAstapIniFile(fitsFileFullPath));

        if (result == null || result.getSolveInformation() == null) {
            return;
        }

        deleteFileQuietly(resolveLocalFileReference(
                normalizeExternalUrl(getSolveMetadataValue(result.getSolveInformation(), "wcs_link"))));
        deleteFileQuietly(resolveLocalFileReference(
                normalizeExternalUrl(getSolveMetadataValue(result.getSolveInformation(), "annotated_image_link"))));
    }

    private static int downloadFile(URL fileURL, String targetFilePath) throws IOException {
        ApplicationWindow.logger.info("downloading : " + fileURL + " to " + targetFilePath);
        File targetfile = new File(targetFilePath);
        if (targetfile.exists()) {
            targetfile.delete();
            ApplicationWindow.logger.info("deleted pre-existing : " + targetFilePath);
        }

        try (BufferedInputStream in = new BufferedInputStream(fileURL.openStream());
             FileOutputStream fileOutputStream = new FileOutputStream(targetFilePath)) {
            byte[] dataBuffer = new byte[1024];
            int bytesRead;
            int totalBytesRead = 0;
            while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
                fileOutputStream.write(dataBuffer, 0, bytesRead);
                totalBytesRead += bytesRead;
            }
            return totalBytesRead;
        }
    }

    private void copyWcsHeaderCards(Header sourceHeader, Header targetHeader) {
        Cursor<String, HeaderCard> iterator = sourceHeader.iterator();
        while (iterator.hasNext()) {
            HeaderCard sourceCard = iterator.next();
            String key = sourceCard.getKey();
            if (!shouldCopyWcsHeaderKey(key)) {
                continue;
            }
            if (targetHeader.containsKey(key)) {
                targetHeader.deleteKey(key);
            }
            targetHeader.addLine(sourceCard.copy());
        }
    }

    private boolean shouldCopyWcsHeaderKey(String key) {
        if (key == null) {
            return false;
        }
        for (String prefix : APPLY_WCS_HEADER_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private List<HeaderCard> resolveWcsHeaderCards(Map<String, String> wcsData) throws Exception {
        String wcsLink = normalizeExternalUrl(getSolveMetadataValue(wcsData, "wcs_link"));
        if (wcsLink != null && !wcsLink.isEmpty()) {
            List<HeaderCard> cards = loadWcsHeaderCardsFromReference(wcsLink);
            if (!cards.isEmpty()) {
                return cards;
            }
        }

        List<HeaderCard> cards = buildWcsHeaderCardsFromMetadata(wcsData);
        if (!cards.isEmpty()) {
            return cards;
        }

        throw new IOException("The solve result did not provide a usable WCS header.");
    }

    private List<HeaderCard> loadWcsHeaderCardsFromReference(String wcsReference) throws Exception {
        File localWcsFile = resolveLocalFileReference(wcsReference);
        if (localWcsFile != null) {
            return readWcsHeaderCards(localWcsFile);
        }

        File temporaryWcsFile = Files.createTempFile("spacepixels-wcs-", ".fits").toFile();
        try {
            downloadFile(new URL(wcsReference), temporaryWcsFile.getAbsolutePath());
            return readWcsHeaderCards(temporaryWcsFile);
        } catch (Exception error) {
            throw new IOException("Could not import WCS header from " + wcsReference + ": " + error.getMessage(), error);
        } finally {
            Files.deleteIfExists(temporaryWcsFile.toPath());
        }
    }

    private File resolveLocalFileReference(String reference) {
        if (reference == null || reference.isEmpty()) {
            return null;
        }

        try {
            URL url = new URL(reference);
            if ("file".equalsIgnoreCase(url.getProtocol())) {
                return new File(url.toURI());
            }
            return null;
        } catch (Exception ignored) {
            File file = new File(reference);
            return file.isAbsolute() || file.exists() ? file : null;
        }
    }

    private List<HeaderCard> readWcsHeaderCards(File wcsFitsFile) throws Exception {
        try (Fits wcsFits = new Fits(wcsFitsFile)) {
            BasicHDU<?> hdu = ImageProcessing.getImageHDU(wcsFits);
            if (hdu == null || hdu.getHeader() == null) {
                return Collections.emptyList();
            }

            List<HeaderCard> cards = new ArrayList<>();
            Cursor<String, HeaderCard> iterator = hdu.getHeader().iterator();
            while (iterator.hasNext()) {
                HeaderCard card = iterator.next();
                if (isWcsHeaderKey(card.getKey())) {
                    cards.add(card.copy());
                }
            }
            return cards;
        }
    }

    private List<HeaderCard> buildWcsHeaderCardsFromMetadata(Map<String, String> metadata)
            throws HeaderCardException {
        if (metadata == null || metadata.isEmpty()) {
            return Collections.emptyList();
        }

        Header temporaryHeader = new Header();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }

            String key = entry.getKey().toUpperCase(Locale.US);
            if (!isWcsHeaderKey(key)) {
                continue;
            }

            writeHeaderValue(temporaryHeader, key, entry.getValue());
        }

        List<HeaderCard> cards = new ArrayList<>();
        Cursor<String, HeaderCard> iterator = temporaryHeader.iterator();
        while (iterator.hasNext()) {
            HeaderCard card = iterator.next();
            if (isWcsHeaderKey(card.getKey())) {
                cards.add(card.copy());
            }
        }
        return cards;
    }

    private void clearExistingWcsHeader(Header header) {
        if (header == null) {
            return;
        }

        List<String> keysToDelete = new ArrayList<>();
        Cursor<String, HeaderCard> iterator = header.iterator();
        while (iterator.hasNext()) {
            HeaderCard card = iterator.next();
            if (isWcsHeaderKey(card.getKey())) {
                keysToDelete.add(card.getKey());
            }
        }

        for (String key : keysToDelete) {
            if (header.containsKey(key)) {
                header.deleteKey(key);
            }
        }
    }

    private void clearLegacySolveMetadata(Header header) {
        if (header == null) {
            return;
        }

        for (String key : new String[]{"RA", "DEC", "PIXSCALE", "PARITY", "RADIUS", "SOURCE", "WCS_LINK", "PLTSOLVD", "WARNING", "ERROR"}) {
            if (header.containsKey(key)) {
                header.deleteKey(key);
            }
        }
    }

    private boolean isWcsHeaderKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }

        String upperKey = key.toUpperCase(Locale.US);
        return FIXED_WCS_HEADER_KEYS.contains(upperKey)
                || upperKey.matches("CTYPE\\d+")
                || upperKey.matches("CRPIX\\d+")
                || upperKey.matches("CRVAL\\d+")
                || upperKey.matches("CUNIT\\d+")
                || upperKey.matches("CDELT\\d+")
                || upperKey.matches("CROTA\\d+")
                || upperKey.matches("CD\\d+_\\d+")
                || upperKey.matches("PC\\d+_\\d+")
                || upperKey.matches("PV\\d+_\\d+")
                || upperKey.matches("PS\\d+_\\d+")
                || upperKey.matches("A_\\d+_\\d+")
                || upperKey.matches("B_\\d+_\\d+")
                || upperKey.matches("AP_\\d+_\\d+")
                || upperKey.matches("BP_\\d+_\\d+");
    }

    private String getSolveMetadataValue(Map<String, String> metadata, String key) {
        if (metadata == null || metadata.isEmpty() || key == null || key.isEmpty()) {
            return null;
        }

        String direct = metadata.get(key);
        if (direct != null) {
            return direct;
        }

        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private String normalizeExternalUrl(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        if (normalized.startsWith("'") && normalized.endsWith("'") && normalized.length() > 1) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }

        normalized = normalized.replace("\\:", ":").replace("\\=", "=");
        return normalized.isEmpty() ? null : normalized;
    }

    private void writeSolveProvenance(Header header, Map<String, String> wcsData) throws HeaderCardException {
        String source = getSolveMetadataValue(wcsData, "source");
        if (source != null && !source.isEmpty()) {
            writeHeaderValue(header, "SOURCE", source);
        }

        String wcsLink = normalizeExternalUrl(getSolveMetadataValue(wcsData, "wcs_link"));
        if (wcsLink != null && isRemoteReference(wcsLink)) {
            writeHeaderValue(header, "WCS_LINK", wcsLink);
        }
    }

    private boolean isRemoteReference(String reference) {
        if (reference == null || reference.isEmpty()) {
            return false;
        }

        try {
            URL url = new URL(reference);
            return !"file".equalsIgnoreCase(url.getProtocol());
        } catch (Exception ignored) {
            return false;
        }
    }

    private Map<String, String> extractHeaderMap(Header header) {
        Map<String, String> headerMap = new HashMap<>();
        if (header == null) {
            return headerMap;
        }

        Cursor<String, HeaderCard> iterator = header.iterator();
        while (iterator.hasNext()) {
            HeaderCard card = iterator.next();
            if (card.getKey() != null && card.getValue() != null) {
                headerMap.put(card.getKey(), card.getValue());
            }
        }
        return headerMap;
    }

    private File getLegacySolveResultFile(String fitsFileFullPath) {
        int extensionIndex = fitsFileFullPath.lastIndexOf('.');
        String basePath = extensionIndex >= 0 ? fitsFileFullPath.substring(0, extensionIndex) : fitsFileFullPath;
        return new File(basePath + "_result.ini");
    }

    private File getLegacyWcsSidecarFile(String fitsFileFullPath, String extension) {
        int extensionIndex = fitsFileFullPath.lastIndexOf('.');
        String basePath = extensionIndex >= 0 ? fitsFileFullPath.substring(0, extensionIndex) : fitsFileFullPath;
        return new File(basePath + extension);
    }

    private File getLegacyAstapIniFile(String fitsFileFullPath) {
        int extensionIndex = fitsFileFullPath.lastIndexOf('.');
        String basePath = extensionIndex >= 0 ? fitsFileFullPath.substring(0, extensionIndex) : fitsFileFullPath;
        return new File(basePath + ".ini");
    }

    private void deleteFileQuietly(File file) {
        if (file == null || !file.isFile()) {
            return;
        }

        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException ignored) {
        }
    }

    private void writeHeaderValue(Header header, String key, String value) throws HeaderCardException {
        if (header == null || key == null || key.isEmpty() || value == null) {
            return;
        }

        if (header.containsKey(key)) {
            header.deleteKey(key);
        }

        try {
            if (value.contains(".")) {
                header.addValue(key, Double.parseDouble(value), "SpacePixels WCS");
            } else {
                header.addValue(key, Integer.parseInt(value), "SpacePixels WCS");
            }
        } catch (NumberFormatException e) {
            String cleanValue = value;
            if (cleanValue.startsWith("'") && cleanValue.endsWith("'")) {
                cleanValue = cleanValue.substring(1, cleanValue.length() - 1).trim();
            }
            header.addValue(key, cleanValue, "SpacePixels WCS");
        }
    }
}
