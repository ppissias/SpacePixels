/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */

package eu.startales.spacepixels.tools;

import nom.tam.fits.*;
import nom.tam.util.Cursor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Command Line Tool to inject artificial moving stars into a sequence of aligned FITS files.
 * Useful for generating ground-truth test data to validate transient detection algorithms.
 */
public class ArtificialStarInjector {

    private static class ArtificialStar {
        double startX, startY;
        double angleRad;
        double totalMovementPx;
        double peakAdu;
        double sigma; // Gaussian spread (optical blur)

        public ArtificialStar(double startX, double startY, double angleRad, double totalMovementPx, double peakAdu, double sigma) {
            this.startX = startX;
            this.startY = startY;
            this.angleRad = angleRad;
            this.totalMovementPx = totalMovementPx;
            this.peakAdu = peakAdu;
            this.sigma = sigma;
        }
    }

    public static void main(String[] args) {
        if (args.length < 5) {
            System.out.println("==================================================================");
            System.out.println("  SpacePixels - Artificial Star Injector");
            System.out.println("==================================================================");
            System.out.println("Usage:");
            System.out.println("  java ArtificialStarInjector <directory> <num_stars> <total_pixels_moved> <peak_brightness_adu> <star_fwhm_pixels>");
            System.out.println("\nExample:");
            System.out.println("  java ArtificialStarInjector C:\\astro\\m101 15 10.0 4500 4.0");
            System.out.println("    (Injects 15 stars into the sequence, each moving a total of 10 pixels");
            System.out.println("     across the entire sequence, with a peak added brightness of 4500 ADU,");
            System.out.println("     and a star size (FWHM) of 4.0 pixels)");
            return;
        }

        File inputDir = new File(args[0]);
        int numStars = Integer.parseInt(args[1]);
        double totalMovement = Double.parseDouble(args[2]);
        double peakBrightness = Double.parseDouble(args[3]);
        double fwhmPixels = Double.parseDouble(args[4]);
        
        // Convert physical pixel size (FWHM) to Gaussian mathematical Sigma
        double opticalSigma = fwhmPixels / 2.354820045;

        if (!inputDir.exists() || !inputDir.isDirectory()) {
            System.err.println("Error: The provided path is not a valid directory.");
            return;
        }

        try {
            processDirectory(inputDir, numStars, totalMovement, peakBrightness, opticalSigma);
        } catch (Exception e) {
            System.err.println("Fatal Error during processing:");
            e.printStackTrace();
        }
    }

