package com.originalcapture.camera

import android.app.Activity
import android.content.Intent
import com.facebook.react.bridge.*

class CameraAttestModule(private val reactCtx: ReactApplicationContext)
  : ReactContextBaseJavaModule(reactCtx), ActivityEventListener {

  companion object { private const val RC_NATIVE_CAMERA = 42420 }

  private var pendingPromise: Promise? = null

  override fun getName() = "CameraAttest"

  init {
    reactCtx.addActivityEventListener(this)
  }

  @ReactMethod
  fun openCamera(promise: Promise) {
    val activity = currentActivity
    if (activity == null) {
      promise.reject("E_NO_ACTIVITY", "No current Activity")
      return
    }
    if (pendingPromise != null) {
      promise.reject("E_BUSY", "Camera already in progress")
      return
    }
    pendingPromise = promise
    val intent = Intent(activity, NativeCameraActivity::class.java)
    activity.startActivityForResult(intent, RC_NATIVE_CAMERA)
  }

  override fun onActivityResult(activity: Activity?, requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode != RC_NATIVE_CAMERA) return
    val promise = pendingPromise ?: return
    pendingPromise = null

    val map = Arguments.createMap().apply {
      putString("action", data?.getStringExtra("action") ?: "error")
      putBoolean("ok", data?.getBooleanExtra("ok", false) == true)
      putString("mediaPath", data?.getStringExtra("mediaPath"))
      putString("receiptPath", data?.getStringExtra("receiptPath"))
      putString("message", data?.getStringExtra("message"))
    }
    promise.resolve(map)
  }

  override fun onNewIntent(intent: Intent?) {}
}
