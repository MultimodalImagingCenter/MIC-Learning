package fr.curie.tools.utilityplugins;

import fr.curie.tools.detection.DetectionUtils;
import fr.curie.tools.detection.ProcessedDetection;
import ij.*;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import ij.plugin.frame.RoiManager;

import java.awt.*;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import static fr.curie.tools.detection.DetectionUtils.loadClassIDsFromFile;
import static ij.plugin.frame.RoiManager.getRoiManager;

/**
 * ImageJ Plugin using a dynamic GenericDialog based on method selection.
 * Implements DialogListener to update UI elements.
 */
public class MaskConversion_Plugin implements PlugIn, DialogListener {

    private final String[] detectionMethods = {
            "Stack Mask (Single Stack)",
            "Instances per Class (Single Stack)",
            "Instance + Semantic Masks (Two Images)"
    };

    // Constants for choice indices
    private static final int METHOD_STACK_MASK = 0;
    private static final int METHOD_INSTANCE_PER_CLASS = 1;
    private static final int METHOD_INSTANCE_SEMANTIC = 2;

    // Output Option Labels
    private final String[] outputOptions = new String[]{
            "Add_ROIs to Manager",
            "Create_Stack of Instance Masks",
            "Create_Instance Mask",
            "Create_Semantic Mask",
            "Create_Stack_of_Instances_per_Class"
    };

    //Default states for output checkboxes
    private final boolean[] outputDefaults = {
            true,  // Default to "adding ROIs"
            false,
            false,
            false,
            false
    };

    // Dialog components
    private GenericDialog gd;
    private Choice methodChoice;
    private Choice instanceStackChoice;
    private Choice instanceMaskChoice;
    private Choice semanticMaskChoice;
    private Choice instancePerClasseChoice;
    private TextField classFilePathField;

    // --- Preference Key ---
    private static final String PREF_CLASS_FILE_PATH = "maskConversion.classFilePath";

    //
    int methodIndex;
    ImagePlus imp1;
    ImagePlus imp2;
    boolean outputRois;
    boolean outputStackMask;
    boolean outputInstanceMask;
    boolean outputSemanticMask;
    boolean outputInstancePerClass;
    String classFilePath;

