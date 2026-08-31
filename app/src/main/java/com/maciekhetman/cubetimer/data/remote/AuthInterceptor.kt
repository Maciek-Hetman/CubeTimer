package com.maciekhetman.cubetimer.data.remote

import com.maciekhetman.cubetimer.data.auth.TokenStorage
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp Interceptor that decorates outgoing requests with device identification,
 * sync protocol version, content type, and authentication Bearer token.
 */
class AuthInterceptor(
    private val tokenStorage: TokenStorage
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val builder = originalRequest.newBuilder()

        // 1. Attach Device ID
        builder.header(HEADER_DEVICE_ID, tokenStorage.getDeviceId())

        // 2. Attach Sync Protocol Version
        builder.header(HEADER_SYNC_PROTOCOL, PROTOCOL_VERSION_2)

        // 3. Attach Content-Type for mutating requests if not specified
        if (originalRequest.body != null && originalRequest.header(HEADER_CONTENT_TYPE) == null) {
            builder.header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
        }

        // 4. Attach Bearer Access Token if present and not already attached
        val accessToken = tokenStorage.getAccessToken()
        if (!accessToken.isNullOrBlank() && originalRequest.header(HEADER_AUTHORIZATION) == null) {
            builder.header(HEADER_AUTHORIZATION, "Bearer $accessToken")
        }

        return chain.proceed(builder.build())
    }

    companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
        const val HEADER_DEVICE_ID = "X-Device-Id"
        const val HEADER_SYNC_PROTOCOL = "X-Sync-Protocol"
        const val HEADER_CONTENT_TYPE = "Content-Type"

        const val PROTOCOL_VERSION_2 = "2"
        const val CONTENT_TYPE_JSON = "application/json"
    }
}
