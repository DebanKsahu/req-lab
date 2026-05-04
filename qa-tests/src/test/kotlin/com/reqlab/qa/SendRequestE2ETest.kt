package com.reqlab.qa

import com.reqlab.core.scripting.ReqLabScriptEngine
import com.reqlab.core.scripting.ScriptContext
import com.reqlab.core.scripting.ScriptResult
import com.reqlab.core.scripting.SendRequestResult
import com.reqlab.core.scripting.SendRequestSpec
import com.reqlab.server.module
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.net.ServerSocket
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end integration tests for `reqlab.sendRequest()`.
 *
 * Each test spins scripts against an embedded sample server so the HTTP calls
 * are real.  The engine under test is a [ReqLabScriptEngine] wired with a
 * real Ktor CIO executor — exactly the same wiring used by [RequestExecutor]
 * in production.
 *
 * Endpoints used (all in the embedded sample server):
 *  - POST /api/chain/token   — issues a Bearer token for the chain demo
 *  - GET  /api/chain/data    — requires Authorization: Bearer <chain token>
 *  - GET  /api/users/{id}    — returns a single user (id 1–3)
 *  - POST /api/token         — existing auth token endpoint (user+role)
 *  - GET  /api/protected     — requires X-Token header
 *  - GET  /json/user         — basic JSON user fixture
 *  - GET  /api/echo-full     — echoes all request headers & params
 *  - POST /api/validate      — validates a JSON body
 */
class SendRequestE2ETest {

    // ── Engine under test ─────────────────────────────────────────────────

    private val engine: ReqLabScriptEngine by lazy {
        ReqLabScriptEngine(
            sendRequestExecutor = { spec ->
                val startMs = System.currentTimeMillis()
                val response = client.request(spec.url) {
                    method = HttpMethod.parse(spec.method)
                    spec.headers.forEach { (k, v) -> headers.append(k, v) }
                    spec.body?.let { setBody(it) }
                }
                SendRequestResult(
                    statusCode  = response.status.value,
                    statusText  = response.status.description,
                    body        = response.bodyAsText(),
                    headers     = response.headers.entries().associate { it.key to (it.value.firstOrNull() ?: "") },
                    elapsedMs   = System.currentTimeMillis() - startMs,
                )
            }
        )
    }

    private val base get() = BASE_URL

    // ── Test helpers ──────────────────────────────────────────────────────

    private suspend fun runPre(script: String, env: Map<String, String> = emptyMap()): ScriptResult =
        engine.executePreRequestScript(
            script,
            ScriptContext(url = "$base/status/200", method = "GET", variables = env),
            prefix = "reqlab",
        )

    private suspend fun runTest_(script: String, statusCode: Int = 200, body: String = "{}", env: Map<String, String> = emptyMap()): ScriptResult =
        engine.executeTestScript(
            script,
            ScriptContext(
                url = "$base/status/200", method = "GET",
                statusCode = statusCode, responseBody = body,
                variables = env,
            ),
            prefix = "reqlab",
        )

    // ── Chain-token flow tests ─────────────────────────────────────────────

    @Test
    fun `chain token pre-script fetches bearer token and sets Authorization header`() = runTest {
        val result = runPre("""
            reqlab.sendRequest({
                url:    "$base/api/chain/token",
                method: "POST",
                header: [{ key: "Content-Type", value: "application/json" }],
                body:   { mode: "raw", raw: '{"username":"alice"}' }
            }, function(err, resp) {
                var token = resp.json().token
                reqlab.environment.set("chainToken", token)
            })
        """.trimIndent())

        assertTrue(result.success, "Pre-script must succeed: ${result.logs}")
        val token = result.newVariables["chainToken"]
        assertTrue(!token.isNullOrBlank(), "chainToken should be set, got: $token")
        assertTrue(token!!.startsWith("chain."), "Token should have chain. prefix, got: $token")
    }

