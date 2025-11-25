# Model and data
The model is a **U-Net** from the **BioImage Model Zoo**, available at [bioimage.io/affable-shark](https://bioimage.io/#/artifacts/affable-shark). 
It was trained to segment instances of **nuclei** in fluorescence microscopy images.
It predicts boundary maps and foreground probabilities for nucleus segmentation in
different light microscopy modalities, mainly with DAPI staining.

## Training data
The network was trained on data from the Data Science Bowl Nucleus Segmentation Challenge.
The training script can be found [here](https://github.com/constantinpape/torch-em/tree/main/experiments/dsb).

## Expected image
A grayscale fluorescence light microscopy image showing only the nuclei.

# Processing

## Pre-processing
These pre-processing steps are applied to each image before prediction:
1.  Resizing the image to the size expected by the model: 256x256.
2.  Normalizing the pixel values (using the `per_sample_scale_range.ijm` macro).

## Post-processing
The model produces a raw output consisting of two images:
- A foreground probability map (indicating the presence of a nucleus).
- A boundary probability map (outlining cell borders).

To generate the final segmentation, the `Contours2InstanceSegmentation.ijm` macro processes this raw output by:
1. Separating individual objects using a watershed algorithm.
2. Assigning a unique value to each object.

## Result
The final output is an **instance mask**, where each object is represented by a unique pixel value. 
For clearer visualization, each value is assigned a distinct color (by applying a Look-Up Table, or LUT).