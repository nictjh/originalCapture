package com.originalcapture

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.ReactPackage
import com.facebook.react.uimanager.ViewManager
import com.facebook.react.bridge.NativeModule
import com.originalcapture.camera.CameraAttestModule

class CameraAttestPackage : ReactPackage {
  override fun createNativeModules(ctx: ReactApplicationContext): List<NativeModule> =
    listOf(CameraAttestModule(ctx))
  override fun createViewManagers(ctx: ReactApplicationContext): List<ViewManager<*, *>> =
    emptyList()
}