# SpacePixels
 Image preparation tool for supporting object detection in astronomical images (FITS). 
 
 The tool has the following capabilities
 - import a set of aligned images (output from stacking tool, for example Deep Sky Stacker or other) 
 - Batch stretch with custom nonlinear stretch algorithms
 - Batch convert to monochrome 
 - Plate solve wither using ASTAP locally or Astrometry.net (online) 
 - Apply a plate solution to the FITS header of a set of images
 - Stretch and blink a set of FITS images 

The software is currently still in alpha version and is intended to be a support software for preparing the images (FITS header, stretch, convert to monochrome) for object detection and for manual object detection (blink). The software was tested with the A-Track object detection software succesfully (https://github.com/akdeniz-uzay/A-Track ) 
  
## Running
 ```
 gradlew run
 ```
## building and zipping
 ```
 gradlew distZip
 ```
 
