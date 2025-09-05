package fr.curie.gui;

public class NavigationStep {
    private final String displayText; // Text to show (e.g., "Home", "Classification")
    private final String cardLayoutKey; // Key to show in CardLayout (e.g., HOME_PANEL_KEY)
    private final Runnable action; // The action to perform to restore this state

    public NavigationStep(String displayText, String cardLayoutKey, Runnable action) {
        this.displayText = displayText;
        this.cardLayoutKey = cardLayoutKey;
        this.action = action;
    }

    public String getDisplayText() { return displayText; }
    public String getCardLayoutKey() { return cardLayoutKey; }
    public Runnable getAction() { return action; }
}
