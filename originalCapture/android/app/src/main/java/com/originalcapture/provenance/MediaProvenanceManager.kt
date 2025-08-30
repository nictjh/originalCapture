package com.originalcapture.provenance

import com.originalcapture.provenance.model.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.time.Instant
import java.util.*

/**
 * Manager class for maintaining edit operation state with undo/redo capabilities
 * and C2PA-like JSON generation for media provenance tracking
 */
class MediaProvenanceManager(
    private val initialMetadata: MediaMetadata
) {
    // Core state management
    private var mediaProvenance: MediaProvenance = MediaProvenance(
        meta = initialMetadata,
        provenance = ProvenanceInfo()
    )

    // Edit chain with undo/redo support
    private val operationChain = mutableListOf<EditOperation>()
    private var currentIndex = -1 // Points to the last applied operation

    // Gson instance for JSON serialization
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .create()

    /**
     * Apply a new edit operation to the chain
     */
    fun applyOperation(operation: EditOperation): MediaProvenanceManager {
        // Remove any operations after current index (for new branch after undo)
        if (currentIndex < operationChain.size - 1) {
            operationChain.subList(currentIndex + 1, operationChain.size).clear()
        }

        // Add the new operation
        operationChain.add(operation)
        currentIndex = operationChain.size - 1

        // Update the media provenance
        updateMediaProvenance()

        return this
    }

    /**
     * Undo the last operation
     */
    fun undo(): EditOperation? {
        return if (canUndo()) {
            val undoneOperation = operationChain[currentIndex]
            currentIndex--
            updateMediaProvenance()
            undoneOperation
        } else null
    }

    /**
     * Redo the next operation
     */
    fun redo(): EditOperation? {
        return if (canRedo()) {
            currentIndex++
            val redoneOperation = operationChain[currentIndex]
            updateMediaProvenance()
            redoneOperation
        } else null
    }

    /**
     * Check if undo is possible
     */
    fun canUndo(): Boolean = currentIndex >= 0

    /**
     * Check if redo is possible
     */
    fun canRedo(): Boolean = currentIndex < operationChain.size - 1

    /**
     * Get current active operations (up to currentIndex)
     */
    fun getCurrentOperations(): List<EditOperation> =
        if (currentIndex >= 0) operationChain.take(currentIndex + 1) else emptyList()

    /**
     * Get all operations in the chain (including undone ones)
     */
    fun getAllOperations(): List<EditOperation> = operationChain.toList()

    /**
     * Get current position in the operation chain
     */
    fun getCurrentIndex(): Int = currentIndex

    /**
     * Get total number of operations in chain
     */
    fun getTotalOperations(): Int = operationChain.size

    /**
     * Clear all operations and reset to initial state
     */
    fun clear(): MediaProvenanceManager {
        operationChain.clear()
        currentIndex = -1
        updateMediaProvenance()
        return this
    }

    /**
     * Set export information
     */
    fun setExportInfo(exportInfo: ExportInfo): MediaProvenanceManager {
        mediaProvenance = mediaProvenance.setExportInfo(exportInfo)
        return this
    }

    /**
     * Update media metadata (useful for operations that change dimensions)
     */
    fun updateMetadata(newMetadata: MediaMetadata): MediaProvenanceManager {
        mediaProvenance = mediaProvenance.copy(meta = newMetadata)
        return this
    }

    /**
     * Get the current MediaProvenance object
     */
    fun getMediaProvenance(): MediaProvenance = mediaProvenance

    /**
     * Generate C2PA-like JSON representation
     */
    fun generateC2paJson(): String {
        val c2paObject = JsonObject().apply {
            addProperty("asset_id", mediaProvenance.assetId)
            addProperty("created_at", mediaProvenance.createdAt.toString())

            // Meta object
            add("meta", JsonObject().apply {
                addProperty("width", mediaProvenance.meta.width)
                addProperty("height", mediaProvenance.meta.height)
                mediaProvenance.meta.durationMs?.let { addProperty("duration_ms", it) }
                addProperty("format", mediaProvenance.meta.format)
                mediaProvenance.meta.frameRate?.let { addProperty("frame_rate", it) }
            })

            // Provenance object
            add("provenance", JsonObject().apply {
                addProperty("c2pa_present", mediaProvenance.provenance.c2paPresent)
                addProperty("signed", mediaProvenance.provenance.signed)
                addProperty("actions_count", getCurrentOperations().size)
            })

            // Operations array
            add("ops", gson.toJsonTree(getCurrentOperations().map { operation ->
                JsonObject().apply {
                    addProperty("t", operation.type)
                    addProperty("timestamp", operation.timestamp.toString())
                    add("p", gson.toJsonTree(operation.parameters))
                }
            }))

            // Export information
            mediaProvenance.export?.let { export ->
                add("export", JsonObject().apply {
                    addProperty("fmt", export.format)
                    export.quality?.let { addProperty("quality", it) }
                    export.bitrate?.let { addProperty("bitrate", it) }
                })
            }
        }

        return gson.toJson(c2paObject)
    }

    /**
     * Generate operation history with status indicators
     */
    fun getOperationHistory(): String {
        if (operationChain.isEmpty()) return "No operations in chain"

        val history = StringBuilder()
        operationChain.forEachIndexed { index, operation ->
            val status = when {
                index <= currentIndex -> "✓"
                else -> "○"
            }
            val riskIndicator = if (operation.type == "compose") " [HIGH RISK]" else ""
            history.append("$status ${index + 1}. ${operation.type}: ${operation::class.simpleName}$riskIndicator\n")
            history.append("    Timestamp: ${operation.timestamp}\n")
            history.append("    Parameters: ${operation.parameters}\n\n")
        }
        return history.toString()
    }

    /**
     * Get risk assessment of current operation chain
     */
    fun getRiskAssessment(): RiskAssessment {
        val currentOps = getCurrentOperations()
        val highRiskOps = currentOps.count { it.type == "compose" }
        val totalOps = currentOps.size

        return when {
            highRiskOps > 0 -> RiskAssessment.HIGH
            totalOps > 10 -> RiskAssessment.MEDIUM
            totalOps > 0 -> RiskAssessment.LOW
            else -> RiskAssessment.NONE
        }
    }

    /**
     * Get statistics about the current operation chain
     */
    fun getStatistics(): ChainStatistics {
        val currentOps = getCurrentOperations()
        val operationsByType = currentOps.groupBy { it.type }

        return ChainStatistics(
            totalOperations = currentOps.size,
            operationsByBucket = operationsByType.mapValues { it.value.size },
            highRiskOperations = operationsByType["compose"]?.size ?: 0,
            undoAvailable = canUndo(),
            redoAvailable = canRedo(),
            currentPosition = currentIndex + 1,
            totalInChain = operationChain.size,
            riskLevel = getRiskAssessment()
        )
    }

    /**
     * Create a snapshot of the current state
     */
    fun createSnapshot(): ProvenanceSnapshot {
        return ProvenanceSnapshot(
            assetId = mediaProvenance.assetId,
            timestamp = Instant.now(),
            operations = getCurrentOperations().toList(),
            metadata = mediaProvenance.meta.copy(),
            currentIndex = currentIndex,
            c2paJson = generateC2paJson()
        )
    }

    /**
     * Restore from a snapshot
     */
    fun restoreFromSnapshot(snapshot: ProvenanceSnapshot): MediaProvenanceManager {
        operationChain.clear()
        operationChain.addAll(snapshot.operations)
        currentIndex = snapshot.currentIndex
        updateMetadata(snapshot.metadata)
        updateMediaProvenance()
        return this
    }

    /**
     * Private helper to update the MediaProvenance object
     */
    private fun updateMediaProvenance() {
        val currentOps = getCurrentOperations()
        mediaProvenance = mediaProvenance.copy(
            ops = currentOps.toMutableList(),
            provenance = mediaProvenance.provenance.copy(
                actionsCount = currentOps.size
            )
        )
    }

    // Builder pattern for easy creation
    class Builder(private val metadata: MediaMetadata) {
        private val manager = MediaProvenanceManager(metadata)

        fun addOperation(operation: EditOperation) = apply { manager.applyOperation(operation) }
        fun setExportInfo(exportInfo: ExportInfo) = apply { manager.setExportInfo(exportInfo) }
        fun build(): MediaProvenanceManager = manager
    }

    companion object {
        /**
         * Create a new manager for image processing
         */
        fun forImage(width: Int, height: Int, format: String = "jpg"): MediaProvenanceManager {
            val metadata = MediaMetadata(width = width, height = height, format = format)
            return MediaProvenanceManager(metadata)
        }

        /**
         * Create a new manager for video processing
         */
        fun forVideo(
            width: Int,
            height: Int,
            durationMs: Long,
            frameRate: Double = 30.0,
            format: String = "mp4"
        ): MediaProvenanceManager {
            val metadata = MediaMetadata(
                width = width,
                height = height,
                durationMs = durationMs,
                frameRate = frameRate,
                format = format
            )
            return MediaProvenanceManager(metadata)
        }
    }
}

