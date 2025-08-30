# train.py - Context-aware training with gray zones + calibrated probabilities + clean model selection
from __future__ import annotations
import random, json
from typing import Dict, Any, List, Tuple
import numpy as np

from sklearn.linear_model import LogisticRegression
from sklearn.ensemble import RandomForestClassifier, GradientBoostingClassifier
from sklearn.preprocessing import StandardScaler
from sklearn.model_selection import train_test_split, cross_val_score
from sklearn.metrics import classification_report, f1_score, precision_recall_curve
from sklearn.pipeline import Pipeline
from sklearn.calibration import CalibratedClassifierCV
from joblib import dump

from features import default_feature_order, feature_vector, featurize

random.seed(42)
np.random.seed(42)

BUCKETS = ["transform", "adjust", "filter", "overlay", "privacy_blur", "compose"]

# ---------------------------
# DATA GENERATION (GRAY ZONES)
# ---------------------------

def _mk_misleading_image() -> Dict[str, Any]:
    """
    Generate intentionally misleading image edits.
    IMPORTANT CHANGE:
      - Not always using 'compose'. Some misleading examples come from severe crop/trim
        that destroys context, so the model cannot rely on 'n_compose' alone.
    """
    w, h = random.choice([(1080, 1440), (1920, 1080), (720, 720)])
    ops = []

    # 70%: high-risk composition; 30%: compose-free but context-destroying
    if random.random() < 0.70:
        compose_type = random.choice(
            ["splice", "inpaint", "bg_replace", "face_swap", "object_replace", "deepfake"]
        )
        area = random.uniform(20, 75)  # larger areas = more misleading
        ops.append({"t": "compose", "p": {"type": compose_type, "area_pct": area}})
    else:
        # Compose-free misleading via aggressive crop (keep very small portion)
        keep_ratio = random.uniform(0.08, 0.30)
        cw, ch = int(w * keep_ratio), int(h * keep_ratio)
        ops.append({"t": "transform", "p": {"crop": [0, 0, cw, ch]}})
        # Optionally add a second context-destroying transform
        if random.random() < 0.4:
            ops.append({"t": "transform", "p": {"time_trim_pct": random.uniform(40, 85)}})

    # Aggressive adjustments to hide traces (still probabilistic)
    if random.random() < 0.8:
        ops.append({"t": "adjust", "p": {
            "brightness": random.randint(-60, 60),
            "contrast": random.randint(-50, 50),
            "saturation": random.randint(-40, 40)
        }})

    # Sometimes add filters to mask manipulation
    if random.random() < 0.35:
        ops.append({"t": "filter", "p": {"lut": random.choice(["vintage", "cinematic", "noir"])}})

    # Very rarely add privacy blur in misleading cases (to avoid shortcut)
    if random.random() < 0.05:
        ops.append({"t": "privacy_blur", "p": {"method": "gaussian", "area_pct": random.uniform(1, 8)}})

    return {
        "asset_id": f"misleading-img-{random.randint(1000,9999)}",
        "created_at": "2025-08-29T09:12:33Z",
        "meta": {"width": w, "height": h},
        "provenance": {
            # Overlap provenance so it's NOT a shortcut:
            "c2pa_present": random.random() < 0.20,   # sometimes present
            "signed": random.random() < 0.15,         # sometimes signed
            "actions_count": len(ops)
        },
        "ops": ops,
        "export": {"fmt": "jpg", "quality": random.choice([60, 70, 75, 80, 85])}
    }

