package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureRequestGateTest {
  private var nowMs = 0L

  @Test
  fun `gate accepts first request then blocks duplicates until released`() {
    val gate = CaptureRequestGate(cooldownMs = 1_500L, nowMs = { nowMs })

    assertEquals(CaptureRequestDecision.Accepted, gate.tryAcquire())
    assertEquals(CaptureRequestDecision.InFlight, gate.tryAcquire())
  }

  @Test
  fun `gate enforces cooldown after release`() {
    val gate = CaptureRequestGate(cooldownMs = 1_500L, nowMs = { nowMs })

    assertEquals(CaptureRequestDecision.Accepted, gate.tryAcquire())
    gate.release()

    nowMs = 1_499L
    assertEquals(CaptureRequestDecision.Cooldown, gate.tryAcquire())

    nowMs = 1_500L
    assertEquals(CaptureRequestDecision.Accepted, gate.tryAcquire())
  }

  @Test
  fun `reset clears in flight and cooldown state`() {
    val gate = CaptureRequestGate(cooldownMs = 1_500L, nowMs = { nowMs })

    assertEquals(CaptureRequestDecision.Accepted, gate.tryAcquire())
    gate.release()
    gate.reset()

    assertEquals(CaptureRequestDecision.Accepted, gate.tryAcquire())
  }
}
