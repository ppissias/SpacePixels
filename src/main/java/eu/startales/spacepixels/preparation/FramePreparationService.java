package eu.startales.spacepixels.preparation;

import com.google.gson.GsonBuilder;
import eu.startales.spacepixels.fits.FitsInput;
import eu.startales.spacepixels.fits.ExposureGroup;
import eu.startales.spacepixels.preparation.PreparationInputs.Role;
import eu.startales.spacepixels.util.StagedFile;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prepares a private copy of one observing sequence, validating complete individual-frame output before publication. */
public final class FramePreparationService {
    /** Completed preparation products survive a subsequent import failure. */
    public record Result(Path directory, Path aligned, int frames, String tool, double multiplier, double addedOffset,
                         List<Path> excluded) {
        /** Captures the excluded input identities without retaining the worker's mutable collection. */
        public Result { excluded = List.copyOf(excluded); }
        /** Number of original lights, including exposures excluded by registration. */
        public int inputFrames() { return frames + excluded.size(); }
    }

    /** Captured input lists distinguish existing master FITS files from folders of calibration exposures. */
    public record Inputs(Map<Role, List<Path>> files, Set<Role> masters, long sourceBytes) {
        /** Copies both collection levels so no live input selection is retained. */
        public Inputs {
            Map<Role, List<Path>> detached = new EnumMap<>(Role.class);
            files.forEach((key, value) -> detached.put(key, List.copyOf(value)));
            files = Map.copyOf(detached);
            masters = Set.copyOf(masters);
        }
    }

    /** Local header-fix provenance, written only in the run's manifest. */
    private record HeaderFix(String source, List<SirilHeaderCorrections.Correction> corrections) { }

    /** Per-frame identity in the completed manifest. */
    private record FrameMapping(String source, String copied, String output) { }

    /** The service never owns application settings or mutable GUI/session state. */
    public FramePreparationService() { }

    /** Inventories explicitly selected roles; a supplied calibration file means an existing master FITS. */
    public static Inputs inspect(Map<Role, Path> paths) throws IOException {
        Map<Role, List<Path>> files = new EnumMap<>(Role.class);
        Set<Role> masters = new HashSet<>();
        long bytes = 0;
        Set<Path> unique = new HashSet<>();
        for (Role role : Role.values()) {
            Path path = paths.get(role);
            if (path == null) continue;
            List<Path> found;
            if (role != Role.LIGHTS && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                if (!isFits(path)) throw new IOException("An existing " + role + " master must be a FITS file.");
                found = List.of(path.toRealPath());
                masters.add(role);
            } else {
                found = PreparationInputs.inspect(path).files();
                if (role != Role.LIGHTS && found.stream().anyMatch(PreparationInputs::namedMaster))
                    throw new IOException(role + " folder contains an existing master. Select the master FITS file directly, or use a folder containing only individual calibration exposures.");
            }
            int required = role == Role.LIGHTS ? 3 : masters.contains(role) ? 1 : 2;
            if (found.size() < required) throw new IOException(role + " needs at least " + required + " image(s), or select an existing calibration master.");
            for (Path file : found) {
                if (!unique.add(file.toRealPath())) throw new IOException("An input image was assigned to more than one role.");
                bytes += Files.size(file);
            }
            files.put(role, found);
        }
        if (!files.containsKey(Role.LIGHTS)) throw new IOException("Choose the folder of individual light exposures.");
        return new Inputs(files, masters, bytes);
    }

    /** Runs conversion/calibration/registration on the worker, observing cancellation through owned subprocess cleanup. */
    public Result prepare(PreparationRequest request, BooleanSupplier cancelled, BiConsumer<Integer, String> progress, Consumer<String> details) throws Exception {
        return prepare(request, cancelled, progress, details, null);
    }

