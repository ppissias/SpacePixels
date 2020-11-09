/**
 * 
 */
package spv.util;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import javax.swing.JFrame;
import javax.swing.JOptionPane;

import org.apache.commons.configuration2.FileBasedConfiguration;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.apache.commons.configuration2.ex.ConfigurationException;

import io.github.ppissias.astrolib.AstrometryDotNet;
import io.github.ppissias.astrolib.PlateSolveResult;
import io.github.ppissias.astrolib.SubmitFileRequest;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.FitsFactory;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;
import nom.tam.util.BufferedDataOutputStream;
import nom.tam.util.Cursor;
import spv.gui.ApplicationWindow;


/**
 * Util class for referencing a bunch of FITS files,
 * interfacing with plate solving utility and finally
 * updating the FITS header files. 
 * 
 * TODO
 * stretch images after mono conversion: values from Average and above: stretch to the helf value between their current value and the MAX value.
 * no nlinear stretch to help detections. 
 * 
 * @author Petros Pissias
 *
 */
public class ImagePreprocessing {

	//fits folder path
	private final File alignedFitsFolderFullPath;

	//executor service
	private final ExecutorService executor = Executors.newFixedThreadPool(1);
	
	//astrometry.net interface
	private final AstrometryDotNet astrometryNetInterface = new AstrometryDotNet();
	
	//config file stuff
	private final SPConfigurationFile configurationFile;
	
	/**
	 * Returns new instance. Each instance is associated with a directory containing the source FITS files
	 * @param alignedFitsFolderFullPath
	 * @return
	 * @throws IOException
	 * @throws FitsException
	 * @throws ConfigurationException
	 */
	public static synchronized ImagePreprocessing getInstance(File alignedFitsFolderFullPath) throws IOException, FitsException, ConfigurationException {
		//first create config file if it does not exist
		
		//get user home if it exists
		String userhome = System.getProperty("user.home");
		if (userhome == null) {
			userhome = "";
		}

		ApplicationWindow.logger.info("Will use config file:"+new File(userhome+"/spacepixelviewer.config").getAbsolutePath());
		SPConfigurationFile configurationFile = new SPConfigurationFile(userhome+"/spacepixelviewer.config");
			
		return new ImagePreprocessing(alignedFitsFolderFullPath, configurationFile);
	}
	

	/**
	 * Internal constructor
	 * @param alignedFitsFolderFullPath
	 * @param configFile
	 * @throws IOException
	 * @throws FitsException
	 */
	private ImagePreprocessing(File alignedFitsFolderFullPath, SPConfigurationFile configFile) throws IOException, FitsException {
		this.alignedFitsFolderFullPath = alignedFitsFolderFullPath;
		this.configurationFile = configFile;		
	}
	
	/**
	 * Returns the FITS file information
	 * @return
	 * @throws FitsException 
	 * @throws IOException 
	 * @throws ConfigurationException 
	 */
	public FitsFileInformation[] getFitsfileInformation() throws IOException, FitsException, ConfigurationException {
		//list of fits files in DIR
		File[] fitsFileInformation = getFitsFilesDetails();
		
		//get FITS objects
		Fits[] fitsFiles = new Fits[fitsFileInformation.length];
		
		//return 
		FitsFileInformation[] ret = new FitsFileInformation[fitsFileInformation.length];
		
		//populate information
		for (int i=0;i<fitsFiles.length;i++) {
			fitsFiles[i] = new Fits(fitsFileInformation[i]);
			
			//get info from FITS file
			int hdu = fitsFiles[i].getNumberOfHDUs();
			

			Header fitsHeader = fitsFiles[i].getHDU(0).getHeader();
			Cursor<String, HeaderCard> iter = fitsHeader.iterator();							 						
			
			//info
			String fpath = fitsFileInformation[i].getAbsolutePath();
			boolean monochromeImage;
			int width;
			int height;
			
			int[] axes = fitsFiles[i].getHDU(0).getAxes();
						
			
			if (axes.length == 2) {
				//mono image
				monochromeImage = true;
				Object kernelData = fitsFiles[i].getHDU(0).getKernel();
				if (kernelData instanceof short[][]) {
					short[][] data = (short[][])fitsFiles[i].getHDU(0).getKernel();
					height=data.length;
					width=data[0].length;					
				} else if (kernelData instanceof int[][]) {
					int[][] data = (int[][])fitsFiles[i].getHDU(0).getKernel();
					height=data.length;
					width=data[0].length;						
				} else if (kernelData instanceof float[][]) {
					float[][] data = (float[][])fitsFiles[i].getHDU(0).getKernel();
					height=data.length;
					width=data[0].length;						
				} else {
					throw new FitsException("Cannot understand file, it has a type="+kernelData.getClass().getName());
				}								
			} else if (axes.length == 3) {
				//color image
				monochromeImage = false;
				Object kernelData = fitsFiles[i].getHDU(0).getKernel();
				if (kernelData instanceof short[][][]) {
					short[][][] data = (short[][][])fitsFiles[i].getHDU(0).getKernel();
					height=data[0].length;
					width=data[0][0].length;						
				} else if (kernelData instanceof int[][][]) {
					int[][][] data = (int[][][])fitsFiles[i].getHDU(0).getKernel();
					height=data[0].length;
					width=data[0][0].length;						
				} else if (kernelData instanceof float[][][]) {
					float[][][] data = (float[][][])fitsFiles[i].getHDU(0).getKernel();
					height=data[0].length;
					width=data[0][0].length;						
				} else {
					throw new FitsException("Cannot understand file, it has a type="+kernelData.getClass().getName());
				}
			
			} else {
				throw new FitsException("Cannot understand file, it has axes length="+axes.length);
			}
			
			//populate return object
			ret[i] = new FitsFileInformation(fpath,fitsFileInformation[i].getName(), monochromeImage, width, height);
			
			while (iter.hasNext()) {
				//fits header
				HeaderCard fitsHeaderCard = iter.next();
				ret[i].getFitsHeader().put(fitsHeaderCard.getKey(), fitsHeaderCard.getValue());
				//ApplicationWindow.logger.info("processing "+fitsHeaderCard.getKey()+" key form fits header");
			}
			
			//read solve info if it exists
			PlateSolveResult previousSolveresult = readSolveResults(fpath);
			if (previousSolveresult != null) { 
				ret[i].setSolveResult(previousSolveresult);
			}
			
			ApplicationWindow.logger.info("populated fits object as "+ret[i]);
		}
		
		closeFitsFiles(fitsFiles);
		
		//return the full list
		return ret;
							
	}


