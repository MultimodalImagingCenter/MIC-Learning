package fr.curie.gui;

// ConfigManager.java

import ij.IJ;
import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

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
            return Collections.emptyList();
        }
        return Arrays.asList(idsString.split(","));
    }


    public List<String> getExampleIds(String taskId, String modelId) {
        String key = "run." + taskId + "." + modelId + ".examples";

        //check that the "runnable" key is true
        if (!isRunnable(taskId, modelId) ) {
            return Collections.emptyList();
        }

        return getIdsList(key);
    }

    public List<DisplayItem> getExampleDisplayItems(List<String> exampleIds) {

        return exampleIds.stream()
                .map(id -> new DisplayItem(id, getExampleName(id)))
                .collect(Collectors.toList());
    }

    // Get localized UI text
    public String getString(String key) {
        return uiStrings.getString(key);
    }

    // try to get text, return alternative if not found
    public String getString(String key, String alternativeText) {
        String result;
        try {
            result = uiStrings.getString(key);
        } catch (Exception e) {
            result= alternativeText;
            System.err.println("Missing property in UIstrings for key: " + key);
        }
        return result;
    }

    public String getTaskName(String taskId) { return getString("task." + taskId + ".name");}

    public String getModelName(String modelId) { return getString("model." + modelId + ".name"); }

    public String getExampleName(String exampleId) {
        if (exampleId != null && !exampleId.trim().isEmpty()) {
            return getString("example." + exampleId + ".name", "no name (" + exampleId + ")");
        } else {
            return null;
        }
    }
    public String getDescriptionTitle(String exampleId) { return getString("example." + exampleId + ".description.title"); }



    // Construct Content Paths
    private String getContentBasePath() {
        return GLOBAL_CONTENT_FOLDER + "/" + languageCode + "/";
    }

    public String getDescriptionPath(String propertyKey, String itemId){
        // eg. content/en/model/cnn/cnn.md
        return getContentBasePath() + propertyKey + "/" + itemId + "/" + itemId + ".md";
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

    // Check if a couple task/model is defined as runnable
    public boolean isRunnable(String taskId, String modelId) {
        String key = "run." + taskId + "." + modelId + ".runnable";
        return Boolean.parseBoolean(structureConfig.getProperty(key, "false"));
    }

    // verify a couple task/model is actually runnable
    // First check if the associated key is defined as true (verification done when fetching ids list)
    // Then verify if the list of example id isn't empty
    public boolean checkIfRunnable(String taskId, String modelId){
        List<String> exampleIds = getExampleIds(taskId, modelId);
        return exampleIds != null && !exampleIds.isEmpty() && exampleIds.stream().allMatch(StringUtils::isNotEmpty);
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
            String relativeModelDirPath = useCaseProps.getProperty("model.folder", ".");
            String modelPath = "";
            if (relativeModelDirPath != null && !relativeModelDirPath.isEmpty()) {
                File modelDir = new File(useCaseDir, relativeModelDirPath);
                modelPath = modelDir.getCanonicalPath();
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