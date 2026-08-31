package com.maciekhetman.cubetimer.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object NetworkModule {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun provideOkHttpClient(
        authInterceptor: okhttp3.Interceptor? = null,
        authenticator: okhttp3.Authenticator? = null,
        enableLogging: Boolean = false
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)

        if (authInterceptor != null) {
            builder.addInterceptor(authInterceptor)
        }

        if (enableLogging) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            builder.addInterceptor(logging)
        }

        if (authenticator != null) {
            builder.authenticator(authenticator)
        }

        return builder.build()
    }

    fun provideAuthApiService(
        baseUrl: String,
        okHttpClient: OkHttpClient,
        json: Json = NetworkModule.json
    ): CubeSyncAuthApiService {
        val contentType = "application/json".toMediaType()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

        return retrofit.create(CubeSyncAuthApiService::class.java)
    }

    fun provideCubeSyncApiClient(
        apiService: CubeSyncAuthApiService
    ): CubeSyncApiClient {
        return CubeSyncApiClientImpl(apiService)
    }
}
