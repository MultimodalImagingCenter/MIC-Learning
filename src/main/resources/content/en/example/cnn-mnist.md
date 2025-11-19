# Model and data

## Data
The data comes from a public dataset : MNIST dataset (Modified National Institute of Standards and Technology database),
a database of handwritten digits

**Data Description**
- This is a set of 60,000 training images and 10,000 testing images, each with one handwritten digit (between 0 and 9).
- All images are grayscale, 28x28 pixels (px).
- 10 classes : numbers from 0 to 9.

The data was split into 70% for training, 15% for validation, and 15% for testing.

## Model
This CNN model has the same architecture as the LeNet-5 network, with 8 layers :

1.  **Conv2d:** Applies 6 filters with a `5x5` kernel and `2x2` padding, followed by a **Sigmoid** activation.
    *   *Output Shape: `24 x 24 x 6`*

2.  **AvgPool2d:** Downsamples the feature maps using a `5x5` kernel, `2x2` stride, and `2x2` padding.
    *   *Output Shape: `12 x 12 x 6`*

3.  **Conv2d:** Applies 16 filters with a `5x5` kernel, followed by a **Sigmoid** activation.
    *   *Output Shape: `8 x 8 x 16`*

4.  **AvgPool2d:** Performs a second downsampling with a `5x5` kernel, `2x2` stride, and `2x2` padding.
    *   *Output Shape: `4 x 4 x 16`*

5.  **Flatten:** Reshapes the `4x4x16` tensor into a flat vector.
    *   *Output Vector Size: `256`*

6.  **Linear (Fully Connected):** Reduces the vector to **120** units, followed by a **Sigmoid** activation.

7.  **Linear (Fully Connected):** Further reduces the vector to **84** units, followed by a **Sigmoid** activation.

8.  **Linear (Output Layer):** Produces the final logits with a size equal to the number of classes (`output` variable).
## Expected image
A grayscale image of one handwritten digit (don't hesitate to try to test it on your handwriting !)

# Processing

## Pre-processing
These pre-processing steps are applied to each image before prediction:
1. Resizing the image to the size expected by the model: 28x28.
2. Normalizing the pixel values.

## Post-processing
These post-processing steps are applied to the raw prediction output:
1.  Apply a softmax function to the results to obtain a probability
2.  Select highest probability and the associated class

## Result
The final output is a class + a probability for each image.