	/**
	 * Returns FITS files in the provided directory
	 * @return ordered by filename 
	 * @throws IOException
	 * @throws FitsException
	 */
	private File[] getFitsFilesDetails() throws IOException, FitsException {
		
		File directory = alignedFitsFolderFullPath;
		if (!directory.isDirectory()) {
			throw new IOException("file:"+directory.getAbsolutePath()+" is not a directory");
		}
		
		List<File> fitsFilesPath = new ArrayList<File>();
		for (File f : directory.listFiles(new FilenameFilter() {

			@Override
			public boolean accept(File dir, String name) {
				//accept ends with
				String[] acceptedFileTypes = {"fits","fit","fts","Fits","Fit","FIT","FTS","Fts","FITS"};
				for (String acceptedFileEnd :acceptedFileTypes) {
					if (name.endsWith(acceptedFileEnd)) {
						return true;
					} 
				}
				
				return false;
			}			
		})) {
			fitsFilesPath.add(f);
		}
		
		if (fitsFilesPath.size() == 0) {
			throw new IOException("No fits files in directory:"+directory.getAbsolutePath());
		}
		
		File[] ret = fitsFilesPath.toArray(new File[] {});

		Arrays.sort(ret, new Comparator<File> () {
			@Override
			public int compare(File arg0, File arg1) {
				return arg0.getAbsolutePath().compareTo(arg1.getAbsolutePath());
			}
			
		});
		
		return ret;
	}	

	/**
	 * Close all fits files. Helper method
	 * @param fitsFiles
	 * @throws IOException
	 */
	private static void closeFitsFiles(Fits[] fitsFiles) throws IOException {
		for (Fits fitsFile : fitsFiles) {
			fitsFile.close();
		}
	}


	
	/**
	 * Tries to plate solve a dedicated image using its index
	 * @param index which image to plate solve
	 * @param astap should astap be used? 
	 * @param astrometry should the online Astromerty.net service be used ? 
	 * @return after the completion of the solve execution the result is returned.
	 * @throws FitsException 
	 * @throws IOException 
	 */
	public Future<PlateSolveResult> solve(String fitsFileFullPath, boolean astap, boolean astrometry) throws FitsException, IOException {
		ApplicationWindow.logger.info("trying to solve image astap="+astap+" astrometry="+astrometry);
		
		if (astap) {
			String astapPath = configurationFile.getProperty("astap");
			if (astapPath != null && (! "".equals(astapPath)) ) {
				File astapPathFile = new File(astapPath);
				if (astapPathFile.exists()) {
					FutureTask<PlateSolveResult> task = ASTAPInterface.solveImage(astapPathFile, fitsFileFullPath);
					
					executor.execute(task);
					return task;
				}
			}
			throw new IOException ("ASTAP executable path is not correct:"+astapPath);
		}		
		else if (astrometry) {			
			try {				
				astrometryNetInterface.login();
				
				SubmitFileRequest typicalParamsRequest = SubmitFileRequest.builder().withPublicly_visible("y").withScale_units("degwidth").withScale_lower(0.1f).withScale_upper(180.0f).withDownsample_factor(2f).withRadius(10f).build();

				Future<PlateSolveResult> solveResult = astrometryNetInterface.customSolve(new File(fitsFileFullPath), typicalParamsRequest);
				
				return solveResult;
				
			} catch (IOException | InterruptedException e) {
				JOptionPane.showMessageDialog(new JFrame(), "Could not solve image with astrometry.net :"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);
			}

		}
		
		return null;
	}
	
	/**
	 * Will apply the WCS FITS header to all images
	 * 		
		 * Sample FITS WCS headers form astrometry.net and astap 
		 *
		 *astap
		 *
		 *SIMPLE  =                    T / file does conform to FITS standard             
		 * BITPIX  =                    8 / number of bits per data pixel                  
		 * NAXIS   =                    0 / number of data axes                            
		 * NAXIS3  =                    3 / length of data axis 3                          
		 * EXTEND  =                    T / FITS dataset may contain extensions            
		 * COMMENT   FITS (Flexible Image Transport System) format is defined in 'Astronomy
		 * COMMENT   and Astrophysics', volume 376, page 359; bibcode: 2001A&A...376..359H 
		 * BZERO   =                32768 / offset data range to that of unsigned short    
		 * BSCALE  =                    1 / default scaling factor                         
		 * EXPTIME =                 120. / Exposure time (in seconds)                     
		 * EXPOSURE=                 120. / Exposure time (in seconds)                     
		 * SOFTWARE= 'DeepSkyStacker 4.1.1'                                                
		 * OBJECT  = 'ic5070  '                                                            
		 * TELESCOP= '        '                                                            
		 * INSTRUME= 'SBIG ST-2K Color Dual CCD Camera' / Camera Model                     
		 * OBSERVER= '        '                                                            
		 * DATE-OBS= '2019-07-23T00:07:23.000' / GMT START OF EXPOSURE                     
		 * CCD-TEMP=     9.78091507038411 / CCD TEMP IN DEGREES C                          
		 * XPIXSZ  =                  7.4 / PIXEL WIDTH IN MICRONS                        
		 * YPIXSZ  =                  7.4 / PIXEL HEIGHT IN MICRONS                        
		 * XBINNING=                    1 / HORIZONTAL BINNING FACTOR                      
		 * YBINNING=                    1 / VERTICAL BINNING FACTOR                        
		 * XORGSUBF=                    0 / SUB_FRAME ORIGIN X_POS                         
		 * YORGSUBF=                    0 / SUB_FRAME ORIGIN Y_POS                         
		 * EGAIN   =                 0.55 / ELECTRONS PER ADU                              
		 * FOCALLEN=                 600. / FOCAL LENGTH IN MM                             
		 * APTDIA  =                   0. / APERTURE DIAMETER IN MM                        
		 * APTAREA =                   0. / APERTURE AREA IN SQ-MM                         
		 * CBLACK  =                    0 / BLACK ADU FOR DISPLAY                          
		 * CWHITE  =                65535 / WHITE ADU FOR DISPLAY                          
		 * PEDESTAL=                 -100 / ADD TO ADU FOR 0-BASE                          
		 * SBSTDVER= 'SBFITSEXT Version 1.0' / SBIG FITS EXTENSIONS VER                    
		 * SWACQUIR= 'Astro Photography Tool - APT v.3.71' / DATA ACQ SOFTWARE             
		 * SWCREATE= 'Astro Photography Tool - APT v.3.71' / IMAGING SOFTWARE              
		 * FILTER  = '        '           / OPTICAL FILTER NAME                            
		 * SNAPSHOT=                    1 / NUMBER IMAGES COADDED                          
		 * DATE    = '2019-07-23'         / GMT DATE WHEN THIS FILE CREATED                
		 * RESMODE =                    0 / RESOLUTION MODE                                
		 * EXPSTATE= '125     '           / EXPOSURE STATE (HEX)                           
		 * RESPONSE=                2000. / CCD RESPONSE FACTOR                            
		 * NOTE    = 'Local time:7/23/2019 at 2:07:23'                                     
		 * TRAKTIME=                   0. / TRACKING EXPOSURE                              
		 * JD      =     2458687.50659722 / JULIAN DATE                                    
		 * SITELAT = '+37 51 54.000'      / THE SITE LATITUDE                              
		 * SITELONG= '+23 45 18.310'      / THE SITE LONGITUDE                             
		 * OBJCTRA = '20 50 49'           / THE RA OF THE IMAGE CENTER                     
		 * OBJCTDEC= '+44 23 51'          / THE DEC OF THE IMAGE CENTER                    
		 * CTYPE1  = 'RA---TAN'           / first parameter RA  ,  projection TANgential   
		 * CTYPE2  = 'DEC--TAN'           / second parameter DEC,  projection TANgential   
		 * CUNIT1  = 'deg     '           / Unit of coordinates                            
		 * CRPIX1  =  7.615000000000E+002 / X of reference pixel                           
		 * CRPIX2  =  5.630000000000E+002 / Y of reference pixel                           
		 * CRVAL1  =  3.127276202297E+002 / RA of reference pixel (deg)                    
		 * CRVAL2  =  4.434473452560E+001 / DEC of reference pixel (deg)                   
		 * CDELT1  =  7.063089252922E-004 / X pixel size (deg)                             
		 * CDELT2  =  7.060478729734E-004 / Y pixel size (deg)                             
		 * CROTA1  =  1.005937259997E+002 / Image twist of X axis        (deg)             
		 * CROTA2  =  1.006185817292E+002 / Image twist of Y axis        (deg)             
		 * CD1_1   = -1.301516297229E-004 / CD matrix to convert (x,y) to (Ra, Dec)        
		 * CD1_2   =  6.940136303228E-004 / CD matrix to convert (x,y) to (Ra, Dec)        
		 * CD2_1   = -6.942138368168E-004 / CD matrix to convert (x,y) to (Ra, Dec)        
		 * CD2_2   = -1.298024647548E-004 / CD matrix to convert (x,y) to (Ra, Dec)        
		 * PLTSOLVD=                    T / ASTAP internal solver                          
		 * COMMENT 6  Solved in 75.4 sec. Offset was 0.055 deg.                            
		 * WARNING = 'Warning scale was inaccurate! Set FOV=0.79d, scale=2.5", FL=601mmStarCOMMENT 
		 * cmdline:"C:\Users\Petros Pissias\Desktop\apps\astro\astap\astap.exe" -f 
		 * COMMENT "C:\Users\Petros Pissias\Documents\00space_ic5070\L_2019-07-23_02-09-30_
		 * COMMENT Bin1x1_120s__10C.reg.fit" -r 360 -z 0 -fov 0 -wcs -annotate             END                                                                                                                                                                                                                                                                                                                             
		 */
		
