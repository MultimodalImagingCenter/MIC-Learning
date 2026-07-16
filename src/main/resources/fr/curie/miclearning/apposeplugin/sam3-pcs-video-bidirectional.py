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
from collections import defaultdict
from muggled_sam.make_sam import make_sam_from_state_dict
from muggled_sam.demo_helpers.video_data_storage import SAMVideoMemoryBank
from muggled_sam.demo_helpers.bounding_boxes import get_2box_iou

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
    return (x1 / img_width, y1 / img_height)

# --- 1. build sam3 model
device, dtype = "cpu", torch.float32
if torch.cuda.is_available():
    device, dtype = "cuda", torch.bfloat16
log_to_java(f"loading model, using device: {device}...")

# model paths received from java
detection_model_path = detectionModelPath
tracking_model_path = trackingModelPath
sam_core_detect = make_sam_from_state_dict(detection_model_path)
detect_model = sam_core_detect.get_detector_context().to(device=device, dtype=dtype)
# Allow loading of alternate model for tracking
sam_core_track = sam_core_detect
is_using_separate_tracking_model = (tracking_model_path is not None and tracking_model_path != detection_model_path)
if is_using_separate_tracking_model:
    log_to_java("loading separate tracking model...")
    sam_core_track = make_sam_from_state_dict(tracking_model_path)
track_model = sam_core_track.get_tracking_context().to(device=device, dtype=dtype)

# --- 2. prepare prediction
# -- 2.1 pre process input image
# input image is saved in shared memory as appose.NDArray
# wrap the input image from appose.NDArray to numpy.ndarray
log_to_java("pre processing images...")
video_input = videoInput
narr = video_input.ndarray()

# fix dimension order (from (B,H,W,C) rgb to (B,H,W,C) bgr)
# TODO add format image check
images_bgr = narr[..., ::-1]
n_frames, h_img, w_img = images_bgr.shape[:3]

# array need to be saved in a contiguous memory block
images_bgr = np.ascontiguousarray(images_bgr).astype(np.uint8)

# -- 2.2 format inputs
# - 2.2.1 text inputs
text_prompt = textPrompt

# - 2.2.1 visual inputs
positive_rois = positiveRois
negative_rois = negativeRois

pos_box_xy1xy2_norm_list = []
neg_box_xy1xy2_norm_list = []
pos_point_xy_norm_list =  []
neg_point_xy_norm_list = []

# positive_rois received format: [[x,y,w,h], [x,y], ...] (absolute values)
# expected format:
#  one list for boxes : [[(x1, y1), (x2, y2)], ...] (normalized values)
#  one list for points : [(x, y), ... ] (normalized values)
if len(positive_rois) > 0:
    for prompt in positive_rois :
        if len(prompt) == 4: # box
            pos_box_xy1xy2_norm_list.append(xywh_to_norm_x1y1x2y2(prompt, h_img, w_img))
        elif len(prompt) == 2: #point
            pos_point_xy_norm_list.append(norm_xy(prompt,h_img, w_img))
        else :
            log_to_java(f"unknown prompt size: prompt {prompt}")


# negative_rois received format: [[x,y,w,h], [x,y], ...] (absolute values)
# expected format:
#  one list for boxes : [[(x1, y1), (x2, y2)], ...] (normalized values)
#  one list for points : [(x, y), ... ] (normalized values)
if len(negative_rois) > 0:
    for prompt in negative_rois :
        if len(prompt) == 4: # box
            neg_box_xy1xy2_norm_list.append(xywh_to_norm_x1y1x2y2(prompt, h_img, w_img))
        elif len(prompt) == 2: #point
            neg_point_xy_norm_list.append(norm_xy(prompt,h_img, w_img))
        else :
            log_to_java(f"unknown prompt size: prompt {prompt}")

# -- 2.3 Parameters for detection/tracking (received directly from java)
detect_every_n_frames = detectEveryNFrames  # Set to None to only run once on startup
detection_score_threshold = detectionScoreThreshold # proba min pour ajouter nouvelle detection
tracking_score_threshold = trackingScoreThreshold # score de présence min pour garder un objet déjà suivi
existing_box_iou_threshold = trackingBoxIouThreshold
remove_after_n_missed_frames = removeAfterNMissed
mask_threshold = maskThreshold
# taille max du coté le plus long pour detection et tracking
# ensuite divisé par facteur = 3,5 pour obtenir taille final coté masque
max_side_length_detect = maxSideLengthDetect
max_side_length_track = maxSideLengthTrack  # Reduce this to increase speed at the cost of mask quality

