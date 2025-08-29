# features.py
"""
Edit-log → numeric features for 'misleading edit' classification.
Supports both IMAGES and VIDEOS using the same 6 buckets:
  transform, adjust, filter, overlay, privacy_blur, compose

Video-specific signals are OPTIONAL per op:
  - area_pct_avg : average % frame area affected over the op's active span
  - time_pct     : % of total clip duration the op affects (0..100)

If these are absent, the code falls back to image-style fields:
  - area_pct     : % area affected (single value)
"""

from __future__ import annotations
from typing import Dict, Any, List, Optional
import numpy as np

# Stable buckets used by the ML features
BUCKETS = ["transform", "adjust", "filter", "overlay", "privacy_blur", "compose"]


# --------- helpers ---------

def _get(d: Dict[str, Any], *path: str, default=None):
    """Safe nested getter: _get(log, 'provenance','signed', default=False)"""
    cur: Any = d
    for k in path:
        if not isinstance(cur, dict) or k not in cur:
            return default
        cur = cur[k]
    return cur


def _ops(log: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Always return a list; tolerate missing/invalid logs."""
    ops = log.get("ops", [])
    return ops if isinstance(ops, list) else []


def _media_dims(log: Dict[str, Any]) -> Optional[tuple[int, int]]:
    """
    Find width/height from either 'meta' or 'media'.
    Returns None if unavailable so area features gracefully become 0.
    """
    w = _get(log, "meta", "width")
    h = _get(log, "meta", "height")
    if not (isinstance(w, int) and isinstance(h, int) and w > 0 and h > 0):
        w = _get(log, "media", "width")
        h = _get(log, "media", "height")
    if isinstance(w, int) and isinstance(h, int) and w > 0 and h > 0:
        return w, h
    return None


def _clamp01(x: float) -> float:
    """Clamp a percentage value 0..100 (robust to bad inputs)."""
    try:
        v = float(x)
    except Exception:
        return 0.0
    if v < 0:
        return 0.0
    if v > 100:
        return 100.0
    return v


# --------- core featurization ---------

def featurize(log: Dict[str, Any]) -> Dict[str, float]:
    """
    Turn an edit log (image or video) into named numeric features.
    Missing fields default to 0; function is side-effect free.
    """

    feats: Dict[str, float] = {}

    # 1) Counts per bucket (how many ops of each kind)
    counts = {b: 0 for b in BUCKETS}
    for op in _ops(log):
        t = op.get("t")
        if t in counts:
            counts[t] += 1
    feats.update({f"n_{b}": float(v) for b, v in counts.items()})

    # 2) Provenance / export hints (C2PA-ish + encoder quality)
    feats["c2pa_present"] = 1.0 if _get(log, "provenance", "c2pa_present", default=False) else 0.0
    feats["signed"] = 1.0 if _get(log, "provenance", "signed", default=False) else 0.0
    feats["actions_count"] = float(_get(log, "provenance", "actions_count", default=0) or 0)
    # Keep name 'jpeg_quality' for backward compatibility; use export.quality for images/videos
    feats["jpeg_quality"] = float(_get(log, "export", "quality", default=90) or 90)

    # 3) Magnitudes / coverage (image + video)
    total_crop_area_pct = 0.0            # image-style crop accumulation
    total_adjust_mag = 0.0               # sum of |brightness|+|contrast|+|saturation|
    total_blur_area_pct = 0.0            # % area blurred (sum)
    total_compose_area_pct = 0.0         # % area composed (sum)

    # NEW (video-aware): optional time coverage + peak compose area
    total_blur_time_pct = 0.0            # sum of time_pct for privacy_blur
    total_compose_time_pct = 0.0         # sum of time_pct for compose
    max_compose_area_pct = 0.0           # max area_pct(_avg) among compose ops

    dims = _media_dims(log)
    img_area = None if not dims else dims[0] * dims[1]

    for op in _ops(log):
        t = op.get("t")
        p = op.get("p", {}) if isinstance(op.get("p"), dict) else {}

        # Prefer video-averaged area if present; else fall back to image-style area
        area = p.get("area_pct_avg", p.get("area_pct", 0.0))
        try:
            area = float(area) if area is not None else 0.0
        except Exception:
            area = 0.0

        # Optional time coverage for videos (% of clip duration this op affects)
        timep = _clamp01(p.get("time_pct", 0.0))

        if t == "transform":
            # If a crop rect [x,y,w,h] exists, add its area% of original image
            crop = p.get("crop")
            if isinstance(crop, (list, tuple)) and len(crop) == 4 and img_area:
                _, _, cw, ch = crop
                if isinstance(cw, (int, float)) and isinstance(ch, (int, float)) and cw > 0 and ch > 0:
                    total_crop_area_pct += 100.0 * (float(cw) * float(ch)) / float(img_area)

        if t == "adjust":
            # Sum absolute deltas for a simple intensity proxy
            b = abs(float(p.get("brightness", 0) or 0))
            c = abs(float(p.get("contrast", 0) or 0))
            s = abs(float(p.get("saturation", 0) or 0))
            total_adjust_mag += (b + c + s)

        if t == "privacy_blur":
            total_blur_area_pct += area
            total_blur_time_pct += timep

        if t == "compose":
            total_compose_area_pct += area
            total_compose_time_pct += timep
            if area > max_compose_area_pct:
                max_compose_area_pct = area

    feats["total_crop_area_pct"] = float(total_crop_area_pct)
    feats["total_adjust_mag"] = float(total_adjust_mag)
    feats["total_blur_area_pct"] = float(total_blur_area_pct)
    feats["total_compose_area_pct"] = float(total_compose_area_pct)

    # NEW (video-aware) features:
    feats["total_blur_time_pct"] = float(total_blur_time_pct)
    feats["total_compose_time_pct"] = float(total_compose_time_pct)
    feats["max_compose_area_pct"] = float(max_compose_area_pct)

    # 4) Simple interaction: overlay placed immediately after a compose?
    feats["overlay_after_compose"] = 0.0
    last_t = None
    for op in _ops(log):
        t = op.get("t")
        if last_t == "compose" and t == "overlay":
            feats["overlay_after_compose"] = 1.0
        last_t = t

    return feats


def default_feature_order() -> List[str]:
    """
    Exact order used in training/serving.  The 3 new video-aware features
    are appended at the end to preserve backward compatibility of the
    earlier indices.  After updating this list you MUST retrain.
    """
    return [
        # counts
        "n_transform", "n_adjust", "n_filter", "n_overlay", "n_privacy_blur", "n_compose",
        # provenance/export
        "c2pa_present", "signed", "actions_count", "jpeg_quality",
        # magnitudes (image + shared)
        "total_crop_area_pct", "total_adjust_mag", "total_blur_area_pct", "total_compose_area_pct",
        # interaction
        "overlay_after_compose",
        # --- appended video-aware features ---
        "total_blur_time_pct", "total_compose_time_pct", "max_compose_area_pct",
    ]


def feature_vector(log: Dict[str, Any], keys: List[str]) -> np.ndarray:
    """Pack named features into a numpy vector in `keys` order (missing → 0.0)."""
    fdict = featurize(log)
    return np.array([float(fdict.get(k, 0.0)) for k in keys], dtype=float)


#log JSON → featurize() (dict) → feature_vector(..., default_feature_order()) (NumPy array) → fed to your model.