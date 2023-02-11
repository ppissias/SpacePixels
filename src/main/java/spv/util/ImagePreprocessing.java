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
import nom.tam.util.Cursor;
import spv.gui.ApplicationWindow;


/**
 * Util class for referencing a bunch of FITS files,
 * interfacing with plate solving utility and finally
 * updating the FITS header files. 
 * 
 * TODO
 * stretch images after mono conversion: values from Average and above: stretch to the half value between their current value and the MAX value.
 * non linear stretch to help detections. 
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
			
			//ApplicationWindow.logger.info("populated fits object as "+ret[i]);
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

	public void applyWCSHeader(String wcsHeaderFile, int stretchFactor, int iterations, boolean stretch, StretchAlgorithm algo) throws IOException, FitsException {
	
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

	
		String[] wcsHeaderElements = {"CTYPE", "CUNIT1", "CUNIT2", "WCSAXES", "IMAGEW", "IMAGEH","A_ORDER", "B_ORDER","AP_ORDER","BP_ORDER", "CRPIX", "CRVAL","CDELT","CROTA","CD1_","CD2_","EQUINOX", "LONPOLE", "LATPOLE",  "A_", "B_","AP_","BP_"};

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

			//write to disk
			writeUpdatedFITSFile(fitsFileInformation[i], fitsFiles[i], stretchFactor, iterations, stretch, algo);

		}

		wcsHeaderFITS.close();
		closeFitsFiles(fitsFiles);
	}

	/**
	 * Will stretch all FITS files creating a mono file where needed as well
	 * @param stretchFactor
	 * @param iterations
	 * @throws IOException
	 * @throws FitsException
	 */
	public void onlyStretch(int stretchFactor, int iterations, StretchAlgorithm algo) throws IOException, FitsException {
		
		//list of fits files in DIR
		File[] fitsFileInformation = getFitsFilesDetails();
		
		//populate information
		for (int i=0;i<fitsFileInformation.length;i++) {
			Fits fitsFile = new Fits(fitsFileInformation[i]);
			//write to disk
			writeOnlyStretchedFitsFile(fitsFileInformation[i], fitsFile, stretchFactor, iterations, algo);
			
		}
		
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
		//ApplicationWindow.logger.info("Will write solve results to:"+solveResultFilename);
		
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
			//ApplicationWindow.logger.info("Will read solve results from:"+solveResultFilename);
			
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
	 * @param iterations 
	 * @param stretch 
	 * @throws IOException 
	 * @throws FitsException 
	 */
	private void writeUpdatedFITSFile(File fileInformation, Fits originalFits, int stretchFactor, int iterations, boolean stretch, StretchAlgorithm algo) throws FitsException, IOException {
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
		
		if (stretch) {
			//stretch and write stretched FITS file
			String newFNameSolvedStretched = addDirectory(fileInformation, "_solved_stretched");
			stretchFITSImage(originalFits, stretchFactor,iterations, algo);
			writeFitsWithSuffix(originalFits, newFNameSolvedStretched, "_wcs_stretch");
			
			if (isColor) {
				//create dir for storing monochrome solved streched image if it does not exist
				String newFNameSolvedMonoStretch = addDirectory(fileInformation, "_solved_mono_stretched");
				stretchFITSImage(monochromeFits, stretchFactor, iterations, algo);
				//write FITS image with suffix
				writeFitsWithSuffix(monochromeFits, newFNameSolvedMonoStretch, "_mono_wcs_stretch");			
			}
		}
	}
	
	/**
	 * Writes an updated FITS file to disk. This call will
	 * - create 2 directories _solved and _solved_stretched an will save the image with the updated WCS header elements and also a stretched version
	 * - if the image is a color image, it will repeat the process after converting it to a monochrome image
	 * @param fileInformation
	 * @param originalFits
	 * @param iterations 
	 * @param stretch 
	 * @throws IOException 
	 * @throws FitsException 
	 */
	private void writeOnlyStretchedFitsFile(File fileInformation, Fits originalFits, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException, IOException {
		//check if it is a color image
		int naxis = originalFits.getHDU(0).getHeader().getIntValue("NAXIS");
		boolean isColor = false;
		if (naxis == 3) {
			isColor = true;
		}
				
		Fits monochromeFits = null;
		if (isColor) {
			//convert to Mono
			monochromeFits = convertToMono(originalFits);
		}
		

		//stretch and write stretched FITS file
		String newFNameSolvedStretched = addDirectory(fileInformation, "_stretched");
		stretchFITSImage(originalFits, stretchFactor,iterations, algo);
		writeFitsWithSuffix(originalFits, newFNameSolvedStretched, "_stretch");
		
		if (isColor) {
			//create dir for storing monochrome solved streched image if it does not exist
			String newFNameSolvedMonoStretch = addDirectory(fileInformation, "_mono_stretched");
			stretchFITSImage(monochromeFits, stretchFactor, iterations, algo);
			//write FITS image with suffix
			writeFitsWithSuffix(monochromeFits, newFNameSolvedMonoStretch, "_mono_stretch");			
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
			//ApplicationWindow.logger.info("applying : " + 2 + " to NAXIS field");
		}

		// remove NAXIS3
		headerCursor.setKey("NAXIS3");
		if (headerCursor.hasNext()) { // property exists
			headerCursor.next();
			// remove
			headerCursor.remove();
			//ApplicationWindow.logger.info("removed NAXIS3 field");
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
		//ApplicationWindow.logger.info("addDirectory called. currentFile:"+currentFile.getAbsolutePath()+" dir:"+directory);
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
		
		//ApplicationWindow.logger.info("converting color to mono");
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
			//ApplicationWindow.logger.info("returning short[][] with height"+monoData.length+ "and width"+monoData[0].length);

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
			//ApplicationWindow.logger.info("returning int[][] with height"+monoData.length+ "and width"+monoData[0].length);

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
			//ApplicationWindow.logger.info("returning float[][] with height"+monoData.length+ "and width"+monoData[0].length);

			return monoData;
			
		} else {
			throw new FitsException("Cannot understand file, it has a type="+kernelData.getClass().getName());
		}	
		
	}
	

	/**
	 * Stretches the FITS image according to the specified algorithm
	 * @param fitsImage
	 * @param stretchFactor percentage from 0 to 100
	 * @param iterations 
	 * @throws FitsException
	 * @throws IOException
	 */
	public void stretchFITSImage(Fits fitsImage, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException, IOException {
		
		//ApplicationWindow.logger.info("will stretch FITS image with factor:"+stretchFactor+" and iterations="+iterations);
		//stretchFactor is from 0 to 100 
		Object kernelData = fitsImage.getHDU(0).getKernel();
		
		if (kernelData instanceof short[][]) {
			short[][] data =(short[][]) kernelData;

			short[][] stretchedData = (short[][])stretchImageData(data, stretchFactor, iterations,data[0].length, data.length, algo);
			//now stretch each value
			for (int i=0;i<data.length;i++) {
				for (int j=0;j<data[i].length;j++) {
					data[i][j] = stretchedData[i][j];
				}
			}

		} else if (kernelData instanceof int[][]) {
			int[][] data = (int[][])kernelData;
			
		} else if (kernelData instanceof float[][]) {
			float[][] data = (float[][])kernelData;
			
		}else if (kernelData instanceof short[][][]) {
			short[][][] data =(short[][][]) kernelData;

			//Red data
			short[][] stretchedRedData = (short[][])stretchImageData(data[0], stretchFactor, iterations, data[0][0].length, data[0].length, algo);
			short[][] stretchedGreenData = (short[][])stretchImageData(data[1], stretchFactor, iterations, data[1][0].length, data[1].length,  algo);
			short[][] stretchedBlueData = (short[][])stretchImageData(data[2], stretchFactor, iterations, data[2][0].length, data[2].length, algo);

			//now stretch each value
			for (int i=0;i<data[0].length;i++) {
				for (int j=0; j<data[0][i].length; j++) {
					
					//if algo is extreme
					//set max value to all (convert to mono)
					if (algo.equals(StretchAlgorithm.EXTREME)) {
						short max = stretchedRedData[i][j];
						if (max < stretchedGreenData[i][j]) {
							max =  stretchedGreenData[i][j];
						}
						if (max < stretchedBlueData[i][j]) {
							max =  stretchedBlueData[i][j];
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
        BufferedImage ret = new BufferedImage(350, 350, BufferedImage.TYPE_INT_ARGB);
        
		if (kernelData instanceof short[][]) {
			short[][] data =(short[][]) kernelData;
			
			int imageHeight = data.length;
			int imageWidth = data[0].length;
			
			if (imageWidth > 350) {
				imageWidth = 350;
			}		
			if (imageHeight > 350) {
				imageHeight = 350;
			}
			for (int i=0;i<imageHeight;i++) {
				for (int j=0;j<imageWidth;j++) {
					
					int convertedValue = ((int)data[i][j]) + ((int)Short.MAX_VALUE);
					float intensity = ((float)convertedValue) / (2*(float)Short.MAX_VALUE);
					ret.setRGB(j, i, new Color(intensity,intensity,intensity, 1.0f).getRGB()); 
				}
			}


		} else if (kernelData instanceof int[][]) {
			int[][] data = (int[][])kernelData;
			
		} else if (kernelData instanceof float[][]) {
			float[][] data = (float[][])kernelData;
			
		}else if (kernelData instanceof short[][][]) {
			short[][][] data =(short[][][]) kernelData;

			int imageHeight = data[0].length;
			int imageWidth = data[0][0].length;
			
			if (imageWidth > 350) {
				imageWidth = 350;
			}		
			if (imageHeight > 350) {
				imageHeight = 350;
			}			
			for (int i=0;i<imageHeight;i++) {
				for (int j=0;j<imageWidth;j++) {
					int convertedValueR = ((int)data[0][i][j]) + ((int)Short.MAX_VALUE) +1;
					float intensityR = ((float)convertedValueR) / (2*(float)Short.MAX_VALUE);

					int convertedValueG = ((int)data[1][i][j]) + ((int)Short.MAX_VALUE) + 1;
					float intensityG = ((float)convertedValueG) / (2*(float)Short.MAX_VALUE);
					
					int convertedValueB = ((int)data[2][i][j]) + ((int)Short.MAX_VALUE) + 1;
					float intensityB = ((float)convertedValueB) / (2*(float)Short.MAX_VALUE);
					
					ret.setRGB(j, i,  new Color(intensityR,intensityG,intensityB, 1.0f).getRGB()); 
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
	 * Returns a BufferedImage from the FITS raw data
	 * @param kernelData FITS raw data
	 * @param width should be maximum to the image width
	 * @param height should be maximum to the image height
	 * @param stretchFactor the stretch factor
	 * @param iterations iterations
	 * @param algo stretch algorithm
	 * @return the BufferedImage containing the FITS raw data
	 * @throws FitsException 
	 */
	private BufferedImage getStretchedImage(Object kernelData, int width, int height, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException {
        BufferedImage ret = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        if (kernelData instanceof short[][]) {
			short[][] data =(short[][]) kernelData;
	
			int imageHeight = data.length;
			int imageWidth = data[0].length;
			
			if (imageWidth > width) {
				imageWidth = width;
			}		
			if (imageHeight > height) {
				imageHeight = height;
			}	
			
			short[][] stretchedData = (short[][])stretchImageData(data, stretchFactor, iterations, imageWidth, imageHeight, algo);
		
			//convert to RGB
			for (int i=0;i<imageHeight;i++) {
				for (int j=0;j<imageWidth;j++) {

					int absValue = ((int)stretchedData[i][j]) + ((int)Short.MAX_VALUE)+1;
					if (absValue > 2*Short.MAX_VALUE) {
						absValue = 2*Short.MAX_VALUE;
					}
					float intensity = (((float)absValue) / ((float)(2*Short.MAX_VALUE)));
					try {
						ret.setRGB(j, i, new Color(intensity,intensity,intensity, 1.0f).getRGB()); 
					}catch (IllegalArgumentException e) {
						ApplicationWindow.logger.info("preparing preview: stretchedData[i][j]="+stretchedData[i][j]);
						ApplicationWindow.logger.info("preparing preview: intensity="+intensity);
						throw (e);
					}
				}
			}


		} else if (kernelData instanceof int[][]) {
			int[][] data = (int[][])kernelData;
			
		} else if (kernelData instanceof float[][]) {
			float[][] data = (float[][])kernelData;
			
		}else if (kernelData instanceof short[][][]) {
			short[][][] data =(short[][][]) kernelData;

			short[][] stretchedDataRed = (short[][])stretchImageData(data[0], stretchFactor, iterations, width, height, algo);
			short[][] stretchedDataGreen = (short[][])stretchImageData(data[1], stretchFactor, iterations, width, height, algo);
			short[][] stretchedDataBlue = (short[][])stretchImageData(data[2], stretchFactor, iterations, width, height, algo);

			int imageHeight = data[0].length;
			int imageWidth = data[0][0].length;
			
			if (imageWidth > width) {
				imageWidth = width;
			}		
			if (imageHeight > height) {
				imageHeight = height;
			}				
			//determine absolute value
			for (int i=0;i<imageHeight;i++) {
				for (int j=0;j<imageWidth;j++) {

					int absValueRed = ((int)stretchedDataRed[i][j]) + ((int)Short.MAX_VALUE)+1;
					if (absValueRed > 2*Short.MAX_VALUE) {
						absValueRed = 2*Short.MAX_VALUE;
					}
					float intensityRed = (((float)absValueRed) / ((float)(2*Short.MAX_VALUE)));

					int absValueGreen = ((int)stretchedDataGreen[i][j]) + ((int)Short.MAX_VALUE)+1;
					if (absValueGreen > 2*Short.MAX_VALUE) {
						absValueGreen = 2*Short.MAX_VALUE;
					}					
					float intensityGreen = (((float)absValueGreen) / ((float)(2*Short.MAX_VALUE)));

					int absValueBlue = ((int)stretchedDataBlue[i][j]) + ((int)Short.MAX_VALUE)+1;
					if (absValueBlue > 2*Short.MAX_VALUE) {
						absValueBlue = 2*Short.MAX_VALUE;
					}					
					float intensityBlue = (((float)absValueBlue) / ((float)(2*Short.MAX_VALUE)));
				
					//if algo is extreme
					//set max value to all (convert to mono)
					if (algo.equals(StretchAlgorithm.EXTREME)) {
						float maxValue = absValueRed;
						if (maxValue < absValueGreen) {
							maxValue = absValueGreen;
						}
						if (maxValue < absValueBlue) {
							maxValue = absValueBlue;
						}
						
						intensityRed = (((float)maxValue) / ((float)(2*Short.MAX_VALUE)));
						intensityGreen = intensityRed;
						intensityBlue= intensityRed;
					}
						
					try {
						Color targetColor = new Color(intensityRed,intensityGreen,intensityBlue, 1.0f);
						ret.setRGB(j, i,  targetColor.getRGB()); 
					}catch (IllegalArgumentException e) {
						ApplicationWindow.logger.info("preparing preview: stretchedDataRed="+stretchedDataRed[i][j]);
						ApplicationWindow.logger.info("preparing preview: absValueRed="+absValueRed);
						ApplicationWindow.logger.info("preparing preview: intensityRed="+intensityRed);
						ApplicationWindow.logger.info("preparing preview: stretchedDataGreen="+stretchedDataGreen[i][j]);
						ApplicationWindow.logger.info("preparing preview: absValueGreen="+absValueGreen);
						ApplicationWindow.logger.info("preparing preview: intensityGreen="+intensityGreen);
						ApplicationWindow.logger.info("preparing preview: stretchedDataBlue="+stretchedDataBlue[i][j]);
						ApplicationWindow.logger.info("preparing preview: absValueBlue="+absValueBlue);
						ApplicationWindow.logger.info("preparing preview: intensityBlue="+intensityBlue);						
						throw (e);
					}

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
	 * @param iterations 
	 * @return
	 * @throws FitsException 
	 */
	public BufferedImage getStretchedImagePreview(Object kernelData, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException {
        return getStretchedImage(kernelData, 350, 350, stretchFactor, iterations, algo);
	}	

	/**
	 * Returns an image preview for the stretch window
	 * @param kernelData
	 * @param iterations 
	 * @return
	 * @throws FitsException 
	 */
	public BufferedImage getStretchedImageFullSize(Object kernelData, int width, int height, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException {
		return getStretchedImage(kernelData, width, height, stretchFactor, iterations, algo);
		/**
        BufferedImage ret = null;
		
		if (kernelData instanceof short[][]) {
			short[][] data =(short[][]) kernelData;
			ret = new BufferedImage(data.length, data[0].length, BufferedImage.TYPE_INT_ARGB);
			
			short[][] stretchedData = (short[][])stretchImageData(data, stretchFactor, iterations, data.length, data[0].length, algo);
			
			for (int i=0;i<data.length;i++) {
				for (int j=0;j<data[0].length;j++) {

					int absValue = ((int)stretchedData[i][j]) + ((int)Short.MAX_VALUE)+1;
					if (absValue > 2*Short.MAX_VALUE) {
						absValue = 2*Short.MAX_VALUE;
					}
					float intensity = (((float)absValue) / ((float)(2*Short.MAX_VALUE)));
					try {
						ret.setRGB(i, j, new Color(intensity,intensity,intensity, 1.0f).getRGB()); 
					}catch (IllegalArgumentException e) {
						ApplicationWindow.logger.info("preparing preview: stretchedData[i][j]="+stretchedData[i][j]);
						ApplicationWindow.logger.info("preparing preview: intensity="+intensity);
						throw (e);
					}
				}
			}


		} else if (kernelData instanceof int[][]) {
			int[][] data = (int[][])kernelData;
			
		} else if (kernelData instanceof float[][]) {
			float[][] data = (float[][])kernelData;
			
		}else if (kernelData instanceof short[][][]) {
			short[][][] data =(short[][][]) kernelData;

			ret = new BufferedImage(data[0].length, data[0][0].length, BufferedImage.TYPE_INT_ARGB);
			
			short[][] stretchedDataRed = (short[][])stretchImageData(data[0], stretchFactor, iterations, data[0].length, data[0][0].length, algo);
			short[][] stretchedDataGreen = (short[][])stretchImageData(data[1], stretchFactor, iterations, data[1].length, data[1][0].length, algo);
			short[][] stretchedDataBlue = (short[][])stretchImageData(data[2], stretchFactor, iterations, data[2].length, data[2][0].length, algo);

			//determine average value
			for (int i=0;i<data[0].length;i++) {
				for (int j=0;j<data[0][0].length;j++) {
					int absValueRed = ((int)stretchedDataRed[i][j]) + ((int)Short.MAX_VALUE)+1;
					if (absValueRed > 2*Short.MAX_VALUE) {
						absValueRed = 2*Short.MAX_VALUE;
					}
					float intensityRed = (((float)absValueRed) / ((float)(2*Short.MAX_VALUE)));

					int absValueGreen = ((int)stretchedDataGreen[i][j]) + ((int)Short.MAX_VALUE)+1;
					if (absValueGreen > 2*Short.MAX_VALUE) {
						absValueGreen = 2*Short.MAX_VALUE;
					}					
					float intensityGreen = (((float)absValueGreen) / ((float)(2*Short.MAX_VALUE)));

					int absValueBlue = ((int)stretchedDataBlue[i][j]) + ((int)Short.MAX_VALUE)+1;
					if (absValueBlue > 2*Short.MAX_VALUE) {
						absValueBlue = 2*Short.MAX_VALUE;
					}					
					float intensityBlue = (((float)absValueBlue) / ((float)(2*Short.MAX_VALUE)));


				
					try {
						Color targetColor = new Color(intensityRed,intensityGreen,intensityBlue, 1.0f);
						ret.setRGB(i, j,  targetColor.getRGB()); 
					}catch (IllegalArgumentException e) {
						ApplicationWindow.logger.info("preparing preview: stretchedDataRed="+stretchedDataRed[i][j]);
						ApplicationWindow.logger.info("preparing preview: absValueRed="+absValueRed);
						ApplicationWindow.logger.info("preparing preview: intensityRed="+intensityRed);
						ApplicationWindow.logger.info("preparing preview: stretchedDataGreen="+stretchedDataGreen[i][j]);
						ApplicationWindow.logger.info("preparing preview: absValueGreen="+absValueGreen);
						ApplicationWindow.logger.info("preparing preview: intensityGreen="+intensityGreen);
						ApplicationWindow.logger.info("preparing preview: stretchedDataBlue="+stretchedDataBlue[i][j]);
						ApplicationWindow.logger.info("preparing preview: absValueBlue="+absValueBlue);
						ApplicationWindow.logger.info("preparing preview: intensityBlue="+intensityBlue);						
						throw (e);
					}

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
		*/
	}	
	
	/**
	 * Facade for stretching the image
	 * @param kernelData
	 * @param intensity
	 * @param iterations
	 * @param width
	 * @param height
	 * @return
	 * @throws FitsException
	 */
	private Object stretchImageData(Object kernelData, int intensity, int iterations, int width, int height, StretchAlgorithm algo) throws FitsException {
		switch (algo) {
		case ENHANCE_HIGH : {
			return stretchImageEnhanceHigh(kernelData, intensity, iterations, width, height);

		}
		case ENHANCE_LOW: {
			return stretchImageEnhanceLow(kernelData, intensity, iterations, width, height);

		}
		
		case EXTREME : {
			return stretchImageEnhanceExtreme(kernelData, intensity, iterations, width, height);
		
		}
		default : {
			return stretchImageEnhanceLow(kernelData, intensity, iterations, width, height);			
		}
		}
	}
	
	/**
	 * Stretches iteratively the image my multiplying all pixel values by a constant 
	 * and setting the minimum value to zero
	 * @param intensity
	 * @param iterations
	 * @return
	 * @throws FitsException 
	 */
	private Object stretchImageEnhanceHigh(Object kernelData, int intensity, int iterations, int width, int height) throws FitsException {
			//ApplicationWindow.logger.info("will stretch FITS image with factor:"+intensity+" for iterations:"+iterations+" width="+width+" height="+height);
						
			if (kernelData instanceof short[][]) {
				short[][] data =(short[][]) kernelData;
				
				//copy initial set of data
				short[][] returnData = new short[height][width];
				for (int i=0;i<height;i++) {
					for (int j=0;j<width;j++) {
						returnData[i][j] = data[i][j];
					}
				}
				
				
				for (int iteration=0;iteration<iterations;iteration++) {
					short minimumValue = Short.MAX_VALUE;
					//stretch each value
					for (int i=0;i<height;i++) {
						for (int j=0;j<width;j++) {
							
							int absValue = (int)returnData[i][j] - (int)Short.MIN_VALUE;

							float newValue = (float)absValue * ((float)1 + ((float)intensity/(float)100));
							newValue = newValue - Short.MAX_VALUE;
							if (newValue > Short.MAX_VALUE) {
								returnData[i][j] = Short.MAX_VALUE;
							} else {
								returnData[i][j] = (short)newValue;
							}
							//set minimum value
							if (minimumValue > returnData[i][j]) {
								minimumValue=returnData[i][j];
							}
						}
					}
					//ApplicationWindow.logger.info("minimum value ="+minimumValue);

					//set black to minimum value 
					int minimumValueDistanceFromZero = (int)minimumValue-(int)Short.MIN_VALUE;
					if (minimumValueDistanceFromZero > 2*(int)Short.MAX_VALUE) {
						minimumValueDistanceFromZero = 2*(int)Short.MAX_VALUE;
					}
					
					for (int i=0;i<height;i++) {
						for (int j=0;j<width;j++) {
							returnData[i][j] = (short) ((int)returnData[i][j] - minimumValueDistanceFromZero);
						}
					}					

					//ApplicationWindow.logger.info("iteration"+iteration);

				}
				//ApplicationWindow.logger.info("started with value "+data[10][10]+" finished with value "+returnData[10][10]);

				return returnData;

			} else if (kernelData instanceof int[][]) {
				int[][] data = (int[][])kernelData;
				
				return null;
				
			} else if (kernelData instanceof float[][]) {
				float[][] data = (float[][])kernelData;
				
				return null;
			}
			else {
				throw new FitsException("Cannot understand file, it has a type="+kernelData.getClass().getName());
			}	
	}
	
	/**
	 * Stretches all pixel values (the lowest values more) then sets the black point to zero
	 * and then stretches all values so that the maximum value reaches the allowed max value (high value to white)
	 * iteratively. 
	 * @param intensity
	 * @param iterations
	 * @return
	 * @throws FitsException 
	 */
	private Object stretchImageEnhanceLow(Object kernelData, int intensity, int iterations, int width, int height) throws FitsException {
			//ApplicationWindow.logger.info("will stretch FITS image with factor:"+intensity+" for iterations:"+iterations+" width="+width+" height="+height);
						
			if (kernelData instanceof short[][]) {
				short[][] data =(short[][]) kernelData;
				
				//copy initial set of data
				short[][] returnData = new short[height][width];

				for (int i=0;i<height;i++) {
					for (int j=0;j<width;j++) {
						returnData[i][j] = data[i][j];
					}
				}
				
				//stretch from current value to value * 2, scaled from 1 ==> 0 depending on the distance to the max value. 
				for (int iteration=0;iteration<iterations;iteration++) {
					short minimumValue = Short.MAX_VALUE;
					short maximumValue = Short.MIN_VALUE;

					//stretch each value
					for (int i=0;i<height;i++) {
						for (int j=0;j<width;j++) {
													
							int absValue = (int)returnData[i][j] - (int)Short.MIN_VALUE;
							float scale = 1 - (((float)absValue) / (2*((float) Short.MAX_VALUE)));
							float newValue = (float)absValue * ((float)1 + ((((float)intensity/(float)100))*scale));
							newValue = newValue - Short.MAX_VALUE;
							if (newValue > Short.MAX_VALUE) {
								returnData[i][j] = Short.MAX_VALUE;
							} else {
								returnData[i][j] = (short)newValue;
							}
							//set minimum value
							if (minimumValue > returnData[i][j]) {
								minimumValue=returnData[i][j];
							}
							//set maximum value
							if (maximumValue < returnData[i][j]) {
								maximumValue=returnData[i][j];
							}
						}
					}
					//ApplicationWindow.logger.info("minimum value ="+minimumValue);

					//set black to minimum value and stretch
					int minimumValueDistanceFromZero = (int)minimumValue-(int)Short.MIN_VALUE;
					if (minimumValueDistanceFromZero > 2*(int)Short.MAX_VALUE) {
						minimumValueDistanceFromZero = 2*(int)Short.MAX_VALUE;
					}
					int maximumValueDistanceFromMax = (int)Short.MAX_VALUE-(int)maximumValue;	
					if (maximumValueDistanceFromMax > 2*(int)Short.MAX_VALUE) {
						maximumValueDistanceFromMax = 2*(int)Short.MAX_VALUE;
					}

					float stretchCoefficient = 1 + (((float)maximumValueDistanceFromMax)/(2*(float)Short.MAX_VALUE));
					//ApplicationWindow.logger.info("stretch 2.5: stretchCoefficient "+stretchCoefficient);

					for (int i=0;i<height;i++) {
						for (int j=0;j<width;j++) {
							//deduce minimum value (set black to minimum)
							int absValue = (int)returnData[i][j] - (int)Short.MIN_VALUE - minimumValueDistanceFromZero;
							
							//multiply
							float newValue = ((float)absValue) * stretchCoefficient;

							newValue = newValue - Short.MAX_VALUE;	
							if (newValue > Short.MAX_VALUE) {
								returnData[i][j] =  Short.MAX_VALUE;
							} else {
								returnData[i][j] = (short)newValue;
							}
							
						}
					}					

				}
				//ApplicationWindow.logger.info("started with value "+data[10][10]+" finished with value "+returnData[10][10]);

				return returnData;

			} else if (kernelData instanceof int[][]) {
				int[][] data = (int[][])kernelData;
				
				return null;
				
			} else if (kernelData instanceof float[][]) {
				float[][] data = (float[][])kernelData;
				
				return null;
			}
			else {
				throw new FitsException("Cannot understand file, it has a type="+kernelData.getClass().getName());
			}	
	}	

	
	/**
	 * Converts to mono and stretches all pixel values above a certain value to an intensity 
	 * @param kernelData the data
	 * @param threshhold above that threshhold all values will be strethced to intensity
	 * @param intensity the intensity to be stretched
	 * @param width
	 * @param height
	 * @return the stretched data
	 * @throws FitsException
	 */
	private Object stretchImageEnhanceExtreme(Object kernelData, int threshold, int intensity, int width, int height) throws FitsException {
			//ApplicationWindow.logger.info("will stretch FITS image with factor:"+intensity+" for iterations:"+iterations+" width="+width+" height="+height);
						
			if (kernelData instanceof short[][]) {
				short[][] data =(short[][]) kernelData;
				
				//copy initial set of data
				short[][] returnData = new short[height][width];

				//determine average pixel value (noise level)
				
				long allPixelSumValue = 0;
				for (int i=0;i<height;i++) {
					for (int j=0;j<width;j++) {
						allPixelSumValue += (data[i][j]- (int)Short.MIN_VALUE);				
					}
				}
				
				float averageNoiseLevel = ((float)allPixelSumValue)/((float)width*height);
				ApplicationWindow.logger.info("avg noise level="+averageNoiseLevel);
				for (int i=0;i<height;i++) {
					for (int j=0;j<width;j++) {
						returnData[i][j] = data[i][j];
						
					
						//check if value is above threshold and set
						//convert to abs
						int absValue = (int)returnData[i][j] - (int)Short.MIN_VALUE;
						//get pixel value scale to max
						//float scale = (((float)absValue) / (2*((float) Short.MAX_VALUE)))*100;
						
						if (absValue >= averageNoiseLevel+10*threshold) {
							//set to instensity (intensity is from 1-20)
							float newValue = (((float)intensity)/(float)20)*(2*((float) Short.MAX_VALUE));
							newValue = newValue - Short.MAX_VALUE;
							if (newValue > Short.MAX_VALUE) {
								returnData[i][j] = Short.MAX_VALUE;
							} else {
								returnData[i][j] = (short)newValue;
							}							
						}
						
					}
				}
				
				//ApplicationWindow.logger.info("started with value "+data[10][10]+" finished with value "+returnData[10][10]);

				return returnData;

			} else if (kernelData instanceof int[][]) {
				int[][] data = (int[][])kernelData;
				
				return null;
				
			} else if (kernelData instanceof float[][]) {
				float[][] data = (float[][])kernelData;
				
				return null;
			}
			else {
				throw new FitsException("Cannot understand file, it has a type="+kernelData.getClass().getName());
			}	
	}		
}
