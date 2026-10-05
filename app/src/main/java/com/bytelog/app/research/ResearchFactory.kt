package com.bytelog.app.research

/** The user's research settings. API keys stay on the phone. */
data class ResearchConfig(
    val provider: ProviderId = ProviderId.ANTHROPIC,
    val apiKeys: Map<ProviderId, String> = emptyMap(),
    val models: Map<ProviderId, String> = emptyMap(),
    /** Optional Tavily key, used for web search when the provider has none (DeepSeek). */
    val tavilyKey: String? = null,
    val askBeforeWriting: Boolean = true
) {
    fun keyFor(p: ProviderId): String? = apiKeys[p]?.trim()?.takeIf { it.isNotEmpty() }
    fun modelFor(p: ProviderId): String = models[p]?.trim()?.takeIf { it.isNotEmpty() } ?: p.defaultModel
    val isConfigured: Boolean get() = keyFor(provider) != null
}

object ResearchFactory {

    /** [baseUrl] replaces the provider's web address (used by tests). */
    fun provider(cfg: ResearchConfig, http: ProviderHttp = ProviderHttp(), baseUrl: String? = null): LlmProvider? {
        val key = cfg.keyFor(cfg.provider) ?: return null
        val model = cfg.modelFor(cfg.provider)
        return when (cfg.provider) {
            ProviderId.ANTHROPIC -> if (baseUrl != null) AnthropicProvider(key, model, http, baseUrl) else AnthropicProvider(key, model, http)
            ProviderId.GEMINI -> if (baseUrl != null) GeminiProvider(key, model, http, baseUrl) else GeminiProvider(key, model, http)
            ProviderId.DEEPSEEK -> if (baseUrl != null) DeepSeekProvider(key, model, http, baseUrl) else DeepSeekProvider(key, model, http)
        }
    }

    /** What searches the web for providers that can't. Claude searches by itself, so it gets none. */
    fun searchBackend(cfg: ResearchConfig, http: ProviderHttp = ProviderHttp(), baseUrl: String? = null): SearchBackend? {
        fun gemini(key: String) = if (baseUrl != null) GeminiGroundedSearch(key, cfg.modelFor(ProviderId.GEMINI), http, baseUrl)
        else GeminiGroundedSearch(key, cfg.modelFor(ProviderId.GEMINI), http)
        fun tavily(key: String) = if (baseUrl != null) TavilySearch(key, http, baseUrl) else TavilySearch(key, http)
        return when (cfg.provider) {
            ProviderId.ANTHROPIC -> null
            ProviderId.GEMINI -> cfg.keyFor(ProviderId.GEMINI)?.let(::gemini)
            ProviderId.DEEPSEEK -> cfg.keyFor(ProviderId.GEMINI)?.let(::gemini)
                ?: cfg.tavilyKey?.trim()?.takeIf { it.isNotEmpty() }?.let(::tavily)
                ?: DuckDuckGoSearch()
        }
    }

    /** A line for Settings describing how web search will work with the current choices. */
    fun searchDescription(cfg: ResearchConfig): String = when (cfg.provider) {
        ProviderId.ANTHROPIC -> "Claude searches the web itself."
        ProviderId.GEMINI -> "Gemini searches Google through grounding."
        ProviderId.DEEPSEEK -> when {
            cfg.keyFor(ProviderId.GEMINI) != null -> "DeepSeek has no web search, so searches go through Google via your Gemini key."
            !cfg.tavilyKey.isNullOrBlank() -> "DeepSeek has no web search, so searches go through Tavily."
            else -> "DeepSeek has no web search. Add a Gemini or Tavily key for reliable results; DuckDuckGo is only a best-effort fallback and is often blocked."
        }
    }
}