    /** Uses the desktop's explicit outlier decision, or rejects extremes when invoked without an interaction. */
    public Result prepare(PreparationRequest request, BooleanSupplier cancelled, BiConsumer<Integer, String> progress, Consumer<String> details,
                          eu.startales.spacepixels.fits.ImageScaling.Decision decision) throws Exception {
        Inputs inputs = inspect(request.inputs());
        Path executable = SirilInstallation.resolve(request.executable());
        Path parent = request.outputParent().toRealPath();
        if (!Files.isDirectory(parent)) throw new IOException("Choose an existing output parent folder.");
        long estimated = Math.max(256L * 1024 * 1024, inputs.sourceBytes() * 12);
        if (Files.getFileStore(parent).getUsableSpace() < estimated) throw new IOException("The output disk needs more working space (approximately " + (estimated / 1_073_741_824.0) + " GiB).");
        Path run = eu.startales.spacepixels.util.RunDirectories.create(parent, "spacepixels_prepared_");
        Path log = run.resolve("preparation.log");
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("version", 1);
        manifest.put("outcome", "RUNNING");
        manifest.put("engine", "SIRIL");
        manifest.put("sensor", request.sensor().name());
        manifest.put("bayerSource", request.bayer().name());
        manifest.put("rowOrder", request.rowOrder().name());
        manifest.put("offsetX", request.offsetX());
        manifest.put("offsetY", request.offsetY());
        manifest.put("keepWorkingFiles", request.keepWorkingFiles());
        boolean cleaned = false;
        try {
            Files.createDirectories(run.resolve("masters"));
            if (request.referenceStack()) Files.createDirectories(run.resolve("reference"));
            progress.accept(0, "Checking the Siril installation…");
            details.accept("Run files and complete log: " + run + "\n");
            Files.writeString(run.resolve("siril.cfg"), SirilPreparationRecipe.configuration());
            PreparationProcess.Result probe = PreparationProcess.run(SirilInstallation.probe(executable, run), run, environment(), log,
                    Duration.ofSeconds(15), cancelled);
            String tool = SirilInstallation.describeProbe(probe.output());
            progress.accept(0, "Copying selected inputs; original files remain untouched…");
            Map<Role, List<Path>> copies = new EnumMap<>(Role.class);
            List<HeaderFix> fixes = new ArrayList<>();
            for (Role role : Role.values()) {
                if (!inputs.files().containsKey(role)) continue;
                Path folder = Files.createDirectories(run.resolve("input").resolve(role.folder()));
                Files.createDirectories(run.resolve("converted").resolve(role.folder()));
                List<Path> copied = new ArrayList<>();
                for (Path source : inputs.files().get(role)) {
                    check(cancelled);
                    String extension = PreparationInputs.extension(source);
                    Path copy = folder.resolve(String.format(Locale.ROOT, "%06d.%s", copied.size() + 1, extension));
                    long length = Files.size(source);
                    var modified = Files.getLastModifiedTime(source);
                    Files.copy(source, copy);
                    if (!copy.toFile().setWritable(true)) throw new IOException("Cannot make the private working copy writable.");
                    if (length != Files.size(source) || !modified.equals(Files.getLastModifiedTime(source))) throw new IOException("An input changed while being copied.");
                    if (isFits(copy)) {
                        List<SirilHeaderCorrections.Correction> corrections = SirilHeaderCorrections.normalizePrivateCopy(copy);
                        if (!corrections.isEmpty()) fixes.add(new HeaderFix(source.toString(), corrections));
                    }
                    copied.add(copy);
                }
                copies.put(role, List.copyOf(copied));
            }
            manifest.put("headerCorrections", fixes);
            List<PreparedSequenceWriter.Frame> frames;
            progress.accept(5, "Siril: converting working copies…");
            runSiril(executable, run, "convert.ssf", SirilPreparationRecipe.conversion(copies.keySet(), inputs.masters()), log, cancelled, details);
            progress.accept(10, "Checking converted frames, calibration compatibility and camera interpretation…");
            Map<Role, Integer> counts = new EnumMap<>(Role.class);
            Map<Role, Header> roleHeaders = new EnumMap<>(Role.class);
            for (Role role : copies.keySet()) {
                counts.put(role, copies.get(role).size());
                if (inputs.masters().contains(role)) {
                    Files.copy(copies.get(role).getFirst(), run.resolve("masters").resolve(role.folder() + ".fit"));
                    roleHeaders.put(role, SirilHeaderCorrections.imageHeader(copies.get(role).getFirst()));
                } else {
                    List<Path> converted = sequence(run.resolve("converted").resolve(role.folder()), role.folder() + "_");
                    if (converted.size() != copies.get(role).size()) throw new IOException("Siril did not convert every selected " + role + " image. See preparation.log.");
                    for (int i = 0; i < converted.size(); i++) if (isFits(copies.get(role).get(i))) verifyMetadata(copies.get(role).get(i), converted.get(i));
                    validateSensor(request, converted, role);
                    roleHeaders.put(role, SirilHeaderCorrections.imageHeader(converted.getFirst()));
                    if (role == Role.LIGHTS && request.sensor() == PreparationRequest.Sensor.CFA) {
                        Header cfa = SirilHeaderCorrections.imageHeader(converted.getFirst());
                        manifest.put("resolvedCfa", Map.of("pattern", cfa.getStringValue("BAYERPAT"), "rowOrder", cfa.getStringValue("ROWORDER"),
                                "offsetX", cfa.getIntValue("XBAYROFF", 0), "offsetY", cfa.getIntValue("YBAYROFF", 0)));
                    }
                }
            }
            validateCalibrationHeaders(roleHeaders);
            progress.accept(15, "Siril: calibrating and registering individual exposures…");
            runSiril(executable, run, "prepare.ssf", SirilPreparationRecipe.processing(request, counts, inputs.masters()), log, cancelled, details);
            Path lights = run.resolve("converted/lights");
            List<Path> registered = sequence(lights, "r_");
            List<Path> converted = sequence(lights, "lights_");
            List<Path> originalMetadata = new ArrayList<>();
            for (int i = 0; i < converted.size(); i++) originalMetadata.add(isFits(copies.get(Role.LIGHTS).get(i)) ? copies.get(Role.LIGHTS).get(i) : converted.get(i));
            frames = associate(inputs.files().get(Role.LIGHTS), originalMetadata, registered);
            Set<Path> retained = new HashSet<>();
            for (PreparedSequenceWriter.Frame frame : frames) retained.add(frame.original());
            List<Path> excluded = inputs.files().get(Role.LIGHTS).stream().filter(path -> !retained.contains(path)).toList();
            manifest.put("inputFrameCount", inputs.files().get(Role.LIGHTS).size());
            manifest.put("registeredFrameCount", frames.size());
            manifest.put("excludedSources", excluded.stream().map(Path::toString).toList());
            if (!excluded.isEmpty()) details.accept("Registration kept " + frames.size() + " of " + inputs.files().get(Role.LIGHTS).size()
                    + " lights; excluded " + excluded.size() + ". Excluded source identities are recorded in preparation.json.\n");
            for (PreparedSequenceWriter.Frame frame : frames) {
                Header header = SirilHeaderCorrections.imageHeader(frame.registered());
                int planes = header.getIntValue("NAXIS", 0);
                if (request.sensor() == PreparationRequest.Sensor.MONO ? planes != 2 : planes != 3 || header.getIntValue("NAXIS3", 0) != 3) {
                    throw new IOException("Registered output does not match the chosen sensor/debayer interpretation.");
                }
            }
            progress.accept(70, "Validating and writing the common 16-bit monochrome sequence…");
            PreparedSequenceWriter.Result written = PreparedSequenceWriter.write(frames, run.resolve("aligned"), cancelled,
                    percent -> progress.accept(70 + percent * 29 / 100, "Validating prepared frames… " + percent + "%"), decision);
            check(cancelled);
            try (var importer = new eu.startales.spacepixels.fits.FitsImportService(run.resolve("aligned").toFile(), null)) {
                if (importer.readFiles().length != frames.size()) throw new IOException("The completed sequence did not pass ordinary import validation.");
            }
            check(cancelled);
            List<FrameMapping> mappings = new ArrayList<>();
            for (int i = 0; i < frames.size(); i++) mappings.add(new FrameMapping(frames.get(i).original().toString(),
                    run.relativize(frames.get(i).metadata()).toString(), run.relativize(written.files().get(i)).toString()));
            manifest.put("tool", tool);
            manifest.put("frames", mappings);
            manifest.put("intensityMultiplier", written.multiplier());
            manifest.put("addedOffsetBeforeMultiplier", written.addedOffset());
            manifest.put("scaling", written.scaling());
            manifest.put("monochromeMethod", "Fixed RGB arithmetic mean, common image-data scale and offset; no nonlinear stretch");
            manifest.put("registrationFraming", "Reference-sized canvas; zero registration margins retained for drift/dither analysis");
            manifest.put("astrometry", "Plate solve the registered prepared sequence; source WCS is not reused");
            if (!request.keepWorkingFiles()) {
                progress.accept(99, "Removing copied inputs and intermediate files…");
                details.accept("Removing working files; final frames, requested stack, scripts, settings and logs are kept.\n");
                removeWorkingFiles(run);
                cleaned = true;
            }
            check(cancelled);
            manifest.put("outcome", "COMPLETED");
            writeManifest(run, manifest);
            check(cancelled);
            progress.accept(100, "Prepared " + frames.size() + " of " + inputs.files().get(Role.LIGHTS).size() + " individual frames."
                    + (excluded.isEmpty() ? "" : " Registration excluded " + excluded.size() + "."));
            return new Result(run, run.resolve("aligned"), frames.size(), tool, written.multiplier(), written.addedOffset(), excluded);
        } catch (Exception failure) {
            if (!request.keepWorkingFiles() && !cleaned) {
                try { removeWorkingFiles(run); }
                catch (Exception cleanup) { failure.addSuppressed(cleanup); manifest.put("cleanupProblem", cleanup.getMessage()); }
            }
            manifest.put("outcome", failure instanceof CancellationException ? "CANCELLED" : "FAILED");
            manifest.put("reason", failure.getMessage());
            try { writeManifest(run, manifest); } catch (Exception saving) { failure.addSuppressed(saving); }
            if (failure instanceof CancellationException) throw failure;
            throw new IOException(failure.getMessage() + "\nRun files and logs: " + run, failure);
        }
    }

