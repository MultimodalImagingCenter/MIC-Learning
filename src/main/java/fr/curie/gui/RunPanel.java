package fr.curie.gui;

import ij.IJ;
import ij.ImagePlus;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;


public class RunPanel extends JPanel{
    private JPanel rootPanel;
    private JPanel runButtonsPanel;
    private JPanel descriptionPanel;
    private JButton exampleImageButton;
    private JButton runButton;
    private JLabel titleLabel;
    private JButton userImageButton;
    private JEditorPane descriptionArea;
    private JRadioButton defaultParamRButton;
    private JRadioButton userParamRButton;
    private JScrollPane scrollPane;

    private final MainApplication_Frame mainFrame;
    protected StructureManager uiStructure;
    private String modelPath;
    private UseCaseConfig currentUseCase;
    private boolean defaultParameters;


    public RunPanel(MainApplication_Frame mainFrame) {

        this.mainFrame = mainFrame;
        try {
            this.uiStructure = mainFrame.getUIStructure();
        } catch (Exception e) {
            System.err.println(getClass().getSimpleName() + ": Error loading resource bundle");
            this.uiStructure = null;
        }

        this.setLayout(new BorderLayout());
        this.add(rootPanel, BorderLayout.CENTER);

        // Setup listeners
        setupListeners();
    }

    /**
     * Configures the entire panel for a specific example model
     */
    public void configurePanel(String exampleId) {
        // load the configuration for this use case.
        this.currentUseCase = uiStructure.loadUseCase(mainFrame.getModelDirectoryPath(), exampleId);

        // handle the case where the configuration might fail to load.
        if (this.currentUseCase == null) {
            titleLabel.setText("Error");
            descriptionArea.setText("<html>Could not load the configuration for " + exampleId + ".<br>Check logs for details.</html>");
            runButton.setEnabled(false);
            exampleImageButton.setEnabled(false);
            userImageButton.setEnabled(false);
            return;
        }

        // populate the UI elements from the loaded UseCaseConfig object.
        titleLabel.setText(currentUseCase.getDescriptionTitle());

        String markdownFilePath = uiStructure.getExampleDescriptionPath(exampleId);
        descriptionArea.setText(ContentLoader.loadAndParseMarkdown(markdownFilePath));
        descriptionArea.setCaretPosition(0); // Scroll to top

        // reset UI state with default param
        defaultParameters = true;
        defaultParamRButton.setSelected(true);
        userParamRButton.setSelected(false);
        userImageButton.setEnabled(true);

        // only enable the run button if a valid model path is provided in the config
        runButton.setEnabled(true);
        // get model path
        this.modelPath = currentUseCase.getModelDirectoryPath();
        System.out.println("model path = " + modelPath);
        // check if path valid
        File modelDir = new File(modelPath);
        if (!modelDir.exists() || !modelDir.isDirectory()) {
            IJ.error("Model Not Found", "The required model directory does not exist at:\n" + modelPath);
            this.modelPath = null;
            runButton.setEnabled(false);
        }


        // only enable the example image button if a valid path is provided in the config
        // and path exist
        if (currentUseCase.getExampleImagePath() == null || currentUseCase.getExampleImagePath().isEmpty()) {
            exampleImageButton.setEnabled(false);
            IJ.error("Image Path Not Found", "No example image path was found for model " + exampleId );
        } else {
            File imageFile = new File(currentUseCase.getExampleImagePath());
            if (!imageFile.exists() ) {
                IJ.error("Image Not Found", "The required example image does not exist at:\n" + currentUseCase.getExampleImagePath());
                exampleImageButton.setEnabled(false);
            } else {
                exampleImageButton.setEnabled(true);
            }
        }
    }


    private void setupListeners() {
        exampleImageButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String exampleImagePath = currentUseCase.getExampleImagePath();
                IJ.open(exampleImagePath);
                // (no need to check for null, the button is disabled if the path is missing)
            }
        });

        userImageButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Create and start a new thread to handle the blocking operation
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        // This code now runs on a background thread, NOT the EDT.
                        IJ.open();
                    }
                }).start();
            }
        });

        defaultParamRButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                defaultParameters=true;
                userParamRButton.setSelected(false);
            }
        });

        userParamRButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                defaultParameters=false;
                defaultParamRButton.setSelected(false);
            }
        });

        runButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // get the active image (necessary for some plugins)
                ImagePlus imp = IJ.getImage();
                String impTitle = (imp != null) ? imp.getTitle() : "";

                // Running the macro on a new thread to avoid freezing the GUI
                new Thread(() -> {
                    try {
                        // 1. Get the correct macro template
                        String macroTemplate = defaultParameters
                                ? currentUseCase.getDefaultMacro()
                                : currentUseCase.getOptionMacro();

                        if (macroTemplate != null && !macroTemplate.isEmpty()) {
                            // 2. Get the pre-resolved model path
                            String modelDir = modelPath;

                            // 3. Replace placeholders.
                            modelDir=modelDir.replace('\\','/');
                            String finalMacroScript = macroTemplate.replace("{MODEL_PATH}", modelDir)
                                    .replace("{IMP_TITLE}", impTitle);

                            // 4. Execute the script.
                            IJ.runMacro(finalMacroScript);
                        } else {
                            IJ.error("Configuration Error", "No macro definition found for this parameter choice.");
                        }

                    } catch (Exception ex) {
                        IJ.error("Macro Execution Failed", "Could not run the macro. Check usecase.properties and model configuration.\nError: " + ex.getMessage());
                        ex.printStackTrace();
                    }

                }).start();
            }
        });


    }
}
