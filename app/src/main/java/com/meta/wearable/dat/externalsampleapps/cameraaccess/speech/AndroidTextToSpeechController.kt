package com.meta.wearable.dat.externalsampleapps.cameraaccess.speech

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

class AndroidTextToSpeechController(context: Context) : TextToSpeechController {
  private val applicationContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private var textToSpeech: TextToSpeech? = null
  private var ready = false
  private var released = false
  private var activeUtteranceId: String? = null
  private var onDone: (() -> Unit)? = null
  private var onError: (() -> Unit)? = null

  init {
    runOnMain {
      if (released) return@runOnMain
      textToSpeech =
          TextToSpeech(applicationContext) { status ->
            val engine = textToSpeech ?: return@TextToSpeech
            val languageResult =
                if (status == TextToSpeech.SUCCESS) engine.setLanguage(Locale.KOREAN)
                else TextToSpeech.LANG_NOT_SUPPORTED
            ready =
                status == TextToSpeech.SUCCESS &&
                    languageResult != TextToSpeech.LANG_MISSING_DATA &&
                    languageResult != TextToSpeech.LANG_NOT_SUPPORTED
          }
      textToSpeech?.setOnUtteranceProgressListener(progressListener)
    }
  }

  override fun speak(text: String, onDone: () -> Unit, onError: () -> Unit) {
    runOnMain {
      val engine = textToSpeech
      if (released || !ready || engine == null) {
        onError()
        return@runOnMain
      }

      val utteranceId = UUID.randomUUID().toString()
      activeUtteranceId = utteranceId
      this.onDone = onDone
      this.onError = onError
      val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
      if (result == TextToSpeech.ERROR) {
        completeWithError(utteranceId)
      }
    }
  }

  override fun stop() {
    runOnMain {
      textToSpeech?.stop()
      clearCallbacks()
    }
  }

  override fun release() {
    runOnMain {
      if (released) return@runOnMain
      released = true
      ready = false
      textToSpeech?.stop()
      textToSpeech?.shutdown()
      textToSpeech = null
      clearCallbacks()
    }
  }

  @Suppress("OVERRIDE_DEPRECATION")
  private val progressListener =
      object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
          utteranceId ?: return
          runOnMain {
            if (activeUtteranceId == utteranceId) {
              val callback = onDone
              clearCallbacks()
              callback?.invoke()
            }
          }
        }

        override fun onError(utteranceId: String?) {
          utteranceId ?: return
          completeWithError(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
          utteranceId ?: return
          completeWithError(utteranceId)
        }
      }

  private fun completeWithError(utteranceId: String) {
    runOnMain {
      if (activeUtteranceId == utteranceId) {
        val callback = onError
        clearCallbacks()
        callback?.invoke()
      }
    }
  }

  private fun clearCallbacks() {
    activeUtteranceId = null
    onDone = null
    onError = null
  }

  private fun runOnMain(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      action()
    } else {
      mainHandler.post(action)
    }
  }
}
