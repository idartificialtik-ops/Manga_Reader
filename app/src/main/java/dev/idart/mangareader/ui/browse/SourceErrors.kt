package dev.idart.mangareader.ui.browse

import android.util.Log

private const val TAG = "SourceError"

/**
 * Extrai o status HTTP do texto: "HTTP error 403", "HTTP 403", "code: 403".
 * O `\b` evita casar os 3 primeiros dígitos de um número maior (ex.: 4031).
 */
private val HTTP_STATUS = Regex("""(?i)http[ _-]?(?:error|status|code)?[ :=]*(\d{3})\b""")

/**
 * Mensagem legível para falhas de rede de uma fonte.
 *
 * A exceção original pertence à classloader da extensão: o app só enxerga um
 * `RuntimeException` embrulhado, sem tipo nem status HTTP acessíveis. Por isso
 * o status é extraído do texto — é a única forma de funcionar com qualquer
 * extensão, e evita despejar "RuntimeException: eu.kanade...HttpException" na
 * tela.
 */
fun friendlySourceError(e: Throwable, sourceId: Long? = null): String {
    if (e is java.net.UnknownHostException) {
        return "Servidor não encontrado (DNS). Verifique a internet, a VPN ou o DNS privado."
    }
    if (e is java.net.SocketTimeoutException || e is java.net.ConnectException) {
        return "Sem resposta do servidor (timeout). Sua rede pode estar bloqueando este site — " +
            "teste no 4G/5G ou desligue a VPN."
    }
    if (e is javax.net.ssl.SSLException) {
        return "Falha de conexão segura (TLS). Teste em outra rede."
    }

    val chain = generateSequence(e) { it.cause }.take(5).toList()
    val code = chain.asSequence()
        .mapNotNull { HTTP_STATUS.find(it.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() }
        .firstOrNull()
    val raw = chain.joinToString(" <- ") { cause ->
        cause.message?.takeIf { it.isNotBlank() }
            ?.let { "${cause::class.simpleName}: $it" }
            ?: cause::class.simpleName.orEmpty()
    }
    Log.w(TAG, "fonte=${sourceId ?: "?"} http=${code ?: "-"} erro=$raw")

    return when (code) {
        // O CloudflareInterceptor deste host é pass-through (não resolve o
        // desafio anti-bot), então é o 403 mais comum por aqui.
        403 ->
            "O site bloqueou o acesso (403). Fuentes com proteção anti-bot costumam " +
                "responder assim — tente outra fonte ou outra rede."
        401 -> "A fonte exigiu autenticação (401): normalmente ela precisa de login na versão web."
        404 -> "A fonte não está mais disponível (404); pode ter sido descontinuada."
        429 -> "O site limitou as requisições (429). Aguarde alguns minutos e tente de novo."
        in 500..599 -> "O site está instável (HTTP $code). Tente mais tarde."
        else -> chain.last().message?.takeIf { it.isNotBlank() } ?: "Erro de rede"
    }
}
