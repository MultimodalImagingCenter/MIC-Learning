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

# 1. build sam3 model
device, dtype = "cpu", torch.float32
if torch.cuda.is_available():
    device, dtype = "cuda", torch.bfloat16
log_to_java(f"building model, using device: {device}...")
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
# Pre-process image and compute vision embeddings once
log_to_java("encoding image...")
enc_img = detect_model.encode_image(image_bgr)

# store results in arrays
total_detection =0
all_masks = []
all_boxes = []
all_scores = []
all_prompt_ids = []  # To track which prompt produced which detection

# 4. make predictions for each input
log_to_java("running prediction...")
for i, prompt in enumerate(text_prompts):
    # encode inputs
    enc_exm = detect_model.encode_exemplars(enc_img, text=prompt)
    # make prediction
    mask_preds, box_preds, det_scores, pres_score = detect_model.generate_detections(enc_img, enc_exm)
    # keep only prediction above confidence threshold
    filtered_masks, filtered_boxes, filtered_scores, presence_score = detect_model.filter_detections(
        mask_preds, box_preds, det_scores, pres_score, confidence_threshold
    )
    n_det = filtered_masks.shape[0]
    log_to_java("   prompt: {} - number of objects detected: {} - presence score: {}".format(prompt, n_det, *presence_score.tolist()))

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
        prompt_ids = torch.full((n_det,), i, dtype=torch.int32)
        all_prompt_ids.append(prompt_ids)


 # 6. Concatenate and Share
if total_detection > 0:
    # Merge all results into single tensors
    pre_final_masks = torch.cat(all_masks, dim=0).numpy().astype('uint8')
    final_boxes = torch.cat(all_boxes, dim=0).numpy().astype('float64')
    final_scores = torch.cat(all_scores, dim=0).numpy().astype('float64')
    final_ids = torch.cat(all_prompt_ids, dim=0).numpy().astype('int32')
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
    task.outputs['prompt_ids'] = shared_ids
else:
    task.outputs['results_number'] = 0
