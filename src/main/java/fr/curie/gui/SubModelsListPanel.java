package fr.curie.gui;

import java.awt.event.ActionListener;
import java.util.function.BiConsumer;

public class SubModelsListPanel extends ButtonDescriptionListPanel{

    private final String parentTaskId;

    public SubModelsListPanel(MainApplication_Frame mainFrame, String pageTitle, String parentTaskId) {
        // The property key for the list of models is based on the parent task
        super(mainFrame, pageTitle, parentTaskId + ".model");
        this.parentTaskId = parentTaskId;

        BiConsumer<String, String> buttonHandler = createButtonHandler(mainFrame);
        this.setButtonActionHandler(buttonHandler);
        this.initializePanel();
    }

    // Define the specific action for models buttons
    private BiConsumer<String, String> createButtonHandler(MainApplication_Frame mainFrame) {
        return (subModelId, subModelName) -> {

            // check if the model is implemented
            String runKeyBase = "run." + parentTaskId + "." + subModelId;
            boolean isRunnable = false;
            if (bundle.containsKey(runKeyBase + ".runnable")) {
                isRunnable = Boolean.parseBoolean(bundle.getString(runKeyBase + ".runnable"));
            }

            // define the action depending on weather the model is runnable or not
            ActionListener selectAction;
            if (isRunnable) {
                // If it's runnable, create the action to navigate to the run page
                selectAction = selectEvent -> {
                    mainFrame.navigateToRunPage(parentTaskId, subModelId, subModelName);
                };
            } else {
                // If not runnable, the action is null. The button will be disabled.
                selectAction = null;
            }

            this.displayFinalDescription(subModelId, selectAction);
        };
    }

    private void displayFinalDescription(String modelId, ActionListener selectAction) {
        String titleKey = propertyKey + "." + modelId + ".description.title";
        String contentKey = propertyKey + "." + modelId + ".description.content";

        try {
            String title = bundle.getString(titleKey);
            String markdownFilePath = contentFolder + bundle.getString(contentKey);
            String htmlContent = ContentLoader.loadAndParseMarkdown(markdownFilePath);

            if (selectAction != null) {
                // If an action was provided, it's runnable.
                descriptionPanel.updateContent(title, htmlContent, selectAction);
            } else {
                // If no action, it's not runnable.
                descriptionPanel.updateContentDisabled(title, htmlContent);
            }
        } catch (Exception e) {
            // Fallback if any keys are missing
            descriptionPanel.updateContentDisabled("Information", "<html><body>Configuration for this combination is incomplete.</body></html>");
            System.err.println("Missing description/configuration for key base: " + propertyKey + "." + modelId );
        }
    }
}
