package fr.curie.miclearning.prediction.model;

import ai.djl.translate.TranslatorFactory;
import fr.curie.miclearning.tools.tiling.TilingOptions;
import ij.IJ;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;


public class ModelConfig {

    // universal fields
    Path modelPath;
    String engine;
    String modelName;
    TranslatorFactory translatorFactory;
    public boolean autoDetectTranslator = false;

    Map<String, String> arguments = new HashMap<>();
    Map<String, String> options = new HashMap<>();

    // Tiling
    TilingOptions tilingOptions = new TilingOptions();

    float NMS_DEFAULT_VALUE = 0.4f;

    public void reset() {
        modelPath = null;
        engine = null;
        modelName = null;
        translatorFactory = null;
        options.clear();
        arguments.clear();
        tilingOptions.reset();
    }

    // --- Getters ---
    public Path getModelPath() {
        return modelPath;
    }

    public String getEngine() {
        return engine;
    }

    public String getModelName() {
        return modelName;
    }

    public TranslatorFactory getTranslatorFactory() {
        return translatorFactory;
    }

    public Map<String, String> getArguments() {
        return arguments;
    }

    public Map<String, String> getOptions() {
        return options;
    }

    public Path getSynsetFilePath() {
        if (arguments.get("synsetFilePath") != null){
            return Paths.get(arguments.get("synsetFilePath"));
        }
        return arguments.get("synsetFileName") != null ? Paths.get(arguments.get("synsetFileName")) : null;
    }

    public String getSynsetFileName() {
        return arguments.get("synsetFileName");
    }

    public String getDevice(){
        return options.get("device") != null ? options.get("device") : "cpu";
    }

    public int getDefaultWidth(){
        return arguments.get("width") != null ? Integer.parseInt(arguments.get("width")) : -1;
    }
    public int getDefaultHeight(){
        return arguments.get("height") != null ? Integer.parseInt(arguments.get("height")) : -1;
    }

    public float getNmsThreshold(){
        return arguments.get("nmsThreshold") != null ? Float.parseFloat(arguments.get("nmsThreshold")) : NMS_DEFAULT_VALUE;
    }

    public TilingOptions getTilingOptions() {return tilingOptions;}
    public boolean useTiling() {return tilingOptions.useTiling;}
    public int getTileWidth() {return tilingOptions.tileWidth;}
    public int getTileHeight() {return tilingOptions.tileHeight;}
    public double getTileOverlap() {return tilingOptions.overlap;}


    // --- Setters  ---
    public void setModelPath(Path modelPath) {
        this.modelPath = modelPath;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public void setTranslatorFactory(TranslatorFactory translatorFactory) {
        this.translatorFactory = translatorFactory;
    }

    public void setDevice(String device){
        options.put("device", device != null ? device : "cpu");
    }

    public void setAutoDetectTranslator(boolean autoDetectTranslator){this.autoDetectTranslator = autoDetectTranslator;}

    public void addArgument(String key, String value) {
        this.arguments.put(key, value);
    }


    public void setTilingOptions(boolean useTiling, int tileWidth, int tileHeight, double overlap){
        this.tilingOptions.useTiling = useTiling;
        if (useTiling){
            this.tilingOptions.tileWidth = tileWidth;
            this.tilingOptions.tileHeight = tileHeight;
            this.tilingOptions.overlap = overlap;
        }
    }
    public void setTilingOptions(TilingOptions tilingOptions){
        this.tilingOptions = tilingOptions;
    }

    public void printConfig() {
        IJ.log("\n===========================================");
        IJ.log(" --- Model Configurations ---");
        IJ.log("------------------------------------------------------------------------------");
        IJ.log("Model Path: " + modelPath);
        IJ.log("Engine: " + engine);
        IJ.log("Model Name: " + modelName);
        IJ.log("Translator Factory: " + (translatorFactory!= null ? translatorFactory.getClass().getName() : "null"));
        IJ.log("Synset File Name: " + getSynsetFileName());
        IJ.log("Synset File Path: " + getSynsetFilePath());

        IJ.log(" --- Arguments ---");
        for (Map.Entry<String, String> entry : arguments.entrySet()) {
            IJ.log(entry.getKey() + ": " + entry.getValue());
        }

        IJ.log(" --- Options ---");
        for (Map.Entry<String, String> entry : options.entrySet()) {
            IJ.log(entry.getKey() + ": " + entry.getValue());
        }

//        System.out.println("\n--- Tiling Options ---");
//        System.out.println("Use Tiling: " + tilingOptions.useTiling);
//        System.out.println("Tile Width: " + tilingOptions.tileWidth);
//        System.out.println("Tile Height: " + tilingOptions.tileHeight);
//        System.out.println("Tile Overlap: " + tilingOptions.overlap);

//        System.out.println("\nDefault Width: " + getDefaultWidth());
//        System.out.println("Default Height: " + getDefaultHeight());
//        System.out.println("NMS Threshold: " + getNmsThreshold());
//        System.out.println("Device: " + getDevice());
        IJ.log("===========================================\n");
    }
}