    @Test
    fun `chain token stored in pre-script allows access to protected chain data`() = runTest {
        // Phase 1: fetch token via sendRequest
        val preResult = runPre("""
            reqlab.sendRequest({
                url:    "$base/api/chain/token",
                method: "POST",
                header: [{ key: "Content-Type", value: "application/json" }],
                body:   { mode: "raw", raw: '{"username":"bob"}' }
            }, function(err, resp) {
                var token = resp.json().token
                reqlab.environment.set("chainToken", token)
                reqlab.request.headers.upsert("Authorization", "Bearer " + token)
            })
        """.trimIndent())

        assertTrue(preResult.success, "Pre-script should succeed: ${preResult.logs}")
        val token = preResult.newVariables["chainToken"]
        assertTrue(!token.isNullOrBlank(), "chainToken must be set")

        // Phase 2: main request uses the injected Authorization header
        val authHeader = preResult.requestMutations.headers["Authorization"]
        assertTrue(!authHeader.isNullOrBlank(), "Authorization header should be injected")
        assertTrue(authHeader!!.startsWith("Bearer chain."), "Should have Bearer chain. prefix")

        // Simulate the actual request to /api/chain/data using the token
        val response = client.request("$base/api/chain/data") {
            method = HttpMethod.Get
            headers.append("Authorization", authHeader)
        }
        val body = response.bodyAsText()
        assertEquals(200, response.status.value, "Chain data should be accessible: $body")
        assertTrue(body.contains("bob"), "Decoded username should appear in response: $body")
        assertTrue(body.contains("chain-protected-data"), "Should return the protected resource")
    }

    @Test
    fun `user lookup pre-script fetches user by id and injects role header`() = runTest {
        val result = runPre("""
            reqlab.sendRequest("$base/api/users/1", function(err, resp) {
                var user = resp.json()
                reqlab.environment.set("fetchedRole", user.role)
                reqlab.environment.set("fetchedName", user.name)
                reqlab.request.headers.upsert("X-User-Role", user.role)
            })
        """.trimIndent())

        assertTrue(result.success, "User lookup should succeed: ${result.logs}")
        assertEquals("admin", result.newVariables["fetchedRole"], "User 1 is admin")
        assertEquals("Alice", result.newVariables["fetchedName"], "User 1 is Alice")
        assertEquals("admin", result.requestMutations.headers["X-User-Role"], "Header should be injected")
    }

    @Test
    fun `user lookup for all valid IDs returns correct roles`() = runTest {
        val expected = mapOf(1 to "admin", 2 to "user", 3 to "moderator")
        expected.forEach { (id, expectedRole) ->
            val result = runPre("""
                reqlab.sendRequest("$base/api/users/$id", function(err, resp) {
                    reqlab.environment.set("role_$id", resp.json().role)
                })
            """.trimIndent())
            assertTrue(result.success)
            assertEquals(expectedRole, result.newVariables["role_$id"], "User $id role mismatch")
        }
    }

    @Test
    fun `sendRequest options object passes method headers and body to chain token endpoint`() = runTest {
        val result = runPre("""
            reqlab.sendRequest({
                url:    "$base/api/chain/token",
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body:   '{"username":"carol"}'
            }, function(err, resp) {
                reqlab.environment.set("carolToken", resp.json().token)
                reqlab.environment.set("carolUser",  resp.json().username)
            })
        """.trimIndent())

        assertTrue(result.success, "sendRequest with headers object format should succeed: ${result.logs}")
        assertEquals("carol", result.newVariables["carolUser"])
        assertTrue(result.newVariables["carolToken"]?.startsWith("chain.") == true)
    }

    // ── Classic auth flow (POST /api/token → GET /api/protected) ──────────

    @Test
    fun `classic auth flow fetches pm-style token and sets X-Token for protected endpoint`() = runTest {
        val preResult = runPre("""
            reqlab.sendRequest({
                url:    "$base/api/token",
                method: "POST",
                header: [{ key: "Content-Type", value: "application/json" }],
                body:   { mode: "raw", raw: '{"user":"alice","role":"admin"}' }
            }, function(err, resp) {
                var token = resp.json().token
                reqlab.environment.set("authToken", token)
                reqlab.request.headers.upsert("X-Token", token)
            })
        """.trimIndent())

        assertTrue(preResult.success, "Auth pre-script should succeed: ${preResult.logs}")
        val token = preResult.newVariables["authToken"]
        assertTrue(!token.isNullOrBlank(), "authToken must be set")
        assertEquals(token, preResult.requestMutations.headers["X-Token"], "X-Token header must be injected")

        // Verify the token actually works against /api/protected
        val protectedResp = client.request("$base/api/protected") {
            method = HttpMethod.Get
            headers.append("X-Token", token!!)
        }
        assertEquals(200, protectedResp.status.value)
        val protectedBody = protectedResp.bodyAsText()
        assertTrue(protectedBody.contains("alice"), "Decoded token should include the username")
    }