    /**
     * Removes only the three known working trees of this unique owned run, without following symbolic links.
     * Real-path checks also refuse junctions or a replaced run root that could redirect cleanup outside the run.
     */
    static void removeWorkingFiles(Path run) throws IOException {
        Path root = run.toAbsolutePath().normalize();
        if (root.getFileName() == null || !root.getFileName().toString().startsWith("spacepixels_prepared_")
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !root.equals(root.toRealPath())) {
            throw new IOException("Refusing cleanup outside the owned preparation run.");
        }
        List<Path> targets = List.of(root.resolve("input"), root.resolve("converted"), root.resolve("masters"));
        for (Path target : targets) {
            if (!target.getParent().equals(root)) throw new IOException("Unsafe preparation cleanup path.");
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                /** Refuses directory redirections before visiting anything inside them, including links to final output. */
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    if (!directory.toRealPath().equals(directory.toAbsolutePath().normalize())) throw new IOException("A working directory redirects preparation cleanup.");
                    return FileVisitResult.CONTINUE;
                }
                /** Deletes the owned file or link itself, never a symbolic link's target. */
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                /** Deletes an owned directory after its contents, preserving failures rather than silently skipping them. */
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException problem) throws IOException {
                    if (problem != null) throw problem;
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /** Previews reproducible generated instructions without touching selected originals or launching a tool. */
    public static String preview(PreparationRequest request) throws IOException {
        Inputs inputs = inspect(request.inputs());
        String interpretation = "# Input: " + request.sensor() + "; Bayer: " + request.bayer() + "; rows: " + request.rowOrder()
                + "; offsets: " + request.offsetX() + ", " + request.offsetY() + "\n"
                + "# Working files: " + (request.keepWorkingFiles() ? "kept" : "removed after processing; final images, scripts, settings and logs kept") + "\n";
        Map<Role, Integer> counts = new EnumMap<>(Role.class);
        inputs.files().forEach((role, files) -> counts.put(role, files.size()));
        return interpretation + "# CFA overrides are applied to private converted headers before processing.\n# Private Siril configuration\n" + SirilPreparationRecipe.configuration() + "\n# Conversion\n"
                + SirilPreparationRecipe.conversion(inputs.files().keySet(), inputs.masters()) + "\n# Preparation\n"
                + SirilPreparationRecipe.processing(request, counts, inputs.masters());
    }

    /** Validates converted image modes and exposure cohorts; explicit CFA overrides affect only run-owned working copies. */
    static String validateSensor(PreparationRequest request, List<Path> files, Role role) throws Exception {
        String first = null;
        Header reference = null;
        double minimumLightExposure = Double.POSITIVE_INFINITY;
        double maximumLightExposure = Double.NEGATIVE_INFINITY;
        for (Path path : files) {
            try (Fits fits = new Fits(path.toFile())) {
                BasicHDU<?> image = FitsInput.getImageHDU(fits);
                if (image == null) throw new IOException("Converted input has no image HDU.");
                int dimensions = image.getAxes().length;
                Header header = image.getHeader();
                if (reference == null) reference = header;
                else {
                    if (reference.getIntValue("NAXIS1") != header.getIntValue("NAXIS1") || reference.getIntValue("NAXIS2") != header.getIntValue("NAXIS2")) {
                        throw new IOException("Selected " + role + " images have different dimensions.");
                    }
                    if ((role == Role.LIGHTS || role == Role.FLATS) && !java.util.Objects.equals(reference.getStringValue("FILTER"), header.getStringValue("FILTER"))) {
                        throw new IOException("Selected " + role + " images have different or inconsistently missing filter metadata. Choose one group.");
                    }
                    if (role == Role.DARKS || role == Role.DARK_FLATS || role == Role.FLATS && request.inputs().containsKey(Role.DARK_FLATS)) {
                        requireExposure(reference, header, role + " inputs");
                    }
                }
                if (role == Role.LIGHTS) {
                    double exposure = header.getDoubleValue("EXPTIME", header.getDoubleValue("EXPOSURE", Double.NaN));
                    if (Double.isFinite(exposure)) {
                        minimumLightExposure = Math.min(minimumLightExposure, exposure);
                        maximumLightExposure = Math.max(maximumLightExposure, exposure);
                        // Match ordinary import's millisecond interpretation and compare the full range, independent of file order.
                        if (!ExposureGroup.sameExposure((long) (minimumLightExposure * 1000.0), (long) (maximumLightExposure * 1000.0))) {
                            throw new IOException("Light frame exposures range from " + minimumLightExposure + " s to "
                                    + maximumLightExposure + " s. Choose one exposure group.");
                        }
                    }
                }
                String pattern = header.getStringValue("BAYERPAT");
                if (request.sensor() == PreparationRequest.Sensor.CFA && pattern != null) {
                    String mosaic = pattern.toUpperCase(Locale.ROOT).replaceAll("[ _-]", "");
                    if (mosaic.contains("XTRANS") || mosaic.length() == 36 && mosaic.matches("[RGB]+")) {
                        throw new IOException("This is not an ordinary Bayer mosaic. Prepare it with a supported external recipe and import the registered result.");
                    }
                }
                if (request.sensor() == PreparationRequest.Sensor.RGB && dimensions != 3) throw new IOException(role + " is not already debayered RGB.");
                if (request.sensor() != PreparationRequest.Sensor.RGB && dimensions != 2) throw new IOException(role + " has colour channels inconsistent with the chosen sensor mode.");
                if (request.sensor() == PreparationRequest.Sensor.MONO && pattern != null && !pattern.isBlank()) throw new IOException("CFA metadata was found. Choose Colour (Bayer CFA), or use a genuinely monochrome sequence.");
                if (request.sensor() != PreparationRequest.Sensor.CFA) continue;
                if (role != Role.LIGHTS) continue; // Calibration stays in the sensor domain and is never debayered independently.
                if (request.bayer() != PreparationRequest.Bayer.METADATA) pattern = request.bayer().name();
                if (pattern == null || !Set.of("RGGB", "BGGR", "GRBG", "GBRG").contains(pattern.strip())) throw new IOException("The Bayer pattern could not be resolved. Choose an explicit pattern for this sequence.");
                pattern = pattern.strip();
                String row = request.rowOrder() == PreparationRequest.RowOrder.METADATA ? header.getStringValue("ROWORDER")
                        : request.rowOrder() == PreparationRequest.RowOrder.TOP_DOWN ? "TOP-DOWN" : "BOTTOM-UP";
                // Siril's RAW conversion supplies its effective orientation; FITS inputs need an explicit value when absent.
                if (row == null || !(row.equals("TOP-DOWN") || row.equals("BOTTOM-UP"))) throw new IOException("CFA row order is missing. Select Top-down or Bottom-up for this sequence.");
                int offsetX = request.bayer() == PreparationRequest.Bayer.METADATA ? header.getIntValue("XBAYROFF", 0) : request.offsetX();
                int offsetY = request.bayer() == PreparationRequest.Bayer.METADATA ? header.getIntValue("YBAYROFF", 0) : request.offsetY();
                if ((offsetX & ~1) != 0 || (offsetY & ~1) != 0) throw new IOException("CFA offsets must be 0 or 1 pixel in the initial preparation recipe.");
                String signature = pattern + "/" + row + "/" + offsetX + "/" + offsetY;
                if (first != null && !first.equals(signature)) throw new IOException("CFA metadata differs among the selected " + role + " frames.");
                first = signature;
                if (request.bayer() != PreparationRequest.Bayer.METADATA || request.rowOrder() != PreparationRequest.RowOrder.METADATA) {
                    image.getKernel();
                    header.addValue("BAYERPAT", pattern, "Observer-selected Bayer interpretation");
                    header.addValue("ROWORDER", row, "Effective CFA row order");
                    header.addValue("XBAYROFF", offsetX, "CFA X offset in pixels");
                    header.addValue("YBAYROFF", offsetY, "CFA Y offset in pixels");
                    header.deleteKey("CHECKSUM"); header.deleteKey("DATASUM");
                    fits.close();
                    try (Fits rewritten = new Fits()) {
                        rewritten.addHDU(image);
                        StagedFile.write(path.toFile(), staged -> rewritten.write(staged.toFile()));
                    }
                }
            }
        }
        return first == null ? null : first.split("/")[0];
    }

    /** Rejects contradictions that are known from FITS metadata; unknown RAW facts are still delegated to the tool. */
    static void validateCalibrationHeaders(Map<Role, Header> headers) throws IOException {
        Header light = headers.get(Role.LIGHTS);
        if (light != null) for (Role role : new Role[]{Role.DARKS, Role.FLATS, Role.BIASES, Role.DARK_FLATS}) {
            Header calibration = headers.get(role);
            if (calibration == null) continue;
            for (String key : List.of("NAXIS1", "NAXIS2", "XBINNING", "YBINNING")) {
                int expected = light.getIntValue(key, 0);
                int actual = calibration.getIntValue(key, 0);
                if (expected > 0 && actual > 0 && expected != actual) throw new IOException(role + " does not match the light frames' " + key + ".");
            }
            if (role == Role.DARKS) requireExposure(light, calibration, "Dark/light calibration");
            if (role == Role.FLATS && light.getStringValue("FILTER") != null && calibration.getStringValue("FILTER") != null
                    && !light.getStringValue("FILTER").equals(calibration.getStringValue("FILTER"))) {
                throw new IOException("The flat filter does not match the selected lights.");
            }
        }
        if (headers.containsKey(Role.FLATS) && headers.containsKey(Role.DARK_FLATS)) {
            requireExposure(headers.get(Role.FLATS), headers.get(Role.DARK_FLATS), "Dark-flat calibration");
        }
    }

    /** Known exposure durations must agree when optimization is disabled; missing values remain explicit unknowns. */
    private static void requireExposure(Header first, Header second, String context) throws IOException {
        double expected = first.getDoubleValue("EXPTIME", first.getDoubleValue("EXPOSURE", Double.NaN));
        double actual = second.getDoubleValue("EXPTIME", second.getDoubleValue("EXPOSURE", Double.NaN));
        if (Double.isFinite(expected) && Double.isFinite(actual) && Math.abs(expected - actual) > 0.0005) {
            throw new IOException(context + " has different exposure durations. Choose matching inputs; dark optimization is disabled.");
        }
    }

    /** Associates retained numeric source identities without closing gaps left by registration exclusions. */
    static List<PreparedSequenceWriter.Frame> associate(List<Path> sources, List<Path> metadata, List<Path> registered) throws Exception {
        if (metadata.size() != sources.size()) throw new IOException("Source metadata does not match the selected lights.");
        if (registered.size() < 3) throw new IOException("Registration kept " + registered.size() + " of " + sources.size()
                + " lights; at least three registered exposures are required. See preparation.log.");
        Pattern identity = Pattern.compile(".*_(\\d+)\\.(?:fit|fits|fts)$", Pattern.CASE_INSENSITIVE);
        Map<Integer, Path> outputs = new HashMap<>();
        for (Path path : registered) {
            Matcher match = identity.matcher(path.getFileName().toString());
            if (!match.matches()) throw new IOException("Cannot associate an external output with its source: " + path.getFileName());
            int index = Integer.parseInt(match.group(1)) - 1;
            if (index < 0 || index >= sources.size() || outputs.put(index, path) != null) throw new IOException("Ambiguous registered exposure identity.");
        }
        List<PreparedSequenceWriter.Frame> result = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            Path expected = metadata.get(i);
            Path output = outputs.get(i);
            if (output == null) continue;
            verifyMetadata(expected, output);
            result.add(new PreparedSequenceWriter.Frame(sources.get(i), expected, output));
        }
        return List.copyOf(result);
    }