    @Override
    public void run(String arg) {
        if (Macro.getOptions() != null) {
            //IJ.log("macro options");
            parseMacro();
        } else {
            askUser();
        }

        // Retrieve Class file (if exist)
        Map<String, Integer> classIdMap = null;
        if (classFilePath != null && !classFilePath.trim().isEmpty()) {
            classFilePath = classFilePath.trim();
            File f = new File(classFilePath);
            if (f.exists() && f.isFile()) {
                classIdMap = loadClassIDsFromFile(classFilePath);
                Prefs.set(PREF_CLASS_FILE_PATH, classFilePath);
            } else {
                IJ.log("Warning: Provided Class Name File path does not exist or is not a file: " + classFilePath);
            }
        } else {
            Prefs.set(PREF_CLASS_FILE_PATH, "");
        }
        IJ.log("imp1" + imp1);
        //Input Validation
        if (imp1 == null) {
            IJ.error("Mask Conversion", "Invalid image selection or missing Image.");
            return;
        }
        if (methodIndex == METHOD_INSTANCE_SEMANTIC && imp2 == null) {
            IJ.error("Mask Conversion", "Method requires 2 images, but selection is invalid or missing.");
            return;
        }
        if (methodIndex == METHOD_INSTANCE_SEMANTIC && imp1 == imp2) {
            IJ.log("Warning: The same image was selected for both Instance and Semantic masks.");
        }

        recordInMacro();

        // --- 2. Convert mask into ProcessedDetections ---
        List<ProcessedDetection> results = null;
        String chosenMethodName = detectionMethods[methodIndex];
        IJ.log("User selected method: " + chosenMethodName);

        try {
            switch (methodIndex) {
                case METHOD_STACK_MASK:
                    IJ.log("Processing with Stack Mask method using image: " + imp1.getTitle());
                    results = DetectionUtils.detectionFromStackMask(imp1, classIdMap);
                    break;

                case METHOD_INSTANCE_PER_CLASS:
                    IJ.log("Processing with Instances per Class method using image: " + imp1.getTitle());
                    results = DetectionUtils.detectionFromInstancePerClasses(imp1, classIdMap);
                    break;

                case METHOD_INSTANCE_SEMANTIC:
                    IJ.log("Processing with Instance + Semantic method using images: "
                            + imp1.getTitle() + " (Instance) and " + imp2.getTitle() + " (Semantic)");
                    results = DetectionUtils.detectionFromInstanceAndSemantic(imp1, imp2, classIdMap);
                    break;

                default:
                    IJ.error("Internal Error: Unknown method index selected.");
                    return;
            }
        } catch (Exception e) {
            IJ.error("Error during detection processing",
                    "An error occurred while running the '" + chosenMethodName + "' method:\n" + e.getMessage());
            e.printStackTrace();
            return;
        }

        // -- 4. Create ROIs and mask from ProcessedDetection

        if (results.isEmpty()) {
            IJ.log("Detection list is empty. No outputs will be generated.");
            IJ.showMessage("Detection Complete", "Method: " + chosenMethodName + "\nNo detections found.");
            return;
        }

        IJ.log("Generating selected outputs...");

        try {
            // --- ROI Output ---
            if (outputRois) {
                IJ.log("-> Adding ROIs to Manager...");
                RoiManager rm = getRoiManager(); // Get or create ROI Manager
                rm.reset(); // Delete previous ROIs
                DetectionUtils.addRoisToManager(rm, results, false, true);
                IJ.log("ROIs added to manager");
            }

            // --- Mask Outputs  ---
            if (outputStackMask) {
                IJ.log("-> Creating Stack of Instance Masks...");
                ImagePlus stackMask = DetectionUtils.createStackMask(imp1, results);
                if (stackMask != null) {
                    stackMask.show();
                } else {
                    IJ.log("   Failed to create Stack Mask.");
                }
            }

            if (outputInstanceMask) {
                IJ.log("-> Creating Instance Mask...");
                ImagePlus instanceMask = DetectionUtils.createInstanceMask(imp1, results);
                if (instanceMask != null) {
                    instanceMask.show();
                } else {
                    IJ.log("   Failed to create Instance Mask.");
                }
            }

            if (outputSemanticMask) {
                IJ.log("-> Creating Semantic Mask...");
                ImagePlus semanticMask = DetectionUtils.createSemanticMask(imp1, results);
                if (semanticMask != null) {
                    semanticMask.show();
                } else {
                    IJ.log("   Failed to create Semantic Mask.");
                }
            }

            if (outputInstancePerClass) {
                IJ.log("-> Creating Stack of Instances per Class...");
                ImagePlus instanceMaskPerClass = DetectionUtils.createInstanceMaskPerClass(imp1, results, classIdMap);
                if (instanceMaskPerClass != null) {
                    instanceMaskPerClass.show();
                } else {
                    IJ.log("   Failed to create Instance Mask Per Class.");
                }
            }

        } catch (Exception e) {
            IJ.error("Error During Output Generation", "An error occurred while generating outputs:\n" + e.getMessage());
            e.printStackTrace();
        }
    }

    private void recordInMacro() {
        Recorder.setCommand("Mask conversion");
        Recorder.recordOption("maskType", ""+methodIndex);
        if (imp1 != null) {
            Recorder.recordOption("image1", imp1.getTitle());
        }
        if (imp2 != null){
            Recorder.recordOption("image2", imp2.getTitle());
        }
        Recorder.recordOption("outputRoi", ""+outputRois);
        Recorder.recordOption("outputStackMask", "" + outputStackMask);
        Recorder.recordOption("outputInstanceMask", "" + outputInstanceMask);
        Recorder.recordOption("outputSemanticMask", "" + outputSemanticMask);
        Recorder.recordOption("outputInstancePerClass", "" + outputInstancePerClass);

        if (classFilePath != null){
            Recorder.recordOption("classFile", classFilePath);
        }
        Recorder.saveCommand();
    }

