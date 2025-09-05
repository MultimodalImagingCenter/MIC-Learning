package fr.curie.modelloading;

import ai.djl.nn.BlockFactory;
import ai.djl.translate.TranslatorFactory;
import fr.curie.tiling.TilingOptions;

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
    BlockFactory blockFactory;
    public boolean autoDetectTranslator = false;

    Map<String, String> arguments = new HashMap<>();
    Map<String, String> options = new HashMap<>();

    // specific fields
    String synsetFileName;
    Path synsetFilePath;

    TilingOptions tilingOptions = new TilingOptions();

    float NMS_DEFAULT_VALUE = 0.4f;

    public void reset() {
        modelPath = null;
        engine = null;
        modelName = null;
        translatorFactory = null;
        blockFactory = null;
        options.clear();
        arguments.clear();
        synsetFileName = null;
        synsetFilePath = null;
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

    public BlockFactory getBlockFactory() {
        return blockFactory;
    }

    public Map<String, String> getArguments() {
        return arguments;
    }

    public Map<String, String> getOptions() {
        return options;
    }

    public Path getSynsetFilePath() {
        return arguments.get("synsetFileName") != null ? Paths.get(arguments.get("synsetFileName")) : null;
    }

    public String getSynsetFileName() {
        return arguments.get("synsetFileName");
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

    public void setBlockFactory(BlockFactory blockFactory){this.blockFactory = blockFactory; }

    public void setAutoDetectTranslator(boolean autoDetectTranslator){this.autoDetectTranslator = autoDetectTranslator;}

    public void addArgument(String key, String value) {
        this.arguments.put(key, value);
    }

    public void setSynsetFileName(String synsetFileName) {
        this.synsetFileName = synsetFileName;
    }

    public void setSynsetFilePath(Path synsetFilePath) {
        this.synsetFilePath = synsetFilePath;
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
}
