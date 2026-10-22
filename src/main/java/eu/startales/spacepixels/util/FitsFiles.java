package eu.startales.spacepixels.util;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import nom.tam.fits.FitsException;

/** Stable discovery for interactive import, strict headless validation and batch operations. */
final class FitsFiles {
    private FitsFiles() {}

    static File[] list(File directory) throws IOException, FitsException {
        if (!directory.isDirectory()) {
            throw new IOException("file:" + directory.getAbsolutePath() + " is not a directory");
        }

        FitsSequenceValidator.validateDirectoryExtensions(directory);
        File[] ret = directory.listFiles(file -> file.isFile()
                && FitsSequenceValidator.imageExtension(file.getName()) != null
                && !".xisf".equals(FitsSequenceValidator.imageExtension(file.getName())));
        if (ret == null) {
            throw new IOException("Could not list input directory: " + directory);
        }
        if (ret.length == 0) {
            throw new IOException("No fits files in directory:" + directory.getAbsolutePath());
        }

        Arrays.sort(ret, Comparator.comparing(File::getAbsolutePath));
        return ret;
    }
}
