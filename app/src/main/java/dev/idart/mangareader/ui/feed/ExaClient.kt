package dev.idart.mangareader.ui.feed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

data class ExaResult(
    val title: String,
    val url: String,
    val text: String,
)

/**
 * Minimal Exa neural-search client (`POST /search`). Blocking: callers must
 * dispatch to [kotlinx.coroutines.Dispatchers.IO].
 */
class ExaClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    fun search(apiKey: String, query: String, numResults: Int = 8): List<ExaResult> {
        val payload = buildJsonObject {
            put("query", JsonPrimitive(query))
            put("numResults", JsonPrimitive(numResults))
            put(
                "contents",
                buildJsonObject {
                    put("text", JsonPrimitive(true))
                    put("maxCharacters", JsonPrimitive(2000))
                },
            )
        }.toString()
        val request = Request.Builder()
            .url("https://api.exa.ai/search")
            .addHeader("x-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IllegalStateException("Resposta vazia")
            if (!response.isSuccessful) {
                throw IllegalStateException("Exa HTTP ${response.code}: ${body.take(200)}")
            }
            val data: JsonArray = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
                ?: json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                ?: throw IllegalStateException("Resposta Exa sem resultados")
            return data.mapNotNull { element ->
                runCatching {
                    val obj: JsonObject = element.jsonObject
                    ExaResult(
                        title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        url = obj["url"]?.jsonPrimitive?.content.orEmpty(),
                        text = obj["text"]?.jsonPrimitive?.content.orEmpty(),
                    )
                }.getOrNull()
            }
        }
    }
}
