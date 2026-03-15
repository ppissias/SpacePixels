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

import io.github.ppissias.jplatesolve.astap.ASTAPInterface;
import io.github.ppissias.jplatesolve.astrometrydotnet.AstrometryDotNet;
import io.github.ppissias.jplatesolve.PlateSolveResult;
import io.github.ppissias.jplatesolve.astrometrydotnet.SubmitFileRequest;
import nom.tam.fits.*;
import nom.tam.util.Cursor;
import org.apache.commons.configuration2.FileBasedConfiguration;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.apache.commons.configuration2.ex.ConfigurationException;
import eu.startales.spacepixels.gui.ApplicationWindow;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.PipelineResult;

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

// --- NEW IMPORT FOR THE CALLBACK ---
import java.util.function.IntPredicate;

public class ImageProcessing {

    private final File alignedFitsFolderFullPath;

    // --- NEW: Multi-threading Executor! ---
    // Uses a cached pool to dynamically spin up threads based on available CPU cores
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final AstrometryDotNet astrometryNetInterface = new AstrometryDotNet();
    private final SPConfigurationFile configurationFile;

    public static synchronized ImageProcessing getInstance(File alignedFitsFolderFullPath) throws IOException, FitsException, ConfigurationException {
        String userhome = System.getProperty("user.home");
        if (userhome == null) userhome = "";

        ApplicationWindow.logger.info("Will use config file:" + new File(userhome + "/spacepixelviewer.config").getAbsolutePath());
        SPConfigurationFile configurationFile = new SPConfigurationFile(userhome + "/spacepixelviewer.config");

        return new ImageProcessing(alignedFitsFolderFullPath, configurationFile);
    }

