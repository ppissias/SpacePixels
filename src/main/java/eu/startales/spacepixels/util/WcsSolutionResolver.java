package eu.startales.spacepixels.util;

import io.github.ppissias.jplatesolve.PlateSolveResult;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;
import nom.tam.util.Cursor;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves a usable WCS solution for an aligned image set.
 * Tries the current file first, then falls back to any other aligned file with a valid solution.
 */
public final class WcsSolutionResolver {

    private WcsSolutionResolver() {
    }

    public static ResolvedWcsSolution resolve(FitsFileInformation preferredFile, FitsFileInformation[] alignedFiles) {
        if (preferredFile != null) {
            ResolvedWcsSolution direct = resolveForFile(preferredFile, false);
            if (direct != null) {
                return direct;
            }
        }

        if (alignedFiles == null) {
            return null;
        }

        for (FitsFileInformation candidate : alignedFiles) {
            if (candidate == null || candidate == preferredFile) {
                continue;
            }
            if (!hasCompatibleDimensions(preferredFile, candidate)) {
                continue;
            }

            ResolvedWcsSolution shared = resolveForFile(candidate, true);
            if (shared != null) {
                return shared;
            }
        }

        return null;
    }

    private static ResolvedWcsSolution resolveForFile(FitsFileInformation fileInfo, boolean sharedAcrossAlignedSet) {
        if (fileInfo == null) {
            return null;
        }

        WcsCoordinateTransformer transformer = WcsCoordinateTransformer.fromHeader(fileInfo.getFitsHeader());
        if (transformer != null) {
            return new ResolvedWcsSolution(transformer, sharedAcrossAlignedSet, fileInfo.getFileName(), "FITS header");
        }

        PlateSolveResult solveResult = fileInfo.getSolveResult();
        if (solveResult != null && solveResult.isSuccess() && solveResult.getSolveInformation() != null) {
            transformer = WcsCoordinateTransformer.fromHeader(solveResult.getSolveInformation());
            if (transformer != null) {
                return new ResolvedWcsSolution(transformer, sharedAcrossAlignedSet, fileInfo.getFileName(), "saved solve metadata");
            }
        }

        transformer = loadSidecarWcsTransformer(fileInfo);
        if (transformer != null) {
            return new ResolvedWcsSolution(transformer, sharedAcrossAlignedSet, fileInfo.getFileName(), "sidecar .wcs");
        }

        return null;
    }

    private static boolean hasCompatibleDimensions(FitsFileInformation preferredFile, FitsFileInformation candidate) {
        if (preferredFile == null || candidate == null) {
            return true;
        }
        return preferredFile.getSizeWidth() == candidate.getSizeWidth()
                && preferredFile.getSizeHeight() == candidate.getSizeHeight();
    }

    private static WcsCoordinateTransformer loadSidecarWcsTransformer(FitsFileInformation fileInfo) {
        File sidecarFile = findSidecarWcsFile(fileInfo.getFilePath());
        if (sidecarFile == null || !sidecarFile.isFile()) {
            return null;
        }

        Map<String, String> headerMap = readHeaderMap(sidecarFile);
        return headerMap != null ? WcsCoordinateTransformer.fromHeader(headerMap) : null;
    }

    private static File findSidecarWcsFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return null;
        }

        int extensionIndex = filePath.lastIndexOf('.');
        String basePath = extensionIndex >= 0 ? filePath.substring(0, extensionIndex) : filePath;

        File lowerCase = new File(basePath + ".wcs");
        if (lowerCase.isFile()) {
            return lowerCase;
        }

        File upperCase = new File(basePath + ".WCS");
        if (upperCase.isFile()) {
            return upperCase;
        }

        return null;
    }

    private static Map<String, String> readHeaderMap(File fitsLikeFile) {
        try (Fits fits = new Fits(fitsLikeFile)) {
            BasicHDU<?> hdu = ImageProcessing.getImageHDU(fits);
            if (hdu == null) {
                return null;
            }

            Header header = hdu.getHeader();
            if (header == null) {
                return null;
            }

            Map<String, String> headerMap = new HashMap<>();
            Cursor<String, HeaderCard> iterator = header.iterator();
            while (iterator.hasNext()) {
                HeaderCard card = iterator.next();
                if (card.getKey() != null && card.getValue() != null) {
                    headerMap.put(card.getKey(), card.getValue());
                }
            }
            return headerMap;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static final class ResolvedWcsSolution {
        private final WcsCoordinateTransformer transformer;
        private final boolean sharedAcrossAlignedSet;
        private final String sourceFileName;
        private final String sourceType;

        private ResolvedWcsSolution(WcsCoordinateTransformer transformer,
                                    boolean sharedAcrossAlignedSet,
                                    String sourceFileName,
                                    String sourceType) {
            this.transformer = transformer;
            this.sharedAcrossAlignedSet = sharedAcrossAlignedSet;
            this.sourceFileName = sourceFileName;
            this.sourceType = sourceType;
        }

        public WcsCoordinateTransformer getTransformer() {
            return transformer;
        }

        public boolean isSharedAcrossAlignedSet() {
            return sharedAcrossAlignedSet;
        }

        public String getSourceFileName() {
            return sourceFileName;
        }

        public String getSourceType() {
            return sourceType;
        }
    }
}
