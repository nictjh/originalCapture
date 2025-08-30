package com.originalcapture.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
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

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource


class NativeCameraActivity : ComponentActivity() {

  private lateinit var fused: FusedLocationProviderClient
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

  private val requestLocation = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { /* do nothing here; */ }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_native_camera)
    fused = LocationServices.getFusedLocationProviderClient(this)
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
    val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
      PackageManager.PERMISSION_GRANTED
    if (!camOk) {
      requestCamera.launch(Manifest.permission.CAMERA)
      return
    }
    //Location is optional, so dont block if not granted
    val locOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
    if (!locOk) {
      requestLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    startCamera()
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

  // Working
//   private fun takePhoto() {
//     val ic = imageCapture ?: return

//     // Delete previous capture if any
//     deleteLastCapture()

//     val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
//       .format(System.currentTimeMillis()) + ".jpg"
//     val file = File(filesDir, name)

//     // capture immediately (no waiting for location)
//     val opts = ImageCapture.OutputFileOptions.Builder(file).build()

//     ic.takePicture(
//         opts,
//         ContextCompat.getMainExecutor(this),
//         object : ImageCapture.OnImageSavedCallback {
//             override fun onError(exc: ImageCaptureException) {
//                 finishWithError("Capture failed: ${exc.message}")
//             }
//             override fun onImageSaved(output: ImageCapture.OutputFileResults) {
//                 lastCapturedFile = file
//                 showPreviewUI(file)
//                 // Now try to get location and write to EXIF if we got one
//                 fetchOneShotLocation { loc ->
//                     Log.i("AttestationPoc", "~~~~~~~~~~~~ loc=${loc?.latitude}, ${loc?.longitude}")
//                     try {
//                         if (loc != null) {
//                             val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
//                             exif.setLatLong(loc.latitude, loc.longitude)
//                             exif.setAttribute(
//                                 androidx.exifinterface.media.ExifInterface.TAG_GPS_TIMESTAMP,
//                                 (System.currentTimeMillis() / 1000L).toString()
//                             )
//                             exif.saveAttributes()
//                             Log.i("AttestationPoc", "~~~~~~~~~~~ Wrote GPS EXIF")
//                             // com.originalcapture.AttestationPoc.logExifTags("AttestationPocPROOOOOF", file)
//                         }
//                     } catch (e: Exception) {
//                         Log.w("AttestationPoc", "~~~~~~~~~~~ Failed to write GPS EXIF: ${e.message}")
//                     }
//                 }
//             }
//         }
//     )
//     }


  private fun takePhoto() {
    val ic = imageCapture ?: return

    // Delete previous capture if any
    deleteLastCapture()

    val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
      .format(System.currentTimeMillis()) + ".jpg"
    val file = File(filesDir, name)

    // capture immediately (no waiting for location)
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

                // Fetch location and write EXIF GPS (then verify immediately)
                fetchOneShotLocation { loc ->
                    Log.i("AttestationPoc", "loc=${loc?.latitude}, ${loc?.longitude}")

                    if (loc == null) {
                        Log.w("AttestationPoc", "No location; skipping EXIF GPS")
                        return@fetchOneShotLocation
                    }

                    try {
                        // --- Write GPS EXIF (UTC formats required by EXIF) ---
                        val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
                        exif.setLatLong(loc.latitude, loc.longitude)

                        val tz = java.util.TimeZone.getTimeZone("UTC")
                        val cal = java.util.Calendar.getInstance(tz).apply { timeInMillis = System.currentTimeMillis() }
                        val yyyy = cal.get(java.util.Calendar.YEAR)
                        val mm   = cal.get(java.util.Calendar.MONTH) + 1
                        val dd   = cal.get(java.util.Calendar.DAY_OF_MONTH)
                        val hh   = cal.get(java.util.Calendar.HOUR_OF_DAY)
                        val mi   = cal.get(java.util.Calendar.MINUTE)
                        val ss   = cal.get(java.util.Calendar.SECOND)

                        val dateStamp = String.format(java.util.Locale.US, "%04d:%02d:%02d", yyyy, mm, dd)
                        val timeStamp = String.format(java.util.Locale.US, "%d/1,%d/1,%d/1", hh, mi, ss)

                        exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_DATESTAMP, dateStamp)
                        exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_TIMESTAMP, timeStamp)
                        exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_SOFTWARE, "OriginalCapture")
                        exif.saveAttributes()
                        Log.i("AttestationPoc", "Wrote GPS EXIF")

                        // --- Verify immediately (reopen fresh instance) ---
                        val verify = androidx.exifinterface.media.ExifInterface(file.absolutePath)
                        val latLong = FloatArray(2)
                        val hasLatLong = verify.getLatLong(latLong)
                        Log.i(
                            "AttestationPoc",
                            "Verify EXIF: hasLatLong=$hasLatLong lat=${if (hasLatLong) latLong[0] else "NA"} lon=${if (hasLatLong) latLong[1] else "NA"} " +
                            "gpsDate=${verify.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_DATESTAMP)} " +
                            "gpsTime=${verify.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_TIMESTAMP)}"
                        )

                        // Optional: structured dump
                        com.originalcapture.AttestationPoc.logExifTagsHard("AttestationPoc", file)
                        com.originalcapture.AttestationPoc.logExifProof("AttestationPoc", file)

                        // If you want to continue to attestation here (instead of on Save):
                        // val res = com.originalcapture.AttestationPoc.run(this@YourActivity, file)
                        // Log.i("AttestationPoc", "Attestation result: $res")

                    } catch (e: Exception) {
                        Log.w("AttestationPoc", "Failed to write/verify GPS EXIF: ${e.message}")
                    }
                }
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

  private fun hasGms(): Boolean = try {
        // Class exists because you added the dep; check runtime availability:
        val availability = com.google.android.gms.common.GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(this)
        availability == com.google.android.gms.common.ConnectionResult.SUCCESS
    } catch (_: Throwable) {
        false
    }

    private fun fetchWithLocationManager(onResult: (Location?) -> Unit) {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) { onResult(null); return }

        // Try cached first (fast, may be null)
        val cached = sequenceOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { prov ->
            runCatching { lm.getLastKnownLocation(prov) }.getOrNull()
        }.maxByOrNull { it.time ?: 0L }

        if (cached != null) {
            onResult(cached); return
        }

        // Request a single quick update from the best available provider
        val provider = when {
            fineGranted && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) { onResult(null); return }

        var finished = false
        fun finish(loc: Location?) {
            if (!finished) {
                finished = true
                onResult(loc)
            }
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                lm.removeUpdates(this)
                finish(loc)
            }
            @Deprecated("unused") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }

        try {
            val perm = if (fineGranted) Manifest.permission.ACCESS_FINE_LOCATION else Manifest.permission.ACCESS_COARSE_LOCATION
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                finish(null); return
            }
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            // Hard timeout so we always return
            previewView.postDelayed({
                lm.removeUpdates(listener)
                finish(null)
            }, 8000)
        } catch (e: Exception) {
            Log.w("AttestationPoc", "LM fallback failed: ${e.message}")
            finish(null)
        }
    }

    // private fun fetchOneShotLocation(onResult: (Location?) -> Unit) {
    //     val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
    //             PackageManager.PERMISSION_GRANTED
    //     if (!ok) { onResult(null); return }

    //     var done = false
    //     fun finish(loc: Location?) { if (!done) { done = true; onResult(loc) } }

    //     val timeoutMs = 8000L
    //     // hard timeout → ALWAYS finish
    //     previewView.postDelayed({ finish(null) }, timeoutMs)

    //     // try last known first (usually instant)
    //     fused.lastLocation
    //         .addOnSuccessListener { last ->
    //             if (last != null) {
    //                 finish(last)
    //             } else {
    //                 val cts = CancellationTokenSource()
    //                 fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
    //                     .addOnSuccessListener { cur -> finish(cur) }
    //                     .addOnFailureListener { finish(null) }
    //                 // cancel near the timeout; final finish is the posted timeout above
    //                 previewView.postDelayed({ cts.cancel() }, timeoutMs - 500)
    //             }
    //         }
    //         .addOnFailureListener { finish(null) }
    // }

    private fun fetchOneShotLocation(onResult: (Location?) -> Unit) {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { onResult(null); return }

        if (hasGms()) {
            Log.i("AttestationPoc", "GMS available, using FusedLocationProviderClient")
            // Try Play services first, with guaranteed callback + LM fallback
            var done = false
            fun finish(loc: Location?) { if (!done) { done = true; onResult(loc) } }

            val priority = if (fine)
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY
            else
                com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY

            val timeoutMs = 8000L
            previewView.postDelayed({ finish(null) }, timeoutMs)

            // lastLocation (fast) then getCurrentLocation
            fused.lastLocation
                .addOnSuccessListener { last ->
                    if (last != null) {
                        finish(last)
                    } else {
                        val cts = CancellationTokenSource()
                        fused.getCurrentLocation(priority, cts.token)
                            .addOnSuccessListener { cur ->
                                if (cur != null) finish(cur) else fetchWithLocationManager(::finish)
                            }
                            .addOnFailureListener { fetchWithLocationManager(::finish) }
                        previewView.postDelayed({ cts.cancel() }, timeoutMs - 1000)
                    }
                }
                .addOnFailureListener { fetchWithLocationManager(::finish) }
        } else {
            // No Play services on the device → pure AOSP
            fetchWithLocationManager(onResult)
        }
    }

}
