package com.originalcapture

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import kotlin.random.Random

// for verifying
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence


// Valid real Android device produced and signed this exact file, with verified boot and app binding.
object AttestationPoc {

    private const val TAG = "AttestationPoc"
    private const val KEY_ALIAS_PREFIX = "CaptureKey_"

    data class Result(
        val ok: Boolean,
        val mediaPath: String?,
        val sidecarPath: String?,
        val message: String
    )

    // ---- First Iteration: Working ----
    // fun run(context: Context): Result = runCatching {
    //     // 1) Create a small fake JPG file to simulate the captured media
    //     val mediaFile = File(context.filesDir, "demo.jpg")
    //     if (!mediaFile.exists()) {
    //         val randomBytes = Random.Default.nextBytes(2048)
    //         FileOutputStream(mediaFile).use { it.write(randomBytes) }
    //     }

    //     // 2) SHA-256 of the exact bytes
    //     val contentHash = sha256File(mediaFile)
    //     val contentHashB64 = b64(contentHash)

    //     // 3) Canonical, minified payload string
    //     val payloadCanonical = buildCanonicalPayload(
    //         contentHashB64 = contentHashB64,
    //         appId = context.packageName
    //     )

    //     // 4) Generate per-capture key with attestationChallenge = SHA256(payload)
    //     val alias = KEY_ALIAS_PREFIX + System.currentTimeMillis()
    //     val payloadHash = sha256(payloadCanonical.toByteArray(Charsets.UTF_8))
    //     val chain = generateKeyWithAttestation(alias, payloadHash)

    //     // 5) Sign payload inside TEE/StrongBox
    //     val signatureB64 = signPayload(alias, payloadCanonical)

    //     // 6) Emit sidecar receipt JSON
    //     val sidecar = File(context.filesDir, mediaFile.name + ".sig.json")
    //     writeReceipt(sidecar, payloadCanonical, signatureB64, chain)

    //     // 7) Delete key for unlinkability
    //     deleteKey(alias)

    //     Log.i(TAG, "Media: ${mediaFile.absolutePath}")
    //     Log.i(TAG, "Receipt: ${sidecar.absolutePath}")

    //     Result(
    //         ok = true,
    //         mediaPath = mediaFile.absolutePath,
    //         sidecarPath = sidecar.absolutePath,
    //         message = "POC complete."
    //     )
    // }.getOrElse { e ->
    //     Log.e(TAG, "POC failed", e)
    //     Result(false, null, null, "POC failed: ${e.message}")
    // }


    fun run(context: Context): Result = runCatching {

        // 1) Create a small fake JPG file to simulate the captured media
        val mediaFile = File(context.filesDir, "demo.jpg")
        if (!mediaFile.exists()) {
            val randomBytes = Random.Default.nextBytes(2048)
            FileOutputStream(mediaFile).use { it.write(randomBytes) }
        }

        // 2) Hash the media file
        val contentHash = sha256File(mediaFile) // Hash the file
        val contentHashB64 = b64(contentHash) // Base64 encode the hash

        // 3) Build Canonical, minified payload string
        val payloadCanonical = buildCanonicalPayload(
            contentHashB64 = contentHashB64,
            appId = context.packageName
        )

        // 4) Generate per-capture key with attestationChallenge = SHA256(payload)
        val alias = KEY_ALIAS_PREFIX + System.currentTimeMillis() // This generates a unique alias for the key
        val payloadHash = sha256(payloadCanonical.toByteArray(Charsets.UTF_8))
        val attRes = generateKeyWithAttestation(alias, payloadHash) // <-- returns AttestationResult

        // 5) Sign payload inside TEE/StrongBox (or software if that’s what device provides)
        // Signs the payload only because of the algorithm defined in signPayload()
        val signatureB64 = signPayload(alias, payloadCanonical)

        // 6) Emit sidecar receipt JSON (include chain if present; empty list otherwise)
        val sidecar = File(context.filesDir, mediaFile.name + ".sig.json")
        val chain = attRes.chain ?: emptyList()
        writeReceipt(sidecar, payloadCanonical, signatureB64, chain)

        // 7) Delete key for unlinkability (no reuse)
        deleteKey(alias)

        Log.i(TAG, "Media: ${mediaFile.absolutePath}")
        Log.i(TAG, "Receipt: ${sidecar.absolutePath}")
        Log.i(TAG, "Attestation summary: ${attRes.message}")

        // Optional: enforce on-device policy (warn when weak)
        val note = when (attRes.level) {
            AttestationLevel.STRONGBOX    -> "Hardware-backed (StrongBox)"
            AttestationLevel.TEE_HARDWARE -> "Hardware-backed (TEE)"
            AttestationLevel.HARDWARE_NO_CHAIN -> "Hardware key (no attestation chain) - older device fallback"
            AttestationLevel.SOFTWARE     -> "Software keystore - NOT strong"
            AttestationLevel.NONE         -> "No chain & not hardware - NOT strong"
        }

        Result(
            ok = true,
            mediaPath = mediaFile.absolutePath,
            sidecarPath = sidecar.absolutePath,
            message = "POC complete. $note. insideSecureHardware=${attRes.insideSecureHardware}"
        )
    }.getOrElse { e ->
        Log.e(TAG, "POC failed", e)
        Result(false, null, null, "POC failed: ${e.message}")
    }


