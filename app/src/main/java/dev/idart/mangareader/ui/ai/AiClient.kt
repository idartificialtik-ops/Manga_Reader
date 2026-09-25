package dev.idart.mangareader.ui.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

/**
 * Minimal OpenAI-compatible `/chat/completions` client (works with OpenAI,
 * DeepSeek, Groq, OpenRouter, Ollama, …). Function-calling tools supported.
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall> = emptyList(),
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition,
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonElement,
)

/** HTTP error carrying the status code, so callers can retry rate limits. */
class RateLimitedException(val code: Int, message: String) : IllegalStateException(message)

data class AssistantTurn(
    val text: String,
    val mangaHits: List<MangaHit> = emptyList(),
)

/** One SSE event: a slice of text plus tool-call fragments. */
data class StreamChunk(
    val textDelta: String = "",
    val toolDeltas: List<ToolDelta> = emptyList(),
)

data class ToolDelta(
    val index: Int,
    val id: String?,
    val name: String?,
    val arguments: String?,
)

data class MangaHit(
    val sourceId: Long,
    val sourceName: String,
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val genre: String? = null,
    val status: Int = 0,
)

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

class AiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    },
) {

    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        jsonMode: Boolean = false,
    ): ChatMessage {
        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            put(
                "messages",
                JsonArray(messages.map { json.encodeToJsonElement(ChatMessage.serializer(), it) }),
            )
            if (tools.isNotEmpty()) {
                put(
                    "tools",
                    JsonArray(tools.map { json.encodeToJsonElement(ToolDefinition.serializer(), it) }),
                )
            }
            if (jsonMode) {
                put(
                    "response_format",
                    buildJsonObject { put("type", JsonPrimitive("json_object")) },
                )
            }
            put("temperature", JsonPrimitive(0.3))
        }.toString()
        val request = Request.Builder()
            .url(chatUrl(baseUrl))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executeChat(request)
    }

    private fun executeChat(request: Request): ChatMessage {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IllegalStateException("Resposta vazia")
            if (!response.isSuccessful) {
                val detail = runCatching {
                    json.parseToJsonElement(body).jsonObject["error"]
                        ?.jsonObject?.get("message")?.jsonPrimitive?.content
                }.getOrNull()
                throw RateLimitedException(response.code, "HTTP ${response.code}: ${detail ?: body.take(200)}")
            }
            val root = json.parseToJsonElement(body).jsonObject
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw IllegalStateException("Sem choices na resposta")
            val message = choice["message"]?.jsonObject
                ?: throw IllegalStateException("Sem message na resposta")
            // Lenient decode: some providers (Ollama, Groq, …) return
            // "tool_calls": null or non-string arguments.
            val text = message["content"]
                ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                ?.jsonPrimitive?.content
            val calls = message["tool_calls"]
                ?.takeIf { it is JsonArray }
                ?.jsonArray
                ?.mapNotNull { element ->
                    runCatching {
                        val obj = element.jsonObject
                        val fn = obj["function"]?.jsonObject
                            ?: return@mapNotNull null
                        val args = fn["arguments"]?.let {
                            if (it is JsonPrimitive && it.isString) it.content else it.toString()
                        } ?: "{}"
                        ToolCall(
                            id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                            function = FunctionCall(
                                name = fn["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                                arguments = args,
                            ),
                        )
                    }.getOrNull()
                }
                .orEmpty()
            return ChatMessage(
                role = message["role"]?.jsonPrimitive?.content ?: "assistant",
                content = text,
                toolCalls = calls,
            )
        }
    }

    /**
     * Lists model ids from an OpenAI-compatible provider (`GET /models`).
     */
    fun listModels(baseUrl: String, apiKey: String): List<String> {
        val request = Request.Builder()
            .url(modelsUrl(baseUrl))
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IllegalStateException("Resposta vazia")
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${body.take(200)}")
            }
            val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                ?: throw IllegalStateException("Resposta sem 'data'")
            return data.mapNotNull { element ->
                runCatching { element.jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()
            }.distinct().sorted()
        }
    }

    fun toolJson(
        properties: Map<String, Pair<String, String>>,
        requiredExtra: List<String> = emptyList(),
    ): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    properties.forEach { (name, typeAndDesc) ->
                        put(
                            name,
                            buildJsonObject {
                                put("type", JsonPrimitive(typeAndDesc.first))
                                put("description", JsonPrimitive(typeAndDesc.second))
                            },
                        )
                    }
                },
            )
            val required = requiredExtra.filter { it in properties }
            if (required.isNotEmpty()) {
                put("required", JsonArray(required.map { JsonPrimitive(it) }))
            }
        }
    }

    fun argString(argsJson: String, key: String): String? {
        return runCatching {
            json.parseToJsonElement(argsJson).jsonObject[key]?.jsonPrimitive?.content
        }.getOrNull()
    }

    fun argLong(argsJson: String, key: String): Long? {
        return runCatching {
            json.parseToJsonElement(argsJson).jsonObject[key]?.jsonPrimitive?.content?.toLongOrNull()
        }.getOrNull()
    }

    /**
     * Streaming `/chat/completions` (SSE). Calls [onChunk] per event with
     * text deltas and tool-call deltas (accumulate `arguments` by `index`).
     * Blocking: callers must dispatch to IO.
     */
    suspend fun chatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        onChunk: suspend (StreamChunk) -> Unit,
    ) {
        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            put(
                "messages",
                JsonArray(messages.map { json.encodeToJsonElement(ChatMessage.serializer(), it) }),
            )
            if (tools.isNotEmpty()) {
                put(
                    "tools",
                    JsonArray(tools.map { json.encodeToJsonElement(ToolDefinition.serializer(), it) }),
                )
            }
            put("temperature", JsonPrimitive(0.3))
            put("stream", JsonPrimitive(true))
        }.toString()
        val request = Request.Builder()
            .url(chatUrl(baseUrl))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val detail = runCatching {
                    json.parseToJsonElement(body).jsonObject["error"]
                        ?.jsonObject?.get("message")?.jsonPrimitive?.content
                }.getOrNull()
                throw IllegalStateException("HTTP ${response.code}: ${detail ?: body.take(200)}")
            }
            val body = response.body ?: throw IllegalStateException("Resposta vazia")
            // Sem `use` aninhado: onChunk é suspend e `use` exige lambda comum.
            val buffered = body.source().buffer()
            var events = 0
            val raw = StringBuilder()
            try {
                var line = buffered.readUtf8Line()
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("data:")) {
                        val data = trimmed.removePrefix("data:").trim()
                        if (data == "[DONE]") return
                        if (data.isNotEmpty()) {
                            val chunk = parseStreamEvent(data)
                            if (chunk != null) {
                                events++
                                onChunk(chunk)
                            } else {
                                raw.append(data)
                            }
                        }
                    } else if (trimmed.isNotEmpty()) {
                        raw.append(trimmed)
                    }
                    line = buffered.readUtf8Line()
                }
            } finally {
                runCatching { buffered.close() }
            }
            if (events == 0) {
                // Provedor ignorou stream:true e mandou o JSON fechado (ou
                // outro formato): tenta como resposta normal.
                android.util.Log.d("AiClient", "stream sem eventos, corpo: ${raw.take(300)}")
                parseFullResponse(raw.toString())?.let { onChunk(it) }
            }
        }
    }

    /**
     * Parses a complete (non-SSE) chat response into a single chunk.
     * Null when it isn't a recognizable completion payload.
     */
    private fun parseFullResponse(data: String): StreamChunk? {
        return runCatching {
            val message = json.parseToJsonElement(data).jsonObject["choices"]
                ?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject ?: return null
            val text = message["content"]?.stringOrNull().orEmpty()
            val calls = message["tool_calls"]?.takeIf { it is JsonArray }?.jsonArray
                ?.mapIndexedNotNull { index, element ->
                    runCatching {
                        val obj = element.jsonObject
                        val fn = obj["function"]?.jsonObject
                        val args = fn?.get("arguments")?.let {
                            if (it is JsonPrimitive && it.isString) it.content else it.toString()
                        } ?: "{}"
                        ToolDelta(
                            index = index,
                            id = obj["id"]?.stringOrNull(),
                            name = fn?.get("name")?.stringOrNull(),
                            arguments = args,
                        )
                    }.getOrNull()
                }
                .orEmpty()
            if (text.isEmpty() && calls.isEmpty()) null else StreamChunk(text, calls)
        }.getOrNull()
    }

    private fun parseStreamEvent(data: String): StreamChunk? {
        return runCatching {
            val choice = json.parseToJsonElement(data).jsonObject["choices"]
                ?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val delta = choice["delta"]?.jsonObject ?: return null
            val text = delta["content"]?.stringOrNull().orEmpty()
            val calls = delta["tool_calls"]?.takeIf { it is JsonArray }?.jsonArray
                ?.mapNotNull { element ->
                    runCatching {
                        val obj = element.jsonObject
                        val fn = obj["function"]?.jsonObject
                        ToolDelta(
                            index = obj["index"]?.stringOrNull()?.toIntOrNull() ?: 0,
                            id = obj["id"]?.stringOrNull(),
                            name = fn?.get("name")?.stringOrNull(),
                            arguments = fn?.get("arguments")?.stringOrNull(),
                        )
                    }.getOrNull()
                }
                .orEmpty()
            StreamChunk(text, calls)
        }.getOrNull()
    }

    private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? {
        return (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    companion object {
        /**
         * Accepts either a base (`https://host/v1`) or a full endpoint
         * (`https://host/v1/chat/completions`) and always returns the chat URL.
         */
        fun chatUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
        }

        /** `GET /models` URL derived from either URL form. */
        fun modelsUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')
            return "$trimmed/models"
        }
    }
}
