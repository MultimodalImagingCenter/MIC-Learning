package fr.curie.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.ActionListener;
import java.util.List;
import java.util.function.BiConsumer;

public class  SubTasksListPanel extends ButtonDescriptionListPanel{
    private static final Logger log = LoggerFactory.getLogger(SubTasksListPanel.class);
    private final String parentModelId;

    public SubTasksListPanel(MainApplication_Frame mainFrame, String pageTitle, String parentModelId) {
        // The property key for the list of models is based on the parent task
        super(mainFrame, pageTitle, parentModelId + ".task");
        this.parentModelId = parentModelId;

        BiConsumer<String, String> buttonHandler = this::displayFinalDescription;

        this.setButtonActionHandler(buttonHandler);
        this.initializePanel();
    }

    private void displayFinalDescription(String taskId, String taskName) {
        try {
            //fetch title
            String titleKey = propertyKey + "." + taskId + ".description.title";
            String title = uiStructure.getString(titleKey);

            // find markdown file + retrieve markdown content
            String markdownFilePath = uiStructure.getTaskDescriptionForModelPath(parentModelId, taskId);
            String htmlContent = ContentLoader.loadAndParseMarkdown(markdownFilePath);

            // check that is runnable, and examples exist
            boolean isRunnable = uiStructure.checkIfRunnable(taskId, parentModelId);

            if (isRunnable) {
                //retrieve example models id
                List<String> exampleIds = uiStructure.getExampleIds(taskId, parentModelId);
                // Convert IDs to DisplayItems for the combo box.
                List<DisplayItem> displayItems = uiStructure.getExampleDisplayItems(exampleIds);

                // define action for the select button
                // Action reads the selected ID from the description panel's combo box.
                ActionListener comboBoxAction = e -> {
                    String selectedExampleId = descriptionPanel.getSelectedExampleId();
                    if (selectedExampleId != null && !selectedExampleId.trim().isEmpty()) {
                        mainFrame.navigateToRunPage(selectedExampleId, taskName);
                    } else {
                        System.err.println("Missing information for the model : " + selectedExampleId);
                    }
                };
                // Use updateContentList to show and populate the combo box.
                descriptionPanel.updateContentList(title, htmlContent, comboBoxAction, displayItems);

            } else {
                // Not runnable or no examples configured.
                descriptionPanel.updateContentDisabled(title, htmlContent);
            }
        } catch (Exception e) {
            descriptionPanel.updateContentDisabled("Information", "<html><body>Configuration for this combination is incomplete.</body></html>");
            System.err.println("Missing description/configuration for key base: " + propertyKey + "." + taskId);
            log.error("e: ", e);
        }
    }
}