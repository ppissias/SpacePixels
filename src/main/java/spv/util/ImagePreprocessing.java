/**
 * 
 */
package spv.util;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
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
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;
import nom.tam.util.Cursor;
import spv.gui.ApplicationWindow;


/**
 * Util class for referencing a bunch of FITS files,
 * interfacing with plate solving utility and finally
 * updating the FITS header files. 
 * 
 * @author Petros Pissias
 *
 */
public class ImagePreprocessing {

	//astap executable path
	private final File astapExecutable;
	
	//fits folder path
	private final File alignedFitsFolderFullPath;

	//executor service
	private final ExecutorService executor = Executors.newFixedThreadPool(1);
	
	//astrometry.net interface
	private final AstrometryDotNet astrometryNetInterface = new AstrometryDotNet();
	
	/**
	 * Returns new instance
	 * @param astapExecutableFullPath
	 * @param alignedFitsFolderFullPath
	 * @return
	 * @throws IOException
	 * @throws FitsException
	 * @throws ConfigurationException
	 */
	public static synchronized ImagePreprocessing getInstance(File astapExecutableFullPath, File alignedFitsFolderFullPath) throws IOException, FitsException, ConfigurationException {
		//first create config file if it does not exist
		
		//get user home if it exists
		String userhome = System.getProperty("user.home");
		if (userhome == null) {
			userhome = "";
		}
		//create file
		File configurationFile = new File(userhome+"/spacepixelviewer.config");
		ApplicationWindow.logger.info("Will use config file:"+configurationFile.getAbsolutePath());
		
		configurationFile.createNewFile();
		
		//check if a value was provided for ASTAP 
		if (astapExecutableFullPath == null) {
			//read previous value from properties file 
			Configurations configs = new Configurations();
			FileBasedConfigurationBuilder<PropertiesConfiguration> configBuilder = configs.propertiesBuilder(configurationFile);
			FileBasedConfiguration config = configBuilder.getConfiguration();
			
			String astapConfigExecPath = config.getString("astap");
			if (astapConfigExecPath == null || "".equals(astapConfigExecPath)) {
				//does not exist, do nothing
				
			} else {
				//it exists, use the one from configuration
				File astapExecutablePathFromConfig = new File(astapConfigExecPath);
				astapExecutableFullPath = astapExecutablePathFromConfig;
			}
			
		} else {
			//update properties file
			Configurations configs = new Configurations();
			FileBasedConfigurationBuilder<PropertiesConfiguration> configBuilder = configs.propertiesBuilder(configurationFile);
			FileBasedConfiguration config = configBuilder.getConfiguration();
			
			config.setProperty("astap", astapExecutableFullPath.getAbsolutePath());
			configBuilder.save();
			
		}
		return new ImagePreprocessing(astapExecutableFullPath,alignedFitsFolderFullPath);
	}
	

	/**
	 * internal constructor
	 * @param aspsExecutableFullPath
	 * @param alignedFitsFolderFullPath
	 * @throws IOException 
	 * @throws FitsException 
	 */
	private ImagePreprocessing(File astapExecutable, File alignedFitsFolderFullPath) throws IOException, FitsException {
		this.alignedFitsFolderFullPath = alignedFitsFolderFullPath;
		this.astapExecutable = astapExecutable;
		
	}
	
	/**
	 * Returns the FITS file information
	 * @return
	 * @throws FitsException 
	 * @throws IOException 
	 */
	public FitsFileInformation[] getFitsfileInformation() throws IOException, FitsException {
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
					short[][] data = (short[][])fitsFiles[i].getHDU(0).getKernel();
					height=data.length;
					width=data[0].length;						
				} else if (kernelData instanceof float[][]) {
					short[][] data = (short[][])fitsFiles[i].getHDU(0).getKernel();
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
	 */
	public Future<PlateSolveResult> solve(String fitsFileFullPath, boolean astap, boolean astrometry) throws FitsException {
		ApplicationWindow.logger.info("trying to solve image astap="+astap+" astrometry="+astrometry);
		
		if (astap) {
			FutureTask<PlateSolveResult> task = ASTAPInterface.solveImage(astapExecutable, fitsFileFullPath);
			
			executor.execute(task);
			return task;
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
}
