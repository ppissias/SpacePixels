/*
 * SpacePixels
 * 
 * Copyright (c)2020-2023, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package spv.util;

import java.io.File;
import java.io.IOException;

import org.apache.commons.configuration2.FileBasedConfiguration;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.apache.commons.configuration2.ex.ConfigurationException;

public class SPConfigurationFile {

	private final Configurations configs = new Configurations();
	private final FileBasedConfigurationBuilder<PropertiesConfiguration> configBuilder;
	private final FileBasedConfiguration config;	
	/**
	 * @throws ConfigurationException 
	 * @throws IOException 
	 * 
	 */
	public SPConfigurationFile(String filename) throws ConfigurationException, IOException {
		File configurationFile = new File(filename);
		
		//create if it does not exist
		if (!configurationFile.exists()) {
			configurationFile.createNewFile();
		}
		configBuilder = configs.propertiesBuilder(configurationFile);
		config = configBuilder.getConfiguration();			
	}

	public void setProperty(String key, String value) {
		config.setProperty(key, value);
	}
	
	public String getProperty(String key) {
		return config.getString(key);
	}
	
	public void save() throws ConfigurationException {
		configBuilder.save();
	}
}
