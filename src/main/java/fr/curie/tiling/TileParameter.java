package fr.curie.tiling;

public class TileParameter {
    public int x_offset;
    public int y_offset;
    public int tile_width;
    public int tile_height;

    public TileParameter(int x_offset, int y_offset, int tile_width, int tile_height) {
        this.x_offset = x_offset;
        this.y_offset = y_offset;
        this.tile_width = tile_width;
        this.tile_height = tile_height;
    }

    public boolean validTile() {
        return x_offset >= 0 & y_offset >= 0 & tile_height > 0 & tile_height > 0;
    }
}
