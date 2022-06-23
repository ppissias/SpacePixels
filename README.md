# SpacePixels
 Image preparation tool for supporting object detection in astronomical images (FITS). 
 The intention for this software is to be an easy-to-use tool that typical amateur astronomers can use to detect moving objects in their astronomical images. 
 In the current phase, the system is preparing a set of images and relies on other software for automatic object detection, but it is planned to extend the system for doing also object detection. 
 
 The tool currently has the following capabilities
 - import a set of aligned images (output from stacking tool, for example Deep Sky Stacker or other) 
 - Batch stretch with custom nonlinear stretch algorithms
 - Batch convert to monochrome 
 - Plate solve wither using ASTAP locally or Astrometry.net (online) 
 - Apply a plate solution to the FITS header of a set of images
 - Stretch and blink a set of FITS images 

The software is currently still in alpha version. 
The software was tested with the A-Track object detection software succesfully (https://github.com/akdeniz-uzay/A-Track ).
  
## Running
 ```
 gradlew run
 ```
## building and zipping
 ```
 gradlew distZip
 ```
 