    /** Checks the maintainer's site-coordinate invariant before exact source metadata is restored in final outputs. */
    private static void verifyMetadata(Path source, Path output) throws Exception {
        Header expected = SirilHeaderCorrections.imageHeader(source);
        Header actual = SirilHeaderCorrections.imageHeader(output);
        for (String key : List.of("SITELAT", "SITELONG")) {
            if (expected.containsKey(key) && (!actual.containsKey(key)
                    || Math.abs(expected.getDoubleValue(key) - actual.getDoubleValue(key)) > 1e-8)) {
                throw new IOException("Siril did not preserve " + key + " metadata in a registered frame.");
            }
        }
        for (String key : List.of("DATE-OBS", "EXPTIME")) {
            if (expected.containsKey(key) && !actual.containsKey(key)) throw new IOException("Siril removed " + key + " metadata from a registered frame.");
        }
        if (expected.containsKey("EXPTIME") && Math.abs(expected.getDoubleValue("EXPTIME") - actual.getDoubleValue("EXPTIME")) > 0.000001) {
            throw new IOException("Siril changed an exposure duration.");
        }
        Map<String, String> sourceTime = new HashMap<>();
        Map<String, String> resultTime = new HashMap<>();
        for (String key : List.of("DATE-OBS", "TIME-OBS")) {
            if (expected.containsKey(key)) sourceTime.put(key, expected.getStringValue(key));
            if (actual.containsKey(key)) resultTime.put(key, actual.getStringValue(key));
        }
        long before = new eu.startales.spacepixels.fits.FitsFileInformation("", "source", true, 1, 1, sourceTime).getObservationTimestamp();
        long after = new eu.startales.spacepixels.fits.FitsFileInformation("", "output", true, 1, 1, resultTime).getObservationTimestamp();
        if (before > 0 && (after <= 0 || Math.abs(before - after) > 1000)) throw new IOException("Siril changed or removed an observation time.");
    }

