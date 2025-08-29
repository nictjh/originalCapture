# utils_crypto.py
import hashlib
import base64
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.exceptions import InvalidSignature

def sha256_bytes(data: bytes) -> bytes:
    return hashlib.sha256(data).digest()

def decode_b64(s: str) -> bytes:
    return base64.b64decode(s)

def verify_signature_with_pubkey(pubkey, payload_bytes: bytes, sig_bytes: bytes) -> bool:
    """
    pubkey: cryptography public key (loaded from certificate)
    payload_bytes: the exact bytes that were signed (payload_canonical)
    sig_bytes: raw signature bytes (for ECDSA: ASN.1 DER-encoded)
    """
    try:
        if isinstance(pubkey, ec.EllipticCurvePublicKey):
            pubkey.verify(sig_bytes, payload_bytes, ec.ECDSA(hashes.SHA256()))
            return True
        elif isinstance(pubkey, rsa.RSAPublicKey):
            pubkey.verify(sig_bytes, payload_bytes, padding.PKCS1v15(), hashes.SHA256())
            return True
        else:
            # Unknown key type
            return False
    except InvalidSignature:
        return False
