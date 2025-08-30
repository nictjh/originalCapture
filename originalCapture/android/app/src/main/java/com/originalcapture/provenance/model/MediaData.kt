package com.originalcapture.provenance.model

import java.util.*
import java.time.Instant

// Base data classes
data class MediaProvenance(
    val assetId: String = UUID.randomUUID().toString(),
    val createdAt: Instant = Instant.now(),
    val meta: MediaMetadata,
    val provenance: ProvenanceInfo,
    val ops: MutableList<EditOperation> = mutableListOf(),
    val export: ExportInfo? = null
) {
    // Helper function to add an operation and return a new instance
    fun addOperation(operation: EditOperation): MediaProvenance {
        val newOps = ops.toMutableList().apply { add(operation) }
        val newProvenance = provenance.copy(actionsCount = newOps.size)
        return this.copy(
            ops = newOps,
            provenance = newProvenance
        )
    }

    // Helper function to remove an operation and return a new instance
    fun removeOperation(operation: EditOperation): MediaProvenance {
        val newOps = ops.toMutableList().apply { remove(operation) }
        val newProvenance = provenance.copy(actionsCount = newOps.size)
        return this.copy(
            ops = newOps,
            provenance = newProvenance
        )
    }

    // Helper function to set export info
    fun setExportInfo(exportInfo: ExportInfo): MediaProvenance {
        return this.copy(export = exportInfo)
    }
}

data class MediaMetadata(
    val width: Int,
    val height: Int,
    val durationMs: Long? = null,
    val format: String,
    val frameRate: Double? = null
)

data class ProvenanceInfo(
    val c2paPresent: Boolean = true,
    val signed: Boolean = true,
    val actionsCount: Int = 0
)

data class ExportInfo(
    val format: String,
    val quality: Int? = null,
    val bitrate: Int? = null
)

// Edit Operations
sealed class EditOperation(
    open val type: String,
    open val timestamp: Instant = Instant.now(),
    open val parameters: Map<String, Any>
)

// Transform operations
data class CropOperation(
    override val type: String = "transform",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) : EditOperation(type, timestamp, parameters) {
    constructor(x: Int, y: Int, width: Int, height: Int) : this(
        type = "transform",
        timestamp = Instant.now(),
        parameters = mapOf("crop" to listOf(x, y, width, height)),
        x = x,
        y = y,
        width = width,
        height = height
    )
}

data class RotateOperation(
    override val type: String = "transform",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val degrees: Float,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false
) : EditOperation(type, timestamp, parameters) {
    constructor(degrees: Float, flipHorizontal: Boolean = false, flipVertical: Boolean = false) : this(
        type = "transform",
        timestamp = Instant.now(),
        parameters = mapOf(
            "rotate" to degrees,
            "flip_horizontal" to flipHorizontal,
            "flip_vertical" to flipVertical
        ),
        degrees = degrees,
        flipHorizontal = flipHorizontal,
        flipVertical = flipVertical
    )
}

data class ScaleOperation(
    override val type: String = "transform",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val width: Int,
    val height: Int,
    val maintainAspectRatio: Boolean = true
) : EditOperation(type, timestamp, parameters) {
    constructor(width: Int, height: Int, maintainAspectRatio: Boolean = true) : this(
        type = "transform",
        timestamp = Instant.now(),
        parameters = mapOf(
            "scale" to listOf(width, height),
            "maintain_aspect_ratio" to maintainAspectRatio
        ),
        width = width,
        height = height,
        maintainAspectRatio = maintainAspectRatio
    )
}

data class TrimOperation(
    override val type: String = "transform",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val startMs: Long,
    val endMs: Long
) : EditOperation(type, timestamp, parameters) {
    constructor(startMs: Long, endMs: Long) : this(
        type = "transform",
        timestamp = Instant.now(),
        parameters = mapOf("trim" to listOf(startMs, endMs)),
        startMs = startMs,
        endMs = endMs
    )
}