// Supporting data classes
enum class RiskAssessment {
    NONE, LOW, MEDIUM, HIGH
}

data class ChainStatistics(
    val totalOperations: Int,
    val operationsByBucket: Map<String, Int>,
    val highRiskOperations: Int,
    val undoAvailable: Boolean,
    val redoAvailable: Boolean,
    val currentPosition: Int,
    val totalInChain: Int,
    val riskLevel: RiskAssessment
)

data class ProvenanceSnapshot(
    val assetId: String,
    val timestamp: Instant,
    val operations: List<EditOperation>,
    val metadata: MediaMetadata,
    val currentIndex: Int,
    val c2paJson: String
)

// Extension functions for easier operation creation
object OperationFactory {
    fun crop(x: Int, y: Int, width: Int, height: Int) = CropOperation(x, y, width, height)
    fun rotate(degrees: Float, flipHorizontal: Boolean = false, flipVertical: Boolean = false) =
        RotateOperation(degrees, flipHorizontal, flipVertical)
    fun scale(width: Int, height: Int, maintainAspectRatio: Boolean = true) =
        ScaleOperation(width, height, maintainAspectRatio)
    fun trim(startMs: Long, endMs: Long) = TrimOperation(startMs, endMs)
    fun speed(factor: Float) = SpeedOperation(factor)

