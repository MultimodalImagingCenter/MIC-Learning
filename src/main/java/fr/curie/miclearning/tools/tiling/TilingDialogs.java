package fr.curie.miclearning.tools.tiling;

import ij.IJ;
import ij.gui.GenericDialog;

public class TilingDialogs {

    public static void addTilingDialog(GenericDialog gd){
        gd.addMessage("Tiling options");
        gd.addCheckbox("Use_tiling", false);
        gd.addMessage("if you want to use model default size as tile size, write -1 for tile width and height");
        gd.addNumericField("tile_width", -1);
        gd.addNumericField("tile_height", -1);
        gd.addNumericField("overlap (proportion of tile between 0 and 1)", 0.0, 2);
    }

    public static TilingOptions getTilingAnswer(GenericDialog gd){
        TilingOptions tilingOptions = new TilingOptions();
        tilingOptions.useTiling = gd.getNextBoolean();
        tilingOptions.tileWidth = (int) gd.getNextNumber();
        tilingOptions.tileHeight = (int) gd.getNextNumber();
        tilingOptions.defaultTileSize = tilingOptions.tileWidth <=0 || tilingOptions.tileHeight <= 0;
        double overlap = Math.max(0.0, gd.getNextNumber());
        if (overlap >= 1){
            IJ.log("Warning : overlap must smaller than 1. No overlap will be used");
            overlap = 0.0;
        }
        tilingOptions.overlap = overlap;
        return tilingOptions;
    }
}
