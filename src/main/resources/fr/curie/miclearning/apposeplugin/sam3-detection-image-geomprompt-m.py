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
import cv2
import numpy as np
from muggled_sam.make_sam import make_sam_from_state_dict

def xywh_to_norm_x1y1x2y2(box, img_height, img_width):
    x1, y1, w, h = box
    x2, y2 = x1 + w, y1 + h
    norm_x1 = x1 / img_width
    norm_y1 = y1 / img_height
    norm_x2 = x2 / img_width
    norm_y2 = y2 / img_height

    return [(norm_x1, norm_y1), (norm_x2, norm_y2)]

def norm_xy(point, img_height, img_width):
    x1, y1 = point
    return (x1/img_width, y1/img_height)

# 1. build sam3 model
device, dtype = "cpu", torch.float32
if torch.cuda.is_available():
    device, dtype = "cuda", torch.bfloat16
log_to_java(f"building model, using device: {device}")
sam_core = make_sam_from_state_dict(model_path)
detect_model = sam_core.get_detector_context()
detect_model.to(device=device, dtype=dtype)

# 2. pre process input image
# input image is saved in shared memory as appose.NDArray
# wrap the input image from appose.NDArray to numpy.ndarray
log_to_java("pre processing image...")
narr = image_input.ndarray()

# fix dimension order (narr has dimension (C,H,W), pil expect (H,W,C))
# fix dimension order (from (C,H,W) rgb to (H,W,C) bgr)
# TODO add format image check
image_bgr = narr.transpose(1, 2, 0)[..., ::-1]
h_img, w_img = image_bgr.shape[:2]

# array need ot be saved in a contiguous memory block
image_bgr = np.ascontiguousarray(image_bgr).astype(np.uint8)

# 3. prepare prediction
# compute vision embeddings once
log_to_java("encoding image...")
enc_img = detect_model.encode_image(image_bgr)

# store results in arrays
total_detection =0
all_masks = []
all_boxes = []
all_scores = []
all_group_ids = []  # To track which prompt produced which detection

# 4. make predictions for each input
log_to_java("running prediction...")
text_prompt = "visual"
# manage negative groups (if any)
neg_box_xy1xy2_norm_list = []
neg_point_xy_norm_list = []
# negative_rois format: [[x,y,w,h], [x,y], ...] (absolute values)
# expected format:
#  one list for boxes : [[(x1, y1), (x2, y2)], ...] (normalized values)
# one list for points : [(x, y), ... ] (normalized values)
if len(negative_rois) > 0:
    log_to_java(f"({len(negative_rois)} negative prompt(s) added to every detection)")
    for prompt in negative_rois :
        if len(prompt) == 4: # box
            neg_box_xy1xy2_norm_list.append(xywh_to_norm_x1y1x2y2(prompt, h_img, w_img))
        elif len(prompt) == 2: #point
            neg_point_xy_norm_list.append(norm_xy(prompt,h_img, w_img))
        else :
            log_to_java(f"unknown prompt size: prompt {prompt}")

# make prediction for each positive roi group
# positive_rois structure: { '1': [[x,y,w,h], [x,y], ...], '2': [[x,y,w,h], [x,y], ...] }
for group_id, prompts in positive_rois.items():
    log_to_java(f"  group {group_id} - {len(prompts)} prompt(s)...")
    pos_box_xy1xy2_norm_list = []
    pos_point_xy_norm_list = []
    for prompt in prompts :
        if len(prompt) == 4: # box
            pos_box_xy1xy2_norm_list.append(xywh_to_norm_x1y1x2y2(prompt, h_img, w_img))
        elif len(prompt) == 2: #point
            pos_point_xy_norm_list.append(norm_xy(prompt,h_img, w_img))
        else :
            log_to_java(f"unknown prompt size: prompt {prompt}")

    # encode inputs
    enc_exm = detect_model.encode_exemplars(
        enc_img,
        text_prompt,
        pos_box_xy1xy2_norm_list,
        pos_point_xy_norm_list,
        neg_box_xy1xy2_norm_list,
        neg_point_xy_norm_list,
    )
    # make prediction
    mask_preds, box_preds, det_scores, pres_score = detect_model.generate_detections(enc_img, enc_exm)
    # keep only prediction above confidence threshold
    filtered_masks, filtered_boxes, filtered_scores, presence_score = detect_model.filter_detections(
        mask_preds, box_preds, det_scores, pres_score, confidence_threshold
    )
    n_det = filtered_masks.shape[0]
    log_to_java("  group {} - {} prompt(s): number of object(s) detected: {} - presence score: {}".format(group_id, len(prompts), n_det, *presence_score.tolist()))

    # 5. process and store results for 1 prompt
    if presence_score > 0.5 and n_det>0 :
        total_detection += n_det

        # 5.1 process boxes = convert boxes format
        # Extract coordinates from muggled SAM output: [[[x1, y1], [x2, y2]], [...]]
        x1 = filtered_boxes[:, 0, 0]
        y1 = filtered_boxes[:, 0, 1]
        x2 = filtered_boxes[:, 1, 0]
        y2 = filtered_boxes[:, 1, 1]

        # already normalized
        # combine into [[x1, y1, w, h], [...]]
        xywh_norm = torch.stack(
            [x1, y1, x2 - x1,  y2 - y1],
            dim=-1)

        # 5.2 move to cpu + Store results
        all_masks.append((filtered_masks > mask_threshold).detach().cpu())
        all_boxes.append(xywh_norm.detach().cpu().float())
        all_scores.append(filtered_scores.detach().cpu().float())

        # Create an ID array for this prompt
        group_ids = torch.full((n_det,), int(group_id), dtype=torch.int32)
        all_group_ids.append(group_ids)


 # 6. Concatenate and Share
if total_detection > 0:
    # Merge all results into single tensors
    pre_final_masks = torch.cat(all_masks, dim=0).numpy().astype('uint8')
    final_boxes = torch.cat(all_boxes, dim=0).numpy().astype('float64')
    final_scores = torch.cat(all_scores, dim=0).numpy().astype('float64')
    final_ids = torch.cat(all_group_ids, dim=0).numpy().astype('int32')
    task.outputs['results_number'] = int(final_boxes.shape[0])

    # resize masks
    final_masks = np.empty((total_detection, h_img, w_img), dtype=np.uint8, device="cpu")
    for i in range(total_detection):
        final_masks[i] = cv2.resize(pre_final_masks[i],(w_img, h_img),
                                      interpolation=cv2.INTER_NEAREST)

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

