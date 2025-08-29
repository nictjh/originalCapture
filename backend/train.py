# train.py
from __future__ import annotations
import random, json
from typing import Dict, Any, List, Tuple
import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import classification_report
from joblib import dump
from features import default_feature_order, feature_vector, featurize

random.seed(7)

BUCKETS = ["transform", "adjust", "filter", "overlay", "privacy_blur", "compose"]

def _mk_image_log() -> Dict[str, Any]: #creates a random log (image)
    w, h = 1080, 1440
    ops = []
    k = random.randint(1, 6)
    for _ in range(k):
        t = random.choices(BUCKETS, weights=[3,3,1,2,2,1])[0]
        if t == "transform":
            if random.random() < 0.6:
                cw, ch = random.randint(200, w), random.randint(200, h)
                x, y = random.randint(0, max(0, w-cw)), random.randint(0, max(0, h-ch))
                p = {"crop": [x, y, cw, ch]}
            else:
                p = {"rotate": random.choice([0, 90, 180, 270])}
        elif t == "adjust":
            p = {"brightness": random.randint(-30, 30),
                 "contrast": random.randint(-30, 30),
                 "saturation": random.randint(-30, 30)}
        elif t == "filter":
            p = {"lut": random.choice(["bw","vintage","cinematic"])}
        elif t == "overlay":
            p = {"text": bool(random.getrandbits(1))}
        elif t == "privacy_blur":
            p = {"method": "gaussian", "area_pct": round(random.random()*30, 1)}
        else:  # compose
            p = {"type": random.choice(["splice","inpaint","bg_replace"]),
                 "area_pct": round(5 + random.random()*40, 1)}
        ops.append({"t": t, "p": p})

    return {
        "asset_id": f"img-{random.randint(1000,9999)}",
        "created_at": "2025-08-29T09:12:33Z",
        "meta": {"width": w, "height": h},
        "provenance": {
            "c2pa_present": random.random() < 0.25,
            "signed": random.random() < 0.2,
            "actions_count": len(ops)
        },
        "ops": ops,
        "export": {"fmt": "jpg", "quality": random.choice([75,80,85,90,95])}
    }

def _mk_video_log() -> Dict[str, Any]: #creates a random log (video)
    w, h, fps, dur = 1920, 1080, 30, random.uniform(5, 20)
    ops = []
    k = random.randint(2, 6)
    for _ in range(k):
        t = random.choices(BUCKETS, weights=[3,3,1,2,2,1])[0]
        if t == "transform":
            # trim or speed change as transform
            if random.random() < 0.5:
                a, b = sorted([random.uniform(0, dur*0.8), random.uniform(dur*0.2, dur)])
                p = {"start": round(a,2), "end": round(b,2), "time_pct": round(100*(b-a)/dur,1)}
            else:
                p = {"speed": random.choice([0.5, 0.75, 1.0, 1.25, 2.0])}
        elif t == "adjust":
            p = {"brightness": random.randint(-25, 25),
                 "contrast": random.randint(-25, 25),
                 "saturation": random.randint(-25, 25)}
        elif t == "filter":
            p = {"lut": random.choice(["bw","film","warm"])}
        elif t == "overlay":
            p = {"captions": bool(random.getrandbits(1))}
        elif t == "privacy_blur":
            timep = round(random.uniform(10, 90), 1)
            p = {"method":"gaussian", "area_pct_avg": round(random.uniform(2, 25),1), "time_pct": timep}
        else:  # compose
            timep = round(random.uniform(5, 60), 1)
            p = {"type": random.choice(["bg_replace","inpaint","face_swap"]),
                 "area_pct_avg": round(random.uniform(5, 45),1),
                 "time_pct": timep}
        ops.append({"t": t, "p": p})

    return {
        "asset_id": f"vid-{random.randint(1000,9999)}",
        "created_at": "2025-08-29T09:12:33Z",
        "media": {"type":"video", "width": w, "height": h, "fps": fps, "duration_sec": round(dur,2)},
        "provenance": {
            "c2pa_present": random.random() < 0.25,
            "signed": random.random() < 0.2,
            "actions_count": len(ops)
        },
        "ops": ops,
        "export": {"fmt":"mp4","codec":"h264","quality": random.choice([70,80,90])}
    }

def make_dataset(n: int) -> Tuple[List[Dict[str, Any]], np.ndarray]:
    logs = []
    for _ in range(n):
        logs.append(_mk_video_log() if random.random() < 0.5 else _mk_image_log())
    # Rule labels (interpretable baseline)
    y = np.array([rule_label(l) for l in logs], dtype=int)
    return logs, y

def rule_label(log: Dict[str, Any]) -> int:
    """
    1 = misleading, 0 = not.
    Simple interpretable rules that use both image + video features.
    """
    f = featurize(log)
    # Strong signals
    if f["n_compose"] > 0 and (f["total_compose_area_pct"] >= 10 or f["max_compose_area_pct"] >= 15):
        return 1
    # Video-only: long-lasting compose
    if f["total_compose_time_pct"] >= 25:
        return 1
    # Heavy crop (keeping small portion of scene)
    if f["total_crop_area_pct"] > 60:
        return 1
    # Aggressive global adjustments
    if f["total_adjust_mag"] > 120:
        return 1
    return 0

if __name__ == "__main__":
    feat_order = default_feature_order()
    logs, y = make_dataset(2000)
    X = np.vstack([feature_vector(l, feat_order) for l in logs])

    clf = LogisticRegression(max_iter=300, class_weight="balanced")
    clf.fit(X, y)
    preds = clf.predict(X)
    print(classification_report(y, preds, digits=3))

    dump({"model": clf, "features": feat_order}, "model.joblib")
    with open("sample_log.json", "w") as f:
        json.dump(logs[0], f, indent=2)
    print("Saved model.joblib and sample_log.json")
