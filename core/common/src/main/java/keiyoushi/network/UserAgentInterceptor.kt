package keiyoushi.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Host implementation of the User-Agent marker interceptor required by some
 * keiyoushi-built extensions ("UserAgentInterceptor must be present in
 * default client"). Sets the default User-Agent when the request has none.
 */
class UserAgentInterceptor(
    private val userAgent: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("User-Agent") != null) {
            return chain.proceed(request)
        }
        val headers = request.headers.newBuilder()
            .add("User-Agent", userAgent)
            .build()
        return chain.proceed(request.newBuilder().headers(headers).build())
    }
}
