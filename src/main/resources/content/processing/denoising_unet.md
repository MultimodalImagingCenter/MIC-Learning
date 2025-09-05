# Model and data

## Data
The images are of cells obtained via **quantitative image-based cytometry (QIBC)**. These original images have a sufficiently high signal-to-noise ratio to be considered noise-free.

Artificial noise was added to the images. For each image, the intensity range (*e* = *i*<sub>max</sub> − *i*<sub>min</sub>) was measured. Then, Gaussian noise was added using the Gaussian noise function in ImageJ, with a standard deviation value of `std = 0.25 * e` (i.e., 25% of the intensity range).

Model training and inference were performed in ImageJ, using the **Noise2Void 0.8.6 plugin**.

## Model
The trained model is a **Noise2Void** model.
Noise2Void is a denoising algorithm with a **U-Net architecture**, whose key characteristic is its reliance on **self-supervised learning**. Therefore, training a Noise2Void model does not require pairs of noisy images and their clean ground truth counterparts, but only the noisy images themselves.

Three noisy images (with a 50% noise level) were used to train the model. Training was performed using the CSBDeep Noise2Void plugin with the following parameters:
*   Number of epochs: 100
*   Number of steps per epoch: 150
*   Validation set: 10% of the training images
*   Batch size: 64
*   Patch shape: 64x64
*   Neighborhood radius: 5

# Processing

Prediction is performed using the Noise2Void plugin.

## Result
The final result is a denoised image.
