# verisnap — Hardware-Attested Camera + Edit-Chain C2PA + AI Authenticity
> **Submission:** This repository is submitted for **“7. Privacy Meets AI: Building a Safer Digital Future.”**  

**Mission:** Let anyone trust that a photo/video came from a real device/app and—if it was edited—exactly how it was edited.

**How:** The capture app (APK) signs capture facts with hardware-backed keys (TEE concept). A mock server verifies those facts and consults an AI authenticity model. Only when the user edits does VeriSnap mint/extend a C2PA manifest that chains those edits. The server then fuses hardware verification + model signal into a final verdict.

## Contents

**`/originalcapture`** – Android app that simulates TikTok-style "Record / Take Photo".
- Captures media and strips metadata (no EXIF/GPS/etc. on outputs).
- Computes media hash and signs an attestation payload using a device key (mocked hardware key in the demo).
- Can perform optional local edits (e.g., crop).
- If edits were performed, builds or extends a C2PA manifest that chains those edits.
- Uploads to the mock server.

**`/backend/main.py`** – Mock verification service simlating Tiktok backend
- Verifies signature/attestation (proving the capture originated from the device key).
- Calls the AI model and returns a consolidated verdict.

**`/backed/...`** – AI authenticity model (REST).
- Consumes media + (optionally) a C2PA edit chain + server verification signal.
- Returns `{ verdict: Authentic | NonAuthentic, confidence }`.

This is an end-to-end demo. The attestation pattern mirrors real deployments (Android Keystore/TEE).

## What verisnap Proves (Demo)

1. **Origin & integrity at capture:** The app signs with **hardware based key** a payload that includes `sha256(media)`, app id, timestamp, and a device key id. The server verifies the signature before doing anything else.

2. **Edit lineage (only when edits occur):** If the user chooses to edit, verisnap creates/extends a C2PA manifest that chains each edit step. No edit → no C2PA.

3. **Visual authenticity:** The AI model classifies media as `Authentic` or `Not Authentic` based on visual cues (and, when present, the edit chain).

## Demo Workflows

### 1) Take a picture with no edits → Upload
Server verifies hardware signature, runs AI → Final verdict: **Authentic**.

### 2) Take a picture with minor edits → Upload
App exports edited media and submits edits metadata.
Server verifies signature, creates a C2PA manifest describing the edits, runs AI → Final verdict: **Authentic**.

### Not demonstrated (but supported by policy):
**Heavy edits → Upload**
Server verifies signature, C2PA chain present (edits), AI flags manipulation → Final verdict: **NonAuthentic**.

## Architecture

```
┌───────────┐    capture bundle     ┌─────────────┐        media (+ optional edits)
│  APK      │  ───────────────────▶ │  Mock       │  ───────────────────────────▶  AI Model
│ (Camera + │  { media, hash,       │  Server     │   (uses C2PA only if edits)   (REST)
│  Edits UI)│    attestation, sig   │ Verify with   │
└────┬──────┘       c2pa       }     │  C2PA (edits)        ▲
     │  final JSON (verdict)         └─────────────┬────────┘
     └─────────────────────────────────────────────┘
```

**Key point:** C2PA manifests are only produced when edits occur. For no-edit media, the result contains hardware verification + model verdict but no manifest.


## Testing Environment
- A34 (Android 14)
- Node.js 18+ (server)
- Python 3.10+ (model)

## Quick Start

