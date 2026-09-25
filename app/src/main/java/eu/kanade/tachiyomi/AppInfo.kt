package eu.kanade.tachiyomi

import dev.idart.mangareader.BuildConfig

/**
 * Real host implementation (mirrors extensions-lib 1.x API shape).
 *
 * Used by extensions.
 */
object AppInfo {
    /**
     * Version code of the host application. May be useful for sharing as User-Agent information.
     * Note that this value differs between forks so logic should not rely on it.
     *
     * @since extension-lib 1.3
     */
    fun getVersionCode(): Int = BuildConfig.VERSION_CODE

    /**
     * Version name of the host application. May be useful for sharing as User-Agent information.
     * Note that this value differs between forks so logic should not rely on it.
     *
     * @since extension-lib 1.5
     */
    fun getVersionName(): String = BuildConfig.VERSION_NAME

    /**
     * A list of supported image MIME types by the reader.
     * e.g. ["image/jpeg", "image/png", ...]
     *
     * @since extension-lib 1.5
     */
    fun getSupportedImageMimeTypes(): List<String> {
        return listOf(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "image/avif",
        )
    }
}
