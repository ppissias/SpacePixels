/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.ppissias.jtransient.config.DetectionConfig;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Reads and writes SpacePixels detection-profile JSON while keeping compatibility with JTransient's
 * flat {@link DetectionConfig} payload and older SpacePixels profile files.
 */
public final class SpacePixelsDetectionProfileIO {

    public static final String DEFAULT_FILENAME = "spacepixels_detection_profile.json";
    public static final String LEGACY_FILENAME = "spacepixels_jtransient.json";

    private static final String AUTO_TUNE_MAX_CANDIDATE_FRAMES_FIELD = "autoTuneMaxCandidateFrames";
    private static final String QUALITY_GROW_SIGMA_MULTIPLIER_FIELD = "qualityGrowSigmaMultiplier";
    private static final String QUALITY_MAX_ELONGATION_FOR_FWHM_FIELD = "qualityMaxElongationForFwhm";
    private static final String LEGACY_GROW_SIGMA_MULTIPLIER_FIELD = "growSigmaMultiplier";
    private static final String LEGACY_MAX_ELONGATION_FOR_FWHM_FIELD = "maxElongationForFwhm";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile int activeAutoTuneMaxCandidateFrames = SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES;

    private SpacePixelsDetectionProfileIO() {
    }

    public static SpacePixelsDetectionProfile load(Reader reader) throws IOException {
        JsonObject root = readRoot(reader);
        migrateLegacyQualityFields(root);
        DetectionConfig detectionConfig = GSON.fromJson(root, DetectionConfig.class);
        if (detectionConfig == null) {
            throw new IOException("Configuration file did not contain a DetectionConfig object.");
        }

        int autoTuneMaxCandidateFrames = SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES;
        JsonElement autoTuneElement = root.get(AUTO_TUNE_MAX_CANDIDATE_FRAMES_FIELD);
        if (autoTuneElement != null && !autoTuneElement.isJsonNull()) {
            try {
                autoTuneMaxCandidateFrames = SpacePixelsDetectionProfile.normalizeAutoTuneMaxCandidateFrames(autoTuneElement.getAsInt());
            } catch (Exception ex) {
                throw new IOException("Invalid value for " + AUTO_TUNE_MAX_CANDIDATE_FRAMES_FIELD + ".", ex);
            }
        }

        setActiveAutoTuneMaxCandidateFrames(autoTuneMaxCandidateFrames);
        return new SpacePixelsDetectionProfile(detectionConfig, autoTuneMaxCandidateFrames);
    }

    public static boolean needsMigration(Reader reader) throws IOException {
        JsonObject saved = readRoot(reader);
        JsonObject current = GSON.toJsonTree(new DetectionConfig()).getAsJsonObject();
        current.addProperty(AUTO_TUNE_MAX_CANDIDATE_FRAMES_FIELD, SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES);
        return !saved.keySet().equals(current.keySet());
    }

    public static File migrate(File sourceFile, File targetFile, SpacePixelsDetectionProfile detectionProfile) throws IOException {
        Path source = sourceFile.toPath().toAbsolutePath().normalize();
        Path target = targetFile.toPath().toAbsolutePath().normalize();
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                write(writer, detectionProfile);
            }

            if (!source.equals(target)) {
                Files.move(temporary, target);
                return sourceFile;
            }

            Path backup = Files.createTempFile(source.getParent(), source.getFileName().toString() + ".backup-", ".json");
            try {
                Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                Files.deleteIfExists(backup);
                throw ex;
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            return backup.toFile();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void write(Writer writer, SpacePixelsDetectionProfile detectionProfile) {
        write(writer, detectionProfile.getDetectionConfig(), detectionProfile.getAutoTuneMaxCandidateFrames());
    }

    public static void write(Writer writer, DetectionConfig detectionConfig, int autoTuneMaxCandidateFrames) {
        JsonObject root = GSON.toJsonTree(detectionConfig).getAsJsonObject();
        root.addProperty(
                AUTO_TUNE_MAX_CANDIDATE_FRAMES_FIELD,
                SpacePixelsDetectionProfile.normalizeAutoTuneMaxCandidateFrames(autoTuneMaxCandidateFrames));
        GSON.toJson(root, writer);
    }

    public static int getActiveAutoTuneMaxCandidateFrames() {
        return activeAutoTuneMaxCandidateFrames;
    }

    public static void setActiveAutoTuneMaxCandidateFrames(int autoTuneMaxCandidateFrames) {
        activeAutoTuneMaxCandidateFrames = SpacePixelsDetectionProfile.normalizeAutoTuneMaxCandidateFrames(autoTuneMaxCandidateFrames);
    }

    private static JsonObject readRoot(Reader reader) throws IOException {
        JsonElement rootElement = JsonParser.parseReader(reader);
        if (rootElement == null || rootElement.isJsonNull() || !rootElement.isJsonObject()) {
            throw new IOException("Configuration file did not contain a JSON object.");
        }
        return rootElement.getAsJsonObject();
    }

    private static void migrateLegacyQualityFields(JsonObject root) {
        if (!root.has(QUALITY_GROW_SIGMA_MULTIPLIER_FIELD)) {
            JsonElement growSigma = root.get(LEGACY_GROW_SIGMA_MULTIPLIER_FIELD);
            if (growSigma != null && !growSigma.isJsonNull()) {
                root.add(QUALITY_GROW_SIGMA_MULTIPLIER_FIELD, growSigma.deepCopy());
            }
        }

        if (!root.has(QUALITY_MAX_ELONGATION_FOR_FWHM_FIELD)) {
            JsonElement legacyMaxElongation = root.get(LEGACY_MAX_ELONGATION_FOR_FWHM_FIELD);
            if (legacyMaxElongation != null && !legacyMaxElongation.isJsonNull()) {
                root.add(QUALITY_MAX_ELONGATION_FOR_FWHM_FIELD, legacyMaxElongation.deepCopy());
            }
        }
        root.remove(LEGACY_MAX_ELONGATION_FOR_FWHM_FIELD);
    }
}
