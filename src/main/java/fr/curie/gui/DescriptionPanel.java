package fr.curie.gui;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import java.awt.*;
import java.awt.event.ActionListener;

public class DescriptionPanel extends JPanel {
    private JEditorPane descriptionArea;
    private JButton selectButton;
    private JLabel titleLabel;
    private JPanel rootPanel;
    private JPanel scrollPanel;
    private JScrollPane scrollPane;

    private final String selectText;
    private final String notAvailableText;

    public DescriptionPanel(String selectText, String notAvailableText) {
        this.selectText = selectText;
        this.notAvailableText = notAvailableText;
        this.setLayout(new BorderLayout(10, 10));
        this.add(rootPanel, BorderLayout.CENTER);

        selectButton.setVisible(true);
        selectButton.setEnabled(false);
        titleLabel.setFont(new Font(Font.SERIF, Font.ITALIC, 16));


        descriptionArea.addHyperlinkListener(new HyperlinkListener() {
            @Override
            public void hyperlinkUpdate(HyperlinkEvent e) {
                if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                    try {
                        Desktop.getDesktop().browse(e.getURL().toURI());
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(descriptionArea);
        scrollPanel.add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * Updates the panel with new content and configures the select button.
     */
    public void updateContent(String title, String htmlContent, ActionListener selectAction) {
        titleLabel.setText(title);
        titleLabel.setFont(new Font(Font.SERIF, Font.BOLD, 18));
        descriptionArea.setText(htmlContent);
        descriptionArea.setCaretPosition(0); // Scroll to the top

        // Remove any previous action listeners to prevent duplicates
        for (ActionListener al : selectButton.getActionListeners()) {
            selectButton.removeActionListener(al);
        }
        selectButton.addActionListener(selectAction);
        selectButton.setText(selectText);
        selectButton.setEnabled(true);
    }

    /**
     * Updates the panel with content but keeps the select button disabled.
     * It also allows for a custom message on the button.
     */
    public void updateContentDisabled(String title, String htmlContent) {
        titleLabel.setText(title);
        titleLabel.setFont(new Font(Font.SERIF, Font.BOLD, 18));
        descriptionArea.setText(htmlContent);
        descriptionArea.setCaretPosition(0);

        // Remove any previous action listeners
        for (ActionListener al : selectButton.getActionListeners()) {
            selectButton.removeActionListener(al);
        }

        // Set the message but keep the button disabled
        selectButton.setText(notAvailableText);
        selectButton.setEnabled(false);
        selectButton.setVisible(true);
    }

    /**
     * Resets the panel to its default "select an item" state.
     */
    public void reset() {
        // TODO : make those texts linked to bundle
        titleLabel.setText("Choose an item to see details : ");
        titleLabel.setFont(new Font(Font.SERIF, Font.ITALIC, 14));
        descriptionArea.setText("");
        selectButton.setEnabled(false);
        selectButton.setText(selectText);
    }
}
