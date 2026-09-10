package com.peter.minimal

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Gemini API client using only built-in Android/Java classes
 * (HttpURLConnection + org.json, both included in Android — no extra
 * Gradle dependency needed, which means one less thing that can break
 * the build).
 *
 * UNVERIFIED: the exact endpoint path and JSON request/response shape
 * are written to the best of my knowledge but not checked against live
 * docs (no network access here). If this fails, check
 * https://ai.google.dev/gemini-api/docs for the current REST request
 * format and adjust buildRequestJson()/parseResponse() below — the
 * surrounding networking code (connection setup, error handling) should
 * still be correct regardless.
 *
 * This performs a blocking network call — always invoke it from a
 * background thread, never the main thread.
 */
class GeminiClient(private val apiKey: String) {

    /**
     * Sends [prompt] to Gemini and returns its text reply, or throws
     * an exception with a descriptive message on failure. Caller is
     * responsible for catching and displaying/logging the exception —
     * this class does not swallow errors silently.
     */
    fun generateReply(prompt: String): String {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "gemini-1.5-flash:generateContent?key=$apiKey"
        )

        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000

            val requestBody = buildRequestJson(prompt)
            connection.outputStream.use { out: OutputStream ->
                out.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = BufferedReader(InputStreamReader(stream)).use { it.readText() }

            if (responseCode !in 200..299) {
                throw RuntimeException(
                    "Gemini API returned HTTP $responseCode: ${responseText.take(300)}"
                )
            }

            return parseResponse(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildRequestJson(prompt: String): String {
        val root = JSONObject()
        val contents = JSONArray()
        val content = JSONObject()
        val parts = JSONArray()
        val part = JSONObject()

        part.put("text", prompt)
        parts.put(part)
        content.put("parts", parts)
        contents.put(content)
        root.put("contents", contents)

        return root.toString()
    }

    private fun parseResponse(responseText: String): String {
        try {
            val root = JSONObject(responseText)
            val candidates = root.optJSONArray("candidates")
                ?: throw RuntimeException("No 'candidates' in Gemini response: ${responseText.take(300)}")

            if (candidates.length() == 0) {
                throw RuntimeException("Empty 'candidates' array in Gemini response")
            }

            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.getJSONObject("content")
            val parts = content.getJSONArray("parts")
            val text = parts.getJSONObject(0).getString("text")

            return text.trim()
        } catch (e: Exception) {
            throw RuntimeException(
                "Could not parse Gemini response (API shape may have changed): ${e.message}"
            )
        }
    }
}