    private ImageProcessing(File alignedFitsFolderFullPath, SPConfigurationFile configFile) throws IOException, FitsException {
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

    // --- UPDATED SIGNATURE: Accepts the UI callback ---
    public File detectObjects(DetectionConfig config, IntPredicate safetyPrompt) throws Exception {
        long startTime = System.currentTimeMillis();

        File[] fitsFileInformation = getFitsFilesDetails();
        int numFrames = fitsFileInformation.length;

        System.out.println("\n--- Loading " + numFrames + " FITS files for JTransient Engine ---");

        // 1. Prepare the data bridges for JTransient and SpacePixels
        List<ImageFrame> framesForLibrary = new ArrayList<>();
        List<short[][]> rawFramesForExport = new ArrayList<>();

        for (int i = 0; i < numFrames; i++) {
            File currentFile = fitsFileInformation[i];
            Fits fitsFile = new Fits(currentFile);
            Object kernel = fitsFile.getHDU(0).getKernel();

            if (!(kernel instanceof short[][])) {
                fitsFile.close();
                throw new IOException("Cannot process: Expected short[][] but found " + kernel.getClass().toString());
            }

            short[][] imageData = (short[][]) kernel;

            // Wrap the raw data for the library
            framesForLibrary.add(new ImageFrame(i, currentFile.getName(), imageData));

            // Keep the raw array for GIF generation later
            rawFramesForExport.add(imageData);

            fitsFile.close();
        }

        // =========================================================
        // 2. HAND OFF TO THE LIBRARY!
        // =========================================================
        System.out.println("\n--- Passing data to JTransient Engine ---");
        JTransientEngine engine = new JTransientEngine();
        JTransientEngine.DEBUG = true;

        PipelineResult result = engine.runPipeline(framesForLibrary, config);

        // Shut down the library's internal threads so they don't leak memory
        engine.shutdown();
        // =========================================================

        // --- NEW: THE SAFETY VALVE CHECK ---
        if (safetyPrompt != null) {
            int trackCount = result.tracks.size();
            // If the UI callback returns false, the user clicked "No". Abort report generation!
            if (!safetyPrompt.test(trackCount)) {
                System.out.println("Report generation aborted by UI callback. Track count: " + trackCount);
                return null;
            }
        }
        // -----------------------------------

        // 3. Handle the results in SpacePixels
        System.out.println("\n--- PHASE 5: Exporting Visualizations ---");

        if (fitsFileInformation.length > 0) {
            File exportDir = createDetectionsDirectory(fitsFileInformation[0]);

            System.out.println("Total Pipeline Time: " + (System.currentTimeMillis() - startTime) + "ms");

            try {
                if (!result.tracks.isEmpty()) {
                    System.out.println("Preparing to export to: " + exportDir.getAbsolutePath());
                } else {
                    System.out.println("No targets found, but generating pipeline report at: " + exportDir.getAbsolutePath());
                }

                // Pass the tracks, the telemetry, the raw frames, AND the new Master Data to the exporter
                ImageDisplayUtils.exportTrackVisualizations(
                        result.tracks,
                        result.telemetry.trackerTelemetry,
                        rawFramesForExport,
                        result.masterStackData, // <--- ADDED MASTER STACK
                        result.masterStars,     // <--- ADDED MASTER STARS
                        exportDir,
                        result.telemetry,
                        config);

                return new File(exportDir, ImageDisplayUtils.detectionReportName);
            } catch (IOException e) {
                System.err.println("Failed to export visualizations: " + e.getMessage());
            }
        }
        return null;
    }

// =========================================================================
// ITERATIVE SLOW-MOVER PIPELINE
// =========================================================================

    public File detectSlowObjectsIterative(DetectionConfig config, java.util.function.IntPredicate safetyPrompt) throws Exception {
        long startTime = System.currentTimeMillis();

        File[] fitsFileInformation = getFitsFilesDetails();
        int numFrames = fitsFileInformation.length;

        if (numFrames < 5) {
            System.out.println("Not enough frames for iterative detection. Falling back to standard pipeline.");
            return detectObjects(config, safetyPrompt);
        }

        System.out.println("\n--- Loading " + numFrames + " FITS files for ITERATIVE PIPELINE ---");

        // 1. Load all frames into RAM once to save massive Disk I/O
        List<ImageFrame> allFrames = new ArrayList<>();
        List<short[][]> rawFramesForExport = new ArrayList<>();

        for (int i = 0; i < numFrames; i++) {
            File currentFile = fitsFileInformation[i];
            Fits fitsFile = new Fits(currentFile);
            Object kernel = fitsFile.getHDU(0).getKernel();

            if (!(kernel instanceof short[][])) {
                fitsFile.close();
                throw new IOException("Cannot process: Expected short[][] but found " + kernel.getClass().toString());
            }

            short[][] imageData = (short[][]) kernel;
            allFrames.add(new ImageFrame(i, currentFile.getName(), imageData));
            rawFramesForExport.add(imageData);
            fitsFile.close();
        }

        // 2. Create the Master Directory for this iterative run
        File parentDir = fitsFileInformation[0].getParentFile();
        if (parentDir == null) {
            parentDir = new File(System.getProperty("user.dir"));
        }
        String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File masterDir = new File(parentDir, "iterative_detections_" + timestamp);

        if (!masterDir.exists()) {
            masterDir.mkdirs();
        }

        // 3. The Iteration Loop (5, 10, 15... up to numFrames)
        for (int k = 5; k <= numFrames; k += 5) {
            System.out.println("\n>>> RUNNING ITERATION: " + k + " Maximally Spaced Frames");

            List<ImageFrame> spacedSubset = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                // Calculate the maximally spaced index
                int index = (int) Math.round(i * (numFrames - 1) / (double) (k - 1));
                spacedSubset.add(allFrames.get(index));
            }

            // Run the math for this subset
            JTransientEngine engine = new JTransientEngine();
            PipelineResult result = engine.runPipeline(spacedSubset, config);
            engine.shutdown();

            // Check Safety Valve for this specific run
            if (safetyPrompt != null && !safetyPrompt.test(result.tracks.size())) {
                System.out.println("Iteration " + k + " aborted by UI callback due to high track count. Stopping further iterations.");
                break; // Stop the loop, but keep the master directory with whatever finished previously!
            }

            // Create a specific sub-directory for this iteration
            File iterationDir = new File(masterDir, k + "_frames");
            iterationDir.mkdirs();

            System.out.println("Exporting " + k + "-frame visualization to: " + iterationDir.getName());

            try {
                // Export this specific run's report into its own folder, including Master Data
                ImageDisplayUtils.exportTrackVisualizations(
                        result.tracks,
                        result.telemetry.trackerTelemetry,
                        rawFramesForExport,     // The exporter will pull the correct original frames!
                        result.masterStackData, // <--- ADDED MASTER STACK
                        result.masterStars,     // <--- ADDED MASTER STARS
                        iterationDir,
                        result.telemetry,
                        config);

            } catch (IOException e) {
                System.err.println("Failed to export visualization for iteration " + k + ": " + e.getMessage());
            }
        }

        System.out.println("Total Iterative Pipeline Time: " + (System.currentTimeMillis() - startTime) + "ms");

        // Return the Master Directory.
        // SpacePixels will naturally open this folder in the OS file explorer!
        return masterDir;
    }
// =========================================================================
    // UPDATED FITS FILE INFORMATION (The Import Gatekeeper)
    // =========================================================================

