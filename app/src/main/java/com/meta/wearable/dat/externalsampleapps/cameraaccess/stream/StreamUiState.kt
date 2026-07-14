/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamUiState - DAT Camera Streaming UI State
//
// This data class manages UI state for camera streaming operations using the DAT API.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.types.StreamState

enum class VoiceState {
  Stopped,
  Listening,
  SuspendedForTts,
  Unavailable,
  Error,
}

sealed interface FoodAnalysisState {
  data object Idle : FoodAnalysisState

  data object Capturing : FoodAnalysisState

  data object Uploading : FoodAnalysisState

  data object Success : FoodAnalysisState

  data class Error(val stage: FoodAnalysisErrorStage, val canRetry: Boolean) : FoodAnalysisState
}

enum class FoodAnalysisErrorStage {
  Capture,
  Analysis,
}

data class StreamUiState(
    val streamState: StreamState = StreamState.STOPPED,
    val videoFrame: Bitmap? = null,
    val videoFrameCount: Int = 0,
    val capturedPhoto: Bitmap? = null,
    val isShareDialogVisible: Boolean = false,
    val isCapturing: Boolean = false,
    val recognizedText: String = "",
    val voiceState: VoiceState = VoiceState.Stopped,
    val foodAnalysisState: FoodAnalysisState = FoodAnalysisState.Idle,
    val foodName: String = "",
    val recipeText: String = "",
    val userMessage: String? = null,
    val isSpeaking: Boolean = false,
)