def _mk_benign_image() -> Dict[str, Any]:
    """
    Generate clearly/mostly benign image edits.
    IMPORTANT CHANGE:
      - Sometimes includes harmless 'compose' such as montage/merge with small area,
        so 'compose' ≠ always misleading.
    """
    w, h = random.choice([(1080, 1440), (1920, 1080), (720, 720)])
    ops = []

    num_ops = random.randint(1, 4)
    available_ops = ["transform", "adjust", "filter", "privacy_blur", "overlay"]

    for _ in range(num_ops):
        t = random.choice(available_ops)
        if t == "transform":
            if random.random() < 0.65:
                # Gentle crop
                keep_ratio = random.uniform(0.65, 0.95)
                cw, ch = int(w * keep_ratio), int(h * keep_ratio)
                ops.append({"t": t, "p": {"crop": [10, 10, cw, ch]}})
            else:
                ops.append({"t": t, "p": {"rotate": random.choice([0, 90, 180, 270])}})
        elif t == "adjust":
            ops.append({"t": t, "p": {
                "brightness": random.randint(-20, 20),
                "contrast": random.randint(-15, 15),
                "saturation": random.randint(-15, 15)
            }})
        elif t == "filter":
            ops.append({"t": t, "p": {"lut": random.choice(["bw","warm","cool","natural"])}})
        elif t == "privacy_blur":
            ops.append({"t": t, "p": {"method": "gaussian", "area_pct": random.uniform(1, 18)}})
        elif t == "overlay":
            ops.append({"t": t, "p": {"text": random.choice([True, False])}})

    # 15% benign examples include harmless compose (montage/merge) with small area
    if random.random() < 0.15:
        ops.append({"t": "compose", "p": {"type": "merge", "area_pct": random.uniform(4, 18)}})

    return {
        "asset_id": f"benign-img-{random.randint(1000,9999)}",
        "created_at": "2025-08-29T09:12:33Z",
        "meta": {"width": w, "height": h},
        "provenance": {
            "c2pa_present": random.random() < 0.60,  # often present
            "signed": random.random() < 0.45,        # sometimes signed
            "actions_count": len(ops)
        },
        "ops": ops,
        "export": {"fmt": "jpg", "quality": random.choice([80, 85, 90, 95])}
    }

def _mk_misleading_video() -> Dict[str, Any]:
    """
    Generate misleading video edits.
    IMPORTANT CHANGE:
      - Not always compose. Allow compose-free but misleading via heavy trim/speed/context removal.
    """
    w, h, fps, dur = 1920, 1080, 30, random.uniform(10, 120)
    ops = []

    if random.random() < 0.70:
        # High-impact composition
        compose_type = random.choice(["bg_replace", "face_swap", "inpaint", "deepfake", "object_replace"])
        area = random.uniform(25, 80)
        time_pct = random.uniform(25, 90)
        ops.append({"t": "compose", "p": {"type": compose_type, "area_pct_avg": area, "time_pct": time_pct}})
    else:
        # Compose-free misleading via huge trim/speed/time removal
        if random.random() < 0.6:
            ops.append({"t": "transform", "p": {"time_trim_pct": random.uniform(45, 85)}})
        if random.random() < 0.6:
            ops.append({"t": "transform", "p": {"speed": random.choice([0.25, 0.5, 3.0, 4.0])}})

    if random.random() < 0.9:
        ops.append({"t": "adjust", "p": {
            "brightness": random.randint(-40, 40),
            "contrast": random.randint(-35, 35),
            "saturation": random.randint(-25, 25)
        }})
    if random.random() < 0.3:
        ops.append({"t": "overlay", "p": {"captions": True}})

    # Rare privacy blur in misleading to prevent shortcut learning
    if random.random() < 0.05:
        ops.append({"t": "privacy_blur", "p": {"method": "gaussian", "area_pct_avg": random.uniform(1, 10), "time_pct": random.uniform(5, 50)}})

    return {
        "asset_id": f"misleading-vid-{random.randint(1000,9999)}",
        "created_at": "2025-08-29T09:12:33Z",
        "media": {"type": "video", "width": w, "height": h, "fps": fps, "duration_sec": dur},
        "provenance": {
            "c2pa_present": random.random() < 0.25,
            "signed": random.random() < 0.15,
            "actions_count": len(ops)
        },
        "ops": ops,
        "export": {"fmt": "mp4", "codec": "h264", "quality": random.choice([55, 60, 70, 80, 85])}
    }

