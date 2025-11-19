**Convolutional Neural Networks (CNNs)** have an architecture specifically designed to process data with a grid-like 
topology, such as images.

They are composed of several specific types of layers:

*   **Convolutional Layer:** This layer contains multiple **filters** (or **kernels**). A filter is a small matrix of 
weights. By positioning this filter over a portion of the image, a **dot product** is computed between its weights and 
the pixel values it covers. The filter is then slid over to the next portion of the image, a new dot product is 
calculated, and so on, until it has traversed the entire image. Each of these filters learns to detect a specific type
of feature, such as an edge or a particular texture. The output of this operation is called a **feature map**.

*   **Activation Layer:** Each feature map is passed through a non-linear activation function, such as a **Rectified 
Linear Unit (ReLU)**, which replaces all negative values with zero.

*   **Pooling Layer (Subsampling):** This layer reduces the spatial dimensions (width and height) of the feature maps. 
A common operation is **max-pooling**, which involves retaining only the maximum value from a small rectangular region 
of the feature map.

![cnn_simplified](/contentImages/cnn_simplified.png){width=600}
*Purwono & al. “Understanding of Convolutional Neural Network” (2023)*

The first part of a CNN, often called the convolutional base, typically consists of a sequence of these three layer 
types (convolution + activation + pooling). The final output of this base is a set of highly processed feature maps, 
which have smaller spatial dimensions than the original image but a greater depth (more channels). These maps are then 
"flattened" into a vector and fed into a standard Multi-Layer Perceptron (MLP) (a set of **fully-connected (dense) l
ayers**) which produces the final output: a probability for each class.

Crucially, the values within the convolutional filters, as well as the weights and biases of the final MLP, are all 
**learned** during the training process.

This type of architecture is particularly effective for image processing, notably because the convolutional layers 
enable the detection of local patterns **regardless of their position in the image** (a property known as **translation 
invariance**). Furthermore, through the succession of pooling layers, the network learns a **hierarchy of features** at 
various scales. It starts by identifying simple features in the early layers (e.g., lines, colors) and combines them in 
deeper layers to detect more complex concepts (e.g., shapes, objects, up to an entire face).