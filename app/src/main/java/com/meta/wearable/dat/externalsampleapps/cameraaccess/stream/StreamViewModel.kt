/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamViewModel - DAT Camera Streaming API Demo
//
// This ViewModel demonstrates the DAT Camera Streaming APIs for:
// - Creating and managing stream sessions with wearable devices
// - Receiving video frames from device cameras
// - Capturing photos during streaming sessions
// - Handling different video qualities and formats
// - Processing raw video data (I420 -> ARGB conversion)

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.externalsampleapps.cameraaccess.AppConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.analysis.FoodAnalysisClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.analysis.FoodAnalysisError
import com.meta.wearable.dat.externalsampleapps.cameraaccess.analysis.FoodAnalysisOutcome
import com.meta.wearable.dat.externalsampleapps.cameraaccess.analysis.HttpFoodAnalysisClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.speech.AndroidSpeechRecognitionController
import com.meta.wearable.dat.externalsampleapps.cameraaccess.speech.AndroidTextToSpeechController
import com.meta.wearable.dat.externalsampleapps.cameraaccess.speech.SpeechRecognitionController
import com.meta.wearable.dat.externalsampleapps.cameraaccess.speech.SpeechRecognitionError
import com.meta.wearable.dat.externalsampleapps.cameraaccess.speech.SpeechRecognitionEvent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.speech.TextToSpeechController
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CaptureSource {
  Manual,
  Voice,
}

