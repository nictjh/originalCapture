package com.originalcapture.editor.model

import java.util.*

/**
 * Simple data class to track video trim operation
 */
data class VideoTrim(
    val originalPath: String,
    val startTimeMs: Long = 0,
    val endTimeMs: Long,
    val trimmedDurationMs: Long = endTimeMs - startTimeMs,
    val timestamp: Long = System.currentTimeMillis(),
    val trimId: String = UUID.randomUUID().toString()
) {
    val isTrimmed: Boolean
        get() = startTimeMs > 0 || endTimeMs < originalDurationMs

    val originalDurationMs: Long = endTimeMs // Simplified - in real app get from MediaMetadataRetriever

    fun toJson(): String {
        return buildString {
            appendLine("{")
            appendLine("  \"trimId\": \"$trimId\",")
            appendLine("  \"originalPath\": \"$originalPath\",")
            appendLine("  \"startTimeMs\": $startTimeMs,")
            appendLine("  \"endTimeMs\": $endTimeMs,")
            appendLine("  \"trimmedDurationMs\": $trimmedDurationMs,")
            appendLine("  \"timestamp\": $timestamp,")
            appendLine("  \"isTrimmed\": $isTrimmed")
            appendLine("}")
        }
    }
}

/**
 * Simple manager to track trim state
 */
class TrimTracker {
    private var currentTrim: VideoTrim? = null

    fun setTrim(originalPath: String, startMs: Long, endMs: Long, originalDurationMs: Long): VideoTrim {
        // Validate trim bounds
        val validStart = maxOf(0, startMs)
        val validEnd = minOf(originalDurationMs, endMs)

        if (validStart >= validEnd) {
            throw IllegalArgumentException("Invalid trim: start ($validStart) must be before end ($validEnd)")
        }

        if (validEnd - validStart < 1000) {
            throw IllegalArgumentException("Trim too short: minimum 1 second required")
        }

        currentTrim = VideoTrim(
            originalPath = originalPath,
            startTimeMs = validStart,
            endTimeMs = validEnd
        )

        return currentTrim!!
    }

    fun getCurrentTrim(): VideoTrim? = currentTrim

    fun clearTrim() {
        currentTrim = null
    }

    fun hasTrim(): Boolean = currentTrim != null && currentTrim!!.isTrimmed
}