    // Helper Functions below

    /**
     * Streams the file in 8kb chunks
     * Updates the SHA-256 digest incrementally
     * Returns the final 256 bit hash at the end
     * Specifically used for the media.bin in the normal flow (Not used in PoC)
     *
     *
     *
     * Purpose: Guarantee the exact file bytes on disk are what is binded into the proof.
     */
    private fun sha256File(f: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val r = ins.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest()
    }


    /**
     * Quick one shot hash of any byte array
     *
     * Returns the final 256 bit hash at the end
     */
    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)


    /**
     * Encodes byte array to base64 string without line wraps
     * This is to ensure that every binary artifact is transport-safe JSON fields
     *
     *
     *
     * Used for the fields when building the canonical payload and the results
     */
    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)




    /** Build a minified JSON string with fixed field order (canonical) */
    private fun buildCanonicalPayload(
        contentHashB64: String,
        appId: String
    ): String {
        val ts = System.currentTimeMillis()
        val nonce = Random.Default.nextBytes(24)
        // fixed key order; no spaces; keep exactly this format for signing
        return "{\"schema\":\"attest.v1\"," +   // version 1 of my schema
                "\"alg\":\"ES256\"," +          //Signing algo
                "\"hash_alg\":\"SHA-256\"," +   // Hash algo for content_hash_b64
                "\"content_hash_b64\":\"$contentHashB64\"," + // Hash of the media file
                "\"ts_unix_ms\":$ts," +         // Device timestamp, can be used to limit reply with server side acceptance window
                "\"nonce_b64\":\"${b64(nonce)}\"," + // Random nonce to make each payload unique even if content is same
                "\"app_id\":\"$appId\"}"    // My app package name, to bind. Prevents cross app replay
    }


    data class AttestationResult(
        val chain: List<X509Certificate>?,   // null if none
        val insideSecureHardware: Boolean?,  // null if KeyInfo unavailable
        val level: AttestationLevel,         // our classification
        val message: String, // debug info
    )

    enum class AttestationLevel {
        STRONGBOX,          // chain says StrongBox (preferred)
        TEE_HARDWARE,       // chain says TEE (hardware)
        SOFTWARE,           // chain is Software Attestation
        HARDWARE_NO_CHAIN,  // KeyInfo says hardware, but no chain (old devices)
        NONE                // no chain + KeyInfo says not hardware (emulator/software keystore)
    }

    /**
     * Asks Keystore to generate a new P-256 signing key with attestation, tries StrongBox first then TEE,
     * Collects signals to classify the attestation level,
     *
     * Parameters: alias = unique per-capture key name, attestationChallenge = SHA256(payload)
     * Returns AttestationResult
     *
     *
     * This is the core of the idea for Camera Pipeline side
     */
    private fun generateKeyWithAttestation(
        alias: String,
        attestationChallenge: ByteArray
    ): AttestationResult {

        // Configures the KeyPairGenerator -> create EC keypair using AndroidKeyStore (It will select TEE or StrongBox)
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")


        // Helper function to build the KeyGenParameterSpec with StrongBox flag
        fun buildSpec(isStrongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")) //P-256 for ES256
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .setAttestationChallenge(attestationChallenge) // Binds the attestation to our payload, so my server can verify that the key was created for this exact payload
            if (isStrongBox) b.setIsStrongBoxBacked(true) // Requests StrongBox if available on device
            return b.build()
        }

        // 1) Try StrongBox first, if error then fallback to TEE
        var usedStrongBox = true

        try {
            kpg.initialize(buildSpec(true))
            kpg.generateKeyPair() // KeyPair is generated and attestation cert chain is stored in AndroidKeyStore
        } catch (e: StrongBoxUnavailableException) {
            Log.w(TAG, "StrongBox unavailable; falling back to TEE", e)
            usedStrongBox = false
            kpg.initialize(buildSpec(false)) // Fallback to TEE only
            kpg.generateKeyPair()
        } catch (e: Exception) { // Pokeball CATCH laaa
            // Some devices throw a generic Exception when StrongBox flag is set but not supported
            Log.w(TAG, "StrongBox not supported; falling back to TEE", e)
            usedStrongBox = false
            kpg.initialize(buildSpec(false))
            kpg.generateKeyPair()
        }



        // 2) Fetch chain from Keystore
        // supposed to get my leaf + intermediates + root
        // If null, device is either old (pre-Android 7) or emulator or has software-only keystore
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) } // get AndroidKeyStore instance
        val rawChain = ks.getCertificateChain(alias)?.map { it as X509Certificate }

        // Checks Security Levels from attestation extension in leaf cert
        rawChain?.firstOrNull()?.let { logSecurityLevelsFromAttestation(it) }


        // 3) Query KeyInfo.isInsideSecureHardware as a fallback signal when I cant get a chain
        // Why I cant get a chain: old device, emulator, software keystore
        // Some devices do not have StrongBox but still have TEE-backed keys E.g. Like my Android 9 Note 8
        // Returns boolean but if true it does not say if StrongBox or TEE
        val insideSecureHardware: Boolean? = runCatching {
            val priv = ks.getKey(alias, null)
            val kf = java.security.KeyFactory.getInstance(
                (priv?.algorithm ?: "EC"), "AndroidKeyStore"
            )
            val keyInfo = kf.getKeySpec(priv, android.security.keystore.KeyInfo::class.java)
            keyInfo.isInsideSecureHardware
        }.getOrNull()


        // 4) Classify attestation level
        val level = classifyAttestationLevel(rawChain, usedStrongBox, insideSecureHardware)

        // 5) Return everything for logging/server
        val msg = buildString {
            append("usedStrongBox=").append(usedStrongBox)
            append(", insideSecureHardware=").append(insideSecureHardware)
            append(", level=").append(level)
            if (rawChain != null) {
                append(", subjects=[")
                append(rawChain.joinToString(" | ") { it.subjectX500Principal.name })
                append("]")
            } else append(", no x5c chain")
        }

        return AttestationResult(
            chain = rawChain,
            insideSecureHardware = insideSecureHardware,
            level = level,
            message = msg
        )
    }

    /**
     * Helper to classify by chain CN + KeyInfo
     */
    private fun classifyAttestationLevel(
        chain: List<X509Certificate>?,
        usedStrongBox: Boolean,
        insideSecureHardware: Boolean?
    ): AttestationLevel {

        if (!chain.isNullOrEmpty()) {
            val subjects = chain.map { it.subjectX500Principal.name }
            val isSoftware = subjects.any { it.contains("Software Attestation", ignoreCase = true) }
            if (isSoftware) return AttestationLevel.SOFTWARE // This is happens when device can't produce a hardware backed certificate chain
            // For e.g. emulators or devices without TEE/StrongBox support

            // Non-software chain: prefer StrongBox if requested and device supports it
            if (usedStrongBox && subjects.any { it.contains("StrongBox", ignoreCase = true) }) {
                return AttestationLevel.STRONGBOX // This is the best case which we are aiming for
            }

            // Otherwise treat as TEE hardware attestation
            return AttestationLevel.TEE_HARDWARE
        }

        // No chain — fall back to KeyInfo signal (useful on Android 9 and older)
        return when (insideSecureHardware) {
            true  -> AttestationLevel.HARDWARE_NO_CHAIN
            false -> AttestationLevel.NONE
            null  -> AttestationLevel.NONE
        }
    }


    // ---- First Iteration: Working ----
    /**
     * Generate a per-capture EC P-256 key with an attestation certificate chain.
     * Correct way to request StrongBox: use a builder, set isStrongBoxBacked(true),
     * try/catch StrongBoxUnavailableException, then rebuild without StrongBox.
     */
    // private fun generateKeyWithAttestation(
    //     alias: String,
    //     attestationChallenge: ByteArray
    // ): List<X509Certificate> {
    //     val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")

    //     fun baseBuilder(isStrongBox: Boolean): KeyGenParameterSpec {
    //         val b = KeyGenParameterSpec.Builder(
    //             alias,
    //             KeyProperties.PURPOSE_SIGN
    //         )
    //             .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")) // P-256
    //             .setDigests(KeyProperties.DIGEST_SHA256)
    //             .setUserAuthenticationRequired(false)
    //             .setAttestationChallenge(attestationChallenge)
    //         if (isStrongBox) b.setIsStrongBoxBacked(true)
    //         return b.build()
    //     }

    //     try {
    //         // Try StrongBox first (if present on device)
    //         kpg.initialize(baseBuilder(true))
    //         kpg.generateKeyPair()
    //     } catch (e: StrongBoxUnavailableException) {
    //         // Fallback to TEE
    //         Log.w(TAG, "StrongBox unavailable, falling back to TEE", e)
    //         kpg.initialize(baseBuilder(false))
    //         kpg.generateKeyPair()
    //     }

    //     val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    //     val chain = ks.getCertificateChain(alias)
    //         ?: throw IllegalStateException("No attestation chain returned (software keystore or emulator?)")

    //     @Suppress("UNCHECKED_CAST")
    //     return chain.map { it as X509Certificate }
    // }

    /** Sign payload inside TEE/StrongBox; return base64 DER signature */
    private fun signPayload(alias: String, payloadCanonical: String): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val priv = ks.getKey(alias, null)
            ?: throw IllegalStateException("Private key not found for alias $alias")

        val sig = Signature.getInstance("SHA256withECDSA") // This algorithm hashes and then signs that hash
        sig.initSign(priv as java.security.PrivateKey)
        sig.update(payloadCanonical.toByteArray(Charsets.UTF_8))
        val der = sig.sign()
        return b64(der)
    }

    /**
     * Sidecar receipt JSON: payload + signature + x5c (DER b64, leaf first)
     * the leaf certificate contains the key's public part to verify the signature
     */
    private fun writeReceipt(
        out: File,
        payloadCanonical: String,
        signatureB64: String,
        certs: List<X509Certificate>
    ) {
        val arr = JSONArray()
        certs.forEach { cert -> arr.put(b64(cert.encoded)) }

        val obj = JSONObject().apply {
            put("payload_canonical", payloadCanonical)
            put("sig_b64", signatureB64)
            put("x5c_der_b64", arr)
        }
        out.writeText(obj.toString())
    }

    /** Delete key alias (no reuse = unlinkable) */
    private fun deleteKey(alias: String) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.deleteEntry(alias)
    }


    private fun logSecurityLevelsFromAttestation(leaf: X509Certificate) {
        val oid = "1.3.6.1.4.1.11129.2.1.17"
        val ext = leaf.getExtensionValue(oid)
        if (ext == null) {
            Log.w(TAG, "No Android Key Attestation extension on leaf")
            return
        }

        val octets = ASN1OctetString.getInstance(ext).octets
        val seq = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(octets))

        // Use .value (BigInteger) -> .toInt() (values are 0,1,2)
        val attLvlEnum = ASN1Enumerated.getInstance(seq.getObjectAt(1)).value.toInt()
        val kmLvlEnum  = ASN1Enumerated.getInstance(seq.getObjectAt(3)).value.toInt()

        fun map(v: Int) = when (v) {
            0 -> "SOFTWARE"
            1 -> "TEE"
            2 -> "STRONGBOX"
            else -> "UNKNOWN($v)"
        }

        Log.i(TAG, "attestationSecurityLevel=${map(attLvlEnum)}, keymasterSecurityLevel=${map(kmLvlEnum)}")
    }
}
