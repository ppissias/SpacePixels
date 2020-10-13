/**
 * 
 */
package spv.gui;

import javax.swing.table.AbstractTableModel;

import io.github.ppissias.astrolib.PlateSolveResult;
import spv.util.FitsFileInformation;

/**
 * The table model showing information from the FITS files
 * @author Petros Pissias
 *
 */
public class FitsFileTableModel extends AbstractTableModel {

	private final FitsFileInformation[] fitsfiles;

	private final String[] columns = {"filename","moco/color","width","length","header","solved","obj"};
	 
	public FitsFileTableModel(FitsFileInformation[] fitsfiles) {
		this.fitsfiles = fitsfiles;
	}
	

	@Override
	public int getColumnCount() {
		return columns.length;
	}

	@Override
	public int getRowCount() {		
		return fitsfiles.length;
	}

	
	@Override
    public String getColumnName(int col) {
        return columns[col];
    }

	@Override
	public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
		if (aValue instanceof PlateSolveResult) {
			fitsfiles[rowIndex].setSolveResult((PlateSolveResult)aValue);
		}
		super.setValueAt(aValue, rowIndex, columnIndex);
	}
	
    @Override
    public Object getValueAt(int row, int col) {
    	if (row >= 0 ) {
	    	switch (col) { 
	    		case 0 : { //filename
	    			return fitsfiles[row].getFileName();
	
	    		}
	
	    		case 1 : { //moco/color
	    			if (fitsfiles[row].isMonochrome()) {
	    				return "Monochrome";
	    			} else {
	    				return "Color";
	    			}
	
	    		}
	
	    		case 2 : { //width
	    			return ""+fitsfiles[row].getSizeWidth();
	    		}
	
	    		case 3 : { //length
	    			return ""+fitsfiles[row].getSizeHeight();
	    		}
	
	    		case 4 : { //filename
	    			return fitsfiles[row].getFitsHeader().size()+" elements";
	    		}
	
	    		case 5 : { //solved
	    			PlateSolveResult solveResult = fitsfiles[row].getSolveResult();
	    			if (solveResult != null) {
	    				if (solveResult.isSuccess()) {
	    					return "yes";
	    				} else {
	    					return "no";
	    				}
	    			} else {
	    				return "no";
	    			}
	    		}
	    		
	    		case 6 :{ //entire object (hidden from the GUI)
	    			return fitsfiles[row];
	    		}
	    		default : {
	    			return "";
	    		}
	    	}
    	} else {
    		return null;
    	}

    }
    
    @Override
    public boolean isCellEditable(int row, int col)
        { return false; }
    

}
