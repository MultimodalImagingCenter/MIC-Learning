# Model and data
The model is a **U-Net** from the **BioImage Model Zoo**.
This semantic segmentation model was trained to segment mitochondria in sections of nerve tissue from ***D. melanogaster***, imaged by transmission electron microscopy.

# Processing

## Pre-processing
These pre-processing steps are applied to each image before prediction:
1.  Resizing the image to the size expected by the model: 512x512.
2.  Normalizing the pixel values (using the `per_sample_scale_range.ijm` macro).

## Result
The final result consists of two probability masks:
*   one indicating the presence of mitochondria
*   and the other for the mitochondria boundaries