package com.meta.wearable.dat.externalsampleapps.cameraaccess.analysis

interface FoodAnalysisClient {
  suspend fun analyzePhoto(jpegBytes: ByteArray): FoodAnalysisOutcome
}
