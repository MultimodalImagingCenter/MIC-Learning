package fr.curie.miclearning.plugin.sam;

import ai.djl.translate.TranslatorFactory;
import fr.curie.miclearning.prediction.translator.configurator.TranslatorConfigurator;
import fr.curie.miclearning.prediction.model.ModelConfig;
import ij.gui.GenericDialog;

public class Sam2Configurator implements TranslatorConfigurator {

    public static final String FACTORY_CLASS_NAME = "fr.curie.miclearning.plugin.sam.ImpSam2TranslatorFactory";

    @Override
    public Class<? extends TranslatorFactory> getFactoryClass() {
        // Return the actual factory class
        return ImpSam2TranslatorFactory.class;
    }

    @Override
    public String getFactoryClassName() {
        return FACTORY_CLASS_NAME;
    }

    @Override
    public void addDialogFields(GenericDialog gd, ModelConfig currentConfig) {
        gd.addMessage("--- SAM segmentation Options ---");

        // Retrieve defaults from config, with hardcoded fallbacks
        String defaultMethod = currentConfig.getArguments().getOrDefault("encode_method", "encode");
        String defaultPath = currentConfig.getArguments().getOrDefault("encoder", "");
        //gd.addStringField("encode method:", defaultMethod, 30);
        //gd.addStringField("encoder path:", defaultPath, 30);

        //
    }

    @Override
    public void retrieveDialogResults(GenericDialog gd, ModelConfig configToUpdate) {

        //configToUpdate.getArguments().put("encode_method", gd.getNextString());
        //configToUpdate.getArguments().put("encoder", gd.getNextString());
    }
}