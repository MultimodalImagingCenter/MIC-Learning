package fr.curie.modelloading.configurators;


import ai.djl.translate.TranslatorFactory;
import fr.curie.modelloading.ModelConfig;
import fr.curie.yolo.translators.ImpYolo11SegmentationTranslatorFactory;
import ij.gui.GenericDialog;

import static fr.curie.modelloading.DjlModelLoader.checkSynsetFile;

public class YoloSegmentationConfigurator implements TranslatorConfigurator {

    public static final String FACTORY_CLASS_NAME = "fr.curie.yolo.ImpYolo11SegmentationTranslatorFactory"; // Replace with your actual package

    @Override
    public Class<? extends TranslatorFactory> getFactoryClass() {
        // Return the actual factory class
        return ImpYolo11SegmentationTranslatorFactory.class;
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

        // Associate the factory class name with the config
        configToUpdate.getArguments().put("translatorFactory", FACTORY_CLASS_NAME);
    }
}
