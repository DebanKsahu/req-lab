package com.reqlab.core.scripting

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Real JavaScript scripting engine for ReqLab.
 *
 * Uses a platform-specific JavaScript runtime ([evaluateJs]) to execute
 * actual JavaScript code — supporting variables, loops, conditionals,
 * functions, closures, and the full `reqlab.*` Postman-compatible API.
 *
 * Architecture:
 *   1. [ScriptBootstrap.build] generates a self-contained JS IIFE that
 *      sets up the `reqlab` namespace, injects context data, executes
 *      the user script, and returns results as a JSON string.
 *   2. [evaluateJs] evaluates that IIFE on the platform's JS engine.
 *   3. This class parses the JSON result into a [ScriptResult].
 */
class ReqLabScriptEngine(
    private val evaluator: (String) -> String = ::evaluateJs,
    private val bootstrapBuilder: (String, ScriptContext, String) -> String = ScriptBootstrap::build,
    /**
     * Optional executor for `reqlab.sendRequest()` calls from scripts.
     * When null, queued sub-requests are silently ignored (callback never runs).
     * When provided, the engine makes real HTTP calls and runs callbacks in a
     * second JS evaluation phase, merging assertions/logs/variables into the result.
     */
    private val sendRequestExecutor: (suspend (SendRequestSpec) -> SendRequestResult)? = null,
) : ScriptEngine {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun executePreRequestScript(
        script: String,
        context: ScriptContext,
        prefix: String,
    ): ScriptResult = execute(script, context, prefix)

    override suspend fun executeTestScript(
        script: String,
        context: ScriptContext,
        prefix: String,
    ): ScriptResult = execute(script, context, prefix)

    private suspend fun execute(
        script: String,
        context: ScriptContext,
        prefix: String,
    ): ScriptResult {
        if (script.isBlank()) {
            return ScriptResult(success = true)
        }
        return try {
            val jsProgram = bootstrapBuilder(script, context, prefix)
            val resultJson = evaluator(jsProgram)
            val baseResult = parseResult(resultJson)
            val pendingSendRequests = parseSendRequests(resultJson)
            if (pendingSendRequests.isNotEmpty() && sendRequestExecutor != null) {
                processSendRequests(baseResult, pendingSendRequests, context, prefix)
            } else {
                baseResult
            }
        } catch (e: Exception) {
            ScriptResult(
                success = false,
                error = "Script execution failed: ${e.message}",
            )
        }
    }

    private suspend fun processSendRequests(
        baseResult: ScriptResult,
        pending: List<SendRequestSpec>,
        context: ScriptContext,
        prefix: String,
    ): ScriptResult {
        var merged = baseResult
        for (spec in pending) {
            try {
                val subResponse = sendRequestExecutor!!.invoke(spec)
                val callbackSrc = spec.callbackSource ?: continue
                val callbackScript = buildCallbackUserScript(callbackSrc, subResponse)
                val callbackCtx = buildCallbackContext(context, merged, subResponse)
                val callbackProgram = bootstrapBuilder(callbackScript, callbackCtx, prefix)
                val callbackResultJson = evaluator(callbackProgram)
                val callbackResult = parseResult(callbackResultJson)
                merged = mergeResults(merged, callbackResult)
            } catch (e: Exception) {
                merged = merged.copy(
                    logs = merged.logs + "[sendRequest error] ${e.message ?: "Unknown error"}",
                )
            }
        }
        return merged
    }

    // ── sendRequest helpers ───────────────────────────────────────────────

    /**
     * Builds a JS user-script that declares a synthetic response object
     * and invokes the serialized callback source with it.
     */
    private fun buildCallbackUserScript(callbackSource: String, sub: SendRequestResult): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
        val escapedBody = esc(sub.body)
        val escapedStatus = esc(sub.statusText)
        val headersJson = sub.headers.entries.joinToString(",") { (k, v) -> "\"${esc(k)}\":\"${esc(v)}\"" }
        return """
            var __sr_code=${sub.statusCode};
            var __sr_st="${escapedStatus}";
            var __sr_body="${escapedBody}";
            var __sr_time=${sub.elapsedMs};
            var __sr_hdr={${headersJson}};
            var __sr_resp={
              code:__sr_code,status:__sr_st,statusCode:__sr_code,responseTime:__sr_time,
              json:function(){return __sr_body?JSON.parse(__sr_body):null;},
              text:function(){return __sr_body||'';},
              headers:{get:function(n){var l=n.toLowerCase();for(var k in __sr_hdr){if(k.toLowerCase()===l)return __sr_hdr[k];}return undefined;}}
            };
            ($callbackSource)(null,__sr_resp);
        """.trimIndent()
    }

    /**
     * Builds a [ScriptContext] for the callback phase:
     * - response fields reflect the sub-request response
     * - environment/global/collection variables are merged with phase-1 mutations
     */
    private fun buildCallbackContext(
        original: ScriptContext,
        phase1Result: ScriptResult,
        sub: SendRequestResult,
    ): ScriptContext = original.copy(
        statusCode = sub.statusCode,
        responseBody = sub.body,
        responseHeaders = sub.headers,
        responseTimeMs = sub.elapsedMs,
        responseSizeBytes = sub.body.length.toLong(),
        variables = original.variables + phase1Result.newVariables,
        globalVariables = original.globalVariables + phase1Result.newGlobalVariables,
        collectionVariables = original.collectionVariables + phase1Result.newCollectionVariables,
    )

    /**
     * Merges callback result into the base result.
     * Later variables override earlier ones; assertions and logs are concatenated.
     */
    private fun mergeResults(base: ScriptResult, cb: ScriptResult) = ScriptResult(
        success = base.success && cb.success,
        logs = base.logs + cb.logs,
        assertions = base.assertions + cb.assertions,
        error = base.error ?: cb.error,
        newVariables = base.newVariables + cb.newVariables,
        newGlobalVariables = base.newGlobalVariables + cb.newGlobalVariables,
        newCollectionVariables = base.newCollectionVariables + cb.newCollectionVariables,
        newRequestVariables = base.newRequestVariables + cb.newRequestVariables,
        requestMutations = mergeRequestMutations(base.requestMutations, cb.requestMutations),
        executionSetNextRequestCalled = cb.executionSetNextRequestCalled || base.executionSetNextRequestCalled,
        executionNextRequest = if (cb.executionSetNextRequestCalled) cb.executionNextRequest else base.executionNextRequest,
        executionSkipRequest = cb.executionSkipRequest || base.executionSkipRequest,
    )

    private fun mergeRequestMutations(base: ScriptRequestMutations, cb: ScriptRequestMutations) =
        ScriptRequestMutations(
            url = cb.url ?: base.url,
            method = cb.method ?: base.method,
            body = cb.body ?: base.body,
            headers = base.headers + cb.headers,
            queryParams = base.queryParams + cb.queryParams,
        )

    // ── JSON result parsing ───────────────────────────────────────────────

    private fun parseSendRequests(resultJson: String): List<SendRequestSpec> {
        return try {
            val root = json.parseToJsonElement(resultJson).jsonObject
            val arr = root["sendRequests"]?.jsonArray ?: return emptyList()
            arr.map { el ->
                val obj = el.jsonObject
                SendRequestSpec(
                    url = obj["url"]?.jsonPrimitive?.contentOrNull ?: "",
                    method = obj["method"]?.jsonPrimitive?.contentOrNull ?: "GET",
                    headers = jsonObjectToMap(obj["headers"]),
                    body = obj["body"]?.jsonPrimitive?.contentOrNull,
                    callbackSource = obj["callbackSource"]?.jsonPrimitive?.contentOrNull,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseResult(resultJson: String): ScriptResult {
        val root = json.parseToJsonElement(resultJson).jsonObject

        val error = root["error"]?.jsonPrimitive?.contentOrNull

        val tests = root["tests"]?.jsonArray?.map { el ->
            val obj = el.jsonObject
            AssertionResult(
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                passed = obj["passed"]?.jsonPrimitive?.booleanOrNull ?: false,
                message = obj["message"]?.jsonPrimitive?.contentOrNull,
            )
        } ?: emptyList()

        val logs = root["logs"]?.jsonArray?.map { el ->
            el.jsonPrimitive.contentOrNull ?: ""
        } ?: emptyList()

        val envVars = jsonObjectToMap(root["envVars"])
        val globalVars = jsonObjectToMap(root["globalVars"])
        val collVars = jsonObjectToMap(root["collVars"])
        val reqVars = jsonObjectToMap(root["reqVars"])

        val mutObj = root["mutations"]?.jsonObject
        val mutations = ScriptRequestMutations(
            url = mutObj?.get("url")?.jsonPrimitive?.contentOrNull,
            method = mutObj?.get("method")?.jsonPrimitive?.contentOrNull,
            body = mutObj?.get("body")?.jsonPrimitive?.contentOrNull,
            headers = jsonObjectToMap(mutObj?.get("headers")),
            queryParams = jsonObjectToMap(mutObj?.get("queryParams")),
        )

        val execObj = root["execution"]?.jsonObject
        val executionSetNextRequestCalled = execObj?.get("setNextRequestCalled")?.jsonPrimitive?.booleanOrNull ?: false
        val executionNextRequest = execObj?.get("nextRequest")?.jsonPrimitive?.contentOrNull
        val executionSkipRequest = execObj?.get("skipRequest")?.jsonPrimitive?.booleanOrNull ?: false

        return ScriptResult(
            success = error == null && tests.all { it.passed },
            logs = logs,
            assertions = tests,
            error = error,
            newVariables = envVars,
            newGlobalVariables = globalVars,
            newCollectionVariables = collVars,
            newRequestVariables = reqVars,
            requestMutations = mutations,
            executionSetNextRequestCalled = executionSetNextRequestCalled,
            executionNextRequest = executionNextRequest,
            executionSkipRequest = executionSkipRequest,
        )
    }

    private fun jsonObjectToMap(element: kotlinx.serialization.json.JsonElement?): Map<String, String> {
        if (element == null || element !is JsonObject) return emptyMap()
        return element.entries.associate { (k, v) ->
            k to (v.jsonPrimitive.contentOrNull ?: "")
        }
    }
}