# Bundle re-used data together for ease of use
imgenc_config_dict_track = {"max_side_length": max_side_length_track, "use_square_sizing": True}
imgenc_config_dict_detect = {"max_side_length": max_side_length_detect, "use_square_sizing": True}
detection_prompts_dict = {
    "text": text_prompt,
    "box_xy1xy2_norm_list": pos_box_xy1xy2_norm_list,
    "point_xy_norm_list": pos_point_xy_norm_list,
    "negative_boxes_list": neg_box_xy1xy2_norm_list,
    "negative_points_list": neg_point_xy_norm_list,
}

needs_detect_reencode = is_using_separate_tracking_model or imgenc_config_dict_track != imgenc_config_dict_detect

# -- 2.4 Memory
# Set up storage for tracking memory and keeping track of lost objects
# -> Assumes each object is represented by a unique dictionary key (e.g. 'obj1')
# memory_per_obj_dict est un dict qui associe à chaque objet une memory bank
# -> This holds both the 'prompt' & 'frame' memory data needed for tracking!
# forward
memory_per_obj_dict_forward = defaultdict(SAMVideoMemoryBank) # defined with max_frame_memory=6 and max_prompt_memory=32
missed_frames_per_obj_dict_forward = defaultdict(int)
# backward
memory_per_obj_dict_backward = defaultdict(SAMVideoMemoryBank) # defined with max_frame_memory=6 and max_prompt_memory=32
missed_frames_per_obj_dict_backward = defaultdict(int)

## -- 2.5 fram index and bidirectionality
n_frame_to_process = nFrameToProcess
bidirectional = biDirectional
first_frame_index = 0
prompt_frame_index = promptFrame
last_frame_index = lastFrame
frame_offset= frameOffset

#forward
forward_id_list = range(prompt_frame_index+1, last_frame_index+1)
id_and_memory_forward = {
    "frame_id_list": forward_id_list,
    "memory_per_obj": memory_per_obj_dict_forward,
    "missed_frames_per_obj": missed_frames_per_obj_dict_forward,
}

# backward (if bidirectional
if bidirectional:
    backward_id_list = range(prompt_frame_index-1, first_frame_index-1, -1)
    id_and_memory_backward = {
        "frame_id_list": backward_id_list,
        "memory_per_obj": memory_per_obj_dict_backward,
        "missed_frames_per_obj": missed_frames_per_obj_dict_backward,
    }
    # bundle data together
    ids_and_memories_dicts = [id_and_memory_forward, id_and_memory_backward]
else :
    ids_and_memories_dicts = [id_and_memory_forward]

# store results in arrays
total_detection =0 # number of detections across all frames
all_masks = []
all_boxes = []
all_scores = []
all_ids = [] #object ids
index = 0 # index to get results in "all_X" lists
all_ids_used = [] # list of all used id

#
# 3. make predictions
log_to_java("running prediction...")

# 3.1 make prediction for prompt image


# Encode image data for tracking (this is the heaviest part of video inference)
frame_idx = prompt_frame_index
frame = images_bgr[frame_idx]
#log_to_java(f"   Frame: {frame_idx + 1}")
encoded_img = track_model.encode_image(frame, **imgenc_config_dict_track)

masks_uint8_on_frame = []
box_xywh_norm_on_frame = []
scores_on_frame = []
ids_on_frame = []
objs_to_remove_list = []

#log_to_java(f"Performing detection update! (frame {frame_idx})")
det_encimg = encoded_img
if needs_detect_reencode:
    det_encimg = detect_model.encode_image(frame, **imgenc_config_dict_detect)
det_exemplars = detect_model.encode_exemplars(det_encimg, **detection_prompts_dict)
det_masks, det_boxes, det_scores, pres_scores = detect_model.generate_detections(
    det_encimg, det_exemplars, detection_filter_threshold=detection_score_threshold
)