    private void parseMacro() {
        IJ.log("MaskConversion on macro");
        String options = Macro.getOptions();
        IJ.log(options);

        //method
        String methodString = Macro.getValue(options, "maskType", null);
        if (methodString == null ){
            IJ.error("An initial mask type is required. Please choose between : \n\t- 0 : Unitary instance masks stack \n\t- 1 : Instance mask per classes \n\t- 2 : Instance mask + Semantic mask");
            return;
        }
        else {
            methodIndex = Integer.parseInt(methodString);
        }
        // images(s)
        String imp1Name = Macro.getValue(options, "image1", null);
        String imp2Name = Macro.getValue(options, "image2", null);

        imp1 = null;
        imp2 = null;
        if (methodIndex == 1 || methodIndex == 2 || methodIndex == 0) {
            if (imp1Name != null) {
                imp1 = WindowManager.getImage(imp1Name);
            }
            if (methodIndex == 2){
                if (imp2Name != null) {
                    imp2 = WindowManager.getImage(imp2Name);
                }
            }
        } else {
            IJ.error("An initial mask type is required. Please choose between : \n\t- 0 : Unitary instance masks stack \n\t- 1 : Instance mask per classes \n\t- 2 : Instance mask + Semantic mask");
            return;
        }

        outputRois = Boolean.parseBoolean(Macro.getValue(options, "outputRoi", "false"));
        outputStackMask = Boolean.parseBoolean(Macro.getValue(options, "outputStackMask", "false"));
        outputInstanceMask = Boolean.parseBoolean(Macro.getValue(options, "outputInstanceMask", "false"));
        outputSemanticMask = Boolean.parseBoolean(Macro.getValue(options, "outputSemanticMask", "false"));
        outputInstancePerClass = Boolean.parseBoolean(Macro.getValue(options, "outputInstancePerClass", "false"));

        classFilePath = Macro.getValue(options, "classFile", null);
    }