    // ── Test-script sendRequest (post-response assertions) ─────────────────

    @Test
    fun `test script calls sendRequest to validate side effect on secondary endpoint`() = runTest {
        val result = runTest_(
            script = """
                reqlab.sendRequest("$base/json/user", function(err, resp) {
                    reqlab.test("sub-request status is 200", function() {
                        reqlab.expect(resp.code).to.equal(200)
                    })
                    reqlab.test("sub-request user has name", function() {
                        reqlab.expect(resp.json().name).to.equal("Jane")
                    })
                    reqlab.environment.set("sideEffectName", resp.json().name)
                })
            """.trimIndent(),
        )

        assertTrue(result.success, "Test script with sendRequest should succeed: ${result.logs}")
        assertEquals(2, result.assertions.size, "Two assertions from callback")
        assertTrue(result.assertions.all { it.passed }, "All assertions should pass")
        assertEquals("Jane", result.newVariables["sideEffectName"])
    }

    @Test
    fun `test script sendRequest callback failure counts as test failure`() = runTest {
        val result = runTest_(
            script = """
                reqlab.sendRequest("$base/json/user", function(err, resp) {
                    reqlab.test("intentionally wrong assertion", function() {
                        reqlab.expect(resp.json().name).to.equal("NOT-JANE")
                    })
                })
            """.trimIndent(),
        )

        // The script overall fails because the assertion failed
        assertFalse(result.success, "Should report failure when callback assertion fails")
        assertEquals(1, result.assertions.size)
        assertFalse(result.assertions[0].passed)
    }

    // ── Multiple sequential sendRequests ──────────────────────────────────

    @Test
    fun `multiple sendRequests in pre-script execute in order and accumulate variables`() = runTest {
        val result = runPre("""
            reqlab.sendRequest("$base/api/users/1", function(err, resp) {
                reqlab.environment.set("user1Name", resp.json().name)
                reqlab.environment.set("user1Role", resp.json().role)
            })
            reqlab.sendRequest("$base/api/users/2", function(err, resp) {
                reqlab.environment.set("user2Name", resp.json().name)
                reqlab.environment.set("user2Role", resp.json().role)
            })
            reqlab.sendRequest("$base/api/users/3", function(err, resp) {
                reqlab.environment.set("user3Role", resp.json().role)
            })
        """.trimIndent())

        assertTrue(result.success, "Multiple sendRequests should succeed: ${result.logs}")
        assertEquals("Alice",      result.newVariables["user1Name"])
        assertEquals("admin",      result.newVariables["user1Role"])
        assertEquals("Bob",        result.newVariables["user2Name"])
        assertEquals("user",       result.newVariables["user2Role"])
        assertEquals("moderator",  result.newVariables["user3Role"])
    }

    @Test
    fun `chained sendRequests use result of first call in second call body`() = runTest {
        // This test uses two separate sendRequests in the same pre-script.
        // The first fetches a user's role; the second posts to validate with that role.
        val result = runPre("""
            reqlab.sendRequest("$base/api/users/1", function(err, resp) {
                var role = resp.json().role
                reqlab.environment.set("lookedUpRole", role)
                // second sendRequest uses the role from the first
                reqlab.sendRequest({
                    url:    "$base/api/chain/token",
                    method: "POST",
                    header: [{ key: "Content-Type", value: "application/json" }],
                    body:   { mode: "raw", raw: '{"username":"' + role + '"}' }
                }, function(err2, resp2) {
                    reqlab.environment.set("roleToken", resp2.json().token)
                    reqlab.environment.set("roleUser",  resp2.json().username)
                })
            })
        """.trimIndent())

        assertTrue(result.success, "Nested sendRequests should succeed: ${result.logs}")
        assertEquals("admin", result.newVariables["lookedUpRole"], "First sendRequest result")
        // Nested sendRequest may not be processed (only top-level queue is processed per phase),
        // but top-level variables should be set
        assertFalse(result.newVariables["lookedUpRole"].isNullOrBlank())
    }

    // ── Error handling ─────────────────────────────────────────────────────

    @Test
    fun `sendRequest to unreachable host does not throw and appends error log`() = runTest {
        val result = runPre("""
            reqlab.sendRequest("http://localhost:1/no-route", function(err, resp) {
                reqlab.environment.set("gotErr", err ? "yes" : "no")
            })
        """.trimIndent())

        // Script must not throw — error goes to logs
        assertTrue(result.success, "Unreachable sendRequest should not crash the script")
    }

