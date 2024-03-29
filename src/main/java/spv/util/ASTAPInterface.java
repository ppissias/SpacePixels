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
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import javax.swing.JFrame;
import javax.swing.JOptionPane;

import io.github.ppissias.astrolib.PlateSolveResult;

/**
 * Main ASTAP interface for plate solving.
 * Returns a task that will call the executable with the correct arguments and will wait until the result is available.
 * 
 *
 */
public class ASTAPInterface {

	public static FutureTask<PlateSolveResult> solveImage(File astapExecutable, String imageFullPath) {
		FutureTask<PlateSolveResult> task = new FutureTask<PlateSolveResult>(new Callable<PlateSolveResult>() {
			
			@Override
			public PlateSolveResult call() throws Exception {
			
				//call ASTAP with correct arguments
				//do a simple test run of astap
				String[] cmdArray = new String[11];
				cmdArray[0] = astapExecutable.getAbsolutePath();
				cmdArray[1] = "-f";
				cmdArray[2] = imageFullPath;
				cmdArray[3] = "-r";
				cmdArray[4] = "360"; //blind if necessary
				cmdArray[5] = "-z";
				cmdArray[6] = "0"; 
				cmdArray[7] = "-fov";
				cmdArray[8] = "0"; 				
				cmdArray[9] = "-wcs";
				cmdArray[10] = "-annotate";
				
				
				try {
					Process proc = Runtime.getRuntime().exec(cmdArray, null, astapExecutable.getParentFile());
					//wait 2 seconds
					Thread.sleep(2000);
					
					if (!proc.isAlive()) {
						if (proc.exitValue() > 0) {
							JOptionPane.showMessageDialog(new JFrame(), "ASTAP exited with error code:"+proc.exitValue(), "Error",JOptionPane.ERROR_MESSAGE);
						}
					}
					//proc.
				} catch (IOException e) {
					JOptionPane.showMessageDialog(new JFrame(), "Cannot execute ASTAP:"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);
				}
				
				//wait for results and return to the user
				ASTAPSolveResultsReader astapSolveResultsReader = new ASTAPSolveResultsReader(imageFullPath);
				PlateSolveResult ret = astapSolveResultsReader.getSolveResult();				
				return ret;
			}			
		});
		
		return task;
	}
}
