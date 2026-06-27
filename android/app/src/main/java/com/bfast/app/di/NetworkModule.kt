package com.bfast.app.di

import com.bfast.app.data.remote.AuthInterceptor
import com.bfast.app.data.remote.BFastApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // IMPORTANT: Replace this with your machine's local IP address or 10.0.2.2 for Android emulator
    private const val BASE_URL = "http://192.168.1.5:3000/api/v1/"

    @Provides
    @Singleton
    fun provideOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor(authInterceptor)
            .addInterceptor(RetryInterceptor(maxRetries = 3))
            .connectTimeout(15, TimeUnit.SECONDS)   // 15s connect (was 30s — too long)
            .readTimeout(30, TimeUnit.SECONDS)       // 30s read for slow networks
            .writeTimeout(30, TimeUnit.SECONDS)      // 30s write for uploads
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideBFastApi(retrofit: Retrofit): BFastApi {
        return retrofit.create(BFastApi::class.java)
    }
}

/**
 * Retry interceptor with exponential backoff for transient network failures.
 *
 * Automatically retries on:
 *   - SocketTimeoutException (slow network)
 *   - IOException (connection dropped)
 *   - HTTP 500, 502, 503, 504 (server errors)
 *
 * Does NOT retry on:
 *   - HTTP 400, 401, 403, 404, 409 (client errors — these are intentional)
 *   - UnknownHostException (no internet at all)
 *
 * Compatible with mobile data, WiFi, and slow 2G/3G connections common in India.
 */
class RetryInterceptor(private val maxRetries: Int = 3) : Interceptor {

    companion object {
        /** HTTP status codes that should be retried (server-side transient errors). */
        private val RETRYABLE_STATUS_CODES = setOf(500, 502, 503, 504)

        /** Base delay for exponential backoff (ms). */
        private const val BASE_DELAY_MS = 500L
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var lastException: IOException? = null
        var lastResponse: Response? = null

        for (attempt in 0..maxRetries) {
            try {
                // Close previous response to avoid connection leaks
                lastResponse?.close()

                val response = chain.proceed(request)

                // If it's a client error (4xx), don't retry — return immediately
                if (response.code in 400..499) {
                    return response
                }

                // If it's a retryable server error, retry
                if (response.code in RETRYABLE_STATUS_CODES && attempt < maxRetries) {
                    response.close()
                    val delay = BASE_DELAY_MS * (1L shl attempt) // Exponential backoff
                    Thread.sleep(delay.coerceAtMost(5000L))
                    lastResponse = null
                    continue
                }

                return response

            } catch (e: SocketTimeoutException) {
                // Timeout — retry
                lastException = e
                if (attempt < maxRetries) {
                    val delay = BASE_DELAY_MS * (1L shl attempt)
                    Thread.sleep(delay.coerceAtMost(5000L))
                }
            } catch (e: UnknownHostException) {
                // No internet — don't retry, throw immediately with a clear message
                throw IOException(
                    "Unable to connect to BFast servers. Please check your internet connection " +
                    "(WiFi or mobile data) and try again.", e
                )
            } catch (e: IOException) {
                // Other IO errors (connection reset, etc.) — retry
                lastException = e
                if (attempt < maxRetries) {
                    val delay = BASE_DELAY_MS * (1L shl attempt)
                    Thread.sleep(delay.coerceAtMost(5000L))
                }
            }
        }

        // All retries exhausted
        throw lastException ?: IOException(
            "The request failed after $maxRetries attempts. This might be due to a weak network connection. " +
            "Please move to an area with better signal and try again."
        )
    }
}