    public FitsFileInformation[] getFitsfileInformation() throws Exception {
        File[] fitsFileInformation = getFitsFilesDetails();
        int numFiles = fitsFileInformation.length;

        if (numFiles == 0) return new FitsFileInformation[0];

        // --- THE GATEKEEPER: Check the format of the first file ---
        FitsFormatChecker.FitsFormat format = FitsFormatChecker.checkFormat(fitsFileInformation[0]);

        if (format == FitsFormatChecker.FitsFormat.MONO_32BIT_FLOAT || format == FitsFormatChecker.FitsFormat.MONO_32BIT_INT) {
            int choice = JOptionPane.showConfirmDialog(null,
                    "These images appear to be 32-bit.\nSpacePixels' transient detection engine is highly optimized for 16-bit monochrome files.\n\n" +
                            "Would you like to automatically standardize this sequence to 16-bit Mono?",
                    "Format Conversion Required",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                ApplicationWindow.logger.info("Starting 32-bit Mono to 16-bit Mono conversion...");
                batchConvert32BitTo16Bit(fitsFileInformation, false);
                JOptionPane.showMessageDialog(null, "Conversion Complete!\n\nThe 16-bit files have been saved in a new directory.\nPlease import the newly created directory to continue.", "Success", JOptionPane.INFORMATION_MESSAGE);
                return new FitsFileInformation[0]; // Stop the current import
            }

        } else if (format == FitsFormatChecker.FitsFormat.COLOR_32BIT_FLOAT || format == FitsFormatChecker.FitsFormat.COLOR_32BIT_INT) {
            int choice = JOptionPane.showConfirmDialog(null,
                    "These images are 32-bit Color.\nSpacePixels' transient detection engine requires 16-bit monochrome files.\n\n" +
                            "Would you like to extract the Luminance (16-bit Mono) and save a 16-bit Color version?",
                    "Color Format Conversion Required",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                ApplicationWindow.logger.info("Starting 32-bit Color to 16-bit Mono/Color conversion...");
                batchConvert32BitTo16Bit(fitsFileInformation, true);
                JOptionPane.showMessageDialog(null, "Conversion Complete!\n\nThe 16-bit files have been saved in new directories.\nPlease import the '_16bit_mono' directory to run detections.", "Success", JOptionPane.INFORMATION_MESSAGE);
                return new FitsFileInformation[0]; // Stop the current import
            }
        } else if (format == FitsFormatChecker.FitsFormat.UNSUPPORTED) {
            JOptionPane.showMessageDialog(null, "Unsupported FITS format detected. SpacePixels supports 16-bit and 32-bit FITS files.", "Import Error", JOptionPane.ERROR_MESSAGE);
            return new FitsFileInformation[0];
        }

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

                    int[] axes = hdu.getAxes();

                    if (axes.length == 2) {
                        monochromeImage = true;
                        height = axes[0];
                        width = axes[1];
                    } else if (axes.length == 3) {
                        monochromeImage = false;
                        height = axes[1];
                        width = axes[2];
                    } else {
                        throw new FitsException("Cannot understand file, it has axes length=" + axes.length);
                    }

                    FitsFileInformation fileInfo = new FitsFileInformation(fpath, currentFile.getName(), monochromeImage, width, height);

                    Cursor<String, HeaderCard> iter = fitsHeader.iterator();
                    while (iter.hasNext()) {
                        HeaderCard fitsHeaderCard = iter.next();
                        fileInfo.getFitsHeader().put(fitsHeaderCard.getKey(), fitsHeaderCard.getValue());
                    }

                    PlateSolveResult previousSolveresult = readSolveResults(fpath);
                    if (previousSolveresult != null) {
                        fileInfo.setSolveResult(previousSolveresult);
                    }

                    return fileInfo;

                } finally {
                    if (fitsFile != null) {
                        fitsFile.close();
                    }
                }
            });
        }

        FitsFileInformation[] ret = new FitsFileInformation[numFiles];

        try {
            List<Future<FitsFileInformation>> futures = executor.invokeAll(tasks);
            for (int i = 0; i < futures.size(); i++) {
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

// =========================================================================
    // 32-BIT TO 16-BIT STANDARDIZATION LOGIC
    // =========================================================================

    private void batchConvert32BitTo16Bit(File[] files, boolean isColor) throws Exception {
        for (File file : files) {
            ApplicationWindow.logger.info("Converting 32-bit file: " + file.getName());

            // --- DEBUG: Print Original File ---
            printFitsDebugInfo("ORIGINAL 32-BIT", file);

            Fits originalFits = new Fits(file);
            Object kernel = originalFits.getHDU(0).getKernel();

            if (isColor) {
                // 1. Convert to 16-bit Color
                short[][][] color16 = standardizeTo16BitColor(kernel);
                Fits colorFits = createFitsFromData(color16, originalFits);
                String colorName = addDirectory(file, "_16bit_color");
                writeFitsWithSuffix(colorFits, colorName, "_16bit_color");

                // --- DEBUG: Print Converted Color File ---
                String finalColorPath = colorName.substring(0, colorName.lastIndexOf(".")) + "_16bit_color.fit";
                printFitsDebugInfo("CONVERTED 16-BIT COLOR", new File(finalColorPath));

                // 2. Extract Luminance to 16-bit Mono
                short[][] mono16 = extractLuminance(color16);
                Fits monoFits = createFitsFromData(mono16, originalFits);

                // Note: No manual header hacking needed here anymore!
                // createFitsFromData safely handles NAXIS and NAXIS3 automatically.
                String monoName = addDirectory(file, "_16bit_mono");
                writeFitsWithSuffix(monoFits, monoName, "_16bit_mono");

                // --- DEBUG: Print Converted Luminance File ---
                String finalLuminancePath = monoName.substring(0, monoName.lastIndexOf(".")) + "_16bit_mono.fit";
                printFitsDebugInfo("CONVERTED 16-BIT LUMINANCE", new File(finalLuminancePath));

            } else {
                // Convert to 16-bit Mono
                short[][] mono16 = standardizeTo16BitMono(kernel);
                Fits monoFits = createFitsFromData(mono16, originalFits);
                String monoName = addDirectory(file, "_16bit_converted");
                writeFitsWithSuffix(monoFits, monoName, "_16bit");

                // --- DEBUG: Print Converted Mono File ---
                String finalMonoPath = monoName.substring(0, monoName.lastIndexOf(".")) + "_16bit.fit";
                printFitsDebugInfo("CONVERTED 16-BIT MONO", new File(finalMonoPath));
            }
            originalFits.close();
        }
    }

    private Fits createFitsFromData(Object newData, Fits originalFits) throws FitsException, IOException {
        Fits updatedFits = new Fits();
        BasicHDU<?> newHDU = FitsFactory.hduFactory(newData);
        updatedFits.addHDU(newHDU);

        Header newHeader = newHDU.getHeader();
        Header originalHeader = originalFits.getHDU(0).getHeader();

        java.util.List<String> structuralKeys = java.util.Arrays.asList(
                "SIMPLE", "BITPIX", "NAXIS", "NAXIS1", "NAXIS2", "NAXIS3",
                "EXTEND", "BZERO", "BSCALE"
        );

        Cursor<String, HeaderCard> originalCursor = originalHeader.iterator();
        while (originalCursor.hasNext()) {
            HeaderCard card = originalCursor.next();
            String key = card.getKey();

            if (key != null && !structuralKeys.contains(key)) {
                if (!newHeader.containsKey(key)) {
                    newHeader.addLine(card);
                }
            }
        }

        // --- THE CRITICAL FIX ---
        // Explicitly tell the FITS format that this signed 16-bit array
        // actually represents unsigned 0-65535 data!
        newHeader.addValue("BZERO", 32768.0, "offset data range to that of unsigned short");
        newHeader.addValue("BSCALE", 1.0, "default scaling factor");

        return updatedFits;
    }

// =========================================================================
    // DEBUG: FITS VERIFICATION
    // =========================================================================

    private void printFitsDebugInfo(String label, File fitsFile) {
        try (Fits fits = new Fits(fitsFile)) {
            BasicHDU<?> hdu = fits.getHDU(0);
            Header header = hdu.getHeader();
            Object kernel = hdu.getKernel();

            int bitpix = header.getIntValue("BITPIX", 0);
            double bzero = header.getDoubleValue("BZERO", 0.0);
            double bscale = header.getDoubleValue("BSCALE", 1.0);

            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;

            // Scan 2D arrays (Mono)
            if (kernel instanceof float[][]) {
                float[][] data = (float[][]) kernel;
                for (float[] row : data) {
                    for (float val : row) {
                        if (val < min) min = val;
                        if (val > max) max = val;
                    }
                }
            } else if (kernel instanceof short[][]) {
                short[][] data = (short[][]) kernel;
                for (short[] row : data) {
                    for (short val : row) {
                        if (val < min) min = val;
                        if (val > max) max = val;
                    }
                }
            } else if (kernel instanceof int[][]) {
                int[][] data = (int[][]) kernel;
                for (int[] row : data) {
                    for (int val : row) {
                        if (val < min) min = val;
                        if (val > max) max = val;
                    }
                }
            }

            System.out.println("\n--- DEBUG: " + label + " ---");
            System.out.println("File:   " + fitsFile.getName());
            System.out.println("BITPIX: " + bitpix);
            System.out.println("BZERO:  " + bzero);
            System.out.println("BSCALE: " + bscale);
            if (min != Double.MAX_VALUE) {
                System.out.println("Raw Min Value: " + min);
                System.out.println("Raw Max Value: " + max);
                // Calculate what the true value is after BZERO/BSCALE are applied by a viewer
                System.out.println("True Visual Min: " + ((min * bscale) + bzero));
                System.out.println("True Visual Max: " + ((max * bscale) + bzero));
            } else {
                System.out.println("(3D Color array, skipping min/max scan for brevity)");
            }
            System.out.println("-----------------------------------");

        } catch (Exception e) {
            System.err.println("Debug read failed for " + fitsFile.getName() + ": " + e.getMessage());
        }
    }

    private short[][] standardizeTo16BitMono(Object kernel) throws IOException {
        if (kernel instanceof float[][]) {
            float[][] floatData = (float[][]) kernel;
            int height = floatData.length;
            int width = floatData[0].length;
            short[][] shortData = new short[height][width];

            float maxVal = 0.0f;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (floatData[y][x] > maxVal) maxVal = floatData[y][x];
                }
            }
            float scaleFactor = (maxVal <= 1.0f && maxVal > 0.0f) ? 65535.0f : 1.0f;

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    float trueVal = floatData[y][x] * scaleFactor;
                    if (trueVal < 0) trueVal = 0;
                    if (trueVal > 65535) trueVal = 65535;

                    // The FITS Shift: Map 0...65535 down to -32768...32767
                    int shiftedVal = Math.round(trueVal) - 32768;
                    shortData[y][x] = (short) shiftedVal;
                }
            }
            return shortData;
        } else if (kernel instanceof int[][]) {
            int[][] intData = (int[][]) kernel;
            int height = intData.length;
            int width = intData[0].length;
            short[][] shortData = new short[height][width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int trueVal = intData[y][x];
                    if (trueVal < 0) trueVal = 0;
                    if (trueVal > 65535) trueVal = 65535;

                    // The FITS Shift
                    shortData[y][x] = (short) (trueVal - 32768);
                }
            }
            return shortData;
        }
        throw new IOException("Unsupported FITS format for Mono Standardization");
    }

    private short[][][] standardizeTo16BitColor(Object kernel) throws IOException {
        if (kernel instanceof float[][][]) {
            float[][][] floatData = (float[][][]) kernel;
            int depth = floatData.length;
            int height = floatData[0].length;
            int width = floatData[0][0].length;
            short[][][] shortData = new short[depth][height][width];

            float maxVal = 0.0f;
            for (int z = 0; z < depth; z++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        if (floatData[z][y][x] > maxVal) maxVal = floatData[z][y][x];
                    }
                }
            }
            float scaleFactor = (maxVal <= 1.0f && maxVal > 0.0f) ? 65535.0f : 1.0f;

            for (int z = 0; z < depth; z++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        float trueVal = floatData[z][y][x] * scaleFactor;
                        if (trueVal < 0) trueVal = 0;
                        if (trueVal > 65535) trueVal = 65535;

                        // The FITS Shift
                        int shiftedVal = Math.round(trueVal) - 32768;
                        shortData[z][y][x] = (short) shiftedVal;
                    }
                }
            }
            return shortData;
        } else if (kernel instanceof int[][][]) {
            int[][][] intData = (int[][][]) kernel;
            int depth = intData.length;
            int height = intData[0].length;
            int width = intData[0][0].length;
            short[][][] shortData = new short[depth][height][width];
            for (int z = 0; z < depth; z++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int trueVal = intData[z][y][x];
                        if (trueVal < 0) trueVal = 0;
                        if (trueVal > 65535) trueVal = 65535;

                        // The FITS Shift
                        shortData[z][y][x] = (short) (trueVal - 32768);
                    }
                }
            }
            return shortData;
        }
        throw new IOException("Unsupported FITS format for Color Standardization");
    }

    private short[][] extractLuminance(short[][][] color16) {
        int height = color16[0].length;
        int width = color16[0][0].length;
        short[][] monoData = new short[height][width];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = color16[0][y][x] & 0xFFFF; // Prevent signed negative issues during math
                int g = color16[1][y][x] & 0xFFFF;
                int b = color16[2][y][x] & 0xFFFF;
                int average = (r + g + b) / 3;
                monoData[y][x] = (short) average;
            }
        }
        return monoData;
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

        } else {
            throw new FitsException("Cannot convert to mono. Expected 16-bit color (short[][][]), but received type=" + kernelData.getClass().getName());
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