package fr.curie.tools.tiling;

// --- User Tiling Selection ---
public class TilingOptions {
    public boolean useTiling = false;
    public int tileWidth;
    public int tileHeight;
    public double overlap;
    public boolean defaultTileSize;

    public void reset() {
        useTiling = false;
        tileWidth = -1;
        tileHeight = -1;
        overlap = 0.0;
        defaultTileSize = true;
    }

    public void setWidthAndHeight(int width, int height) {
        this.tileWidth = width;
        this.tileHeight = height;
    }

}
