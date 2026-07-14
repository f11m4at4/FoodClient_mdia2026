package com.meta.wearable.dat.externalsampleapps.cameraaccess.analysis

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpFoodAnalysisClientTest {
  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun `analyzePhoto sends jpeg multipart and limits recipe to 500 characters`() = runTest {
    val longRecipe = "가".repeat(650)
    server.enqueue(
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """{"foodName":"김치볶음밥","recipeText":"$longRecipe","requestId":"req-1"}"""
            )
    )

    val outcome = createClient().analyzePhoto("jpeg-content".encodeToByteArray())

    assertTrue(outcome is FoodAnalysisOutcome.Success)
    val result = (outcome as FoodAnalysisOutcome.Success).result
    assertEquals("김치볶음밥", result.foodName)
    assertEquals(MAX_RECIPE_TEXT_LENGTH, result.recipeText.length)
    assertEquals("req-1", result.requestId)

    val request = server.takeRequest(1, TimeUnit.SECONDS)!!
    assertEquals("/v1/food-analysis", request.path)
    val body = request.body.readUtf8()
    assertTrue(body.contains("name=\"photo\""))
    assertTrue(body.contains("filename=\"capture.jpg\""))
    assertTrue(body.contains("Content-Type: image/jpeg"))
  }

  @Test
  fun `analyzePhoto maps http error without parsing body`() = runTest {
    server.enqueue(MockResponse().setResponseCode(503).setBody("service unavailable"))
    val logger = RecordingLogger()

    val outcome = createClient(logger).analyzePhoto(byteArrayOf(1, 2, 3))

    assertEquals(
        FoodAnalysisOutcome.Failure(FoodAnalysisError.HttpStatus(503)),
        outcome,
    )
    assertEquals(FoodAnalysisLogStage.Upload, logger.failureStage)
  }

  @Test
  fun `analyzePhoto maps invalid json`() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))
    val logger = RecordingLogger()

    val outcome = createClient(logger).analyzePhoto(byteArrayOf(1))

    assertEquals(
        FoodAnalysisOutcome.Failure(FoodAnalysisError.InvalidResponse),
        outcome,
    )
    assertEquals(FoodAnalysisLogStage.Parse, logger.failureStage)
  }

  @Test
  fun `analyzePhoto maps blank food name to food not detected`() = runTest {
    server.enqueue(
        MockResponse()
            .setResponseCode(200)
            .setBody(
                """{"foodName":"  ","recipeText":"설명","requestId":"req-2"}"""
            )
    )
    val logger = RecordingLogger()

    val outcome = createClient(logger).analyzePhoto(byteArrayOf(1))

    assertEquals(
        FoodAnalysisOutcome.Failure(FoodAnalysisError.FoodNotDetected),
        outcome,
    )
    assertEquals(FoodAnalysisLogStage.Parse, logger.failureStage)
  }

  @Test
  fun `analyzePhoto reports missing server url without making request`() = runTest {
    val client = HttpFoodAnalysisClient(baseUrl = "", logger = NoOpLogger)

    val outcome = client.analyzePhoto(byteArrayOf(1))

    assertEquals(
        FoodAnalysisOutcome.Failure(FoodAnalysisError.MissingServerUrl),
        outcome,
    )
    assertEquals(0, server.requestCount)
  }

  @Test
  fun `analyzePhoto maps read timeout`() = runTest {
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
    val logger = RecordingLogger()
    val client =
        HttpFoodAnalysisClient(
            baseUrl = server.url("/").toString(),
            httpClient =
                OkHttpClient.Builder()
                    .connectTimeout(1, TimeUnit.SECONDS)
                    .readTimeout(100, TimeUnit.MILLISECONDS)
                    .build(),
            logger = logger,
        )

    val outcome = client.analyzePhoto(byteArrayOf(1))

    assertEquals(FoodAnalysisOutcome.Failure(FoodAnalysisError.Timeout), outcome)
    assertEquals(FoodAnalysisLogStage.Upload, logger.failureStage)
  }

  @Test
  @OptIn(ExperimentalCoroutinesApi::class)
  fun `cancelling coroutine cancels okhttp call`() = runTest {
    val cancellationObserved = AtomicBoolean(false)
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
    val client =
        HttpFoodAnalysisClient(
            baseUrl = server.url("/").toString(),
            httpClient =
                OkHttpClient.Builder()
                    .eventListener(
                        object : EventListener() {
                          override fun canceled(call: Call) {
                            cancellationObserved.set(true)
                          }
                        }
                    )
                    .build(),
            logger = NoOpLogger,
        )
    val job = launch { client.analyzePhoto(byteArrayOf(1)) }
    runCurrent()
    assertTrue(server.takeRequest(1, TimeUnit.SECONDS) != null)

    job.cancelAndJoin()

    assertTrue(cancellationObserved.get())
  }

  private fun createClient(logger: FoodAnalysisLogger = NoOpLogger): HttpFoodAnalysisClient =
      HttpFoodAnalysisClient(
          baseUrl = server.url("/").toString(),
          httpClient =
              OkHttpClient.Builder()
                  .connectTimeout(1, TimeUnit.SECONDS)
                  .readTimeout(1, TimeUnit.SECONDS)
                  .build(),
          logger = logger,
      )

  private class RecordingLogger : FoodAnalysisLogger {
    var failureStage: FoodAnalysisLogStage? = null
      private set

    override fun uploadStarted() = Unit

    override fun uploadSucceeded(requestId: String, statusCode: Int, elapsedMs: Long) = Unit

    override fun requestFailed(
        stage: FoodAnalysisLogStage,
        error: FoodAnalysisError,
        statusCode: Int?,
        elapsedMs: Long,
    ) {
      failureStage = stage
    }
  }

  private object NoOpLogger : FoodAnalysisLogger {
    override fun uploadStarted() = Unit

    override fun uploadSucceeded(requestId: String, statusCode: Int, elapsedMs: Long) = Unit

    override fun requestFailed(
        stage: FoodAnalysisLogStage,
        error: FoodAnalysisError,
        statusCode: Int?,
        elapsedMs: Long,
    ) = Unit
  }
}
