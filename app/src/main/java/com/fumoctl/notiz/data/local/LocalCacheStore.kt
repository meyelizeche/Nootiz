package com.fumoctl.notiz.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.LruCache
import com.fumoctl.notiz.data.model.AttachmentMetadata
import com.fumoctl.notiz.data.model.Folder
import com.fumoctl.notiz.data.model.Note
import com.fumoctl.notiz.data.model.NoteShareInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

enum class SyncOpType {
    CREATE_FOLDER,
    RENAME_FOLDER,
    DELETE_FOLDER,
    CREATE_NOTE,
    UPDATE_NOTE,
    DELETE_NOTE,
    UPLOAD_ATTACHMENT,
    DELETE_ATTACHMENT,
    SHARE_NOTE,
    REVOKE_SHARE
}

data class QueuedSyncOp(
    val opId: Long,
    val scopeKey: String,
    val opType: SyncOpType,
    val entityId: String,
    val parentId: String?,
    val payloadJson: String,
    val retryCount: Int,
    val createdAtMs: Long
)

data class PendingQueueSummary(
    val pendingCount: Int,
    val pendingNoteIds: Set<String>
)

class LocalCacheStore(context: Context) {

    private val appContext = context.applicationContext
    private val dbLock = Any()

    @Volatile
    private var helperInstance: CacheDbHelper? = null

    @Volatile
    private var recoveredInFlightOnce = false

    private val memoryAttachmentCache = object : LruCache<String, ByteArray>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    companion object {
        private const val DB_NAME = "notiz_offline_cache.db"
        private const val DB_VERSION = 1

        const val LOCAL_FOLDER_PREFIX = "local-folder-"
        const val LOCAL_NOTE_PREFIX = "local-note-"
        const val LOCAL_ATTACHMENT_PREFIX = "local-att-"
        const val PENDING_SHARE_PREFIX = "pending:"

        fun buildScopeKey(baseUrl: String, userId: String): String {
            return "${SessionPreferences.normalizeBaseUrl(baseUrl)}|${userId.trim()}"
        }

        fun isLocalId(id: String?): Boolean {
            if (id.isNullOrBlank()) return false
            return id.startsWith(LOCAL_FOLDER_PREFIX) ||
                id.startsWith(LOCAL_NOTE_PREFIX) ||
                id.startsWith(LOCAL_ATTACHMENT_PREFIX)
        }
    }

