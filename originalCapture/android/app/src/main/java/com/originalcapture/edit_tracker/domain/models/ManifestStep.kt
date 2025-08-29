package com.originalcapture.edit_tracker.domain.models

import java.util.UUID

/**
 * Sealed hierarchy representing all possible steps in the provenance chain.
 * Each step is immutable and contains all necessary context for its operation.
 */

sealed class ManifestStep {
    // Unique identifier and timestamp for every step
    abstract val id: String
    abstract val timestamp: Long

    /**
     * The initial capture step. This is the root of the provenance chain.
     * @param origHash The SHA-256 hash of the original image bytes (Base64 encoded)
     * @param provJwt The provenance JWT received from the server after attestation
     */
    data class CaptureStep(
        val origHash: String,   // SHA-256 of original image
        val provJwt: String,    // JWT from server after attestation
        override val timestamp: Long = System.currentTimeMillis(),
        override val id: String = UUID.randomUUID().toString()
    ) : ManifestStep()

    data class EditStep(
        val op: String,     // Will use an enum for this
        val params: Map<String, Any>,   // Operation parameters (e.g. crop x, y co-ords)
        val newImageHash: String,       // SHA-256 of image after this edit
        override val timestamp: Long = System.currentTimeMillis(),
        override val id: String = UUID.randomUUID().toString()
    ) : ManifestStep()

    /**
     * Attestation data from initial capture
     */
    data class AttestationData(
        val payload: String,
        val signature: String,
        val securityLevel: String
    )
}

