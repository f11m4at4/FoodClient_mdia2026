package com.meta.wearable.dat.externalsampleapps.cameraaccess.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class AndroidSpeechRecognitionController(context: Context) : SpeechRecognitionController {
  private val applicationContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private var speechRecognizer: SpeechRecognizer? = null
  private var eventListener: ((SpeechRecognitionEvent) -> Unit)? = null
  private var released = false

  override val isRecognitionAvailable: Boolean =
      SpeechRecognizer.isRecognitionAvailable(applicationContext)

  override fun startListening(onEvent: (SpeechRecognitionEvent) -> Unit) {
    runOnMain {
      if (released) return@runOnMain
      eventListener = onEvent
      if (!isRecognitionAvailable) {
        onEvent(SpeechRecognitionEvent.Error(SpeechRecognitionError.Unknown))
        return@runOnMain
      }

      val recognizer =
          speechRecognizer
              ?: SpeechRecognizer.createSpeechRecognizer(applicationContext).also {
                it.setRecognitionListener(recognitionListener)
                speechRecognizer = it
              }
      recognizer.startListening(
          Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
          }
      )
    }
  }

  override fun stopListening() {
    runOnMain {
      speechRecognizer?.cancel()
      eventListener = null
    }
  }

  override fun release() {
    runOnMain {
      if (released) return@runOnMain
      released = true
      eventListener = null
      speechRecognizer?.cancel()
      speechRecognizer?.destroy()
      speechRecognizer = null
    }
  }

  private val recognitionListener =
      object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
          eventListener?.invoke(SpeechRecognitionEvent.Ready)
        }

        override fun onPartialResults(partialResults: Bundle?) {
          partialResults.bestRecognition()?.let { text ->
            eventListener?.invoke(SpeechRecognitionEvent.PartialResult(text))
          }
        }

        override fun onResults(results: Bundle?) {
          results.bestRecognition()?.let { text ->
            eventListener?.invoke(SpeechRecognitionEvent.FinalResult(text))
          }
        }

        override fun onError(error: Int) {
          eventListener?.invoke(SpeechRecognitionEvent.Error(error.toSpeechRecognitionError()))
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
      }

  private fun Bundle?.bestRecognition(): String? =
      this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
          ?.takeIf(String::isNotEmpty)

  private fun Int.toSpeechRecognitionError(): SpeechRecognitionError =
      when (this) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechRecognitionError.PermissionDenied
        SpeechRecognizer.ERROR_NO_MATCH -> SpeechRecognitionError.NoMatch
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechRecognitionError.Timeout
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechRecognitionError.Busy
        SpeechRecognizer.ERROR_CLIENT -> SpeechRecognitionError.Client
        else -> SpeechRecognitionError.Unknown
      }

  private fun runOnMain(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      action()
    } else {
      mainHandler.post(action)
    }
  }
}
