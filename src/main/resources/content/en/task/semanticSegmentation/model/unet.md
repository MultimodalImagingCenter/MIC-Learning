**U-Nets** are specifically designed for **semantic segmentation** of biomedical images. Their architecture features a
symmetric U-shape, composed of an encoder (the descending part of the "U") and a decoder (the ascending part of the
"U"):

*   **The Contracting Path (Encoder):** This section follows the architecture of a typical convolutional network used
    for classification. It consists of a series of blocks, each containing two successive convolution layers followed by a
    ReLU activation. The "contraction" is achieved through **downsampling** steps (e.g., max-pooling) between each block.
    With each subsequent block, the number of feature channels (depth) is increased (typically doubled), while with each
    downsampling step, the spatial dimensions of the feature maps are halved. This allows the network to progressively
    capture high-level, contextual features at different scales, but at the cost of losing precise localization information.

*   **The Expansive Path (Decoder):** Symmetrically to the encoder, this path is composed of blocks containing two
    convolution layers followed by ReLU activations. However, these blocks are connected by **upsampling** operations
    (often called **up-convolutions** or transposed convolutions), which increase the spatial dimensions of the feature
    maps. At each upsampling stage, a key feature known as **skip connections** is introduced. These connections copy the
    feature map from the corresponding layer in the contracting path and **concatenate** it with the upsampled feature map
    from the expansive path. In doing so, high-level context information (passed up through the decoder) is combined with
    fine-grained localization information (provided by the skip connection from the encoder).

*   **Final Layer:** Finally, after the last block of the expansive path, a final convolution (often a 1x1 convolution)
    followed by an activation function (typically a **softmax** or **sigmoid** function) produces a class map. This map
    provides the final semantic segmentation, where each pixel in the output corresponds to a predicted class label.

![unet](/contentImages/unet_network.png){width=400}
*Ronneberger & al. "U-Net: Convolutional Networks for Biomedical Image Segmentation" (2015)*