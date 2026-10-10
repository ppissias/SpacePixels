/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.gui;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * @author Petros Pissias
 *
 */
public class ImageVisualizerComponent extends Component {

    private BufferedImage image = null;

    /**
     *
     */
    public ImageVisualizerComponent() {

    }

    public void setImage(BufferedImage image) {
        this.image = image;

        repaint();

    }

    @Override
    public Dimension getPreferredSize() {
        if (image == null) {
            return new Dimension(300, 300);
        } else {
            return new Dimension(image.getWidth(), image.getHeight());
        }
    }

    @Override
    public void paint(Graphics g) {
        //super.paint(g);

        if (image != null) {
            g.drawImage(image, 0, 0, null);
        } else {
            //just draw something

            for (int i = 0; i < 100; i++) {
                for (int j = 0; j < 100; j++) {
                    g.drawRect(i, j, i + j, i + j);
                }
            }
        }
    }
}
