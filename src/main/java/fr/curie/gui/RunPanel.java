package fr.curie.gui;

import ij.IJ;
import ij.ImagePlus;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.ResourceBundle;

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

    private MainApplication_Frame mainFrame;
    private ResourceBundle bundle;
    private String baseKey;
    private String modelPath;
    private boolean defaultParameters;


    public RunPanel(MainApplication_Frame mainFrame) {

        this.mainFrame = mainFrame;
        this.bundle = mainFrame.getResourceBundle(); // Get bundle from main frame

        // Add the rootPanel from the .form file to this JPanel
        this.setLayout(new BorderLayout());
        this.add(rootPanel, BorderLayout.CENTER);

        // Setup listeners - they will be generic, their context comes from fields
        setupListeners();
    }

    /**
     * Configures the entire panel for a specific task and model combination.
     */
    public void configurePanel(String taskId, String modelId) {
        this.baseKey = "run." + taskId + "." + modelId;

        titleLabel.setText(bundle.getString(baseKey + ".title"));

        String contentKey = baseKey + ".content";
        String contentFolder = bundle.getString("content.folder");
        String markdownFilePath = contentFolder + bundle.getString(contentKey);
        descriptionArea.setText(ContentLoader.loadAndParseMarkdown(markdownFilePath));
        descriptionArea.setCaretPosition(0); // Scroll to top
        // by default : default params are used
        defaultParameters = true;
        defaultParamRButton.setSelected(true);
        userParamRButton.setSelected(false);

        // Retrieve the model folder + check if valid
        this.modelPath = getModelFolder(taskId, modelId);

        // --- Configure the RUN button's action command or properties ---
        runButton.setActionCommand(taskId + ":" + modelId);
    }

    private void setupListeners() {
        exampleImageButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String exampleImageName = bundle.getString(baseKey + ".exampleImage");
                if (!exampleImageName.isEmpty()) {
                    // Construct the absolute path to the image
                    String absoluteImagePath = modelPath + File.separator + exampleImageName;
                    // Check if the image actually exists
                    File imageFile = new File(absoluteImagePath);
                    if (!imageFile.exists() ) {
                        IJ.error("Image Not Found", "The required example image does not exist at:\n" + absoluteImagePath + "\nPlease ensure the model is downloaded and in the correct location.");
                        return;
                    }
                        IJ.open(absoluteImagePath);

                }
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
                String[] command = e.getActionCommand().split(":");
                String taskId = command[0];
                String modelId = command[1];
                // get the active image (necessary for some plugins)
                ImagePlus imp = IJ.getImage();
                String impTitle = (imp != null) ? imp.getTitle() : "";

                // Running the macro on a new thread to avoid freezing the GUI
                new Thread(() -> {
                    try {
                        String macroKey = defaultParameters ? baseKey + ".macroTemplate.default": baseKey + ".macroTemplate.options";

                        if (bundle.containsKey(macroKey)) {
                            // 1. Get the template string
                            String macroTemplate = bundle.getString(macroKey);

                            // 2. Replace all placeholders
                            String finalMacroScript = macroTemplate.replace("{MODEL_PATH}", modelPath);
                            finalMacroScript = finalMacroScript.replace("{IMP_TITLE}", impTitle);
                            finalMacroScript = finalMacroScript.replace("\\", "/");

                            // 3. Execute the entire script
                            IJ.runMacro(finalMacroScript);

                        } else {
                            IJ.error("Configuration Error", "No macro definition found for this task/model combination.");
                        }

                    } catch (Exception ex) {
                        IJ.error("Macro Execution Failed", "Could not run the macro. Check properties file and model configuration.\nError: " + ex.getMessage());
                        ex.printStackTrace();
                    }
                }).start();
            }
        });
    }

    private String getModelFolder(String taskId, String modelId){
        try {
            // Get the base path for all models
            String baseModelPath = mainFrame.getModelDirectoryPath();
            if (baseModelPath == null) {
                return null;
            }

            // Get the specific subfolder name for this model from properties
            String modelSubfolder = bundle.getString(baseKey + ".modelSubfolder");

            // Construct the full, absolute path to the specific model directory
            String absoluteModelPath = baseModelPath + File.separator + modelSubfolder;

            // Check if the specific model directory actually exists
            File modelDir = new File(absoluteModelPath);
            if (!modelDir.exists() || !modelDir.isDirectory()) {
                IJ.error("Model Not Found", "The required model directory does not exist at:\n" + absoluteModelPath + "\nPlease ensure the model is downloaded and in the correct location.");
                return null;
            }

            return absoluteModelPath;

        } catch (Exception ex) {
            // Catch any MissingResourceException or other errors
            IJ.error("Macro Execution Failed", "Could not run the macro. Check properties file and model configuration.\nError: " + ex.getMessage());
            ex.printStackTrace();
        }
        return null;
    }
}
