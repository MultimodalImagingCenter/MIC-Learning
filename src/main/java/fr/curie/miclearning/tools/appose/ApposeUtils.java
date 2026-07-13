package fr.curie.miclearning.tools.appose;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageConverter;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.appose.ShmImg;
import net.imglib2.img.ImagePlusAdapter;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.integer.UnsignedByteType;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.stream.Collectors;


public class ApposeUtils {
    public static <T extends NumericType<T> & NativeType<T>> Img<T> imp2Img(ImagePlus imp) {
        // Wrap the ImagePlus into an ImgLib2 Img (which implements RAI)
        // This works for 8-bit, 16-bit and 32-bit (float)
        Img<T> image = ImagePlusAdapter.wrap(imp);
        System.out.println("\nImp converted to Img of dimensions: " + image.numDimensions());
        return image;
    }

    public static <T extends NumericType<T> & NativeType<T>> ShmImg<T> imp2ShmImg(ImagePlus imp) {
        ImagePlus tempImp = imp;

        // handle RGB: Convert packed ARGB to a 3-slice stack
        if (imp.getType() == ImagePlus.COLOR_RGB) {
            tempImp = imp.duplicate();
            new ImageConverter(tempImp).convertToRGBStack();
        }

        // wrap the ImagePlus into a RAI
        RandomAccessibleInterval<T> rai = ImageJFunctions.wrap(tempImp);

        // create the shared memory copy
        return ShmImg.copyOf(rai);
    }

    public static String getResourceAsString(String resourcePath) {

        try (InputStream is = ApposeUtils.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                IJ.log("ERROR : Unable to find resource");
                throw new RuntimeException("Unable to find resource : " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            IJ.log("ERROR : Unable to load resource");
            throw new RuntimeException("Error while reading file at " + resourcePath + ": ", e);
        }
    }

    public boolean isSupported(ImagePlus imp) {
        int type = imp.getType();
        return type == ImagePlus.COLOR_RGB;
    }

    public static ShmImg<UnsignedByteType> video2ShmImg(ImagePlus imp){
        return video2ShmImg(imp, 0, Math.max(imp.getNSlices(), imp.getNFrames()) -1);
    }
    public static ShmImg<UnsignedByteType> video2ShmImg(ImagePlus imp, int firstFrame){
        return video2ShmImg(imp, firstFrame, Math.max(imp.getNSlices(), imp.getNFrames()) -1);
    }

    public static ShmImg<UnsignedByteType> video2ShmImg(ImagePlus imp, int firstFrame, int lastFrame) {
        // last and first frame with index starting to 0
        if (imp.getType() != ImagePlus.COLOR_RGB) {
            throw new IllegalArgumentException("Only RGB images are supported.");
        }

        // TODO : ajouter possibilité image initiale RGBStack ou grayscale

        if (lastFrame<0) lastFrame = Math.max(imp.getNSlices(), imp.getNFrames())-1;
        int w = imp.getWidth();
        int h = imp.getHeight();
        int channels = 3;

        ShmImg<UnsignedByteType> shm = new ShmImg<>(new UnsignedByteType(),channels, w, h, lastFrame - firstFrame +1);

        Cursor<UnsignedByteType> cursor = shm.cursor();

        for (int f = firstFrame; f <= lastFrame; f++) {
            int[] pixels = (int[]) imp.getStack().getPixels(f + 1);

            for (int pixel : pixels) {
                // Channel R
                cursor.fwd();
                cursor.get().set((pixel >> 16) & 0xFF);

                // Channel G
                cursor.fwd();
                cursor.get().set((pixel >> 8) & 0xFF);

                // Channel B
                cursor.fwd();
                cursor.get().set(pixel & 0xFF);
            }
        }
        return shm;
    }
}