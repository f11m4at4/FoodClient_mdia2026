package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.os.SystemClock

internal class CaptureRequestGate(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
  private var inFlight = false
  private var nextAllowedAtMs = 0L

  fun tryAcquire(): CaptureRequestDecision {
    if (inFlight) {
      return CaptureRequestDecision.InFlight
    }

    if (nowMs() < nextAllowedAtMs) {
      return CaptureRequestDecision.Cooldown
    }

    inFlight = true
    return CaptureRequestDecision.Accepted
  }

  fun release() {
    inFlight = false
    nextAllowedAtMs = nowMs() + cooldownMs
  }

  fun reset() {
    inFlight = false
    nextAllowedAtMs = 0L
  }

  private companion object {
    const val DEFAULT_COOLDOWN_MS = 1_500L
  }
}

internal enum class CaptureRequestDecision {
  Accepted,
  InFlight,
  Cooldown,
}