    /** Runs one generated script against a private settings file and requires Siril's explicit success marker. */
    private static void runSiril(Path executable, Path run, String name, String script, Path log, BooleanSupplier cancelled, Consumer<String> details) throws Exception {
        Path file = run.resolve(name);
        Files.writeString(file, script);
        details.accept("\n=== Siril: " + name + " ===\n");
        PreparationProcess.Result result = PreparationProcess.run(List.of(executable.toString(), "-i", run.resolve("siril.cfg").toString(),
                "-d", run.toString(), "-s", file.toString()), run, environment(), log, Duration.ofHours(24), cancelled, batch -> {
                    String text = batch.lines().filter(line -> !line.equals("T") && !line.matches("\\d+: running command .*"))
                            .map(line -> line.replaceFirst("^(?:log|progress):\\s*", ""))
                            .collect(java.util.stream.Collectors.joining("\n"));
                    if (!text.isBlank()) details.accept(text + "\n");
                });
        if (result.exitCode() != 0 || !result.output().contains("Script execution finished successfully")) throw new IOException("Siril failed while running " + name + ". See preparation.log.");
    }

    /** Lists only individual uncompressed FITS outputs with the requested exact sequence prefix. */
    private static List<Path> sequence(Path folder, String prefix) throws IOException {
        try (var files = Files.list(folder)) {
            return files.filter(Files::isRegularFile).filter(FramePreparationService::isFits)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .sorted(java.util.Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
    }

    /** FITS detection includes compressed images for source copying; generated sequences are explicitly uncompressed. */
    private static boolean isFits(Path file) {
        String extension = PreparationInputs.extension(file);
        return extension != null && Set.of("fit", "fits", "fts", "fz", "fits.fz", "fit.fz", "fts.fz").contains(extension);
    }

    /** Bounds subprocess parallelism independently of any saved external-application preferences. */
    private static Map<String, String> environment() {
        return Map.of("OMP_NUM_THREADS", Integer.toString(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()))), "LC_ALL", "C", "LANG", "C");
    }

    /** Publishes a complete local provenance document atomically; failed runs never receive COMPLETED status. */
    private static void writeManifest(Path run, Map<String, Object> manifest) throws Exception {
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(manifest);
        StagedFile.write(run.resolve("preparation.json").toFile(), path -> Files.writeString(path, json));
    }

    /** Observes cancellation while copying and between subprocess phases. */
    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("Preparation cancelled.");
    }
}
