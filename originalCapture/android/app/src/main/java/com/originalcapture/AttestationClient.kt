package com.originalcapture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLConnection
import java.util.concurrent.TimeUnit

object AttestationClient {

    data class VerifyResponse(
        val code: Int,
        val body: String
    )

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun parseSidecar(path: String): Triple<String, String, List<String>> {
        val text = File(path).readText(Charsets.UTF_8)
        val obj = JSONObject(text)

        val payloadCanonical = obj.getString("payload_canonical")
        val sigB64 = obj.getString("sig_b64")

        val certsJson = obj.getJSONArray("x5c_der_b64")
        val certs = MutableList(certsJson.length()) { i -> certsJson.getString(i) }
        return Triple(payloadCanonical, sigB64, certs)
    }

    private fun guessMimeType(file: File): String {
        return URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
    }

    /**
     * Sends multipart/form-data to POST /verify
     */
    suspend fun sendAttestationToServer(
        baseUrl: String,
        mediaPath: String,
        sidecarPath: String
    ): VerifyResponse = withContext(Dispatchers.IO) {
        val mediaFile = File(mediaPath)
        require(mediaFile.exists()) { "Media file not found at $mediaPath" }

        val (payloadCanonical, sigB64, x5cList) = parseSidecar(sidecarPath)

        val mediaBody = mediaFile.asRequestBody(guessMimeType(mediaFile).toMediaTypeOrNull())

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("payload_canonical", payloadCanonical)
            .addFormDataPart("sig_b64", sigB64)
            .apply { x5cList.forEach { addFormDataPart("x5c_der_b64", it) } }
            .addFormDataPart("media", mediaFile.name, mediaBody)
            .build()

        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/verify")
            .post(multipart)
            .build()

        httpClient.newCall(req).execute().use { resp ->
            VerifyResponse(resp.code, resp.body?.string().orEmpty())
        }
    }
}
