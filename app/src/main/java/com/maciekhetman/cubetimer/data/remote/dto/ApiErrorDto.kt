package com.maciekhetman.cubetimer.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Standard CubeSync error envelope returned for all 4xx and 5xx API errors:
 * {
 *   "error": {
 *     "code": "invalid_credentials",
 *     "message": "email or password is incorrect"
 *   }
 * }
 */
@Serializable
data class ApiErrorResponse(
    @SerialName("error")
    val error: ApiErrorDetail
)

@Serializable
data class ApiErrorDetail(
    @SerialName("code")
    val code: String,
    @SerialName("message")
    val message: String
)
