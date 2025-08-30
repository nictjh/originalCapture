package com.originalcapture.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import com.originalcapture.AttestationPoc
import com.originalcapture.R
import com.originalcapture.editor.SimpleTrimEditorActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

import androidx.camera.video.QualitySelector
import androidx.camera.video.Quality
import androidx.camera.video.FileOutputOptions

class NativeCameraActivity : ComponentActivity() {

  private lateinit var previewView: PreviewView
  private lateinit var captureBtn: ConstraintLayout
  private lateinit var captureOuterCircle: View
  private lateinit var captureInnerCircle: View
  private lateinit var modeTabLayout: TabLayout
  private lateinit var versionText: TextView

  private lateinit var imagePreview: ImageView
  private lateinit var videoPreview: VideoView
  private lateinit var saveBtn: ImageButton
  private lateinit var editBtn: ImageButton
  private lateinit var retakeBtn: ImageButton
  private lateinit var buttonBar: View

  private var imageCapture: ImageCapture? = null
  private var videoCapture: VideoCapture<Recorder>? = null
  private var recording: Recording? = null
  private var lastCapturedFile: File? = null
  private var isVideoMode = false

  private val requestCamera = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) startCamera() else finishWithError("CAMERA permission denied")
  }

  private val requestAudio = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (!granted) Log.w("Camera", "Audio permission denied - video will be silent")
  }

  // Trim editor launcher
  private val trimEditorLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == RESULT_OK) {
      val data = result.data
      val action = data?.getStringExtra("action")
      val success = data?.getBooleanExtra("ok", false) ?: false

      when (action) {
        "trim_saved" -> {
          if (success) {
            val originalPath = data.getStringExtra("originalPath")
            val trimFile = data.getStringExtra("trimFile")
            val startTimeMs = data.getLongExtra("startTimeMs", 0)
            val endTimeMs = data.getLongExtra("endTimeMs", 0)
            val trimmedDurationMs = data.getLongExtra("trimmedDurationMs", 0)
            val message = data.getStringExtra("message") ?: "Video trimmed"

            // Return trim result to parent
            val resultIntent = Intent().apply {
              putExtra("action", "video_trimmed")
              putExtra("ok", true)
              putExtra("originalPath", originalPath)
              putExtra("trimFile", trimFile)
              putExtra("startTimeMs", startTimeMs)
              putExtra("endTimeMs", endTimeMs)
              putExtra("trimmedDurationMs", trimmedDurationMs)
              putExtra("message", message)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
          } else {
            val message = data.getStringExtra("message") ?: "Trim failed"
            finishWithError(message)
          }
        }
        "error" -> {
          val message = data.getStringExtra("message") ?: "Trim editor failed"
          finishWithError(message)
        }
      }
    } else {
      // User cancelled - return to camera view
      showCameraUI()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_native_camera)

    initViews()
    ensurePermissionsAndStart()
  }

  private fun initViews() {
    previewView = findViewById(R.id.previewView)
    captureBtn = findViewById(R.id.captureBtn)
    captureOuterCircle = findViewById(R.id.captureOuterCircle)
    captureInnerCircle = findViewById(R.id.captureInnerCircle)
    modeTabLayout = findViewById(R.id.modeTabLayout)
    versionText = findViewById(R.id.versionText)

    imagePreview = findViewById(R.id.imagePreview)
    videoPreview = findViewById(R.id.videoPreview)
    saveBtn = findViewById(R.id.saveBtn)
    editBtn = findViewById(R.id.editBtn)
    retakeBtn = findViewById(R.id.retakeBtn)
    buttonBar = findViewById(R.id.buttonBar)

    // Set up mode tab listener
    modeTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
      override fun onTabSelected(tab: TabLayout.Tab?) {
        isVideoMode = tab?.position == 1
        updateCaptureButtonColor()
        startCamera()
      }

      override fun onTabUnselected(tab: TabLayout.Tab?) {}
      override fun onTabReselected(tab: TabLayout.Tab?) {}
    })

    captureBtn.setOnClickListener {
      if (isVideoMode) {
        if (recording == null) {
          recordVideo()
        } else {
          recording?.stop()
          recording = null
        }
      } else {
        takePhoto()
      }
    }

    saveBtn.setOnClickListener { onSave() }
    editBtn.setOnClickListener { onEdit() }
    retakeBtn.setOnClickListener { onRetake() }

    // Set initial tab selection
    modeTabLayout.getTabAt(if (isVideoMode) 1 else 0)?.select()
    updateCaptureButtonColor()
  }

  private fun updateCaptureButtonColor() {
    if (isVideoMode) {
      captureOuterCircle.setBackgroundResource(R.drawable.circle_red_stroke)
      captureInnerCircle.setBackgroundResource(R.drawable.circle_red)
    } else {
      captureOuterCircle.setBackgroundResource(R.drawable.circle_white_stroke)
      captureInnerCircle.setBackgroundResource(R.drawable.circle_white)
    }
  }

  private fun ensurePermissionsAndStart() {
    val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    if (cameraOk) {
      // Request audio permission for video mode
      requestAudio.launch(Manifest.permission.RECORD_AUDIO)
      startCamera()
    } else {
      requestCamera.launch(Manifest.permission.CAMERA)
    }
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

      // Setup video capture
      val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
        .build()
      videoCapture = VideoCapture.withOutput(recorder)

      try {
        cameraProvider.unbindAll()
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        if (isVideoMode) {
          cameraProvider.bindToLifecycle(
            this, cameraSelector, preview, videoCapture
          )
        } else {
          cameraProvider.bindToLifecycle(
            this, cameraSelector, preview, imageCapture
          )
        }
      } catch (e: Exception) {
        finishWithError("Camera bind failed: ${e.message}")
      }
    }, ContextCompat.getMainExecutor(this))
  }

  private fun takePhoto() {
    val ic = imageCapture ?: return
    deleteLastCapture()

    val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
      .format(System.currentTimeMillis()) + ".jpg"
    val file = File(getExternalFilesDir(null), name)

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
          showPreviewUI(file, isVideo = false)
        }
      }
    )
  }

  private fun recordVideo() {
    val vc = videoCapture ?: return
    deleteLastCapture()

    if (recording != null) {
      // Stop current recording
      recording?.stop()
      recording = null
      return
    }

    val name = "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
      .format(System.currentTimeMillis()) + ".mp4"
    val file = File(getExternalFilesDir(null), name)

    recording = vc.output
      .prepareRecording(this, FileOutputOptions.Builder(file).build())
      .withAudioEnabled()
      .start(ContextCompat.getMainExecutor(this)) { event ->
        when (event) {
          is VideoRecordEvent.Start -> {
            // Change button to indicate recording in progress
            captureInnerCircle.setBackgroundResource(R.drawable.circle_red_recording)
          }
          is VideoRecordEvent.Finalize -> {
            if (event.hasError()) {
              finishWithError("Video recording failed: ${event.error}")
            } else {
              lastCapturedFile = file
              showPreviewUI(file, isVideo = true)
            }
            recording = null
            updateCaptureButtonColor()
          }
        }
      }
  }

  private fun showPreviewUI(file: File, isVideo: Boolean) {
    // Hide camera widgets
    previewView.visibility = View.GONE
    captureBtn.visibility = View.GONE
    modeTabLayout.visibility = View.GONE
    versionText.visibility = View.GONE

    // Show appropriate preview
    if (isVideo) {
      videoPreview.visibility = View.VISIBLE
      imagePreview.visibility = View.GONE

      videoPreview.setVideoURI(Uri.fromFile(file))
      videoPreview.start()

      // Loop video preview
      videoPreview.setOnCompletionListener {
        videoPreview.start()
      }

      // Enable edit button for videos only
      editBtn.isEnabled = true
      editBtn.alpha = 1.0f
    } else {
      imagePreview.visibility = View.VISIBLE
      videoPreview.visibility = View.GONE
      imagePreview.setImageURI(Uri.fromFile(file))

      // Disable edit for photos in this simple version
      editBtn.isEnabled = false
      editBtn.alpha = 0.5f
    }

    // Show action buttons
    buttonBar.visibility = View.VISIBLE
  }

  private fun showCameraUI() {
    // Hide preview widgets
    imagePreview.visibility = View.GONE
    videoPreview.visibility = View.GONE
    buttonBar.visibility = View.GONE

    // Show camera widgets
    previewView.visibility = View.VISIBLE
    captureBtn.visibility = View.VISIBLE
    modeTabLayout.visibility = View.VISIBLE
    versionText.visibility = View.VISIBLE

    // Stop video preview if playing
    if (videoPreview.isPlaying) {
      videoPreview.stopPlayback()
    }
  }

  private fun onSave() {
    val file = lastCapturedFile ?: run {
      finishWithError("No file to save")
      return
    }

    if (file.extension.lowercase() == "mp4") {
      // For videos, just return the path (no attestation for videos yet)
      val data = Intent().apply {
        putExtra("action", "save")
        putExtra("ok", true)
        putExtra("mediaPath", file.absolutePath)
        putExtra("mediaType", "video")
        putExtra("message", "Video saved successfully")
      }
      setResult(RESULT_OK, data)
      finish()
    } else {
      // For images, use attestation
      val res = AttestationPoc.run(this, file)
      val data = Intent().apply {
        putExtra("action", "save")
        putExtra("ok", res.ok)
        putExtra("mediaPath", res.mediaPath)
        putExtra("receiptPath", res.sidecarPath)
        putExtra("mediaType", "image")
        putExtra("message", res.message)
      }
      setResult(RESULT_OK, data)
      finish()
    }
  }

  private fun onEdit() {
    val file = lastCapturedFile ?: run {
      finishWithError("No file to edit")
      return
    }

    // Only launch trim editor for videos
    if (file.extension.lowercase() == "mp4") {
      val intent = Intent(this, SimpleTrimEditorActivity::class.java).apply {
        putExtra(SimpleTrimEditorActivity.EXTRA_VIDEO_PATH, file.absolutePath)
      }
      trimEditorLauncher.launch(intent)
    } else {
      // Photos not supported in this simple version
      finishWithError("Photo editing not supported in this version")
    }
  }

  private fun onRetake() {
    deleteLastCapture()
    showCameraUI()
  }

  private fun deleteLastCapture() {
    val f = lastCapturedFile ?: return
    try {
      if (f.exists()) f.delete()
      val sidecar = File(f.parentFile ?: filesDir, f.name + ".sig.json")
      if (sidecar.exists()) sidecar.delete()
    } catch (e: Exception) {
      Log.w("Camera", "Failed deleting previous capture: ${e.message}")
    } finally {
      lastCapturedFile = null
    }
  }

  override fun onBackPressed() {
    deleteLastCapture()
    super.onBackPressed()
  }

  private fun finishWithError(msg: String) {
    Log.e("Camera", msg)
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