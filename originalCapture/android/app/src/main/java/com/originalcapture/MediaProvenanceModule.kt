package com.originalcapture

import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.originalcapture.provenance.MediaProvenanceManager
import com.originalcapture.provenance.OperationFactory
import com.originalcapture.provenance.model.ExportInfo
import com.originalcapture.provenance.model.BlurArea
import com.originalcapture.provenance.model.MediaMetadata
import org.json.JSONObject

@ReactModule(name = MediaProvenanceModule.NAME)
class MediaProvenanceModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "MediaProvenance"
        private const val TAG = "C2PA_DEBUG"
    }

    // Store manager instances by ID for multi-instance support
    private val managers = mutableMapOf<String, MediaProvenanceManager>()
    private var nextManagerId = 0

    override fun getName(): String = NAME

    /**
     * Create a new MediaProvenanceManager for an image
     */
    @ReactMethod
    fun createImageManager(
        width: Int,
        height: Int,
        format: String,
        promise: Promise
    ) {
        try {
            val managerId = "manager_${nextManagerId++}"
            val manager = MediaProvenanceManager.forImage(width, height, format)
            managers[managerId] = manager

            val result = WritableNativeMap().apply {
                putString("managerId", managerId)
                putBoolean("success", true)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("CREATE_MANAGER_ERROR", e.message, e)
        }
    }

    /**
     * Create a new MediaProvenanceManager for a video
     */
    @ReactMethod
    fun createVideoManager(
        width: Int,
        height: Int,
        durationMs: Double,
        frameRate: Double,
        format: String,
        promise: Promise
    ) {
        try {
            val managerId = "manager_${nextManagerId++}"
            val manager = MediaProvenanceManager.forVideo(
                width, height, durationMs.toLong(), frameRate, format
            )
            managers[managerId] = manager

            val result = WritableNativeMap().apply {
                putString("managerId", managerId)
                putBoolean("success", true)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("CREATE_MANAGER_ERROR", e.message, e)
        }
    }

    /**
     * Apply a crop operation
     */
    @ReactMethod
    fun applyCrop(
        managerId: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        promise: Promise
    ) {
        try {
            val manager = managers[managerId]
                ?: return promise.reject("INVALID_MANAGER", "Manager not found")

            manager.applyOperation(OperationFactory.crop(x, y, width, height))
            promise.resolve(createSuccessResponse())
        } catch (e: Exception) {
            promise.reject("OPERATION_ERROR", e.message, e)
        }
    }

    /**
     * Apply a rotate operation
     */
    /**
     * Apply a rotate operation with C2PA logging
     */
    @ReactMethod
    fun applyRotate(
        managerId: String,
        degrees: Double,
        flipHorizontal: Boolean,
        flipVertical: Boolean,
        promise: Promise
    ) {
        try {
            val manager = managers[managerId]
                ?: return promise.reject("INVALID_MANAGER", "Manager not found")

            // Apply the operation
            manager.applyOperation(OperationFactory.rotate(degrees.toFloat(), flipHorizontal, flipVertical))

            // Generate and log C2PA JSON after operation
            val c2paJson = manager.generateC2paJson()
            Log.d("C2PA_DEBUG", "C2PA after rotate: $c2paJson")

            promise.resolve(createSuccessResponse())
        } catch (e: Exception) {
            promise.reject("OPERATION_ERROR", e.message, e)
        }
    }

    /**
     * Apply brightness adjustment
     */
    @ReactMethod
    fun applyBrightness(
        managerId: String,
        value: Int,
        promise: Promise
    ) {
        try {
            val manager = managers[managerId]
                ?: return promise.reject("INVALID_MANAGER", "Manager not found")

            manager.applyOperation(OperationFactory.brightness(value))
            promise.resolve(createSuccessResponse())
        } catch (e: Exception) {
            promise.reject("OPERATION_ERROR", e.message, e)
        }
    }

    /**
     * Apply contrast adjustment
     */
    @ReactMethod
    fun applyContrast(
        managerId: String,
        value: Int,
        promise: Promise
    ) {
        try {
            val manager = managers[managerId]
                ?: return promise.reject("INVALID_MANAGER", "Manager not found")

            manager.applyOperation(OperationFactory.contrast(value))
            promise.resolve(createSuccessResponse())
        } catch (e: Exception) {
            promise.reject("OPERATION_ERROR", e.message, e)
        }
    }

    /**
     * Apply saturation adjustment
     */
    @ReactMethod
    fun applySaturation(
        managerId: String,
        value: Int,
        promise: Promise
    ) {
        try {
            val manager = managers[managerId]
                ?: return promise.reject("INVALID_MANAGER", "Manager not found")

            manager.applyOperation(OperationFactory.saturation(value))
            promise.resolve(createSuccessResponse())
        } catch (e: Exception) {
            promise.reject("OPERATION_ERROR", e.message, e)
        }
    }

    /**
     * Apply Gaussian blur to specific areas
     */
    @ReactMethod
    fun applyGaussianBlur(
        managerId: String,
        areas: ReadableArray,
        radius: Double,
        promise: Promise
    ) {
        try {
            val manager = managers[managerId]
                ?: return promise.reject("INVALID_MANAGER", "Manager not found")

            val blurAreas = mutableListOf<BlurArea>()
            for (i in 0 until areas.size()) {
                val area = areas.getMap(i)!!
                // Note: BlurArea's last parameter is 'areaPct' based on the model class
                blurAreas.add(
                    BlurArea(
                        area.getInt("x"),
                        area.getInt("y"),
                        area.getInt("width"),
                        area.getInt("height"),
                        area.getDouble("strength").toFloat()
                    )
                )
            }

            manager.applyOperation(OperationFactory.gaussianBlur(blurAreas, radius.toFloat()))
            promise.resolve(createSuccessResponse())
        } catch (e: Exception) {
            promise.reject("OPERATION_ERROR", e.message, e)
        }
    }

    /**
     * Apply text overlay
     */
    @ReactMethod
    fun applyTextOverlay(
        managerId: String,
        text: String,
        x: Int,
        y: Int,
        fontSize: Int,
        color: String,
        fontFamily: String?,
        promise: Promise
    ) {
        try {
            val manager = managers[managerId]
                ?: return promise.reject("INVALID_MANAGER", "Manager not found")

            manager.applyOperation(
                OperationFactory.textOverlay(text, x, y, fontSize, color, fontFamily)
            )
            promise.resolve(createSuccessResponse())
        } catch (e: Exception) {
            promise.reject("OPERATION_ERROR", e.message, e)
        }
    }

    /**
     * Apply pixelation to specific areas
     */
    @ReactMethod
    fun applyPixelate(
        managerId: String,
        areas: ReadableArray,
        blockSize: Int,
        promise: Promise
    ) {
        try {
            val manager = managers[managerId]
                ?: return promise.reject("INVALID_MANAGER", "Manager not found")

            val blurAreas = mutableListOf<BlurArea>()
            for (i in 0 until areas.size()) {
                val area = areas.getMap(i)!!
                blurAreas.add(
                    BlurArea(
                        area.getInt("x"),
                        area.getInt("y"),
                        area.getInt("width"),
                        area.getInt("height"),
                        area.getDouble("strength").toFloat()
                    )
                )
            }

            manager.applyOperation(OperationFactory.pixelate(blurAreas, blockSize))
            promise.resolve(createSuccessResponse())
        } catch (e: Exception) {
            promise.reject("OPERATION_ERROR", e.message, e)
        }
    }
    @ReactMethod
    fun applyWatermark(
        managerId: String,
        imagePath: String?,
        text: String?,
        position: String,
        opacity: Double,
        promise: Promise
    ) {
        try {
            val manager = managers[managerId]
                ?: return promise.reject("INVALID_MANAGER", "Manager not found")

            manager.applyOperation(
                OperationFactory.watermark(imagePath, text, position, opacity.toFloat())
            )
            promise.resolve(createSuccessResponse())
        } catch (e: Exception) {
            promise.reject("OPERATION_ERROR", e.message, e)
        }
    }

    /**
     * Apply video trim
     */
    @ReactMethod
    fun applyTrim(
        managerId: String,
        startMs: Double,
        endMs: Double,
        promise: Promise
    ) {
        try {
            val manager = managers[managerId]
                ?: return promise.reject("INVALID_MANAGER", "Manager not found")

            manager.applyOperation(OperationFactory.trim(startMs.toLong(), endMs.toLong()))
            promise.resolve(createSuccessResponse())
        } catch (e: Exception) {
            promise.reject("OPERATION_ERROR", e.message, e)
        }
    }

    /**
     * Apply background replacement (HIGH RISK)
     */
    @ReactMethod
    fun applyBackgroundReplace(
        managerId: String,
        newBackground: String,
        method: String,
        promise: Promise
    ) {
        try {
            val manager = managers[managerId]
                ?: return promise.reject("INVALID_MANAGER", "Manager not found")

            manager.applyOperation(OperationFactory.backgroundReplace(newBackground, method))
            promise.resolve(createSuccessResponse())
        } catch (e: Exception) {
            promise.reject("OPERATION_ERROR", e.message, e)
        }
    }

    /**
     * Undo last operation
     */
    @ReactMethod
    fun undo(managerId: String, promise: Promise) {
        try {
            val manager = managers[managerId]
                ?: return promise.reject("INVALID_MANAGER", "Manager not found")

            val undoneOp = manager.undo()
            val result = WritableNativeMap().apply {
                putBoolean("success", undoneOp != null)
                putString("operationType", undoneOp?.type)
                putBoolean("canUndo", manager.canUndo())
                putBoolean("canRedo", manager.canRedo())
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("UNDO_ERROR", e.message, e)
        }
    }

    /**
     * Redo operation
     */
    @ReactMethod
    fun redo(managerId: String, promise: Promise) {
        try {
            val manager = managers[managerId]
                ?: return promise.reject("INVALID_MANAGER", "Manager not found")

            val redoneOp = manager.redo()
            val result = WritableNativeMap().apply {
                putBoolean("success", redoneOp != null)
                putString("operationType", redoneOp?.type)
                putBoolean("canUndo", manager.canUndo())
                putBoolean("canRedo", manager.canRedo())
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("REDO_ERROR", e.message, e)
        }
    }

    /**
     * Set export information
     */
    @ReactMethod
    fun setExportInfo(
        managerId: String,
        format: String,
        quality: Int?,
        bitrate: Int?,
        promise: Promise
    ) {
        try {
            val manager = managers[managerId]
                ?: return promise.reject("INVALID_MANAGER", "Manager not found")

            manager.setExportInfo(ExportInfo(format, quality, bitrate))
            promise.resolve(createSuccessResponse())
        } catch (e: Exception) {
            promise.reject("EXPORT_ERROR", e.message, e)
        }
    }

    /**
     * Generate C2PA JSON with logging
     */
    @ReactMethod
    fun generateC2paJson(managerId: String, promise: Promise) {
        try {
            val manager = managers[managerId]
                ?: return promise.reject("INVALID_MANAGER", "Manager not found")

            val json = manager.generateC2paJson()
            logC2PAState("Manual C2PA generation", json)
            promise.resolve(json)
        } catch (e: Exception) {
            promise.reject("JSON_ERROR", e.message, e)
        }
    }

    /**
     * Get operation history
     */
    @ReactMethod
    fun getOperationHistory(managerId: String, promise: Promise) {
        try {
            val manager = managers[managerId]
                ?: return promise.reject("INVALID_MANAGER", "Manager not found")

            val history = manager.getOperationHistory()
            promise.resolve(history)
        } catch (e: Exception) {
            promise.reject("HISTORY_ERROR", e.message, e)
        }
    }

    /**
     * Get statistics
     */
    @ReactMethod
    fun getStatistics(managerId: String, promise: Promise) {
        try {
            val manager = managers[managerId]
                ?: return promise.reject("INVALID_MANAGER", "Manager not found")

            val stats = manager.getStatistics()
            val result = WritableNativeMap().apply {
                putInt("totalOperations", stats.totalOperations)
                putInt("highRiskOperations", stats.highRiskOperations)
                putString("riskLevel", stats.riskLevel.toString())
                putBoolean("undoAvailable", stats.undoAvailable)
                putBoolean("redoAvailable", stats.redoAvailable)
                putInt("currentPosition", stats.currentPosition)
                putInt("totalInChain", stats.totalInChain)

                // Operation counts by type
                val opCounts = WritableNativeMap()
                stats.operationsByBucket.forEach { (type, count) ->
                    opCounts.putInt(type, count)
                }
                putMap("operationsByType", opCounts)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("STATS_ERROR", e.message, e)
        }
    }

    /**
     * Clear all operations
     */
    @ReactMethod
    fun clearOperations(managerId: String, promise: Promise) {
        try {
            val manager = managers[managerId]
                ?: return promise.reject("INVALID_MANAGER", "Manager not found")

            manager.clear()
            promise.resolve(createSuccessResponse())
        } catch (e: Exception) {
            promise.reject("CLEAR_ERROR", e.message, e)
        }
    }

    /**
     * Destroy a manager instance
     */
    @ReactMethod
    fun destroyManager(managerId: String, promise: Promise) {
        try {
            val removed = managers.remove(managerId) != null
            promise.resolve(removed)
        } catch (e: Exception) {
            promise.reject("DESTROY_ERROR", e.message, e)
        }
    }

    /**
     * Get current operations list
     */
    @ReactMethod
    fun getCurrentOperations(managerId: String, promise: Promise) {
        try {
            val manager = managers[managerId]
                ?: return promise.reject("INVALID_MANAGER", "Manager not found")

            val operations = manager.getCurrentOperations()
            val opsArray = WritableNativeArray()

            operations.forEach { op ->
                val opMap = WritableNativeMap().apply {
                    putString("type", op.type)
                    putString("timestamp", op.timestamp.toString())
                    putString("className", op::class.simpleName)
                }
                opsArray.pushMap(opMap)
            }

            promise.resolve(opsArray)
        } catch (e: Exception) {
            promise.reject("OPERATIONS_ERROR", e.message, e)
        }
    }

    private fun createSuccessResponse(): WritableMap {
        return WritableNativeMap().apply {
            putBoolean("success", true)
        }
    }

    private fun logOperationCreation(operationName: String, details: Map<String, Any>) {
        Log.d(TAG, "Operation Created: $operationName")
        Log.d(TAG, "Operation Details: ${details.toJsonString()}")
    }

    private fun logC2PAState(context: String, c2paJson: String) {
        Log.d(TAG, "=== C2PA STATE: $context ===")
        Log.d(TAG, "C2PA JSON: $c2paJson")

        // Pretty print for better readability
        try {
            val jsonObject = JSONObject(c2paJson)
            val prettyJson = jsonObject.toString(2)
            Log.d(TAG, "Pretty C2PA JSON:\n$prettyJson")
        } catch (e: Exception) {
            Log.d(TAG, "Raw C2PA JSON: $c2paJson")
        }
        Log.d(TAG, "=== END C2PA STATE ===")
    }
}

// Extension function to convert Map to JSON string
private fun Map<String, Any>.toJsonString(): String {
    return try {
        JSONObject(this).toString()
    } catch (e: Exception) {
        this.toString()
    }
}