def _mk_benign_video() -> Dict[str, Any]:
    """
    Generate benign video edits.
    IMPORTANT CHANGE:
      - Occasionally includes harmless compose (merge picture-in-picture) with small coverage.
    """
    w, h, fps, dur = 1920, 1080, 30, random.uniform(5, 60)
    ops = []

    num_ops = random.randint(1, 5)
    for _ in range(num_ops):
        t = random.choices(["transform", "adjust", "filter", "overlay", "privacy_blur"], weights=[3, 4, 2, 2, 2])[0]
        if t == "transform":
            if random.random() < 0.5:
                ops.append({"t": t, "p": {"speed": random.choice([0.75, 1.25, 1.5, 2.0])}})
            else:
                start_pct = random.uniform(0, 25)  # mild trim
                ops.append({"t": t, "p": {"time_pct": start_pct}})
        elif t == "adjust":
            ops.append({"t": t, "p": {
                "brightness": random.randint(-25, 25),
                "contrast": random.randint(-20, 20),
                "saturation": random.randint(-15, 15)
            }})
        elif t == "filter":
            ops.append({"t": t, "p": {"lut": random.choice(["cinematic", "warm", "cool", "natural"])}})
        elif t == "overlay":
            ops.append({"t": t, "p": {"captions": True}})
        elif t == "privacy_blur":
            ops.append({"t": t, "p": {"method": "gaussian", "area_pct_avg": random.uniform(1, 15), "time_pct": random.uniform(5, 60)}})

    # 12% benign with harmless compose (PiP/merge), small area/time
    if random.random() < 0.12:
        ops.append({"t": "compose", "p": {"type": "merge", "area_pct_avg": random.uniform(3, 15), "time_pct": random.uniform(5, 30)}})

    return {
        "asset_id": f"benign-vid-{random.randint(1000,9999)}",
        "created_at": "2025-08-29T09:12:33Z",
        "media": {"type": "video", "width": w, "height": h, "fps": fps, "duration_sec": dur},
        "provenance": {
            "c2pa_present": random.random() < 0.60,
            "signed": random.random() < 0.45,
            "actions_count": len(ops)
        },
        "ops": ops,
        "export": {"fmt": "mp4", "codec": "h264", "quality": random.choice([80, 85, 90, 95])}
    }