data class SpeedOperation(
    override val type: String = "transform",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val speedFactor: Float
) : EditOperation(type, timestamp, parameters) {
    constructor(speedFactor: Float) : this(
        type = "transform",
        timestamp = Instant.now(),
        parameters = mapOf("speed" to speedFactor),
        speedFactor = speedFactor
    )
}

// Adjust operations
data class BrightnessOperation(
    override val type: String = "adjust",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val value: Int
) : EditOperation(type, timestamp, parameters) {
    constructor(value: Int) : this(
        type = "adjust",
        timestamp = Instant.now(),
        parameters = mapOf("brightness" to value),
        value = value
    )
}

data class ContrastOperation(
    override val type: String = "adjust",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val value: Int
) : EditOperation(type, timestamp, parameters) {
    constructor(value: Int) : this(
        type = "adjust",
        timestamp = Instant.now(),
        parameters = mapOf("contrast" to value),
        value = value
    )
}

data class SaturationOperation(
    override val type: String = "adjust",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val value: Int
) : EditOperation(type, timestamp, parameters) {
    constructor(value: Int) : this(
        type = "adjust",
        timestamp = Instant.now(),
        parameters = mapOf("saturation" to value),
        value = value
    )
}

data class HueOperation(
    override val type: String = "adjust",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val value: Int
) : EditOperation(type, timestamp, parameters) {
    constructor(value: Int) : this(
        type = "adjust",
        timestamp = Instant.now(),
        parameters = mapOf("hue" to value),
        value = value
    )
}

data class GammaOperation(
    override val type: String = "adjust",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val value: Float
) : EditOperation(type, timestamp, parameters) {
    constructor(value: Float) : this(
        type = "adjust",
        timestamp = Instant.now(),
        parameters = mapOf("gamma" to value),
        value = value
    )
}

// Filter operations
data class LutOperation(
    override val type: String = "filter",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val lutName: String,
    val intensity: Float
) : EditOperation(type, timestamp, parameters) {
    constructor(lutName: String, intensity: Float) : this(
        type = "filter",
        timestamp = Instant.now(),
        parameters = mapOf(
            "lut" to lutName,
            "intensity" to intensity
        ),
        lutName = lutName,
        intensity = intensity
    )
}

data class BlackWhiteOperation(
    override val type: String = "filter",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val intensity: Float
) : EditOperation(type, timestamp, parameters) {
    constructor(intensity: Float = 1.0f) : this(
        type = "filter",
        timestamp = Instant.now(),
        parameters = mapOf("bw" to intensity),
        intensity = intensity
    )
}

data class VintageOperation(
    override val type: String = "filter",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val style: String,
    val intensity: Float
) : EditOperation(type, timestamp, parameters) {
    constructor(style: String, intensity: Float = 1.0f) : this(
        type = "filter",
        timestamp = Instant.now(),
        parameters = mapOf(
            "vintage" to style,
            "intensity" to intensity
        ),
        style = style,
        intensity = intensity
    )
}

data class CinematicOperation(
    override val type: String = "filter",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val style: String,
    val intensity: Float
) : EditOperation(type, timestamp, parameters) {
    constructor(style: String, intensity: Float = 1.0f) : this(
        type = "filter",
        timestamp = Instant.now(),
        parameters = mapOf(
            "cinematic" to style,
            "intensity" to intensity
        ),
        style = style,
        intensity = intensity
    )
}

// Overlay operations
data class TextOverlayOperation(
    override val type: String = "overlay",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val text: String,
    val x: Int,
    val y: Int,
    val fontSize: Int,
    val color: String,
    val fontFamily: String? = null
) : EditOperation(type, timestamp, parameters) {
    constructor(text: String, x: Int, y: Int, fontSize: Int, color: String, fontFamily: String? = null) : this(
        type = "overlay",
        timestamp = Instant.now(),
        parameters = mapOf<String, Any>(
            "text" to text,
            "position" to listOf(x, y),
            "font_size" to fontSize,
            "color" to color,
            "font_family" to fontFamily as Any
        ),
        text = text,
        x = x,
        y = y,
        fontSize = fontSize,
        color = color,
        fontFamily = fontFamily
    )
}

