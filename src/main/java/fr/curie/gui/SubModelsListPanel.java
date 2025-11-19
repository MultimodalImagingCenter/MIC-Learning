package fr.curie.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.ActionListener;
import java.util.List;
import java.util.function.BiConsumer;

public class SubModelsListPanel extends ButtonDescriptionListPanel{
    private static final Logger log = LoggerFactory.getLogger(SubTasksListPanel.class);
    private final String parentTaskId;

    public SubModelsListPanel(MainApplication_Frame mainFrame, String pageTitle, String parentTaskId) {
        // The property key for the list of models is based on the parent task
        super(mainFrame, pageTitle, parentTaskId + ".model");
        this.parentTaskId = parentTaskId;

        BiConsumer<String, String> buttonHandler = this::displayFinalDescription;

        this.setButtonActionHandler(buttonHandler);
        this.initializePanel();
    }


    private void displayFinalDescription(String modelId, String modelName) {
        try {
            //fetch title
            String titleKey = propertyKey + "." + modelId + ".description.title";
            String title = uiStructure.getString(titleKey);

            // find markdown file + retrieve markdown content
            String markdownFilePath = uiStructure.getModelDescriptionForTaskPath(parentTaskId, modelId);
            String htmlContent = ContentLoader.loadAndParseMarkdown(markdownFilePath);

            // check that is runnable, and examples exist
            boolean isRunnable = uiStructure.checkIfRunnable(parentTaskId, modelId);

            if (isRunnable) {
                //retrieve example models id
                List<String> exampleIds = uiStructure.getExampleIds(parentTaskId, modelId);

                // Convert IDs to DisplayItems for the combo box (display model names)
                List<DisplayItem> displayItems = uiStructure.getExampleDisplayItems(exampleIds);

                // define action for the select button
                // Action reads the selected ID from the description panel's combo box
                ActionListener comboBoxAction = e -> {
                    String selectedExampleId = descriptionPanel.getSelectedExampleId();
                    if (selectedExampleId != null && !selectedExampleId.trim().isEmpty()) {
                        mainFrame.navigateToRunPage(selectedExampleId, modelName);
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
            System.err.println("Missing description/configuration for key base: " + propertyKey + "." + modelId);
            log.error("e: ", e);
        }
    }

}
