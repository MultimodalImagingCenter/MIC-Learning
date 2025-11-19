package fr.curie.gui;

import java.awt.event.ActionListener;

public class ButtonDescriptionListPanel extends GenericButtonListPanel{
    protected DescriptionPanel descriptionPanel;

    /**
     * Constructor for the generic button list panel.
     *
     * @param mainFrame   Reference to the main application frame.
     * @param pageTitle   The title to display at the top of this page.
     * @param propertyKey The prefix for button name keys (e.g., "task." for "task.classification.name").
     */
    public ButtonDescriptionListPanel(MainApplication_Frame mainFrame, String pageTitle, String propertyKey) {

        super(mainFrame, pageTitle, propertyKey);

        // add the description panel to the right side
        descriptionPanel = new DescriptionPanel(uiStructure);
        getSplitPane().setRightComponent(descriptionPanel);

    }

    // (only for ModelListPanel and taskListPanel)
    // update the description Panel depending on clicked button
    public void displayDescriptionContent(String itemId, ActionListener selectAction){
        if (uiStructure == null) return;
        try {
            // Fetch title from properties
            String titleKey = propertyKey + "." + itemId + ".description.title";
            String title = uiStructure.getString(titleKey, "could not find title (" + propertyKey +"." + itemId + ")");

            // find markdown file path + fetch markdown content
            String markdownFilePath = uiStructure.getDescriptionPath(propertyKey, itemId);
            String htmlContent = ContentLoader.loadAndParseMarkdown(markdownFilePath);

            // Update the description panel
            descriptionPanel.updateContent(title, htmlContent, selectAction);

        } catch (Exception e) {
            String title = "Info for " + itemId;
            String message = "<html><body><i>No detailed description available.</i></body></html>";
            descriptionPanel.updateContentDisabled(title, message);
        }
    }

    // Method to be called when this panel is shown
    public void onPanelShown() {
        if (descriptionPanel != null) {
            descriptionPanel.reset();
        }
    }
}