### 1) Server Start
```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

### 2) Apk
```bash
cd originalcapture
npm install
npx expo install
npx expo run:android
```

Capture → (optional) edit → upload → see verdict.

## End-to-End Flow (Detailed)

### Client (APK)

#### Capture media
- Photo/Video captured via native camera.
- No EXIF/GPS metadata is attached to output files.

#### Compute digest + attestation
- `sha256(media)`
- Attestation payload (canonical JSON):
- `signature = sign(payload, device_private_key)` (LEGIT hardware key used in demo).

#### Optional edits
If the user opens the editor and applies changes (e.g., crop/rotate/annotate), the app exports a new edited media file and prepares an edits descriptor (e.g., ordered steps, bounding boxes, parameters).

Only in this case will the app create/extend a C2PA chain.

#### Upload (POST /ingest)
`multipart/form-data`:
- `file`: media (edited or original)
- `attestation`: JSON (above)
- `signature`: base64 signature over the attestation JSON
- `edits`: (optional) JSON array describing edit steps (presence of this field triggers C2PA creation/extension)

**(do not send)** C2PA for no-edit media

### Server

#### Verify
- Parse attestation; verify signature with the registered public key for `device_key_id`.
- Recompute `sha256(file)` and compare to `attestation.media_sha256`.

#### C2PA (only if edits provided)
- Create a new manifest if none exists, or extend an existing one.
- Add assertions that describe each edit step (tool name, parameters, time).
- Store manifest alongside the edited media.

#### AI Classification
- Call the AI model with media + (optional) C2PA summary + hardware_verification information.
- Model returns `classification`.

#### Fuse and respond
- If hardware verification fails → bias toward Not Authentic.
- Otherwise rely on the model verdict.
- Return JSON with classification.

### Response (light edits example)

```json
const classification = {
    label: "benign",
    risk_score: 0.126,
    model_prob_misleading: 0.080,
    rule_points: 0,
    reasons: ["Crop is too minor"],
    features_used: [
      "n_transform",
      "n_adjust",
      "n_filter",
      "n_overlay",
      "n_privacy_blur",
      "n_compose",
      "c2pa_present",
      "signed",
      "actions_count",
      "jpeg_quality",
      "total_crop_area_pct",
      "total_adjust_mag",
      "total_blur_area_pct",
      "total_compose_area_pct",
      "overlay_after_compose",
      "total_blur_time_pct",
      "total_compose_time_pct",
      "max_compose_area_pct",
    ],
    classification: "Authentic",
  };
```

### Response (heavy edits example)

```json
const classification = {
    label: "risky",
    risk_score: 0.534,
    model_prob_misleading: 0.458,
    rule_points: 7,
    reasons: [
      "Has composition edits (splice/inpaint/bg replace)",
      "Large composed region",
      "Composition affects large portion of duration",
    ],
    features_used: [
      "n_transform",
      "n_adjust",
      "n_filter",
      "n_overlay",
      "n_privacy_blur",
      "n_compose",
      "c2pa_present",
      "signed",
      "actions_count",
      "jpeg_quality",
      "total_crop_area_pct",
      "total_adjust_mag",
      "total_blur_area_pct",
      "total_compose_area_pct",
      "overlay_after_compose",
      "total_blur_time_pct",
      "total_compose_time_pct",
      "max_compose_area_pct",
    ],
    classification: "Not Authentic",
  };
```

## Security & Privacy
- No EXIF/PII: Captured and edited files are written without EXIF/GPS or other metadata by default.
- Key safety: Private keys never leave the device. In production, adopt Android Keystore + Key Attestation (TEE/StrongBox) and validate the certificate chain on the server.
- Replay resistance: Attestation includes nonce + timestamp; the server should reject stale timestamps or repeated nonces.
- Transport: Use HTTPS in real deployments.
- Storage: Encrypt at rest; set TTLs for raw uploads; manifests are intended for auditability but may be access-controlled.

---

## Design Choices & Trade-offs

- **Privacy-first capture:** we intentionally drop metadata so users don’t leak location/PII.
- **Attestation before analysis:** we verify the signed payload and media hash first, so the AI model never runs on unverifiable content.
- **C2PA only for edits:** avoids bloating simple captures while giving a portable edit lineage when changes exist.
- **Lightweight demo stack:** Python backend + RN app keep the barrier to entry low, but the components are designed to be swapped for production equivalents.

---

## What We Tackled & Solved (Submission Summary)

- ✅ **Hardware-style attestation** at capture time (origin & integrity).
- ✅ **User privacy** by removing EXIF/GPS while still proving capture integrity.
- ✅ **Edit transparency via C2PA**, only when edits occur (no edit → no manifest).
- ✅ **AI-assisted authenticity** that considers both visual signals and provenance.
- ✅ **End-to-end flow** (Android app → backend → model) with clear APIs and reproducible demos.
- ✅ **Two real workflows demonstrated** (no edits → Authentic; minor edits → Authentic).
- ✅ **Policy path for heavy edits → Not Authentic** (documented & testable).