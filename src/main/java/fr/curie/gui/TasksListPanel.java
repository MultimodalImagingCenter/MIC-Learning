package fr.curie.gui;

import java.awt.event.ActionListener;
import java.util.function.BiConsumer;

public class TasksListPanel extends ButtonDescriptionListPanel {

    public TasksListPanel(MainApplication_Frame mainFrame) {
        super(mainFrame,                  // Main frame reference
                "Task Selection",            // Page Title
                "task"                   // Property key prefix for button names
        );

        BiConsumer<String, String> buttonHandler = createButtonHandler(mainFrame);
        this.setButtonActionHandler(buttonHandler);
        this.initializePanel();

    }

    // Define the specific action for task buttons
    private BiConsumer<String, String> createButtonHandler(MainApplication_Frame mainFrame) {
        return (taskId, taskName) -> {
            // Define the action for the "Select" button
            ActionListener selectAction = selectEvent -> {
                String title = taskName + " task selection";
                mainFrame.navigateToSubModelsList(title, taskId);
            };

            this.displayDescriptionContent(taskId, selectAction);
        };
    }

}
