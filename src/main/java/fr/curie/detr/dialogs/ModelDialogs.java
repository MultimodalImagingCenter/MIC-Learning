package fr.curie.detr.dialogs;

import fr.curie.detr.ModelConfig;
import fr.curie.detr.configurators.TranslatorConfigurator;
import ij.IJ;
import ij.Prefs;
import ij.gui.GenericDialog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handles all GenericDialog interactions for model loading configuration.
 * Provides methods to add fields to existing dialogs to support flexible UI construction.
 */
public class ModelDialogs {

    private static String PREF_LAST_MODEL_DIR;
    private static final String DEFAULT_PROPERTIES_FILENAME = "serving.properties";
    private static final String AUTO_DETECT_TRANSLATOR_OPTION = "[Auto-detect from 'libs' folder]";

    // A data holder for the result of the initial dialog part.
    public static class InitialChoice {
        public final Path modelPath;
        public final String propertiesFileName;
        public final boolean forceManualConfiguration;

        public InitialChoice(Path modelPath, String propertiesFileName, boolean forceManualConfiguration) {
            this.modelPath = modelPath;
            this.propertiesFileName = propertiesFileName;
            this.forceManualConfiguration = forceManualConfiguration;
        }
    }

    // A data holder for the complete user configuration result.
    public static class UserConfigurationResult {
        public final ModelConfig config;
        public final String newPropertiesFileName;

        public UserConfigurationResult(ModelConfig config, String newPropertiesFileName) {
            this.config = config;
            this.newPropertiesFileName = newPropertiesFileName;
        }
    }

    /**
     * Adds the standard model selection fields to an existing GenericDialog.
     * This allows plugins to create a custom initial dialog.
     *
     * @param gd The GenericDialog to add fields to.
     */
    public static void addInitialDialogFields(GenericDialog gd, String plugin_name) {
        String defaultDir = null;
        PREF_LAST_MODEL_DIR = plugin_name + ".lastmodeldir";
        String lastDir = Prefs.get(PREF_LAST_MODEL_DIR, defaultDir);
        if (lastDir != null && Files.isDirectory(Paths.get(lastDir))) {
            defaultDir = lastDir;
        } else {
            defaultDir = getDefaultModelsDir();
        }

        gd.addDirectoryField("Model_Directory:", defaultDir, 60);
        gd.addStringField("Properties_File_Name:", DEFAULT_PROPERTIES_FILENAME, 30);
        gd.addCheckbox("Show_manual_configuration_dialog", false);
    }

    /**
     * Tries to find the default ImageJ models directory.
     *
     * @return A default directory path string.
     */
    private static String getDefaultModelsDir() {
        String imagejRoot = IJ.getDirectory("imagej");

        if (imagejRoot != null) {
            Path modelsPath = Paths.get(imagejRoot, "models");
            // Check if the 'models' directory exists
            if (Files.isDirectory(modelsPath)) {
                return modelsPath.toString();
            } else {
                // If 'models' doesn't exist, fall back to the ImageJ root itself
                //IJ.log("Note: ImageJ/models/ directory not found. Defaulting to ImageJ root: " + imagejRoot);
                return imagejRoot;
            }
        } else {
            //IJ.log("Warning: Could not determine ImageJ installation directory. Defaulting to user home.");
            return IJ.getDirectory("home"); // Fallback to user's home directory
        }
    }

    /**
     * Retrieves the results for the fields added by addInitialDialogFields.
     *
     * @param gd The GenericDialog from which to retrieve answers.
     * @return An InitialChoice object with the user's selections.
     */
    public static InitialChoice getInitialChoice(GenericDialog gd) {
        String dirPath = gd.getNextString();
        IJ.log("Model Directory : " + dirPath);
        String propsFileName = gd.getNextString();
        IJ.log("Properties File Name : " + propsFileName);
        boolean forceManual = gd.getNextBoolean();
        IJ.log("Show manual configuration dialog : " + forceManual);

        Path modelPath = Paths.get(dirPath);
        if (Files.isDirectory(modelPath)) {
            // Save the selected directory for next time
            Prefs.set(PREF_LAST_MODEL_DIR, dirPath);
            Prefs.savePreferences();
            return new InitialChoice(modelPath, propsFileName, forceManual);
        } else {
            IJ.error("Selection Error", "The selected path is not a valid directory:\n" + dirPath);
            return null;
        }
    }

