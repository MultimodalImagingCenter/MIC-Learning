package fr.curie.gui;

// ConfigManager.java

import ij.IJ;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

public class StructureManager {

    private static final String STRUCTURE_PROPERTIES_PATH = "/structure.properties";
    private static final String UI_BUNDLE_NAME = "UIstrings";
    private static final String GLOBAL_CONTENT_FOLDER = "content";

    // Singleton Instance
    private static final StructureManager INSTANCE = new StructureManager();

    private Properties structureConfig;
    private ResourceBundle uiStrings;
    private String languageCode;
    private String defaultLanguage = "en";// Default, could be made dynamic later

    // Private constructor to prevent instantiation
    private StructureManager() {
        try {
            // Load the structural configuration (number and ids of models, classes and examples)
            structureConfig = new Properties();
            InputStream structureStream = StructureManager.class.getResourceAsStream(STRUCTURE_PROPERTIES_PATH);
            if (structureStream == null) {
                throw new RuntimeException("ERROR: " + STRUCTURE_PROPERTIES_PATH + " not found in resources.");
            }
            structureConfig.load(structureStream);

            // Load the translated content
            // 1. Load the UI strings
            try {
                this.uiStrings = ResourceBundle.getBundle(UI_BUNDLE_NAME);
            } catch (Exception e) {
                System.err.println("MainApplicationFrame: Error loading resource bundle: " + UI_BUNDLE_NAME);
                IJ.error("Configuration Error", "Could not load text resources. Plugin may not function correctly.");
                this.uiStrings = null;
            }

            // 2. Determine which language was actually loaded (for constructing content paths)
            Locale loadedLocale = this.uiStrings.getLocale();

            // If the locale is ROOT or has no language, it means the fallback (UIstrings.properties) was used.
            if (loadedLocale.getLanguage().isEmpty() || loadedLocale.equals(Locale.ROOT)) {
                this.languageCode = defaultLanguage;
            } else {
                this.languageCode = loadedLocale.getLanguage();
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize StructureManager", e);
        }
    }

    public static StructureManager getInstance() {
        return INSTANCE;
    }

    // Get list of ids (list of models available, tasks for a model...)
    public List<String> getIdsList(String key) {
        String idsString;
        try {
            idsString = structureConfig.getProperty(key + ".ids", "");
        } catch (Exception e) {
            System.err.println("Missing property in structure.properties for key: " + key);
            return null;
        }
        return Arrays.asList(idsString.split(","));
    }

    public List<String> getModelIds() { return getIdsList("model"); }

    public List<String> getTaskIds() { return getIdsList("task"); }

    public List<String> getModelIdsForTask(String taskId) {
        String key = taskId + ".model";
        return getIdsList(key);
    }

    public List<String> getTaskIdsForModel(String modelId) {
        String key = modelId + ".task";
        return getIdsList(key);
    }

    public List<String> getExampleIdsFor(String taskId, String modelId) {
        String key = "run." + taskId + "." + modelId + ".examples";
        if (!isRunnable(taskId, modelId) || !structureConfig.containsKey(key)) {
            return Collections.emptyList();
        }
        return getIdsList(key);
    }

    // Get localized UI text
    public String getString(String key) {
        return uiStrings.getString(key);
    }

    public String getTaskName(String taskId) { return getString("task." + taskId + ".name");}

    public String getModelName(String modelId) { return getString("model." + modelId + ".name"); }

    public String getExampleName(String exampleId) { return getString("example." + exampleId + ".name"); }
    public String getDescriptionTitle(String exampleId) { return getString("example." + exampleId + ".description.title"); }



    // Construct Content Paths
    private String getContentBasePath() {
        return GLOBAL_CONTENT_FOLDER + "/" + languageCode + "/";
    }

    public String getDescriptionPath(String propertyKey, String itemId){
        // eg. content/en/model/cnn/cnn.md
        return getContentBasePath() + propertyKey + "/" + itemId + "/" + itemId + ".md";
    }


    public String getTaskDescriptionPath(String taskId) {
        return getContentBasePath() + "task/" + taskId + "/" + taskId + ".md";
    }

    public String getModelDescriptionPath(String modelId) {
        return getContentBasePath() + "model/" + modelId + "/" + modelId + ".md";
    }

    public String getModelDescriptionForTaskPath(String taskId, String modelId) {
        return getContentBasePath() + "task/" + taskId + "/model/" + modelId + ".md";
    }

    public String getTaskDescriptionForModelPath(String modelId, String taskId) {
        return getContentBasePath() + "model/" + modelId + "/task/" + taskId + ".md";
    }

    public String getExampleDescriptionPath(String exampleId) {
        return getContentBasePath() + "example/" + exampleId + ".md";
    }

    // Check if a couple task/model has a runnable example
    public boolean isRunnable(String taskId, String modelId) {
        String key = "run." + taskId + "." + modelId + ".runnable";
        return Boolean.parseBoolean(structureConfig.getProperty(key, "false"));
    }

    // Load useCase config (instructions on where and how to use an example model)
    public UseCaseConfig loadUseCase(String mainModelPath, String exampleId) {
        File useCaseDir = new File(mainModelPath, exampleId);
        File useCaseFile = new File(useCaseDir, "usecase.properties");

        if (!useCaseFile.exists()) {
            System.err.println("usecase.properties not found at: " + useCaseFile.getPath());
            return null;
        }

        Properties useCaseProps = new Properties();
        try (InputStream is = Files.newInputStream(useCaseFile.toPath())) {
            useCaseProps.load(is);

            String name = getExampleName(exampleId); // From UIStrings
            String descriptionTitle = getDescriptionTitle(exampleId);

            // Resolve the model path  relative to the use case dir
            String relativeModelDirPath = useCaseProps.getProperty("model.directory", ".");
            String modelPath = "";
            if (relativeModelDirPath != null && !relativeModelDirPath.isEmpty()) {
                File modelDir = new File(useCaseDir, relativeModelDirPath);
                modelPath = modelDir.getCanonicalPath().replace("\\", "/");
            }

            // Resolve the example image path  relative to the use case dir
            String relativeExampleImagePath = useCaseProps.getProperty("example.image");
            String exampleImagePath = "";
            if (relativeExampleImagePath != null && !relativeExampleImagePath.isEmpty()) {
                File exampleImageFile = new File(useCaseDir, relativeExampleImagePath);
                exampleImagePath = exampleImageFile.getCanonicalPath();
            }

            return new UseCaseConfig(
                    name,
                    descriptionTitle,
                    modelPath,
                    exampleImagePath,
                    useCaseProps.getProperty("macro.default"),
                    useCaseProps.getProperty("macro.options")
            );
        } catch (IOException e) {
            // Handle error: log it, maybe return null or throw a specific exception
            System.err.println("Could not load use case: " + useCaseFile);
            e.printStackTrace();
            return null;
        }


    }
}