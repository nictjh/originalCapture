package com.originalcapture.camera

import android.app.Activity
import android.content.Intent
import com.facebook.react.bridge.*

class CameraAttestModule(private val reactCtx: ReactApplicationContext)
  : ReactContextBaseJavaModule(reactCtx), ActivityEventListener, LifecycleEventListener {

  companion object { private const val RC_NATIVE_CAMERA = 42420 }

  private var pendingPromise: Promise? = null
  private var pendingOpen: Boolean = false

  // REQUIRED: the JS module name (NativeModules.CameraAttest)
  override fun getName(): String = "CameraAttest"

  init {
    reactCtx.addActivityEventListener(this)
    reactCtx.addLifecycleEventListener(this)
  }

  @ReactMethod
  fun openCamera(promise: Promise) {
    val activity = currentActivity
    if (pendingPromise != null) {
      promise.reject("E_BUSY", "Camera already in progress")
      return
    }
    pendingPromise = promise

    if (activity == null) {
      // Defer until onHostResume if Activity isn't ready yet
      pendingOpen = true
      return
    }
    startNativeCamera(activity)
  }

  private fun startNativeCamera(activity: Activity) {
    val intent = Intent(activity, NativeCameraActivity::class.java)
    activity.startActivityForResult(intent, RC_NATIVE_CAMERA)
  }

  // LifecycleEventListener
  override fun onHostResume() {
    if (pendingOpen) {
      val act = currentActivity
      if (act == null) {
        pendingPromise?.reject("E_NO_ACTIVITY", "No current Activity")
        pendingPromise = null
        pendingOpen = false
        return
      }
      pendingOpen = false
      startNativeCamera(act)
    }
  }
  override fun onHostPause() {}
  override fun onHostDestroy() {}

  // ActivityEventListener
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