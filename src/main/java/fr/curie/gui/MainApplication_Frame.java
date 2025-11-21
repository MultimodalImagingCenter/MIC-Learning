package fr.curie.gui;

import ij.IJ;
import ij.plugin.frame.PlugInFrame;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.Stack;

public class MainApplication_Frame extends PlugInFrame {
    private JPanel rootPanel;
    private JPanel headerPanel;
    private JLabel iconLabel;
    private JLabel titleLabel;
    private JPanel contentAreaPanel;
    private JPanel progressPanel;

    private MenuBar mainMenuBar;

    // retrieve structure of the app (models and tasks lists...)
    public StructureManager structure = StructureManager.getInstance();

    private static final String MODELS_SUBFOLDER = "MicLearningModels";

    // Constants for panel names in CardLayout
    public static final String HOME_PANEL_KEY = "HOME_PANEL";
    public static final String TASKS_LIST_PANEL_KEY = "TASKS_LIST_PANEL";
    public static final String MODELS_LIST_PANEL_KEY = "MODELS_LIST_PANEL";
    public static final String SUB_TASKS_LIST_PANEL_KEY = "SUB_TASKS_LIST_PANEL";
    public static final String SUB_MODELS_LIST_PANEL_KEY = "SUB_MODELS_LIST_PANEL";
    public static final String RUN_PANEL_KEY = "RUN_PANEL";

    private HomePanel homePanel;
    private TasksListPanel tasksListPanel;
    private ModelsListPanel modelsListPanel;
    private RunPanel runPanel;

    // stack to keep track of the user's navigation history
    private final Stack<NavigationStep> navigationHistory = new Stack<>();
    // texts for navigation steps
    private String homeNavText;
    private String taskListNavText;
    private String modelListNavText;


    public MainApplication_Frame() {
        super("MIC learning Plug-in");

        if (rootPanel == null) {
            IJ.error("Plugin UI Error", "The main UI panel (rootPanel) could not be initialized from the form");
        }

        // Set the content of this PlugInFrame to be the rootPanel from the form
        this.setLayout(new BorderLayout());
        this.add(rootPanel, BorderLayout.CENTER);


        // forces the Manager to check if the user changed the language
        StructureManager.getInstance().refreshLanguage();
        // get text to display in navigation
        getNavStepsTexts();

        // Create and set the menu bar
        createMainMenuBar();
        this.setMenuBar(mainMenuBar);

        // Set up the content area
        setupContentArea();


        this.pack();
        this.setSize(new Dimension(750, 540));
        this.setLocationRelativeTo(null);
        this.setVisible(true);

    }

    private void createMainMenuBar() {
        mainMenuBar = new MenuBar();

        // --- File menu ---
        Menu fileMenu = new Menu("File");
        MenuItem closeItem = new MenuItem("Close Plugin Window");
        closeItem.addActionListener(e -> this.close());
        fileMenu.add(closeItem);
        mainMenuBar.add(fileMenu);

        // --- Settings Menu ---
        Menu settingsMenu = new Menu("Settings");
        Menu languageMenu = new Menu("Language");

        // Get current preference to check the right box
        String currentPref = StructureManager.getInstance().getLanguagePreference();

        // Create items
        CheckboxMenuItem itemAuto = new CheckboxMenuItem("Auto (System Default)");
        CheckboxMenuItem itemEn = new CheckboxMenuItem("English");
        CheckboxMenuItem itemFr = new CheckboxMenuItem("Français");

        // set state based on stored preference
        itemAuto.setState("auto".equals(currentPref));
        itemEn.setState("en".equals(currentPref));
        itemFr.setState("fr".equals(currentPref));

        // define the action listener
        // (specific listener to handle the "Restart" message)
        java.awt.event.ItemListener languageChangeListener = e -> {
            Object source = e.getSource();
            String newLang = "auto";

            // determine which language was clicked
            if (source == itemEn) newLang = "en";
            else if (source == itemFr) newLang = "fr";

            // update Checkboxes UI
            itemAuto.setState("auto".equals(newLang));
            itemEn.setState("en".equals(newLang));
            itemFr.setState("fr".equals(newLang));

            // get the currently active language preference
            String currentLang = StructureManager.getInstance().getLanguagePreference();

            // 4. Only process the change if it's actually different
            if (!newLang.equals(currentLang)) {
                // Save the new preference
                StructureManager.getInstance().setLanguagePreference(newLang);

                // Show the message
                IJ.showMessage("Language Changed",
                        "Please close and reopen the plugin window to apply the language change.");
            }
            // save to ImageJ Prefs
            StructureManager.getInstance().setLanguagePreference(newLang);

        };

        // Add listeners
        itemAuto.addItemListener(languageChangeListener);
        itemEn.addItemListener(languageChangeListener);
        itemFr.addItemListener(languageChangeListener);

        // Add items to sub-menu
        languageMenu.add(itemAuto);
        languageMenu.add(itemEn);
        languageMenu.add(itemFr);

        settingsMenu.add(languageMenu);
        mainMenuBar.add(settingsMenu);

        // --- help menu ---
        Menu helpMenu = new Menu("Help");
        MenuItem aboutItem = new MenuItem("About");
        aboutItem.addActionListener(e -> IJ.showMessage("About Mic Learning", "Version 1.0\n"));
        helpMenu.add(aboutItem);
        mainMenuBar.add(helpMenu);
    }

