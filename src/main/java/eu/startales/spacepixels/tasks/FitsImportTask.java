/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */

package eu.startales.spacepixels.tasks;

import eu.startales.spacepixels.config.ApplicationSettings;

import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.MixedExposureException;
import eu.startales.spacepixels.util.SessionReports;
import eu.startales.spacepixels.util.XisfImageConverter;

import java.io.File;
import java.util.logging.Logger;

/**
 * Imports a folder of aligned frames. A conversion that writes a new folder, or the choice of one exposure time,
 * continues the import with that folder. The result is the new session processor, which the window then owns, and
 * its frames; the import is declined when no frames were loaded, for example because a conversion was declined.
 */
public class FitsImportTask implements Job<FitsImportTask.Result> {

    /** The imported session: its processor, its frames, and the newest report beside them (or null). */
    public record Result(ImageProcessing processing, FitsFileInformation[] files, File lastReport) {
    }

    private static final Logger LOGGER = Logger.getLogger(FitsImportTask.class.getName());

    private final File directory;
    private final ApplicationSettings settings;

    public FitsImportTask(File directory) {
        this(directory, null);
    }

    public FitsImportTask(File directory, ApplicationSettings settings) {
        this.directory = directory;
        this.settings = settings;
    }

    @Override
    public JobOutcome<Result> run(JobContext context) throws Exception {
        ApplicationSettings preferences = settings == null ? ApplicationSettings.loadDefault() : settings;
        File folder = directory;
        while (true) {
            ImageProcessing imgProcessing = null;
            boolean ownershipTransferred = false;
            try {
                File importDirectory = XisfImageConverter.prepareDirectoryForFitsImport(folder, context::progress);
                if (!folder.equals(importDirectory)) {
                    LOGGER.info("Redirecting import to converted XISF directory: " + importDirectory.getAbsolutePath());
                    context.progress(90, "Loading generated FITS metadata...");
                }

                imgProcessing = ImageProcessing.getInstance(importDirectory, preferences);
                FitsFileInformation[] filesInfo = imgProcessing.getFitsfileInformation();
                context.progress(100, "Import complete.");
                if (filesInfo.length == 0) {
                    return JobOutcome.declined("No frames were imported.");
                }
                File lastReport = SessionReports.latest(new File(filesInfo[0].getFilePath()).getParentFile());
                ownershipTransferred = true;
                return JobOutcome.completed(new Result(imgProcessing, filesInfo, lastReport));

            } catch (ImageProcessing.RedirectImportException e) {
                // The user opted to automatically load the newly converted directory!
                LOGGER.info("Redirecting import to newly created directory: " + e.getNewDirectory().getAbsolutePath());
                folder = e.getNewDirectory();
            } catch (MixedExposureException e) {
                // Only the window can ask; the API and batch runs reject the folder with the exception's message.
                if (!e.canImportLargestGroup()) {
                    throw e;
                }
                File largestGroup = ImageProcessing.offerToImportLargestExposureGroup(e);
                if (largestGroup == null) {
                    return JobOutcome.declined("The import was cancelled.");
                }
                LOGGER.info("Redirecting import to the images of one exposure time: " + largestGroup.getAbsolutePath());
                folder = largestGroup;
            } finally {
                if (!ownershipTransferred && imgProcessing != null) {
                    imgProcessing.close();
                }
            }
        }
    }
}