    fun brightness(value: Int) = BrightnessOperation(value)
    fun contrast(value: Int) = ContrastOperation(value)
    fun saturation(value: Int) = SaturationOperation(value)
    fun hue(value: Int) = HueOperation(value)
    fun gamma(value: Float) = GammaOperation(value)

    fun lut(name: String, intensity: Float = 1.0f) = LutOperation(name, intensity)
    fun blackWhite(intensity: Float = 1.0f) = BlackWhiteOperation(intensity)
    fun vintage(style: String, intensity: Float = 1.0f) = VintageOperation(style, intensity)
    fun cinematic(style: String, intensity: Float = 1.0f) = CinematicOperation(style, intensity)

    fun textOverlay(text: String, x: Int, y: Int, fontSize: Int, color: String, fontFamily: String? = null) =
        TextOverlayOperation(text, x, y, fontSize, color, fontFamily)
    fun captions(captions: Map<Long, String>, style: String? = null) =
        CaptionsOperation(captions, style)
    fun watermark(imagePath: String? = null, text: String? = null, position: String, opacity: Float) =
        WatermarkOperation(imagePath, text, position, opacity)

    fun gaussianBlur(areas: List<BlurArea>, radius: Float) = GaussianBlurOperation(areas, radius)
    fun pixelate(areas: List<BlurArea>, blockSize: Int) = PixelateOperation(areas, blockSize)

