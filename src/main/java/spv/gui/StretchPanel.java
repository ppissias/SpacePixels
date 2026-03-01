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

import nom.tam.fits.FitsException;
import spv.util.StretchAlgorithm;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.IOException;

public class StretchPanel extends JPanel {
    // link to main window
    private ApplicationWindow mainAppWindow;


    private JSlider stretchSlider;

    private JCheckBox stretchCheckbox;
    private JComboBox<StretchAlgorithm> stretchAlgoCombo = new JComboBox<StretchAlgorithm>();

    public StretchAlgorithm getStretchAlgorithm() {
        return (StretchAlgorithm) stretchAlgoCombo.getSelectedItem();
    }

    public boolean isStretchEnabled() {
        return stretchCheckbox.isSelected();
    }

    public JSlider getStretchSlider() {
        return stretchSlider;
    }

    private JSlider stretchIterationsSlider;

    public JSlider getStretchIterationsSlider() {
        return stretchIterationsSlider;
    }

    /**
     * Create the panel.
     */
    public StretchPanel(ApplicationWindow mainAppWindow) {
        this.mainAppWindow = mainAppWindow;
        GridBagLayout gridBagLayout = new GridBagLayout();
        gridBagLayout.columnWidths = new int[]{231, 332, 0};
        gridBagLayout.rowHeights = new int[]{21, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,};
        gridBagLayout.columnWeights = new double[]{0.0, 1.0, Double.MIN_VALUE};
        gridBagLayout.rowWeights = new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, Double.MIN_VALUE};
        setLayout(gridBagLayout);


        stretchCheckbox = new JCheckBox("Stretch");
        stretchCheckbox.setToolTipText("if checked the images will also be stretched");

