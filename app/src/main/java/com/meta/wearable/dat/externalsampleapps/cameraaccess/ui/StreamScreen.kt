/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamScreen - DAT Camera Streaming UI
//
// This composable demonstrates the main streaming UI for DAT camera functionality. It shows how to
// display live video from wearable devices and handle photo capture.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.FoodAnalysisErrorStage
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.FoodAnalysisState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.VoiceState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

@Composable
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
  val audioPermissionLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        streamViewModel.onAudioPermissionResult(granted)
      }

  LaunchedEffect(Unit) {
    streamViewModel.startStream()
    if (!streamViewModel.isSpeechRecognitionAvailable()) {
      streamViewModel.onAudioPermissionResult(false)
    } else if (
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    ) {
      streamViewModel.onAudioPermissionResult(true)
    } else {
      audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  DisposableEffect(lifecycleOwner, streamViewModel) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> streamViewModel.onScreenStarted()
        Lifecycle.Event.ON_STOP -> streamViewModel.onScreenStopped()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      streamViewModel.onScreenStopped()
      streamViewModel.stopStream()
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    streamUiState.videoFrame?.let { videoFrame ->
      // Use key() to force recomposition when frame counter changes,
      // even if the bitmap reference is the same (due to caching optimization)
      key(streamUiState.videoFrameCount) {
        Image(
            bitmap = videoFrame.asImageBitmap(),
            contentDescription = stringResource(R.string.live_stream),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
      }
    }
    if (streamUiState.streamState == StreamState.STARTING) {
      CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
      )
    }

    StreamStatusPanel(
        uiState = streamUiState,
        onRetry = streamViewModel::capturePhoto,
        modifier =
            Modifier.align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    )

    Box(modifier = Modifier.fillMaxSize().padding(all = 24.dp)) {
      Row(
          modifier =
              Modifier.align(Alignment.BottomCenter)
                  .navigationBarsPadding()
                  .fillMaxWidth()
                  .height(56.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        SwitchButton(
            label = stringResource(R.string.stop_stream_button_title),
            onClick = {
              streamViewModel.stopStream()
              wearablesViewModel.navigateToDeviceSelection()
            },
            isDestructive = true,
            modifier = Modifier.weight(1f),
        )

        // Photo capture button
        CaptureButton(
            onClick = { streamViewModel.capturePhoto() },
        )
      }
    }
  }

  streamUiState.capturedPhoto?.let { photo ->
    if (streamUiState.isShareDialogVisible) {
      SharePhotoDialog(
          photo = photo,
          onDismiss = { streamViewModel.hideShareDialog() },
          onShare = { bitmap ->
            streamViewModel.sharePhoto(bitmap)
            streamViewModel.hideShareDialog()
          },
      )
    }
  }
}

@Composable
private fun StreamStatusPanel(
    uiState: com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val voiceStatus =
      stringResource(
          when (uiState.voiceState) {
            VoiceState.Stopped -> R.string.voice_state_stopped
            VoiceState.Listening -> R.string.voice_state_listening
            VoiceState.SuspendedForTts -> R.string.voice_state_suspended
            VoiceState.Unavailable -> R.string.voice_state_unavailable
            VoiceState.Error -> R.string.voice_state_error
          }
      )
  val analysisStatus =
      stringResource(
          when (val state = uiState.foodAnalysisState) {
            FoodAnalysisState.Idle -> R.string.analysis_state_idle
            FoodAnalysisState.Capturing -> R.string.analysis_state_capturing
            FoodAnalysisState.Uploading -> R.string.analysis_state_uploading
            FoodAnalysisState.Success -> R.string.analysis_state_success
            is FoodAnalysisState.Error ->
                if (state.stage == FoodAnalysisErrorStage.Capture) {
                  R.string.analysis_state_capture_error
                } else {
                  R.string.analysis_state_request_error
                }
          }
      )

  Column(
      modifier =
          modifier
              .heightIn(max = 280.dp)
              .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
              .verticalScroll(rememberScrollState())
              .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
        text = voiceStatus,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
    )
    if (uiState.recognizedText.isNotBlank()) {
      Text(
          text = stringResource(R.string.recognized_text, uiState.recognizedText),
          color = Color.White,
          style = MaterialTheme.typography.bodySmall,
      )
    }
    Text(
        text = analysisStatus,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
    )
    if (uiState.foodName.isNotBlank()) {
      Text(
          text = stringResource(R.string.food_name, uiState.foodName),
          color = Color.White,
          style = MaterialTheme.typography.titleSmall,
      )
    }
    if (uiState.recipeText.isNotBlank()) {
      Text(
          text = uiState.recipeText,
          color = Color.White,
          maxLines = 8,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.bodySmall,
      )
    }
    uiState.userMessage?.let { message ->
      Text(
          text = message,
          color = Color.White,
          style = MaterialTheme.typography.bodySmall,
      )
    }
    val errorState = uiState.foodAnalysisState as? FoodAnalysisState.Error
    if (errorState?.canRetry == true) {
      TextButton(onClick = onRetry) { Text(stringResource(R.string.retry_analysis)) }
    }
  }
}
