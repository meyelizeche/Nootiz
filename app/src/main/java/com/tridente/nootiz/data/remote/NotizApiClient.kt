package com.tridente.nootiz.data.remote

import android.util.LruCache
import com.tridente.nootiz.data.local.SessionPreferences
import com.tridente.nootiz.data.model.AttachmentMetadata
import com.tridente.nootiz.data.model.AuthResponse
import com.tridente.nootiz.data.model.Folder
import com.tridente.nootiz.data.model.Note
import com.tridente.nootiz.data.model.NoteShareInfo
import com.tridente.nootiz.data.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class ApiException(val statusCode: Int, override val message: String) : Exception(message)

fun Throwable.isOfflineOrUnreachable(): Boolean {
    return when (this) {
        is IOException -> true
        is ApiException -> statusCode == 502 || statusCode == 503 || statusCode == 504
        else -> cause?.isOfflineOrUnreachable() == true
    }
}

fun Throwable.isUnauthorizedError(): Boolean {
    return (this as? ApiException)?.statusCode == 401
}

class NotizApiClient(private val sessionPrefs: SessionPreferences) {

    private val attachmentCache = object : LruCache<String, ByteArray>(15 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    private fun resolveUrl(path: String, overrideBaseUrl: String? = null): URL {
        val base = SessionPreferences.normalizeBaseUrl(overrideBaseUrl ?: sessionPrefs.baseUrl)
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return URL("$base$cleanPath")
    }

    // 5.1 Health Check: GET /api/health
    suspend fun checkHealth(customBaseUrl: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (resolveUrl("/api/health", customBaseUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code in 200..299) {
                    val body = readStream(conn.inputStream)
                    val json = JSONObject(body)
                    json.optString("status") == "ok"
                } else {
                    throw parseApiError(code, conn.errorStream)
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    // 5.2 Auth: POST /api/auth/register
    suspend fun register(username: String, email: String, password: String): Result<AuthResponse> {
        val payload = JSONObject().apply {
            put("username", username.trim())
            put("email", email.trim())
            put("password", password)
        }
        return requestJson("POST", "/api/auth/register", payload, authRequired = false) { raw ->
            parseAuthResponse(JSONObject(raw))
        }
    }

    // 5.2 Auth: POST /api/auth/login
    suspend fun login(identifier: String, password: String): Result<AuthResponse> {
        val payload = JSONObject().apply {
            put("identifier", identifier.trim())
            put("password", password)
        }
        return requestJson("POST", "/api/auth/login", payload, authRequired = false) { raw ->
            parseAuthResponse(JSONObject(raw))
        }
    }

    // 5.2 Auth: POST /api/auth/google
    suspend fun loginWithGoogle(idToken: String): Result<AuthResponse> {
        val payload = JSONObject().apply {
            put("id_token", idToken.trim())
        }
        return requestJson("POST", "/api/auth/google", payload, authRequired = false) { raw ->
            parseAuthResponse(JSONObject(raw))
        }
    }

    // 5.2 Auth: GET /api/auth/me
    suspend fun getMe(): Result<User> {
        return requestJson("GET", "/api/auth/me", null, authRequired = true) { raw ->
            parseUser(JSONObject(raw))
        }
    }

    // 5.3 Folders: GET /api/folders
    suspend fun getFolders(): Result<List<Folder>> {
        return requestJson("GET", "/api/folders", null, authRequired = true) { raw ->
            val arr = JSONArray(raw)
            List(arr.length()) { idx -> parseFolder(arr.getJSONObject(idx)) }
        }
    }

    // 5.3 Folders: POST /api/folders
    suspend fun createFolder(name: String): Result<Folder> {
        val payload = JSONObject().apply {
            put("name", name.trim())
        }
        return requestJson("POST", "/api/folders", payload, authRequired = true) { raw ->
            parseFolder(JSONObject(raw))
        }
    }

    // 5.3 Folders: PUT /api/folders/{id}
    suspend fun renameFolder(folderId: String, newName: String): Result<Folder> {
        val payload = JSONObject().apply {
            put("name", newName.trim())
        }
        return requestJson("PUT", "/api/folders/$folderId", payload, authRequired = true) { raw ->
            parseFolder(JSONObject(raw))
        }
    }

    // 5.3 Folders: DELETE /api/folders/{id}
    suspend fun deleteFolder(folderId: String): Result<Unit> {
        return requestJson("DELETE", "/api/folders/$folderId", null, authRequired = true) { }
    }

    // 5.4 Notes: GET /api/notes
    suspend fun getNotes(
        folderId: String? = null,
        tag: String? = null,
        sharedOnly: Boolean = false
    ): Result<List<Note>> {
        val queryParts = mutableListOf<String>()
        if (!folderId.isNullOrBlank()) {
            queryParts.add("folder_id=${URLEncoder.encode(folderId, "UTF-8")}")
        }
        if (!tag.isNullOrBlank()) {
            queryParts.add("tag=${URLEncoder.encode(tag.trim(), "UTF-8")}")
        }
        if (sharedOnly) {
            queryParts.add("shared_only=true")
        }
        val query = if (queryParts.isEmpty()) "" else "?${queryParts.joinToString("&")}"
        return requestJson("GET", "/api/notes$query", null, authRequired = true) { raw ->
            val arr = JSONArray(raw)
            List(arr.length()) { idx -> parseNote(arr.getJSONObject(idx)) }
        }
    }

    // 5.4 Notes: GET /api/notes/{id}
    suspend fun getNote(noteId: String): Result<Note> {
        return requestJson("GET", "/api/notes/$noteId", null, authRequired = true) { raw ->
            parseNote(JSONObject(raw))
        }
    }

    // 5.4 Notes: POST /api/notes
    suspend fun createNote(
        title: String,
        body: String = "",
        tags: List<String> = emptyList(),
        folderId: String? = null
    ): Result<Note> {
        val payload = JSONObject().apply {
            put("title", title.trim().ifEmpty { "\u200B" })
            put("body", body)
            val tagsArr = JSONArray()
            tags.forEach { t ->
                val clean = t.trim().lowercase()
                if (clean.isNotEmpty()) tagsArr.put(clean)
            }
            put("tags", tagsArr)
            if (!folderId.isNullOrBlank()) {
                put("folder_id", folderId)
            }
        }
        return requestJson("POST", "/api/notes", payload, authRequired = true) { raw ->
            parseNote(JSONObject(raw))
        }
    }

    // 5.4 Notes: PUT /api/notes/{id}
    suspend fun updateNote(
        noteId: String,
        title: String? = null,
        body: String? = null,
        tags: List<String>? = null,
        updateFolder: Boolean = false,
        folderId: String? = null
    ): Result<Note> {
        val payload = JSONObject().apply {
            if (title != null) put("title", title.trim().ifEmpty { "\u200B" })
            if (body != null) put("body", body)
            if (tags != null) {
                val tagsArr = JSONArray()
                tags.forEach { t ->
                    val clean = t.trim().lowercase()
                    if (clean.isNotEmpty()) tagsArr.put(clean)
                }
                put("tags", tagsArr)
            }
            if (updateFolder) {
                if (folderId == null) {
                    put("folder_id", JSONObject.NULL)
                } else {
                    put("folder_id", folderId)
                }
            }
        }
        return requestJson("PUT", "/api/notes/$noteId", payload, authRequired = true) { raw ->
            parseNote(JSONObject(raw))
        }
    }

    // 5.4 Notes: DELETE /api/notes/{id}
    suspend fun deleteNote(noteId: String): Result<Unit> {
        return requestJson("DELETE", "/api/notes/$noteId", null, authRequired = true) { }
    }

    // 5.5 Shares: POST /api/notes/{id}/shares
    suspend fun shareNote(
        noteId: String,
        recipient: String,
        permission: String = "read"
    ): Result<NoteShareInfo> {
        val payload = JSONObject().apply {
            put("recipient", recipient.trim())
            put("permission", permission)
        }
        return requestJson("POST", "/api/notes/$noteId/shares", payload, authRequired = true) { raw ->
            parseShareInfo(JSONObject(raw))
        }
    }

    // 5.5 Shares: GET /api/notes/{id}/shares
    suspend fun getNoteShares(noteId: String): Result<List<NoteShareInfo>> {
        return requestJson("GET", "/api/notes/$noteId/shares", null, authRequired = true) { raw ->
            val arr = JSONArray(raw)
            List(arr.length()) { idx -> parseShareInfo(arr.getJSONObject(idx)) }
        }
    }

    // 5.5 Shares: DELETE /api/notes/{id}/shares/{target_user_id}
    suspend fun revokeNoteShare(noteId: String, targetUserId: String): Result<Unit> {
        return requestJson("DELETE", "/api/notes/$noteId/shares/$targetUserId", null, authRequired = true) { }
    }

    // 5.6 Attachments: GET /api/notes/{id}/attachments
    suspend fun getAttachments(noteId: String): Result<List<AttachmentMetadata>> {
        return requestJson("GET", "/api/notes/$noteId/attachments", null, authRequired = true) { raw ->
            val arr = JSONArray(raw)
            List(arr.length()) { idx -> parseAttachment(arr.getJSONObject(idx)) }
        }
    }

    // 5.6 Attachments: POST /api/notes/{id}/attachments (multipart/form-data field "file")
    suspend fun uploadAttachment(
        noteId: String,
        filename: String,
        contentType: String,
        bytes: ByteArray
    ): Result<AttachmentMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val boundary = "----NootizBoundary" + UUID.randomUUID().toString().replace("-", "")
            val lineEnd = "\r\n"
            val twoHyphens = "--"
            val safeFilename = filename.replace("\"", "_")

            val conn = (resolveUrl("/api/notes/$noteId/attachments").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doInput = true
                doOutput = true
                useCaches = false
                connectTimeout = 5000
                readTimeout = 20000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                sessionPrefs.token?.let { setRequestProperty("Authorization", "Bearer $it") }
            }

            try {
                DataOutputStream(conn.outputStream).use { dos ->
                    val headerText = buildString {
                        append(twoHyphens).append(boundary).append(lineEnd)
                        append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(safeFilename).append("\"").append(lineEnd)
                        append("Content-Type: ").append(contentType).append(lineEnd)
                        append(lineEnd)
                    }
                    val footerText = "$lineEnd$twoHyphens$boundary$twoHyphens$lineEnd"

                    dos.write(headerText.toByteArray(Charsets.UTF_8))
                    dos.write(bytes)
                    dos.write(footerText.toByteArray(Charsets.UTF_8))
                    dos.flush()
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    val raw = readStream(conn.inputStream)
                    val meta = parseAttachment(JSONObject(raw))
                    attachmentCache.put(meta.id, bytes)
                    meta
                } else {
                    throw parseApiError(code, conn.errorStream)
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    // 5.6 Attachments: GET /api/notes/{id}/attachments/{attachment_id}
    suspend fun downloadAttachmentBytes(noteId: String, attachmentId: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                attachmentCache.get(attachmentId)?.let { return@runCatching it }
                val conn = (resolveUrl("/api/notes/$noteId/attachments/$attachmentId")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 15000
                    sessionPrefs.token?.let { setRequestProperty("Authorization", "Bearer $it") }
                }
                try {
                    val code = conn.responseCode
                    if (code in 200..299) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        attachmentCache.put(attachmentId, bytes)
                        bytes
                    } else {
                        throw parseApiError(code, conn.errorStream)
                    }
                } finally {
                    conn.disconnect()
                }
            }
        }

    // 5.6 Attachments: DELETE /api/notes/{id}/attachments/{attachment_id}
    suspend fun deleteAttachment(noteId: String, attachmentId: String): Result<Unit> {
        attachmentCache.remove(attachmentId)
        return requestJson(
            "DELETE",
            "/api/notes/$noteId/attachments/$attachmentId",
            null,
            authRequired = true
        ) { }
    }

    private suspend fun <T> requestJson(
        method: String,
        path: String,
        bodyJson: JSONObject?,
        authRequired: Boolean,
        parser: (String) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (resolveUrl(path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 4500
                readTimeout = 12000
                setRequestProperty("Accept", "application/json")
                if (authRequired) {
                    val token = sessionPrefs.token
                    if (!token.isNullOrBlank()) {
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                }
                if (bodyJson != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
            }
            try {
                if (bodyJson != null) {
                    conn.outputStream.use { os ->
                        os.write(bodyJson.toString().toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    val responseText = if (code == HttpURLConnection.HTTP_NO_CONTENT) {
                        ""
                    } else {
                        readStream(conn.inputStream)
                    }
                    parser(responseText)
                } else {
                    throw parseApiError(code, conn.errorStream)
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun readStream(stream: InputStream?): String {
        if (stream == null) return ""
        val buffer = ByteArrayOutputStream()
        stream.use { input ->
            val data = ByteArray(4096)
            var n: Int
            while (input.read(data).also { n = it } != -1) {
                buffer.write(data, 0, n)
            }
        }
        return buffer.toString("UTF-8")
    }

    private fun repairMojibake(text: String): String {
        if (text.isEmpty() || (!text.contains('\u00c3') && !text.contains('\u00c2'))) {
            return text
        }
        return text
            .replace("\u00c3\u00b1", "\u00f1") // \u00c3\u00b1 -> \u00f1
            .replace("\u00c3\u0091", "\u00d1") // \u00c3\u0091 -> \u00d1
            .replace("\u00c3\u2018", "\u00d1") // \u00c3\u2018 -> \u00d1
            .replace("\u00c3\u00a1", "\u00e1") // \u00c3\u00a1 -> \u00e1
            .replace("\u00c3\u00a9", "\u00e9") // \u00c3\u00a9 -> \u00e9
            .replace("\u00c3\u00ad", "\u00ed") // \u00c3\u00ad -> \u00ed
            .replace("\u00c3\u00b3", "\u00f3") // \u00c3\u00b3 -> \u00f3
            .replace("\u00c3\u00ba", "\u00fa") // \u00c3\u00ba -> \u00fa
            .replace("\u00c3\u00bc", "\u00fc") // \u00c3\u00bc -> \u00fc
            .replace("\u00c3\u0081", "\u00c1") // \u00c3\u0081 -> \u00c1
            .replace("\u00c3\u0089", "\u00c9") // \u00c3\u0089 -> \u00c9
            .replace("\u00c3\u2030", "\u00c9") // \u00c3\u2030 -> \u00c9
            .replace("\u00c3\u008d", "\u00cd") // \u00c3\u008d -> \u00cd
            .replace("\u00c3\u0093", "\u00d3") // \u00c3\u0093 -> \u00d3
            .replace("\u00c3\u201c", "\u00d3") // \u00c3\u201c -> \u00d3
            .replace("\u00c3\u009a", "\u00da") // \u00c3\u009a -> \u00da
            .replace("\u00c3\u0161", "\u00da") // \u00c3\u0161 -> \u00da
            .replace("\u00c3\u009c", "\u00dc") // \u00c3\u009c -> \u00dc
            .replace("\u00c3\u0153", "\u00dc") // \u00c3\u0153 -> \u00dc
            .replace("\u00c2\u00bf", "\u00bf") // \u00c2\u00bf -> \u00bf
            .replace("\u00c2\u00a1", "\u00a1") // \u00c2\u00a1 -> \u00a1
    }

    private fun parseApiError(code: Int, errorStream: InputStream?): ApiException {
        val raw = readStream(errorStream)
        val message = try {
            val json = JSONObject(raw)
            repairMojibake(json.optString("error")).ifBlank { defaultStatusMessage(code) }
        } catch (_: Exception) {
            repairMojibake(raw).ifBlank { defaultStatusMessage(code) }
        }
        return ApiException(code, message)
    }

    private fun defaultStatusMessage(code: Int): String = when (code) {
        400 -> "Solicitud inv\u00e1lida (400)"
        401 -> "Credenciales inv\u00e1lidas o sesi\u00f3n expirada (401)"
        403 -> "No tienes permiso para realizar esta acci\u00f3n (403)"
        404 -> "Recurso no encontrado (404)"
        409 -> "Conflicto: el recurso ya existe (409)"
        else -> "Error del servidor (HTTP $code)"
    }

    private fun parseAuthResponse(json: JSONObject): AuthResponse {
        val token = json.getString("token")
        val user = parseUser(json.getJSONObject("user"))
        return AuthResponse(token = token, user = user)
    }

    private fun parseUser(json: JSONObject): User = User(
        id = json.optString("id"),
        username = repairMojibake(json.optString("username")),
        email = repairMojibake(json.optString("email")),
        createdAt = json.optString("created_at")
    )

    private fun parseFolder(json: JSONObject): Folder = Folder(
        id = json.optString("id"),
        userId = json.optString("user_id"),
        name = repairMojibake(json.optString("name")),
        createdAt = json.optString("created_at")
    )

    private fun parseNote(json: JSONObject): Note {
        val tagsArr = json.optJSONArray("tags") ?: JSONArray()
        val tags = List(tagsArr.length()) { i -> repairMojibake(tagsArr.optString(i)) }.filter { it.isNotBlank() }
        val folderId = if (json.isNull("folder_id")) null else json.optString("folder_id").ifBlank { null }
        return Note(
            id = json.optString("id"),
            ownerId = json.optString("owner_id"),
            ownerUsername = repairMojibake(json.optString("owner_username")),
            folderId = folderId,
            title = repairMojibake(json.optString("title")).replace("\u200B", "").trim(),
            body = repairMojibake(json.optString("body")),
            tags = tags,
            accessRole = json.optString("access_role", "owner"),
            createdAt = json.optString("created_at"),
            updatedAt = json.optString("updated_at")
        )
    }

    private fun parseShareInfo(json: JSONObject): NoteShareInfo = NoteShareInfo(
        noteId = json.optString("note_id"),
        sharedWithUserId = json.optString("shared_with_user_id"),
        username = repairMojibake(json.optString("username")),
        email = repairMojibake(json.optString("email")),
        permission = json.optString("permission", "read"),
        createdAt = json.optString("created_at")
    )

    private fun parseAttachment(json: JSONObject): AttachmentMetadata = AttachmentMetadata(
        id = json.optString("id"),
        noteId = json.optString("note_id"),
        uploaderId = json.optString("uploader_id"),
        filename = repairMojibake(json.optString("filename")),
        contentType = json.optString("content_type"),
        sizeBytes = json.optInt("size_bytes", 0),
        url = json.optString("url"),
        markdown = repairMojibake(json.optString("markdown")),
        createdAt = json.optString("created_at")
    )
}
