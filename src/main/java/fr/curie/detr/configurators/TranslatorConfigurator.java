package fr.curie.detr.configurators;

import ai.djl.translate.TranslatorFactory;
import fr.curie.detr.ModelConfig;
import ij.gui.GenericDialog;

public interface TranslatorConfigurator {

    /**
     * Gets the TranslatorFactory class associated with this configurator.
     * @return The Class of the TranslatorFactory.
     */
    Class<? extends TranslatorFactory> getFactoryClass();

    /**
     * @return The full name of the TranslatorFactory.
     */
    String getFactoryClassName();

    /**
     * Adds the translator-specific argument fields to a GenericDialog.
     * It should use the provided 'currentConfig' to set default values.
     *
     * @param gd The GenericDialog to add fields to.
     * @param currentConfig The current model configuration, used to pre-fill dialog values.
     */
    void addDialogFields(GenericDialog gd, ModelConfig currentConfig);

    /**
     * Reads the results from the dialog for the fields it added
     * and updates the ModelConfig object.
     *
     * @param gd The GenericDialog from which to retrieve values.
     * @param configToUpdate The ModelConfig object to populate with the results.
     */
    void retrieveDialogResults(GenericDialog gd, ModelConfig configToUpdate);
}