num_detections = det_masks.shape[1]
if num_detections > 0:
    # Initialize new detections using the corresponding mask predictions
    next_new_idx =  0
    new_det_idxs_list = range(num_detections)
    if num_detections > 0:
        log_to_java(f"     {num_detections} object(s) detected on prompt frame")
    for idx_offset, det_idx in enumerate(new_det_idxs_list):
        raw_det_mask = det_masks[:, [det_idx]]
        init_mem = track_model.encode_prompt_memory_from_mask(encoded_img, raw_det_mask)
        new_idx = next_new_idx + idx_offset
        all_ids_used.append(new_idx)
        memory_per_obj_dict_forward[new_idx].store_prompt_result(init_mem)
        memory_per_obj_dict_backward[new_idx].store_prompt_result(init_mem)

        # save results for this object
        # mask
        mask_uint8 = (raw_det_mask > mask_threshold).byte().cpu().numpy().squeeze()
        masks_uint8_on_frame.append(mask_uint8)
        # score
        scores_on_frame.append(det_scores[0,idx_offset])
        # id
        ids_on_frame.append(new_idx)
        # box
        raw_det_box = det_boxes[:, [det_idx]]
        x1,y1 = raw_det_box[0,0,0]
        x2, y2 = raw_det_box[0,0,1]
        box_xywh_norm = [x1, y1, x2-x1, y2-y1]
        box_xywh_norm_on_frame.append(box_xywh_norm)

    pass
n_obj = len(masks_uint8_on_frame)
total_detection += n_obj

if n_obj>0:
    # 5. process and store results for the frame
    # Boxes
    boxes_np = np.array([ [float(v) for v in box] for box in box_xywh_norm_on_frame], dtype='float64')
    shared_boxes = appose.NDArray("float64", boxes_np.shape)
    shared_boxes.ndarray()[:] = boxes_np
    all_boxes.append(shared_boxes)

    # Masks
    masks_unit8_resized = []
    for i, mask in enumerate(masks_uint8_on_frame):
        resized_mask = cv2.resize(mask, (w_img, h_img), interpolation=cv2.INTER_NEAREST)
        masks_unit8_resized.append(resized_mask)
    masks_np = np.array(masks_unit8_resized, dtype='uint8')
    shared_masks = appose.NDArray("uint8", masks_np.shape)
    shared_masks.ndarray()[:] = masks_np
    all_masks.append(shared_masks)

    # Scores
    scores_np = np.array([float(s) for s in scores_on_frame], dtype='float64')
    shared_scores = appose.NDArray("float64", scores_np.shape)
    shared_scores.ndarray()[:] = scores_np
    all_scores.append(shared_scores)

    # IDs
    ids_np = np.array(ids_on_frame, dtype='int32')
    shared_ids = appose.NDArray("int32", ids_np.shape)
    shared_ids.ndarray()[:] = ids_np
    all_ids.append(shared_ids)

    # 6. send results to java via task update
    task.update(
        message=f"    frame {frame_idx + frame_offset +1} - number of object detected/tracked: {n_obj}",
        current=index+1,
        maximum=n_frame_to_process,
        info={
            "frame_idx": frame_idx+frame_offset,
            "n_results": n_obj,
            "object_ids": all_ids[index],
            "scores" : all_scores[index],
            "boxes": all_boxes[index],
            "masks": all_masks[index]
        }
    )
else :
    all_masks.append([])
    all_boxes.append([])
    all_scores.append([])
    all_ids.append([])

    task.update(
        message=f"   frame {frame_idx+frame_offset+1} - no object detected/tracked",
        current=frame_idx+1,
        maximum=n_frame_to_process,
        info={
            "frame_idx": frame_idx+frame_offset,
            "n_results": 0
        }
    )

index +=1
# 3.2 Process video frames : forward

