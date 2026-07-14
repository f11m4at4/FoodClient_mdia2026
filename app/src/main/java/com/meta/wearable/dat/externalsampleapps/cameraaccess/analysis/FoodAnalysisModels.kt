package com.meta.wearable.dat.externalsampleapps.cameraaccess.analysis

import kotlinx.serialization.Serializable

const val MAX_RECIPE_TEXT_LENGTH = 500

data class FoodAnalysisResult(
    val foodName: String,
    val recipeText: String,
    val requestId: String,
)

sealed interface FoodAnalysisOutcome {
  data class Success(val result: FoodAnalysisResult) : FoodAnalysisOutcome

  data class Failure(val error: FoodAnalysisError) : FoodAnalysisOutcome
}

sealed interface FoodAnalysisError {
  data object MissingServerUrl : FoodAnalysisError

  data object Timeout : FoodAnalysisError

  data object Cancelled : FoodAnalysisError

  data object Network : FoodAnalysisError

  data class HttpStatus(val statusCode: Int) : FoodAnalysisError

  data object InvalidResponse : FoodAnalysisError

  data object FoodNotDetected : FoodAnalysisError
}

@Serializable
internal data class FoodAnalysisResponseDto(
    val foodName: String,
    val recipeText: String,
    val requestId: String = "",
)

internal fun FoodAnalysisResponseDto.toResult(): FoodAnalysisOutcome {
  val normalizedFoodName = foodName.trim()
  if (normalizedFoodName.isEmpty()) {
    return FoodAnalysisOutcome.Failure(FoodAnalysisError.FoodNotDetected)
  }

  val normalizedRecipe = recipeText.trim()
  if (normalizedRecipe.isEmpty()) {
    return FoodAnalysisOutcome.Failure(FoodAnalysisError.InvalidResponse)
  }

  return FoodAnalysisOutcome.Success(
      FoodAnalysisResult(
          foodName = normalizedFoodName,
          recipeText = normalizedRecipe.take(MAX_RECIPE_TEXT_LENGTH),
          requestId = requestId.trim(),
      )
  )
}
