package fr.curie.detr.configurators;

import ai.djl.translate.TranslatorFactory;
import fr.curie.detr.DetrTranslatorFactory;
import fr.curie.detr.ModelConfig;
import ij.gui.GenericDialog;

import static fr.curie.detr.DjlModelLoaderNew.checkSynsetFile;

public class DetrConfigurator implements TranslatorConfigurator {

    public static final String FACTORY_CLASS_NAME = "fr.curie.detr.DetrTranslatorFactory";

    @Override
    public Class<? extends TranslatorFactory> getFactoryClass() {
        // Return the actual factory class
        return DetrTranslatorFactory.class;
    }

    @Override
    public String getFactoryClassName() {
        return FACTORY_CLASS_NAME;
    }

    @Override
    public void addDialogFields(GenericDialog gd, ModelConfig currentConfig) {
        gd.addMessage("--- Detr Object Detection Options ---");

        // Retrieve defaults from config, with hardcoded fallbacks
        float defaultThreshold = Float.parseFloat(currentConfig.getArguments().getOrDefault("threshold", "0.2f"));
        float defaultNmsThreshold = Float.parseFloat(currentConfig.getArguments().getOrDefault("nmsThreshold", "0.6f"));
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