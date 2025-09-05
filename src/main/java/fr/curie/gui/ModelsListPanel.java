package fr.curie.gui;

import java.awt.event.ActionListener;
import java.util.function.BiConsumer;

public class ModelsListPanel extends ButtonDescriptionListPanel {

    public ModelsListPanel(MainApplication_Frame mainFrame) {
        super(mainFrame,                  // Main frame reference
                "Model selection",            // Page Title
                "model"                  // Property key prefix for button name
        );


        BiConsumer<String, String> buttonHandler = createButtonHandler(mainFrame);
        this.setButtonActionHandler(buttonHandler);
        this.initializePanel();

    }

    // Define the specific action for model buttons
    private BiConsumer<String, String> createButtonHandler(MainApplication_Frame mainFrame) {
        return (modelId, modelName) -> {
            // Define the action for the "Select" button
            ActionListener selectAction = selectEvent -> {
                String title = modelName + " task selection";
                mainFrame.navigateToSubTasksList(title, modelId);
            };

            this.displayDescriptionContent(modelId, selectAction);
        };
    }

}