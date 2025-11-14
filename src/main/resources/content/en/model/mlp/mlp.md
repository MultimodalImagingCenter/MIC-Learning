## Neurons
A Multilayer Perceptron (MLP) is a type of neural network. A **single neuron** operates as follows:

*   A vector of numerical values (x1, x2, ..., xn) is provided as **input** to the neuron.
*   Each input, xi, is associated with a **weight**, wi. 
The neuron then calculates the weighted sum of the inputs and adds a **bias**, b. 
These are the weights and biases that are learned during the training phase.
*   This result, z, is then passed through an **activation function**, such as a threshold function: 
if z is greater than a certain threshold (\theta), the final **output** is 1, otherwise, it is 0.

![single_neuron](/contentImages/perceptron.png){width=300}
*a single neuron*

## Multilayer Perceptron
An MLP is composed of at least three layers:

*   An **input layer**, where each neuron simply corresponds to a numerical feature of the input data.
*   One or more **hidden layers**, each composed of multiple neurons. Each neuron in one layer is connected to every 
neuron in the following layer, which is known as a **fully connected network**. Each of these neurons calculates the 
weighted sum of its inputs (the values from the preceding layer) using learned weights and a bias. This sum is then 
passed through a non-linear activation function (such as the sigmoid function or the Rectified Linear Unit (ReLU)). 
It is these hidden layers and their non-linear functions that enable the model to solve non-linear problems.
*   And an **output layer**, which receives the values from the last hidden layer and produces the final output. 
The number of neurons in this layer depends on the nature of the task (for example, for a classification task with 10 
classes, the output layer will be composed of 10 neurons).

![mlp](/contentImages/mlp.png){width=400}
*a MLP*