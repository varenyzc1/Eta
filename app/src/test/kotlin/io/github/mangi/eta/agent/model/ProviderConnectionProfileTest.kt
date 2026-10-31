package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConnectionProfileTest {
    @Test
    fun OAuthProjectionBindsProtocolAndHostWithoutPuttingTokensIntoConfig() {
        val original = config().copy(
            baseUrl = "https://custom-gateway.example/v1", apiKey = "old-api-fixture",
            openAiEndpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        )
        val effective = ProviderRequest(original, JSONArray(), JSONArray()).effectiveConfig
        assertEquals("https://chatgpt.com/backend-api/codex", effective.baseUrl)
        assertEquals(OpenAiEndpointMode.RESPONSES, effective.openAiEndpointMode)
        assertEquals("", effective.apiKey)
        assertEquals(OpenAiResponsesProvider, ProviderClientFactory.getClient(original))
        assertEquals("old-api-fixture", original.apiKey)
    }

    @Test
    fun ApiKeyProjectionKeepsCustomEndpointAndCredentials() {
        val original = config().copy(baseUrl = "https://custom-gateway.example/v1", apiKey = "api-fixture")
        assertEquals(original, ProviderRequest(original, JSONArray(), JSONArray()).effectiveConfig)
    }

    @Test
    fun grokSubscriptionUsesResponsesOnTheProxy() {
        val original = config().copy(
            authMode = ProviderAuthModes.XAI_GROK,
            openAiEndpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
        )
        val effective = ProviderRequest(original, JSONArray(), JSONArray()).effectiveConfig
        assertEquals("https://cli-chat-proxy.grok.com/v1", effective.baseUrl)
        assertEquals(OpenAiEndpointMode.RESPONSES, effective.openAiEndpointMode)
        assertEquals(OpenAiResponsesProvider, ProviderClientFactory.getClient(original))
        assertEquals("https://cli-chat-proxy.grok.com/v1/responses", ProviderConnectionProfile.XAI_GROK.inferenceUrl)
    }

    @Test
    fun grokBodyCannotOverrideSelectedModelOrDisableStreaming() {
        val original = config().copy(
            authMode = ProviderAuthModes.XAI_GROK,
            extraBodyJson = """{"model":"unexpected-model","stream":false,"store":true}""",
        )
        val request = OpenAiResponsesProvider.buildRequestJson(
            ProviderRequest(original, JSONArray(), JSONArray()).effectiveConfig,
            JSONArray().put(JSONObject().put("role", "user").put("content", "fixture")),
            JSONArray(),
        )
        assertEquals("grok-fixture", request.getString("model"))
        assertTrue(request.getBoolean("stream"))
        assertEquals(false, request.getBoolean("store"))
        assertEquals(1, request.getJSONArray("input").length())
    }

    @Test
    fun grokToolSchemasDropTopLevelCombinatorsOnlyForTheSubscriptionProxy() {
        val tools = JSONArray().put(
            JSONObject().put("type", "function").put(
                "function",
                JSONObject().put("name", "fetch_url").put("description", "fixture").put(
                    "parameters",
                    JSONObject().put("type", "object")
                        .put("properties", JSONObject().put("url", JSONObject().put("type", "string")))
                        .put("oneOf", JSONArray().put(JSONObject().put("required", JSONArray().put("url")))),
                ),
            ),
        )
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "fixture"))
        val grok = OpenAiResponsesProvider.buildRequestJson(
            ProviderRequest(config().copy(authMode = ProviderAuthModes.XAI_GROK), messages, tools).effectiveConfig,
            messages,
            tools,
        ).getJSONArray("tools").getJSONObject(0).getJSONObject("parameters")
        assertTrue(!grok.has("oneOf"))
        assertEquals("object", grok.getString("type"))
        assertTrue(grok.getJSONObject("properties").has("url"))

        val apiKey = OpenAiResponsesProvider.buildRequestJson(
            config().copy(openAiEndpointMode = OpenAiEndpointMode.RESPONSES), messages, tools,
        ).getJSONArray("tools").getJSONObject(0).getJSONObject("parameters")
        assertTrue(apiKey.has("oneOf"))
        // 发送副本不能改写原工具定义。
        assertTrue(tools.getJSONObject(0).getJSONObject("function").getJSONObject("parameters").has("oneOf"))
    }

    @Test
    fun unknownAuthenticationModeDoesNotFallBackToApiKey() {
        assertTrue(runCatching { ProviderConnectionProfile.forAuthMode("unknown-fixture") }.isFailure)
    }

    private fun config() = AgentModelClient.ModelConfig(
        providerId = "account", baseUrl = "https://api.x.ai/v1", apiKey = "",
        model = "grok-fixture", systemPrompt = "fixture",
    )
}
