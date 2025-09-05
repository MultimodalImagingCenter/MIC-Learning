package fr.curie.gui;

import javax.swing.*;
import java.awt.*;
import java.util.ResourceBundle;
import java.util.function.BiConsumer;

public class GenericButtonListPanel extends JPanel {

    private JPanel rootPanel;
    private JSplitPane splitPane;
    private JPanel buttonsDisplayPanel;
    private JLabel questionLabel;
    private JScrollPane scrollPane;
    private JPanel buttonsPanel;

    protected MainApplication_Frame mainFrame;

    protected ResourceBundle bundle;
    protected String pageTitle;
    protected String propertyKey; // e.g., "task" or "model"
    protected BiConsumer<String, String> buttonActionHandler;

    /**
     * Constructor for the generic button list panel.
     *
     * @param mainFrame                 Reference to the main application frame.
     * @param pageTitle                 The title to display at the top of this page.
     * @param propertyKey The prefix for button name keys (e.g., "task" for "task.classification.name").
     *
     */
    public GenericButtonListPanel(MainApplication_Frame mainFrame,
                                  String pageTitle,
                                  String propertyKey) {

        this.mainFrame = mainFrame;
        this.pageTitle = pageTitle;
        this.propertyKey = propertyKey;
        try {
            this.bundle = mainFrame.getResourceBundle();
        } catch (Exception e) {
            System.err.println(getClass().getSimpleName() + ": Error loading resource bundle");
            this.bundle = null;
        }
    }

    public void initializePanel() {
        this.setLayout(new BorderLayout());
        this.add(rootPanel, BorderLayout.CENTER);

        // get the resources bundle from main frame

        // give 15% of the space to the left panel
        splitPane.setResizeWeight(0.15);


        // add buttons to the left panel
        populateButtons();

    }

    protected void populateButtons() {
        if (bundle == null) {
            buttonsPanel.add(new JLabel("Error: Content definitions not loaded."));
            return;
        }
        String question;
        try {
            question = bundle.getString(propertyKey+".askChoice.text");
        } catch (Exception e) {
            try {
                question = bundle.getString("askChoice.text");
            } catch (Exception ee) {
                question = "choose an option";
            }
        }
        questionLabel.setText(question);


        GridBagConstraints gbc = new GridBagConstraints();

        // Spacer to push content down
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        JPanel topSpacer = new JPanel();
        topSpacer.setOpaque(false); // Make it invisible
        buttonsPanel.add(topSpacer, gbc);

        // Default constraints for all buttons
        gbc.gridx = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(1, 5, 1, 5); // padding

        String idsString;
        try {
            idsString = bundle.getString(propertyKey+".list.ids");
        } catch (Exception e) {
            System.err.println("Missing property for button IDs: " + propertyKey);
            buttonsPanel.add(new JLabel("<html>No items defined for this list.</html>"));
            return;
        }

        if (idsString.trim().isEmpty()) {
            buttonsPanel.add(new JLabel("\"<html>No items defined for this list.</html>\""));
            return;
        }

        String[] itemIds = idsString.split(",");
        int currentRow = 2; // starts at 1 because 0 use by spacer

        for (String id : itemIds) {
            String trimmedId = id.trim();
            if (trimmedId.isEmpty()) continue;

            String buttonTextKey = propertyKey + "." + trimmedId + ".name";
            String buttonText;
            try {
                buttonText = bundle.getString(buttonTextKey);
            } catch (Exception e) {
                System.err.println("Missing property for button name: " + buttonTextKey);
                buttonText = "Unnamed (" + trimmedId + ")";
            }

            JButton button = new JButton(buttonText);
            button.setActionCommand(trimmedId); // Set an action command to easily identify the button later

            String finalButtonText = buttonText;
            button.addActionListener(e -> {
                if (buttonActionHandler != null) {
                    buttonActionHandler.accept(e.getActionCommand(), finalButtonText);
                }
            });

            gbc.gridy = currentRow++;
            buttonsPanel.add(button, gbc);
        }

        // Spacer to push content up (keeps centered when expanding the window)
        gbc.gridx = 0;
        gbc.gridy = currentRow; // After the last button
        gbc.weighty = 1.0; // Takes up space below the buttons
        gbc.fill = GridBagConstraints.VERTICAL; // Fills that space vertically
        JPanel bottomSpacer = new JPanel();
        bottomSpacer.setOpaque(false); // Make it invisible
        buttonsPanel.add(bottomSpacer, gbc);

        buttonsPanel.revalidate();
        buttonsPanel.repaint();
    }

    public void setButtonActionHandler(BiConsumer<String, String> handler) {
        this.buttonActionHandler = handler;
    }

    public JSplitPane  getSplitPane(){
        return splitPane;
    }


}
