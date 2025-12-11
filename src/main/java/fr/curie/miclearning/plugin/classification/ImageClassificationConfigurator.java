package fr.curie.miclearning.plugin.classification;

import ai.djl.modality.cv.translator.ImageClassificationTranslatorFactory;
import ai.djl.translate.TranslatorFactory;
import fr.curie.miclearning.prediction.translator.configurator.TranslatorConfigurator;
import fr.curie.miclearning.prediction.model.ModelConfig;
import ij.gui.GenericDialog;

import static fr.curie.miclearning.prediction.model.DjlModelLoader.checkSynsetFile;

public class ImageClassificationConfigurator implements TranslatorConfigurator {

    public static final String FACTORY_CLASS_NAME = "ai.djl.modality.cv.translator.ImageClassificationTranslatorFactory";

    @Override
    public Class<? extends TranslatorFactory> getFactoryClass() {
        // Return the actual factory class
        return ImageClassificationTranslatorFactory.class;
    }

    @Override
    public String getFactoryClassName() {
        return FACTORY_CLASS_NAME;
    }


    @Override
    public void addDialogFields(GenericDialog gd, ModelConfig currentConfig) {
        gd.addMessage("--- Image Classification Options ---");

        // Retrieve defaults from config, with hardcoded fallbacks
        boolean defaultSoftmax = Boolean.parseBoolean(currentConfig.getArguments().getOrDefault("applySoftmax", "true"));
        int defaultTopK = Integer.parseInt(currentConfig.getArguments().getOrDefault("topK", "5"));
        float defaultThreshold = Float.parseFloat(currentConfig.getArguments().getOrDefault("threshold", "0.5"));
        String defaultSynset = currentConfig.getArguments().getOrDefault("synsetFileName", "synset.txt");

        gd.addCheckbox("Apply_Softmax", defaultSoftmax);
        gd.addNumericField("Top_K_Results:", defaultTopK, 0);
        gd.addNumericField("Confidence_Threshold:", defaultThreshold, 2, 6, "(0-1)");
        gd.addStringField("Labels_File_Name (in model dir):", defaultSynset, 30);
    }

    @Override
    public void retrieveDialogResults(GenericDialog gd, ModelConfig configToUpdate) {
        configToUpdate.getArguments().put("applySoftmax", String.valueOf(gd.getNextBoolean()));
        configToUpdate.getArguments().put("topK", String.valueOf((int) gd.getNextNumber()));
        configToUpdate.getArguments().put("threshold", String.valueOf((float) gd.getNextNumber()));
        String synsetName = gd.getNextString();
        checkSynsetFile(configToUpdate, synsetName);

        // Associate the factory class name with the config
        configToUpdate.getArguments().put("translatorFactory", FACTORY_CLASS_NAME);
    }
}