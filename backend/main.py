# main.py
from fastapi import FastAPI, File, UploadFile, Form, HTTPException
import json, base64
from utils_crypto import sha256_bytes, decode_b64, verify_signature_with_pubkey
from utils_x509_attestation import load_cert_chain_from_b64_list, validate_chain_against_roots, extract_key_description_from_cert, enforce_policy
from cryptography import x509
from cryptography.hazmat.primitives import serialization

import yaml

app = FastAPI()
config = yaml.safe_load(open("config.yaml"))

TRUST_STORE_FILE = f"{config['trust_store_dir']}/root"

@app.post("/verify")
async def verify_endpoint(
    payload_canonical: str = Form(...),
    sig_b64: str = Form(...),
    x5c_der_b64: str = Form(...),  # we accept JSON array string for simplicity
    media: UploadFile = File(None)
):
    # parse inputs
    try:
        payload_raw_bytes = payload_canonical.encode("utf-8")
        payload_obj = json.loads(payload_canonical)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"invalid payload_canonical: {e}")

    sig_bytes = base64.b64decode(sig_b64)

    try:
        x5c_list = json.loads(x5c_der_b64)
        certs = load_cert_chain_from_b64_list(x5c_list)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"invalid x5c_der_b64: {e}")

    if len(certs) == 0:
        raise HTTPException(status_code=400, detail="empty certificate chain")

    leaf_cert = certs[0]
    pubkey = leaf_cert.public_key()

    # 1) re-hash media and compare
    if media is None:
        raise HTTPException(status_code=400, detail="media file required")
    media_bytes = await media.read()
    media_hash = sha256_bytes(media_bytes)
    # payload has content_hash_b64 according to your schema
    content_hash_b64 = payload_obj.get("content_hash_b64")
    if not content_hash_b64:
        raise HTTPException(status_code=400, detail="payload missing content_hash_b64")
    claimed = base64.b64decode(content_hash_b64)
    if media_hash != claimed:
        raise HTTPException(status_code=400, detail="media hash mismatch with payload.content_hash_b64")

    # 2) verify signature over payload_canonical using leaf cert's public key
    ok = verify_signature_with_pubkey(pubkey, payload_raw_bytes, sig_bytes)
    if not ok:
        raise HTTPException(status_code=400, detail="signature verification failed")

    # 3) validate x.509 chain to Google attestation roots
    ok_chain, msg = validate_chain_against_roots(certs, TRUST_STORE_FILE)
    if not ok_chain:
        raise HTTPException(status_code=400, detail=f"certificate chain validation failed: {msg}")

    # 4) parse attestation extension and enforce policy
    try:
        att = extract_key_description_from_cert(leaf_cert)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"failed to parse attestation extension: {e}")

    policy_ok, policy_msg = enforce_policy(att, payload_obj, config)
    if not policy_ok:
        raise HTTPException(status_code=403, detail=f"policy failed: {policy_msg}")

    return {"ok": True, "message": "verified", "attestation": {"attestationSecurityLevel": att.get("attestationSecurityLevel")}}
