/**
 * 
 */
package spv.util;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.configuration2.FileBasedConfiguration;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.apache.commons.configuration2.ex.ConfigurationException;

/**
 * @author Petros Pissias
 *
 */
public class ASTAPSolveResultsReader {

	private final String fileBeingSolvedFullPath;

	public String getFileBeingSolved() {
		return fileBeingSolvedFullPath;
	}

	public ASTAPSolveResultsReader(String fileBeingSolvedFullPath) {
		super();
		this.fileBeingSolvedFullPath = fileBeingSolvedFullPath;
	} 

	public PlateSolveResult getSolveResult() throws ConfigurationException, IOException {
		
		//determine .ini filename
		String iniFileName = getExpectedIniFilename(fileBeingSolvedFullPath);
		
		//wait for file to be present
		File iniFile = new File(iniFileName);
		while (!iniFile.exists()) {
			try {
				System.out.println("Waiting for file to become available:"+iniFileName);
				Thread.sleep(1000);
			} catch (InterruptedException e) {
			}
		}
		
		//read .ini file
		Configurations configs = new Configurations();
		FileBasedConfigurationBuilder<PropertiesConfiguration> iniFileConfigBuilder = configs.propertiesBuilder(iniFile);
		FileBasedConfiguration iniFileConfig = iniFileConfigBuilder.getConfiguration();
		
		//basic results
		String PLTSOLVD = iniFileConfig.getString("PLTSOLVD");
		String WARNING = iniFileConfig.getString("WARNING");
		//solve result (including all properties)
		Map<String,String> solveResult = new HashMap<String,String>();		
		Iterator<String> keysIter = iniFileConfig.getKeys();
		while (keysIter.hasNext()) {
			String key = keysIter.next();
			solveResult.put(key, iniFileConfig.getString(key));
		}
		
		if (PLTSOLVD.equals("T")) {
			return new PlateSolveResult(true, null, WARNING, solveResult);
		} else if (PLTSOLVD.equals("F")) {
			String ERROR = iniFileConfig.getString("ERROR");
			return new PlateSolveResult(false, ERROR, WARNING, solveResult);
		} else {
			throw new IOException("Unexpected value at ini file PLTSOLVD="+PLTSOLVD);
		}
	}
	
	/**
	 * Expected .ini file
	 * @param fitsFileName
	 * @return
	 */
	private String getExpectedIniFilename(String fitsFileName) {		
		int lastSepPosition = fitsFileName.lastIndexOf(".");		
		return fitsFileName.substring(0, lastSepPosition)+".ini";

	}
	
	
}
