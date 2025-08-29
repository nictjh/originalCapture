package com.originalcapture.edit_tracker.domain.models

/**
 * Simplified manifest with chronological edit steps.
 */

data class ProvenanceManifest(
    val version: String = "0.1",
    val chain: List<ManifestStep>
) {

    /**
     * Get current image hash from previous step
     */
    fun getCurrentImageHash(): String {
        return when (val lastStep = chain.lastOrNull()) {
            is ManifestStep.CaptureStep -> lastStep.origHash
            is ManifestStep.EditStep -> lastStep.newImageHash
            null -> ""
        }
    }

    /**
     * Get number of edit operations
     */
    fun getEditCount(): Int {
        return chain.count { it is ManifestStep.EditStep }
    }

    /**
     * Checks if image has been edited
     */
    fun hasEdits(): Boolean {
        return getEditCount() > 0
    }
}