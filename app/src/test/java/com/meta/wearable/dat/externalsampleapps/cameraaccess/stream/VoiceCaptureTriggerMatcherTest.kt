package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptureTriggerMatcherTest {
  @Test
  fun `normalize removes whitespace and punctuation`() {
    assertEquals("사진찍어줘", VoiceCaptureTriggerMatcher.normalize(" 사진, 찍어줘?! "))
  }

  @Test
  fun `matches trigger phrase after whitespace and punctuation cleanup`() {
    assertTrue(
        VoiceCaptureTriggerMatcher.matches(
            recognizedText = "사진 찍어줘.",
            triggerPhrase = "사진찍어줘",
        )
    )
  }

  @Test
  fun `does not match when extra words remain after normalization`() {
    assertFalse(
        VoiceCaptureTriggerMatcher.matches(
            recognizedText = "이거 사진 찍어줘",
            triggerPhrase = "사진찍어줘",
        )
    )
  }
}
