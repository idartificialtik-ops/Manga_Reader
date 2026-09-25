package dev.idart.mangareader.sources.extension

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Calls 1.6-style suspend source methods by name.
 *
 * Some current extensions (e.g. Lunar Manga) are compiled against the
 * suspend-based extensions API with obfuscated base classes: they implement
 * the host [eu.kanade.tachiyomi.source] interfaces but their suspend members
 * (`getPopularManga`, `getMangaUpdate`, …) are not visible through any host
 * supertype, and the inherited legacy Observable bridges hit unimplemented
 * abstracts ([AbstractMethodError]). Their method *names* are kept, so they
 * can be invoked reflectively.
 *
 * A suspend function `foo(a: Int)` is looked up as a Java method
 * `foo(int, Continuation)` (parameter count = args + 1) and driven with the
 * standard `COROUTINE_SUSPENDED` protocol.
 */
internal suspend fun Any.invokeSuspendMethod(name: String, vararg args: Any?): Any? {
    val method = this.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size + 1 }
        ?: throw NoSuchMethodException(
            "${this.javaClass.name} has no suspend method $name/${args.size}",
        )
    return suspendCancellableCoroutine { cont ->
        try {
            val result = method.invoke(this, *args, cont)
            if (result !== COROUTINE_SUSPENDED) {
                cont.resume(result)
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            cont.resumeWithException(e.cause ?: e)
        }
    }
}