data class CaptionsOperation(
    override val type: String = "overlay",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val captions: Map<Long, String>,
    val style: String? = null
) : EditOperation(type, timestamp, parameters) {
    constructor(captions: Map<Long, String>, style: String? = null) : this(
        type = "overlay",
        timestamp = Instant.now(),
        parameters = mapOf(
            "captions" to captions,
            "style" to (style ?: "")
        ),
        captions = captions,
        style = style
    )
}

data class WatermarkOperation(
    override val type: String = "overlay",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val imagePath: String?,
    val text: String?,
    val position: String,
    val opacity: Float
) : EditOperation(type, timestamp, parameters) {
    constructor(imagePath: String? = null, text: String? = null, position: String, opacity: Float) : this(
        type = "overlay",
        timestamp = Instant.now(),
        parameters = mapOf(
            "image_path" to (imagePath ?: ""),
            "text" to (text ?: ""),
            "position" to position,
            "opacity" to opacity
        ),
        imagePath = imagePath,
        text = text,
        position = position,
        opacity = opacity
    )
}

// Privacy blur operations
data class GaussianBlurOperation(
    override val type: String = "privacy_blur",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val areas: List<BlurArea>,
    val radius: Float
) : EditOperation(type, timestamp, parameters) {
    constructor(areas: List<BlurArea>, radius: Float) : this(
        type = "privacy_blur",
        timestamp = Instant.now(),
        parameters = mapOf(
            "method" to "gaussian",
            "areas" to areas.map { it.toMap() },
            "radius" to radius
        ),
        areas = areas,
        radius = radius
    )
}

data class PixelateOperation(
    override val type: String = "privacy_blur",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val areas: List<BlurArea>,
    val blockSize: Int
) : EditOperation(type, timestamp, parameters) {
    constructor(areas: List<BlurArea>, blockSize: Int) : this(
        type = "privacy_blur",
        timestamp = Instant.now(),
        parameters = mapOf(
            "method" to "pixelate",
            "areas" to areas.map { it.toMap() },
            "block_size" to blockSize
        ),
        areas = areas,
        blockSize = blockSize
    )
}

data class BlurArea(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val areaPct: Float
) {
    fun toMap(): Map<String, Any> = mapOf(
        "x" to x, "y" to y, "width" to width, "height" to height, "area_pct" to areaPct
    )
}

// Compose operations (HIGH RISK)
data class SpliceOperation(
    override val type: String = "compose",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val sourceMedia: String,
    val startMs: Long,
    val endMs: Long,
    val position: Long
) : EditOperation(type, timestamp, parameters) {
    constructor(sourceMedia: String, startMs: Long, endMs: Long, position: Long) : this(
        type = "compose",
        timestamp = Instant.now(),
        parameters = mapOf(
            "source_media" to sourceMedia,
            "source_range" to listOf(startMs, endMs),
            "position" to position
        ),
        sourceMedia = sourceMedia,
        startMs = startMs,
        endMs = endMs,
        position = position
    )
}

data class InpaintOperation(
    override val type: String = "compose",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val areas: List<BlurArea>,
    val method: String
) : EditOperation(type, timestamp, parameters) {
    constructor(areas: List<BlurArea>, method: String) : this(
        type = "compose",
        timestamp = Instant.now(),
        parameters = mapOf(
            "method" to method,
            "areas" to areas.map { it.toMap() }
        ),
        areas = areas,
        method = method
    )
}

data class BackgroundReplaceOperation(
    override val type: String = "compose",
    override val timestamp: Instant = Instant.now(),
    override val parameters: Map<String, Any>,
    val newBackground: String,
    val method: String
) : EditOperation(type, timestamp, parameters) {
    constructor(newBackground: String, method: String) : this(
        type = "compose",
        timestamp = Instant.now(),
        parameters = mapOf(
            "new_background" to newBackground,
            "method" to method
        ),
        newBackground = newBackground,
        method = method
    )
}