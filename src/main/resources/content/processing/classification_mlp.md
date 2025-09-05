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
This MLP model has been trained to classify the 3 cellular types.

It has the following architecture:
*   An input layer with 50,176 neurons (for a 224x224 input).
*   Two hidden layers, with 128 and 64 neurons, respectively.
*   An output layer with 3 neurons, one for each class.

## Expected image
An RGB image of a homogeneous tissue, composed of a single cell type.

# Processing

## Pre-processing
These pre-processing steps are applied to each image before prediction:
1. Resizing the image to the size expected by the model: 224x224.
2. Normalizing the pixel values.

## Post-processing
These post-processing steps are applied to the raw prediction output:
1.  Apply a softmax function to the results to obtain a probability
2.  Select highest probability and the associated class

## Result
The final output is a class + probability for each image.