package com.originalcapture.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.originalcapture.AttestationPoc
import com.originalcapture.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class NativeCameraActivity : ComponentActivity() {

  private lateinit var previewView: PreviewView
  private lateinit var captureBtn: Button

  private lateinit var imagePreview: ImageView
  private lateinit var saveBtn: Button
  private lateinit var editBtn: Button
  private lateinit var retakeBtn : Button

  private var imageCapture: ImageCapture? = null
  private var lastCapturedFile: File? = null

  private val requestCamera = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) startCamera() else finishWithError("CAMERA permission denied")
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_native_camera)

    previewView = findViewById(R.id.previewView)
    captureBtn = findViewById(R.id.captureBtn)

    imagePreview = findViewById(R.id.imagePreview)
    saveBtn = findViewById(R.id.saveBtn)
    editBtn = findViewById(R.id.editBtn)
    retakeBtn = findViewById(R.id.retakeBtn)

    captureBtn.setOnClickListener { takePhoto() }
    saveBtn.setOnClickListener { onSave() }
    editBtn.setOnClickListener { onEdit() }
    retakeBtn.setOnClickListener { onRetake() }

    ensurePermissionAndStart()
  }

  private fun ensurePermissionAndStart() {
    val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
      PackageManager.PERMISSION_GRANTED
    if (ok) startCamera() else requestCamera.launch(Manifest.permission.CAMERA)
  }

  private fun startCamera() {
    val providerFuture = ProcessCameraProvider.getInstance(this)
    providerFuture.addListener({
      val cameraProvider = providerFuture.get()
      val preview = Preview.Builder().build().also {
        it.setSurfaceProvider(previewView.surfaceProvider)
      }
      imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()

      try {
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
          this,
          CameraSelector.DEFAULT_BACK_CAMERA,
          preview,
          imageCapture
        )
      } catch (e: Exception) {
        finishWithError("Camera bind failed: ${e.message}")
      }
    }, ContextCompat.getMainExecutor(this))
  }

  private fun takePhoto() {
    val ic = imageCapture ?: return

    // Delete previous capture if any
    deleteLastCapture()

    val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
      .format(System.currentTimeMillis()) + ".jpg"
    val file = File(filesDir, name)

    val opts = ImageCapture.OutputFileOptions.Builder(file).build()
    ic.takePicture(
      opts,
      ContextCompat.getMainExecutor(this),
      object : ImageCapture.OnImageSavedCallback {
        override fun onError(exc: ImageCaptureException) {
          finishWithError("Capture failed: ${exc.message}")
        }
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
          lastCapturedFile = file
          showPreviewUI(file)
        }
      }
    )
  }

  /** Switch UI from live camera to post-capture choices */
  private fun showPreviewUI(file: File) {
    // Hide camera widgets
    previewView.visibility = View.GONE
    captureBtn.visibility = View.GONE

    // Show preview widgets
    imagePreview.visibility = View.VISIBLE
    saveBtn.visibility = View.VISIBLE
    editBtn.visibility = View.VISIBLE
    retakeBtn.visibility = View.VISIBLE

    imagePreview.setImageURI(Uri.fromFile(file))
  }

  /** Switch back to live camera preview */
  private fun showCameraUI() {
    // Hide preview widgets
    imagePreview.visibility = View.GONE
    saveBtn.visibility = View.GONE
    editBtn.visibility = View.GONE
    retakeBtn.visibility = View.GONE

    // Show camera widgets
    previewView.visibility = View.VISIBLE
    captureBtn.visibility = View.VISIBLE
  }

  /** Save = run attestation + return to RN */
  private fun onSave() {
    val file = lastCapturedFile ?: run {
      finishWithError("No file to save")
      return
    }
    val res = AttestationPoc.run(this, file)
    val data = Intent().apply {
      putExtra("action", "save")
      putExtra("ok", res.ok)
      putExtra("mediaPath", res.mediaPath)
      putExtra("receiptPath", res.sidecarPath)
      putExtra("message", res.message)
    }
    setResult(RESULT_OK, data)
    finish()
  }

  /** Edit = skip attestation, just return the media path so RN can open editor */
  private fun onEdit() {
    val file = lastCapturedFile ?: run {
      finishWithError("No file to edit")
      return
    }
    val data = Intent().apply {
      putExtra("action", "edit")
      putExtra("ok", true)
      putExtra("mediaPath", file.absolutePath)
      // no receipt yet
    }
    setResult(RESULT_OK, data)
    finish()
  }

  /** Retake = discard the previous shot and go back to camera */
  private fun onRetake() {
    deleteLastCapture()
    showCameraUI()
  }

  /** Deletes lastCapturedFile and its sidecar if present */
  private fun deleteLastCapture() {
    val f = lastCapturedFile ?: return
    try {
      if (f.exists()) f.delete()
      // Delete sidecar too if it was produced (unlikely before Save, but safe)
      val sidecar = File(f.parentFile ?: filesDir, f.name + ".sig.json")
      if (sidecar.exists()) sidecar.delete()
    } catch (e: Exception) {
      Log.w("NativeCameraActivity", "Failed deleting previous capture: ${e.message}")
    } finally {
      lastCapturedFile = null
    }
  }

  /** If user backs out without saving/editing, clean up temp file */
  override fun onBackPressed() {
    deleteLastCapture()
    super.onBackPressed()
  }


  private fun finishWithError(msg: String) {
    Log.e("NativeCameraActivity", msg)
    deleteLastCapture()
    val data = Intent().apply {
      putExtra("action", "error")
      putExtra("ok", false)
      putExtra("message", msg)
    }
    setResult(RESULT_OK, data)
    finish()
  }
}
