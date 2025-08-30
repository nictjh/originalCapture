from __future__ import annotations
from typing import Dict, Any, Optional, List
from fastapi import FastAPI
from pydantic import BaseModel
from joblib import load
import numpy as np

# Handle Pydantic version compatibility
try:
    # Pydantic v2 style
    from pydantic import RootModel
    class EditLog(RootModel[Dict[str, Any]]):
        pass
    def _get_root(payload: EditLog) -> Dict[str, Any]:
        return payload.root
except Exception:
    # Fallback for Pydantic v1
    from pydantic import BaseModel
    class EditLog(BaseModel):
        __root__: Dict[str, Any]
    def _get_root(payload: EditLog) -> Dict[str, Any]:
        return payload.__root__

from features import feature_vector, default_feature_order, featurize

app = FastAPI(title="Misleading Edit Judge", version="0.2")

# ----- Load model (with safe fallback to rules-only) -----
MODEL = None
FEAT_ORDER: List[str] = default_feature_order()
try:
    art = load("model.joblib")  # expects {"model": clf, "features": feat_order}
    MODEL = art.get("model", None)
    # Prefer the saved order if present
    if isinstance(art.get("features"), list):
        FEAT_ORDER = art["features"]
except Exception:
    # No model.joblib? We'll run rules-only.
    MODEL = None


# ----- Simple interpretable rules (image + video aware) -----
def _rule_score_and_reasons(log: Dict[str, Any]) -> tuple[int, List[str]]:
    """
    Returns:
      points (int) ∈ [0, ~8], higher = riskier
      reasons (list[str]) human-friendly flags for UI
    """
    f = featurize(log)
    pts = 0
    reasons: List[str] = []

    # Compose = splice/inpaint/bg_replace/face_swap
    if f["n_compose"] > 0:
        pts += 3
        reasons.append("Has composition edits (splice/inpaint/bg replace)")

    if f["total_compose_area_pct"] >= 10 or f["max_compose_area_pct"] >= 15:
        pts += 2
        reasons.append("Large composed region")

    # Video-only: long-lasting composition over the timeline
    if f["total_compose_time_pct"] >= 25:
        pts += 2
        reasons.append("Composition affects large portion of duration")

    # Heavy crop keeps only a small fraction of scene
    if f["total_crop_area_pct"] > 60:
        pts += 1
        reasons.append("Aggressive cropping")

    # Very strong global adjustments
    if f["total_adjust_mag"] > 120:
        pts += 1
        reasons.append("Extreme global adjustments")

    # Privacy-aware reduction if edits look protective and not manipulative
    if f["n_compose"] == 0 and f["total_blur_area_pct"] > 0:
        pts -= 1
        reasons.append("Privacy blur detected (no composition)")

    # Signed provenance tempers risk
    if f["c2pa_present"] and f["signed"]:
        pts -= 1
        reasons.append("Signed provenance present")

    # Keep within sane bounds
    pts = max(0, pts)
    return pts, reasons


def _blend_prob_with_rules(prob: Optional[float], rule_pts: int) -> float:
    """
    Combine ML probability (if available) with interpretable rule points.
    Rule points convert to ~0.0..1.0 via a small scale; ML dominates if present.
    """
    rules_as_prob = min(1.0, 0.12 * rule_pts)  # 0,1,2,... → 0.00,0.12,0.24,...
    if prob is None:
        return rules_as_prob
    return min(1.0, 0.8 * prob + 0.2 * rules_as_prob)


def _label_from_score(score: float) -> str:
    if score >= 0.7:
        return "misleading"
    if score >= 0.4:
        return "risky"
    return "benign"


# ----- Routes -----
@app.get("/health")
def health():
    return {"ok": True, "model_loaded": MODEL is not None, "feature_count": len(FEAT_ORDER)}


@app.post("/judge")
def judge(payload: EditLog):
    log = _get_root(payload)

    # Vectorize
    x = feature_vector(log, FEAT_ORDER).reshape(1, -1)

    # ML prob (if model exists)
    ml_prob: Optional[float] = None
    if MODEL is not None:
        try:
            ml_prob = float(MODEL.predict_proba(x)[0, 1])
        except Exception:
            ml_prob = None

    # Interpretable rules
    rule_pts, reasons = _rule_score_and_reasons(log)

    # Blend & label
    risk_score = _blend_prob_with_rules(ml_prob, rule_pts)
    label = _label_from_score(risk_score)

    return {
        "label": label,
        "risk_score": round(risk_score, 3),
        "model_prob_misleading": (None if ml_prob is None else round(ml_prob, 3)),
        "rule_points": rule_pts,
        "reasons": reasons,
        "features_used": FEAT_ORDER,  # helpful for debugging
    }