package com.meta.wearable.dat.externalsampleapps.cameraaccess.analysis

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class HttpFoodAnalysisClient(
    baseUrl: String,
    private val httpClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val logger: FoodAnalysisLogger = AndroidFoodAnalysisLogger,
) : FoodAnalysisClient {
  private val endpoint: HttpUrl? =
      baseUrl.trim().takeIf(String::isNotEmpty)?.trimEnd('/')?.let {
        "$it/v1/food-analysis".toHttpUrlOrNull()
      }

  override suspend fun analyzePhoto(jpegBytes: ByteArray): FoodAnalysisOutcome {
    val target = endpoint ?: return FoodAnalysisOutcome.Failure(FoodAnalysisError.MissingServerUrl)
    val request =
        Request.Builder()
            .url(target)
            .post(
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "photo",
                        "capture.jpg",
                        jpegBytes.toRequestBody(JPEG_MEDIA_TYPE),
                    )
                    .build()
            )
            .build()
    val call = httpClient.newCall(request)
    val startedAtNanos = System.nanoTime()
    logger.uploadStarted()

    return try {
      call.await().use { response ->
        val elapsedMs = elapsedMillisSince(startedAtNanos)
        if (!response.isSuccessful) {
          val error = FoodAnalysisError.HttpStatus(response.code)
          logger.requestFailed(FoodAnalysisLogStage.Upload, error, response.code, elapsedMs)
          return@use FoodAnalysisOutcome.Failure(error)
        }

        val responseBody = response.body?.string()
        if (responseBody.isNullOrBlank()) {
          logger.requestFailed(
              FoodAnalysisLogStage.Parse,
              FoodAnalysisError.InvalidResponse,
              response.code,
              elapsedMs,
          )
          return@use FoodAnalysisOutcome.Failure(FoodAnalysisError.InvalidResponse)
        }

        val outcome =
            try {
              json.decodeFromString<FoodAnalysisResponseDto>(responseBody).toResult()
            } catch (_: SerializationException) {
              FoodAnalysisOutcome.Failure(FoodAnalysisError.InvalidResponse)
            } catch (_: IllegalArgumentException) {
              FoodAnalysisOutcome.Failure(FoodAnalysisError.InvalidResponse)
            }

        when (outcome) {
          is FoodAnalysisOutcome.Success ->
              logger.uploadSucceeded(outcome.result.requestId, response.code, elapsedMs)
          is FoodAnalysisOutcome.Failure ->
              logger.requestFailed(
                  FoodAnalysisLogStage.Parse,
                  outcome.error,
                  response.code,
                  elapsedMs,
              )
        }
        outcome
      }
    } catch (cancelled: CancellationException) {
      call.cancel()
      throw cancelled
    } catch (_: SocketTimeoutException) {
      val error = FoodAnalysisError.Timeout
      logger.requestFailed(
          FoodAnalysisLogStage.Upload,
          error,
          null,
          elapsedMillisSince(startedAtNanos),
      )
      FoodAnalysisOutcome.Failure(error)
    } catch (_: IOException) {
      val error =
          if (call.isCanceled()) FoodAnalysisError.Cancelled else FoodAnalysisError.Network
      logger.requestFailed(
          FoodAnalysisLogStage.Upload,
          error,
          null,
          elapsedMillisSince(startedAtNanos),
      )
      FoodAnalysisOutcome.Failure(error)
    }
  }

  private fun elapsedMillisSince(startedAtNanos: Long): Long =
      TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

  private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) {
              continuation.resumeWith(Result.failure(e))
            }
          }

          override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, responseToClose, _ -> responseToClose.close() }
          }
        }
    )
  }

  private companion object {
    val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
  }
}
