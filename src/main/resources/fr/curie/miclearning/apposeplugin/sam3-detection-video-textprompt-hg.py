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
import numpy as np
from transformers import Sam3VideoModel, Sam3VideoProcessor
from accelerate import Accelerator
import PIL
import time

# 1. build sam3 model
device = Accelerator().device
log_to_java(f"building model, using device: {device}...")
model = Sam3VideoModel.from_pretrained(model_path).to(device, dtype=torch.bfloat16)
processor = Sam3VideoProcessor.from_pretrained(model_path)

# 2. pre process input video
log_to_java("pre processing video...")
# Access the NDArray from shared memory
# input is saved in shared memory as appose.NDArray
# wrap the input from appose.NDArray to numpy.ndarray
narr = video_input.ndarray()

if narr.ndim == 4:
    # actually, no nee to fix dimension order
    working_arr = narr
else:
    # Fallback/Error handling if input is not 4D as expected
    raise ValueError(f"Expected 4D array got ndim={narr.ndim}")

# array need ot be saved in a contiguous memory block
video_frames = np.ascontiguousarray(working_arr).astype(np.uint8)
# get original image dimensions
h_img, w_img = working_arr[1].shape[:2]

# 3. prepare prediction
# Initialize video inference session
log_to_java("initializing inference session")
inference_session = processor.init_video_session(
    video=video_frames,
    inference_device=device,
    processing_device="cpu",
    video_storage_device="cpu",
    dtype=torch.bfloat16,
)

# store all results in arrays
all_masks = []
all_boxes = []
all_scores = []
all_ids = []

# 4. make predictions
# add prompt
text = text_prompt
inference_session = processor.add_text_prompt(
    inference_session=inference_session,
    text=text,
)

# Process all frames in the video
# analyze and send to java frame by frame
log_to_java(f"running prediction... Prompt = {text}")
for model_outputs in model.propagate_in_video_iterator(
    inference_session=inference_session, max_frame_num_to_track=max_frame_number-1): #-1 because is it the number of the last frame, not the total number of frame
    processed_outputs = processor.postprocess_outputs(inference_session, model_outputs)

    frame_idx = model_outputs.frame_idx
    masks, boxes, scores, ids = processed_outputs["masks"], processed_outputs["boxes"], processed_outputs["scores"], processed_outputs['object_ids']
    N = len(ids)
    # Select buffer index using modulo to wrap around the pool

    # log_to_java("   frame: {} - number of objects detected: {}".format(frame_idx, N))

    # 5. process and store results for the frame
    if boxes is not None and len(boxes) > 0:
        # 5.1 process boxes
        # convert boxes format
        # extract coordinates from SAM output: [x1, y1, x2, y2]
        x1, y1, x2, y2 = boxes[:, 0], boxes[:, 1], boxes[:, 2], boxes[:, 3]
        # Calculate width and height (absolute pixels)
        width = x2 - x1 + 1
        height = y2 - y1 + 1
        # combine into [x1, y1, w, h]
        xywh_norm = torch.stack([x1/w_img, y1/h_img, width/w_img, height/h_img], dim=-1)
        # prepare share boxes results
        shared_boxes = appose.NDArray("float64", xywh_norm.shape)
        # move tensor to cpu + Perform a zero-copy write into the shared memory
        shared_boxes.ndarray()[:] = xywh_norm.detach().cpu().numpy().astype('float64')
        all_boxes.append(shared_boxes)

        # 5.2 process masks
        # convert tensor to numpy uint8
        shared_masks = appose.NDArray("uint8", masks.squeeze().shape)
        masks_np = (masks.squeeze() > 0.5).detach().cpu().numpy().astype('uint8')
         # move tensor to cpu + write into shared memory
        shared_masks.ndarray()[:] = masks_np
        all_masks.append(shared_masks)

        # 5.3 process scores
        shared_scores = appose.NDArray("float64", scores.shape)
        shared_scores.ndarray()[:] = scores.detach().cpu().numpy().astype('float64')
        all_scores.append(shared_scores)

         #5.4. process ids
        shared_ids = appose.NDArray("int32", ids.shape)
        shared_ids.ndarray()[:] = ids.detach().cpu().numpy().astype('int32')
        all_ids.append(shared_ids)

        # 6. send results to java via task update
        task.update(
            message=f"   frame {frame_idx} - number of objects detected: {N}",
            current=frame_idx,
            maximum=max_frame_number,
            info={
                "frame_idx": frame_idx,
                "n_results": N,
                "object_ids": all_ids[frame_idx],
                "scores" : all_scores[frame_idx],
                "boxes": all_boxes[frame_idx],
                "masks": all_masks[frame_idx]
            }
        )

log_to_java("python segmentation complete")