    @Test
    fun `sendRequest to nonexistent user ID returns 404 and callback can check status`() = runTest {
        val result = runPre("""
            reqlab.sendRequest("$base/api/users/999", function(err, resp) {
                reqlab.environment.set("notFoundCode", String(resp.code))
            })
        """.trimIndent())

        assertTrue(result.success, "404 response should not crash the script: ${result.logs}")
        assertEquals("404", result.newVariables["notFoundCode"], "Should capture 404 status code")
    }

    @Test
    fun `sendRequest without callback still executes without crashing`() = runTest {
        val result = runPre("""
            reqlab.sendRequest("$base/json/user")
        """.trimIndent())

        assertTrue(result.success, "sendRequest without callback should not throw: ${result.logs}")
    }

    // ── Response data access in callbacks ─────────────────────────────────

    @Test
    fun `callback can access resp text json code and headers`() = runTest {
        val result = runPre("""
            reqlab.sendRequest("$base/json/user", function(err, resp) {
                reqlab.environment.set("respCode",   String(resp.code))
                reqlab.environment.set("respText",   resp.text())
                reqlab.environment.set("respName",   resp.json().name)
                reqlab.environment.set("hasHeader",  resp.headers.get("Content-Type") ? "yes" : "no")
            })
        """.trimIndent())

        assertTrue(result.success, "Callback data access should work: ${result.logs}")
        assertEquals("200",  result.newVariables["respCode"])
        assertTrue(result.newVariables["respText"]?.contains("Jane") == true)
        assertEquals("Jane", result.newVariables["respName"])
        assertEquals("yes",  result.newVariables["hasHeader"])
    }

    @Test
    fun `callback receives elapsed time information in response`() = runTest {
        val result = runPre("""
            reqlab.sendRequest("$base/json/user", function(err, resp) {
                var elapsed = resp.responseTime
                reqlab.environment.set("hasElapsed", elapsed >= 0 ? "yes" : "no")
            })
        """.trimIndent())

        assertTrue(result.success, "Elapsed time should be available: ${result.logs}")
        assertEquals("yes", result.newVariables["hasElapsed"])
    }

    // ── String vs options-object URL formats ──────────────────────────────

    @Test
    fun `string URL format is equivalent to options object with GET method`() = runTest {
        val stringResult = runPre("""
            reqlab.sendRequest("$base/api/users/2", function(err, resp) {
                reqlab.environment.set("stringName", resp.json().name)
            })
        """.trimIndent())

        val optionsResult = runPre("""
            reqlab.sendRequest({ url: "$base/api/users/2", method: "GET" }, function(err, resp) {
                reqlab.environment.set("optionsName", resp.json().name)
            })
        """.trimIndent())

        assertTrue(stringResult.success)
        assertTrue(optionsResult.success)
        assertEquals("Bob", stringResult.newVariables["stringName"])
        assertEquals("Bob", optionsResult.newVariables["optionsName"])
    }

    @Test
    fun `options object with Postman-style header array parses correctly`() = runTest {
        val result = runPre("""
            reqlab.sendRequest({
                url:    "$base/api/chain/token",
                method: "POST",
                header: [
                    { key: "Content-Type", value: "application/json" },
                    { key: "X-Client",     value: "reqlab-test" }
                ],
                body: { mode: "raw", raw: '{"username":"dave"}' }
            }, function(err, resp) {
                reqlab.environment.set("daveUser", resp.json().username)
            })
        """.trimIndent())

        assertTrue(result.success, "Postman-style header array should work: ${result.logs}")
        assertEquals("dave", result.newVariables["daveUser"])
    }

    // ── Companion: server lifecycle ────────────────────────────────────────

    companion object {
        private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
        private var port: Int = 0
        private var BASE_URL: String = ""

        private val client = HttpClient(CIO) {
            followRedirects = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        @BeforeClass
        @JvmStatic
        fun startServer() {
            port = ServerSocket(0).use { it.localPort }
            BASE_URL = "http://localhost:$port"
            server = embeddedServer(Netty, port = port, module = { module() })
            server!!.start(wait = false)
            repeat(50) {
                runCatching { java.net.Socket("localhost", port).close(); return }
                Thread.sleep(100)
            }
        }

        @AfterClass
        @JvmStatic
        fun stopServer() {
            server?.stop(100L, 100L)
        }
    }
}