    private class CacheDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS cached_folders (
                    scope_key TEXT NOT NULL,
                    id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (scope_key, id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS cached_notes (
                    scope_key TEXT NOT NULL,
                    id TEXT NOT NULL,
                    owner_id TEXT NOT NULL,
                    owner_username TEXT NOT NULL,
                    folder_id TEXT,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    tags_json TEXT NOT NULL,
                    access_role TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (scope_key, id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS cached_note_shares (
                    scope_key TEXT NOT NULL,
                    note_id TEXT NOT NULL,
                    shared_with_user_id TEXT NOT NULL,
                    username TEXT NOT NULL,
                    email TEXT NOT NULL,
                    permission TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (scope_key, note_id, shared_with_user_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS cached_attachments (
                    scope_key TEXT NOT NULL,
                    id TEXT NOT NULL,
                    note_id TEXT NOT NULL,
                    uploader_id TEXT NOT NULL,
                    filename TEXT NOT NULL,
                    content_type TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    url TEXT NOT NULL,
                    markdown TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (scope_key, id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sync_queue (
                    op_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    scope_key TEXT NOT NULL,
                    op_type TEXT NOT NULL,
                    entity_id TEXT NOT NULL,
                    parent_id TEXT,
                    payload_json TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    retry_count INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT,
                    created_at_ms INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_sync_queue_scope_status
                ON sync_queue (scope_key, status, op_id)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS id_mappings (
                    scope_key TEXT NOT NULL,
                    local_id TEXT NOT NULL,
                    remote_id TEXT NOT NULL,
                    entity_type TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    PRIMARY KEY (scope_key, local_id)
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            onCreate(db)
        }
    }

    private fun writableDbLocked(): SQLiteDatabase {
        var helper = helperInstance
        if (helper == null) {
            helper = CacheDbHelper(appContext)
            helperInstance = helper
        }
        val db = helper.writableDatabase
        if (!recoveredInFlightOnce) {
            recoveredInFlightOnce = true
            val cv = ContentValues().apply { put("status", "PENDING") }
            db.update("sync_queue", cv, "status = ?", arrayOf("IN_FLIGHT"))
        }
        return db
    }

    private inline fun <T> withTransaction(block: (SQLiteDatabase) -> T): T = synchronized(dbLock) {
        val db = writableDbLocked()
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    // -------------------------------------------------------------------------
    // ID Mapping Resolution
    // -------------------------------------------------------------------------

    fun resolveId(scopeKey: String, rawId: String?): String? {
        if (rawId.isNullOrBlank()) return rawId
        synchronized(dbLock) {
            val db = writableDbLocked()
            return resolveIdLocked(db, scopeKey, rawId)
        }
    }

    private fun resolveIdLocked(db: SQLiteDatabase, scopeKey: String, rawId: String?): String? {
        if (rawId.isNullOrBlank()) return rawId
        var current: String = rawId
        repeat(5) {
            val next = db.rawQuery(
                "SELECT remote_id FROM id_mappings WHERE scope_key = ? AND local_id = ?",
                arrayOf(scopeKey, current)
            ).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
            if (next.isNullOrBlank() || next == current) return current
            current = next
        }
        return current
    }

    fun getAllIdMappings(scopeKey: String): Map<String, String> = synchronized(dbLock) {
        val db = writableDbLocked()
        val result = mutableMapOf<String, String>()
        db.rawQuery(
            "SELECT local_id, remote_id FROM id_mappings WHERE scope_key = ?",
            arrayOf(scopeKey)
        ).use { c ->
            while (c.moveToNext()) {
                result[c.getString(0)] = c.getString(1)
            }
        }
        result
    }

    // -------------------------------------------------------------------------
    // Attachment Binary Disk + Memory Storage
    // -------------------------------------------------------------------------

    private fun attachmentDirForScope(scopeKey: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(scopeKey.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        return File(appContext.filesDir, "attachments_cache/$digest").apply {
            if (!exists()) mkdirs()
        }
    }

    private fun sanitizeFileKey(id: String): String =
        id.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    private fun attachmentFile(scopeKey: String, attachmentId: String): File =
        File(attachmentDirForScope(scopeKey), "${sanitizeFileKey(attachmentId)}.bin")

    private fun memoryKey(scopeKey: String, attachmentId: String): String = "$scopeKey|$attachmentId"

    fun saveAttachmentBytes(scopeKey: String, attachmentId: String, bytes: ByteArray) {
        val resolved = resolveId(scopeKey, attachmentId) ?: attachmentId
        memoryAttachmentCache.put(memoryKey(scopeKey, resolved), bytes)
        runCatching {
            val file = attachmentFile(scopeKey, resolved)
            file.writeBytes(bytes)
        }
    }

    fun readAttachmentBytes(scopeKey: String, attachmentId: String): ByteArray? {
        val resolved = resolveId(scopeKey, attachmentId) ?: attachmentId
        memoryAttachmentCache.get(memoryKey(scopeKey, resolved))?.let { return it }
        if (resolved != attachmentId) {
            memoryAttachmentCache.get(memoryKey(scopeKey, attachmentId))?.let { return it }
        }
        val candidates = listOf(resolved, attachmentId).distinct()
        for (id in candidates) {
            val file = attachmentFile(scopeKey, id)
            if (file.exists() && file.isFile) {
                val bytes = runCatching { file.readBytes() }.getOrNull()
                if (bytes != null) {
                    memoryAttachmentCache.put(memoryKey(scopeKey, resolved), bytes)
                    return bytes
                }
            }
        }
        return null
    }

    fun deleteAttachmentBytes(scopeKey: String, attachmentId: String) {
        val resolved = resolveId(scopeKey, attachmentId) ?: attachmentId
        memoryAttachmentCache.remove(memoryKey(scopeKey, attachmentId))
        memoryAttachmentCache.remove(memoryKey(scopeKey, resolved))
        runCatching { attachmentFile(scopeKey, attachmentId).delete() }
        runCatching { attachmentFile(scopeKey, resolved).delete() }
    }

    private fun moveAttachmentBytesLocked(scopeKey: String, oldId: String, newId: String) {
        val existingMem = memoryAttachmentCache.remove(memoryKey(scopeKey, oldId))
        if (existingMem != null) {
            memoryAttachmentCache.put(memoryKey(scopeKey, newId), existingMem)
        }
        val oldFile = attachmentFile(scopeKey, oldId)
        val newFile = attachmentFile(scopeKey, newId)
        if (oldFile.exists()) {
            runCatching {
                if (newFile.exists()) newFile.delete()
                if (!oldFile.renameTo(newFile)) {
                    oldFile.copyTo(newFile, overwrite = true)
                    oldFile.delete()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cached Reads
    // -------------------------------------------------------------------------

    fun getFolders(scopeKey: String): List<Folder> = synchronized(dbLock) {
        val db = writableDbLocked()
        val list = mutableListOf<Folder>()
        db.rawQuery(
            "SELECT id, user_id, name, created_at FROM cached_folders WHERE scope_key = ? ORDER BY LOWER(name) ASC",
            arrayOf(scopeKey)
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Folder(
                        id = c.getString(0),
                        userId = c.getString(1),
                        name = c.getString(2),
                        createdAt = c.getString(3)
                    )
                )
            }
        }
        list
    }

    fun getNotes(scopeKey: String): List<Note> = synchronized(dbLock) {
        val db = writableDbLocked()
        val list = mutableListOf<Note>()
        db.rawQuery(
            """
            SELECT id, owner_id, owner_username, folder_id, title, body, tags_json, access_role, created_at, updated_at
            FROM cached_notes
            WHERE scope_key = ?
            ORDER BY updated_at DESC, created_at DESC
            """.trimIndent(),
            arrayOf(scopeKey)
        ).use { c ->
            while (c.moveToNext()) {
                list.add(readNoteCursor(c))
            }
        }
        list
    }

    fun getNote(scopeKey: String, noteId: String): Note? = synchronized(dbLock) {
        val db = writableDbLocked()
        val resolved = resolveIdLocked(db, scopeKey, noteId) ?: noteId
        db.rawQuery(
            """
            SELECT id, owner_id, owner_username, folder_id, title, body, tags_json, access_role, created_at, updated_at
            FROM cached_notes
            WHERE scope_key = ? AND id = ?
            """.trimIndent(),
            arrayOf(scopeKey, resolved)
        ).use { c ->
            if (c.moveToFirst()) readNoteCursor(c) else null
        }
    }

    fun hasPendingChangesForNote(scopeKey: String, noteId: String): Boolean = synchronized(dbLock) {
        val db = writableDbLocked()
        val resolved = resolveIdLocked(db, scopeKey, noteId) ?: noteId
        db.rawQuery(
            """
            SELECT 1 FROM sync_queue
            WHERE scope_key = ? AND (entity_id = ? OR parent_id = ?)
            LIMIT 1
            """.trimIndent(),
            arrayOf(scopeKey, resolved, resolved)
        ).use { c -> c.moveToFirst() }
    }

    fun getNoteShares(scopeKey: String, noteId: String): List<NoteShareInfo> = synchronized(dbLock) {
        val db = writableDbLocked()
        val resolved = resolveIdLocked(db, scopeKey, noteId) ?: noteId
        val list = mutableListOf<NoteShareInfo>()
        db.rawQuery(
            """
            SELECT note_id, shared_with_user_id, username, email, permission, created_at
            FROM cached_note_shares
            WHERE scope_key = ? AND note_id = ?
            ORDER BY created_at ASC
            """.trimIndent(),
            arrayOf(scopeKey, resolved)
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    NoteShareInfo(
                        noteId = c.getString(0),
                        sharedWithUserId = c.getString(1),
                        username = c.getString(2),
                        email = c.getString(3),
                        permission = c.getString(4),
                        createdAt = c.getString(5)
                    )
                )
            }
        }
        list
    }

    fun getAllOutgoingSharesByNote(scopeKey: String): Map<String, List<NoteShareInfo>> = synchronized(dbLock) {
        val db = writableDbLocked()
        val map = mutableMapOf<String, MutableList<NoteShareInfo>>()
        db.rawQuery(
            """
            SELECT note_id, shared_with_user_id, username, email, permission, created_at
            FROM cached_note_shares
            WHERE scope_key = ?
            ORDER BY created_at ASC
            """.trimIndent(),
            arrayOf(scopeKey)
        ).use { c ->
            while (c.moveToNext()) {
                val info = NoteShareInfo(
                    noteId = c.getString(0),
                    sharedWithUserId = c.getString(1),
                    username = c.getString(2),
                    email = c.getString(3),
                    permission = c.getString(4),
                    createdAt = c.getString(5)
                )
                map.getOrPut(info.noteId) { mutableListOf() }.add(info)
            }
        }
        map
    }

    fun getAttachments(scopeKey: String, noteId: String): List<AttachmentMetadata> = synchronized(dbLock) {
        val db = writableDbLocked()
        val resolved = resolveIdLocked(db, scopeKey, noteId) ?: noteId
        val list = mutableListOf<AttachmentMetadata>()
        db.rawQuery(
            """
            SELECT id, note_id, uploader_id, filename, content_type, size_bytes, url, markdown, created_at
            FROM cached_attachments
            WHERE scope_key = ? AND note_id = ?
            ORDER BY created_at ASC
            """.trimIndent(),
            arrayOf(scopeKey, resolved)
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    AttachmentMetadata(
                        id = c.getString(0),
                        noteId = c.getString(1),
                        uploaderId = c.getString(2),
                        filename = c.getString(3),
                        contentType = c.getString(4),
                        sizeBytes = c.getInt(5),
                        url = c.getString(6),
                        markdown = c.getString(7),
                        createdAt = c.getString(8)
                    )
                )
            }
        }
        list
    }

    fun getPendingSummary(scopeKey: String): PendingQueueSummary = synchronized(dbLock) {
        val db = writableDbLocked()
        var count = 0
        val noteIds = mutableSetOf<String>()
        db.rawQuery(
            "SELECT op_type, entity_id, parent_id FROM sync_queue WHERE scope_key = ?",
            arrayOf(scopeKey)
        ).use { c ->
            while (c.moveToNext()) {
                count++
                val type = runCatching { SyncOpType.valueOf(c.getString(0)) }.getOrNull()
                val entityId = c.getString(1)
                val parentId = if (c.isNull(2)) null else c.getString(2)
                when (type) {
                    SyncOpType.CREATE_NOTE, SyncOpType.UPDATE_NOTE -> noteIds.add(entityId)
                    SyncOpType.UPLOAD_ATTACHMENT,
                    SyncOpType.DELETE_ATTACHMENT,
                    SyncOpType.SHARE_NOTE,
                    SyncOpType.REVOKE_SHARE -> if (!parentId.isNullOrBlank()) noteIds.add(parentId)
                    else -> {}
                }
            }
        }
        PendingQueueSummary(pendingCount = count, pendingNoteIds = noteIds)
    }

    // -------------------------------------------------------------------------
    // Optimistic Local Mutations + Smart Sync Queue Coalescing
    // -------------------------------------------------------------------------

    fun createFolderOfflineFirst(scopeKey: String, userId: String, name: String): Result<Folder> = runCatching {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "El nombre de la carpeta no puede estar vacío." }
        require(cleanName.length <= 120) { "El nombre de la carpeta no puede superar 120 caracteres." }

        withTransaction { db ->
            val duplicate = db.rawQuery(
                "SELECT 1 FROM cached_folders WHERE scope_key = ? AND LOWER(name) = LOWER(?) LIMIT 1",
                arrayOf(scopeKey, cleanName)
            ).use { it.moveToFirst() }
            if (duplicate) {
                throw IllegalStateException("Ya existe una carpeta con el nombre \"$cleanName\".")
            }

            val localId = "$LOCAL_FOLDER_PREFIX${UUID.randomUUID()}"
            val nowIso = Instant.now().toString()
            val folder = Folder(
                id = localId,
                userId = userId,
                name = cleanName,
                createdAt = nowIso
            )
            upsertFolderLocked(db, scopeKey, folder)
            insertQueueOpLocked(
                db = db,
                scopeKey = scopeKey,
                opType = SyncOpType.CREATE_FOLDER,
                entityId = localId,
                parentId = null,
                payload = JSONObject().apply { put("name", cleanName) }
            )
            folder
        }
    }

    fun renameFolderOfflineFirst(scopeKey: String, folderId: String, newName: String): Result<Folder> = runCatching {
        val cleanName = newName.trim()
        require(cleanName.isNotEmpty()) { "El nombre de la carpeta no puede estar vacío." }
        require(cleanName.length <= 120) { "El nombre de la carpeta no puede superar 120 caracteres." }

        withTransaction { db ->
            val resolvedId = resolveIdLocked(db, scopeKey, folderId) ?: folderId
            val duplicate = db.rawQuery(
                "SELECT 1 FROM cached_folders WHERE scope_key = ? AND LOWER(name) = LOWER(?) AND id != ? LIMIT 1",
                arrayOf(scopeKey, cleanName, resolvedId)
            ).use { it.moveToFirst() }
            if (duplicate) {
                throw IllegalStateException("Ya existe una carpeta con el nombre \"$cleanName\".")
            }

            val existing = db.rawQuery(
                "SELECT id, user_id, name, created_at FROM cached_folders WHERE scope_key = ? AND id = ?",
                arrayOf(scopeKey, resolvedId)
            ).use { c ->
                if (c.moveToFirst()) {
                    Folder(c.getString(0), c.getString(1), c.getString(2), c.getString(3))
                } else {
                    null
                }
            } ?: throw IllegalStateException("Carpeta no encontrada.")

            val updated = existing.copy(name = cleanName)
            upsertFolderLocked(db, scopeKey, updated)

            // Coalesce into PENDING CREATE_FOLDER or PENDING RENAME_FOLDER if present
            val pendingCreateId = findPendingOpIdLocked(db, scopeKey, SyncOpType.CREATE_FOLDER, resolvedId)
            if (pendingCreateId != null) {
                updateQueuePayloadLocked(
                    db,
                    pendingCreateId,
                    JSONObject().apply { put("name", cleanName) }
                )
            } else {
                val pendingRenameId = findPendingOpIdLocked(db, scopeKey, SyncOpType.RENAME_FOLDER, resolvedId)
                if (pendingRenameId != null) {
                    updateQueuePayloadLocked(
                        db,
                        pendingRenameId,
                        JSONObject().apply { put("name", cleanName) }
                    )
                } else {
                    insertQueueOpLocked(
                        db = db,
                        scopeKey = scopeKey,
                        opType = SyncOpType.RENAME_FOLDER,
                        entityId = resolvedId,
                        parentId = null,
                        payload = JSONObject().apply { put("name", cleanName) }
                    )
                }
            }
            updated
        }
    }

    fun deleteFolderOfflineFirst(scopeKey: String, folderId: String): Result<Unit> = runCatching {
        withTransaction { db ->
            val resolvedId = resolveIdLocked(db, scopeKey, folderId) ?: folderId
            db.delete(
                "cached_folders",
                "scope_key = ? AND id = ?",
                arrayOf(scopeKey, resolvedId)
            )
            // Server ON DELETE SET NULL: detach notes locally
            val detachCv = ContentValues().apply { putNull("folder_id") }
            db.update(
                "cached_notes",
                detachCv,
                "scope_key = ? AND folder_id = ?",
                arrayOf(scopeKey, resolvedId)
            )

            // Detach folder_id in any PENDING CREATE_NOTE / UPDATE_NOTE referencing this folder
            clearFolderInPendingNoteOpsLocked(db, scopeKey, resolvedId)

            val pendingCreateId = findPendingOpIdLocked(db, scopeKey, SyncOpType.CREATE_FOLDER, resolvedId)
            db.delete(
                "sync_queue",
                "scope_key = ? AND status = 'PENDING' AND entity_id = ? AND op_type IN (?, ?)",
                arrayOf(scopeKey, resolvedId, SyncOpType.CREATE_FOLDER.name, SyncOpType.RENAME_FOLDER.name)
            )
            if (pendingCreateId == null) {
                insertQueueOpLocked(
                    db = db,
                    scopeKey = scopeKey,
                    opType = SyncOpType.DELETE_FOLDER,
                    entityId = resolvedId,
                    parentId = null,
                    payload = JSONObject()
                )
            }
        }
    }

    fun createNoteOfflineFirst(
        scopeKey: String,
        ownerId: String,
        ownerUsername: String,
        title: String,
        body: String,
        tags: List<String>,
        folderId: String?
    ): Note = withTransaction { db ->
        val resolvedFolderId = resolveIdLocked(db, scopeKey, folderId)
        val cleanTags = normalizeTags(tags)
        val cleanTitle = title.trim()
        val nowIso = Instant.now().toString()
        val localNoteId = "$LOCAL_NOTE_PREFIX${UUID.randomUUID()}"

        val note = Note(
            id = localNoteId,
            ownerId = ownerId,
            ownerUsername = ownerUsername,
            folderId = resolvedFolderId,
            title = cleanTitle,
            body = body,
            tags = cleanTags,
            accessRole = "owner",
            createdAt = nowIso,
            updatedAt = nowIso
        )
        upsertNoteLocked(db, scopeKey, note)

        val payload = JSONObject().apply {
            put("title", cleanTitle)
            put("body", body)
            put("tags", JSONArray(cleanTags))
            if (resolvedFolderId == null) {
                put("folder_id", JSONObject.NULL)
            } else {
                put("folder_id", resolvedFolderId)
            }
        }
        insertQueueOpLocked(
            db = db,
            scopeKey = scopeKey,
            opType = SyncOpType.CREATE_NOTE,
            entityId = localNoteId,
            parentId = null,
            payload = payload
        )
        note
    }

    fun updateNoteOfflineFirst(
        scopeKey: String,
        noteId: String,
        title: String?,
        body: String?,
        tags: List<String>?,
        updateFolder: Boolean,
        folderId: String?
    ): Result<Note> = runCatching {
        withTransaction { db ->
            val resolvedNoteId = resolveIdLocked(db, scopeKey, noteId) ?: noteId
            val resolvedFolderId = resolveIdLocked(db, scopeKey, folderId)
            val existing = db.rawQuery(
                """
                SELECT id, owner_id, owner_username, folder_id, title, body, tags_json, access_role, created_at, updated_at
                FROM cached_notes
                WHERE scope_key = ? AND id = ?
                """.trimIndent(),
                arrayOf(scopeKey, resolvedNoteId)
            ).use { c ->
                if (c.moveToFirst()) readNoteCursor(c) else null
            } ?: throw IllegalStateException("Nota no encontrada en la caché local.")

            val updatedTitle = title?.trim() ?: existing.title
            val updatedBody = body ?: existing.body
            val updatedTags = tags?.let { normalizeTags(it) } ?: existing.tags
            val updatedFolderId = if (updateFolder && existing.isOwner) resolvedFolderId else existing.folderId
            val nowIso = Instant.now().toString()

            val updatedNote = existing.copy(
                title = updatedTitle,
                body = updatedBody,
                tags = updatedTags,
                folderId = updatedFolderId,
                updatedAt = nowIso
            )
            upsertNoteLocked(db, scopeKey, updatedNote)

            val pendingCreate = findPendingOpLocked(db, scopeKey, SyncOpType.CREATE_NOTE, resolvedNoteId)
            if (pendingCreate != null) {
                val createPayload = JSONObject(pendingCreate.second)
                if (title != null) createPayload.put("title", updatedTitle)
                if (body != null) createPayload.put("body", updatedBody)
                if (tags != null) createPayload.put("tags", JSONArray(updatedTags))

                val folderCreateOp = if (updateFolder && existing.isOwner && !updatedFolderId.isNullOrBlank()) {
                    findPendingOpIdLocked(db, scopeKey, SyncOpType.CREATE_FOLDER, updatedFolderId)
                } else {
                    null
                }

                if (updateFolder && existing.isOwner) {
                    if (folderCreateOp != null && folderCreateOp > pendingCreate.first) {
                        // Keep CREATE_NOTE folder null initially and append/merge UPDATE_NOTE after CREATE_FOLDER
                        updateQueuePayloadLocked(db, pendingCreate.first, createPayload)
                        upsertPendingNoteUpdateOpLocked(
                            db = db,
                            scopeKey = scopeKey,
                            noteId = resolvedNoteId,
                            title = null,
                            body = null,
                            tags = null,
                            updateFolder = true,
                            folderId = updatedFolderId
                        )
                    } else {
                        if (updatedFolderId == null) {
                            createPayload.put("folder_id", JSONObject.NULL)
                        } else {
                            createPayload.put("folder_id", updatedFolderId)
                        }
                        updateQueuePayloadLocked(db, pendingCreate.first, createPayload)
                    }
                } else {
                    updateQueuePayloadLocked(db, pendingCreate.first, createPayload)
                }
            } else {
                upsertPendingNoteUpdateOpLocked(
                    db = db,
                    scopeKey = scopeKey,
                    noteId = resolvedNoteId,
                    title = if (title != null) updatedTitle else null,
                    body = if (body != null) updatedBody else null,
                    tags = if (tags != null) updatedTags else null,
                    updateFolder = updateFolder && existing.isOwner,
                    folderId = updatedFolderId
                )
            }

            updatedNote
        }
    }

    fun deleteNoteOfflineFirst(scopeKey: String, noteId: String) {
        val attachmentsToDelete = mutableListOf<String>()
        withTransaction { db ->
            val resolvedNoteId = resolveIdLocked(db, scopeKey, noteId) ?: noteId
            db.rawQuery(
                "SELECT id FROM cached_attachments WHERE scope_key = ? AND note_id = ?",
                arrayOf(scopeKey, resolvedNoteId)
            ).use { c ->
                while (c.moveToNext()) attachmentsToDelete.add(c.getString(0))
            }

            db.delete("cached_notes", "scope_key = ? AND id = ?", arrayOf(scopeKey, resolvedNoteId))
            db.delete("cached_note_shares", "scope_key = ? AND note_id = ?", arrayOf(scopeKey, resolvedNoteId))
            db.delete("cached_attachments", "scope_key = ? AND note_id = ?", arrayOf(scopeKey, resolvedNoteId))

            val pendingCreateId = findPendingOpIdLocked(db, scopeKey, SyncOpType.CREATE_NOTE, resolvedNoteId)

            // Remove all pending child & update operations for this note
            db.delete(
                "sync_queue",
                "scope_key = ? AND status = 'PENDING' AND (entity_id = ? OR parent_id = ?)",
                arrayOf(scopeKey, resolvedNoteId, resolvedNoteId)
            )

            if (pendingCreateId == null && !resolvedNoteId.startsWith(LOCAL_NOTE_PREFIX)) {
                insertQueueOpLocked(
                    db = db,
                    scopeKey = scopeKey,
                    opType = SyncOpType.DELETE_NOTE,
                    entityId = resolvedNoteId,
                    parentId = null,
                    payload = JSONObject()
                )
            }
        }
        attachmentsToDelete.forEach { attId -> deleteAttachmentBytes(scopeKey, attId) }
    }

    fun uploadAttachmentOfflineFirst(
        scopeKey: String,
        noteId: String,
        uploaderId: String,
        filename: String,
        contentType: String,
        bytes: ByteArray
    ): AttachmentMetadata {
        val localAttId = "$LOCAL_ATTACHMENT_PREFIX${UUID.randomUUID()}"
        saveAttachmentBytes(scopeKey, localAttId, bytes)
        return withTransaction { db ->
            val resolvedNoteId = resolveIdLocked(db, scopeKey, noteId) ?: noteId
            val nowIso = Instant.now().toString()
            val url = "/api/notes/$resolvedNoteId/attachments/$localAttId"
            val meta = AttachmentMetadata(
                id = localAttId,
                noteId = resolvedNoteId,
                uploaderId = uploaderId,
                filename = filename,
                contentType = contentType,
                sizeBytes = bytes.size,
                url = url,
                markdown = "![$filename]($url)",
                createdAt = nowIso
            )
            upsertAttachmentLocked(db, scopeKey, meta)
            insertQueueOpLocked(
                db = db,
                scopeKey = scopeKey,
                opType = SyncOpType.UPLOAD_ATTACHMENT,
                entityId = localAttId,
                parentId = resolvedNoteId,
                payload = JSONObject().apply {
                    put("filename", filename)
                    put("content_type", contentType)
                }
            )
            meta
        }
    }

    fun deleteAttachmentOfflineFirst(scopeKey: String, noteId: String, attachmentId: String) {
        withTransaction { db ->
            val resolvedNoteId = resolveIdLocked(db, scopeKey, noteId) ?: noteId
            val resolvedAttId = resolveIdLocked(db, scopeKey, attachmentId) ?: attachmentId
            db.delete(
                "cached_attachments",
                "scope_key = ? AND id = ?",
                arrayOf(scopeKey, resolvedAttId)
            )
            val pendingUploadId = findPendingOpIdLocked(db, scopeKey, SyncOpType.UPLOAD_ATTACHMENT, resolvedAttId)
            if (pendingUploadId != null) {
                db.delete("sync_queue", "op_id = ?", arrayOf(pendingUploadId.toString()))
            } else if (!resolvedAttId.startsWith(LOCAL_ATTACHMENT_PREFIX)) {
                insertQueueOpLocked(
                    db = db,
                    scopeKey = scopeKey,
                    opType = SyncOpType.DELETE_ATTACHMENT,
                    entityId = resolvedAttId,
                    parentId = resolvedNoteId,
                    payload = JSONObject()
                )
            }
        }
        deleteAttachmentBytes(scopeKey, attachmentId)
    }

    fun queueShareNoteOffline(
        scopeKey: String,
        noteId: String,
        recipient: String,
        permission: String
    ): NoteShareInfo = withTransaction { db ->
        val resolvedNoteId = resolveIdLocked(db, scopeKey, noteId) ?: noteId
        val cleanRecipient = recipient.trim()
        val recipientKey = cleanRecipient.lowercase()
        val pendingUserId = "$PENDING_SHARE_PREFIX$recipientKey"
        val nowIso = Instant.now().toString()

        // Check if recipient matches an already-cached user share on this note (e.g. changing role offline)
        val existingShare = db.rawQuery(
            """
            SELECT shared_with_user_id, username, email, created_at
            FROM cached_note_shares
            WHERE scope_key = ? AND note_id = ? AND (LOWER(username) = ? OR LOWER(email) = ? OR shared_with_user_id = ?)
            LIMIT 1
            """.trimIndent(),
            arrayOf(scopeKey, resolvedNoteId, recipientKey, recipientKey, pendingUserId)
        ).use { c ->
            if (c.moveToFirst()) {
                NoteShareInfo(
                    noteId = resolvedNoteId,
                    sharedWithUserId = c.getString(0),
                    username = c.getString(1),
                    email = c.getString(2),
                    permission = permission,
                    createdAt = c.getString(3)
                )
            } else {
                null
            }
        }

        val shareInfo = existingShare ?: NoteShareInfo(
            noteId = resolvedNoteId,
            sharedWithUserId = pendingUserId,
            username = cleanRecipient,
            email = if (cleanRecipient.contains("@")) cleanRecipient else "Pendiente de sincronizar",
            permission = permission,
            createdAt = nowIso
        )
        upsertShareLocked(db, scopeKey, shareInfo)

        val existingPendingId = db.rawQuery(
            """
            SELECT op_id FROM sync_queue
            WHERE scope_key = ? AND status = 'PENDING' AND op_type = ? AND parent_id = ? AND entity_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(scopeKey, SyncOpType.SHARE_NOTE.name, resolvedNoteId, recipientKey)
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

        val payload = JSONObject().apply {
            put("recipient", cleanRecipient)
            put("permission", permission)
        }
        if (existingPendingId != null) {
            updateQueuePayloadLocked(db, existingPendingId, payload)
        } else {
            insertQueueOpLocked(
                db = db,
                scopeKey = scopeKey,
                opType = SyncOpType.SHARE_NOTE,
                entityId = recipientKey,
                parentId = resolvedNoteId,
                payload = payload
            )
        }
        shareInfo
    }

    fun cacheImmediateShareResult(scopeKey: String, shareInfo: NoteShareInfo) = withTransaction { db ->
        val resolvedNoteId = resolveIdLocked(db, scopeKey, shareInfo.noteId) ?: shareInfo.noteId
        // Remove any pending placeholder for this username/email
        db.delete(
            "cached_note_shares",
            "scope_key = ? AND note_id = ? AND (shared_with_user_id = ? OR shared_with_user_id = ?)",
            arrayOf(
                scopeKey,
                resolvedNoteId,
                "$PENDING_SHARE_PREFIX${shareInfo.username.lowercase()}",
                "$PENDING_SHARE_PREFIX${shareInfo.email.lowercase()}"
            )
        )
        upsertShareLocked(db, scopeKey, shareInfo.copy(noteId = resolvedNoteId))
    }

    fun revokeShareOfflineFirst(
        scopeKey: String,
        noteId: String,
        targetUserId: String,
        currentUserId: String?
    ) = withTransaction { db ->
        val resolvedNoteId = resolveIdLocked(db, scopeKey, noteId) ?: noteId
        db.delete(
            "cached_note_shares",
            "scope_key = ? AND note_id = ? AND shared_with_user_id = ?",
            arrayOf(scopeKey, resolvedNoteId, targetUserId)
        )
        if (!currentUserId.isNullOrBlank() && targetUserId == currentUserId) {
            // Leaving a shared note removes it from our local notes list immediately
            db.delete("cached_notes", "scope_key = ? AND id = ?", arrayOf(scopeKey, resolvedNoteId))
        }

        if (targetUserId.startsWith(PENDING_SHARE_PREFIX)) {
            val recipientKey = targetUserId.removePrefix(PENDING_SHARE_PREFIX)
            db.delete(
                "sync_queue",
                "scope_key = ? AND status = 'PENDING' AND op_type = ? AND parent_id = ? AND entity_id = ?",
                arrayOf(scopeKey, SyncOpType.SHARE_NOTE.name, resolvedNoteId, recipientKey)
            )
        } else {
            insertQueueOpLocked(
                db = db,
                scopeKey = scopeKey,
                opType = SyncOpType.REVOKE_SHARE,
                entityId = targetUserId,
                parentId = resolvedNoteId,
                payload = JSONObject()
            )
        }
    }

    // -------------------------------------------------------------------------
    // Sync Engine Queue Processing & ID Remapping
    // -------------------------------------------------------------------------

    fun acquireNextReadyOp(scopeKey: String): QueuedSyncOp? = withTransaction { db ->
        val pendingOps = mutableListOf<QueuedSyncOp>()
        db.rawQuery(
            """
            SELECT op_id, scope_key, op_type, entity_id, parent_id, payload_json, retry_count, created_at_ms
            FROM sync_queue
            WHERE scope_key = ? AND status = 'PENDING'
            ORDER BY op_id ASC
            """.trimIndent(),
            arrayOf(scopeKey)
        ).use { c ->
            while (c.moveToNext()) {
                val type = runCatching { SyncOpType.valueOf(c.getString(2)) }.getOrNull() ?: continue
                pendingOps.add(
                    QueuedSyncOp(
                        opId = c.getLong(0),
                        scopeKey = c.getString(1),
                        opType = type,
                        entityId = c.getString(3),
                        parentId = if (c.isNull(4)) null else c.getString(4),
                        payloadJson = c.getString(5),
                        retryCount = c.getInt(6),
                        createdAtMs = c.getLong(7)
                    )
                )
            }
        }
        if (pendingOps.isEmpty()) return@withTransaction null

        val head = pendingOps.first()
        var chosen = head

        // Ensure causal dependencies (e.g., CREATE_FOLDER before CREATE_NOTE/UPDATE_NOTE referencing it)
        if (head.opType == SyncOpType.CREATE_NOTE || head.opType == SyncOpType.UPDATE_NOTE) {
            val payload = runCatching { JSONObject(head.payloadJson) }.getOrNull()
            val rawFolderId = if (payload != null && !payload.isNull("folder_id")) {
                payload.optString("folder_id").ifBlank { null }
            } else {
                null
            }
            val resolvedFolder = resolveIdLocked(db, scopeKey, rawFolderId)
            if (resolvedFolder != null && resolvedFolder.startsWith(LOCAL_FOLDER_PREFIX)) {
                val folderCreate = pendingOps.firstOrNull {
                    it.opType == SyncOpType.CREATE_FOLDER && it.entityId == resolvedFolder
                }
                if (folderCreate != null) {
                    chosen = folderCreate
                }
            }
        } else if (head.parentId != null && head.parentId.startsWith(LOCAL_NOTE_PREFIX)) {
            val resolvedNote = resolveIdLocked(db, scopeKey, head.parentId)
            if (resolvedNote != null && resolvedNote.startsWith(LOCAL_NOTE_PREFIX)) {
                val noteCreate = pendingOps.firstOrNull {
                    it.opType == SyncOpType.CREATE_NOTE && it.entityId == resolvedNote
                }
                if (noteCreate != null) {
                    chosen = noteCreate
                }
            }
        }

        val cv = ContentValues().apply { put("status", "IN_FLIGHT") }
        db.update("sync_queue", cv, "op_id = ?", arrayOf(chosen.opId.toString()))
        chosen
    }

    fun completeOp(opId: Long) = withTransaction { db ->
        db.delete("sync_queue", "op_id = ?", arrayOf(opId.toString()))
    }

    fun releaseOpForRetry(opId: Long, errorMessage: String?) = withTransaction { db ->
        db.execSQL(
            "UPDATE sync_queue SET status = 'PENDING', retry_count = retry_count + 1, last_error = ? WHERE op_id = ?",
            arrayOf<Any?>(errorMessage, opId)
        )
    }

    fun completeCreateFolderOp(
        scopeKey: String,
        opId: Long,
        localFolderId: String,
        serverFolder: Folder
    ) = withTransaction { db ->
        recordIdMappingLocked(db, scopeKey, localFolderId, serverFolder.id, "folder")

        // Check if user queued a RENAME_FOLDER or DELETE_FOLDER while CREATE_FOLDER was in flight
        val pendingDelete = findPendingOpIdLocked(db, scopeKey, SyncOpType.DELETE_FOLDER, localFolderId)
        val pendingRename = findPendingOpLocked(db, scopeKey, SyncOpType.RENAME_FOLDER, localFolderId)

        db.delete("cached_folders", "scope_key = ? AND id = ?", arrayOf(scopeKey, localFolderId))
        if (pendingDelete == null) {
            val effectiveFolder = if (pendingRename != null) {
                val newName = runCatching { JSONObject(pendingRename.second).optString("name") }
                    .getOrNull()?.ifBlank { null } ?: serverFolder.name
                serverFolder.copy(name = newName)
            } else {
                serverFolder
            }
            upsertFolderLocked(db, scopeKey, effectiveFolder)
        }

        // Update cached_notes folder_id from localFolderId -> serverFolder.id
        val noteCv = ContentValues().apply { put("folder_id", serverFolder.id) }
        db.update(
            "cached_notes",
            noteCv,
            "scope_key = ? AND folder_id = ?",
            arrayOf(scopeKey, localFolderId)
        )

        // Update remaining queue operations referencing localFolderId
        val folderEntityCv = ContentValues().apply { put("entity_id", serverFolder.id) }
        db.update(
            "sync_queue",
            folderEntityCv,
            "scope_key = ? AND entity_id = ? AND op_id != ?",
            arrayOf(scopeKey, localFolderId, opId.toString())
        )

        // Rewrite folder_id inside CREATE_NOTE / UPDATE_NOTE JSON payloads
        db.rawQuery(
            """
            SELECT op_id, payload_json FROM sync_queue
            WHERE scope_key = ? AND op_type IN (?, ?) AND op_id != ?
            """.trimIndent(),
            arrayOf(scopeKey, SyncOpType.CREATE_NOTE.name, SyncOpType.UPDATE_NOTE.name, opId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val qId = c.getLong(0)
                val obj = runCatching { JSONObject(c.getString(1)) }.getOrNull() ?: continue
                if (!obj.isNull("folder_id") && obj.optString("folder_id") == localFolderId) {
                    obj.put("folder_id", serverFolder.id)
                    updateQueuePayloadLocked(db, qId, obj)
                }
            }
        }

        db.delete("sync_queue", "op_id = ?", arrayOf(opId.toString()))
    }

    fun completeCreateNoteOp(
        scopeKey: String,
        opId: Long,
        localNoteId: String,
        serverNote: Note
    ) = withTransaction { db ->
        recordIdMappingLocked(db, scopeKey, localNoteId, serverNote.id, "note")

        val pendingDelete = findPendingOpIdLocked(db, scopeKey, SyncOpType.DELETE_NOTE, localNoteId)
        val pendingUpdate = findPendingOpLocked(db, scopeKey, SyncOpType.UPDATE_NOTE, localNoteId)
        val existingLocal = db.rawQuery(
            """
            SELECT id, owner_id, owner_username, folder_id, title, body, tags_json, access_role, created_at, updated_at
            FROM cached_notes WHERE scope_key = ? AND id = ?
            """.trimIndent(),
            arrayOf(scopeKey, localNoteId)
        ).use { c -> if (c.moveToFirst()) readNoteCursor(c) else null }

        db.delete("cached_notes", "scope_key = ? AND id = ?", arrayOf(scopeKey, localNoteId))

        if (pendingDelete == null) {
            val mergedNote = if (pendingUpdate != null && existingLocal != null) {
                // Preserve edits typed while CREATE_NOTE was in flight
                serverNote.copy(
                    title = existingLocal.title,
                    body = existingLocal.body,
                    tags = existingLocal.tags,
                    folderId = existingLocal.folderId,
                    updatedAt = existingLocal.updatedAt
                )
            } else {
                serverNote
            }
            upsertNoteLocked(db, scopeKey, mergedNote)
        }

        // Remap note_id in cached_attachments and cached_note_shares
        val attCv = ContentValues().apply { put("note_id", serverNote.id) }
        db.update(
            "cached_attachments",
            attCv,
            "scope_key = ? AND note_id = ?",
            arrayOf(scopeKey, localNoteId)
        )
        val shareCv = ContentValues().apply { put("note_id", serverNote.id) }
        db.update(
            "cached_note_shares",
            shareCv,
            "scope_key = ? AND note_id = ?",
            arrayOf(scopeKey, localNoteId)
        )

        // Remap entity_id and parent_id in remaining queue ops
        val entityCv = ContentValues().apply { put("entity_id", serverNote.id) }
        db.update(
            "sync_queue",
            entityCv,
            "scope_key = ? AND entity_id = ? AND op_id != ?",
            arrayOf(scopeKey, localNoteId, opId.toString())
        )
        val parentCv = ContentValues().apply { put("parent_id", serverNote.id) }
        db.update(
            "sync_queue",
            parentCv,
            "scope_key = ? AND parent_id = ? AND op_id != ?",
            arrayOf(scopeKey, localNoteId, opId.toString())
        )

        db.delete("sync_queue", "op_id = ?", arrayOf(opId.toString()))
    }

    fun completeUpdateNoteOp(scopeKey: String, opId: Long, serverNote: Note) = withTransaction { db ->
        // Only overwrite cached note with serverNote if no newer PENDING UPDATE_NOTE arrived during flight
        val newerPendingUpdate = findPendingOpIdLocked(db, scopeKey, SyncOpType.UPDATE_NOTE, serverNote.id)
        val pendingDelete = findPendingOpIdLocked(db, scopeKey, SyncOpType.DELETE_NOTE, serverNote.id)
        if (newerPendingUpdate == null && pendingDelete == null) {
            upsertNoteLocked(db, scopeKey, serverNote)
        }
        db.delete("sync_queue", "op_id = ?", arrayOf(opId.toString()))
    }

    fun completeRenameFolderOp(scopeKey: String, opId: Long, serverFolder: Folder) = withTransaction { db ->
        val newerPendingRename = findPendingOpIdLocked(db, scopeKey, SyncOpType.RENAME_FOLDER, serverFolder.id)
        val pendingDelete = findPendingOpIdLocked(db, scopeKey, SyncOpType.DELETE_FOLDER, serverFolder.id)
        if (newerPendingRename == null && pendingDelete == null) {
            upsertFolderLocked(db, scopeKey, serverFolder)
        }
        db.delete("sync_queue", "op_id = ?", arrayOf(opId.toString()))
    }

    fun completeUploadAttachmentOp(
        scopeKey: String,
        opId: Long,
        localAttachmentId: String,
        serverMeta: AttachmentMetadata
    ) {
        withTransaction { db ->
            recordIdMappingLocked(db, scopeKey, localAttachmentId, serverMeta.id, "attachment")
            val pendingDelete = findPendingOpIdLocked(db, scopeKey, SyncOpType.DELETE_ATTACHMENT, localAttachmentId)
            db.delete("cached_attachments", "scope_key = ? AND id = ?", arrayOf(scopeKey, localAttachmentId))
            if (pendingDelete == null) {
                upsertAttachmentLocked(db, scopeKey, serverMeta)
            }
            val entityCv = ContentValues().apply { put("entity_id", serverMeta.id) }
            db.update(
                "sync_queue",
                entityCv,
                "scope_key = ? AND entity_id = ? AND op_id != ?",
                arrayOf(scopeKey, localAttachmentId, opId.toString())
            )
            moveAttachmentBytesLocked(scopeKey, localAttachmentId, serverMeta.id)
            db.delete("sync_queue", "op_id = ?", arrayOf(opId.toString()))
        }
    }

    fun completeShareNoteOp(
        scopeKey: String,
        opId: Long,
        recipientKey: String,
        serverShare: NoteShareInfo
    ) = withTransaction { db ->
        db.delete(
            "cached_note_shares",
            "scope_key = ? AND note_id = ? AND shared_with_user_id = ?",
            arrayOf(scopeKey, serverShare.noteId, "$PENDING_SHARE_PREFIX$recipientKey")
        )
        upsertShareLocked(db, scopeKey, serverShare)
        db.delete("sync_queue", "op_id = ?", arrayOf(opId.toString()))
    }

    // -------------------------------------------------------------------------
    // Server Snapshot Reconciliation (Preserving Any Pending Local Operations)
    // -------------------------------------------------------------------------

    fun mergeServerFolders(scopeKey: String, serverFolders: List<Folder>) = withTransaction { db ->
        val deletedIds = mutableSetOf<String>()
        val renamedMap = mutableMapOf<String, String>()
        val pendingCreateIds = mutableSetOf<String>()

        db.rawQuery(
            "SELECT op_type, entity_id, payload_json FROM sync_queue WHERE scope_key = ? ORDER BY op_id ASC",
            arrayOf(scopeKey)
        ).use { c ->
            while (c.moveToNext()) {
                val type = runCatching { SyncOpType.valueOf(c.getString(0)) }.getOrNull() ?: continue
                val entityId = c.getString(1)
                val payload = c.getString(2)
                when (type) {
                    SyncOpType.CREATE_FOLDER -> pendingCreateIds.add(entityId)
                    SyncOpType.RENAME_FOLDER -> {
                        val name = runCatching { JSONObject(payload).optString("name") }.getOrNull()
                        if (!name.isNullOrBlank()) renamedMap[entityId] = name
                    }
                    SyncOpType.DELETE_FOLDER -> deletedIds.add(entityId)
                    else -> {}
                }
            }
        }

        val localPendingFolders = mutableListOf<Folder>()
        if (pendingCreateIds.isNotEmpty()) {
            db.rawQuery(
                "SELECT id, user_id, name, created_at FROM cached_folders WHERE scope_key = ?",
                arrayOf(scopeKey)
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    if (pendingCreateIds.contains(id) || id.startsWith(LOCAL_FOLDER_PREFIX)) {
                        localPendingFolders.add(Folder(id, c.getString(1), c.getString(2), c.getString(3)))
                    }
                }
            }
        }

        db.delete("cached_folders", "scope_key = ?", arrayOf(scopeKey))
        serverFolders.forEach { folder ->
            if (!deletedIds.contains(folder.id)) {
                val effective = renamedMap[folder.id]?.let { folder.copy(name = it) } ?: folder
                upsertFolderLocked(db, scopeKey, effective)
            }
        }
        localPendingFolders.forEach { folder ->
            upsertFolderLocked(db, scopeKey, folder)
        }
    }

    fun mergeServerNotes(scopeKey: String, currentUserId: String?, serverNotes: List<Note>) = withTransaction { db ->
        val deletedNoteIds = mutableSetOf<String>()
        val deletedFolderIds = mutableSetOf<String>()
        val pendingCreateIds = mutableSetOf<String>()
        val pendingUpdatesByNoteId = mutableMapOf<String, MutableList<JSONObject>>()

        db.rawQuery(
            "SELECT op_type, entity_id, parent_id, payload_json FROM sync_queue WHERE scope_key = ? ORDER BY op_id ASC",
            arrayOf(scopeKey)
        ).use { c ->
            while (c.moveToNext()) {
                val type = runCatching { SyncOpType.valueOf(c.getString(0)) }.getOrNull() ?: continue
                val entityId = c.getString(1)
                val parentId = if (c.isNull(2)) null else c.getString(2)
                val payloadStr = c.getString(3)
                when (type) {
                    SyncOpType.CREATE_NOTE -> pendingCreateIds.add(entityId)
                    SyncOpType.UPDATE_NOTE -> {
                        val json = runCatching { JSONObject(payloadStr) }.getOrNull()
                        if (json != null) {
                            pendingUpdatesByNoteId.getOrPut(entityId) { mutableListOf() }.add(json)
                        }
                    }
                    SyncOpType.DELETE_NOTE -> deletedNoteIds.add(entityId)
                    SyncOpType.DELETE_FOLDER -> deletedFolderIds.add(entityId)
                    SyncOpType.REVOKE_SHARE -> {
                        if (!currentUserId.isNullOrBlank() && entityId == currentUserId && !parentId.isNullOrBlank()) {
                            deletedNoteIds.add(parentId)
                        }
                    }
                    else -> {}
                }
            }
        }

        val localPendingNotes = mutableMapOf<String, Note>()
        db.rawQuery(
            """
            SELECT id, owner_id, owner_username, folder_id, title, body, tags_json, access_role, created_at, updated_at
            FROM cached_notes WHERE scope_key = ?
            """.trimIndent(),
            arrayOf(scopeKey)
        ).use { c ->
            while (c.moveToNext()) {
                val n = readNoteCursor(c)
                if (pendingCreateIds.contains(n.id) || n.id.startsWith(LOCAL_NOTE_PREFIX) || pendingUpdatesByNoteId.containsKey(n.id)) {
                    localPendingNotes[n.id] = n
                }
            }
        }

        db.delete("cached_notes", "scope_key = ?", arrayOf(scopeKey))

        serverNotes.forEach { srv ->
            if (deletedNoteIds.contains(srv.id)) return@forEach
            var effective = if (deletedFolderIds.contains(srv.folderId)) srv.copy(folderId = null) else srv
            val updates = pendingUpdatesByNoteId[srv.id]
            if (!updates.isNullOrEmpty()) {
                val localModified = localPendingNotes[srv.id]
                if (localModified != null) {
                    effective = localModified.copy(
                        ownerId = srv.ownerId,
                        ownerUsername = srv.ownerUsername,
                        accessRole = srv.accessRole
                    )
                }
            }
            upsertNoteLocked(db, scopeKey, effective)
        }

        localPendingNotes.values
            .filter { pendingCreateIds.contains(it.id) || it.id.startsWith(LOCAL_NOTE_PREFIX) }
            .forEach { localNote ->
                if (!deletedNoteIds.contains(localNote.id)) {
                    upsertNoteLocked(db, scopeKey, localNote)
                }
            }
    }

    fun upsertSingleServerNote(scopeKey: String, serverNote: Note) = withTransaction { db ->
        val hasPending = db.rawQuery(
            """
            SELECT 1 FROM sync_queue
            WHERE scope_key = ? AND entity_id = ? AND op_type IN (?, ?)
            LIMIT 1
            """.trimIndent(),
            arrayOf(scopeKey, serverNote.id, SyncOpType.UPDATE_NOTE.name, SyncOpType.DELETE_NOTE.name)
        ).use { it.moveToFirst() }
        if (!hasPending) {
            upsertNoteLocked(db, scopeKey, serverNote)
        }
    }

    fun removeNoteFromCacheOnly(scopeKey: String, noteId: String) = withTransaction { db ->
        val resolved = resolveIdLocked(db, scopeKey, noteId) ?: noteId
        db.delete("cached_notes", "scope_key = ? AND id = ?", arrayOf(scopeKey, resolved))
        db.delete("cached_note_shares", "scope_key = ? AND note_id = ?", arrayOf(scopeKey, resolved))
        db.delete("cached_attachments", "scope_key = ? AND note_id = ?", arrayOf(scopeKey, resolved))
    }

    fun mergeServerSharesForNote(scopeKey: String, noteId: String, serverShares: List<NoteShareInfo>) = withTransaction { db ->
        val resolvedNoteId = resolveIdLocked(db, scopeKey, noteId) ?: noteId
        val revokedUserIds = mutableSetOf<String>()
        val pendingShareOps = mutableMapOf<String, String>()

        db.rawQuery(
            """
            SELECT op_type, entity_id, payload_json FROM sync_queue
            WHERE scope_key = ? AND parent_id = ?
            ORDER BY op_id ASC
            """.trimIndent(),
            arrayOf(scopeKey, resolvedNoteId)
        ).use { c ->
            while (c.moveToNext()) {
                val type = runCatching { SyncOpType.valueOf(c.getString(0)) }.getOrNull() ?: continue
                val entityId = c.getString(1)
                when (type) {
                    SyncOpType.REVOKE_SHARE -> revokedUserIds.add(entityId)
                    SyncOpType.SHARE_NOTE -> {
                        val perm = runCatching { JSONObject(c.getString(2)).optString("permission", "read") }
                            .getOrDefault("read")
                        pendingShareOps[entityId] = perm
                    }
                    else -> {}
                }
            }
        }

        val pendingPlaceholders = mutableListOf<NoteShareInfo>()
        db.rawQuery(
            """
            SELECT note_id, shared_with_user_id, username, email, permission, created_at
            FROM cached_note_shares
            WHERE scope_key = ? AND note_id = ? AND shared_with_user_id LIKE ?
            """.trimIndent(),
            arrayOf(scopeKey, resolvedNoteId, "$PENDING_SHARE_PREFIX%")
        ).use { c ->
            while (c.moveToNext()) {
                pendingPlaceholders.add(
                    NoteShareInfo(
                        noteId = c.getString(0),
                        sharedWithUserId = c.getString(1),
                        username = c.getString(2),
                        email = c.getString(3),
                        permission = c.getString(4),
                        createdAt = c.getString(5)
                    )
                )
            }
        }

        db.delete("cached_note_shares", "scope_key = ? AND note_id = ?", arrayOf(scopeKey, resolvedNoteId))

        serverShares.forEach { share ->
            if (revokedUserIds.contains(share.sharedWithUserId)) return@forEach
            val overridePerm = pendingShareOps[share.username.lowercase()]
                ?: pendingShareOps[share.email.lowercase()]
            val effective = if (overridePerm != null) share.copy(permission = overridePerm) else share
            upsertShareLocked(db, scopeKey, effective.copy(noteId = resolvedNoteId))
        }

        pendingPlaceholders.forEach { placeholder ->
            val key = placeholder.sharedWithUserId.removePrefix(PENDING_SHARE_PREFIX)
            val alreadyMatched = serverShares.any {
                it.username.equals(key, ignoreCase = true) || it.email.equals(key, ignoreCase = true)
            }
            if (!alreadyMatched && pendingShareOps.containsKey(key)) {
                upsertShareLocked(db, scopeKey, placeholder)
            }
        }
    }

    fun mergeServerAttachmentsForNote(
        scopeKey: String,
        noteId: String,
        serverAttachments: List<AttachmentMetadata>
    ) = withTransaction { db ->
        val resolvedNoteId = resolveIdLocked(db, scopeKey, noteId) ?: noteId
        val deletedAttIds = mutableSetOf<String>()
        val pendingUploadIds = mutableSetOf<String>()

        db.rawQuery(
            """
            SELECT op_type, entity_id FROM sync_queue
            WHERE scope_key = ? AND parent_id = ?
            """.trimIndent(),
            arrayOf(scopeKey, resolvedNoteId)
        ).use { c ->
            while (c.moveToNext()) {
                val type = runCatching { SyncOpType.valueOf(c.getString(0)) }.getOrNull() ?: continue
                val entityId = c.getString(1)
                when (type) {
                    SyncOpType.DELETE_ATTACHMENT -> deletedAttIds.add(entityId)
                    SyncOpType.UPLOAD_ATTACHMENT -> pendingUploadIds.add(entityId)
                    else -> {}
                }
            }
        }

        val localPendingAttachments = mutableListOf<AttachmentMetadata>()
        if (pendingUploadIds.isNotEmpty()) {
            db.rawQuery(
                """
                SELECT id, note_id, uploader_id, filename, content_type, size_bytes, url, markdown, created_at
                FROM cached_attachments WHERE scope_key = ? AND note_id = ?
                """.trimIndent(),
                arrayOf(scopeKey, resolvedNoteId)
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    if (pendingUploadIds.contains(id) || id.startsWith(LOCAL_ATTACHMENT_PREFIX)) {
                        localPendingAttachments.add(
                            AttachmentMetadata(
                                id = id,
                                noteId = c.getString(1),
                                uploaderId = c.getString(2),
                                filename = c.getString(3),
                                contentType = c.getString(4),
                                sizeBytes = c.getInt(5),
                                url = c.getString(6),
                                markdown = c.getString(7),
                                createdAt = c.getString(8)
                            )
                        )
                    }
                }
            }
        }

        db.delete("cached_attachments", "scope_key = ? AND note_id = ?", arrayOf(scopeKey, resolvedNoteId))

        serverAttachments.forEach { att ->
            if (!deletedAttIds.contains(att.id)) {
                upsertAttachmentLocked(db, scopeKey, att.copy(noteId = resolvedNoteId))
            }
        }
        localPendingAttachments.forEach { att ->
            upsertAttachmentLocked(db, scopeKey, att)
        }
    }

    // -------------------------------------------------------------------------
    // Internal SQL Helpers
    // -------------------------------------------------------------------------

    private fun normalizeTags(tags: List<String>): List<String> =
        tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

    private fun readNoteCursor(c: android.database.Cursor): Note {
        val tagsRaw = c.getString(6)
        val tags = mutableListOf<String>()
        runCatching {
            val arr = JSONArray(tagsRaw)
            for (i in 0 until arr.length()) {
                val t = arr.optString(i).trim()
                if (t.isNotEmpty()) tags.add(t)
            }
        }
        return Note(
            id = c.getString(0),
            ownerId = c.getString(1),
            ownerUsername = c.getString(2),
            folderId = if (c.isNull(3)) null else c.getString(3).ifBlank { null },
            title = c.getString(4),
            body = c.getString(5),
            tags = tags,
            accessRole = c.getString(7),
            createdAt = c.getString(8),
            updatedAt = c.getString(9)
        )
    }

    private fun upsertFolderLocked(db: SQLiteDatabase, scopeKey: String, folder: Folder) {
        val cv = ContentValues().apply {
            put("scope_key", scopeKey)
            put("id", folder.id)
            put("user_id", folder.userId)
            put("name", folder.name)
            put("created_at", folder.createdAt)
        }
        db.insertWithOnConflict("cached_folders", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun upsertNoteLocked(db: SQLiteDatabase, scopeKey: String, note: Note) {
        val tagsArr = JSONArray()
        note.tags.forEach { tagsArr.put(it) }
        val cv = ContentValues().apply {
            put("scope_key", scopeKey)
            put("id", note.id)
            put("owner_id", note.ownerId)
            put("owner_username", note.ownerUsername)
            if (note.folderId.isNullOrBlank()) putNull("folder_id") else put("folder_id", note.folderId)
            put("title", note.title)
            put("body", note.body)
            put("tags_json", tagsArr.toString())
            put("access_role", note.accessRole)
            put("created_at", note.createdAt)
            put("updated_at", note.updatedAt)
        }
        db.insertWithOnConflict("cached_notes", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun upsertShareLocked(db: SQLiteDatabase, scopeKey: String, share: NoteShareInfo) {
        val cv = ContentValues().apply {
            put("scope_key", scopeKey)
            put("note_id", share.noteId)
            put("shared_with_user_id", share.sharedWithUserId)
            put("username", share.username)
            put("email", share.email)
            put("permission", share.permission)
            put("created_at", share.createdAt)
        }
        db.insertWithOnConflict("cached_note_shares", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun upsertAttachmentLocked(db: SQLiteDatabase, scopeKey: String, att: AttachmentMetadata) {
        val cv = ContentValues().apply {
            put("scope_key", scopeKey)
            put("id", att.id)
            put("note_id", att.noteId)
            put("uploader_id", att.uploaderId)
            put("filename", att.filename)
            put("content_type", att.contentType)
            put("size_bytes", att.sizeBytes)
            put("url", att.url)
            put("markdown", att.markdown)
            put("created_at", att.createdAt)
        }
        db.insertWithOnConflict("cached_attachments", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun insertQueueOpLocked(
        db: SQLiteDatabase,
        scopeKey: String,
        opType: SyncOpType,
        entityId: String,
        parentId: String?,
        payload: JSONObject
    ): Long {
        val cv = ContentValues().apply {
            put("scope_key", scopeKey)
            put("op_type", opType.name)
            put("entity_id", entityId)
            if (parentId == null) putNull("parent_id") else put("parent_id", parentId)
            put("payload_json", payload.toString())
            put("status", "PENDING")
            put("retry_count", 0)
            put("created_at_ms", System.currentTimeMillis())
        }
        return db.insertOrThrow("sync_queue", null, cv)
    }

    private fun updateQueuePayloadLocked(db: SQLiteDatabase, opId: Long, payload: JSONObject) {
        val cv = ContentValues().apply { put("payload_json", payload.toString()) }
        db.update("sync_queue", cv, "op_id = ?", arrayOf(opId.toString()))
    }

    private fun findPendingOpIdLocked(
        db: SQLiteDatabase,
        scopeKey: String,
        opType: SyncOpType,
        entityId: String
    ): Long? {
        return db.rawQuery(
            """
            SELECT op_id FROM sync_queue
            WHERE scope_key = ? AND status = 'PENDING' AND op_type = ? AND entity_id = ?
            ORDER BY op_id ASC LIMIT 1
            """.trimIndent(),
            arrayOf(scopeKey, opType.name, entityId)
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }

    private fun findPendingOpLocked(
        db: SQLiteDatabase,
        scopeKey: String,
        opType: SyncOpType,
        entityId: String
    ): Pair<Long, String>? {
        return db.rawQuery(
            """
            SELECT op_id, payload_json FROM sync_queue
            WHERE scope_key = ? AND status = 'PENDING' AND op_type = ? AND entity_id = ?
            ORDER BY op_id ASC LIMIT 1
            """.trimIndent(),
            arrayOf(scopeKey, opType.name, entityId)
        ).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getString(1) else null }
    }

    private fun upsertPendingNoteUpdateOpLocked(
        db: SQLiteDatabase,
        scopeKey: String,
        noteId: String,
        title: String?,
        body: String?,
        tags: List<String>?,
        updateFolder: Boolean,
        folderId: String?
    ) {
        val existing = findPendingOpLocked(db, scopeKey, SyncOpType.UPDATE_NOTE, noteId)
        val merged = if (existing != null) JSONObject(existing.second) else JSONObject()
        if (title != null) {
            merged.put("has_title", true)
            merged.put("title", title)
        }
        if (body != null) {
            merged.put("has_body", true)
            merged.put("body", body)
        }
        if (tags != null) {
            merged.put("has_tags", true)
            merged.put("tags", JSONArray(tags))
        }
        if (updateFolder) {
            merged.put("update_folder", true)
            if (folderId == null) {
                merged.put("folder_id", JSONObject.NULL)
            } else {
                merged.put("folder_id", folderId)
            }
        }

        if (existing != null) {
            val folderCreateOp = if (updateFolder && !folderId.isNullOrBlank()) {
                findPendingOpIdLocked(db, scopeKey, SyncOpType.CREATE_FOLDER, folderId)
            } else {
                null
            }
            if (folderCreateOp != null && folderCreateOp > existing.first) {
                // Re-insert at tail so it runs after CREATE_FOLDER
                db.delete("sync_queue", "op_id = ?", arrayOf(existing.first.toString()))
                insertQueueOpLocked(db, scopeKey, SyncOpType.UPDATE_NOTE, noteId, null, merged)
            } else {
                updateQueuePayloadLocked(db, existing.first, merged)
            }
        } else {
            insertQueueOpLocked(db, scopeKey, SyncOpType.UPDATE_NOTE, noteId, null, merged)
        }
    }

    private fun clearFolderInPendingNoteOpsLocked(db: SQLiteDatabase, scopeKey: String, deletedFolderId: String) {
        db.rawQuery(
            """
            SELECT op_id, op_type, payload_json FROM sync_queue
            WHERE scope_key = ? AND status = 'PENDING' AND op_type IN (?, ?)
            """.trimIndent(),
            arrayOf(scopeKey, SyncOpType.CREATE_NOTE.name, SyncOpType.UPDATE_NOTE.name)
        ).use { c ->
            while (c.moveToNext()) {
                val qId = c.getLong(0)
                val obj = runCatching { JSONObject(c.getString(2)) }.getOrNull() ?: continue
                if (!obj.isNull("folder_id") && obj.optString("folder_id") == deletedFolderId) {
                    obj.put("folder_id", JSONObject.NULL)
                    updateQueuePayloadLocked(db, qId, obj)
                }
            }
        }
    }

    private fun recordIdMappingLocked(
        db: SQLiteDatabase,
        scopeKey: String,
        localId: String,
        remoteId: String,
        entityType: String
    ) {
        if (localId == remoteId) return
        val cv = ContentValues().apply {
            put("scope_key", scopeKey)
            put("local_id", localId)
            put("remote_id", remoteId)
            put("entity_type", entityType)
            put("created_at_ms", System.currentTimeMillis())
        }
        db.insertWithOnConflict("id_mappings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
