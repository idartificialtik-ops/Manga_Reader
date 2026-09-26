<p align="center">
  <img src="docs/capa_1024.png" alt="Manga Reader" width="220">
</p>

# Manga_Reader

Leitor de mangás para Android em **português do Brasil**, inspirado no Komikku. Multi-módulo,
Jetpack Compose e Kotlin. Traz leitor contínuo (webtoon), fontes extensíveis, downloads
offline, **tradução de páginas no próprio aparelho** (OCR + tradução) e um **assistente de IA**
com busca real nas fontes instaladas.

> Status: em desenvolvimento (`0.1.0`). Compilações prontas são publicadas na
> aba **[Releases](https://github.com/idartificialtik-ops/Manga_Reader/releases)** — dá para instalar
> direto, sem compilar nada.

---

## Índice

- [Funcionalidades](#funcionalidades)
- [Stack](#stack)
- [Arquitetura](#arquitetura)
- [Como buildar](#como-buildar)
- [Instalação](#instalação)
- [Configuração](#configuração)
- [Privacidade](#privacidade)
- [Limitações conhecidas](#limitações-conhecidas)

---

## Funcionalidades

### Leitura
- **Modo webtoon** contínuo (rolagem vertical sem fim de página) com capítulos encadeados
  automaticamente.
- **Leitor imersivo**: sem barras — o quadrinho ocupa a tela inteira. Um **toque no centro**
  revela barra superior e contador de página (auto-oculta em 4 s). Tela ligada enquanto lê.
- Retomada de leitura por capítulo/página, marcador de lido/favorito.
- Capas carregadas com os **headers da própria fonte** (CDNs que bloqueiam hotlink funcionam).

### Fontes e extensões
- Fonte **embutida**: Comic Fury.
- **Extensões Keiyoushi**: repositório integrado, busca, download do APK e instalação
  (via `PackageInstaller`, com tela de confirmação do sistema).
- **Filtros nativos por fonte** (status, gêneros, ordenação, checkboxes, tri-state) renderizados
  de forma genérica a partir do `FilterList` da extensão.
- **Busca global** com filtros de status e gênero aplicados no cliente.

### Tradução de páginas (on-device)
- **OCR ONNX** com PP-OCRv6_manga: download dos modelos sob demanda, detecção em tiles para
  páginas densas, agrupamento de linhas em balões e reconhecimento CTC.
- **Fallback ML Kit** (reconhecimento + tradução) quando o ONNX não está pronto ou falha.
- Bolhas de tradução com **ajuste automático de fonte** e crescimento do balão para não cortar
  texto em balões pequenos.
- Cache por página em disco, com versão — trocar de tradutor reprocessa.

### Assistente de IA
- Compatível com qualquer provedor **OpenAI-compatible** (OpenAI, DeepSeek, Groq, OpenRouter,
  Ollama…): URL flexível (`…/v1` ou `…/chat/completions`), chave em `EncryptedSharedPreferences`.
- **Respostas em tempo real** (SSE) com toggle — e **fallback automático** para o modo normal
  quando o provedor não suporta streaming.
- **Busca de modelos** (`GET /models`) com seletor no próprio app.
- Ferramentas: `search_manga` (com filtros de status/gênero/nº de capítulos), `list_favorites`,
  `manga_details`, `add_to_library` — o usuário pede "adiciona o 2" e entra na biblioteca.
- Recuo com **backoff exponencial em 429/5xx** e mensagens amigáveis; cooldown por fonte que
  falhou para não repetir timeout a cada chamada.

### Feed
- Recomendações personalizadas a partir da **biblioteca do usuário**.
- O **modelo decide** se quer pesquisar novidades na web (via **Exa**, opcional) ou recomendar
  do próprio conhecimento.
- Cada item é casado com a obra real nas fontes (título + autor + similaridade) e o motivo é
  **ancorado na sinopse real** — evita a recomendação vir com descrição de outra obra.
- Atualização automática por WorkManager (6 h / 12 h / 24 h / 3 dias) e botão "Atualizar agora".

### Interface
- **Tema por capa** na tela de detalhes (estilo Komikku): a cor extraída da capa regenera o
  `ColorScheme` inteiro — fundo, chips e botões no mesmo tom, com capa desfocada no cabeçalho.
- 7 paletas + **cor dinâmica** do Android 12+ e modo **AMOLED** (preto puro).
- Biblioteca em grade com badge de não lidos; capas com crossfade.

### Downloads
- WorkManager com **pausa/retomada**, opção **somente no Wi-Fi**, agrupamento e fila;
  páginas baixadas para o app ler **offline**.

---

## Stack

| Camada | Tecnologia |
|---|---|
| UI | Jetpack Compose (BOM 2024.09.03, Material3 1.3.1), Navigation Compose |
| Linguagem | Kotlin 2.0.21, kotlinx.serialization, coroutines |
| Persistência | SQLDelight 2.0.2 (SQLite) |
| Rede | OkHttp 5.3.2 (+ brotli, zstd, logging), Jsoup 1.18.3 |
| Imagens | Coil 3.0.3 |
| Background | WorkManager 2.9.0 |
| OCR/IA on-device | ONNX Runtime 1.22.0, ML Kit (texto JP/KO/ZH + tradução) |
| DI | Injekt |
| Build | AGP 8.7.3, Gradle 8.11.1, JDK 21 |

`minSdk 26` · `targetSdk 36` · `compileSdk 36` · Java/Kotlin target 17

---

## Arquitetura

Módulos Gradle separados por responsibility:

```
:app           UI (Compose), ViewModels, DI, tradutor, assistente, feed
:domain        Modelos e interfaces de caso de uso (independente de Android)
:data          Implementações dos repositórios + SQLDelight
:core:common   Utilitários compartilhados
:source-api    API de fonte (model, filtros, HttpSource)
:sources       Fontes concretas (Comic Fury) + gerenciador de downloads
:extension-host  Bridge para extensões externas (API Tachiyomi/Keiyoushi)
:ocr-onnx      Runtime ONNX: detecção, reconhecimento e loja de modelos
```

- Persistência com SQLDelight (`mangas`, `chapters`, `history`, `categories`, `sources`, …).
- Toda a tela de rede roda em `Dispatchers.IO`; as telas observam `StateFlow`.
- Crash logger local em `files/crash.log`.

---

## Como buildar

**Requisitos**: Android Studio (Ladybug+), **JDK 21**, Android SDK 36.

```bash
git clone <url-do-repo>
cd Manga_Reader

# Se o seu `java` padrão não for o 21, aponte o Gradle para ele (uma vez):
#   GRADLE_USER_HOME/gradle.properties  ->  org.gradle.java.home=/caminho/jdk-21

./gradlew assembleDebug        # APK debug
./gradlew assembleRelease      # APK assinado (precisa de keystore.properties)
./gradlew installDebug         # instala no dispositivo conectado
```

O APK sai em `app/build/outputs/apk/{debug,release}/`.

### Assinatura (release)

O build de release é assinado com a chave local. As credenciais ficam em
**`keystore.properties`** (ignorado pelo git) apontando para a chave em `keystore/`:

```properties
storeFile=/caminho/para/manga-reader.jks
storePassword=…
keyAlias=manga-reader
keyPassword=…
```

Para gerar a sua:

```bash
keytool -genkeypair -v -keystore manga-reader.jks -storetype PKCS12 \
        -alias manga-reader -keyalg RSA -keysize 4096 -validity 10950
```

> Sem `keystore.properties` o build de release sai **sem assinatura** (o app ainda instala,
> mas não atualiza por cima de uma versão assinada com outra chave). Guarde a chave: quem
> perder a chave perde o direito de publicar atualizações do mesmo app.

> Não versione `local.properties` (já está no `.gitignore`) — ele guarda o `sdk.dir` da sua máquina.

---

## Instalação

O APK já compilado fica na aba **[Releases](https://github.com/idartificialtik-ops/Manga_Reader/releases)**.

**No celular (Android 8+)**
1. Baixe o `app-release.apk` da release.
2. Abra o arquivo; o Android vai pedir autorização para **"instalar apps de fontes desconhecidas"**
   — libere para o navegador/gerenciador de arquivos que fez o download.
3. Confirme a instalação.

**Via ADB**
```bash
adb install -r app-release.apk
```

> Requer Android 8.0+ (`minSdk 26`). O app pede permissão de instalação de pacotes para
> instalar as extensões de fontes — a tela de confirmação é a do próprio Android.

---

## Configuração

Tudo opcional, feito pelo próprio app (**Mais → …**):

| Onde | O quê |
|---|---|
| **Mais → Assistente IA** | Provedor (URL, chave, modelo), busca de modelos, toggle de tempo real |
| **Mais → Tradução** | Motor de OCR (ONNX / ML Kit / automático) e tradutor (IA / ML Kit / automático) |
| **Mais → Aparência** | Tema claro/escuro/sistema, 7 paletas, AMOLED |
| **Feed (⚙)** | Intervalo de atualização, atualização automática, chave da **Exa** (link "Pegar chave") |
| **Extensões** | Repositório Keiyoushi, instalar/desinstalar fontes, filtro por idioma, ocultar 18+ |
| **Navegar (⚙/🌐)** | Filtro de idiomas, ocultar fontes 18+, WebView para passar na verificação do Cloudflare |
| **Fonte (⚙)** | Configurações da extensão (login, cookies, chaves) quando ela expõe |

---

## Privacidade

- Biblioteca, histórico, downloads e traduções ficam **no aparelho** (SQLite + arquivos internos).
- As **chaves de API** (provedor de IA e Exa) são guardadas em `EncryptedSharedPreferences`.
- Nenhuma requisição de telemetria/analytics.
- O app só acessa a rede que **você** configurar (fontes e provedores que você adicionar).
- A feature de IA é **opt-in**: sem provedor configurado, o tradutor usa o ML Kit offline.

---

## Limitações conhecidas

- O APK publicado é **assinado** com chave de release do projeto (dá para instalar por cima de
  versões anteriores). Não é a chave oficial da Play Store, então não vai para a loja.
- **Algumas fontes podem falhar por rede/ISP** (ex.: Comic Fury e MangaPlus retornam timeout em
  algumas operadoras/VPNs). Não é bug do app — o app detecta e avisa, e aplica cooldown.
- **Fontes atrás de Cloudflare**: o app tenta resolver o desafio sozinho (WebView invisível) e, se
  não conseguir, oferece um WebView na tela da fonte para você resolver na mão. Bloqueio por
  reputação de IP (VPN/datacenter) não é resolvido por nenhum dos dois caminhos.
- A tradução depende do qualidade do OCR: balões muito pequenos ou fonte estilizada podem sair
  com recognize impreciso.
- O modelo OCR (~22 MB) é baixado no primeiro uso da tradução.
- Integração com trackers (AniList/MAL) ainda não implementada — o botão aparece desabilitado na tela de detalhes.

---

## Licença

[MIT](LICENSE) — use, modifique e redistribua livremente, inclusive para fins comerciais.

---

<p align="center">
  Feito com ☕ em Kotlin + Compose
</p>
