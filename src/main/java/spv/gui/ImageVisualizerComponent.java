/*
 * SpacePixels
 * 
 * Copyright (c)2020-2023, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package spv.gui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;

/**
 * @author Petros Pissias
 *
 */
public class ImageVisualizerComponent extends Component {

	private BufferedImage image=null;
	/**
	 * 
	 */
	public ImageVisualizerComponent() {
		ApplicationWindow.logger.info("init");

	}

	public void setImage(BufferedImage image) {
		this.image = image;
		ApplicationWindow.logger.info("setImage");

		repaint();
		
	}
	@Override
	public Dimension getPreferredSize() {
		if (image == null) {
			return new Dimension(300,300);
		} else {
			return new Dimension(image.getWidth(), image.getHeight());
		}
	}
	
	@Override
	public void paint(Graphics g) {
		//super.paint(g);
		
		if (image != null) {
			ApplicationWindow.logger.info("drawing image");
			g.drawImage(image, 0, 0, null);
		} else {
			//just draw something
			ApplicationWindow.logger.info("drawing something");

			for (int i=0;i<100;i++) {
				for (int j=0;j<100;j++) {
					g.drawRect(i, j, i+j, i+j);
				}
			}
		}
	}
}
