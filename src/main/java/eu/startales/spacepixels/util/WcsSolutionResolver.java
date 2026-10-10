package eu.startales.spacepixels.util;

import java.io.File;

/**
 * Resolves a usable WCS solution for an aligned image set.
 * Tries the current file first, then falls back to any other aligned file with a valid solution. When the session
 * has a plate-solution correction that is switched on ({@link PlateSolutionCorrection}), it is applied: for the file
 * asked for with its own part, or for the session as a whole when no file is given.
 */
public final class WcsSolutionResolver {

    private WcsSolutionResolver() {
    }

    public static ResolvedWcsSolution resolve(FitsFileInformation preferredFile, FitsFileInformation[] alignedFiles) {
        return resolve(preferredFile, alignedFiles, true);
    }

    /** The solution as the FITS headers give it, without a correction. */
    public static ResolvedWcsSolution resolveUncorrected(FitsFileInformation preferredFile, FitsFileInformation[] alignedFiles) {
        return resolve(preferredFile, alignedFiles, false);
    }

    private static ResolvedWcsSolution resolve(FitsFileInformation preferredFile, FitsFileInformation[] alignedFiles, boolean corrected) {
        ResolvedWcsSolution solution = null;
        if (preferredFile != null) {
            solution = resolveForFile(preferredFile, false);
        }

        if (solution == null && alignedFiles != null) {
            for (FitsFileInformation candidate : alignedFiles) {
                if (candidate == null || candidate == preferredFile) {
                    continue;
                }
                if (!hasCompatibleDimensions(preferredFile, candidate)) {
                    continue;
                }

                solution = resolveForFile(candidate, true);
                if (solution != null) {
                    break;
                }
            }
        }

        if (solution == null || !corrected) {
            return solution;
        }
        FitsFileInformation reference = preferredFile != null ? preferredFile : firstFile(alignedFiles);
        PlateSolutionCorrection correction = reference == null ? null : PlateSolutionCorrection.activeIn(folderOf(reference));
        if (correction == null) {
            return solution;
        }
        WcsCoordinateTransformer transformer = correction.apply(solution.getTransformer(),
                preferredFile != null ? preferredFile.getFileName() : null, reference.getSizeWidth(), reference.getSizeHeight());
        if (transformer == solution.getTransformer()) {
            return solution;
        }
        return new ResolvedWcsSolution(transformer, solution.isSharedAcrossAlignedSet(), solution.getSourceFileName(),
                solution.getSourceType() + ", corrected to the stars");
    }

    private static FitsFileInformation firstFile(FitsFileInformation[] files) {
        if (files != null) {
            for (FitsFileInformation file : files) {
                if (file != null) {
                    return file;
                }
            }
        }
        return null;
    }

    private static File folderOf(FitsFileInformation file) {
        String path = file.getFilePath();
        return path == null ? null : new File(path).getAbsoluteFile().getParentFile();
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
