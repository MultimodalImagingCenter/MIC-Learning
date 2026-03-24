import appose
import torch
from PIL import Image
import matplotlib.pyplot as plt
import numpy as np
from sam3.model_builder import build_sam3_image_model
from sam3.model.sam3_image_processor import Sam3Processor
from sam3.model.box_ops import box_xywh_to_cxcywh
from sam3.visualization_utils import draw_box_on_image, normalize_bbox, plot_results
import logging
from PIL import Image

# send messages to Java via appose
appose_mode = 'task' in globals()
def log_to_java(msg):
    # 'task' is a global variable injected by Appose
    if appose_mode:
        task.update(message=str(msg))
    else:
        print(msg)

# 1. build sam3 model
#log_to_java("building model...")
model = build_sam3_image_model(checkpoint_path=model_path)
log_to_java("model built")
processor = Sam3Processor(model, confidence_threshold=confidence_threshold)

# 2. pre process input image
# input image is saved in shared memory as appose.NDArray
# wrap the input image from appose.NDArray to numpy.ndarray
narr = image_input.ndarray()

# fix dimension order (narr has dimension (C,H,W), pil expect (H,W,C))
if narr.ndim == 3:
    working_arr = np.moveaxis(narr, 0, -1)
else:
    working_arr = narr

# array need ot be saved in a contiguous memory block
working_arr = np.ascontiguousarray(working_arr).astype(np.uint8)

# convert nd array to pil Image as it is the only way I managed to make Sam work correctly
pil_img = Image.fromarray(working_arr)
# Get original image dimensions
h_img, w_img = working_arr.shape[:2]

# 3. prepare prediction
inference_state = processor.set_image(pil_img)
# store results in arrays
total_detection =0
all_masks = []
all_boxes = []
all_scores = []
all_prompt_ids = []  # To track which prompt produced which detection

# 4. make predictions
log_to_java("running prediction...")
for i, prompt in enumerate(text_prompts):
    processor.reset_all_prompts(inference_state)
    # text_prompt passed as input
    output = processor.set_text_prompt(state=inference_state, prompt=prompt)
    masks, boxes, scores = output["masks"], output["boxes"], output["scores"]
    log_to_java("   prompt: {}, number of objects detected: {}".format(prompt, len(boxes)))

    total_detection += len(boxes)

    # 5. process and store results for 1 prompt
    if boxes is not None and len(boxes) > 0:

        # 5.1 process boxes = convert boxes format
        # Extract coordinates from SAM output: [x1, y1, x2, y2]
        x1, y1, x2, y2 = boxes[:, 0], boxes[:, 1], boxes[:, 2], boxes[:, 3]
        # Calculate width and height
        width = x2 - x1
        height = y2 - y1

        # Normalize and Combine into [x1, y1, w, h]
        xywh_norm = torch.stack([
            x1 / w_img,     # x1 normalized
            y1 / h_img,     # y1 normalized
            width / w_img,  # width normalized
            height / h_img  # height normalized
        ], dim=-1)

        # 5.2 move to cpu + Store results
        all_masks.append(masks.detach().cpu())
        all_boxes.append(xywh_norm.detach().cpu())
        all_scores.append(scores.detach().cpu())

        # Create an ID array for this prompt
        prompt_ids = torch.full((boxes.shape[0],), i, dtype=torch.int32)
        all_prompt_ids.append(prompt_ids)


 # 6. Concatenate and Share
if len(all_boxes) > 0:
    # Merge all results into single tensors
    final_masks = torch.cat(all_masks, dim=0).numpy().astype('uint8').squeeze()
    final_boxes = torch.cat(all_boxes, dim=0).numpy().astype('float64')
    final_scores = torch.cat(all_scores, dim=0).numpy().astype('float64')
    final_ids = torch.cat(all_prompt_ids, dim=0).numpy().astype('int32')
    task.outputs['results_number'] = int(final_boxes.shape[0])

    # Create the NDArrays for sharing
    shared_masks = appose.NDArray("uint8", final_masks.shape)
    shared_boxes = appose.NDArray("float64", final_boxes.shape)
    shared_scores = appose.NDArray("float64", final_scores.shape)
    shared_ids = appose.NDArray("int32", final_ids.shape)

    # write into the shared memory
    shared_masks.ndarray()[:] = final_masks
    shared_boxes.ndarray()[:] = final_boxes
    shared_scores.ndarray()[:] = final_scores
    shared_ids.ndarray()[:] = final_ids

    # Pass back to Java
    task.outputs['masks'] = shared_masks
    task.outputs['boxes'] = shared_boxes
    task.outputs['scores'] = shared_scores
    task.outputs['prompt_ids'] = shared_ids
else:
    task.outputs['results_number'] = 0
