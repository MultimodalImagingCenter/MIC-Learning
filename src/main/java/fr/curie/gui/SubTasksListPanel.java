package fr.curie.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.ActionListener;
import java.util.function.BiConsumer;

public class SubTasksListPanel extends ButtonDescriptionListPanel{
    private static final Logger log = LoggerFactory.getLogger(SubTasksListPanel.class);
    private final String parentModelId;

    public SubTasksListPanel(MainApplication_Frame mainFrame, String pageTitle, String parentModelId) {
        // The property key for the list of models is based on the parent task
        super(mainFrame, pageTitle, parentModelId + ".task");
        this.parentModelId = parentModelId;

        BiConsumer<String, String> buttonHandler = createButtonHandler(mainFrame);
        this.setButtonActionHandler(buttonHandler);
        this.initializePanel();
    }


    // Define the specific action for models buttons
    private BiConsumer<String, String> createButtonHandler(MainApplication_Frame mainFrame) {
        return (subTaskId, subTaskName) -> {

            // check if the model is implemented
            String runKeyBase = "run." + subTaskId + "." + parentModelId;
            boolean isRunnable = false;
            if (bundle.containsKey(runKeyBase + ".runnable")) {
                isRunnable = Boolean.parseBoolean(bundle.getString(runKeyBase + ".runnable"));
            }

            // define the action depending on weather the model is runnable or not
            ActionListener selectAction;
            if (isRunnable) {
                // If it's runnable, create the action to navigate to the run page
                selectAction = selectEvent -> {
                    mainFrame.navigateToRunPage(subTaskId, parentModelId, subTaskName);
                };
            } else {
                // If not runnable, the action is null. The button will be disabled.
                selectAction = null;
            }

            this.displayFinalDescription(subTaskId, selectAction);
        };
    }

    private void displayFinalDescription(String taskId , ActionListener selectAction) {
        String titleKey = propertyKey + "." + taskId + ".description.title";
        String contentKey = propertyKey + "." + taskId + ".description.content";

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
            System.err.println("Missing description/configuration for key base: " + propertyKey+"." + taskId);
            log.error("e: ", e);
        }
    }
}