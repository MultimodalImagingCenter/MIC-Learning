## Decision Trees
A **decision tree** is a model with a flowchart-like structure: 
it consists of a root, followed by a sequence of nodes and branches, and terminates in leaves. 
The role of each of these components can be broken down as follows:
- **The root:** The initial node where all the features of a data point are provided as input.
- **Internal nodes:** Each node represents a test on one of the features (
for example, "Is the object's diameter greater than 100 pixels?").
- **Branches:** The branches extending from a node correspond to the possible outcomes of the test 
(continuing the previous example, one branch for "diameter > 100px" and another for "diameter <= 100px").
Each branch leads to a new node.
- **Leaves:** These are the terminal nodes. They represent the final prediction (the outcome) 
based on the path taken through the tree's branches.

![a_single_node](/contentImages/node.png){width=200}
*a node and two branches*

Therefore, to make a prediction, a data point "descends" the tree. It is evaluated on a specific 
feature at each node, and the outcome of these tests determines which branch to follow until a final prediction 
is reached at a leaf node.

This type of model has the advantage of being highly **interpretable** (the questions asked at each node are explicit), 
but it is also sensitive to noise and has a tendency to **overfit**. Overfitting occurs when the model learns the 
training data too well, which reduces its ability to generalize to new, unseen data.

![a_single_tree](/contentImages/tree.png){width=300}
*a single tree*

## Random Forests
A solution to this problem is the use of decision tree forests, called **Random Forests**. As the name suggests, 
this is an **ensemble** of different decision trees, trained independently, whose individual predictions are then 
aggregated. To ensure the trees are different from one another, each tree is trained on a random subset of the training 
data. Furthermore, when constructing each node, the algorithm does not consider all possible features to find the best 
split. Instead, it only evaluates a random subset of features. This ensures that all features are potentially explored 
across the entire forest.

To make a prediction, the input data is passed through every tree in the forest. Each tree makes its own prediction, and 
the final result is determined either by a **majority vote** (the most frequently predicted class is chosen) for a 
**classification** task, or by **averaging** all the individual predictions for a **regression** task.

By combining the predictions of many different trees in this manner, the resulting output is far more robust and less 
sensitive to noise than that of a single decision tree.

![a_forest](/contentImages/forest.png){width=400}
*a random forest*

## Machine learning or deep learning ?
Decision Trees and Random Forests are classified as classic Machine Learning because they operate on pre-defined, 
structured data that requires manual feature engineering from a human expert. In other words, you must explicitly tell 
the model which features (e.g., "age," "diameter," "price") to consider. In contrast, Deep Learning models excel at 
automatic feature learning, using their deep neural network architecture to independently discover and build a hierarchy 
of meaningful features directly from raw data, like learning to recognize shapes and objects from the pixels of an image. 
Essentially, Random Forests learn from the features you provide, whereas Deep Learning models learn the features themselves.