		/**
		//astrometry.net
		//SIMPLE  =                    T / Standard FITS file                             
		 * BITPIX  =                    8 / ASCII or bytes array                           
		 * NAXIS   =                    0 / Minimal header                                 
		 * EXTEND  =                    T / There may be FITS ext                          
		 * WCSAXES =                    2 / no comment                                     
		 * CTYPE1  = 'RA---TAN-SIP' / TAN (gnomic) projection + SIP distortions            
		 * CTYPE2  = 'DEC--TAN-SIP' / TAN (gnomic) projection + SIP distortions            
		 * EQUINOX =               2000.0 / Equatorial coordinates definition (yr)         
		 * LONPOLE =                180.0 / no comment                                     
		 * LATPOLE =                  0.0 / no comment                                     
		 * CRVAL1  =        312.584785922 / RA  of reference point                         
		 * CRVAL2  =        44.3796425302 / DEC of reference point                         
		 * CRPIX1  =        739.564693451 / X reference pixel                              
		 * CRPIX2  =        412.081944227 / Y reference pixel                              
		 * CUNIT1  = 'deg     ' / X pixel scale units                                      
		 * CUNIT2  = 'deg     ' / Y pixel scale units                                      
		 * CD1_1   =   -0.000129378038268 / Transformation matrix                          
		 * CD1_2   =    0.000693935921799 / no comment                                     
		 * CD2_1   =    -0.00069486223599 / no comment                                     
		 * CD2_2   =   -0.000129152873537 / no comment                                     
		 * IMAGEW  =                 1522 / Image width,  in pixels.                       
		 * IMAGEH  =                 1125 / Image height, in pixels.                       
		 * A_ORDER =                    2 / Polynomial order, axis 1                       
		 * A_0_0   =                    0 / no comment                                     
		 * A_0_1   =                    0 / no comment                                     
		 * A_0_2   =   -1.26499559714E-06 / no comment                                     
		 * A_1_0   =                    0 / no comment                                     
		 * A_1_1   =   -2.39062436835E-07 / no comment                                     
		 * A_2_0   =   -8.12762702006E-08 / no comment                                     
		 * B_ORDER =                    2 / Polynomial order, axis 2                       
		 * B_0_0   =                    0 / no comment                                     
		 * B_0_1   =                    0 / no comment                                     
		 * B_0_2   =     1.3610757751E-06 / no comment                                     
		 * B_1_0   =                    0 / no comment                                     
		 * B_1_1   =     8.8428680197E-08 / no comment                                     
		 * B_2_0   =     2.5731858321E-07 / no comment                                     
		 * AP_ORDER=                    2 / Inv polynomial order, axis 1                   
		 * AP_0_0  =    0.000105004787597 / no comment                                     
		 * AP_0_1  =    -5.4694811938E-07 / no comment                                     
		 * AP_0_2  =    1.26357249829E-06 / no comment                                     
		 * AP_1_0  =   -3.97035003821E-08 / no comment                                     
		 * AP_1_1  =    2.38950114567E-07 / no comment                                     
		 * AP_2_0  =    8.11809968058E-08 / no comment                                     
		 * BP_ORDER=                    2 / Inv polynomial order, axis 2                   
		 * BP_0_0  =   -0.000114472684206 / no comment                                     
		 * BP_0_1  =    6.04213905525E-07 / no comment                                     
		 * BP_0_2  =   -1.35946156203E-06 / no comment                                     
		 * BP_1_0  =   -3.87567298792E-08 / no comment                                     
		 * BP_1_1  =   -8.84976210267E-08 / no comment                                     
		 * BP_2_0  =   -2.57232933876E-07 / no comment                                     
		 * HISTORY Created by the Astrometry.net suite.                                    
		 * HISTORY For more details, see http://astrometry.net.                            
		 * HISTORY Git URL https://github.com/dstndstn/astrometry.net                      
		 * HISTORY Git revision 0.82-17-g7038e323                                          
		 * HISTORY Git date Fri_Aug_28_20:54:49_2020_+0000                                 
		 * HISTORY This is a WCS header was created by Astrometry.net.                     
		 * DATE    = '2020-10-16T10:15:17' / Date this file was created.                   
		 * COMMENT -- onefield solver parameters: --                                       
		 * COMMENT Index(0): /data1/INDEXES/200/index-219.fits                             
		 * COMMENT Index(1): /data1/INDEXES/200/index-218.fits                             
		 * COMMENT Index(2): /data1/INDEXES/200/index-217.fits                             
		 * COMMENT Index(3): /data1/INDEXES/200/index-216.fits                             
		 * COMMENT Index(4): /data1/INDEXES/200/index-215.fits                             
		 * COMMENT Index(5): /data1/INDEXES/200/index-214.fits                             
		 * COMMENT Index(6): /data1/INDEXES/200/index-213.fits                             
		 * COMMENT Index(7): /data1/INDEXES/200/index-212.fits                             
		 * COMMENT Index(8): /data1/INDEXES/200/index-211.fits                             
		 * COMMENT Index(9): /data1/INDEXES/200/index-210.fits                             
		 * COMMENT Index(10): /data1/INDEXES/200/index-209.fits                            
		 * COMMENT Index(11): /data1/INDEXES/200/index-208.fits                            
		 * COMMENT Index(12): /data1/INDEXES/200/index-207.fits                            
		 * COMMENT Index(13): /data1/INDEXES/200/index-206.fits                            
		 * COMMENT Index(14): /data1/INDEXES/200/index-205.fits                            
		 * COMMENT Index(15): /data1/INDEXES/200/index-204-03.fits                         
		 * COMMENT Index(16): /data1/INDEXES/200/index-203-03.fits                         
		 * COMMENT Index(17): /data1/INDEXES/200/index-202-03.fits                         
		 * COMMENT Index(18): /data1/INDEXES/200/index-201-03.fits                         
		 * COMMENT Index(19): /data1/INDEXES/200/index-200-03.fits                         
		 * COMMENT Index(20): /data1/INDEXES/4100/index-4119.fits                          
		 * COMMENT Index(21): /data1/INDEXES/4100/index-4118.fits                          
		 * COMMENT Index(22): /data1/INDEXES/4100/index-4117.fits                          
		 * COMMENT Index(23): /data1/INDEXES/4100/index-4116.fits                          
		 * COMMENT Index(24): /data1/INDEXES/4100/index-4115.fits                          
		 * COMMENT Index(25): /data1/INDEXES/4100/index-4114.fits                          
		 * COMMENT Index(26): /data1/INDEXES/4100/index-4113.fits                          
		 * COMMENT Index(27): /data1/INDEXES/4100/index-4112.fits                          
		 * COMMENT Index(28): /data1/INDEXES/4100/index-4111.fits                          
		 * COMMENT Index(29): /data1/INDEXES/4100/index-4110.fits                          
		 * COMMENT Index(30): /data1/INDEXES/4100/index-4109.fits                          
		 * COMMENT Index(31): /data1/INDEXES/4100/index-4108.fits                          
		 * COMMENT Index(32): /data1/INDEXES/4100/index-4107.fits                          
		 * COMMENT Field name: job.axy                                                     
		 * COMMENT Field scale lower: 0.236531 arcsec/pixel                                
		 * COMMENT Field scale upper: 425.756 arcsec/pixel                                 
		 * COMMENT X col name: X                                                           
		 * COMMENT Y col name: Y                                                           
		 * COMMENT Start obj: 0                                                            
		 * COMMENT End obj: 0                                                              
		 * COMMENT Solved_in: (null)                                                       
		 * COMMENT Solved_out: (null)                                                      
		 * COMMENT Parity: 2                                                               
		 * COMMENT Codetol: 0.01                                                           
		 * COMMENT Verify pixels: 1 pix                                                    
		 * COMMENT Maxquads: 0                                                             
		 * COMMENT Maxmatches: 0                                                           COMMENT Cpu limit: 600.000000 s                                                 COMMENT Time limit: 0 s                                                         COMMENT Total time limit: 0 s                                                   COMMENT Total CPU limit: 0.000000 s                                             COMMENT Tweak: yes                                                              COMMENT Tweak AB order: 2                                                       COMMENT Tweak ABP order: 2                                                      COMMENT --                                                                      COMMENT -- properties of the matching quad: --                                  COMMENT index id: 4109                                                          COMMENT index healpix: -1                                                       COMMENT index hpnside: 0                                                        COMMENT log odds: 127.771                                                       COMMENT odds: 3.09287e+55                                                       COMMENT quadno: 505184                                                          COMMENT stars: 574205,574181,574216,574173                                      COMMENT field: 8,5,4,2                                                          COMMENT code error: 0.00143375                                                  COMMENT nmatch: 15                                                              COMMENT nconflict: 0                                                            COMMENT nfield: 681                                                             COMMENT nindex: 15                                                              COMMENT scale: 2.54278 arcsec/pix                                               COMMENT parity: 1                                                               COMMENT quads tried: 1651                                                       COMMENT quads matched: 25839                                                    COMMENT quads verified: 195                                                     COMMENT objs tried: 9                                                           COMMENT cpu time: 0.048                                                         COMMENT --                                                                      END                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             
		
	 * @param wcsHeaderFile
	 * @throws FitsException 
	 * @throws IOException 
	 */
	public void applyWCSHeader(String wcsHeaderFile, int stretchFactor) throws IOException, FitsException {
	
		//list of fits files in DIR
		File[] fitsFileInformation = getFitsFilesDetails();
		
		//get FITS objects
		Fits[] fitsFiles = new Fits[fitsFileInformation.length];
		//populate information
		for (int i=0;i<fitsFiles.length;i++) {
			fitsFiles[i] = new Fits(fitsFileInformation[i]);
		}

		//open FITS WCS header
		Fits wcsHeaderFITS = new Fits(wcsHeaderFile);
		Header wcsHeaderFITSHeader = wcsHeaderFITS.getHDU(0).getHeader();

		//keywords with priority matching (startsWith)
		String[] wcsHeaderStringKeywords = {"CTYPE", "CUNIT1", "CUNIT2"}; 

		String[] wcsHeaderIntegerKeywords = {"WCSAXES", "IMAGEW", "IMAGEH","A_ORDER", "B_ORDER","AP_ORDER","BP_ORDER"}; 
		
		String[] wcsHeaderDoubleKeywords = {"CRPIX", "CRVAL","CDELT","CROTA","CD1_","CD2_","EQUINOX", "LONPOLE", "LATPOLE",  "A_", "B_","AP_","BP_"}; 
	
		String[] wcsHeaderElements = {"CTYPE", "CUNIT1", "CUNIT2", "WCSAXES", "IMAGEW", "IMAGEH","A_ORDER", "B_ORDER","AP_ORDER","BP_ORDER", "CRPIX", "CRVAL","CDELT","CROTA","CD1_","CD2_","EQUINOX", "LONPOLE", "LATPOLE",  "A_", "B_","AP_","BP_"};
		/**
		 * TODO 
		 * can probably be simplified with calling
		 * updatedFits.getHDU(0).getHeader().addLine(originalHeaderCard);
		 * 
		 * addline ==> this takes a header element and does not look at the type so we do not need to know more and separate per type
		 */
		//apply to each FITS
		for (int i=0;i<fitsFiles.length;i++) {
			//get current fits header
			Header headerHDU = fitsFiles[i].getHDU(0).getHeader();
			
			//read all elements of the WCS fits header
			Cursor<String, HeaderCard> wcsHeaderFITSHeaderIter = wcsHeaderFITSHeader.iterator();
			while (wcsHeaderFITSHeaderIter.hasNext()) {
				// WCS header element
				HeaderCard wcsHeaderFITSHeaderCard = wcsHeaderFITSHeaderIter.next();
				//WCS elemet key
				String wcsHeaderKey = wcsHeaderFITSHeaderCard.getKey();
				//check if the current WCS element key starts with the predefined keys to be updated
				for (String wcsKeyword : wcsHeaderElements) {
					if (wcsHeaderKey.startsWith(wcsKeyword)) {
						headerHDU.deleteKey(wcsHeaderKey);
						headerHDU.addLine(wcsHeaderFITSHeaderCard);
						break;
					}					
				}
			}
			
			/**
			while (wcsHeaderFITSHeaderIter.hasNext()) {
				//fits WCS header
				HeaderCard wcsHeaderFITSHeaderCard = wcsHeaderFITSHeaderIter.next();
				String wcsHeaderKey = wcsHeaderFITSHeaderCard.getKey();
				String wcsHeaderValue = wcsHeaderFITSHeaderCard.getValue();				
				String comment = wcsHeaderFITSHeaderCard.getComment();
				
				//add the relevant keywords, determine the type
				boolean isWCSStringkeyword = false; 
				for (String wcsKeyword : wcsHeaderStringKeywords) {
					if (wcsHeaderKey.startsWith(wcsKeyword)) {
						isWCSStringkeyword = true;
						break;
					}
				}

				//add the relevant keywords
				boolean isWCSIntegerkeyword = false; 
				for (String wcsKeyword : wcsHeaderIntegerKeywords) {
					if (wcsHeaderKey.startsWith(wcsKeyword)) {
						isWCSIntegerkeyword = true;
						break;
					}
				}

				
				//add the relevant keywords
				boolean isWCSDoublekeyword = false; 
				for (String wcsKeyword : wcsHeaderDoubleKeywords) {
					if (wcsHeaderKey.startsWith(wcsKeyword)) {
						isWCSDoublekeyword = true;
						break;
					}
				}
				
				//apply the keyword with priority for matching, String => Integer => Double 
				if (isWCSStringkeyword) {
					//STRING
					
					//read header of the current file
					Cursor<String, HeaderCard> fitsHeaderCursor = headerHDU.iterator();				
					fitsHeaderCursor.setKey(wcsHeaderKey);
					if (fitsHeaderCursor.hasNext()) {
						//property exists
						fitsHeaderCursor.next();
						//remove
						fitsHeaderCursor.remove();					
					}
					//add new value
					ApplicationWindow.logger.info("applying : "+wcsHeaderKey+" with value "+wcsHeaderValue+" to "+fitsFileInformation[i].getName());
					fitsHeaderCursor.add(new HeaderCard(wcsHeaderKey,wcsHeaderValue,comment));
					
				} else if (isWCSIntegerkeyword) {
					//NUMBER
					//read header of the current file
					Cursor<String, HeaderCard> fitsHeaderCursor = headerHDU.iterator();				
					fitsHeaderCursor.setKey(wcsHeaderKey);
					if (fitsHeaderCursor.hasNext()) {
						//property exists
						fitsHeaderCursor.next();
						//remove
						fitsHeaderCursor.remove();					
					}
					//add new value
					ApplicationWindow.logger.info("applying : "+wcsHeaderKey+" with value "+Integer.parseInt(wcsHeaderValue)+" to "+fitsFileInformation[i].getName());
					fitsHeaderCursor.add(new HeaderCard(wcsHeaderKey,Integer.parseInt(wcsHeaderValue),comment));
					
				} else if (isWCSDoublekeyword) {
					//NUMBER
					//read header of the current file
					Cursor<String, HeaderCard> fitsHeaderCursor = headerHDU.iterator();				
					fitsHeaderCursor.setKey(wcsHeaderKey);
					if (fitsHeaderCursor.hasNext()) {
						//property exists
						fitsHeaderCursor.next();
						//remove
						fitsHeaderCursor.remove();					
					}
					//add new value
					ApplicationWindow.logger.info("applying : "+wcsHeaderKey+" with value "+Double.parseDouble(wcsHeaderValue)+" to "+fitsFileInformation[i].getName());
					fitsHeaderCursor.add(new HeaderCard(wcsHeaderKey,Double.parseDouble(wcsHeaderValue),comment));
					
				}
			
			
			}		*/			
		

			/*
			 * DATE keyword issue (not needed)
			 * was a modification for testing with the NASA asteroid hunter, but I never managed to make it work
			 * 
			 Cursor<String, HeaderCard> headerCursor = headerHDU.iterator(); 
			 String dateObs = headerHDU.getStringValue("DATE-OBS"); 
			 if (dateObs != null) {
				 headerCursor.setKey("DATE"); if (headerCursor.hasNext()) { //property exists
					 headerCursor.next(); 
					 //remove 
					 headerCursor.remove(); headerCursor.add(new HeaderCard("DATE",dateObs,"replaced"));
					 ApplicationWindow.logger.info("applying : "+dateObs+" to DATE field");
				 }
			 }
			 * 
			 * } }
			 */
			
			//write to disk
			writeUpdatedFITSFile(fitsFileInformation[i], fitsFiles[i], stretchFactor);

		}

		wcsHeaderFITS.close();
		closeFitsFiles(fitsFiles);
	}