detect_every_n_frames = 2**31 if (detect_every_n_frames is None or detect_every_n_frames <= 0) else detect_every_n_frames
remove_after_n_missed_frames = 2**31 if (remove_after_n_missed_frames is None or remove_after_n_missed_frames <= 0) else remove_after_n_missed_frames
for dict in ids_and_memories_dicts:
    frame_id_list =dict["frame_id_list"]
    memory_dict = dict["memory_per_obj"]
    missed_frames_dict = dict["missed_frames_per_obj"]
    for frame_idx in frame_id_list:
        frame = images_bgr[frame_idx]
        #log_to_java(f"   Frame: {frame_idx + 1}")


        # Encode image data for tracking (this is the heaviest part of video inference)
        encoded_img = track_model.encode_image(frame, **imgenc_config_dict_track)

        masks_uint8_on_frame = []
        box_xywh_norm_on_frame = []
        scores_on_frame = []
        ids_on_frame = []
        objs_to_remove_list = []

        # 1. Advance video tracking for all known objects
        for idx_obj, obj_memory in memory_dict.items():
            mask_pred, iou_pred, obj_ptr, obj_score = track_model.step_video_masking(
                encoded_img, **obj_memory.to_dict()
            ) # prediction pour la nouvelle frame pour chaque objet connu

            # Skip storage for bad results
            if obj_score[0] < tracking_score_threshold:
                missed_frames_dict[idx_obj] += 1
                if missed_frames_dict[idx_obj] > remove_after_n_missed_frames:
                    objs_to_remove_list.append(idx_obj) # si objet absent trop de frames d'affilé, on le supprime
                continue
            missed_frames_dict[idx_obj] = 0

            # Store memory encodings for continued tracking and 'best' mask for display
            encoded_mem = track_model.encode_frame_memory(encoded_img, mask_pred, obj_ptr, obj_score)
            obj_memory.store_frame_result(encoded_mem)

            # save results for this object
            # mask
            mask_uint8 = (mask_pred > mask_threshold).byte().cpu().numpy().squeeze()
            masks_uint8_on_frame.append(mask_uint8)
            # score
            scores_on_frame.append(obj_score[0])
            # id
            ids_on_frame.append(idx_obj)
            # find bounding box
            contours_list, _ = cv2.findContours(mask_uint8, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            if len(contours_list) == 0:
                continue
            contour = max(contours_list, key=cv2.contourArea) if len(contours_list) > 1 else contours_list[0]
            box_x, box_y, box_w, box_h = cv2.boundingRect(contour)
            mask_h, mask_w = mask_uint8.shape
            box_xywh_norm = [box_x/mask_w, box_y/mask_h, box_w/mask_w, box_h/mask_h]
            box_xywh_norm_on_frame.append(box_xywh_norm)

        # 2. Run detections to pick up new objects
        no_tracked_objects = len(memory_dict) == 0
        # on fait détection uniquement toutes les x frames, ou si on n'a aucune détection
        need_detection = ((frame_idx - prompt_frame_index) % detect_every_n_frames) == 0 or no_tracked_objects
        if need_detection:
            #log_to_java(f"Performing detection update! (frame {frame_idx+1})")
            det_encimg = encoded_img
            if needs_detect_reencode:
                det_encimg = detect_model.encode_image(frame, **imgenc_config_dict_detect)
            det_exemplars = detect_model.encode_exemplars(det_encimg, **detection_prompts_dict)
            det_masks, det_boxes, det_scores, pres_scores = detect_model.generate_detections(
                det_encimg, det_exemplars, detection_filter_threshold=detection_score_threshold
            )
            # If we get new detections, compare to existing objects to see if anything new has appeared
            num_detections = det_masks.shape[1]
            if num_detections > 0:
                # Get bounding boxes of existing objects on frame
                known_boxes_list = []
                for box_xywh in box_xywh_norm_on_frame:
                    box_x, box_y, box_w, box_h = box_xywh
                    box_xy1xy2_norm = torch.tensor(((box_x, box_y), (box_x + box_w, box_y + box_h)))
                    known_boxes_list.append(box_xy1xy2_norm.to(det_boxes))

                # Any detection that doesn't overlap an existing object is assumed to be new
                is_new_obj_list = []
                for idx_det in range(num_detections):
                    new_box = det_boxes[0, idx_det]
                    is_known = any(get_2box_iou(new_box, b) > existing_box_iou_threshold for b in known_boxes_list)
                    is_new_obj_list.append(not is_known)

                # Initialize new detections using the corresponding mask predictions
                next_new_idx = max(all_ids_used) + 1 if len(all_ids_used) > 0 else 0
                new_det_idxs_list = [det_idx for det_idx, is_new in enumerate(is_new_obj_list) if is_new]
                num_new_objs = len(new_det_idxs_list)
                if num_new_objs > 0:
                    log_to_java(f"     {num_new_objs} new object(s) detected")
                for idx_offset, det_idx in enumerate(new_det_idxs_list):
                    raw_det_mask = det_masks[:, [det_idx]]
                    init_mem = track_model.encode_prompt_memory_from_mask(encoded_img, raw_det_mask)
                    new_idx = next_new_idx + idx_offset
                    all_ids_used.append(new_idx)
                    memory_dict[new_idx].store_prompt_result(init_mem)

                    # save results for this object
                    # mask
                    mask_uint8 = (raw_det_mask > mask_threshold).byte().cpu().numpy().squeeze()
                    masks_uint8_on_frame.append(mask_uint8)
                    # score
                    scores_on_frame.append(det_scores[0,idx_offset])
                    # id
                    ids_on_frame.append(new_idx)
                    # box
                    raw_det_box = det_boxes[:, [det_idx]]
                    x1,y1 = raw_det_box[0,0,0]
                    x2, y2 = raw_det_box[0,0,1]
                    box_xywh_norm = [x1, y1, x2-x1, y2-y1]
                    box_xywh_norm_on_frame.append(box_xywh_norm)

                pass

        # 3. Stop tracking objects that were marked for removal
        for idx_obj in objs_to_remove_list:
            memory_dict.pop(idx_obj)
            missed_frames_dict.pop(idx_obj)
        if len(objs_to_remove_list)>0 :
            log_to_java(f"     {len(objs_to_remove_list)} object(s) removed")

        n_obj = len(masks_uint8_on_frame)
        total_detection += n_obj

        if n_obj>0:
            # 5. process and store results for the frame
            # Boxes
            boxes_np = np.array([ [float(v) for v in box] for box in box_xywh_norm_on_frame], dtype='float64')
            shared_boxes = appose.NDArray("float64", boxes_np.shape)
            shared_boxes.ndarray()[:] = boxes_np
            all_boxes.append(shared_boxes)

            # Masks
            masks_unit8_resized = []
            for i, mask in enumerate(masks_uint8_on_frame):
                resized_mask = cv2.resize(mask, (w_img, h_img), interpolation=cv2.INTER_NEAREST)
                masks_unit8_resized.append(resized_mask)
            masks_np = np.array(masks_unit8_resized, dtype='uint8')
            shared_masks = appose.NDArray("uint8", masks_np.shape)
            shared_masks.ndarray()[:] = masks_np
            all_masks.append(shared_masks)

            # Scores
            scores_np = np.array([float(s) for s in scores_on_frame], dtype='float64')
            shared_scores = appose.NDArray("float64", scores_np.shape)
            shared_scores.ndarray()[:] = scores_np
            all_scores.append(shared_scores)

            # IDs
            ids_np = np.array(ids_on_frame, dtype='int32')
            shared_ids = appose.NDArray("int32", ids_np.shape)
            shared_ids.ndarray()[:] = ids_np
            all_ids.append(shared_ids)

            # 6. send results to java via task update
            task.update(
                message=f"    frame {frame_idx + frame_offset +1} - number of object detected/tracked: {n_obj}",
                current=index+1,
                maximum=n_frame_to_process,
                info={
                    "frame_idx": frame_idx+frame_offset,
                    "n_results": n_obj,
                    "object_ids": all_ids[index],
                    "scores" : all_scores[index],
                    "boxes": all_boxes[index],
                    "masks": all_masks[index]
                }
            )
        else :
            all_masks.append([])
            all_boxes.append([])
            all_scores.append([])
            all_ids.append([])

            task.update(
                message=f"   frame {frame_idx+frame_offset+1} - no object detected/tracked",
                current=index+1,
                maximum=n_frame_to_process,
                info={
                    "frame_idx": frame_idx+frame_offset,
                    "n_results": 0
                }
            )
        index+=1

log_to_java("python segmentation complete")