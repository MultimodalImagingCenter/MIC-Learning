package fr.curie.miclearning.prediction.model;


import ai.djl.Device;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import fr.curie.miclearning.prediction.translator.configurator.TranslatorConfigurator;
import ij.IJ;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class DjlModelLoader<I, O> {

    private final Class<I> inputClass;
    private final Class<O> outputClass;
    private final Map<String, TranslatorConfigurator> knownConfigurators;
    private final String[] engineChoices;
    private final ModelDialogs modelDialogs;

    private static final String DEFAULT_PROPERTIES_FILENAME = "serving.properties";

    public DjlModelLoader(
            Class<I> inputClass,
            Class<O> outputClass,
            Map<String, TranslatorConfigurator> knownConfigurators,
            String[] engineChoices) {
        this.inputClass = inputClass;
        this.outputClass = outputClass;
        this.knownConfigurators = knownConfigurators;
        this.engineChoices = engineChoices;
        this.modelDialogs = new ModelDialogs();
    }

    /**
     * The main entry point for loading a model based on user's initial choices.
     * This method orchestrates the entire loading process.
     *
     * @param modelPath     The path to the model directory.
     * @param initialChoice The user's choices from the initial dialog.
     * @return A LoadedModel object representing the outcome.
     */
    public LoadedModel<I, O> loadModel(Path modelPath, ModelDialogs.InitialChoice initialChoice) {
        // 1. try to load a base configuration from the properties file to pre-fill dialogs.
        ModelConfig baseConfig = new ModelConfig();
        Path propertiesPath = modelPath.resolve(initialChoice.propertiesFileName);
        if (Files.isRegularFile(propertiesPath)) {
            baseConfig.setModelPath(modelPath);
            try {
                IJ.log("Found configuration file: " + propertiesPath.getFileName());
                baseConfig = ModelConfigManager.loadConfigFromFile(propertiesPath);
            } catch (IOException e) {
                IJ.log("Warning: Could not read properties file: " + e.getMessage());
                // Proceed with an empty config
            }
        } else {
            IJ.log("Properties file named " + propertiesPath.getFileName() + " not found. Trying with default configuration.");
        }

        // 2. If user did not request manual configuration, attempt to load the model directly.
        if (!initialChoice.forceManualConfiguration && baseConfig != null) {
            IJ.log("Attempting to load model directly....");
            try {
                ZooModel<I, O> model = tryLoadWithConfig(modelPath, baseConfig);
                IJ.log(" --- Model successfully loaded: " + baseConfig.getModelName() + " ---");
                return new LoadedModel<>(model, baseConfig, false); // Success, no rewrite needed
            } catch (Exception e) {
                IJ.log("Could not load model: " + e.getMessage());
                IJ.log("Falling back to manual configuration dialog...");
            }
        } else {
            IJ.log("User requested manual configuration.");
        }

        // 3. Fallback or Forced Manual: Prompt user for full configuration, using baseConfig for defaults.
        try {
            if (baseConfig == null) baseConfig = new ModelConfig();
            Optional<ModelDialogs.UserConfigurationResult> userConfigResultOpt =
                    modelDialogs.promptForFullConfiguration(baseConfig, modelPath, engineChoices, knownConfigurators);

            if (!userConfigResultOpt.isPresent()) {
                return new LoadedModel<>(false, true); // User cancelled the detailed dialog
            }

            ModelDialogs.UserConfigurationResult userResult = userConfigResultOpt.get();
            ModelConfig userConfig = userResult.config;

            IJ.log("Attempting to load model with new user-provided configuration...");
            ZooModel<I, O> model = tryLoadWithConfig(modelPath, userConfig);

            return new LoadedModel<>(model, userConfig, true, userResult.newPropertiesFileName); // Success, config was rewritten

        } catch (Exception e) {
            IJ.handleException(e);
            IJ.error("Model Loading Failed", "Could not load model from path: " + modelPath + "\nError: " + e.getMessage());
            return new LoadedModel<>(false, false); // Hard failure
        }
    }

    /**
     * Tries to load a ZooModel using a given configuration.
     *
     * @param modelPath The path to the model directory.
     * @param config The configuration to use.
     * @return The loaded ZooModel.
     * @throws ai.djl.MalformedModelException if the model cannot be loaded.
     * @throws IOException for other loading errors.
     */
    private ZooModel<I, O> tryLoadWithConfig(Path modelPath, ModelConfig config) throws Exception {
            if (Objects.equals(config.engine, "TensorFlow")){
                IJ.log("Warning: TensorFlow requires Java 11+.");
            }
            Criteria<I, O> criteria = buildCriteriaFromConfig(modelPath, config);

            if (criteria == null){
                IJ.error("criteria could not be built");
                return null;
            }

            // Load the model.
            return criteria.loadModel();

    }

    /**
     * Builds the DJL Criteria object from a ModelConfig.
     * @param modelPath The path to the model directory.
     * @param config    The configuration object containing loading details.
     * @return The configured Criteria object, or null if configuration is invalid.
     */
    private Criteria<I, O> buildCriteriaFromConfig(Path modelPath, ModelConfig config) {
        //  Validation
        if (config.engine == null || config.engine.trim().isEmpty()) {
            IJ.log(" --- Error: Cannot build criteria - Engine is not specified in config.");
            return null;
        }
        if (config.modelName == null || config.modelName.trim().isEmpty()) {
            config.modelName = modelPath.getFileName().toString();
            IJ.log("Warning: Model name was missing in config, using directory name: " + config.modelName);
        }

        Criteria.Builder<I, O> builder = Criteria.builder()
                .setTypes(inputClass, outputClass)
                .optEngine(config.engine)
                .optModelPath(modelPath)
                .optModelName(config.modelName);

        // Add Translator Factory if specified
        if (!config.autoDetectTranslator) {
            if (config.translatorFactory != null) {
                IJ.log("Building criteria with TranslatorFactory: " + config.translatorFactory.getClass().getName());
                builder.optTranslatorFactory(config.translatorFactory);
            } else {
                IJ.log("Warning: Building criteria - No explicit Translator/Factory provided, and auto-detection was not explicitly requested. Relying on DJL defaults.");
            }
        } else {
            IJ.log("Building criteria - Relying on DJL for translator auto-detection.");
        }

        // Add all arguments collected (from properties or user dialog)
        if (config.arguments != null) {
            for (Map.Entry<String, String> entry : config.arguments.entrySet()) {
                if (entry.getValue() != null) {
                    builder.optArgument(entry.getKey(), entry.getValue());
                }
            }
        }

        // Add options collected
        if (config.options != null) {
            for (Map.Entry<String, String> entry : config.options.entrySet()) {
                if (entry.getValue() != null) {
                    builder.optOption(entry.getKey(), entry.getValue());
                }
            }
        }

        // add device
        Device device = Device.cpu(); // Default
        if (config.options != null) {
            String deviceOption = config.options.get("device");
            if (deviceOption != null) {
                switch (deviceOption.trim().toLowerCase()) {
                    case "gpu":
                        device = Device.gpu();
                        break;
                    case "cpu":
                        device = Device.cpu();
                        break;
                    default:
                        IJ.log("Warning: Unknown device option '" + deviceOption + "', defaulting to CPU.");
                }
            }
        }
        builder.optDevice(device);

        try {
            return builder.build();
        } catch (Exception e) {
            IJ.log(" --- Error building DJL Criteria: " + e.getMessage());
            IJ.handleException(e);
            return null;
        }
    }

    //verify if classes names file exists
    public static void checkSynsetFile(ModelConfig config, String synsetName) {
        // handle synset.txt (relative path expected in properties)
        Path modelPath = config.modelPath;
        if (modelPath == null){
            IJ.log("modelPath is null");
            return;
        }
        if (synsetName != null && !synsetName.trim().isEmpty()) {
            Path potentialSynsetPath = modelPath.resolve(synsetName);
            // check if file exists
            if (Files.exists(potentialSynsetPath) && Files.isRegularFile(potentialSynsetPath)) {
                config.arguments.put("synsetFileName", synsetName);
                config.arguments.put("synsetFilePath", String.valueOf(potentialSynsetPath));
                //IJ.log("Found synset file specified in properties: " + potentialSynsetPath);
            }
        }
        // if no file specified or file isn't valid, try to find default 'synset.txt'
        if (config.arguments.get("synsetFileName") == null) {
            Path potentialDefaultSynset = modelPath.resolve("synset.txt");
            if (Files.isRegularFile(potentialDefaultSynset)) {
                config.arguments.put("synsetFileName", "synset.txt");
                config.arguments.put("synsetFilePath", String.valueOf(potentialDefaultSynset));
                IJ.log("Found default synset file 'synset.txt' in model directory.");
            }
        }
    }

    // The LoadedModel inner class remains unchanged.
    public static class LoadedModel<I, O> {
        private final ZooModel<I, O> model;
        private final ModelConfig config;
        private final boolean success;
        private final boolean cancelled;
        private boolean rewritePropertiesFile;
        private String newPropertiesFileName;

        // Constructor for success
        public LoadedModel(ZooModel<I, O> model, ModelConfig config, boolean rewritePropertiesFile) {
            if (model == null)
                throw new IllegalArgumentException("Model cannot be null for a successful loaded result.");
            if (config == null)
                throw new IllegalArgumentException("Config cannot be null for a successful loaded result.");
            this.model = model;
            this.config = config;
            this.success = true;
            this.cancelled = false;
            this.rewritePropertiesFile = rewritePropertiesFile;
            if (rewritePropertiesFile){
                newPropertiesFileName = DEFAULT_PROPERTIES_FILENAME;
            }
        }

        // Constructor for success with specified synset file name-
        public LoadedModel(ZooModel<I, O> model, ModelConfig config, boolean rewritePropertiesFile, String newPropertiesFileName) {
            if (model == null)
                throw new IllegalArgumentException("Model cannot be null for a successful loaded result.");
            if (config == null)
                throw new IllegalArgumentException("Config cannot be null for a successful loaded result.");
            this.model = model;
            this.config = config;
            this.success = true;
            this.cancelled = false;
            this.rewritePropertiesFile = rewritePropertiesFile;
            if (newPropertiesFileName == null || newPropertiesFileName.trim().isEmpty()){
                this.rewritePropertiesFile = false;
            } else {
                this.newPropertiesFileName = newPropertiesFileName;
            }
        }

        // Constructor for failure or cancellation
        public LoadedModel(boolean success, boolean cancelled) {
            this.model = null;
            this.config = null;
            this.success = success;
            this.cancelled = cancelled;
            if (success && cancelled)
                throw new IllegalArgumentException("Result cannot be both success and cancelled.");
        }


        public ZooModel<I, O> getModel() {
            return model;
        }

        public ModelConfig getConfig() {
            return config;
        } // Returns non-static ModelConfig

        public boolean isFail() {
            return !success;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public boolean needToRewriteServing() {
            return rewritePropertiesFile;
        }

        public Path getNewPropertiesFilePath(){
            return model.getModelPath().resolve(newPropertiesFileName);
        }
    }
}