package com.meta.wearable.dat.externalsampleapps.cameraaccess.speech

interface TextToSpeechController {
  fun speak(text: String, onDone: () -> Unit, onError: () -> Unit)

  fun stop()

  fun release()
}
