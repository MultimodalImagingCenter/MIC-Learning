package fr.curie.gui;

public class UseCaseConfig {
    private final String name;
    private final String descriptionTitle;
    private final String modelDirectoryPath;
    private final String exampleImagePath;
    private final String defaultMacro;
    private final String optionMacro;

    public UseCaseConfig(String name, String descriptionTitle, String modelDirectoryPath, String exampleImagePath, String defaultMacro, String optionMacro) {
        this.name = name;
        this.descriptionTitle = descriptionTitle;
        this.modelDirectoryPath = modelDirectoryPath;
        this.exampleImagePath = exampleImagePath;
        this.defaultMacro = defaultMacro;
        this.optionMacro = optionMacro;
    }

    public String getExampleName() { return name; }
    public String getDescriptionTitle() { return descriptionTitle; }
    public String getModelDirectoryPath() { return modelDirectoryPath; }
    public String getExampleImagePath() { return exampleImagePath; }
    public String getDefaultMacro() { return defaultMacro; }
    public String getOptionMacro() { return optionMacro; }

}