@SuppressLint("AutoCloseableUse")
class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
    private val analysisClient: FoodAnalysisClient =
        HttpFoodAnalysisClient(AppConfig.analysisServerUrl),
    private val speechRecognitionController: SpeechRecognitionController =
        AndroidSpeechRecognitionController(application),
    private val textToSpeechController: TextToSpeechController =
        AndroidTextToSpeechController(application),
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "CameraAccess:StreamViewModel"
    private val INITIAL_STATE = StreamUiState()
    private val SESSION_TERMINAL_STATES = setOf(StreamState.CLOSED)
    private const val SPEECH_RESTART_DELAY_MS = 500L
    private const val MAX_ANALYSIS_IMAGE_EDGE = 1600
    private const val ANALYSIS_JPEG_QUALITY = 85
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var session: DeviceSession? = null

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  private var videoJob: Job? = null
  private var stateJob: Job? = null
  private var errorJob: Job? = null
  private var sessionErrorJob: Job? = null
  private var sessionStateJob: Job? = null
  private var analysisJob: Job? = null
  private var speechRestartJob: Job? = null
  private var stream: Stream? = null
  private var previousDeviceSessionState: DeviceSessionState? = null
  private var hasAudioPermission = false
  private var isScreenForeground = false
  private val captureRequestGate = CaptureRequestGate()

  // Presentation queue for buffering frames after color conversion
  private var presentationQueue: PresentationQueue? = null

  fun startStream() {
    videoJob?.cancel()
    stateJob?.cancel()
    errorJob?.cancel()
    sessionErrorJob?.cancel()
    sessionStateJob?.cancel()
    presentationQueue?.stop()
    presentationQueue = null
    previousDeviceSessionState = null

    // Initialize presentation queue - frames are presented based on timestamp, not arrival time
    // Uses IntArray pooling for efficiency - cheaper than Bitmap.copy()
    val queue =
        PresentationQueue(
            bufferDelayMs = 100L,
            maxQueueSize = 15,
            onFrameReady = { frame ->
              // This is called from the presentation thread at regular intervals
              // when a frame's presentation time has arrived
              viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                _uiState.update {
                  it.copy(videoFrame = frame.bitmap, videoFrameCount = it.videoFrameCount + 1)
                }
              }
            },
        )
    presentationQueue = queue
    queue.start()
    if (session == null) {
      previousDeviceSessionState = null
      Wearables.createSession(deviceSelector)
          .onSuccess { createdSession ->
            session = createdSession
            sessionErrorJob = viewModelScope.launch {
              createdSession.errors.collect { error -> handleSessionError(error) }
            }
            session?.start()
          }
          .onFailure { error, _ ->
            Log.e(TAG, "Failed to create session: ${error.description}")
            handleSessionError(error)
          }
      if (session == null) return
    }
    startStreamInternal()
  }

  private fun startStreamInternal() {
    Log.d(TAG, "startStreamInternal() - collecting session state")
    sessionStateJob = viewModelScope.launch {
      session?.state?.collect { currentState ->
        val prevState = previousDeviceSessionState
        previousDeviceSessionState = currentState

        if (currentState == DeviceSessionState.STARTED) {
          wearablesViewModel.setDatAppUpdateRequired(false)
          if (prevState == DeviceSessionState.PAUSED && stream != null) {
            // PAUSED ??STARTED: device-initiated resume (tap gesture).
            // The SDK handles resume internally via requestCameraOn() ??resumeStreaming().
            // Do NOT recreate the stream ??just let the SDK resume it.
            Log.d(TAG, "Session resumed from PAUSED ??stream stays alive")
            return@collect
          }

          videoJob?.cancel()
          stateJob?.cancel()
          errorJob?.cancel()
          stream?.stop()
          stream = null
          session
              ?.addStream(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24))
              ?.onSuccess { addedStream ->
                stream = addedStream
                videoJob = viewModelScope.launch {
                  Log.d(TAG, "Collecting video frames from stream")
                  stream?.videoStream?.collect { handleVideoFrame(it) }
                  Log.d(TAG, "Video stream collection ended")
                }
                stateJob = viewModelScope.launch {
                  stream?.state?.collect { streamState ->
                    val prevStreamState = _uiState.value.streamState
                    Log.d(TAG, "Stream state changed: $prevStreamState -> $streamState")
                    _uiState.update { it.copy(streamState = streamState) }
                    if (streamState == StreamState.STREAMING) {
                      startListeningIfAllowed()
                    } else {
                      stopListeningForInactiveStream()
                    }

                    val wasActive = prevStreamState !in SESSION_TERMINAL_STATES
                    val isTerminated = streamState in SESSION_TERMINAL_STATES
                    if (wasActive && isTerminated) {
                      Log.d(TAG, "Terminal state reached, navigating back")
                      stopStream()
                      wearablesViewModel.navigateToDeviceSelection()
                    }
                  }
                }
                errorJob = viewModelScope.launch {
                  stream?.errorStream?.collect { error ->
                    Log.d(TAG, "Stream error received: $error (description: ${error.description})")
                    if (error == StreamError.STREAM_ERROR) {
                      Log.d(TAG, "Non-critical error, stream continues")
                      return@collect
                    }
                    stopStream()
                    wearablesViewModel.navigateToDeviceSelection()
                    // Use `getLocalizedDescription(context)` for user-facing text ??
                    // `description` is always English and intended for logs.
                    wearablesViewModel.setRecentError(
                        error.getLocalizedDescription(getApplication())
                    )
                  }
                }
                stream?.start()
              }
              ?.onFailure { error, _ ->
                Log.e(TAG, "Failed to add stream to session: ${error.description}")
              }
        } else if (currentState == DeviceSessionState.PAUSED) {
          // Tap gesture paused the session ??keep the stream alive.
          // The SDK transitions StreamState to PAUSED internally.
          Log.d(TAG, "Session paused (tap gesture) ??keeping stream alive for resume")
        }
      }
    }
  }

  fun stopStream() {
    videoJob?.cancel()
    videoJob = null
    stateJob?.cancel()
    stateJob = null
    errorJob?.cancel()
    errorJob = null
    sessionErrorJob?.cancel()
    sessionErrorJob = null
    sessionStateJob?.cancel()
    sessionStateJob = null
    analysisJob?.cancel()
    analysisJob = null
    speechRestartJob?.cancel()
    speechRestartJob = null
    speechRecognitionController.stopListening()
    textToSpeechController.stop()
    captureRequestGate.reset()
    isScreenForeground = false
    presentationQueue?.stop()
    presentationQueue = null
    _uiState.update { INITIAL_STATE }
    stream?.stop()
    stream = null
    session?.stop()
    session = null
  }

  private fun handleSessionError(error: DeviceSessionError) {
    Log.e(TAG, "Session error: ${error.description}")
    val alreadyShowingUpdateRequired =
        wearablesViewModel.uiState.value.isFirmwareUpdateRequired ||
            wearablesViewModel.uiState.value.isDatAppUpdateRequired

    if (error == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
      wearablesViewModel.setDatAppUpdateRequired(true)
    }
    if (alreadyShowingUpdateRequired && error == DeviceSessionError.SESSION_ENDED_BY_DEVICE) {
      stopStream()
      wearablesViewModel.navigateToDeviceSelection()
      return
    }

    wearablesViewModel.setRecentError(error.getLocalizedDescription(getApplication()))
    stopStream()
    wearablesViewModel.navigateToDeviceSelection()
  }

  fun capturePhoto() {
    requestCapture(CaptureSource.Manual)
  }

  fun isSpeechRecognitionAvailable(): Boolean =
      speechRecognitionController.isRecognitionAvailable

  fun onScreenStarted() {
    isScreenForeground = true
    startListeningIfAllowed()
  }

  fun onScreenStopped() {
    isScreenForeground = false
    speechRestartJob?.cancel()
    speechRecognitionController.stopListening()
    _uiState.update {
      if (it.voiceState == VoiceState.Listening) it.copy(voiceState = VoiceState.Stopped) else it
    }
  }

  fun onAudioPermissionResult(granted: Boolean) {
    hasAudioPermission = granted
    if (!speechRecognitionController.isRecognitionAvailable) {
      _uiState.update {
        it.copy(
            voiceState = VoiceState.Unavailable,
            userMessage = "??ê¸°ê¸°?ì„œ???Œì„± ?¸ì‹???¬ìš©?????†ìŠµ?ˆë‹¤.",
        )
      }
      return
    }

    if (!granted) {
      speechRecognitionController.stopListening()
      _uiState.update {
        it.copy(
            voiceState = VoiceState.Error,
            userMessage = "?Œì„± ?¸ì‹???¬ìš©?˜ë ¤ë©?ë§ˆì´??ê¶Œí•œ???ˆìš©??ì£¼ì„¸??",
        )
      }
      return
    }

    _uiState.update {
      it.copy(
          voiceState = VoiceState.Stopped,
          userMessage =
              if (it.userMessage?.contains("ë§ˆì´??ê¶Œí•œ") == true) null else it.userMessage,
      )
    }
    startListeningIfAllowed()
  }

  private fun startListeningIfAllowed() {
    val currentState = _uiState.value
    if (
        !isScreenForeground ||
            !hasAudioPermission ||
            !speechRecognitionController.isRecognitionAvailable ||
            currentState.streamState != StreamState.STREAMING ||
            currentState.isCapturing ||
            currentState.foodAnalysisState == FoodAnalysisState.Uploading ||
            currentState.isSpeaking ||
            currentState.voiceState == VoiceState.Listening
    ) {
      return
    }

    speechRestartJob?.cancel()
    _uiState.update { it.copy(voiceState = VoiceState.Listening) }
    speechRecognitionController.startListening(::handleSpeechRecognitionEvent)
  }
  private fun stopListeningForInactiveStream() {
    speechRestartJob?.cancel()
    speechRecognitionController.stopListening()
    _uiState.update {
      if (it.voiceState == VoiceState.Listening) it.copy(voiceState = VoiceState.Stopped) else it
    }
  }

  private fun handleSpeechRecognitionEvent(event: SpeechRecognitionEvent) {
    when (event) {
      SpeechRecognitionEvent.Ready ->
          _uiState.update { it.copy(voiceState = VoiceState.Listening) }
      is SpeechRecognitionEvent.PartialResult ->
          _uiState.update { it.copy(recognizedText = event.text) }
      is SpeechRecognitionEvent.FinalResult -> {
        _uiState.update {
          it.copy(recognizedText = event.text, voiceState = VoiceState.Stopped)
        }
        if (VoiceCaptureTriggerMatcher.matches(event.text, AppConfig.voiceCaptureTriggerPhrase)) {
          requestCapture(CaptureSource.Voice)
        } else {
          scheduleSpeechRestart()
        }
      }
      is SpeechRecognitionEvent.Error -> handleSpeechRecognitionError(event.error)
    }
  }
  private fun handleSpeechRecognitionError(error: SpeechRecognitionError) {
    when (error) {
      SpeechRecognitionError.NoMatch,
      SpeechRecognitionError.Timeout -> {
        _uiState.update { it.copy(voiceState = VoiceState.Stopped) }
        scheduleSpeechRestart()
      }
      SpeechRecognitionError.PermissionDenied -> {
        hasAudioPermission = false
        _uiState.update {
          it.copy(
              voiceState = VoiceState.Error,
              userMessage = "ë§ˆì´??ê¶Œí•œ??ê±°ë??˜ì–´ ?Œì„± ?¸ì‹??ì¤‘ì??ˆìŠµ?ˆë‹¤.",
          )
        }
      }
      SpeechRecognitionError.Busy,
      SpeechRecognitionError.Client,
      SpeechRecognitionError.Unknown ->
          _uiState.update {
            it.copy(
                voiceState = VoiceState.Error,
                userMessage = "?Œì„± ?¸ì‹???œìž‘?˜ì? ëª»í–ˆ?µë‹ˆ?? ? ì‹œ ???¤ì‹œ ?œë„??ì£¼ì„¸??",
            )
          }
    }
  }

  private fun scheduleSpeechRestart() {
    speechRestartJob?.cancel()
    speechRestartJob =
        viewModelScope.launch {
          delay(SPEECH_RESTART_DELAY_MS)
          startListeningIfAllowed()
        }
  }

  private fun showCaptureFailure(reason: String) {
    Log.e(TAG, "[capture] request failed reason=$reason")
    captureRequestGate.release()
    _uiState.update {
      it.copy(
          isCapturing = false,
          foodAnalysisState =
              FoodAnalysisState.Error(
                  stage = FoodAnalysisErrorStage.Capture,
                  canRetry = true,
              ),
          userMessage = "?¬ì§„??ì´¬ì˜?˜ì? ëª»í–ˆ?µë‹ˆ?? ?¤íŠ¸ë¦??íƒœë¥??•ì¸?˜ê³  ?¤ì‹œ ?œë„??ì£¼ì„¸??",
      )
    }
    startListeningIfAllowed()
  }
  private fun showAnalysisSuccess(outcome: FoodAnalysisOutcome.Success) {
    captureRequestGate.release()
    val result = outcome.result
    _uiState.update {
      it.copy(
          isCapturing = false,
          foodAnalysisState = FoodAnalysisState.Success,
          foodName = result.foodName,
          recipeText = result.recipeText,
          userMessage = "?Œì‹ ë¶„ì„???„ë£Œ?˜ì—ˆ?µë‹ˆ??",
      )
    }
    speakAnalysisResult(result.foodName, result.recipeText)
  }
  private fun showAnalysisFailure(error: FoodAnalysisError) {
    captureRequestGate.release()
    val stage =
        if (
            error == FoodAnalysisError.InvalidResponse ||
                error == FoodAnalysisError.FoodNotDetected
        ) {
          "parse"
        } else {
          "upload"
        }
    Log.e(TAG, "[$stage] analysis failed type=${error::class.simpleName}")
    _uiState.update {
      it.copy(
          isCapturing = false,
          foodAnalysisState =
              FoodAnalysisState.Error(
                  stage = FoodAnalysisErrorStage.Analysis,
                  canRetry = error != FoodAnalysisError.Cancelled,
              ),
          userMessage = error.toUserMessage(),
      )
    }
    if (error != FoodAnalysisError.Cancelled) {
      startListeningIfAllowed()
    }
  }
  private fun FoodAnalysisError.toUserMessage(): String =
      when (this) {
        FoodAnalysisError.MissingServerUrl ->
            "ë¶„ì„ ?œë²„ ì£¼ì†Œê°€ ?¤ì •?˜ì? ?Šì•˜?µë‹ˆ?? local.propertiesë¥??•ì¸??ì£¼ì„¸??"
        FoodAnalysisError.Timeout -> "ë¶„ì„ ?œë²„ ?‘ë‹µ ?œê°„??ì´ˆê³¼?˜ì—ˆ?µë‹ˆ?? ?¤ì‹œ ?œë„??ì£¼ì„¸??"
        FoodAnalysisError.Cancelled -> "?Œì‹ ë¶„ì„ ?”ì²­??ì·¨ì†Œ?˜ì—ˆ?µë‹ˆ??"
        FoodAnalysisError.Network -> "ë¶„ì„ ?œë²„???°ê²°?????†ìŠµ?ˆë‹¤. ?¤íŠ¸?Œí¬ë¥??•ì¸??ì£¼ì„¸??"
        is FoodAnalysisError.HttpStatus ->
            if (statusCode >= 500) {
              "ë¶„ì„ ?œë²„??ë¬¸ì œê°€ ë°œìƒ?ˆìŠµ?ˆë‹¤. ? ì‹œ ???¤ì‹œ ?œë„??ì£¼ì„¸??"
            } else {
              "ë¶„ì„ ?”ì²­??ì²˜ë¦¬?????†ìŠµ?ˆë‹¤. ?…ë ¥ê³??œë²„ ?¤ì •???•ì¸??ì£¼ì„¸??"
            }
        FoodAnalysisError.InvalidResponse -> "ë¶„ì„ ?œë²„ ?‘ë‹µ???½ì„ ???†ìŠµ?ˆë‹¤. ?¤ì‹œ ?œë„??ì£¼ì„¸??"
        FoodAnalysisError.FoodNotDetected -> "?¬ì§„?ì„œ ?Œì‹??ì°¾ì? ëª»í–ˆ?µë‹ˆ?? ?¤ì‹œ ì´¬ì˜??ì£¼ì„¸??"
      }

  private fun speakAnalysisResult(foodName: String, recipeText: String) {
    speechRestartJob?.cancel()
    speechRecognitionController.stopListening()
    _uiState.update {
      it.copy(voiceState = VoiceState.SuspendedForTts, isSpeaking = true)
    }
    textToSpeechController.speak(
        text = "$foodName. $recipeText",
        onDone = {
          _uiState.update { it.copy(voiceState = VoiceState.Stopped, isSpeaking = false) }
          startListeningIfAllowed()
        },
        onError = {
          _uiState.update {
            it.copy(
                voiceState = VoiceState.Stopped,
                isSpeaking = false,
                userMessage = "ë¶„ì„ ê²°ê³¼???œì‹œ?ˆì?ë§??Œì„±?¼ë¡œ ?½ì? ëª»í–ˆ?µë‹ˆ??",
            )
          }
          startListeningIfAllowed()
        },
    )
  }

  private fun requestCapture(source: CaptureSource) {
    val currentState = uiState.value
    if (currentState.streamState != StreamState.STREAMING) {
      Log.w(TAG, "Cannot capture photo from ${source}: stream not active (${currentState.streamState})")
      _uiState.update {
        it.copy(
            foodAnalysisState =
                FoodAnalysisState.Error(
                    stage = FoodAnalysisErrorStage.Capture,
                    canRetry = true,
                ),
            userMessage = "?¤íŠ¸ë¦¬ë°???œìž‘?????¬ì§„??ì´¬ì˜??ì£¼ì„¸??",
        )
      }
      return
    }

    when (captureRequestGate.tryAcquire()) {
      CaptureRequestDecision.InFlight -> {
        Log.d(TAG, "Ignoring duplicate capture request from ${source} while in flight")
        return
      }
      CaptureRequestDecision.Cooldown -> {
        Log.d(TAG, "Ignoring capture request from ${source} during cooldown")
        return
      }
      CaptureRequestDecision.Accepted -> Unit
    }

    val activeStream = stream
    if (activeStream == null) {
      showCaptureFailure("stream unavailable")
      return
    }

    speechRestartJob?.cancel()
    speechRecognitionController.stopListening()
    _uiState.update {
      it.copy(
          voiceState = VoiceState.Stopped,
          isCapturing = true,
          foodAnalysisState = FoodAnalysisState.Capturing,
          foodName = "",
          recipeText = "",
          userMessage = "?¬ì§„??ì´¬ì˜?˜ê³  ?ˆìŠµ?ˆë‹¤.",
      )
    }

    Log.d(TAG, "[capture] request started source=${source}")
    analysisJob?.cancel()
    analysisJob =
        viewModelScope.launch {
          var capturedPhotoData: PhotoData? = null
          var captureErrorDescription: String? = null
          activeStream.capturePhoto()
              .onSuccess { capturedPhotoData = it }
              .onFailure { error, _ -> captureErrorDescription = error.description }

          val photoData = capturedPhotoData
          if (photoData == null) {
            showCaptureFailure(captureErrorDescription ?: "unknown capture error")
            return@launch
          }

          val bitmap = withContext(Dispatchers.Default) { decodePhotoData(photoData) }
          if (bitmap == null) {
            showCaptureFailure("captured image could not be decoded")
            return@launch
          }

          Log.i(TAG, "[capture] request succeeded source=${source}")
          _uiState.update {
            it.copy(
                capturedPhoto = bitmap,
                isShareDialogVisible = true,
                isCapturing = false,
                foodAnalysisState = FoodAnalysisState.Uploading,
                userMessage = "?¬ì§„ ì´¬ì˜???„ë£Œ?˜ì—ˆ?µë‹ˆ?? ?Œì‹??ë¶„ì„?˜ê³  ?ˆìŠµ?ˆë‹¤.",
            )
          }

          val jpegBytes = withContext(Dispatchers.Default) { bitmap.toAnalysisJpeg() }
          if (jpegBytes == null) {
            showAnalysisFailure(FoodAnalysisError.InvalidResponse)
            return@launch
          }

          when (val outcome = analysisClient.analyzePhoto(jpegBytes)) {
            is FoodAnalysisOutcome.Success -> showAnalysisSuccess(outcome)
            is FoodAnalysisOutcome.Failure -> showAnalysisFailure(outcome.error)
          }
        }
  }
  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.putExtra(Intent.EXTRA_STREAM, uri)
      intent.type = "image/png"
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      val chooser = Intent.createChooser(intent, "Share Image")
      chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e("StreamViewModel", "Failed to share photo", e)
    }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    // VideoFrame contains raw I420 video data in a ByteBuffer
    // Use optimized YuvToBitmapConverter for direct I420 to ARGB conversion
    val bitmap =
        YuvToBitmapConverter.convert(
            videoFrame.buffer,
            videoFrame.width,
            videoFrame.height,
        )
    if (bitmap != null) {
      presentationQueue?.enqueue(
          bitmap,
          videoFrame.presentationTimeUs,
      )
    } else {
      Log.e(TAG, "Failed to convert YUV to bitmap")
    }
  }

  private fun decodePhotoData(photo: PhotoData): Bitmap? {
    return when (photo) {
          is PhotoData.Bitmap -> photo.bitmap
          is PhotoData.HEIC -> {
            val data = photo.data.duplicate()
            val byteArray = ByteArray(data.remaining())
            data.get(byteArray)

            // Extract EXIF transformation matrix and apply to bitmap
            val exifInfo = getExifInfo(byteArray)
            val transform = getTransform(exifInfo)
            decodeHeic(byteArray, transform)
          }
        }
  }

  // HEIC Decoding with EXIF transformation
  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap? {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size) ?: return null
    return applyTransform(bitmap, transform)
  }

  private fun Bitmap.toAnalysisJpeg(): ByteArray? {
    if (width <= 0 || height <= 0) return null
    val longestEdge = maxOf(width, height)
    val scale = minOf(1f, MAX_ANALYSIS_IMAGE_EDGE.toFloat() / longestEdge)
    val encodedBitmap =
        if (scale < 1f) {
          Bitmap.createScaledBitmap(
              this,
              (width * scale).toInt().coerceAtLeast(1),
              (height * scale).toInt().coerceAtLeast(1),
              true,
          )
        } else {
          this
        }

    return try {
      ByteArrayOutputStream().use { output ->
        if (!encodedBitmap.compress(Bitmap.CompressFormat.JPEG, ANALYSIS_JPEG_QUALITY, output)) {
          null
        } else {
          output.toByteArray()
        }
      }
    } finally {
      if (encodedBitmap !== this) encodedBitmap.recycle()
    }
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()

    if (exifInfo == null) {
      return matrix // Identity matrix (no transformation)
    }

    when (
        exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_180 -> {
        matrix.postRotate(180f)
      }
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.postScale(1f, -1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> {
        matrix.postRotate(90f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> {
        matrix.postRotate(270f)
      }
      ExifInterface.ORIENTATION_NORMAL,
      ExifInterface.ORIENTATION_UNDEFINED -> {
        // No transformation needed
      }
    }

    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) {
      return bitmap
    }

    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  override fun onCleared() {
    super.onCleared()
    stopStream()
    speechRecognitionController.release()
    textToSpeechController.release()
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
      private val analysisClient: FoodAnalysisClient? = null,
      private val speechRecognitionController: SpeechRecognitionController? = null,
      private val textToSpeechController: TextToSpeechController? = null,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
            application = application,
            wearablesViewModel = wearablesViewModel,
            analysisClient =
                analysisClient ?: HttpFoodAnalysisClient(AppConfig.analysisServerUrl),
            speechRecognitionController =
                speechRecognitionController ?: AndroidSpeechRecognitionController(application),
            textToSpeechController =
                textToSpeechController ?: AndroidTextToSpeechController(application),
        )
            as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}

