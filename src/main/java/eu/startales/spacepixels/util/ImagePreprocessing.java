/*
 * SpacePixels
 *
 * Copyright (c)2020-2023, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util;

import io.github.ppissias.astrolib.AstrometryDotNet;
import io.github.ppissias.astrolib.PlateSolveResult;
import io.github.ppissias.astrolib.SubmitFileRequest;
import nom.tam.fits.*;
import nom.tam.util.Cursor;
import org.apache.commons.configuration2.FileBasedConfiguration;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.apache.commons.configuration2.ex.ConfigurationException;
import eu.startales.spacepixels.gui.ApplicationWindow;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

public class ImagePreprocessing {

    private final File alignedFitsFolderFullPath;

    // --- NEW: Multi-threading Executor! ---
    // Uses a cached pool to dynamically spin up threads based on available CPU cores
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final AstrometryDotNet astrometryNetInterface = new AstrometryDotNet();
    private final SPConfigurationFile configurationFile;

    public static synchronized ImagePreprocessing getInstance(File alignedFitsFolderFullPath) throws IOException, FitsException, ConfigurationException {
        String userhome = System.getProperty("user.home");
        if (userhome == null) userhome = "";

        ApplicationWindow.logger.info("Will use config file:" + new File(userhome + "/spacepixelviewer.config").getAbsolutePath());
        SPConfigurationFile configurationFile = new SPConfigurationFile(userhome + "/spacepixelviewer.config");

        return new ImagePreprocessing(alignedFitsFolderFullPath, configurationFile);
    }

    private ImagePreprocessing(File alignedFitsFolderFullPath, SPConfigurationFile configFile) throws IOException, FitsException {
        this.alignedFitsFolderFullPath = alignedFitsFolderFullPath;
        this.configurationFile = configFile;
    }

    // =========================================================================
    // NEW BATCH PROCESSING METHODS (Called by UI)
    // =========================================================================

    /**
     * Converts all images in the folder to monochrome.
     * If stretch is true, it also stretches the newly created monochrome images.
     */
    public void batchConvertToMono(boolean stretch, int stretchFactor, int iterations, StretchAlgorithm algo) throws IOException, FitsException {
        File[] fitsFileInformation = getFitsFilesDetails();

        for (File fileInfo : fitsFileInformation) {
            Fits originalFits = new Fits(fileInfo);

            // Check if it's already mono to save time
            int naxis = originalFits.getHDU(0).getHeader().getIntValue("NAXIS");
            if (naxis == 3) { // It's a color image
                ApplicationWindow.logger.info("Converting color image to mono: " + fileInfo.getName());
                Fits monochromeFits = convertToMono(originalFits);

                // Save standard mono
                String monoFilename = addDirectory(fileInfo, "_mono");
                writeFitsWithSuffix(monochromeFits, monoFilename, "_mono");

                // If stretch is enabled, stretch the mono version and save it
                if (stretch) {
                    ApplicationWindow.logger.info("Stretching newly converted mono image: " + fileInfo.getName());
                    stretchFITSImage(monochromeFits, stretchFactor, iterations, algo);
                    String monoStretchFilename = addDirectory(fileInfo, "_mono_stretched");
                    writeFitsWithSuffix(monochromeFits, monoStretchFilename, "_mono_stretch");
                }
            } else {
                ApplicationWindow.logger.info("Skipping mono conversion (already mono): " + fileInfo.getName());
            }
            originalFits.close();
        }
    }

    /**
     * Stretches all images in the folder, regardless of whether they are color or mono.
     */
    public void batchStretch(int stretchFactor, int iterations, StretchAlgorithm algo) throws IOException, FitsException {
        File[] fitsFileInformation = getFitsFilesDetails();

        for (File fileInfo : fitsFileInformation) {
            Fits originalFits = new Fits(fileInfo);

            ApplicationWindow.logger.info("Stretching image: " + fileInfo.getName());

            // Stretch the file in memory
            stretchFITSImage(originalFits, stretchFactor, iterations, algo);

            // Save the stretched version
            String stretchFilename = addDirectory(fileInfo, "_stretched");
            writeFitsWithSuffix(originalFits, stretchFilename, "_stretch");

            originalFits.close();
        }
    }


    // =========================================================================
    // THE MULTI-THREADED DETECTION PIPELINE
    // =========================================================================

    /**
     * A helper class to hold the output of a single thread's extraction work.
     */
    private static class FrameExtractionResult {
        public int frameIndex;
        public short[][] rawImageData;
        public List<SourceExtractor.DetectedObject> extractedObjects;
        public FrameQualityAnalyzer.FrameMetrics metrics;
    }

    public void detectObjects() throws IOException {
        long startTime = System.currentTimeMillis();

        // 0. Initialize the Ledger
        PipelineTelemetry telemetry = new PipelineTelemetry();

        File[] fitsFileInformation = getFitsFilesDetails();
        int numFrames = fitsFileInformation.length;
        telemetry.totalFramesLoaded = numFrames;

        System.out.println("\n--- PHASE 1: Loading & Source Extraction (MULTI-THREADED) ---");
        System.out.println("Spinning up parallel extraction for " + numFrames + " frames...");

        List<Callable<FrameExtractionResult>> tasks = new ArrayList<>();

        for (int i = 0; i < numFrames; i++) {
            final int index = i;
            final File currentFile = fitsFileInformation[i];

            tasks.add(() -> {
                System.out.println("  [Thread] Processing Frame " + (index + 1) + "...");
                Fits fitsFile = new Fits(currentFile);
                Object kernel = fitsFile.getHDU(0).getKernel();

                if (!(kernel instanceof short[][])) {
                    fitsFile.close();
                    throw new IOException("Cannot process: Expected short[][] but found " + kernel.getClass().toString());
                }

                short[][] imageData = (short[][]) kernel;

                List<SourceExtractor.DetectedObject> objectsInFrame = SourceExtractor.extractSources(
                        imageData,
                        SourceExtractor.detectionSigmaMultiplier,
                        SourceExtractor.minDetectionPixels
                );

                String fileName = currentFile.getName();
                for (SourceExtractor.DetectedObject obj : objectsInFrame) {
                    obj.sourceFrameIndex = index;
                    obj.sourceFilename = fileName;
                }

                FrameQualityAnalyzer.FrameMetrics metrics = FrameQualityAnalyzer.evaluateFrame(imageData);
                metrics.medianEccentricity = FrameQualityAnalyzer.calculateFrameEccentricity(objectsInFrame);

                FrameExtractionResult result = new FrameExtractionResult();
                result.frameIndex = index;
                result.rawImageData = imageData;
                result.extractedObjects = objectsInFrame;
                result.metrics = metrics;

                // Store filename for telemetry later
                result.metrics.filename = fileName;

                fitsFile.close();
                return result;
            });
        }

        List<FrameExtractionResult> completedResults = new ArrayList<>();
        try {
            List<Future<FrameExtractionResult>> futures = executor.invokeAll(tasks);
            for (Future<FrameExtractionResult> future : futures) {
                completedResults.add(future.get());
            }
        } catch (Exception e) {
            throw new IOException("Multi-threaded extraction failed: " + e.getMessage(), e);
        }

        System.out.println("\n[Parallel Extraction Complete] Reassembling data in chronological order...");
        completedResults.sort(Comparator.comparingInt(r -> r.frameIndex));

        List<short[][]> rawFrames = new ArrayList<>();
        List<List<SourceExtractor.DetectedObject>> rawExtractedFrames = new ArrayList<>();
        List<FrameQualityAnalyzer.FrameMetrics> sessionMetrics = new ArrayList<>();

        for (FrameExtractionResult result : completedResults) {
            rawFrames.add(result.rawImageData);
            rawExtractedFrames.add(result.extractedObjects);
            sessionMetrics.add(result.metrics);

            // --- TELEMETRY UPDATE: Phase 1 ---
            telemetry.totalRawObjectsExtracted += result.extractedObjects.size();
            PipelineTelemetry.FrameExtractionStat stat = new PipelineTelemetry.FrameExtractionStat();
            stat.frameIndex = result.frameIndex;
            stat.filename = result.metrics.filename;
            stat.objectCount = result.extractedObjects.size();
            telemetry.frameExtractionStats.add(stat);
        }

        System.out.println("\n--- PHASE 2: Analyzing Session & Rejecting Outliers ---");
        SessionEvaluator.rejectOutlierFrames(sessionMetrics);

        System.out.println("\n--- PHASE 3: Filtering Bad Frames ---");
        List<List<SourceExtractor.DetectedObject>> allFramesData = new ArrayList<>();

        for (int i = 0; i < rawExtractedFrames.size(); i++) {
            FrameQualityAnalyzer.FrameMetrics metrics = sessionMetrics.get(i);
            if (metrics.isRejected) {
                System.out.println("⚠️ Skipping Frame " + (i + 1) + ": " + metrics.rejectionReason);

                // --- TELEMETRY UPDATE: Phase 3 (Rejections) ---
                telemetry.totalFramesRejected++;
                PipelineTelemetry.FrameRejectionStat rejStat = new PipelineTelemetry.FrameRejectionStat();
                rejStat.frameIndex = i;
                rejStat.filename = metrics.filename;
                rejStat.reason = metrics.rejectionReason;
                telemetry.rejectedFrames.add(rejStat);

            } else {
                telemetry.totalFramesKept++;
                allFramesData.add(rawExtractedFrames.get(i));
            }
        }

        System.out.println("\n--- PHASE 4: Track Linking ---");
        TrackLinker.TrackingResult trackResult = TrackLinker.findMovingObjects(
                allFramesData,
                TrackLinker.maxStarJitter,
                TrackLinker.predictionTolerance,
                TrackLinker.angleToleranceRad
        );

        // --- TELEMETRY UPDATE: Phase 4 (Tracking) ---
        telemetry.totalMovingTargetsFound = trackResult.tracks.size();

        // *Note: If TrackLinker.TrackingResult holds the star map size, you can add it here:*
        // telemetry.totalStationaryStarsIdentified = trackResult.telemetry.get("total_stars");

        System.out.println("Success! Found " + trackResult.tracks.size() + " moving targets/streaks.");

        System.out.println("\n--- PHASE 5: Exporting Visualizations ---");

        // Calculate total time right before exporting
        telemetry.processingTimeMs = System.currentTimeMillis() - startTime;

        if (fitsFileInformation.length > 0) {
            File exportDir = createDetectionsDirectory(fitsFileInformation[0]);
            telemetry.exportDirectoryPath = exportDir.getAbsolutePath();

            try {
                if (!trackResult.tracks.isEmpty()) {
                    System.out.println("Preparing to export to: " + exportDir.getAbsolutePath());
                } else {
                    System.out.println("No targets found, but generating pipeline report at: " + exportDir.getAbsolutePath());
                }

                // --- NEW: Pass the telemetry object into your exporter ---
                ImageDisplayUtils.exportTrackVisualizations(
                        trackResult.tracks,
                        trackResult.telemetry,
                        rawFrames,
                        exportDir,
                        telemetry);

            } catch (IOException e) {
                System.err.println("Failed to export visualizations: " + e.getMessage());
            }
        }
    }

    /**
     * Returns the FITS file information using multi-threaded, header-only extraction for extreme speed.
     */
    public FitsFileInformation[] getFitsfileInformation() throws IOException, FitsException, ConfigurationException {
        File[] fitsFileInformation = getFitsFilesDetails();
        int numFiles = fitsFileInformation.length;

        System.out.println("\n--- Fast-Loading Information for " + numFiles + " FITS files ---");

        List<Callable<FitsFileInformation>> tasks = new ArrayList<>();

        for (int i = 0; i < numFiles; i++) {
            final int index = i;
            final File currentFile = fitsFileInformation[i];

            tasks.add(() -> {
                Fits fitsFile = null;
                try {
                    fitsFile = new Fits(currentFile);
                    BasicHDU<?> hdu = fitsFile.getHDU(0);
                    Header fitsHeader = hdu.getHeader();

                    String fpath = currentFile.getAbsolutePath();
                    boolean monochromeImage;
                    int width;
                    int height;

                    // --- THE FIX: HEADER-ONLY DIMENSION EXTRACTION ---
                    // By getting the axes directly from the HDU, we completely avoid
                    // calling getKernel() and loading gigabytes of raw pixel data into memory!
                    int[] axes = hdu.getAxes();

                    if (axes.length == 2) {
                        monochromeImage = true;
                        height = axes[0];
                        width = axes[1];
                    } else if (axes.length == 3) {
                        monochromeImage = false;
                        // In a 3D Java array [depth][height][width], dimensions are at index 1 and 2
                        height = axes[1];
                        width = axes[2];
                    } else {
                        throw new FitsException("Cannot understand file, it has axes length=" + axes.length);
                    }

                    FitsFileInformation fileInfo = new FitsFileInformation(fpath, currentFile.getName(), monochromeImage, width, height);

                    // Read the header cards
                    Cursor<String, HeaderCard> iter = fitsHeader.iterator();
                    while (iter.hasNext()) {
                        HeaderCard fitsHeaderCard = iter.next();
                        fileInfo.getFitsHeader().put(fitsHeaderCard.getKey(), fitsHeaderCard.getValue());
                    }

                    // Check for existing plate solve configurations
                    PlateSolveResult previousSolveresult = readSolveResults(fpath);
                    if (previousSolveresult != null) {
                        fileInfo.setSolveResult(previousSolveresult);
                    }

                    return fileInfo;

                } finally {
                    // Always ensure the file stream is closed, even if an exception occurs
                    if (fitsFile != null) {
                        fitsFile.close();
                    }
                }
            });
        }

        FitsFileInformation[] ret = new FitsFileInformation[numFiles];

        try {
            // Execute all tasks concurrently
            List<Future<FitsFileInformation>> futures = executor.invokeAll(tasks);

            for (int i = 0; i < futures.size(); i++) {
                // Since the tasks list and futures list maintain their original order,
                // we can map them straight back into the array without needing to re-sort!
                ret[i] = futures.get(i).get();
            }
        } catch (Exception e) {
            throw new IOException("Multi-threaded file loading failed: " + e.getMessage(), e);
        }

        System.out.println("Finished loading metadata for " + numFiles + " files instantly.");
        return ret;
    }
    // =========================================================================
    // EXISTING HELPER METHODS (Unchanged)
    // =========================================================================

    public FitsFileInformation[] getFitsfileInformationOld() throws IOException, FitsException, ConfigurationException {
        File[] fitsFileInformation = getFitsFilesDetails();
        Fits[] fitsFiles = new Fits[fitsFileInformation.length];
        FitsFileInformation[] ret = new FitsFileInformation[fitsFileInformation.length];

        for (int i = 0; i < fitsFiles.length; i++) {
            fitsFiles[i] = new Fits(fitsFileInformation[i]);
            Header fitsHeader = fitsFiles[i].getHDU(0).getHeader();
            Cursor<String, HeaderCard> iter = fitsHeader.iterator();

            String fpath = fitsFileInformation[i].getAbsolutePath();
            boolean monochromeImage;
            int width;
            int height;

            int[] axes = fitsFiles[i].getHDU(0).getAxes();

            if (axes.length == 2) {
                monochromeImage = true;
                Object kernelData = fitsFiles[i].getHDU(0).getKernel();
                if (kernelData instanceof short[][]) {
                    short[][] data = (short[][]) fitsFiles[i].getHDU(0).getKernel();
                    height = data.length;
                    width = data[0].length;
                } else if (kernelData instanceof int[][]) {
                    int[][] data = (int[][]) fitsFiles[i].getHDU(0).getKernel();
                    height = data.length;
                    width = data[0].length;
                } else if (kernelData instanceof float[][]) {
                    float[][] data = (float[][]) fitsFiles[i].getHDU(0).getKernel();
                    height = data.length;
                    width = data[0].length;
                } else {
                    throw new FitsException("Cannot understand file, it has a type=" + kernelData.getClass().getName());
                }
            } else if (axes.length == 3) {
                monochromeImage = false;
                Object kernelData = fitsFiles[i].getHDU(0).getKernel();
                if (kernelData instanceof short[][][]) {
                    short[][][] data = (short[][][]) fitsFiles[i].getHDU(0).getKernel();
                    height = data[0].length;
                    width = data[0][0].length;
                } else if (kernelData instanceof int[][][]) {
                    int[][][] data = (int[][][]) fitsFiles[i].getHDU(0).getKernel();
                    height = data[0].length;
                    width = data[0][0].length;
                } else if (kernelData instanceof float[][][]) {
                    float[][][] data = (float[][][]) fitsFiles[i].getHDU(0).getKernel();
                    height = data[0].length;
                    width = data[0][0].length;
                } else {
                    throw new FitsException("Cannot understand file, it has a type=" + kernelData.getClass().getName());
                }
            } else {
                throw new FitsException("Cannot understand file, it has axes length=" + axes.length);
            }

            ret[i] = new FitsFileInformation(fpath, fitsFileInformation[i].getName(), monochromeImage, width, height);

            while (iter.hasNext()) {
                HeaderCard fitsHeaderCard = iter.next();
                ret[i].getFitsHeader().put(fitsHeaderCard.getKey(), fitsHeaderCard.getValue());
            }

            PlateSolveResult previousSolveresult = readSolveResults(fpath);
            if (previousSolveresult != null) {
                ret[i].setSolveResult(previousSolveresult);
            }
        }
        closeFitsFiles(fitsFiles);
        return ret;
    }

    private File[] getFitsFilesDetails() throws IOException, FitsException {
        File directory = alignedFitsFolderFullPath;
        if (!directory.isDirectory()) {
            throw new IOException("file:" + directory.getAbsolutePath() + " is not a directory");
        }

        List<File> fitsFilesPath = new ArrayList<File>();
        for (File f : directory.listFiles((dir, name) -> {
            String[] acceptedFileTypes = {"fits", "fit", "fts", "Fits", "Fit", "FIT", "FTS", "Fts", "FITS"};
            for (String acceptedFileEnd : acceptedFileTypes) {
                if (name.endsWith(acceptedFileEnd)) {
                    return true;
                }
            }
            return false;
        })) {
            fitsFilesPath.add(f);
        }

        if (fitsFilesPath.isEmpty()) {
            throw new IOException("No fits files in directory:" + directory.getAbsolutePath());
        }

        File[] ret = fitsFilesPath.toArray(new File[]{});
        Arrays.sort(ret, Comparator.comparing(File::getAbsolutePath));
        return ret;
    }

    private static void closeFitsFiles(Fits[] fitsFiles) throws IOException {
        for (Fits fitsFile : fitsFiles) {
            fitsFile.close();
        }
    }

    public Future<PlateSolveResult> solve(String fitsFileFullPath, boolean astap, boolean astrometry) throws FitsException, IOException {
        ApplicationWindow.logger.info("trying to solve image astap=" + astap + " astrometry=" + astrometry);

        if (astap) {
            String astapPath = configurationFile.getProperty("astap");
            if (astapPath != null && (!"".equals(astapPath))) {
                File astapPathFile = new File(astapPath);
                if (astapPathFile.exists()) {
                    FutureTask<PlateSolveResult> task = ASTAPInterface.solveImage(astapPathFile, fitsFileFullPath);
                    executor.execute(task);
                    return task;
                }
            }
            throw new IOException("ASTAP executable path is not correct:" + astapPath);
        } else if (astrometry) {
            try {
                astrometryNetInterface.login();
                SubmitFileRequest typicalParamsRequest = SubmitFileRequest.builder().withPublicly_visible("y").withScale_units("degwidth").withScale_lower(0.1f).withScale_upper(180.0f).withDownsample_factor(2f).withRadius(10f).build();
                return astrometryNetInterface.customSolve(new File(fitsFileFullPath), typicalParamsRequest);
            } catch (IOException | InterruptedException e) {
                JOptionPane.showMessageDialog(new JFrame(), "Could not solve image with astrometry.net :" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
        return null;
    }

    public void applyWCSHeader(String wcsHeaderFile, int stretchFactor, int iterations, boolean stretch, StretchAlgorithm algo) throws IOException, FitsException {
        File[] fitsFileInformation = getFitsFilesDetails();
        Fits[] fitsFiles = new Fits[fitsFileInformation.length];
        for (int i = 0; i < fitsFiles.length; i++) {
            fitsFiles[i] = new Fits(fitsFileInformation[i]);
        }

        Fits wcsHeaderFITS = new Fits(wcsHeaderFile);
        Header wcsHeaderFITSHeader = wcsHeaderFITS.getHDU(0).getHeader();
        String[] wcsHeaderElements = {"CTYPE", "CUNIT1", "CUNIT2", "WCSAXES", "IMAGEW", "IMAGEH", "A_ORDER", "B_ORDER", "AP_ORDER", "BP_ORDER", "CRPIX", "CRVAL", "CDELT", "CROTA", "CD1_", "CD2_", "EQUINOX", "LONPOLE", "LATPOLE", "A_", "B_", "AP_", "BP_"};

        for (int i = 0; i < fitsFiles.length; i++) {
            Header headerHDU = fitsFiles[i].getHDU(0).getHeader();
            Cursor<String, HeaderCard> wcsHeaderFITSHeaderIter = wcsHeaderFITSHeader.iterator();

            while (wcsHeaderFITSHeaderIter.hasNext()) {
                HeaderCard wcsHeaderFITSHeaderCard = wcsHeaderFITSHeaderIter.next();
                String wcsHeaderKey = wcsHeaderFITSHeaderCard.getKey();
                for (String wcsKeyword : wcsHeaderElements) {
                    if (wcsHeaderKey.startsWith(wcsKeyword)) {
                        headerHDU.deleteKey(wcsHeaderKey);
                        headerHDU.addLine(wcsHeaderFITSHeaderCard);
                        break;
                    }
                }
            }
            writeUpdatedFITSFile(fitsFileInformation[i], fitsFiles[i], stretchFactor, iterations, stretch, algo);
        }
        wcsHeaderFITS.close();
        closeFitsFiles(fitsFiles);
    }

    public void onlyStretch(int stretchFactor, int iterations, StretchAlgorithm algo) throws IOException, FitsException {
        File[] fitsFileInformation = getFitsFilesDetails();
        for (int i = 0; i < fitsFileInformation.length; i++) {
            Fits fitsFile = new Fits(fitsFileInformation[i]);
            writeOnlyStretchedFitsFile(fitsFileInformation[i], fitsFile, stretchFactor, iterations, algo);
        }
    }

    public static int downloadFile(URL fileURL, String targetFilePath) throws IOException {
        ApplicationWindow.logger.info("downloading : " + fileURL.toString() + " to " + targetFilePath);
        File targetfile = new File(targetFilePath);
        if (targetfile.exists()) {
            targetfile.delete();
            ApplicationWindow.logger.info("deleted pre-existing : " + targetFilePath);
        }

        BufferedInputStream in = new BufferedInputStream(fileURL.openStream());
        FileOutputStream fileOutputStream = new FileOutputStream(targetFilePath);
        byte dataBuffer[] = new byte[1024];
        int bytesRead;
        int totalBytesRead = 0;
        while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
            fileOutputStream.write(dataBuffer, 0, bytesRead);
            totalBytesRead += bytesRead;
        }
        fileOutputStream.close();
        return totalBytesRead;
    }

    public void writeSolveResults(String fitsFileFullPath, PlateSolveResult result) throws IOException, ConfigurationException {
        String solveResultFilename = fitsFileFullPath.substring(0, fitsFileFullPath.lastIndexOf(".")) + "_result.ini";
        File solveResultFile = new File(solveResultFilename);

        if (solveResultFile.exists()) {
            solveResultFile.delete();
            solveResultFile = new File(solveResultFilename);
        }

        solveResultFile.createNewFile();
        Configurations configs = new Configurations();
        FileBasedConfigurationBuilder<PropertiesConfiguration> configBuilder = configs.propertiesBuilder(solveResultFile);
        FileBasedConfiguration config = configBuilder.getConfiguration();

        config.setProperty("success", result.isSuccess());
        config.setProperty("failure_reason", result.getFailureReason());
        config.setProperty("warning", result.getWarning());

        Map<String, String> solveInformation = result.getSolveInformation();
        if (solveInformation != null) {
            for (String key : solveInformation.keySet()) {
                config.setProperty(key, solveInformation.get(key));
            }
        }
        configBuilder.save();
    }

    public PlateSolveResult readSolveResults(String fitsFileFullPath) throws ConfigurationException {
        PlateSolveResult ret = null;
        String solveResultFilename = fitsFileFullPath.substring(0, fitsFileFullPath.lastIndexOf(".")) + "_result.ini";
        File solveResultFile = new File(solveResultFilename);

        if (solveResultFile.exists()) {
            Configurations configs = new Configurations();
            FileBasedConfigurationBuilder<PropertiesConfiguration> configBuilder = configs.propertiesBuilder(solveResultFile);
            FileBasedConfiguration config = configBuilder.getConfiguration();

            boolean success = config.getBoolean("success");
            String failure_reason = config.getString("failure_reason");
            String warning = config.getString("warning");

            Map<String, String> solveInfo = new HashMap<String, String>();
            ret = new PlateSolveResult(success, failure_reason, warning, solveInfo);

            Iterator<String> keysIterator = config.getKeys();
            while (keysIterator.hasNext()) {
                String key = keysIterator.next();
                if (!key.equals("success") && !key.equals("failure_reason") && !key.equals("warning")) {
                    solveInfo.put(key, config.getString(key));
                }
            }
        }
        return ret;
    }

    public void setProperty(String key, String value) throws ConfigurationException {
        configurationFile.setProperty(key, value);
        configurationFile.save();
    }

    public String getProperty(String key) {
        return configurationFile.getProperty(key);
    }

    private void writeUpdatedFITSFile(File fileInformation, Fits originalFits, int stretchFactor, int iterations, boolean stretch, StretchAlgorithm algo) throws FitsException, IOException {
        int naxis = originalFits.getHDU(0).getHeader().getIntValue("NAXIS");
        boolean isColor = false;
        if (naxis == 3) {
            isColor = true;
        }

        String newFNameSolved = addDirectory(fileInformation, "_solved");
        writeFitsWithSuffix(originalFits, newFNameSolved, "_wcs");

        Fits monochromeFits = null;
        if (isColor) {
            monochromeFits = convertToMono(originalFits);
            String newFNameSolvedMono = addDirectory(fileInformation, "_solved_mono");
            writeFitsWithSuffix(monochromeFits, newFNameSolvedMono, "_mono_wcs");
        }

        if (stretch) {
            String newFNameSolvedStretched = addDirectory(fileInformation, "_solved_stretched");
            stretchFITSImage(originalFits, stretchFactor, iterations, algo);
            writeFitsWithSuffix(originalFits, newFNameSolvedStretched, "_wcs_stretch");

            if (isColor) {
                String newFNameSolvedMonoStretch = addDirectory(fileInformation, "_solved_mono_stretched");
                stretchFITSImage(monochromeFits, stretchFactor, iterations, algo);
                writeFitsWithSuffix(monochromeFits, newFNameSolvedMonoStretch, "_mono_wcs_stretch");
            }
        }
    }

    private void writeOnlyStretchedFitsFile(File fileInformation, Fits originalFits, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException, IOException {
        int naxis = originalFits.getHDU(0).getHeader().getIntValue("NAXIS");
        boolean isColor = false;
        if (naxis == 3) {
            isColor = true;
        }

        Fits monochromeFits = null;
        if (isColor) {
            monochromeFits = convertToMono(originalFits);
        }

        String newFNameSolvedStretched = addDirectory(fileInformation, "_stretched");
        stretchFITSImage(originalFits, stretchFactor, iterations, algo);
        writeFitsWithSuffix(originalFits, newFNameSolvedStretched, "_stretch");

        if (isColor) {
            String newFNameSolvedMonoStretch = addDirectory(fileInformation, "_mono_stretched");
            stretchFITSImage(monochromeFits, stretchFactor, iterations, algo);
            writeFitsWithSuffix(monochromeFits, newFNameSolvedMonoStretch, "_mono_stretch");
        }
    }

    private Fits convertToMono(Fits colorFITSImage) throws FitsException, IOException {
        Object monoKernelData = convertToMono(colorFITSImage.getHDU(0).getKernel());
        Fits updatedFits = new Fits();
        updatedFits.addHDU(FitsFactory.hduFactory(monoKernelData));

        Cursor<String, HeaderCard> updatedFitsHeaderIterator = updatedFits.getHDU(0).getHeader().iterator();
        while (updatedFitsHeaderIterator.hasNext()) {
            updatedFitsHeaderIterator.next();
            updatedFitsHeaderIterator.remove();
        }

        Cursor<String, HeaderCard> originalHeader = colorFITSImage.getHDU(0).getHeader().iterator();
        while (originalHeader.hasNext()) {
            HeaderCard originalHeaderCard = originalHeader.next();
            updatedFits.getHDU(0).getHeader().addLine(originalHeaderCard);
        }

        Cursor<String, HeaderCard> headerCursor = updatedFits.getHDU(0).getHeader().iterator();
        headerCursor.setKey("NAXIS");
        if (headerCursor.hasNext()) {
            headerCursor.next();
            headerCursor.remove();
            headerCursor.add(new HeaderCard("NAXIS", 2, "replaced"));
        }

        headerCursor.setKey("NAXIS3");
        if (headerCursor.hasNext()) {
            headerCursor.next();
            headerCursor.remove();
        }
        return updatedFits;
    }

    private void writeFitsWithSuffix(Fits fitsImage, String fitsFilename, String suffix) throws IOException, FitsException {
        int lastSepPosition = fitsFilename.lastIndexOf(".");
        fitsFilename = fitsFilename.substring(0, lastSepPosition) + suffix + ".fit";
        File toDeleteFile = new File(fitsFilename);
        if (toDeleteFile.exists()) {
            toDeleteFile.delete();
        }
        fitsImage.write(new File(fitsFilename));
    }

    private String addDirectory(File currentFile, String directory) throws IOException {
        String newDirectory = currentFile.getParent() + File.separator + directory;
        File newDirFile = new File(newDirectory);
        if (!newDirFile.exists()) {
            newDirFile.mkdirs();
        }
        return newDirFile.getAbsolutePath() + File.separator + currentFile.getName();
    }

    private Object convertToMono(Object kernelData) throws FitsException {
        if (kernelData instanceof short[][][]) {
            short[][][] data = (short[][][]) kernelData;
            short[][] monoData = new short[data[0].length][data[0][0].length];

            for (int i = 0; i < data[0].length; i++) {
                for (int j = 0; j < data[0][i].length; j++) {
                    short val1 = data[0][i][j];
                    short val2 = data[1][i][j];
                    short val3 = data[2][i][j];

                    int average = ((val1 + val2 + val3) / 3);
                    monoData[i][j] = (short) average;
                }
            }
            return monoData;

        } else if (kernelData instanceof int[][][]) {
            int[][][] data = (int[][][]) kernelData;
            int[][] monoData = new int[data[0].length][data[0][0].length];

            for (int i = 0; i < data[0].length; i++) {
                for (int j = 0; j < data[0][i].length; j++) {
                    int val1 = data[0][i][j];
                    int val2 = data[1][i][j];
                    int val3 = data[2][i][j];

                    long average = ((val1 + val2 + val3) / 3);
                    monoData[i][j] = (int) average;
                }
            }
            return monoData;

        } else if (kernelData instanceof float[][][]) {
            float[][][] data = (float[][][]) kernelData;
            float[][] monoData = new float[data[0].length][data[0][0].length];

            for (int i = 0; i < data.length; i++) {
                for (int j = 0; j < data[i].length; j++) {
                    float val1 = data[0][i][j];
                    float val2 = data[1][i][j];
                    float val3 = data[2][i][j];

                    double average = ((val1 + val2 + val3) / 3);
                    monoData[i][j] = (float) average;
                }
            }
            return monoData;

        } else {
            throw new FitsException("Cannot understand file, it has a type=" + kernelData.getClass().getName());
        }
    }

    public void stretchFITSImage(Fits fitsImage, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException, IOException {
        Object kernelData = fitsImage.getHDU(0).getKernel();

        if (kernelData instanceof short[][]) {
            short[][] data = (short[][]) kernelData;
            short[][] stretchedData = (short[][]) stretchImageData(data, stretchFactor, iterations, data[0].length, data.length, algo);
            for (int i = 0; i < data.length; i++) {
                for (int j = 0; j < data[i].length; j++) {
                    data[i][j] = stretchedData[i][j];
                }
            }

        } else if (kernelData instanceof int[][]) {
            int[][] data = (int[][]) kernelData;

        } else if (kernelData instanceof float[][]) {
            float[][] data = (float[][]) kernelData;

        } else if (kernelData instanceof short[][][]) {
            short[][][] data = (short[][][]) kernelData;

            short[][] stretchedRedData = (short[][]) stretchImageData(data[0], stretchFactor, iterations, data[0][0].length, data[0].length, algo);
            short[][] stretchedGreenData = (short[][]) stretchImageData(data[1], stretchFactor, iterations, data[1][0].length, data[1].length, algo);
            short[][] stretchedBlueData = (short[][]) stretchImageData(data[2], stretchFactor, iterations, data[2][0].length, data[2].length, algo);

            for (int i = 0; i < data[0].length; i++) {
                for (int j = 0; j < data[0][i].length; j++) {
                    if (algo.equals(StretchAlgorithm.EXTREME)) {
                        short max = stretchedRedData[i][j];
                        if (max < stretchedGreenData[i][j]) {
                            max = stretchedGreenData[i][j];
                        }
                        if (max < stretchedBlueData[i][j]) {
                            max = stretchedBlueData[i][j];
                        }
                        stretchedRedData[i][j] = max;
                        stretchedGreenData[i][j] = max;
                        stretchedBlueData[i][j] = max;
                    }

                    data[0][i][j] = (short) (stretchedRedData[i][j]);
                    data[1][i][j] = (short) (stretchedGreenData[i][j]);
                    data[2][i][j] = (short) (stretchedBlueData[i][j]);
                }
            }
        } else if (kernelData instanceof int[][][]) {
            int[][][] data = (int[][][]) kernelData;
        } else if (kernelData instanceof float[][][]) {
            float[][][] data = (float[][][]) kernelData;
        } else {
            throw new FitsException("Cannot understand file, it has a type=" + kernelData.getClass().getName());
        }
    }

    public BufferedImage getImagePreview(Object kernelData) throws FitsException {
        BufferedImage ret = new BufferedImage(350, 350, BufferedImage.TYPE_INT_ARGB);

        if (kernelData instanceof short[][]) {
            short[][] data = (short[][]) kernelData;

            int imageHeight = data.length;
            int imageWidth = data[0].length;

            if (imageWidth > 350) imageWidth = 350;
            if (imageHeight > 350) imageHeight = 350;

            for (int i = 0; i < imageHeight; i++) {
                for (int j = 0; j < imageWidth; j++) {
                    int convertedValue = ((int) data[i][j]) + ((int) Short.MAX_VALUE);
                    float intensity = ((float) convertedValue) / (2 * (float) Short.MAX_VALUE);
                    ret.setRGB(j, i, new Color(intensity, intensity, intensity, 1.0f).getRGB());
                }
            }
        } else if (kernelData instanceof int[][]) {
            int[][] data = (int[][]) kernelData;
        } else if (kernelData instanceof float[][]) {
            float[][] data = (float[][]) kernelData;
        } else if (kernelData instanceof short[][][]) {
            short[][][] data = (short[][][]) kernelData;

            int imageHeight = data[0].length;
            int imageWidth = data[0][0].length;

            if (imageWidth > 350) imageWidth = 350;
            if (imageHeight > 350) imageHeight = 350;

            for (int i = 0; i < imageHeight; i++) {
                for (int j = 0; j < imageWidth; j++) {
                    int convertedValueR = ((int) data[0][i][j]) + ((int) Short.MAX_VALUE) + 1;
                    float intensityR = ((float) convertedValueR) / (2 * (float) Short.MAX_VALUE);

                    int convertedValueG = ((int) data[1][i][j]) + ((int) Short.MAX_VALUE) + 1;
                    float intensityG = ((float) convertedValueG) / (2 * (float) Short.MAX_VALUE);

                    int convertedValueB = ((int) data[2][i][j]) + ((int) Short.MAX_VALUE) + 1;
                    float intensityB = ((float) convertedValueB) / (2 * (float) Short.MAX_VALUE);

                    ret.setRGB(j, i, new Color(intensityR, intensityG, intensityB, 1.0f).getRGB());
                }
            }
        } else if (kernelData instanceof int[][][]) {
            int[][][] data = (int[][][]) kernelData;
        } else if (kernelData instanceof float[][][]) {
            float[][][] data = (float[][][]) kernelData;
        } else {
            throw new FitsException("Cannot understand file, it has a type=" + kernelData.getClass().getName());
        }
        return ret;
    }

    private BufferedImage getStretchedImage(Object kernelData, int width, int height, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException {
        BufferedImage ret = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        if (kernelData instanceof short[][]) {
            short[][] data = (short[][]) kernelData;

            int imageHeight = data.length;
            int imageWidth = data[0].length;

            if (imageWidth > width) imageWidth = width;
            if (imageHeight > height) imageHeight = height;

            short[][] stretchedData = (short[][]) stretchImageData(data, stretchFactor, iterations, imageWidth, imageHeight, algo);

            for (int i = 0; i < imageHeight; i++) {
                for (int j = 0; j < imageWidth; j++) {
                    int absValue = ((int) stretchedData[i][j]) + ((int) Short.MAX_VALUE) + 1;
                    if (absValue > 2 * Short.MAX_VALUE) absValue = 2 * Short.MAX_VALUE;
                    float intensity = (((float) absValue) / ((float) (2 * Short.MAX_VALUE)));
                    try {
                        ret.setRGB(j, i, new Color(intensity, intensity, intensity, 1.0f).getRGB());
                    } catch (IllegalArgumentException e) {
                        throw (e);
                    }
                }
            }
        } else if (kernelData instanceof int[][]) {
            int[][] data = (int[][]) kernelData;
        } else if (kernelData instanceof float[][]) {
            float[][] data = (float[][]) kernelData;
        } else if (kernelData instanceof short[][][]) {
            short[][][] data = (short[][][]) kernelData;

            short[][] stretchedDataRed = (short[][]) stretchImageData(data[0], stretchFactor, iterations, width, height, algo);
            short[][] stretchedDataGreen = (short[][]) stretchImageData(data[1], stretchFactor, iterations, width, height, algo);
            short[][] stretchedDataBlue = (short[][]) stretchImageData(data[2], stretchFactor, iterations, width, height, algo);

            int imageHeight = data[0].length;
            int imageWidth = data[0][0].length;

            if (imageWidth > width) imageWidth = width;
            if (imageHeight > height) imageHeight = height;

            for (int i = 0; i < imageHeight; i++) {
                for (int j = 0; j < imageWidth; j++) {
                    int absValueRed = ((int) stretchedDataRed[i][j]) + ((int) Short.MAX_VALUE) + 1;
                    if (absValueRed > 2 * Short.MAX_VALUE) absValueRed = 2 * Short.MAX_VALUE;
                    float intensityRed = (((float) absValueRed) / ((float) (2 * Short.MAX_VALUE)));

                    int absValueGreen = ((int) stretchedDataGreen[i][j]) + ((int) Short.MAX_VALUE) + 1;
                    if (absValueGreen > 2 * Short.MAX_VALUE) absValueGreen = 2 * Short.MAX_VALUE;
                    float intensityGreen = (((float) absValueGreen) / ((float) (2 * Short.MAX_VALUE)));

                    int absValueBlue = ((int) stretchedDataBlue[i][j]) + ((int) Short.MAX_VALUE) + 1;
                    if (absValueBlue > 2 * Short.MAX_VALUE) absValueBlue = 2 * Short.MAX_VALUE;
                    float intensityBlue = (((float) absValueBlue) / ((float) (2 * Short.MAX_VALUE)));

                    if (algo.equals(StretchAlgorithm.EXTREME)) {
                        float maxValue = absValueRed;
                        if (maxValue < absValueGreen) maxValue = absValueGreen;
                        if (maxValue < absValueBlue) maxValue = absValueBlue;

                        intensityRed = (((float) maxValue) / ((float) (2 * Short.MAX_VALUE)));
                        intensityGreen = intensityRed;
                        intensityBlue = intensityRed;
                    }

                    try {
                        Color targetColor = new Color(intensityRed, intensityGreen, intensityBlue, 1.0f);
                        ret.setRGB(j, i, targetColor.getRGB());
                    } catch (IllegalArgumentException e) {
                        throw (e);
                    }
                }
            }
        } else if (kernelData instanceof int[][][]) {
            int[][][] data = (int[][][]) kernelData;
        } else if (kernelData instanceof float[][][]) {
            float[][][] data = (float[][][]) kernelData;
        } else {
            throw new FitsException("Cannot understand file, it has a type=" + kernelData.getClass().getName());
        }
        return ret;
    }

    public BufferedImage getStretchedImagePreview(Object kernelData, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException {
        return getStretchedImage(kernelData, 350, 350, stretchFactor, iterations, algo);
    }

    public BufferedImage getStretchedImageFullSize(Object kernelData, int width, int height, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException {
        return getStretchedImage(kernelData, width, height, stretchFactor, iterations, algo);
    }

    private Object stretchImageData(Object kernelData, int intensity, int iterations, int width, int height, StretchAlgorithm algo) throws FitsException {
        switch (algo) {
            case ENHANCE_HIGH:
                return stretchImageEnhanceHigh(kernelData, intensity, iterations, width, height);
            case ENHANCE_LOW:
                return stretchImageEnhanceLow(kernelData, intensity, iterations, width, height);
            case EXTREME:
                return stretchImageEnhanceExtreme(kernelData, intensity, iterations, width, height);
            default:
                return stretchImageEnhanceLow(kernelData, intensity, iterations, width, height);
        }
    }

    private Object stretchImageEnhanceHigh(Object kernelData, int intensity, int iterations, int width, int height) throws FitsException {
        if (kernelData instanceof short[][]) {
            short[][] data = (short[][]) kernelData;
            short[][] returnData = new short[height][width];

            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    returnData[i][j] = data[i][j];
                }
            }

            for (int iteration = 0; iteration < iterations; iteration++) {
                short minimumValue = Short.MAX_VALUE;
                for (int i = 0; i < height; i++) {
                    for (int j = 0; j < width; j++) {
                        int absValue = (int) returnData[i][j] - (int) Short.MIN_VALUE;
                        float newValue = (float) absValue * ((float) 1 + ((float) intensity / (float) 100));
                        newValue = newValue - Short.MAX_VALUE;

                        if (newValue > Short.MAX_VALUE) {
                            returnData[i][j] = Short.MAX_VALUE;
                        } else {
                            returnData[i][j] = (short) newValue;
                        }

                        if (minimumValue > returnData[i][j]) {
                            minimumValue = returnData[i][j];
                        }
                    }
                }

                int minimumValueDistanceFromZero = (int) minimumValue - (int) Short.MIN_VALUE;
                if (minimumValueDistanceFromZero > 2 * (int) Short.MAX_VALUE) {
                    minimumValueDistanceFromZero = 2 * (int) Short.MAX_VALUE;
                }

                for (int i = 0; i < height; i++) {
                    for (int j = 0; j < width; j++) {
                        returnData[i][j] = (short) ((int) returnData[i][j] - minimumValueDistanceFromZero);
                    }
                }
            }
            return returnData;
        } else if (kernelData instanceof int[][]) {
            int[][] data = (int[][]) kernelData;
            return null;
        } else if (kernelData instanceof float[][]) {
            float[][] data = (float[][]) kernelData;
            return null;
        } else {
            throw new FitsException("Cannot understand file, it has a type=" + kernelData.getClass().getName());
        }
    }

    private Object stretchImageEnhanceLow(Object kernelData, int intensity, int iterations, int width, int height) throws FitsException {
        if (kernelData instanceof short[][]) {
            short[][] data = (short[][]) kernelData;
            short[][] returnData = new short[height][width];

            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    returnData[i][j] = data[i][j];
                }
            }

            for (int iteration = 0; iteration < iterations; iteration++) {
                short minimumValue = Short.MAX_VALUE;
                short maximumValue = Short.MIN_VALUE;

                for (int i = 0; i < height; i++) {
                    for (int j = 0; j < width; j++) {
                        int absValue = (int) returnData[i][j] - (int) Short.MIN_VALUE;
                        float scale = 1 - (((float) absValue) / (2 * ((float) Short.MAX_VALUE)));
                        float newValue = (float) absValue * ((float) 1 + ((((float) intensity / (float) 100)) * scale));
                        newValue = newValue - Short.MAX_VALUE;

                        if (newValue > Short.MAX_VALUE) {
                            returnData[i][j] = Short.MAX_VALUE;
                        } else {
                            returnData[i][j] = (short) newValue;
                        }

                        if (minimumValue > returnData[i][j]) {
                            minimumValue = returnData[i][j];
                        }
                        if (maximumValue < returnData[i][j]) {
                            maximumValue = returnData[i][j];
                        }
                    }
                }

                int minimumValueDistanceFromZero = (int) minimumValue - (int) Short.MIN_VALUE;
                if (minimumValueDistanceFromZero > 2 * (int) Short.MAX_VALUE) {
                    minimumValueDistanceFromZero = 2 * (int) Short.MAX_VALUE;
                }
                int maximumValueDistanceFromMax = (int) Short.MAX_VALUE - (int) maximumValue;
                if (maximumValueDistanceFromMax > 2 * (int) Short.MAX_VALUE) {
                    maximumValueDistanceFromMax = 2 * (int) Short.MAX_VALUE;
                }

                float stretchCoefficient = 1 + (((float) maximumValueDistanceFromMax) / (2 * (float) Short.MAX_VALUE));

                for (int i = 0; i < height; i++) {
                    for (int j = 0; j < width; j++) {
                        int absValue = (int) returnData[i][j] - (int) Short.MIN_VALUE - minimumValueDistanceFromZero;
                        float newValue = ((float) absValue) * stretchCoefficient;
                        newValue = newValue - Short.MAX_VALUE;

                        if (newValue > Short.MAX_VALUE) {
                            returnData[i][j] = Short.MAX_VALUE;
                        } else {
                            returnData[i][j] = (short) newValue;
                        }
                    }
                }
            }
            return returnData;
        } else if (kernelData instanceof int[][]) {
            int[][] data = (int[][]) kernelData;
            return null;
        } else if (kernelData instanceof float[][]) {
            float[][] data = (float[][]) kernelData;
            return null;
        } else {
            throw new FitsException("Cannot understand file, it has a type=" + kernelData.getClass().getName());
        }
    }

    private Object stretchImageEnhanceExtreme(Object kernelData, int threshold, int intensity, int width, int height) throws FitsException {
        if (kernelData instanceof short[][]) {
            short[][] data = (short[][]) kernelData;
            short[][] returnData = new short[height][width];

            long allPixelSumValue = 0;
            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    allPixelSumValue += (data[i][j] - (int) Short.MIN_VALUE);
                }
            }

            float averageNoiseLevel = ((float) allPixelSumValue) / ((float) width * height);

            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    returnData[i][j] = data[i][j];
                    int absValue = (int) returnData[i][j] - (int) Short.MIN_VALUE;

                    if (absValue >= averageNoiseLevel + 10 * threshold) {
                        float newValue = (((float) intensity) / (float) 20) * (2 * ((float) Short.MAX_VALUE));
                        newValue = newValue - Short.MAX_VALUE;

                        if (newValue > Short.MAX_VALUE) {
                            returnData[i][j] = Short.MAX_VALUE;
                        } else {
                            returnData[i][j] = (short) newValue;
                        }
                    }
                }
            }
            return returnData;
        } else if (kernelData instanceof int[][]) {
            int[][] data = (int[][]) kernelData;
            return null;
        } else if (kernelData instanceof float[][]) {
            float[][] data = (float[][]) kernelData;
            return null;
        } else {
            throw new FitsException("Cannot understand file, it has a type=" + kernelData.getClass().getName());
        }
    }

    public static File createDetectionsDirectory(File anyFitsFile) {
        File parentDir = anyFitsFile.getParentFile();
        if (parentDir == null) {
            parentDir = new File(System.getProperty("user.dir"));
        }

        // Generate a sortable timestamp
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
        String timestamp = java.time.LocalDateTime.now().format(formatter);

        // Create the uniquely named detections directory
        File detectionsDir = new File(parentDir, "detections_" + timestamp);

        if (!detectionsDir.exists()) {
            boolean created = detectionsDir.mkdirs();
            if (created) {
                System.out.println("Created new export directory at: " + detectionsDir.getAbsolutePath());
            } else {
                System.err.println("Failed to create export directory. Check folder permissions.");
            }
        }

        return detectionsDir;
    }
}