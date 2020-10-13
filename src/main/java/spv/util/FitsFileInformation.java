/**
 * 
 */
package spv.util;

import java.util.HashMap;
import java.util.Map;

import io.github.ppissias.astrolib.PlateSolveResult;

/**
 * Holds high level information for a FITS file
 * @author Petros Pissias
 *
 */
public class FitsFileInformation {

	private String filePath;
	private String fileName;
	private boolean monochrome;
	private int sizeWidth;
	private int sizeHeight;
	
	private final Map<String,String> fitsHeader = new HashMap<String, String>(); 
	private PlateSolveResult solveResult;
	

	public String getFilePath() {
		return filePath;
	}
	public void setFilePath(String filePath) {
		this.filePath = filePath;
	}
	public boolean isMonochrome() {
		return monochrome;
	}
	public void setMonochrome(boolean monochrome) {
		this.monochrome = monochrome;
	}
	public int getSizeWidth() {
		return sizeWidth;
	}
	public void setSizeWidth(int sizeWidth) {
		this.sizeWidth = sizeWidth;
	}
	public int getSizeHeight() {
		return sizeHeight;
	}
	public void setSizeHeight(int sizeHeight) {
		this.sizeHeight = sizeHeight;
	}


	
	/**
	 * @param filePath
	 * @param fileName
	 * @param monochrome
	 * @param sizeWidth
	 * @param sizeHeight
	 */
	public FitsFileInformation(String filePath, String fileName, boolean monochrome, int sizeWidth, int sizeHeight) {
		super();
		this.filePath = filePath;
		this.fileName = fileName;
		this.monochrome = monochrome;
		this.sizeWidth = sizeWidth;
		this.sizeHeight = sizeHeight;
	}
	public String getFileName() {
		return fileName;
	}
	public void setFileName(String fileName) {
		this.fileName = fileName;
	}
	public PlateSolveResult getSolveResult() {
		return solveResult;
	}
	public void setSolveResult(PlateSolveResult solveResult) {
		this.solveResult = solveResult;
	}
	public Map<String, String> getFitsHeader() {
		return fitsHeader;
	}
	@Override
	public String toString() {
		return "FitsFileInformation [filePath=" + filePath + ", monochrome=" + monochrome + ", sizeWidth=" + sizeWidth
				+ ", sizeHeight=" + sizeHeight + ", fitsHeader=" + fitsHeader + ", solveResult=" + solveResult + "]";
	}

}