        stretchCheckbox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent arg0) {
                //set other controls accordingly
                mainAppWindow.getMainApplicationPanel().setBatchStretchButtonEnabled(isStretchEnabled());
                stretchAlgoCombo.setEnabled(isStretchEnabled());

                if (stretchCheckbox.isSelected()) {
                    // Code to execute when it's selected
                    stretchSlider.setEnabled(true);
                    stretchIterationsSlider.setEnabled(true);

                    mainAppWindow.setStretchFrameVisible(true);
                    try {
                        mainAppWindow.getMainApplicationPanel().updateImageStretchWindow();
                    } catch (FitsException | IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    // Code to execute when not selected
                    stretchSlider.setEnabled(false);
                    stretchIterationsSlider.setEnabled(false);

                    mainAppWindow.setStretchFrameVisible(false);

                }
            }
        });
        stretchCheckbox.setFont(new Font("Tahoma", Font.PLAIN, 13));
        GridBagConstraints gbc_chckbxNewCheckBox = new GridBagConstraints();
        gbc_chckbxNewCheckBox.anchor = GridBagConstraints.WEST;
        gbc_chckbxNewCheckBox.insets = new Insets(0, 0, 5, 5);
        gbc_chckbxNewCheckBox.gridx = 0;
        gbc_chckbxNewCheckBox.gridy = 15;
        add(stretchCheckbox, gbc_chckbxNewCheckBox);
        stretchAlgoCombo.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent arg0) {
                if (stretchCheckbox.isSelected()) {
                    try {
                        mainAppWindow.getMainApplicationPanel().updateImageStretchWindow();
                    } catch (FitsException | IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });


        stretchAlgoCombo.setEnabled(false);
        stretchAlgoCombo.setToolTipText("choose stretching algorithm");

        stretchAlgoCombo.setModel(new DefaultComboBoxModel<StretchAlgorithm>(StretchAlgorithm.values()));
        stretchAlgoCombo.setSelectedIndex(0);
        GridBagConstraints gbc_stretchAlgoCombo = new GridBagConstraints();
        gbc_stretchAlgoCombo.anchor = GridBagConstraints.WEST;
        gbc_stretchAlgoCombo.insets = new Insets(0, 0, 5, 0);
        gbc_stretchAlgoCombo.gridx = 1;
        gbc_stretchAlgoCombo.gridy = 15;
        add(stretchAlgoCombo, gbc_stretchAlgoCombo);


        JLabel stretchIntensityLabel = new JLabel("Intensity");
        GridBagConstraints gbc_stretchIntensityLabel = new GridBagConstraints();
        gbc_stretchIntensityLabel.insets = new Insets(0, 0, 5, 5);
        gbc_stretchIntensityLabel.gridx = 0;
        gbc_stretchIntensityLabel.gridy = 16;
        add(stretchIntensityLabel, gbc_stretchIntensityLabel);

        JLabel stretchIterationsLabel = new JLabel("Iterations");
        GridBagConstraints gbc_stretchIterationsLabel = new GridBagConstraints();
        gbc_stretchIterationsLabel.anchor = GridBagConstraints.WEST;
        gbc_stretchIterationsLabel.insets = new Insets(0, 0, 5, 0);
        gbc_stretchIterationsLabel.gridx = 1;
        gbc_stretchIterationsLabel.gridy = 16;
        add(stretchIterationsLabel, gbc_stretchIterationsLabel);

        stretchSlider = new JSlider();
        stretchSlider.setToolTipText("Intensity");
        stretchSlider.addChangeListener(new ChangeListener() {
            public void stateChanged(ChangeEvent arg0) {
                try {
                    mainAppWindow.getMainApplicationPanel().updateImageStretchWindow();
                } catch (FitsException | IOException e) {
                    e.printStackTrace();
                }
            }
        });
        stretchSlider.setEnabled(false);

        GridBagConstraints gbc_stretchSlider = new GridBagConstraints();
        gbc_stretchSlider.insets = new Insets(0, 0, 5, 5);
        gbc_stretchSlider.gridx = 0;
        gbc_stretchSlider.gridy = 17;
        add(stretchSlider, gbc_stretchSlider);

        stretchIterationsSlider = new JSlider();
        stretchIterationsSlider.setPaintTicks(true);
        stretchIterationsSlider.setSnapToTicks(true);
        stretchIterationsSlider.setMajorTickSpacing(1);
        stretchIterationsSlider.setMinimum(1);
        stretchIterationsSlider.setValue(1);
        stretchIterationsSlider.setMaximum(20);
        stretchIterationsSlider.setToolTipText("Iterations");

        stretchIterationsSlider.addChangeListener(new ChangeListener() {
            public void stateChanged(ChangeEvent arg0) {
                try {
                    mainAppWindow.getMainApplicationPanel().updateImageStretchWindow();
                } catch (FitsException | IOException e) {
                    e.printStackTrace();
                }
            }
        });
        stretchIterationsSlider.setEnabled(false);

        GridBagConstraints gbc_stretchIterationsSlider = new GridBagConstraints();
        gbc_stretchIterationsSlider.anchor = GridBagConstraints.WEST;
        gbc_stretchIterationsSlider.insets = new Insets(0, 0, 5, 0);
        gbc_stretchIterationsSlider.gridx = 1;
        gbc_stretchIterationsSlider.gridy = 17;
        add(stretchIterationsSlider, gbc_stretchIterationsSlider);

        JLabel importLabel = new JLabel("Import parameters");
        importLabel.setFont(new Font("Tahoma", Font.PLAIN, 13));
        GridBagConstraints gbc_importLabel = new GridBagConstraints();
        gbc_importLabel.anchor = GridBagConstraints.WEST;
        gbc_importLabel.insets = new Insets(0, 0, 5, 5);
        gbc_importLabel.gridx = 0;
        gbc_importLabel.gridy = 18;
        add(importLabel, gbc_importLabel);


        //create action listener to change stretch parameter labels for EXTREME stretching
        stretchAlgoCombo.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                if (StretchAlgorithm.EXTREME.equals(stretchAlgoCombo.getSelectedItem())) {
                    //
                    stretchIntensityLabel.setText("Noise threshold");
                    stretchIterationsLabel.setText("Intensity");
                    stretchIterationsSlider.setValue(15);
                } else {
                    //other options
                    stretchIntensityLabel.setText("Intensity");
                    stretchIterationsLabel.setText("Iterations");
                }
            }

        });

    }


}
