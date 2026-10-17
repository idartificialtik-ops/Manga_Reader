package dev.idart.mangareader.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class UiChatMessage(
    val fromUser: Boolean,
    val text: String,
    val hits: List<MangaHit> = emptyList(),
    val isError: Boolean = false,
    /** Non-null while a tool runs: rendered as "… em execução" + spinner. */
    val toolStatus: String? = null,
)

class AiViewModel(
    private val settings: AiSettings,
    private val client: AiClient,
    private val tools: AgentTools,
    private val history: ChatHistoryStore,
) : ViewModel() {

    val baseUrl: StateFlow<String> = settings.baseUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiSettings.DEFAULT_BASE_URL)
    val apiKey: StateFlow<String> = settings.apiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val model: StateFlow<String> = settings.model
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AiSettings.DEFAULT_MODEL)
    val streamEnabled: StateFlow<Boolean> = settings.streamEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _messages = MutableStateFlow<List<UiChatMessage>>(emptyList())
    val messages: StateFlow<List<UiChatMessage>> = _messages.asStateFlow()

    /** Conversas salvas, mais recente primeiro. */
    val threads: StateFlow<List<ChatThread>> = history.threads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Conversa em andamento. Trocar de thread é trocar este id: o salvamento
     * contínuo abaixo sempre escreve no lugar certo.
     */
    private var threadId = System.currentTimeMillis()

    @Volatile
    private var loadedOnce = false

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * O que o assistente está fazendo agora. Sem isso o chat mostra só um
     * spinner e o usuário não sabe se travou ou está procurando.
     */
    private val _activity = MutableStateFlow<String?>(null)
    val activity: StateFlow<String?> = _activity.asStateFlow()

    /**
     * Resultado que o usuário precisa ver depois que a atividade some: sem
     * isto, "procurando os títulos…" piscava e sumia sem dizer o resultado.
     */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private fun doing(text: String) {
        _activity.value = text
    }

    private fun note(text: String) {
        _notice.value = text
    }

    /**
     * Confirmação de desinstalação pedida pelo assistente. Sem resposta do
     * usuário a ferramenta recusa sozinha.
     */
    val pendingExtensionRemoval: StateFlow<AgentTools.PendingAction?> = tools.pendingAction

    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models: StateFlow<List<String>> = _models.asStateFlow()

    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()

    private val _modelsError = MutableStateFlow<String?>(null)
    val modelsError: StateFlow<String?> = _modelsError.asStateFlow()

    private var job: Job? = null

    /** True while a live (streaming/status) bubble is reserved for this turn. */
    private var pendingLive = false

    init {
        // Retoma a conversa mais recente: "histórico" sem isso é só um nome.
        viewModelScope.launch {
            loadedOnce = false
            val latest = runCatching { history.loadThreads().firstOrNull() }.getOrNull()
            if (latest != null && _messages.value.isEmpty()) {
                threadId = latest.id
                _messages.value = runCatching { history.loadMessages(latest.id) }.getOrDefault(emptyList())
            }
            loadedOnce = true
        }
        // Salva em vez de só no fim do turno: sair da tela no meio de uma
        // resposta não pode apagar o que já foi dito.
        viewModelScope.launch {
            _messages
                .debounce(400)
                .collect { messages ->
                    if (loadedOnce) runCatching { history.saveThread(threadId, messages) }
                }
        }
    }

    /**
     * Escreve a conversa atual **antes** de trocar de thread. O salvamento
     * contínuo tem debounce, e trocar de conversa logo depois de uma resposta
     * descartaria justamente o último turno.
     */
    private fun flushCurrent() {
        val id = threadId
        val snapshot = _messages.value
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            runCatching { history.saveThread(id, snapshot) }
        }
    }

    /** Começa uma conversa nova; a atual é arquivada, não apagada. */
    fun newThread() {
        if (_messages.value.isEmpty()) return
        job?.cancel()
        client.cancelActive()
        _busy.value = false
        _activity.value = null
        _notice.value = null
        pendingLive = false
        flushCurrent()
        _messages.value = emptyList()
        threadId = System.currentTimeMillis()
    }

    /** Reabre uma conversa salva. */
    fun openThread(id: Long) {
        if (id == threadId && _messages.value.isNotEmpty()) return
        job?.cancel()
        client.cancelActive()
        _busy.value = false
        _activity.value = null
        _notice.value = null
        pendingLive = false
        flushCurrent()
        // Suprime o escritor debounced enquanto a nova thread é lida, senão o
        // conteúdo antigo seria gravado por cima do id recém-aberto.
        loadedOnce = false
        threadId = id
        viewModelScope.launch {
            _messages.value = runCatching { history.loadMessages(id) }.getOrDefault(emptyList())
            loadedOnce = true
        }
    }

    fun deleteThread(id: Long) {
        viewModelScope.launch {
            runCatching { history.deleteThread(id) }
            if (id == threadId) {
                _messages.value = emptyList()
                threadId = System.currentTimeMillis()
            }
        }
    }

    /** Apaga todas as conversas e abre uma vazia. */
    fun clearHistory() {
        job?.cancel()
        client.cancelActive()
        _busy.value = false
        _activity.value = null
        _notice.value = null
        pendingLive = false
        // Sem suprimir o escritor, um save em voo poderia recriar o arquivo
        // logo depois do clearAll.
        loadedOnce = false
        _messages.value = emptyList()
        threadId = System.currentTimeMillis()
        viewModelScope.launch {
            runCatching { history.clearAll() }
            loadedOnce = true
        }
    }

    fun setBaseUrl(value: String) = settings.setBaseUrl(value)
    fun setApiKey(value: String) = settings.setApiKey(value)
    fun setModel(value: String) = settings.setModel(value)
    fun setStreamEnabled(value: Boolean) = settings.setStreamEnabled(value)

    /** Fetches the model list from the provider (`GET /models`). */
    fun fetchModels(baseUrl: String, apiKey: String) {
        viewModelScope.launch {
            _modelsLoading.value = true
            _modelsError.value = null
            try {
                val ids = withContext(Dispatchers.IO) {
                    client.listModels(
                        baseUrl.ifBlank { AiSettings.DEFAULT_BASE_URL },
                        apiKey,
                    )
                }
                _models.value = ids
                if (ids.isEmpty()) _modelsError.value = "Nenhum modelo retornado."
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _modelsError.value = friendlyError(e)
            } finally {
                _modelsLoading.value = false
            }
        }
    }

    /**
     * Same, but with the saved key (never exposed to the UI): works even
     * when the key field is empty because it is already stored.
     */
    fun fetchModelsWithSaved(baseUrl: String, typedKey: String) {
        viewModelScope.launch {
            settings.refresh()
            val key = typedKey.ifBlank { settings.apiKey.first() }
            fetchModels(baseUrl, key)
        }
    }

    fun send(prompt: String) {
        val text = prompt.trim()
        if (text.isBlank() || _busy.value) return
        job?.cancel()
        // Cancelar o job não interrompe o socket bloqueante: a Call também.
        client.cancelActive()
        job = viewModelScope.launch {
            _busy.value = true
            doing("pensando…")
            _notice.value = null
            pendingLive = false
            _messages.update { it + UiChatMessage(fromUser = true, text = text) }
            try {
                // Network + source I/O must stay off the Main thread.
                // Teto duro: fonte lenta ou travada não pode deixar o chat
                // girando indefinidamente com a resposta já na tela.
                val turn = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(TURN_TIMEOUT_MS) { runTurn(text) }
                } ?: AssistantTurn(
                    text = "Demorei demais para concluir. Tente uma busca mais específica.",
                    mangaHits = emptyList(),
                )
                _messages.update { list ->
                    // Streamed answer is already on screen: just attach hits.
                    val last = list.lastOrNull()
                    if (last != null && !last.fromUser && !last.isError &&
                        last.toolStatus == null && last.text == turn.text
                    ) {
                        list.dropLast(1) + last.copy(hits = turn.mangaHits)
                    } else {
                        list + UiChatMessage(fromUser = false, text = turn.text, hits = turn.mangaHits)
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _messages.update { list ->
                    val last = list.lastOrNull()
                    // Reaproveita a bolha viva para o erro (não deixa órfã vazia).
                    if (last != null && !last.fromUser && !last.isError &&
                        last.toolStatus != null && last.text.isBlank()
                    ) {
                        list.dropLast(1) + UiChatMessage(
                            fromUser = false,
                            text = friendlyError(e),
                            isError = true,
                        )
                    } else {
                        list + UiChatMessage(fromUser = false, text = friendlyError(e), isError = true)
                    }
                }
            } finally {
                pendingLive = false
                _busy.value = false
                _activity.value = null
                // O histórico é gravado pelo fluxo contínuo do init, com
                // debounce: nada a fazer aqui.
            }
        }
    }

    private suspend fun runTurn(prompt: String): AssistantTurn {
        // Lê do disco (nunca do StateFlow da UI): a chave salva vale de cara.
        settings.refresh()
        val base = settings.baseUrl.first().ifBlank { AiSettings.DEFAULT_BASE_URL }
        val key = readKey()
        require(key.isNotBlank()) { "Configure sua API key nas configurações (ícone acima)." }
        val modelName = settings.model.first().ifBlank { AiSettings.DEFAULT_MODEL }
        val useStream = settings.streamEnabled.first()
        val history = mutableListOf(ChatMessage(role = "system", content = AgentTools.SYSTEM_PROMPT))
        history += _messages.value
            .filter { !it.isError && it.toolStatus == null }
            .takeLast(8)
            .map {
                ChatMessage(
                    role = if (it.fromUser) "user" else "assistant",
                    content = it.text,
                )
            }
        history += ChatMessage(role = "user", content = prompt)
        val allHits = mutableListOf<MangaHit>()
        val toolCache = mutableMapOf<String, String>()
        repeat(MAX_ROUNDS) { round ->
            if (round == MAX_ROUNDS - 1) {
                // Última chance: proíbe mais tools e força a resposta.
                history += ChatMessage(
                    role = "system",
                    content = "Responda ao usuário AGORA com o que você já apurou. Não chame mais ferramentas.",
                )
            }
            val reply = if (useStream) {
                streamRoundWithRetry(base, key, modelName, history)
            } else {
                val direct = chatWithRetry(base, key, modelName, history)
                history += direct.copy(toolCalls = direct.toolCalls)
                direct
            }
            if (reply.toolCalls.isEmpty()) {
                val finalText = reply.content?.ifBlank { null } ?: "(sem resposta)"
                var hits = allHits.distinctBy { it.sourceId to it.url }.take(6)
                if (hits.isEmpty() && !finalText.isBlank() && tools.hasMangaCandidates(finalText)) {
                    // O modelo respondeu do próprio conhecimento (sem tool):
                    // resolve os títulos citados para virar card tocável.
                    // A resposta JÁ está na tela aqui, então o usuário precisa
                    // saber que ainda estamos trabalhando. Só faz sentido
                    // quando a resposta é mesmo uma lista de mangás — numa
                    // conversa sobre extensões não há o que procurar, e
                    // anunciar a busca e depois não mostrar nada confunde.
                    doing("procurando os títulos citados nas fontes…")
                    hits = tools.resolveTitlesFrom(finalText)
                    if (hits.isNotEmpty()) {
                        android.util.Log.d("AgentTools", "hits resolvidos do texto: ${hits.size}")
                    } else {
                        // Dizer que não achou é melhor do que deixar a linha
                        // de atividade sumir sem explicação.
                        note(
                            "Nenhum desses títulos apareceu nas suas fontes instaladas — " +
                                "confira se a fonte certa está na aba Navegar.",
                        )
                    }
                }
                endTurn()
                return AssistantTurn(
                    text = finalText,
                    mangaHits = hits,
                )
            }
            try {
                for (call in reply.toolCalls) {
                    val cacheKey = call.function.name + "\n" + call.function.arguments
                    val cached = toolCache[cacheKey]
                    if (cached != null) {
                        history += ChatMessage(
                            role = "tool",
                            content = cached,
                            toolCallId = call.id,
                        )
                    } else {
                        showToolStatus("⏳ ${call.function.name} em execução…")
                        val started = System.currentTimeMillis()
                        val (summary, hits) = tools.execute(call.function.name, call.function.arguments)
                        android.util.Log.d(
                            "AgentTools",
                            "tool ${call.function.name} levou ${System.currentTimeMillis() - started}ms " +
                                "→ ${hits.size} hits",
                        )
                        toolCache[cacheKey] = summary.take(4000)
                        allHits += hits
                        history += ChatMessage(
                            role = "tool",
                            content = summary.take(4000),
                            toolCallId = call.id,
                        )
                    }
                }
            } finally {
                // Mantém o balão vivo visível ("escrevendo resposta…") até o
                // próximo round terminar — sem piscar entre execuções.
                if (pendingLive) showToolStatus("✍️ escrevendo resposta…")
            }
        }
        // Acabaram os rounds: devolve o que já foi apurado em vez de só o aviso.
        val lastText = history.lastOrNull { it.role == "assistant" }?.content.orEmpty()
        val fallback = allHits.distinctBy { it.sourceId to it.url }.take(6)
            .ifEmpty { tools.resolveTitlesFrom(lastText) }
        endTurn()
        return AssistantTurn(
            text = if (fallback.isEmpty()) {
                "Atingi o limite de passos sem concluir. Tente refinar o pedido."
            } else {
                "Atingi o limite de passos. Aqui estão os resultados que encontrei:"
            },
            mangaHits = fallback,
        )
    }

    /**
     * Blocking chat with exponential-backoff retry on rate limits (429)
     * and server errors (5xx). Retries the SAME request up to [RETRIES].
     */
    private suspend fun chatWithRetry(
        base: String,
        key: String,
        modelName: String,
        history: MutableList<ChatMessage>,
    ): ChatMessage {
        var backoff = 2000L
        repeat(RETRIES) { attempt ->
            try {
                return client.chat(base, key, modelName, history, tools.definitions())
            } catch (e: RateLimitedException) {
                if (e.code != 429 && e.code !in 500..599) throw e
                if (attempt == RETRIES - 1) throw e
                android.util.Log.w("AiViewModel", "HTTP ${e.code}, retry em ${backoff}ms")
                kotlinx.coroutines.delay(backoff)
                backoff *= 2
            }
        }
        error("inacessível")
    }

    /**
     * Streamed round with the same rate-limit retry, but only when the
     * failure happens before any event (mid-stream failures fall back to
     * the blocking path inside [streamRound]).
     */
    private suspend fun streamRoundWithRetry(
        base: String,
        key: String,
        modelName: String,
        history: MutableList<ChatMessage>,
    ): ChatMessage {
        var backoff = 2000L
        repeat(RETRIES) { attempt ->
            try {
                return streamRound(base, key, modelName, history)
            } catch (e: RateLimitedException) {
                if (e.code != 429 && e.code !in 500..599) throw e
                if (attempt == RETRIES - 1) throw e
                android.util.Log.w("AiViewModel", "HTTP ${e.code} no stream, retry em ${backoff}ms")
                kotlinx.coroutines.delay(backoff)
                backoff *= 2
            }
        }
        error("inacessível")
    }

    /**
     * One streamed assistant round: appends a live message, updates it per
     * delta, and returns the final message. Falls back to a blocking call
     * when the provider has no SSE support.
     */
    private suspend fun streamRound(
        base: String,
        key: String,
        modelName: String,
        history: MutableList<ChatMessage>,
    ): ChatMessage {
        doing("escrevendo resposta…")
        val text = StringBuilder()
        val acc = mutableMapOf<Int, ToolAcc>()
        try {
            client.chatStream(base, key, modelName, history, tools.definitions()) { chunk ->
                currentCoroutineContext().ensureActive()
                if (chunk.textDelta.isNotEmpty()) {
                    text.append(chunk.textDelta)
                    updateLiveText(text.toString())
                }
                for (delta in chunk.toolDeltas) {
                    val slot = acc.getOrPut(delta.index) { ToolAcc() }
                    delta.id?.let { slot.id = it }
                    delta.name?.let { slot.name = it }
                    delta.arguments?.let { slot.args.append(it) }
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                endTurn()
                throw e
            }
            // CancelActive() chega aqui como IOException("Canceled"), não como
            // CancellationException: sem este cheque o turno cairia no fallback
            // bloqueante e reenviaria o request que o usuário acabou de cancelar.
            currentCoroutineContext().ensureActive()
            // Sem SSE (ou falha no meio): modo normal (com retry de 429).
            showToolStatus("✍️ escrevendo resposta…")
            val direct = chatWithRetry(base, key, modelName, history)
            history += direct.copy(toolCalls = direct.toolCalls)
            updateLiveText(direct.content.orEmpty())
            return direct
        }
        val calls = acc.values.mapNotNull { it.toToolCall() }
        if (text.isBlank() && calls.isEmpty()) {
            // Stream "ok" mas sem deltas (provedor ignorou stream:true e o
            // parseFullResponse não reconheceu): modo normal uma vez.
            android.util.Log.w("AiViewModel", "stream vazio, fallback normal")
            showToolStatus("✍️ escrevendo resposta…")
            val direct = chatWithRetry(base, key, modelName, history)
            history += direct.copy(toolCalls = direct.toolCalls)
            updateLiveText(direct.content.orEmpty())
            return direct
        }
        val final = ChatMessage(
            role = "assistant",
            content = text.toString().ifBlank { null },
            toolCalls = calls,
        )
        history += final
        // Mantém o texto parcial visível; vira status na execução das tools.
        updateLiveText(text.toString())
        return final
    }

    /**
     * Cria/atualiza o balão do assistente. Só nasce quando há texto: um
     * balão reservado antes disso aparecia como card vazio na tela.
     */
    private fun updateLiveText(text: String) {
        if (text.isBlank()) return
        _messages.update { list ->
            val last = list.lastOrNull()
            val reusable = last != null && !last.fromUser && !last.isError && last.hits.isEmpty()
            if (reusable) {
                list.dropLast(1) + last!!.copy(text = text)
            } else {
                list + UiChatMessage(fromUser = false, text = text)
            }
        }
        pendingLive = true
    }

    /**
     * Status da atividade: só a linha de baixo da tela.
     *
     * Antes isso também escrevia dentro do balão do assistente, o que
     * duplicava a mensagem e deixava um card vazio no meio da conversa. Um
     * lugar só, sempre visível.
     */
    private fun showToolStatus(status: String) {
        doing(status.removePrefix("⏳ ").removePrefix("✍️ "))
    }

    /** Fim do turno: remove o balão vazio, mantém o que tem conteúdo. */
    private fun endTurn() {
        _messages.update { list ->
            if (list.isEmpty()) return@update list
            val last = list.last()
            if (last.fromUser || last.isError) return@update list
            if (last.hits.isEmpty() && last.text.isBlank()) {
                list.dropLast(1)
            } else {
                list.dropLast(1) + last.copy(toolStatus = null)
            }
        }
        pendingLive = false
    }

    private class ToolAcc {
        var id: String? = null
        var name: String? = null
        val args = StringBuilder()

        fun toToolCall(): ToolCall? {
            val callId = id ?: return null
            val fnName = name ?: return null
            return ToolCall(callId, function = FunctionCall(fnName, args.toString().ifBlank { "{}" }))
        }
    }

    private fun friendlyError(e: Throwable): String {
        val raw = e.message.orEmpty()
        val code = (e as? RateLimitedException)?.code
        return when {
            code == 429 || raw.contains("429") ->
                "Muitas requisições (429). Aguarde alguns segundos e tente de novo."
            code != null && code in 500..599 ->
                "O provedor está instável (HTTP $code). Tente novamente em instantes."
            e is java.net.UnknownHostException ->
                "Sem conexão com o provedor. Confira a Base URL e sua internet."
            e is java.net.SocketTimeoutException ->
                "O modelo demorou demais para responder. Tente de novo ou use um modelo mais rápido."
            raw.contains("Cleartext", ignoreCase = true) ->
                "HTTP bloqueado. Use uma Base URL https (Ollama local só funciona no emulador)."
            raw.contains("401", ignoreCase = true) || raw.contains("Unauthorized", ignoreCase = true) ->
                "API key inválida (401). Confira a chave nas configurações."
            raw.contains("404", ignoreCase = true) ->
                "Modelo ou URL não encontrados (404). Confira modelo e Base URL."
            raw.isNotBlank() -> raw
            else -> "Falha desconhecida. Confira Base URL, chave e modelo."
        }
    }

    private suspend fun readKey(): String {
        var current = settings.apiKey.first()
        if (current.isBlank()) {
            kotlinx.coroutines.delay(300)
            settings.refresh()
            current = settings.apiKey.first()
        }
        return current
    }

    suspend fun openHit(hit: MangaHit): Long {
        return tools.openHit(hit)
    }

    /** Resposta do diálogo de desinstalação (true = remover). */
    fun confirmExtensionRemoval(allow: Boolean) {
        tools.resolvePendingAction(allow)
    }

    companion object {
        private const val MAX_ROUNDS = 5

        /** Retries for 429/5xx before surfacing the error. */
        private const val RETRIES = 3

        /** Teto do turno inteiro (rede + fontes + desinstalação). */
        private const val TURN_TIMEOUT_MS = 240_000L
    }
}
