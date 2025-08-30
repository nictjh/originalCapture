# utils_x509_attestation.py
import base64
import json
from typing import List, Tuple, Optional
from cryptography import x509
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.backends import default_backend
from cryptography.x509.oid import ExtensionOID, ObjectIdentifier
from certvalidator import CertificateValidator, ValidationContext
from pyasn1.type import univ, char, namedtype, tag, constraint
from pyasn1.codec.der import decoder as der_decoder
from asn1crypto import core as asn1core

# OID for Android Key Attestation extension
KEY_DESCRIPTION_OID = ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17")

# --- ASN.1 skeleton structures (simplified) ---
# We model only the fields we need to extract from the KeyDescription structure.
# This is a trimmed structure based on Android source/tests and docs.

class AuthorizationList(univ.Sequence):
    componentType = namedtype.NamedTypes(
        # This list is enormous; we only pick fields we expect to see frequently.
        namedtype.OptionalNamedType('purpose', univ.SetOf(componentType=univ.Integer()).subtype(
            implicitTag=tag.Tag(tag.tagClassContext, tag.tagFormatSimple, 1))),
        namedtype.OptionalNamedType('allApplications', univ.Null().subtype(
            implicitTag=tag.Tag(tag.tagClassContext, tag.tagFormatSimple, 600))),
        namedtype.OptionalNamedType('origin', univ.Integer().subtype(
            implicitTag=tag.Tag(tag.tagClassContext, tag.tagFormatSimple, 702))),
        # ... many optional fields omitted for brevity ...
    )

class RootOfTrust(univ.Sequence):
    componentType = namedtype.NamedTypes(
        namedtype.NamedType('verifiedBootKey', univ.OctetString()),
        namedtype.NamedType('deviceLocked', univ.Boolean()),
        namedtype.NamedType('verifiedBootState', univ.Enumerated(namedValues=None)),  # values: verified / self-signed / etc
        namedtype.NamedType('verifiedBootHash', univ.OctetString())
    )

class AttestationApplicationId(univ.Sequence):
    componentType = namedtype.NamedTypes(
        namedtype.NamedType('packageInfos', univ.SequenceOf(componentType=univ.Sequence()))
    )

class KeyDescription(univ.Sequence): 
    componentType = namedtype.NamedTypes( 
        namedtype.NamedType('attestationVersion', univ.Integer()), 
        namedtype.NamedType('attestationSecurityLevel', univ.Integer()), 
        namedtype.NamedType('keymasterVersion', univ.Integer()), 
        namedtype.NamedType('keymasterSecurityLevel', univ.Integer()), 
        namedtype.NamedType('attestationChallenge', univ.OctetString()), 
        namedtype.NamedType('uniqueId', univ.OctetString()), 
        namedtype.NamedType('softwareEnforced', AuthorizationList()), 
        namedtype.NamedType('teeEnforced', AuthorizationList()) 
    )

class KeyDescriptionWrapper(univ.Sequence):
    componentType = namedtype.NamedTypes(
        namedtype.NamedType('keyDescription', KeyDescription())
    )

# --- Helpers ---

def load_cert_chain_from_b64_list(x5c_b64_list: List[str]) -> List[x509.Certificate]:
    certs = []
    for b64 in x5c_b64_list:
        der = base64.b64decode(b64)
        cert = x509.load_der_x509_certificate(der)
        certs.append(cert)
    return certs

def validate_chain_against_roots(chain: List[x509.Certificate], trust_anchor_path: str, crl_path: Optional[str]=None) -> Tuple[bool, str]:
    """
    Use certvalidator to check the chain up to the trust anchors.
    Returns (ok, message)
    """
    trust_roots = []
    with open(trust_anchor_path, "r") as f:
        pem_list = json.load(f)

    trust_roots = [pem.encode() for pem in pem_list]
    try:
        ctx = ValidationContext(trust_roots=trust_roots, allow_fetching=False)
    except Exception as e:
        print("ValidationContext creation failed:", e)
        raise
    # certvalidator wants cryptography.x509 cert objects
    chain_pem = [cert.public_bytes(serialization.Encoding.PEM) for cert in chain]
    leaf = chain_pem[0]
    intermediates = chain_pem[1:]
    try:
        validator = CertificateValidator(leaf, intermediate_certs=intermediates, validation_context=ctx)
        validator.validate_usage(set())
        return True, "chain valid"
    except Exception as e:
        return False, f"chain validation failed: {e}"

def extract_key_description_from_cert(cert: x509.Certificate) -> dict:
    try:
        ext = cert.extensions.get_extension_for_oid(KEY_DESCRIPTION_OID)
    except x509.ExtensionNotFound:
        raise ValueError("Attestation extension not found")
    
    der = ext.value.value
    
    # Decode the entire KeyDescription sequence
    key_desc, rest = der_decoder.decode(der)
    
    # Get security levels by position (positions 1 and 3)
    attestation_security_level = int(key_desc.getComponentByPosition(1))
    keymaster_security_level = int(key_desc.getComponentByPosition(3))
    attestation_challenge = bytes(key_desc.getComponentByPosition(4))
    
    # Now extract fields by position from the native list
    return {
        'attestationSecurityLevel': attestation_security_level,
        'keymasterSecurityLevel': keymaster_security_level,
        'attestationChallenge': attestation_challenge
    }

def enforce_policy(attestation_dict: dict, payload_obj: dict, config: dict) -> Tuple[bool, str]:
    # (a) hardware-backed
    if config.get("require_hardware_backed", True):
        level = attestation_dict.get("attestationSecurityLevel", 0)
        # In Android: 0 = software, 1 = Trusted Environment, 2 = StrongBox (values may vary by implementation)
        if level == 0:
            return False, "Not hardware backed (attestationSecurityLevel indicates software)"
    # (b) challenge / nonce
    # payload may have nonce_b64 or similar (here we accept nonce_b64 field)
    # payload_nonce_b64 = payload_obj.get("nonce_b64")
    # print(payload_nonce_b64)
    # if payload_nonce_b64:
    #     expected = base64.urlsafe_b64decode(payload_nonce_b64 + "==")
    #     if attestation_dict.get("attestationChallenge") != expected:
    #         return False, "attestation challenge does not match payload nonce"
    # (c) app_id match: checking package name is more involved; we recommend parsing the application id field
    # We'll do minimal check: that payload.app_id exists (structural)
    if payload_obj.get("app_id") != config.get("expected_app_id"):
        return False, "payload.app_id does not match expected app id"
    # (d) patch level checks are omitted in minimal parser; a fuller parser should extract osPatchLevel and vendorPatchLevel
    if config.get("min_patch_level"):
        # If we had osPatchLevel extracted:
        # if attestation_dict.get("osPatchLevel", 0) < config["min_patch_level"]: return False, "patch too old"
        pass
    return True, "policy OK"