    private static void processDirectory(File dir, int numStars, double totalMovement, double peakBrightness, double opticalSigma) throws Exception {
        // 1. Find and sort FITS files
        File[] fitsFiles = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".fit") || lower.endsWith(".fits") || lower.endsWith(".fts");
        });

        if (fitsFiles == null || fitsFiles.length == 0) {
            System.out.println("No FITS files found in directory: " + dir.getAbsolutePath());
            return;
        }

        // Sort alphabetically to maintain sequential order
        Arrays.sort(fitsFiles, (a, b) -> a.getName().compareTo(b.getName()));
        int numFrames = fitsFiles.length;

        System.out.println("Found " + numFrames + " FITS files.");

        // 2. Read the first frame to get dimensions
        int width, height;
        try (Fits firstFits = new Fits(fitsFiles[0])) {
            BasicHDU<?> hdu = firstFits.getHDU(0);
            int[] axes = hdu.getAxes();
            if (axes.length != 2) {
                throw new IllegalArgumentException("Expected 2D monochrome 16-bit FITS files. Found " + axes.length + " axes.");
            }
            height = axes[0];
            width = axes[1];
        }

        // 3. Generate random trajectories for the artificial stars
        Random random = new Random();
        List<ArtificialStar> targets = new ArrayList<>();

        System.out.println("Generating " + numStars + " random trajectories...");
        for (int i = 0; i < numStars; i++) {
            // Keep them slightly away from the absolute edges
            double startX = 50 + (random.nextDouble() * (width - 100));
            double startY = 50 + (random.nextDouble() * (height - 100));
            double angle = random.nextDouble() * 2 * Math.PI; // Full 360 degree random angle
            
            // Add slight random variance to the brightness (± 10%) so they don't look completely identical
            double starPeak = peakBrightness * (0.9 + (random.nextDouble() * 0.2));

            targets.add(new ArtificialStar(startX, startY, angle, totalMovement, starPeak, opticalSigma));
        }

        // 4. Create output directory
        File outDir = new File(dir, "injected_stars");
        if (!outDir.exists()) outDir.mkdirs();

        // 5. Process sequence frame by frame
        for (int i = 0; i < numFrames; i++) {
            File inFile = fitsFiles[i];
            System.out.printf("Processing frame [%d/%d]: %s\n", (i + 1), numFrames, inFile.getName());

            try (Fits fits = new Fits(inFile)) {
                BasicHDU<?> hdu = fits.getHDU(0);
                Object kernel = hdu.getKernel();

                if (!(kernel instanceof short[][])) {
                    throw new IllegalArgumentException("File is not 16-bit monochrome (short[][]): " + inFile.getName());
                }
                short[][] data = (short[][]) kernel;

                // Calculate temporal fraction (0.0 at first frame, 1.0 at last frame)
                double timeFraction = numFrames > 1 ? (double) i / (numFrames - 1) : 0.0;

                // Draw all targets
                int starIndex = 1;
                for (ArtificialStar star : targets) {
                    double currentX = star.startX + (Math.cos(star.angleRad) * star.totalMovementPx * timeFraction);
                    double currentY = star.startY + (Math.sin(star.angleRad) * star.totalMovementPx * timeFraction);
                    
                    int pixelCount = drawGaussianStar(data, currentX, currentY, star.peakAdu, star.sigma);
                    System.out.printf("    -> Injecting Star %d at X: %.2f, Y: %.2f (Footprint: %d px)\n", starIndex, currentX, currentY, pixelCount);
                    starIndex++;
                }

                // Create new FITS and copy headers
                Fits outFits = new Fits();
                BasicHDU<?> newHdu = FitsFactory.hduFactory(data);
                outFits.addHDU(newHdu);

                Cursor<String, HeaderCard> originalCursor = hdu.getHeader().iterator();
                Header newHeader = newHdu.getHeader();
                while (originalCursor.hasNext()) {
                    HeaderCard card = originalCursor.next();
                    if (!newHeader.containsKey(card.getKey())) {
                        newHeader.addLine(card);
                    }
                }

                File outFile = new File(outDir, inFile.getName());
                outFits.write(outFile);
            }
        }
        System.out.println("\nSuccessfully injected artificial stars! Output saved to: " + outDir.getAbsolutePath());
    }

    private static int drawGaussianStar(short[][] data, double cx, double cy, double peakAdu, double sigma) {
        int height = data.length;
        int width = data[0].length;
        int radius = (int) Math.ceil(sigma * 4); // Draw out to 4-sigma to capture the faint halo
        int pixelCount = 0;

        for (int y = (int) Math.floor(cy - radius); y <= (int) Math.ceil(cy + radius); y++) {
            for (int x = (int) Math.floor(cx - radius); x <= (int) Math.ceil(cx + radius); x++) {
                if (y >= 0 && y < height && x >= 0 && x < width) {
                    double distSq = (x - cx) * (x - cx) + (y - cy) * (y - cy);
                    double intensity = peakAdu * Math.exp(-distSq / (2 * sigma * sigma));
                    int addedAdu = (int) Math.round(intensity);
                    
                    if (addedAdu > 0) {
                        int currentUnsigned = data[y][x] + 32768;
                        int newUnsigned = Math.min(65535, currentUnsigned + addedAdu);
                        data[y][x] = (short) (newUnsigned - 32768);
                        pixelCount++;
                    }
                }
            }
        }
        return pixelCount;
    }
}