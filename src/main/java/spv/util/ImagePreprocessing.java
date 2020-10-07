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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
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
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;


/**
 * Util class for referencing a bunch of FITS files,
 * interfacing with plate solving utility and finally
 * updating the FITS header files. 
 * 
 * @author Petros Pissias
 *
 */
public class ImagePreprocessing {

	private final File astapExecutableFullPath;
	
	private final File alignedFitsFolderFullPath;
	
	//todo close all as ASTAP cannot read them?
	//vgazw ta properties kai ta methods ta anoigoun kathe fora
	//exw method close all fits files gia na kleisoun ola kai na synexisei to processing apo astap 
	
	public static synchronized ImagePreprocessing getInstance(File astapExecutableFullPath, File alignedFitsFolderFullPath) throws IOException, FitsException, ConfigurationException {
		//first create config file if it does not exist
		
		//get user home if it exists
		String userhome = System.getProperty("user.home");
		if (userhome == null) {
			userhome = "";
		}
		//create file
		File configurationFile = new File(userhome+"/spacepixelviewer.config");
		System.out.println("Will use config file:"+configurationFile.getAbsolutePath());
		
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
	 * 
	 * @param aspsExecutableFullPath
	 * @param alignedFitsFolderFullPath
	 * @throws IOException 
	 * @throws FitsException 
	 */
	private ImagePreprocessing(File astapExecutableFullPath, File alignedFitsFolderFullPath) throws IOException, FitsException {
		this.alignedFitsFolderFullPath = alignedFitsFolderFullPath;
		this.astapExecutableFullPath = astapExecutableFullPath;
		
	}
	
	/**
	 * Returns the Fits objects of the associated files of this directory
	 * @return sorted by filename
	 * @throws IOException
	 * @throws FitsException
	 */
	public Fits[] getFitsFiles() throws IOException, FitsException {

		File[] fitsFiles = getFitsFilesDetails();
		Fits[] ret = new Fits[fitsFiles.length];
	
		for (int i=0;i<fitsFiles.length;i++) {
			ret[i] = new Fits(fitsFiles[i]);
		}
		
		return ret;
	}

	/**
	 * Returns FITS files in the provided directory
	 * @return ordered by filename 
	 * @throws IOException
	 * @throws FitsException
	 */
	public File[] getFitsFilesDetails() throws IOException, FitsException {
		
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
	public static void closeFitsFiles(Fits[] fitsFiles) throws IOException {
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
	 */
	public Future<PlateSolveResult> solve(String fitsFileFullPath, boolean astap, boolean astrometry) {
		System.out.println("trying to solve image astap="+astap+" astrometry="+astrometry);
		
		if (astap) {
			FutureTask<PlateSolveResult> task = new FutureTask<PlateSolveResult>(new Callable<PlateSolveResult>() {
	
				@Override
				public PlateSolveResult call() throws Exception {
					//map that stores 
					Map<String, String> solveResult = new HashMap<String, String>();
					
					//call ASTAP with correct arguments
					//do a simple test run of astap
					String[] cmdArray = new String[6];
					cmdArray[0] = astapExecutableFullPath.getAbsolutePath();
					cmdArray[1] = "-f";
					cmdArray[2] = fitsFileFullPath;
					cmdArray[3] = "-r";
					cmdArray[4] = "360"; //blind if necessary
					cmdArray[5] = "-wcs";
					
					try {
						Process proc = Runtime.getRuntime().exec(cmdArray, null, astapExecutableFullPath.getParentFile());
						//proc.
					} catch (IOException e) {
						JOptionPane.showMessageDialog(new JFrame(), "Cannot execute ASTAP:"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);
					}
					
					//waitfor results and return to the user
					ASTAPSolveResultsReader astapSolveResultsReader = new ASTAPSolveResultsReader(fitsFileFullPath);
					PlateSolveResult ret = astapSolveResultsReader.getSolveResult();				
					return ret;
				}			
			});
			
			ExecutorService executor = Executors.newFixedThreadPool(1);
			executor.execute(task);
			return task;
		}		
		else if (astrometry) {
			AstrometryDotNet astrometryInterface = new AstrometryDotNet();
			try {
				astrometryInterface.login();
				Future<PlateSolveResult> solveResult = astrometryInterface.blindSolve(new File(fitsFileFullPath));
				return solveResult;
				
			} catch (IOException | InterruptedException e) {
				JOptionPane.showMessageDialog(new JFrame(), "Could not solve image with astrometry.net :"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);
			}

		}
		
		return null;
	}
}
