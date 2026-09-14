package fr.curie.miclearning.apposeplugin.sampcs;

import ij.IJ;
import ij.Prefs;
import ij.gui.GenericDialog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Sam3Dialogs {

    static String resolveDefaultModelPath(String lastModelPrefKey) {
        String lastPath = Prefs.get(lastModelPrefKey, null);
        if (lastPath != null && Files.exists(Paths.get(lastPath))) {
            return lastPath;
        }
        return getDefaultSam3ModelPath();
    }

    /**
     * Validates a model path typed by the user: if it points to an existing file, persists it
     * under {@code lastModelPrefKey} and returns it; otherwise shows an error and returns
     * {@code null}.
     */
    static String validateAndSaveModelPath(String modelPathString, String lastModelPrefKey) {
        Path modelPath = Paths.get(modelPathString);
        if (Files.exists(modelPath)) {
            Prefs.set(lastModelPrefKey, modelPathString);
            Prefs.savePreferences();
            return modelPathString;
        } else {
            IJ.error("Selection Error", "The selected path is not valid:\n" + modelPathString);
            return null;
        }
    }

    private static String getDefaultSam3ModelPath() {
        String imagejRoot = IJ.getDirectory("imagej");

        if (imagejRoot != null) {
            Path modelPath = Paths.get(imagejRoot, "models", "sam3.pt");
            // Check if the file 'sam3.pt' exist in the 'models' directory
            if (Files.exists(modelPath)) {
                return modelPath.toString();
            } else {
                // check in the MiclearningModels folder
                modelPath = Paths.get(imagejRoot, "models", "MicLearningModels", "sam3.pt");
                if (Files.exists(modelPath)) {
                    return modelPath.toString();
                } else {
                    return IJ.getDirectory("home"); // Fallback to user's home directory
                }
            }
        } else {
            //IJ.log("Warning: Could not determine ImageJ installation directory. Defaulting to user home.");
            return IJ.getDirectory("home"); // Fallback to user's home directory
        }
    }

    public static void addDownloadInstruction() {
        GenericDialog gd = new GenericDialog("instructions to download SAM3 model");
        gd.addMessage("The SAM checkpoints are available on the SAM3 HuggingFace repository (huggingface.co/facebook/sam3).\n" +
                "To download them, you need to:\n" +
                "   1/ Create a Hugging Face account\n" +
                "   2/ Request access\n" +
                "     The authorization process usually takes no more than one hour.\n" +
                "   3/ Once your access request is approved, you can download the \"sam3.pt\" file.\n" +
                "   4/ Create a \"sam3\" folder inside the \"models\" subfolder of ImageJ, and copy the model file inside it.");
        gd.hideCancelButton();
        gd.showDialog();
    }

}