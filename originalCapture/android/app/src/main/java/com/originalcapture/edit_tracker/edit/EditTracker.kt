package com.originalcapture.edit_tracker.edit

import com.originalcapture.edit_tracker.domain.model.ManifestStep
import com.originalcapture..edit_tracker.domain.model.ProvenanceManifest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simplified tracker that only handles chronological edit operations.
 */
@Singleton
class EditTracker @Inject constructor() {

    private var currentManifest: ProvenanceManifest = ProvenanceManifest()

    /**
     * Start new manifest with capture data.
     */
    fun initialiseWithCapture(
        originalImageHash: String,
        provenanceJwt: String,
        attestationData = ManifestStep.AttestationData? = null
    ) {
        val captureStep = ManifestStep.CaptureStep(
            origHash = originalImageHash,
            provJwt = provenanceJwt,
            attestationData = attestationData
        )

        currentManifest = ProvenanceManifest(chain = listOf(captureStep))
    }

    /**
     * Add a new edit operation to the manifest.
     */
    fun addEditOperation(
        operationType: String,
        parameters: Map<String, Any>,
        newImageHash: String
    ): ProvenanceManifest {
        val editStep = ManifestStep.EditStep(
            op = operationType,
            params = parameters,
            newImageHash = newImageHash,
        )

        val updatedChain = currentManifest.chain + editStep
        currentManifest = currentManifest.copy(chain = updatedChain)
    }

    /**
     * Get the current manifest.
     */
    fun getCurrentManifest(): ProvenanceManifest = currentManifest

    /**
     * Clear the current manifest.
     */
    fun clear() {
        currentManifest = ProvenanceManifest()
    }

    /**
     * Get simple edit history for UI display
     */
    fun getEditSummary(): String {
        val editCount = currentManifest.getEditCount()
        val lastEdit = currentManifest.chain.lastOrNull()

        return when {
            editCount == 0 -> "Original capture"
            lastEdit is ManifestStep.EditStep -> "Edited: ${lastEdit.op} (${editCount} total edits)"
            else -> "Unknown state"
        }
    }
}

