package com.meta.wearable.dat.externalsampleapps.cameraaccess.analysis

import android.util.Log

interface FoodAnalysisLogger {
  fun uploadStarted()

  fun uploadSucceeded(requestId: String, statusCode: Int, elapsedMs: Long)

  fun requestFailed(
      stage: FoodAnalysisLogStage,
      error: FoodAnalysisError,
      statusCode: Int?,
      elapsedMs: Long,
  )
}

enum class FoodAnalysisLogStage(val logLabel: String) {
  Upload("upload"),
  Parse("parse"),
}

object AndroidFoodAnalysisLogger : FoodAnalysisLogger {
  private const val TAG = "CameraAccess:Analysis"

  override fun uploadStarted() {
    Log.d(TAG, "[upload] request started")
  }

  override fun uploadSucceeded(requestId: String, statusCode: Int, elapsedMs: Long) {
    Log.i(
        TAG,
        "[parse] request succeeded requestId=${requestId.ifBlank { "unavailable" }} " +
            "status=$statusCode elapsedMs=$elapsedMs",
    )
  }

  override fun requestFailed(
      stage: FoodAnalysisLogStage,
      error: FoodAnalysisError,
      statusCode: Int?,
      elapsedMs: Long,
  ) {
    Log.e(
        TAG,
        "[${stage.logLabel}] request failed type=${error::class.simpleName} " +
            "status=${statusCode ?: "unavailable"} elapsedMs=$elapsedMs",
    )
  }
}