    fun splice(sourceMedia: String, startMs: Long, endMs: Long, position: Long) =
        SpliceOperation(sourceMedia, startMs, endMs, position)
    fun inpaint(areas: List<BlurArea>, method: String) = InpaintOperation(areas, method)
    fun backgroundReplace(newBackground: String, method: String) =
        BackgroundReplaceOperation(newBackground, method)
}

// Usage examples and testing
fun main() {
    println("=== Media Provenance Manager Demo ===\n")

    // Create a manager for an image
    val imageManager = MediaProvenanceManager.forImage(1080, 1440, "jpg")

    // Apply a series of operations
    imageManager
        .applyOperation(OperationFactory.crop(20, 30, 800, 1000))
        .applyOperation(OperationFactory.brightness(10))
        .applyOperation(OperationFactory.contrast(-5))
        .applyOperation(OperationFactory.saturation(8))
        .applyOperation(OperationFactory.gaussianBlur(
            listOf(BlurArea(100, 100, 200, 200, 6.5f)),
            5.0f
        ))
        .setExportInfo(ExportInfo("jpg", 88))

    // Display the C2PA JSON
    println("C2PA JSON Output:")
    println(imageManager.generateC2paJson())
    println("\n" + "=".repeat(80) + "\n")

    // Show operation history
    println("Operation History:")
    println(imageManager.getOperationHistory())

    // Show statistics
    println("Statistics:")
    val stats = imageManager.getStatistics()
    println("Total Operations: ${stats.totalOperations}")
    println("Risk Level: ${stats.riskLevel}")
    println("Operations by Bucket: ${stats.operationsByBucket}")
    println("Can Undo: ${stats.undoAvailable}, Can Redo: ${stats.redoAvailable}")
    println("\n" + "=".repeat(80) + "\n")

    // Test undo/redo
    println("Testing Undo/Redo:")
    println("Undoing last operation...")
    val undone = imageManager.undo()
    println("Undone: ${undone?.let { it::class.simpleName }}")
    println("Current operations count: ${imageManager.getCurrentOperations().size}")

    println("\nRedoing operation...")
    val redone = imageManager.redo()
    println("Redone: ${redone?.let { it::class.simpleName }}")
    println("Current operations count: ${imageManager.getCurrentOperations().size}")

    println("\n" + "=".repeat(80) + "\n")

    // Test high-risk operations
    println("Testing High-Risk Operations:")
    val videoManager = MediaProvenanceManager.forVideo(1920, 1080, 60000, 24.0)

    videoManager
        .applyOperation(OperationFactory.trim(5000, 55000))
        .applyOperation(OperationFactory.backgroundReplace("new_background.jpg", "ai"))
        .applyOperation(OperationFactory.splice("other_video.mp4", 10000, 20000, 30000))
        .setExportInfo(ExportInfo("mp4", bitrate = 5000))

    println("High-Risk Video C2PA JSON:")
    println(videoManager.generateC2paJson())

    val videoStats = videoManager.getStatistics()
    println("\nVideo Statistics:")
    println("Risk Level: ${videoStats.riskLevel}")
    println("High-Risk Operations: ${videoStats.highRiskOperations}")

    // Create and restore from snapshot
    println("\n" + "=".repeat(80) + "\n")
    println("Testing Snapshot/Restore:")
    val snapshot = imageManager.createSnapshot()
    println("Created snapshot at: ${snapshot.timestamp}")

    imageManager.clear()
    println("Cleared manager - operations count: ${imageManager.getCurrentOperations().size}")

    imageManager.restoreFromSnapshot(snapshot)
    println("Restored from snapshot - operations count: ${imageManager.getCurrentOperations().size}")
}