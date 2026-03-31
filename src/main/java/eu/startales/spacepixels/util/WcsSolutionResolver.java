package eu.startales.spacepixels.util;

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

        return null;
    }

    private static boolean hasCompatibleDimensions(FitsFileInformation preferredFile, FitsFileInformation candidate) {
        if (preferredFile == null || candidate == null) {
            return true;
        }
        return preferredFile.getSizeWidth() == candidate.getSizeWidth()
                && preferredFile.getSizeHeight() == candidate.getSizeHeight();
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
