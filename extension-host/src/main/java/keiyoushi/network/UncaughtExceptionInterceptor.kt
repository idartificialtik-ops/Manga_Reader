package keiyoushi.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Host implementation of the marker interceptor required by keiyoushi-built
 * extensions ("UncaughtExceptionInterceptor must be present in default
 * client"). Pass-through: call exceptions already propagate to the caller.
 */
class UncaughtExceptionInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(chain.request())
    }
}
