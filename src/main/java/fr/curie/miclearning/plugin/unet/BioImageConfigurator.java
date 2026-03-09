package fr.curie.miclearning.plugin.unet;
import ai.djl.translate.TranslatorFactory;
import fr.curie.miclearning.prediction.translator.configurator.TranslatorConfigurator;
import fr.curie.miclearning.prediction.model.ModelConfig;
import ij.gui.GenericDialog;

public class BioImageConfigurator implements TranslatorConfigurator {

    public static final String FACTORY_CLASS_NAME = "fr.curie.miclearning.plugin.unet.BioImageTranslatorFactory";

    @Override
    public Class<? extends TranslatorFactory> getFactoryClass() {
        // Return the actual factory class
        return BioImageTranslatorFactory.class;
    }

    @Override
    public String getFactoryClassName() {
        return FACTORY_CLASS_NAME;
    }

    @Override
    public void addDialogFields(GenericDialog gd, ModelConfig currentConfig) {
        gd.addMessage("--- BioImage Zoo Model Options ---");

        String defaultPostMacro = currentConfig.getArguments().getOrDefault("postProcessingMacro", "");

        gd.addStringField("Post-processing_Macro (.ijm):", defaultPostMacro, 40);
    }

    @Override
    public void retrieveDialogResults(GenericDialog gd, ModelConfig configToUpdate) {

        String postMacroName = gd.getNextString();
        if (postMacroName != null && !postMacroName.trim().isEmpty()) {
            if (!postMacroName.endsWith(".ijm")) {
                // enforce the extension for the user
                postMacroName += ".ijm";
            }
            configToUpdate.getArguments().put("postProcessingMacro", postMacroName);
        }

        // Associate the factory class name with the config
        configToUpdate.getArguments().put("translatorFactory", FACTORY_CLASS_NAME);
    }
}