def make_balanced_dataset(n: int) -> Tuple[List[Dict[str, Any]], np.ndarray]:
    """Create a balanced dataset with overlapping operations to avoid shortcuts."""
    logs: List[Dict[str, Any]] = []
    labels: List[int] = []

    print(f"Generating {n} samples...")
    for i in range(n // 2):
        if i % 500 == 0:
            print(f"  Generated {i*2}/{n} samples...")

        # Misleading examples (60% images, 40% videos)
        if random.random() < 0.6:
            logs.append(_mk_misleading_image())
        else:
            logs.append(_mk_misleading_video())
        labels.append(1)

        # Benign examples (60% images, 40% videos)
        if random.random() < 0.6:
            logs.append(_mk_benign_image())
        else:
            logs.append(_mk_benign_video())
        labels.append(0)

    return logs, np.array(labels, dtype=int)

# ---------------------------
# TRAIN / EVAL UTILITIES
# ---------------------------

def choose_best_threshold(y_true: np.ndarray, p_mis: np.ndarray) -> float:
    """
    Pick the threshold that maximizes F1 on a validation set.
    """
    prec, rec, th = precision_recall_curve(y_true, p_mis)
    # precision_recall_curve returns thresholds of length len(prec)-1
    f1s = []
    for i in range(len(th)):
        p = prec[i+1]
        r = rec[i+1]
        f1s.append(0.0 if (p + r) == 0 else 2 * p * r / (p + r))
    if not f1s:
        return 0.5
    best_idx = int(np.argmax(f1s))
    return float(th[best_idx])

def build_models():
    """
    Return dict of base estimators; we will wrap each in calibration later.
    """
    models = {
        "Logistic Regression (Improved)": LogisticRegression(
            max_iter=2000,
            class_weight="balanced",
            solver='lbfgs',
            random_state=42
        ),
        "Random Forest (Tuned)": RandomForestClassifier(
            n_estimators=250,
            max_depth=18,
            min_samples_split=5,
            class_weight="balanced",
            random_state=42,
            n_jobs=-1
        ),
        "Gradient Boosting": GradientBoostingClassifier(
            n_estimators=150,
            learning_rate=0.08,
            max_depth=3,      # Note: GB's 'max_depth' is per tree; 3 is often good
            random_state=42
        )
    }
    return models

# ---------------------------
# MAIN
# ---------------------------

if __name__ == "__main__":
    print("🚀 Context-aware training with gray zones + calibrated probabilities...")

    # 1) Generate dataset with overlaps (prevents shortcut learning)
    feat_order = default_feature_order()
    logs, y = make_balanced_dataset(6000)
    X = np.vstack([feature_vector(l, feat_order) for l in logs])

    print(f"Dataset: {len(logs)} samples | misleading={int(np.sum(y))} | benign={int(len(y) - np.sum(y))}")

    # 2) Clean splits: train / val / test (no leakage)
    X_trainval, X_test, y_trainval, y_test, logs_trainval, logs_test = train_test_split(
        X, y, logs, test_size=0.30, random_state=42, stratify=y
    )
    X_train, X_val, y_train, y_val, logs_train, logs_val = train_test_split(
        X_trainval, y_trainval, logs_trainval, test_size=0.21, random_state=42, stratify=y_trainval
    )
    # Resulting ratios: ~56% train, ~14% val, 30% test

    # 3) Scaling for LR only (trees don't need it)
    scaler = StandardScaler()
    X_train_scaled = scaler.fit_transform(X_train)
    X_val_scaled = scaler.transform(X_val)
    X_test_scaled = scaler.transform(X_test)

    # 4) Build models, calibrate, and select using validation F1 at best threshold
    models = build_models()
    best = None
    best_name = ""
    best_val_f1 = -1.0
    best_threshold = 0.5
    best_is_logistic = False

    for name, base in models.items():
        print(f"\n--- {name} ---")

        # Pipeline for LR -> scaling + calibrated LR
        if "Logistic" in name:
            base_pipe = Pipeline([('scaler', StandardScaler()), ('clf', base)])
            calibrated = CalibratedClassifierCV(base_pipe, method="isotonic", cv=3)
            calibrated.fit(X_train, y_train)  # base_pipe handles scaling internally
            p_val = calibrated.predict_proba(X_val)[:, 1]
            p_test_preview = calibrated.predict_proba(X_test)[:, 1]  # preview only
            use_scaled_for_cv = True
            best_is_logistic_candidate = True
        else:
            # Trees: no scaling in the base estimator; still calibrate
            calibrated = CalibratedClassifierCV(base, method="isotonic", cv=3)
            calibrated.fit(X_train, y_train)
            p_val = calibrated.predict_proba(X_val)[:, 1]
            p_test_preview = calibrated.predict_proba(X_test)[:, 1]
            use_scaled_for_cv = False
            best_is_logistic_candidate = False

        # Choose threshold on validation
        th = choose_best_threshold(y_val, p_val)
        preds_val = (p_val >= th).astype(int)
        val_f1 = f1_score(y_val, preds_val)
        print(f"Validation: chosen threshold={th:.3f} | F1={val_f1:.3f}")

        # Track the best model by validation F1
        if val_f1 > best_val_f1:
            best_val_f1 = val_f1
            best = calibrated
            best_name = name
            best_threshold = th
            best_is_logistic = best_is_logistic_candidate

    # 5) Final evaluation on the untouched test set (single shot)
    print(f"\n🏆 Selected model: {best_name}")
    p_test = best.predict_proba(X_test)[:, 1]
    y_test_pred = (p_test >= best_threshold).astype(int)
    print(f"Test threshold = {best_threshold:.3f}")
    print("Test Set Performance:")
    print(classification_report(y_test, y_test_pred, digits=3))

    # Optional: quick binning overview for sanity (shows gray-zone mass)
    low = np.mean(p_test < 0.25)
    mid = np.mean((p_test >= 0.25) & (p_test < 0.55))
    high = np.mean(p_test >= 0.55)
    print(f"Score bins on test → low(<0.25): {low:.2%}, mid[0.25,0.55): {mid:.2%}, high(>=0.55): {high:.2%}")

    # 6) Save: model + features + decision threshold + bin cutpoints for UI
    artifact = {
        "model": best,                    # Calibrated estimator (isotonic)
        "features": feat_order,
        "threshold": best_threshold,      # for 'misleading' decision
        "bins": {                         # suggested UI bins for borderline handling
            "not_misleading": 0.25,
            "borderline": 0.55
        },
        "model_name": best_name
    }
    dump(artifact, "model.joblib")

    # 7) Save sample logs for testing
    with open("sample_misleading_advanced.json", "w") as f:
        misleading_samples = [l for l, label in zip(logs, y) if label == 1]
        json.dump(misleading_samples[0], f, indent=2)

    with open("sample_benign_advanced.json", "w") as f:
        benign_samples = [l for l, label in zip(logs, y) if label == 0]
        json.dump(benign_samples[0], f, indent=2)

    print("✅ Training complete (context-aware, calibrated).")
    print("📁 Saved model.joblib + sample logs.")