    private void askUser(){
        // --- 1. Prepare Generic Dialog ---
        int[] wList = WindowManager.getIDList();
        if (wList == null || wList.length == 0) {
            IJ.error("Mask Conversion", "No images are open.");
            return;
        }
        // extract titles of opened images
        String[] titles = new String[wList.length + 1];
        for (int i = 0; i < wList.length; i++) {
            ImagePlus imp = WindowManager.getImage(wList[i]);
            titles[i] = (imp != null) ? imp.getTitle() : ("ID=" + wList[i]);
        }
        titles[wList.length] = "none";

        // Create the Generic Dialog instance
        gd = new GenericDialog("Choose mask type and Output");
        gd.addChoice("Initial mask type:", detectionMethods, detectionMethods[0]);
        gd.addChoice("Unitary instance masks stack:", titles, "none");
        gd.addChoice("Instance mask per classes:", titles, "none");
        gd.addChoice("Instance mask:", titles, "none");
        gd.addChoice("Semantic mask:", titles, "none");

        // Output Section
        gd.addMessage("--- Output Options ---");
        gd.addCheckboxGroup(outputOptions.length, 1, outputOptions, outputDefaults);

        // Optional Class File Section
        gd.addMessage("--- Optional Class Names ---");
        String defaultPath = Prefs.get(PREF_CLASS_FILE_PATH, "");
        gd.addFileField("Class Name File:", defaultPath, 40);

        // Get References to AWT Components
        // Dialog Component Vectors (needed to find components)
        Vector<?> choicesVector = gd.getChoices();;
        Vector<?> textFieldVector = gd.getStringFields();

        // Assign specific components
        if (choicesVector != null && choicesVector.size() >= 5) {
            methodChoice = (Choice) choicesVector.get(0);
            instanceStackChoice = (Choice) choicesVector.get(1);
            instancePerClasseChoice = (Choice) choicesVector.get(2);
            instanceMaskChoice = (Choice) choicesVector.get(3);
            semanticMaskChoice = (Choice) choicesVector.get(4);
        } else {
            IJ.error("Internal error creating dialog components.");
            return;
        }

        if (textFieldVector != null && !textFieldVector.isEmpty()) {
            // The class file path field is the last TextField
            classFilePathField = (TextField) textFieldVector.lastElement();
        } else { IJ.error("Internal error: Could not find TextField components."); return; }

        // Set initial state + Add the listener
        updateImageSelectorsVisibility(methodChoice.getSelectedIndex());
        gd.addDialogListener(this);
        gd.addMessage("First, select the type of mask to convert, then the required images.\n" +
                "Then choose the desired output type(s).\n" +
                "If you have a file containing the list of classes used for detection, add it.");

        // Show Dialog
        gd.showDialog();

        // --- 2. Retrieve gd option  ---
        if (gd.wasCanceled()) {
            return;
        }

        // Retrieve method selected
        methodIndex = methodChoice.getSelectedIndex();

        //Retrieve images selected
        int instanceStackIndex = instanceStackChoice.getSelectedIndex();
        int instancePerClassesIndex = instancePerClasseChoice.getSelectedIndex();
        int instanceMaskIndex = instanceMaskChoice.getSelectedIndex();
        int semanticMaskIndex = semanticMaskChoice.getSelectedIndex();

        // Retrieve Output Choices
        outputRois = gd.getNextBoolean();
        outputStackMask = gd.getNextBoolean();
        outputInstanceMask = gd.getNextBoolean();
        outputSemanticMask = gd.getNextBoolean();
        outputInstancePerClass = gd.getNextBoolean();

        // Get the actual ImagePlus objects
        imp1 = null;
        imp2 = null;
        switch (methodIndex) {
            case METHOD_STACK_MASK:
                if (instanceStackIndex >= 0 && instanceStackIndex < wList.length) {
                    imp1 = WindowManager.getImage(wList[instanceStackIndex]);
                }
                break;

            case METHOD_INSTANCE_PER_CLASS:
                if (instancePerClassesIndex >= 0 && instancePerClassesIndex < wList.length) {
                    imp1 = WindowManager.getImage(wList[instancePerClassesIndex]);
                }
                break;

            case METHOD_INSTANCE_SEMANTIC:
                if ((instanceMaskIndex >= 0 && instanceMaskIndex < wList.length) &
                        (semanticMaskIndex >= 0 && semanticMaskIndex < wList.length)) {
                    imp1 = WindowManager.getImage(wList[instanceMaskIndex]);
                    imp2 = WindowManager.getImage(wList[semanticMaskIndex]);
                }
                break;

            default:
                IJ.error("Internal Error: Unknown method index selected.");
                return;
        }
        // Retrieve Class File Choice
        classFilePath = gd.getNextString();
    }



        @Override
    public boolean dialogItemChanged (GenericDialog gd, AWTEvent e){
        if (methodChoice == null || instanceStackChoice == null || instanceMaskChoice == null || semanticMaskChoice == null
                || instancePerClasseChoice == null || classFilePathField == null ||  e == null) {
            return true;
        }
        // Check the event source
        Object source = e.getSource();
        if (source == methodChoice) {
            int selectedMethodIndex = methodChoice.getSelectedIndex();
            updateImageSelectorsVisibility(selectedMethodIndex);
            return true;
        }
        return true;
    }

    /**
     * Helper method to enable/disable the image selectors based
     * on the chosen detection method.
     *
     * @param selectedMethodIndex The index of the currently selected method.
     */
    private void updateImageSelectorsVisibility ( int selectedMethodIndex){
        if (instanceStackChoice == null || instanceMaskChoice == null || semanticMaskChoice == null || instancePerClasseChoice == null) return;

        instanceStackChoice.setEnabled(selectedMethodIndex == METHOD_STACK_MASK);
        instancePerClasseChoice.setEnabled(selectedMethodIndex == METHOD_INSTANCE_PER_CLASS);
        instanceMaskChoice.setEnabled(selectedMethodIndex == METHOD_INSTANCE_SEMANTIC);
        semanticMaskChoice.setEnabled(selectedMethodIndex == METHOD_INSTANCE_SEMANTIC);
    }




}