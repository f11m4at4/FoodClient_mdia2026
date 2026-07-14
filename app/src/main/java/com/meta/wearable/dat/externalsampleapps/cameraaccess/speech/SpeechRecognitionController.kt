package com.meta.wearable.dat.externalsampleapps.cameraaccess.speech

interface SpeechRecognitionController {
  val isRecognitionAvailable: Boolean

  fun startListening(onEvent: (SpeechRecognitionEvent) -> Unit)

  fun stopListening()

  fun release()
}

sealed interface SpeechRecognitionEvent {
  data object Ready : SpeechRecognitionEvent

  data class PartialResult(val text: String) : SpeechRecognitionEvent

  data class FinalResult(val text: String) : SpeechRecognitionEvent

  data class Error(val error: SpeechRecognitionError) : SpeechRecognitionEvent
}

enum class SpeechRecognitionError {
  PermissionDenied,
  NoMatch,
  Timeout,
  Busy,
  Client,
  Unknown,
}
