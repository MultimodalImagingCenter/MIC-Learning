# Model and data

## Data
The data comes from a public dataset, available on [zenodo](https://zenodo.org/records/1214456)

**Data Description "NCT-CRC-HE-100K"**
- This is a set of 100,000 non-overlapping image patches from hematoxylin & eosin (H&E) stained histological images of human colorectal cancer (CRC) and normal tissue.
- All images are 224x224 pixels (px) at 0.5 microns per pixel (MPP). All images are color-normalized using Macenko's method (http://ieeexplore.ieee.org/abstract/document/5193250/, DOI [10.1109/ISBI.2009.5193250](https://doi.org/10.1109/ISBI.2009.5193250)).
- Tissue classes are: Adipose (ADI), background (BACK), debris (DEB), lymphocytes (LYM), mucus (MUC), smooth muscle (MUS), normal colon mucosa (NORM), cancer-associated stroma (STR), colorectal adenocarcinoma epithelium (TUM).
- These images were manually extracted from N=86 H&E stained human cancer tissue slides from formalin-fixed paraffin-embedded (FFPE) samples from the NCT Biobank (National Center for Tumor Diseases, Heidelberg, Germany) and the UMM pathology archive (University Medical Center Mannheim, Mannheim, Germany). Tissue samples contained CRC primary tumor slides and tumor tissue from CRC liver metastases; normal tissue classes were augmented with non-tumorous regions from gastrectomy specimen to increase variability.

Only 3 classes were kept : LYM, MUS and ADI.

The data was split into 70% for training, 15% for validation, and 15% for testing. Subsampling was applied to the test set to balance the classes.

## Model
This model follows the standard **ResNet-18** architecture, a Deep Convolutional Neural Network with 18 learnable layers, organized into 5 stages:

1.  **Initial Block:** Applies 64 filters with a `7x7` kernel and stride 2, followed by **BatchNorm**, **ReLU** activation, and a **MaxPool2d** layer.
    *   *Output Shape: `64 x 56 x 56`*
2.  **Layer 1 (Residual Block):** Two sequences of convolutions (64 filters, `3x3`) with residual connections.
    *   *Output Shape: `64 x 56 x 56`*
3.  **Layer 2 (Residual Block):** Downsamples the image using stride 2 and increases filters to 128.
    *   *Output Shape: `128 x 28 x 28`*
4.  **Layer 3 (Residual Block):** Downsamples using stride 2 and increases filters to 256.
    *   *Output Shape: `256 x 14 x 14`*
5.  **Layer 4 (Residual Block):** Downsamples using stride 2 and increases filters to 512.
    *   *Output Shape: `512 x 7 x 7`*
6.  **AdaptiveAvgPool2d:** Averages each 7x7 feature map into a single value.
    *   *Output Vector Size: `512`*
7.  **Linear (Output Layer):** Modified from the original ImageNet layer. It maps the 512 features to the **3** target classes (ADI, LYM, MUS).

## Expected image
An RGB image of a homogeneous tissue, composed of a single cell type.

# Processing

## Pre-processing
These pre-processing steps are applied to each image before prediction:
1.  Resizing the image to **224x224** pixels (bilinear interpolation).
2.  Converting to Tensor (scaling pixel values from 0-255 to 0.0-1.0).
3.  Normalizing using ImageNet statistics (Mean: `[0.485, 0.456, 0.406]`, Std: `[0.229, 0.224, 0.225]`)

## Post-processing
These post-processing steps are applied to the raw prediction output:
1.  Apply a Softmax function to the logits to obtain a probability distribution.
2.  Select the index with the highest probability and map it to the corresponding class string (0=ADI, 1=LYM, 2=MUS).

## Result
The final output is a class + probability for each image.