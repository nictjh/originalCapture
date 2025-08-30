package com.originalcapture

import android.content.Context
import java.io.File

data class PipelineResult(
    val ok: Boolean,
    val mediaPath: String?,
    val sidecarPath: String?,
    val message: String?
)

object AttestationPipeline {
    /** Runs your existing pipeline that creates the sidecar/receipt. */
    fun run(context: Context, mediaFile: File): PipelineResult {
        val res = AttestationPoc.run(context, mediaFile) // <- your existing call
        return PipelineResult(
            ok = res.ok,
            mediaPath = res.mediaPath,
            sidecarPath = res.sidecarPath,
            message = res.message
        )
    }
}