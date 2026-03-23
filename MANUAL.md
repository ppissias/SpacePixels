# SpacePixels User Manual

## Table of Contents
1. [Introduction](#introduction)
2. [Getting Started](#getting-started)
   - [System Requirements](#system-requirements)
   - [Installation and Running](#installation-and-running)
3. [User Interface Overview](#user-interface-overview)
4. [File Management](#file-management)
   - [Importing FITS Files](#importing-fits-files)
   - [File Information Table](#file-information-table)
5. [Image Processing and Visualization](#image-processing-and-visualization)
   - [Image Previews](#image-previews)
   - [Stretching and Contrast Enhancement](#stretching-and-contrast-enhancement)
   - [Batch Processing](#batch-processing)
6. [Blinking and Image Inspection](#blinking-and-image-inspection)
   - [Blink Sequence](#blink-sequence)
   - [Manual Transient Inspection](#manual-transient-inspection)
7. [Automated Transient Detection](#automated-transient-detection)
   - [Standard Detection Pipeline](#standard-detection-pipeline)
   - [Deep Stack Anomalies (Ultra-Slow Movers)](#deep-stack-anomalies-ultra-slow-movers)
   - [Iterative Detection Pipeline](#iterative-detection-pipeline)
   - [Detection Configuration \u0026 Auto-Tune](#detection-configuration--auto-tune)
   - [Understanding the HTML Report](#understanding-the-html-report)
8. [Astrometry and Plate Solving](#astrometry-and-plate-solving)
   - [ASTAP Integration](#astap-integration)
   - [Astrometry.net Integration](#astrometrynet-integration)

---

## 1. Introduction

SpacePixels is a Java-based application designed for astronomers and astrophotographers to process sequences of FITS images, with a primary focus on the automated detection of transients (like asteroids, comets, or satellites) and manual visual inspection through blinking sequences. 

The software utilizes a custom tracking engine (`JTransient`) to find moving objects against a stationary starry background, generate detailed reports, and output diagnostic image sequences.

## 2. Getting Started

### System Requirements
*   **Operating System**: Windows, macOS, or Linux.
*   **Java Runtime**: Java 8 or higher (Java 11+ recommended).
*   **Memory**: A minimum of 4GB RAM is recommended. Large FITS sequences may require significantly more memory.
*   **Optional Software**: ASTAP (for local plate-solving).

### Installation and Running
SpacePixels is distributed as a Gradle project. To build and run the application:
1. Ensure Java is installed and in your system\u0027s PATH.
2. Open a terminal/command prompt in the project root directory.
3. Run the application using the Gradle wrapper:
   *   **Windows**: `gradlew run`
   *   **macOS/Linux**: `./gradlew run`

## 3. User Interface Overview

The main application window is divided into a few key areas:
*   **Menu Bar**: Top menu for loading folders, setting configurations, and launching detections.
*   **Table Area**: A large table on the left (or center) that displays the currently loaded sequence of FITS files, including metadata like image dimensions, color space, and timestamps.
*   **Preview Area**: On the right side, an image visualizer displays the currently selected FITS file.
*   **Stretch Controls**: Below the table/preview, a panel containing controls to adjust the visual stretching (brightness/contrast) of the displayed images.

## 4. File Management

### Importing FITS Files
To begin working, you must load a directory containing a sequence of aligned FITS files.
1. Click **File -\u003e Load Directory**.
2. Select a folder. The application will scan for files ending in `.fits`, `.fit`, `.fts` (and their compressed `.fz` variants).
3. **Format Enforcement**: SpacePixels expects all files in a sequence to have the exact same dimensions and color space (e.g., all 16-bit Mono, or all 16-bit Color).
4. **32-Bit Conversion**: If 32-bit images are detected, the application will prompt you to automatically standardize them down to 16-bit, which is required by the detection engine.

### File Information Table
Once loaded, the table displays metadata for each file:
*   **Filename**: The name of the FITS file.
*   **Color**: Indicates whether the image is Monochrome or Color.
*   **Date & Time**: The extracted `DATE-OBS` and `TIME-OBS` combined. Used by the engine for precise time-based kinematics.
*   **Duration**: The exposure time extracted from the `EXPTIME` or `EXPOSURE` header.
*   **Location**: The observatory location, if available in the headers (`SITELAT`, `SITELONG`).
*   **Solved**: Indicates if the image has been successfully plate-solved (either via an existing WCS header or a cached `.ini` result).

## 5. Image Processing and Visualization

### Stretching and Contrast Enhancement
Astronomical images are mostly dark and require \"stretching\" to reveal faint details.
*   **Stretch Factor**: A slider/spinner to increase the intensity of the stretch.
*   **Iterations**: How many passes the stretch algorithm should perform.
*   **Algorithm**:
    *   *Enhance Low*: Brings out faint background details.
    *   *Enhance High*: Focuses on brighter structures.
    *   *Extreme*: Very aggressive stretch, useful for spotting incredibly faint transients or noise patterns.
*   **Apply**: Updates the preview immediately. 
*   **Generate Full Size**: Opens a new window displaying the stretched image at its full 1:1 resolution.

### Batch Processing
Under the **Tools** menu, you can apply transformations to the entire loaded sequence:
*   **Batch Convert to Monochrome**: Extracts the luminance from color FITS files and saves a new sequence of mono images. The detection engine requires mono files.
*   **Batch Stretch**: Applies your current stretch settings and saves new `.fit` files with the stretched data baked in.

## 6. Blinking and Image Inspection

### Blink Sequence
\"Blinking\" is the traditional astronomical method of finding moving objects by rapidly swapping between aligned frames.
1. Select the frames you want to blink in the table (Shift-Click or Ctrl-Click for multiple).
2. Click the **Blink Selected** button (or use the menu).
3. A new window will appear playing the frames as an animation.

### Manual Transient Inspection
If you want to quickly inspect the raw, purified transients extracted by the engine before running the full tracking pipeline:
1. Ensure your sequence is loaded.
2. Click the **Manual Transient Inspection** button on the main panel.
3. The engine will extract all valid transients from every frame using the master background stack.
4. A new resizable window will open displaying the exact pixel footprints of the transients overlaid on the image.
5. Use the **Arrow Keys (Left/Right/Up/Down)** to rapidly cycle through the frames and visually trace moving targets or identify noise.

## 7. Automated Transient Detection

SpacePixels automatically identifies asteroids, comets, satellites, and high-energy anomalies across a sequence of images.

### Standard Detection Pipeline
The standard pipeline creates a median master stack (to identify stationary stars) and then tracks objects that move dynamically across the frames.
1. Click the **Detect Moving Objects (Entire Set)** button.
2. A progress dialog will appear showing the multi-threaded extraction, masking, and tracking phases.
3. Upon completion, SpacePixels generates a comprehensive interactive **HTML Report** in a new `detections_YYYYMMDD_HHMMSS` folder inside your working directory.

### Deep Stack Anomalies (Ultra-Slow Movers)
SpacePixels features a unique scanning phase that examines the stationary Master Median Stack. If an object is moving extremely slowly, it will not be rejected by standard median stacking, but will instead merge into a highly elongated streak. SpacePixels applies physical morphological shape filters to isolate these continuous "capsule" shapes and presents them as Deep Stack Anomalies.

### Iterative Detection Pipeline
This option is useful for large datasets. For very slow-moving objects or very long multi-hour sequences, a standard run might fail because the object doesn't move far enough between consecutive exposures to be distinguished from atmospheric star jitter.
1. Click the **Detect Slow Movers (Iterative)** button.
2. The system will prompt you for a maximum frame limit (useful for massive datasets).
3. The engine creates a single, highly integrated global master stack across the maximum timeline. It then runs multiple time-spaced passes (e.g., using 5 evenly spaced frames, then 10, then 15) to artificially increase the temporal baseline and make slow objects appear to move faster.
4. A master index HTML report is generated linking all the sub-iterations cleanly together.

### Detection Configuration 
The parameters driving the detection engine can be heavily customized via the **Detection Settings** tab.
1. **Basic Tuning**: The most frequently adjusted settings, including your primary Detection Sigma, Min Pixels, Streak constraints, and Export preferences.
2. **Advanced Extractor**: Settings for building the Master Map and extracting Deep Stack Anomalies, as well as background modeling (Sigma Clipping).
3. **Advanced Kinematics**: Deep mathematical thresholds for trajectory prediction, geometric shape matching, and Time-Based Velocity tracking.
4. **Quality Control**: Strict Median Absolute Deviation (MAD) limits to reject bad frames caused by clouds, wind, or tracking errors. Also houses the Auto-Tuner scoring weights.
5. **Advanced Visualization**: Fine-tune the sizes of the bounding boxes, masks, and crop padding generated in the HTML report.

**Auto-Tune**: If you are unsure what values to use, click **Auto-Tune Settings**. The engine sweeps through mathematical combinations to find the optimal signal-to-noise ratio for your specific dataset, balancing sensitivity and noise rejection. You can use **Preview Detection Settings** to visually verify the exact pixel footprints the current settings will extract.

### Understanding the HTML Report
The final output is an interactive HTML dashboard containing:
*   **Pipeline Metrics**: Execution times, frames accepted/rejected, and extraction counts.
*   **Master Shield & Veto Mask**: Visual proof of the generated background protection map (painted in vivid red).
*   **Dither & Sensor Drift Diagnostics**: A 2D trajectory map showing how the frame mathematically shifted across the night (useful for identifying sensor dust and hot-pixel tracks).
*   **Target Visualizations**:
    *   **Moving Targets**: Cropped, animated GIFs centered on the star-field and the object, alongside geometric shape maps. Time-based tracking verifications are badged.
    *   **High-Energy Anomalies**: Single-frame bright flashes shown with context frames (Before/During/After).
    *   **Deep Stack Anomalies**: Crops of the Master and Slow-Mover stacks alongside a sampled animation of the anomaly.
*   **Global Transient Map**: A single image mapping all raw transients from all frames, color-coded by time (Blue to Red) against a darkened master background.
*   **Global Trajectory Map**: A bird's-eye view plotting all connected tracks and single streaks across the full image field.

## 8. Astrometry and Plate Solving

SpacePixels can determine the exact celestial coordinates (Right Ascension and Declination) of your images. This process is called Plate Solving.

### ASTAP Integration
ASTAP is a fast, local plate solver.
1. Ensure ASTAP is installed on your computer.
2. Go to **Configuration Settings**.
3. Set the path to the `astap` executable (e.g., `C:\\Program Files\\astap\\astap.exe`).
4. Select a file in the main table and click **Solve Image**. SpacePixels will use ASTAP to compute the coordinates and update the FITS headers.

### Astrometry.net Integration
If ASTAP is not available or fails, SpacePixels can fall back to the online Astrometry.net service.
*   This requires an internet connection.
*   It is generally slower than local solving via ASTAP.
*   The results are pulled down and saved alongside the image in a `.ini` file and update in the FITS headers.

**Updating FITS Headers**: When an image is successfully solved (either via ASTAP or Astrometry.net), SpacePixels will prompt you to permanently inject the WCS coordinate solutions back into the original FITS header. This makes your files universally compatible with other astronomical software without relying on external `.ini` files!