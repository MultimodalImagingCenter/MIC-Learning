package fr.curie.miclearning.apposeplugin;

import ij.IJ;
import ij.Prefs;
import ij.gui.GenericDialog;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Shared helpers for a plugin dialog's "model path" section:
 * - resolving/validating a checkpoint
 * - showing download instructions.
 */
public class ModelPathDialogs {

    /**
     * Resolve model path given by default : the last saved path, if null, search for a default path name,
     * if null, fall back to ImageJ model directory, if null, ImageJ root directory, then user root directory
     * @param lastModelPrefKey  ij.Prefs key for the last-used path
     * @param candidateFileNames default checkpoint file name(s) to look for under ImageJ's
     *                           "models" (then "models/MicLearningModels") folder (e.g. "sam3.pt")
     */
    public static String resolveDefaultModelPath(String lastModelPrefKey, String... candidateFileNames) {
        String lastPath = Prefs.get(lastModelPrefKey, null);
        if (lastPath != null && Files.exists(Paths.get(lastPath))) {
            return lastPath;
        }
        return getDefaultModelPath(candidateFileNames);
    }

    /**
     * Validates a model path typed by the user: if it points to an existing file, save it
     * under {@code lastModelPrefKey} and returns it; otherwise shows an error and returns
     * {@code null}.
     */
    public static String validateAndSaveModelPath(String modelPathString, String lastModelPrefKey) {
        Path modelPath = Paths.get(modelPathString);
        if (Files.isRegularFile(modelPath)) {
            Prefs.set(lastModelPrefKey, modelPathString);
            Prefs.savePreferences();
            return modelPathString;
        } else if (Files.isDirectory(modelPath)) {
            IJ.error("Selection Error", "The selected path is a folder, not a model checkpoint file (.pt):\n" + modelPathString
                    + "\nPlease select the model file itself.");
            return null;
        } else {
            IJ.error("Selection Error", "The selected path is not valid:\n" + modelPathString);
            return null;
        }
    }

    private static String getDefaultModelPath(String... candidateFileNames) {
        String imagejRoot = IJ.getDirectory("imagej");

        if (imagejRoot != null) {
            Path ijModelsPath = Paths.get(imagejRoot, "models");
            Path ijMLModelsPath = Paths.get(imagejRoot, "models", "MicLearningModels");

            for (String fileName : candidateFileNames) {
                Path modelPath = ijModelsPath.resolve(fileName);
                if (Files.exists(modelPath)) return modelPath.toString();

                modelPath = ijMLModelsPath.resolve(fileName);
                if (Files.exists(modelPath)) return modelPath.toString();
            }
            if (Files.exists(ijModelsPath)) return ijModelsPath.toString();
            return IJ.getDirectory("imagej");
        }

        return IJ.getDirectory("home"); // Fallback to user's home directory
    }

    public static void showDownloadInstructions(Component parent, String title, String message) {
        JOptionPane.showMessageDialog(parent, message, title, JOptionPane.INFORMATION_MESSAGE);
    }

}