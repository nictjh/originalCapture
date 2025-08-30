package com.originalcapture

import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import kotlinx.coroutines.*
import android.util.Log
import java.io.File
import kotlin.coroutines.CoroutineContext

@ReactModule(name = AttestationBridge.NAME)
class AttestationBridge(private val reactCxt: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactCxt), CoroutineScope {

    companion object { const val NAME = "AttestationBridge" }

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = Dispatchers.Main + job
    override fun getName(): String = NAME

    /**
     * JS calls: saveFromEditor(editedPath, baseUrl)
     * - Runs pipeline (generates sidecar/receipt for the edited file)
     * - Sends edited file + sidecar to server
     * - Resolves with { ok, mediaPath, receiptPath, message, serverCode, serverBody }
     */
    @ReactMethod
    fun saveFromEditor(editedPath: String, baseUrl: String, promise: Promise) {
        launch {
            try {
                // Normalize path (strip file:// if present)
                val normalized = editedPath.removePrefix("file://")
                val editedFile = File(normalized)
                if (!editedFile.exists()) {
                    promise.reject("FILE_NOT_FOUND", "Edited file not found: $editedPath")
                    return@launch
                }

                // 1) Run the pipeline to generate sidecar for THIS file
                val pipeline = withContext(Dispatchers.IO) {
                    AttestationPipeline.run(reactCxt, editedFile)
                }
                if (!pipeline.ok) {
                    promise.reject("PIPELINE_FAILED", pipeline.message ?: "Unknown pipeline error")
                    return@launch
                }
                val sidecarPath = pipeline.sidecarPath
                if (sidecarPath.isNullOrBlank()) {
                    promise.reject("SIDE_CAR_MISSING", "Pipeline did not produce a receipt/sidecar")
                    return@launch
                }

                // Choose the media path to send:
                // - If pipeline returns a (possibly same) mediaPath, prefer it
                // - Else, send the edited file path we were given
                val mediaPathToSend = pipeline.mediaPath?.takeIf { it.isNotBlank() }
                    ?: editedFile.absolutePath

                // 2) Send to server
                val resp = withContext(Dispatchers.IO) {
                    AttestationClient.sendAttestationToServer(
                        baseUrl = baseUrl,
                        mediaPath = mediaPathToSend,
                        sidecarPath = sidecarPath
                    )
                }

                // 3) Return everything to JS
                val map = Arguments.createMap().apply {
                    putBoolean("ok", pipeline.ok)
                    putString("mediaPath", mediaPathToSend)
                    putString("receiptPath", sidecarPath)
                    putString("message", pipeline.message)
                    putInt("serverCode", resp.code)
                    putString("serverBody", resp.body)
                }
                promise.resolve(map)
            } catch (e: Exception) {
                Log.e("AttestationBridge", "saveFromEditor failed", e)
                promise.reject("SAVE_FROM_EDITOR_FAILED", e)
            }
        }
    }

    override fun onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy()
        job.cancel()
    }
}