    private void setupContentArea() {
        if (contentAreaPanel != null) {
            // Create and add HomePagePanel
            homePanel = new HomePanel(this);
            contentAreaPanel.add(homePanel, HOME_PANEL_KEY);
            navigateToHomePage();
        }
    }

    private void getNavStepsTexts(){
        homeNavText = structure.getString("navigation.home.text");
        taskListNavText = structure.getString("navigation.taskList.text");
        modelListNavText = structure.getString("navigation.modelList.text");
    }


    /**
     * Gets the central content panel where different views will be displayed.
     * @return The content area panel.
     */
    public JPanel getContentAreaPanel() {
        return contentAreaPanel;
    }


    /**
     * Shows a specific panel (card) in the content area.
     * The panel must have already been added to the contentAreaPanel with the given name.
     * @param panelName The name of the panel/card to show.
     */
    public void showView(String panelName) {
        if (contentAreaPanel != null && contentAreaPanel.getLayout() instanceof CardLayout) {
            CardLayout cl = (CardLayout) (contentAreaPanel.getLayout());
            cl.show(contentAreaPanel, panelName);

        } else {
            IJ.log("Error: contentAreaPanel is not set up with CardLayout or is null.");
        }
    }

    private void rebuildProgress() {
        progressPanel.removeAll(); // Clear the old labels
        progressPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 0, 0));

        for (int i = 0; i < navigationHistory.size(); i++) {
            final NavigationStep step = navigationHistory.get(i);
            final int stepIndex = i; // To use inside the lambda

            JLabel stepLabel = new JLabel(step.getDisplayText());
            stepLabel.setCursor(new Cursor(Cursor.HAND_CURSOR)); // Show hand cursor on hover
            //stepLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
            //stepLabel.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
            //stepLabel.setForeground(Color.BLUE.darker());

            // Add a mouse listener to handle clicks
            stepLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    // Pop history off the stack until we get to the clicked step
                    while (navigationHistory.size() > stepIndex + 1) {
                        navigationHistory.pop();
                    }
                    // Execute the action associated with this step to restore its state
                    step.getAction().run();
                }
            });

            progressPanel.add(stepLabel);

            // Add a ">" separator, but not after the last item
            if (i < navigationHistory.size() - 1) {
                JLabel separator = new JLabel(" > ");
                separator.setFont(new Font("SansSerif", Font.BOLD, 12));
                progressPanel.add(separator);
            }
        }

        progressPanel.revalidate();
        progressPanel.repaint();
    }


    public StructureManager getUIStructure() {
        return structure;
    }

    public void navigateToHomePage() {
        navigationHistory.clear(); // Going home resets the history
        navigationHistory.push(new NavigationStep(homeNavText, HOME_PANEL_KEY, this::navigateToHomePage));
        rebuildProgress();
        showView(HOME_PANEL_KEY);
    }

    public void navigateToTasksList() {
        // action for progress bar
        Runnable action = this::navigateToTasksList;
        // Don't add if we are already there
        while (!navigationHistory.isEmpty() && navigationHistory.peek().getCardLayoutKey().equals(TASKS_LIST_PANEL_KEY)) {
            navigationHistory.pop();
        }
        navigationHistory.push(new NavigationStep(taskListNavText, TASKS_LIST_PANEL_KEY, action));


        if (tasksListPanel == null) {
            tasksListPanel = new TasksListPanel(this); //
            contentAreaPanel.add(tasksListPanel, TASKS_LIST_PANEL_KEY);
        }

        tasksListPanel.onPanelShown();
        rebuildProgress();
        showView(TASKS_LIST_PANEL_KEY);
    }

    public void navigateToModelsList() {
        // action for progress bar
        Runnable action = this::navigateToModelsList;
        // Don't add if we are already there
        while (!navigationHistory.isEmpty() && navigationHistory.peek().getCardLayoutKey().equals(MODELS_LIST_PANEL_KEY)) {
            navigationHistory.pop();
        }
        navigationHistory.push(new NavigationStep(modelListNavText, MODELS_LIST_PANEL_KEY, action));


        if (modelsListPanel == null) {
            modelsListPanel = new ModelsListPanel(this);
            contentAreaPanel.add(modelsListPanel, MODELS_LIST_PANEL_KEY);
        }

        modelsListPanel.onPanelShown();
        rebuildProgress();
        showView(MODELS_LIST_PANEL_KEY);
    }

    public void navigateToSubTasksList( String pageTitle, String parentModelId ) {
        String modelName = structure.getModelName(parentModelId);
        Runnable action = () -> navigateToSubTasksList(pageTitle, parentModelId);

        while (!navigationHistory.isEmpty() && navigationHistory.peek().getCardLayoutKey().equals(SUB_TASKS_LIST_PANEL_KEY)) {
            navigationHistory.pop();
        }
        navigationHistory.push(new NavigationStep(modelName, SUB_TASKS_LIST_PANEL_KEY, action));

        SubTasksListPanel subTasksListPanel = new SubTasksListPanel(this, pageTitle, parentModelId );
        contentAreaPanel.add(subTasksListPanel, SUB_TASKS_LIST_PANEL_KEY);
        rebuildProgress();
        showView(SUB_TASKS_LIST_PANEL_KEY);
    }

    public void navigateToSubModelsList(String pageTitle, String parentTaskId) {
        String taskName = structure.getTaskName(parentTaskId);
        Runnable action = () -> navigateToSubModelsList(pageTitle, parentTaskId);

        while (!navigationHistory.isEmpty() && navigationHistory.peek().getCardLayoutKey().equals(SUB_MODELS_LIST_PANEL_KEY)) {
            navigationHistory.pop();
        }
        navigationHistory.push(new NavigationStep(taskName, SUB_MODELS_LIST_PANEL_KEY, action));

        SubModelsListPanel subModelsListPanel = new SubModelsListPanel(this, pageTitle, parentTaskId);
        contentAreaPanel.add(subModelsListPanel, SUB_MODELS_LIST_PANEL_KEY);
        rebuildProgress();
        showView(SUB_MODELS_LIST_PANEL_KEY);
    }

    public void navigateToRunPage(String modelId, String progressText) {
        Runnable action = () -> navigateToRunPage(modelId, progressText);
        while (!navigationHistory.isEmpty() && navigationHistory.peek().getCardLayoutKey().equals(RUN_PANEL_KEY)) {
            navigationHistory.pop();
        }

        navigationHistory.push(new NavigationStep(progressText, RUN_PANEL_KEY, action));

        if (runPanel == null) {
            runPanel = new RunPanel(this);
            contentAreaPanel.add(runPanel, RUN_PANEL_KEY);
        }

        // Configure the panel with the correct context
        runPanel.configurePanel(modelId);

        rebuildProgress();
        // show the view
        showView(RUN_PANEL_KEY);
    }

    /**
     * Gets the absolute path to the central models directory for this plugin.
     * It will be located at [ImageJ_Root]/models/MicLearningModels/
     * This method also creates the directory if it doesn't exist.
     *
     * @return The absolute path as a String, or null if the ImageJ directory cannot be found.
     */
    public String getModelDirectoryPath() {
        String ijDir = IJ.getDirectory("imagej");
        if (ijDir == null) {
            IJ.error("Could not find ImageJ directory.");
            return null;
        }

        String modelsPath = ijDir + "models" + "/" + MODELS_SUBFOLDER;

        File modelsDir = new File(modelsPath);
        if (!modelsDir.exists()) {
            System.out.println("Creating models directory at: " + modelsPath);
            boolean success = modelsDir.mkdirs();
            if (!success) {
                IJ.error("Failed to create models directory: " + modelsPath);
                return null;
            }
        }
        return modelsPath;
    }


    // method for testing
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            MainApplication_Frame frame = new MainApplication_Frame();
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                    System.exit(0);
                }
            });
            frame.setVisible(true);
        });
    }


}
