package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

internal object VoiceCaptureTriggerMatcher {
  fun matches(recognizedText: String, triggerPhrase: String): Boolean =
      normalize(recognizedText) == normalize(triggerPhrase)

  internal fun normalize(text: String): String =
      text.filterNot { it.isWhitespace() || it.isSentencePunctuation() }

  private fun Char.isSentencePunctuation(): Boolean =
      when (Character.getType(this)) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt() -> true
        else -> false
      }
}
