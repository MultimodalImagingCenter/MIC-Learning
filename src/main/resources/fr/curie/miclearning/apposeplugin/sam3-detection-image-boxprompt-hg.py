import appose
# send messages to Java via appose
appose_mode = 'task' in globals()
def log_to_java(msg):
    # 'task' is a global variable injected by Appose
    if appose_mode:
        task.update(message=str(msg))
    else:
        print(msg)

log_to_java("importing packages...")

import torch
from PIL import Image
import numpy as np
from transformers import Sam3Processor, Sam3Model
import requests

device = "cuda" if torch.cuda.is_available() else "cpu"
log_to_java(f"using device: {device}")

# 1. build sam3 model
log_to_java("building model...")
model = Sam3Model.from_pretrained(model_path).to(device)
processor = Sam3Processor.from_pretrained(model_path)

# 2. pre process input image
# input image is saved in shared memory as appose.NDArray
# wrap the input image from appose.NDArray to numpy.ndarray
log_to_java("pre processing image...")
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
# Pre-process image and compute vision embeddings once
img_inputs = processor(images=pil_img , return_tensors="pt").to(device)
with torch.no_grad():
    vision_embeds = model.get_vision_features(pixel_values=img_inputs.pixel_values)

# store results in arrays
total_detection =0
all_masks = []
all_boxes = []
all_scores = []
all_group_ids = []  # To track which prompt produced which detection

# 4. make predictions
log_to_java("running prediction...")
neg_group_inputs = []
neg_inputs_label = []
if (len(negative_rois) > 0) :
    log_to_java(f"({len(negative_rois)} negative prompt(s) added to every detection)")
    for box in negative_rois :
           x, y, w, h = box
           box_xyxy = [x,y,x+w,y+h]
           neg_group_inputs.append(box_xyxy)
           neg_inputs_label.append(0)

# positive_rois structure: { '1': [[x,y,w,h], ...], '2': [[x,y,w,h], ...] }
for group_id, boxes in positive_rois.items():
    log_to_java(f"  group {group_id} - {len(boxes)} prompt(s)...")
    group_inputs = []
    inputs_label = []
    for box in boxes:
        x, y, w, h = box
        box_xyxy = [x,y,x+w,y+h]
        group_inputs.append(box_xyxy)
        inputs_label.append(1)

    group_inputs = group_inputs + neg_group_inputs
    inputs_label = inputs_label + neg_inputs_label

    boxes_inputs = processor(
        original_sizes = [[h_img, w_img]],
        input_boxes=[group_inputs],
        input_boxes_labels=[inputs_label],
        return_tensors="pt"
    ).to(device)

    with torch.no_grad():
            outputs = model(vision_embeds=vision_embeds, **boxes_inputs)

    results = processor.post_process_instance_segmentation(
        outputs,
        threshold=confidence_threshold,
        mask_threshold=0.5,
        target_sizes=boxes_inputs.get("original_sizes").tolist()
    )[0]

    masks, boxes, scores = results["masks"], results["boxes"], results["scores"]
    log_to_java(f"    number of object(s) detected: {len(boxes)} ")

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
        group_ids = torch.full((boxes.shape[0],), int(group_id), dtype=torch.int32)
        all_group_ids.append(group_ids)


 # 6. Concatenate and Share
if len(all_boxes) > 0:
    # Merge all results into single tensors
    final_masks = torch.cat(all_masks, dim=0).numpy().astype('uint8').squeeze()
    final_boxes = torch.cat(all_boxes, dim=0).numpy().astype('float64')
    final_scores = torch.cat(all_scores, dim=0).numpy().astype('float64')
    final_ids = torch.cat(all_group_ids, dim=0).numpy().astype('int32')
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
    task.outputs['group_ids'] = shared_ids
else:
    task.outputs['results_number'] = 0
