package fr.curie.miclearning.plugin.instanseg;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ij.plugin.frame.RoiManager;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import java.awt.image.BufferedImage;

public class ImageJUtils {

    public static Image imageProcessorToDjlImage(ImageProcessor ip) {
        BufferedImage bf = ip.getBufferedImage();
        return ImageFactory.getInstance().fromImage(bf);
    }

    public static Image bufferedImageToDjlImage(BufferedImage bf) {
        return ImageFactory.getInstance().fromImage(bf);
    }

    public static void binaryMaskToRois(BufferedImage mask, RoiManager roiManager) {
        BufferedImage gray = new BufferedImage(mask.getWidth(), mask.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        ImageProcessor ip = new ByteProcessor(gray);
        ip.setThreshold(1, 255, ImageProcessor.NO_LUT_UPDATE);
        ip.autoThreshold();

        ij.plugin.filter.ParticleAnalyzer pa = new ij.plugin.filter.ParticleAnalyzer(
                ij.plugin.filter.ParticleAnalyzer.ADD_TO_MANAGER,
                0, null, 0, Double.POSITIVE_INFINITY);
        pa.setHideOutputImage(true);
        pa.analyze(new ij.ImagePlus("mask", ip));
    }
}