	/**
	 * Downloads a file ot the target filename
	 * @param fileURL
	 * @param targetFilePath
	 * @return
	 * @throws IOException 
	 */
	public static int downloadFile(URL fileURL, String targetFilePath) throws IOException {
		ApplicationWindow.logger.info("downloading : "+fileURL.toString()+" to "+targetFilePath);
		//if the file exists, delete it
		File targetfile = new File(targetFilePath);
		if (targetfile.exists()) {
			targetfile.delete();
			ApplicationWindow.logger.info("deleted pre-existing : "+targetFilePath);
		}
		
		//download new
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
	
	/**
	 * Writes to disk the plate solve result for reading when openning the images again
	 * @param fitsFileFullPath the FITS file that was solvede
	 * @param result the solve result
	 * @throws IOException 
	 * @throws ConfigurationException 
	 */
	public void writeSolveResults(String fitsFileFullPath, PlateSolveResult result) throws IOException, ConfigurationException {
		//create file
		String solveResultFilename = fitsFileFullPath.substring(0,fitsFileFullPath.lastIndexOf("."))+"_result.ini";
		File solveResultFile = new File(solveResultFilename);
		ApplicationWindow.logger.info("Will write solve results to:"+solveResultFilename);
		
		if (solveResultFile.exists()) {
			solveResultFile.delete();
			solveResultFile = new File(solveResultFilename);
		}
		
		solveResultFile.createNewFile();
		
		//write file
		Configurations configs = new Configurations();
		FileBasedConfigurationBuilder<PropertiesConfiguration> configBuilder = configs.propertiesBuilder(solveResultFile);
		FileBasedConfiguration config = configBuilder.getConfiguration();
		
		config.setProperty("success", result.isSuccess());
		config.setProperty("failure_reason", result.getFailureReason());
		config.setProperty("warning", result.getWarning());
		
		Map<String,String> solveInformation = result.getSolveInformation();
		if (solveInformation != null) {
			for (String key:solveInformation.keySet()) {
				config.setProperty(key, solveInformation.get(key));			
			}			
		}
		configBuilder.save();
					
	}
	
	/**
	 * Returns the results (if already exist)
	 * @param fitsFileFullPath
	 * @return null if they do not exist
	 * @throws ConfigurationException 
	 */
	public PlateSolveResult readSolveResults(String fitsFileFullPath) throws ConfigurationException {
		PlateSolveResult ret = null;
		
		//create file
		String solveResultFilename = fitsFileFullPath.substring(0,fitsFileFullPath.lastIndexOf("."))+"_result.ini";
		File solveResultFile = new File(solveResultFilename);
		
		if (solveResultFile.exists()) {
			ApplicationWindow.logger.info("Will read solve results from:"+solveResultFilename);
			
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
				if (!key.equals("success") && !key.equals("failure_reason") && !key.equals("warning" )) {
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
	
	
	/**
	 * Writes an updated FITS file to disk. This call will
	 * - create 2 directories _solved and _solved_stretched an will save the image with the updated WCS header elements and also a stretched version
	 * - if the image is a color image, it will repeat the process after converting it to a monochrome image
	 * @param fileInformation
	 * @param originalFits
	 * @throws IOException 
	 * @throws FitsException 
	 */
	private void writeUpdatedFITSFile(File fileInformation, Fits originalFits, int stretchFactor) throws FitsException, IOException {
		//check if it is a color image
		int naxis = originalFits.getHDU(0).getHeader().getIntValue("NAXIS");
		boolean isColor = false;
		if (naxis == 3) {
			isColor = true;
		}
		
		
		//create dir for storing solved image if it does not exist
		String newFNameSolved = addDirectory(fileInformation, "_solved");
		//write FITS image with suffix wcs
		writeFitsWithSuffix(originalFits, newFNameSolved, "_wcs");
		
		Fits monochromeFits = null;
		if (isColor) {
			//convert to Mono
			monochromeFits = convertToMono(originalFits);

			//create dir for storing monochrome solved image if it does not exist
			String newFNameSolvedMono = addDirectory(fileInformation, "_solved_mono");
			//write FITS image with suffix
			writeFitsWithSuffix(monochromeFits, newFNameSolvedMono, "_mono_wcs");			
		}
		
		//stretch and write stretched FITS file
		String newFNameSolvedStretched = addDirectory(fileInformation, "_solved_stretched");
		stretch(originalFits, stretchFactor);
		writeFitsWithSuffix(originalFits, newFNameSolvedStretched, "_wcs_stretch");
		
		if (isColor) {
			//create dir for storing monochrome solved streched image if it does not exist
			String newFNameSolvedMonoStretch = addDirectory(fileInformation, "_solved_mono_stretched");
			stretch(monochromeFits, stretchFactor);
			//write FITS image with suffix
			writeFitsWithSuffix(monochromeFits, newFNameSolvedMonoStretch, "_mono_wcs_stretch");			
		}


	}
	
	/**
	 * Converts the FITS color image to a monochrome version, using as pixel value the average of R,G and B 
	 * @param colorImage
	 * @return monochrome FITS image
	 * @throws IOException 
	 * @throws FitsException 
	 */
	private Fits convertToMono(Fits colorFITSImage) throws FitsException, IOException {
		// convert data
		Object monoKernelData = convertToMono(colorFITSImage.getHDU(0).getKernel());
		//create new FITS object with mono data
		Fits updatedFits = new Fits();
		updatedFits.addHDU(FitsFactory.hduFactory(monoKernelData));

		//copy all header elements
		
		//remove all previous header elements and copy the updated ones (not sure if removing is needed)
		Cursor<String, HeaderCard> updatedFitsHeaderIterator = updatedFits.getHDU(0).getHeader().iterator();
		while (updatedFitsHeaderIterator.hasNext()) {				
			updatedFitsHeaderIterator.next();
			updatedFitsHeaderIterator.remove();
			
		}
		
		//copy the corrected FITS header elements to the new file
		Cursor<String, HeaderCard> originalHeader = colorFITSImage.getHDU(0).getHeader().iterator();
		while (originalHeader.hasNext()) {
			HeaderCard originalHeaderCard = originalHeader.next();
			updatedFits.getHDU(0).getHeader().addLine(originalHeaderCard);
		}
		
		//update header elements to specify that this is a monochrome image
		Cursor<String, HeaderCard> headerCursor = updatedFits.getHDU(0).getHeader().iterator();
		// set axis to 2
		headerCursor.setKey("NAXIS");
		if (headerCursor.hasNext()) { // property exists
			headerCursor.next();
			// remove
			headerCursor.remove();
			headerCursor.add(new HeaderCard("NAXIS", 2, "replaced"));
			ApplicationWindow.logger.info("applying : " + 2 + " to NAXIS field");
		}

		// remove NAXIS3
		headerCursor.setKey("NAXIS3");
		if (headerCursor.hasNext()) { // property exists
			headerCursor.next();
			// remove
			headerCursor.remove();
			ApplicationWindow.logger.info("removed NAXIS3 field");
		}

				
		//finished copying header elements, assign the new Fits object (monochrome data and header elements)
		return updatedFits;		
	}
	/**
	 * Writes the FITS image to disk on the target pth adding also the suffix to the filename, the target file will be deleted if it exists 
	 * @param fitsImage
	 * @param path
	 * @param suffix
	 * @throws FitsException 
	 * @throws IOException 
	 */
	private void writeFitsWithSuffix(Fits fitsImage, String fitsFilename, String suffix) throws IOException, FitsException {
		int lastSepPosition = fitsFilename.lastIndexOf(".");
		fitsFilename = fitsFilename.substring(0, lastSepPosition)+suffix+".fit";
		File toDeleteFile = new File(fitsFilename);
		if (toDeleteFile.exists()) {
			toDeleteFile.delete();
		}
		fitsImage.write(new File(fitsFilename));		
	}
	/**
	 * Adds the directory if missing, and returns the new filename under the new directory
	 * @param path
	 * @return
	 * @throws IOException 
	 */
	private String addDirectory(File currentFile, String directory) throws IOException {
		String newDirectory = currentFile.getParent()+File.separator+directory;
		ApplicationWindow.logger.info("addDirectory called. currentFile:"+currentFile.getAbsolutePath()+" dir:"+directory);
		File newDirFile = new File( newDirectory);
		if (newDirFile.exists()) {

		}else {
			//create
			newDirFile.mkdirs();
		}
		return newDirFile.getAbsolutePath()+File.separator+currentFile.getName();
	}
	/**
	 * Converts the FITS data to monochrome
	 * @param kernelData
	 * @return
	 * @throws FitsException 
	 */
	private Object convertToMono(Object kernelData) throws FitsException {
		
		ApplicationWindow.logger.info("converting color to mono");
		if (kernelData instanceof short[][][]) {
			short[][][] data = (short[][][])kernelData;
			short[][] monoData = new short[data[0].length][data[0][0].length];
			
			for (int i=0;i<data[0].length;i++) {
				for (int j=0; j<data[0][i].length; j++) {
					short val1 = data[0][i][j]; //R
					short val2 = data[1][i][j]; //G
					short val3 = data[2][i][j]; //B
					
					int average = ((val1+val2+val3) / 3);
					monoData[i][j] = (short)average;
				}
			}
			ApplicationWindow.logger.info("returning short[][] with height"+monoData.length+ "and width"+monoData[0].length);

			return monoData;
			
		} else if (kernelData instanceof int[][][]) {
			int[][][] data = (int[][][])kernelData;
			int[][] monoData = new int[data[0].length][data[0][0].length];
			
			for (int i=0;i<data[0].length;i++) {
				for (int j=0; j<data[0][i].length; j++) {
					int val1 = data[0][i][j]; //R
					int val2 = data[1][i][j]; //G
					int val3 = data[2][i][j]; //B
					
					long average = ((val1+val2+val3) / 3);
					monoData[i][j] = (int)average;
				}
			}
			ApplicationWindow.logger.info("returning int[][] with height"+monoData.length+ "and width"+monoData[0].length);

			return monoData;
			
		} else if (kernelData instanceof float[][][]) {
			float[][][] data = (float[][][])kernelData;
			float[][] monoData = new float[data[0].length][data[0][0].length];
			
			for (int i=0;i<data.length;i++) {
				for (int j=0; j<data[i].length; j++) {
					float val1 = data[0][i][j]; //R
					float val2 = data[1][i][j]; //G
					float val3 = data[2][i][j]; //B
					
					double average = ((val1+val2+val3) / 3);
					monoData[i][j] = (float)average;
				}
			}
			ApplicationWindow.logger.info("returning float[][] with height"+monoData.length+ "and width"+monoData[0].length);

			return monoData;
			
		} else {
			throw new FitsException("Cannot understand file, it has a type="+kernelData.getClass().getName());
		}	
		
	}
	

	/**
	 * Does a non linear stretch on the image data. 
	 * stretch each pixel value as (current pixel value = current pixel value + ((max pixel value - current pixel value) * stretchFactor/100)
	 * This is a non linear stretch that will stretch more faint data
	 * @param fitsImage
	 * @param stretchFactor percentage from 0 to 100
	 * @throws FitsException
	 * @throws IOException
	 */
	public void stretch(Fits fitsImage, int stretchFactor) throws FitsException, IOException {
		
		ApplicationWindow.logger.info("will stretch FITS image with factor:"+stretchFactor);
		//stretchFactor is from 0 to 100 
		Object kernelData = fitsImage.getHDU(0).getKernel();
		
		if (kernelData instanceof short[][]) {
			short[][] data =(short[][]) kernelData;
			
			//determine average value
			BigInteger sum = BigInteger.ZERO;
			for (int i=0;i<data.length;i++) {
				for (int j=0;j<data[i].length;j++) {
					sum = sum.add(BigInteger.valueOf(data[i][j]));
				}
			}
			ApplicationWindow.logger.info("Addition of all values:"+sum);

			BigInteger averageValueBI = sum.divide(BigInteger.valueOf(data.length*data[0].length));
			short averageValue = averageValueBI.shortValue();
			ApplicationWindow.logger.info("Average value:"+averageValue);
			ApplicationWindow.logger.info("Average value BI:"+averageValueBI);

			//now stretch each value
			for (int i=0;i<data.length;i++) {
				for (int j=0;j<data[i].length;j++) {
//					if (data[i][j] > averageValue) {
					int distanceToMaxValue = Short.MAX_VALUE-data[i][j];
					int delta = (int) (distanceToMaxValue * ((float)stretchFactor/100));
					data[i][j] = (short) (data[i][j] + delta);
					//ApplicationWindow.logger.info("distance to max="+distanceToMaxValue+" stretching value additional pixel value(R):"+delta+" final value="+data[i][j]);
					
//					}
				}
			}

		} else if (kernelData instanceof int[][]) {
			int[][] data = (int[][])kernelData;
			
		} else if (kernelData instanceof float[][]) {
			float[][] data = (float[][])kernelData;
			
		}else if (kernelData instanceof short[][][]) {
			short[][][] data =(short[][][]) kernelData;

			//determine average value
			BigInteger sum = BigInteger.ZERO;

			for (int i=0;i<data[0].length;i++) {
				for (int j=0; j<data[0][i].length; j++) {
					short val1 = data[0][i][j]; //R
					short val2 = data[1][i][j]; //G
					short val3 = data[2][i][j]; //B
					
					int average = ((val1+val2+val3) / 3);
					
					sum = sum.add(BigInteger.valueOf(average));
				}
			}
			
			ApplicationWindow.logger.info("Addition of all values:"+sum);

			BigInteger averageValueBI = sum.divide(BigInteger.valueOf(data[0].length*data[0][0].length));
			short averageValue = averageValueBI.shortValue();
			ApplicationWindow.logger.info("Average value:"+averageValue);
			ApplicationWindow.logger.info("Average value BI:"+averageValueBI);

			//now stretch each value
			for (int i=0;i<data[0].length;i++) {
				for (int j=0; j<data[0][i].length; j++) {
					short val1 = data[0][i][j]; //R
					short val2 = data[1][i][j]; //G
					short val3 = data[2][i][j]; //B
					
					int average = ((val1+val2+val3) / 3);
					short averageShort = (short)average;
					
					//if (averageShort > averageValue) {
						
					//R
					int distanceToMaxValueR = Short.MAX_VALUE-data[0][i][j];						
					int deltaR = (int) (distanceToMaxValueR * ((float)stretchFactor/100));
					data[0][i][j] = (short) (data[0][i][j] + deltaR);
					//ApplicationWindow.logger.info("distance to max="+distanceToMaxValueR+" stretching value additional pixel value(R):"+deltaR+" final value="+data[0][i][j]);
					
					
					//G
					int distanceToMaxValueG = Short.MAX_VALUE-data[1][i][j];
					int deltaG = (int) (distanceToMaxValueG * ((float)stretchFactor/100));
					data[1][i][j] = (short) (data[1][i][j] + deltaG);
					//ApplicationWindow.logger.info("distance to max="+distanceToMaxValueG+" stretching value additional pixel value(G):"+deltaG+" final value="+data[1][i][j]);
					
					//B
					int distanceToMaxValueB = Short.MAX_VALUE-data[2][i][j];
					int deltaB = (int) (distanceToMaxValueB * ((float)stretchFactor/100));
					data[2][i][j] = (short) (data[2][i][j] + deltaB);						
					//ApplicationWindow.logger.info("distance to max="+distanceToMaxValueB+" stretching value additional pixel value(B):"+deltaB+" final value="+data[2][i][j]);
				
					//}
				}
			}		
		} else if (kernelData instanceof int[][][]) {
			int[][][] data =(int[][][]) kernelData;
		
		} else if (kernelData instanceof float[][][]) {
			float[][][] data =(float[][][]) kernelData;
			
		}
		else {
			throw new FitsException("Cannot understand file, it has a type="+kernelData.getClass().getName());
		}								

	}
	
	/**
	 * Returns an image preview
	 * @param kernelData
	 * @return
	 * @throws FitsException 
	 */
	public BufferedImage getImagePreview(Object kernelData) throws FitsException {
        BufferedImage ret = new BufferedImage(350, 350, BufferedImage.TYPE_INT_RGB);
        
		if (kernelData instanceof short[][]) {
			short[][] data =(short[][]) kernelData;
			
			//determine average value
			for (int i=0;i<350;i++) {
				for (int j=0;j<350;j++) {
					int convertedValue = ((int)data[i][j]) + ((int)Short.MAX_VALUE);
					float intensity = ((float)convertedValue) / (2*(float)Short.MAX_VALUE);
					ret.setRGB(i, j, new Color(intensity,intensity,intensity).getRGB()); 
				}
			}


		} else if (kernelData instanceof int[][]) {
			int[][] data = (int[][])kernelData;
			
		} else if (kernelData instanceof float[][]) {
			float[][] data = (float[][])kernelData;
			
		}else if (kernelData instanceof short[][][]) {
			short[][][] data =(short[][][]) kernelData;

			//determine average value
			for (int i=0;i<350;i++) {
				for (int j=0;j<350;j++) {
					int convertedValueR = ((int)data[0][i][j]) + ((int)Short.MAX_VALUE);
					float intensityR = ((float)convertedValueR) / (2*(float)Short.MAX_VALUE);

					int convertedValueG = ((int)data[1][i][j]) + ((int)Short.MAX_VALUE);
					float intensityG = ((float)convertedValueG) / (2*(float)Short.MAX_VALUE);
					
					int convertedValueB = ((int)data[2][i][j]) + ((int)Short.MAX_VALUE);
					float intensityB = ((float)convertedValueB) / (2*(float)Short.MAX_VALUE);
					
					ret.setRGB(i, j,  new Color(intensityR,intensityG,intensityB).getRGB()); 
				}
			}

				
		} else if (kernelData instanceof int[][][]) {
			int[][][] data =(int[][][]) kernelData;
		
		} else if (kernelData instanceof float[][][]) {
			float[][][] data =(float[][][]) kernelData;
			
		}
		else {
			throw new FitsException("Cannot understand file, it has a type="+kernelData.getClass().getName());
		}
		
		return ret;
	}
	
	/**
	 * Returns an image preview for the stretch window
	 * @param kernelData
	 * @return
	 * @throws FitsException 
	 */
	public BufferedImage getStretchedImagePreview(Object kernelData, int stretchFactor) throws FitsException {
        BufferedImage ret = new BufferedImage(350, 350, BufferedImage.TYPE_INT_RGB);
		
		if (kernelData instanceof short[][]) {
			short[][] data =(short[][]) kernelData;
			
			//determine average value
			for (int i=0;i<350;i++) {
				for (int j=0;j<350;j++) {
					int convertedValue = ((int)data[i][j]) + ((int)Short.MAX_VALUE);
					float intensity = ((float)convertedValue) / (2*(float)Short.MAX_VALUE);
					
					//distance to max 
					float distanceToMax = (1-intensity);
					float percentage = (float)stretchFactor/(float)100;
					float delta = intensity + (distanceToMax*percentage);
					ret.setRGB(i, j, new Color(intensity+delta,intensity+delta,intensity+delta).getRGB()); 
				}
			}


		} else if (kernelData instanceof int[][]) {
			int[][] data = (int[][])kernelData;
			
		} else if (kernelData instanceof float[][]) {
			float[][] data = (float[][])kernelData;
			
		}else if (kernelData instanceof short[][][]) {
			short[][][] data =(short[][][]) kernelData;

			//determine average value
			for (int i=0;i<350;i++) {
				for (int j=0;j<350;j++) {
					float percentage = (float)stretchFactor/(float)100;
					
					int convertedValueR = ((int)data[0][i][j]) + ((int)Short.MAX_VALUE);
					float intensityR = ((float)convertedValueR) / (2*((float)Short.MAX_VALUE));
					//distance to max 
					float distanceToMaxR = (1-intensityR);
					float deltaR = (distanceToMaxR*percentage);
					
					int convertedValueG = ((int)data[1][i][j]) + ((int)Short.MAX_VALUE);
					float intensityG = ((float)convertedValueG) / (2*(float)Short.MAX_VALUE);
					//distance to max 
					float distanceToMaxG = (1-intensityG);
					float deltaG = (distanceToMaxG*percentage);
					
					int convertedValueB = ((int)data[2][i][j]) + ((int)Short.MAX_VALUE);
					float intensityB = ((float)convertedValueB) / (2*(float)Short.MAX_VALUE);
					//distance to max 
					float distanceToMaxB = (1-intensityB);
					float deltaB = (distanceToMaxB*percentage);
					
					//ApplicationWindow.logger.info("deltaR="+deltaR+" distanceToMaxR = "+distanceToMaxR+ " intensityR="+intensityR+" convertedValueR ="+convertedValueR+" stretch="+stretchFactor+" R="+convertedValueR+" G="+convertedValueG+" B="+convertedValueB);
					
					
					//ApplicationWindow.logger.info("setting R"+(intensityR+deltaR));
					//ApplicationWindow.logger.info("setting G"+(intensityG+deltaG));
					//ApplicationWindow.logger.info("setting B"+(intensityB+deltaB));
		
					Color targetColor = new Color(intensityR+deltaR,intensityG+deltaG,intensityB+deltaB);
					ret.setRGB(i, j,  targetColor.getRGB()); 

				}
			}

				
		} else if (kernelData instanceof int[][][]) {
			int[][][] data =(int[][][]) kernelData;
		
		} else if (kernelData instanceof float[][][]) {
			float[][][] data =(float[][][]) kernelData;
			
		}
		else {
			throw new FitsException("Cannot understand file, it has a type="+kernelData.getClass().getName());
		}
		
		return ret;		
	}	
}
