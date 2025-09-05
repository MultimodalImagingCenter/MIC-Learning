package fr.curie.gui;

import java.awt.event.ActionListener;

public class ButtonDescriptionListPanel extends GenericButtonListPanel{
    protected DescriptionPanel descriptionPanel;
    protected String contentFolder;

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
        String selectText = bundle.getString("select.button");
        String notAvailableText = bundle.getString("unavailable.text");
        descriptionPanel = new DescriptionPanel(selectText, notAvailableText);
        getSplitPane().setRightComponent(descriptionPanel);

        // find content folder
        contentFolder = bundle.getString("content.folder");
    }

    public void displayDescriptionContent(String itemId, ActionListener selectAction){
        if (bundle == null) return;
        try {
            // Construct keys from the itemId (e.g., "classification")
            String titleKey = propertyKey + "." + itemId + ".description.title";
            String contentKey = propertyKey + "." + itemId + ".description.content";

            // Fetch strings from properties
            String title = bundle.getString(titleKey);

            String markdownFilePath = contentFolder + bundle.getString(contentKey);
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
