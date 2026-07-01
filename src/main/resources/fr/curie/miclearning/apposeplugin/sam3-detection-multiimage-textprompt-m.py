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

# 2. prepare prediction
# pre process input image
# input image is saved in shared memory as appose.NDArray
# wrap the input image from appose.NDArray to numpy.ndarray
log_to_java("pre processing images...")
narr = images_input.ndarray()

# fix dimension order (from (B,H,W,C) rgb to (B,H,W,C) bgr)
# TODO add format image check
images_bgr = narr[..., ::-1]
n_frames, h_img, w_img = images_bgr.shape[:3]

# array need ot be saved in a contiguous memory block
images_bgr = np.ascontiguousarray(images_bgr).astype(np.uint8)

# store results in arrays
total_detection =0 # number of detections on al frames
global_masks = []
global_boxes = []
global_scores = []
global_prompt_ids = []  # To track which prompt produced which detection

# 3. make predictions for each image
log_to_java("running prediction...")
for frame_idx, img in enumerate(images_bgr) :
    # compute vision embeddings once per image
    if n_frames >1:
        log_to_java(f"   Slice {frame_idx + 1}")
    enc_img = detect_model.encode_image(img)

    # store results for this slice
    slice_total_detection =0
    slice_masks = []
    slice_boxes = []
    slice_scores = []
    slice_prompt_ids = []  # To track which prompt produced which detection

    # 4. Make prediction for each prompt
    for i, prompt in enumerate(text_prompts):
        # 4.1 encode inputs
        enc_exm = detect_model.encode_exemplars(enc_img, text=prompt)
        
        # 4.1 make prediction
        mask_preds, box_preds, det_scores, pres_score = detect_model.generate_detections(enc_img, enc_exm)
        # keep only prediction above confidence threshold
        filtered_masks, filtered_boxes, filtered_scores, presence_score = detect_model.filter_detections(
            mask_preds, box_preds, det_scores, pres_score, confidence_threshold
        )

        n_det = filtered_masks.shape[0] #number of detections for this frame, for this prompt
        log_to_java("      prompt: {} - number of objects detected: {} - presence score: {}".format(prompt, n_det, *presence_score.tolist()))
    
        # 4.3 process and store results for 1 prompt
        if presence_score > 0.5 and n_det>0 :
            slice_total_detection += n_det
    
            # 4.3.1 process boxes = convert boxes format
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
    
            # 4.3.2 move to cpu + Store results
            slice_masks.append((filtered_masks > mask_threshold).detach().cpu())
            slice_boxes.append(xywh_norm.detach().cpu().float())
            slice_scores.append(filtered_scores.detach().cpu().float())
    
            # Create an ID array for this prompt
            prompt_ids = torch.full((n_det,), i, dtype=torch.int32)
            slice_prompt_ids.append(prompt_ids)


     # 5. Concatenate and Share
    total_detection += slice_total_detection
    if slice_total_detection > 0 :
        # Merge all results into single tensors
        pre_final_masks = torch.cat(slice_masks, dim=0).numpy().astype('uint8')
        final_boxes = torch.cat(slice_boxes, dim=0).numpy().astype('float64')
        final_scores = torch.cat(slice_scores, dim=0).numpy().astype('float64')
        final_ids = torch.cat(slice_prompt_ids, dim=0).numpy().astype('int32')

        # resize masks
        final_masks = np.empty((slice_total_detection, h_img, w_img), dtype=np.uint8, device="cpu")
        for i in range(slice_total_detection):
            final_masks[i] = cv2.resize(pre_final_masks[i],(w_img, h_img),
                                          interpolation=cv2.INTER_NEAREST)

        # Create the NDArrays for sharing
        shared_masks = appose.NDArray("uint8", final_masks.shape)
        shared_boxes = appose.NDArray("float64", final_boxes.shape)
        shared_scores = appose.NDArray("float64", final_scores.shape)
        shared_ids = appose.NDArray("int32", final_ids.shape)

        # write into the shared memory
        shared_masks.ndarray()[:] = final_masks
        global_masks.append(shared_masks)
        shared_boxes.ndarray()[:] = final_boxes
        global_boxes.append(shared_boxes)
        shared_scores.ndarray()[:] = final_scores
        global_scores.append(shared_scores)
        shared_ids.ndarray()[:] = final_ids
        global_prompt_ids.append(shared_ids)

        # 6. send results to java via task update
        task.update(
            message=f"   slice {frame_idx+1} - number of objects detected: {slice_total_detection}",
            current=frame_idx,
            maximum=n_frames,
            info={
                "frame_idx": frame_idx,
                "n_results": slice_total_detection,
                "prompts_ids": global_prompt_ids[frame_idx],
                "scores" : global_scores[frame_idx],
                "boxes": global_boxes[frame_idx],
                "masks": global_masks[frame_idx]
            }
        )
    else :
        global_masks.append(slice_masks)
        global_boxes.append(slice_boxes)
        global_scores.append(slice_scores)
        global_prompt_ids.append(slice_prompt_ids)

        task.update(
            message=f"   slice {frame_idx+1} - number of objects detected: {slice_total_detection}",
            current=frame_idx,
            maximum=n_frames,
            info={
                "frame_idx": frame_idx,
                "n_results": slice_total_detection
            }
        )

log_to_java("python segmentation complete")