package fr.curie.instanseg;

import ai.djl.MalformedModelException;
import ai.djl.inference.Predictor;
import ai.djl.modality.cv.Image;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import fr.curie.instanseg.InstanSegTranslator;
import fr.curie.instanseg.ImageJUtils;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.plugin.filter.PlugInFilter;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class InstanSeg_Plugin implements PlugInFilter {
    private static ImagePlus imp;
    private Path modelPath;

    @Override
    public int setup(String arg, ImagePlus imagePlus) {
        IJ.log(">>> setup() appelé <<<");
        imp = imagePlus;
        return DOES_ALL;
    }



    @Override
    public void run(ImageProcessor ip) {
        IJ.log("Début du plugin");

        if (imp == null) {
            IJ.log("ImagePlus is null");
            return;
        }


        GenericDialog gd = new GenericDialog("Select InstanSeg Model");
        gd.addStringField("Model Path:", "models/brightfield_nuclei.zip", 40);
        gd.showDialog();
        if (gd.wasCanceled()) {
            IJ.log("Plugin annulé par l'utilisateur");
            return;
        }

        String modelPathStr = gd.getNextString();
        modelPath = Paths.get(modelPathStr);
        IJ.log("Chemin du modèle : " + modelPathStr);
        if (!Files.exists(modelPath)) {
            IJ.log("Le fichier modèle n'existe pas !");
            IJ.error("Model file not found:", modelPathStr);
            return;
        }

        try {
            IJ.log("Conversion image...");
            Image image = ImageJUtils.imageProcessorToDjlImage(ip);
            if (image == null) {
                IJ.log("Erreur conversion image vers DJL");
                return;
            }

            IJ.log("Segmentation en cours...");
            BufferedImage resultImage = runTiledSegmentation(image);
            if (resultImage == null) {
                IJ.log("Erreur dans runTiledSegmentation");
                return;
            }

            IJ.log("Affichage du résultat...");
            ImagePlus resultImp = new ImagePlus("InstanSeg Output", resultImage);
            resultImp.show();

            IJ.log("Conversion ROIs...");
            RoiManager roiManager = RoiManager.getRoiManager();
            roiManager.reset();
            ImageJUtils.binaryMaskToRois(resultImage, roiManager);

            IJ.log("Plugin terminé !");
        } catch (Exception e) {
            IJ.handleException(e);
            IJ.log("Exception : " + e.getMessage());
        }
    }

    private BufferedImage runTiledSegmentation(Image image) throws IOException, ModelNotFoundException, MalformedModelException, TranslateException {
        int tileSize = 256;
        int width = image.getWidth();
        int height = image.getHeight();

        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);

        InstanSegTranslator translator = new InstanSegTranslator();

        Criteria<Image, Image> criteria = Criteria.builder()
                .setTypes(Image.class, Image.class)
                .optModelPath(modelPath)
                .optTranslator(translator)
                .optEngine("PyTorch")
                .optModelName("instanseg.pt")
                .build();

        try (ZooModel<Image, Image> model = criteria.loadModel();
             Predictor<Image, Image> predictor = model.newPredictor()) {

            for (int y = 0; y < height; y += tileSize) {
                for (int x = 0; x < width; x += tileSize) {
                    int w = Math.min(tileSize, width - x);
                    int h = Math.min(tileSize, height - y);

                    BufferedImage tile = imp.getBufferedImage().getSubimage(x, y, w, h);
                    Image inputTile = ImageJUtils.bufferedImageToDjlImage(tile);
                    Image outputTile = predictor.predict(inputTile);
                    BufferedImage resultBuf = (BufferedImage) outputTile.getWrappedImage();

                    output.getRaster().setRect(x, y, resultBuf.getRaster());
                }
            }
        }

        return output;
    }
}
