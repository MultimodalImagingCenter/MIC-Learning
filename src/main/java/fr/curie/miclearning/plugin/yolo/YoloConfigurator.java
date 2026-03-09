package fr.curie.miclearning.plugin.yolo;

import ai.djl.translate.TranslatorFactory;
import fr.curie.miclearning.prediction.translator.configurator.TranslatorConfigurator;
import fr.curie.miclearning.prediction.model.ModelConfig;
import fr.curie.miclearning.plugin.yolo.translator.ImpYoloV8TranslatorFactory;
import ij.gui.GenericDialog;

import static fr.curie.miclearning.prediction.model.DjlModelLoader.checkSynsetFile;

public class YoloConfigurator implements TranslatorConfigurator {

    public static final String FACTORY_CLASS_NAME = "fr.curie.miclearning.plugin.yolo.translator.ImpYoloV8TranslatorFactory";
    @Override
    public Class<? extends TranslatorFactory> getFactoryClass() {
        // Return the actual factory class
        return ImpYoloV8TranslatorFactory.class;
    }

    @Override
    public String getFactoryClassName() {
        return FACTORY_CLASS_NAME;
    }

    @Override
    public void addDialogFields(GenericDialog gd, ModelConfig currentConfig) {
        gd.addMessage("--- YOLO Object Detection Options ---");

        // Retrieve defaults from config, with hardcoded fallbacks
        float defaultThreshold = Float.parseFloat(currentConfig.getArguments().getOrDefault("threshold", "0.3f"));
        float defaultNmsThreshold = Float.parseFloat(currentConfig.getArguments().getOrDefault("nmsThreshold", "0.3f"));
        String defaultSynset = currentConfig.getArguments().getOrDefault("synsetFileName", "synset.txt");

        gd.addNumericField("Confidence_Threshold:", defaultThreshold, 2, 6, "(0-1)");
        gd.addNumericField("NMS_Threshold:", defaultNmsThreshold, 2, 6, "(0-1)");
        gd.addStringField("Labels_File_Name (in model dir):", defaultSynset, 30);
    }

    @Override
    public void retrieveDialogResults(GenericDialog gd, ModelConfig configToUpdate) {

        configToUpdate.getArguments().put("threshold", String.valueOf((float) gd.getNextNumber()));
        configToUpdate.getArguments().put("nmsThreshold", String.valueOf((float) gd.getNextNumber()));
        String synsetName = gd.getNextString();
        checkSynsetFile(configToUpdate, synsetName);

    }
}