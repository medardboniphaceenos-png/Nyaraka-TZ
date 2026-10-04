package com.example

import android.app.Application
import android.system.Os

class MyApplication : Application() {

  companion object {
    init {
      try {
        Os.setenv("MESA_DEBUG", "silent", true)
        Os.setenv("MESA_LOG_FILE", "/dev/null", true)
        Os.setenv("LIBGL_ALWAYS_SOFTWARE", "1", true)
      } catch (_: Throwable) {}
    }
  }

  override fun onCreate() {
    super.onCreate()
    try {
      Os.setenv("MESA_DEBUG", "silent", true)
      Os.setenv("MESA_LOG_FILE", "/dev/null", true)
      Os.setenv("LIBGL_ALWAYS_SOFTWARE", "1", true)
    } catch (_: Throwable) {}
  }
}
