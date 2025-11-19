package fr.curie.gui;

// associate the id of an example model with its name
public class DisplayItem {

    private final String id;
    private final String name;

    public DisplayItem(String id, String name){
        this.id = id;
        this.name = name;
    }

    public String getId() { return id; }
    public String getName() { return name; }

    @Override
    public String toString() {
        return name;
    }
}
