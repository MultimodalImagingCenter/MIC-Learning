package fr.curie.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class HomePanel extends JPanel {
    private JButton taskButton;
    private JButton modelButton;
    private JLabel taskDescriptionLabel;
    private JLabel modelDescriptionLable;
    private JLabel askChoiceLabel;
    private JLabel titleLabel;
    private JPanel rootPanel;

    private MainApplication_Frame mainFrame;


    public HomePanel(MainApplication_Frame mainFrame) {
        this.mainFrame = mainFrame;

        this.setLayout(new BorderLayout());
        this.add(rootPanel, BorderLayout.CENTER);

        initComponents();
    }

    private void initComponents() {
        taskButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (mainFrame != null) {
                    mainFrame.navigateToTasksList();
                }
            }
        });

        modelButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (mainFrame != null) {
                    mainFrame.navigateToModelsList();
                }
            }
        });

    }

}