    /**
     * Prompts the user with a detailed, two-step configuration dialog.
     *
     * @param config         The configuration to use for default values (e.g., from a properties file).
     * @param modelPath          The path to the model directory.
     * @param engineChoices      The available DJL engines.
     * @param knownConfigurators The map of available translator configurators.
     * @return An Optional containing the final configuration, or empty if the user cancels.
     */
    public Optional<UserConfigurationResult> promptForFullConfiguration(
            ModelConfig config,
            Path modelPath,
            String[] engineChoices,
            String[] deviceChoices,
            Map<String, TranslatorConfigurator> knownConfigurators
    ) throws InstantiationException, IllegalAccessException {

        // --- 1. First Configuration Dialog (required arguments) ---
        GenericDialog gdCore = new GenericDialog("Model Configuration - Step 1/2");

        // 1.1 Prepare choices for the first dialog


        // 1.1.1 Retrieve defaults from serving properties if available
        String defaultModelName = config.getModelName() != null ? config.getModelName() : modelPath.getFileName().toString();
        String defaultEngine = config.getEngine() != null ? config.getEngine() : (engineChoices.length > 0 ? engineChoices[0] : "");
        String defaultDevice = config.getDevice() != null ? config.getDevice() : (deviceChoices.length > 0 ? deviceChoices[0] : "cpu");
        int defaultWidth = config.getDefaultWidth() > 0 ? config.getDefaultWidth() : 256;
        int defaultHeight = config.getDefaultHeight() > 0 ? config.getDefaultHeight() : 256;

        // Determine the default translator choice from the existing baseConfig
        String defaultTranslatorChoice = AUTO_DETECT_TRANSLATOR_OPTION;
        String configFactoryClassName = config.getArguments().get("translatorFactory");
        if (configFactoryClassName != null && !configFactoryClassName.trim().isEmpty()) {
            for (Map.Entry<String, TranslatorConfigurator> entry : knownConfigurators.entrySet()) {
                if (entry.getValue().getFactoryClassName().equals(configFactoryClassName)) {
                    defaultTranslatorChoice = entry.getKey();
                    break;
                }
            }
        }

        // 1.1.2 Prepare translator choices
        List<String> translatorOptions = new ArrayList<>();
        translatorOptions.add(AUTO_DETECT_TRANSLATOR_OPTION);
        translatorOptions.addAll(knownConfigurators.keySet());

        // 1.2 Show the first dialog
        gdCore.addMessage("Required arguments and options");
        gdCore.addStringField("Model_Name_Prefix:", defaultModelName, 40);
        gdCore.addChoice("Engine:", engineChoices, defaultEngine);
        gdCore.addChoice("Device:", deviceChoices, defaultDevice);
        gdCore.addChoice("Translator_Type:", translatorOptions.toArray(new String[0]), defaultTranslatorChoice);
        gdCore.addNumericField("Input_Width:", defaultWidth, 0);
        gdCore.addNumericField("Input_Height:", defaultHeight, 0);
        gdCore.addStringField("Save_as_Properties_File_Name:", DEFAULT_PROPERTIES_FILENAME, 40);

        gdCore.showDialog();
        if (gdCore.wasCanceled()) {
            IJ.log("Model loading cancelled by user.");
            return Optional.empty();
        }

        // 1.3 build the new configuration from the dialog answers
        config.setModelPath(modelPath);
        config.setModelName(gdCore.getNextString());
        config.getOptions().put("modelName", config.getModelName());
        config.setEngine(gdCore.getNextChoice());
        config.getArguments().put("engine", config.getEngine());
        config.setDevice(gdCore.getNextChoice());
        String chosenTranslatorName = gdCore.getNextChoice();
        config.addArgument("width", String.valueOf((int) gdCore.getNextNumber()));
        config.addArgument("height", String.valueOf((int) gdCore.getNextNumber()));
        String newPropertiesFileName = gdCore.getNextString();

        // 1.4 verification step :  engine not null + w and h > 0
        if (config.getEngine() == null || config.getEngine().trim().isEmpty()) {
            IJ.error(" --- Configuration Error", "Engine cannot be empty. Please select an engine.");
            return Optional.empty();
        }
        if (config.getArguments().get("width") == null || (Float.parseFloat(config.getArguments().get("width"))) <= 0 ||
                config.getArguments().get("height") == null || (Float.parseFloat(config.getArguments().get("height"))) <= 0) {
            IJ.error(" --- Configuration Error", "Input Width and Height must be positive numbers.");
            return Optional.empty();
        }

        // 1.5 Find the selected translator configurator
        TranslatorConfigurator selectedConfigurator = null;
        if (chosenTranslatorName.equals(AUTO_DETECT_TRANSLATOR_OPTION)) {
            config.setAutoDetectTranslator(true);
            config.setTranslatorFactory(null);
            config.getArguments().remove("translatorFactory");
            IJ.log("User selected automatic translator detection from 'libs' folder.");
        } else {
            config.autoDetectTranslator = false;
            selectedConfigurator = knownConfigurators.get(chosenTranslatorName);
            config.setTranslatorFactory(selectedConfigurator.getFactoryClass().newInstance());
            config.addArgument("translatorFactory", selectedConfigurator.getFactoryClassName());
        }

        // --- 2 Second Configuration Dialog (optional arguments) ---
        GenericDialog gdDetails = new GenericDialog("Model Configuration - Step 2/2");
        gdDetails.addMessage("Optional arguments and options");

        // 2.1 Build the second dialog
        // 2.1.1 Add Pre-processing Arguments
        gdDetails.addMessage(" --- Pre-processing Arguments ---");
        // Flag
        String[] flagChoices = {"(None)", "GRAYSCALE", "COLOR"};
        String defaultFlag = config.getArguments().getOrDefault("flag", flagChoices[0]);
        gdDetails.addChoice("Image_Format_Flag:", flagChoices, defaultFlag);
        // Pad
        String defaultPad = config.getArguments().getOrDefault("pad", "");
        gdDetails.addStringField("Pad (true/false/value):", defaultPad);
        // Resize
        String defaultResize = config.getArguments().getOrDefault("resize", "");
        gdDetails.addStringField("Resize (true/false/w/w,h):", defaultResize);
        // Resize
        String defaultResizeShort = config.getArguments().getOrDefault("resizeShort", "");
        gdDetails.addStringField("Resize_short (true/false/w/w,h):", defaultResizeShort);
        // CenterCrop
        String defaultCenterCrop = config.getArguments().getOrDefault("centerCrop", "");
        gdDetails.addStringField("Center_Crop (true/false)", defaultCenterCrop);
        // CenterFit
        String defaultCenterFit = config.getArguments().getOrDefault("centerFit", "");
        gdDetails.addStringField("Center_Fit_(Pad)", defaultCenterFit);
        // ToTensor
        String defaultToTensor = config.getArguments().getOrDefault("toTensor", "true");
        gdDetails.addStringField("Convert_to_Tensor", defaultToTensor);
        // Normalize
        String defaultNormalize = config.getArguments().getOrDefault("normalize", "");
        gdDetails.addStringField("Normalize (true/false/means,stds):", defaultNormalize);
        // Range
        String[] rangeChoices = {"(None)", "0,1", "-1,1"};
        String defaultRange = config.getArguments().getOrDefault("range", rangeChoices[0]);
        gdDetails.addChoice("Pixel_Value_Range:", rangeChoices, defaultRange);
        // Macro
        String defaultPreMacro = config.getArguments().getOrDefault("preProcessingMacro", "");
        gdDetails.addStringField("Pre-processing_Macro (.ijm):", defaultPreMacro, 40);

        // 2.1.2 Add translator fields using the selected configurator + the default values from initial config
        if (selectedConfigurator != null) {
            selectedConfigurator.addDialogFields(gdDetails, config);
        }

        // 2.2 Show dialog
        gdDetails.showDialog();
        if (gdDetails.wasCanceled()) {
            IJ.log("Model loading cancelled by user.");
            return Optional.empty();
        }

        // 2.3 Retrieve all results from the detailed dialog
        // Pre-processing arguments
        String flag = gdDetails.getNextChoice();
        if (!flag.equals(flagChoices[0])) config.getArguments().put("flag", flag);
        String pad = gdDetails.getNextString();
        if(!pad.trim().isEmpty()) config.getArguments().put("pad", pad);
        String resize = gdDetails.getNextString();
        if(!resize.trim().isEmpty()) config.getArguments().put("resize", resize);
        String resizeShort = gdDetails.getNextString();
        if(!resizeShort.trim().isEmpty()) config.getArguments().put("resizeShort", resizeShort);
        String centerCrop = gdDetails.getNextString();
        if(!centerCrop.trim().isEmpty()) config.getArguments().put("centerCrop", centerCrop);
        String centerFit = gdDetails.getNextString();
        if(!centerFit.trim().isEmpty()) config.getArguments().put("centerFit", centerFit);
        String toTensor = gdDetails.getNextString();
        if(!toTensor.trim().isEmpty()) config.getArguments().put("toTensor", toTensor);
        String normalize = gdDetails.getNextString();
        if(!normalize.trim().isEmpty()) config.getArguments().put("normalize", normalize);
        String range = gdDetails.getNextChoice();
        if (!range.equals(rangeChoices[0])) config.getArguments().put("range", range);
        String preProcessingMacro = gdDetails.getNextString();
        if(!preProcessingMacro.trim().isEmpty()) config.getArguments().put("preProcessingMacro", preProcessingMacro);

        // Retrieve translator-specific results
        if (selectedConfigurator != null) {
            selectedConfigurator.retrieveDialogResults(gdDetails, config);
        }

        return Optional.of(new UserConfigurationResult(config, newPropertiesFileName));
    }
}