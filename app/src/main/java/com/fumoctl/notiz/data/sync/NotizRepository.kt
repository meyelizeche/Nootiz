package com.fumoctl.notiz.data.sync

import android.content.Context
import com.fumoctl.notiz.data.local.LocalCacheStore
import com.fumoctl.notiz.data.local.QueuedSyncOp
import com.fumoctl.notiz.data.local.SessionPreferences
import com.fumoctl.notiz.data.local.SyncOpType
import com.fumoctl.notiz.data.model.AttachmentMetadata
import com.fumoctl.notiz.data.model.Folder
import com.fumoctl.notiz.data.model.Note
import com.fumoctl.notiz.data.model.NoteShareInfo
import com.fumoctl.notiz.data.model.SyncStatus
import com.fumoctl.notiz.data.remote.ApiException
import com.fumoctl.notiz.data.remote.NotizApiClient
import com.fumoctl.notiz.data.remote.isOfflineOrUnreachable
import com.fumoctl.notiz.data.remote.isUnauthorizedError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

data class CachedDashboardData(
    val folders: List<Folder>,
    val notes: List<Note>,
    val outgoingSharesByNote: Map<String, List<NoteShareInfo>>
)

data class ShareNoteOutcome(
    val shareInfo: NoteShareInfo,
    val queuedOffline: Boolean
)

class NotizRepository(
    context: Context,
    private val sessionPrefs: SessionPreferences,
    val apiClient: NotizApiClient
) {
    private val appContext = context.applicationContext
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val cacheStore = LocalCacheStore(appContext)
    val networkMonitor = NetworkMonitor(appContext)

    private val syncMutex = Mutex()
    private val pendingExtraSyncPass = AtomicBoolean(false)
    private val pendingServerRefresh = AtomicBoolean(false)

    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _dataVersion = MutableStateFlow(0L)
    val dataVersion: StateFlow<Long> = _dataVersion.asStateFlow()

    private val _remappedIds = MutableStateFlow<Map<String, String>>(emptyMap())
    val remappedIds: StateFlow<Map<String, String>> = _remappedIds.asStateFlow()

    private val _authExpiredEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val authExpiredEvents: SharedFlow<Unit> = _authExpiredEvents.asSharedFlow()

    private var watchdogJob: Job? = null

    fun start() {
        ioScope.launch {
            sessionPrefs.ensureLoaded()
            networkMonitor.start()
            refreshQueueStatusInternal()

            // Observe OS transport changes
            launch {
                networkMonitor.hasTransport.collect { hasNet ->
                    if (!hasNet) {
                        _syncStatus.update { it.copy(isOnline = false, isSyncing = false) }
                    }
                }
            }

            // Trigger immediate sync + server refresh whenever connectivity is restored or changes
            launch {
                networkMonitor.reconnectEvents.collect {
                    if (activeScopeKey() != null) {
                        requestSync(refreshFromServer = true)
                    }
                }
            }
        }

        if (watchdogJob?.isActive != true) {
            watchdogJob = ioScope.launch {
                while (isActive) {
                    delay(12_000L)
                    val scopeKey = activeScopeKey() ?: continue
                    val summary = cacheStore.getPendingSummary(scopeKey)
                    val status = _syncStatus.value
                    // Periodically probe health if marked offline or if operations are waiting to sync
                    if (!status.isSyncing && (!status.isOnline || summary.pendingCount > 0)) {
                        if (networkMonitor.computeHasActiveTransport()) {
                            val healthy = apiClient.checkHealth().getOrDefault(false)
                            if (healthy) {
                                _syncStatus.update { it.copy(isOnline = true, lastError = null) }
                                requestSync(refreshFromServer = true)
                            }
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        watchdogJob?.cancel()
        networkMonitor.stop()
        ioScope.cancel()
    }

    fun onSessionChanged() {
        ioScope.launch {
            refreshQueueStatusInternal()
            if (activeScopeKey() != null) {
                requestSync(refreshFromServer = true)
            }
        }
    }

    fun onAppResumed() {
        ioScope.launch {
            if (activeScopeKey() != null) {
                requestSync(refreshFromServer = true)
            }
        }
    }

    private fun activeScopeKey(): String? {
        sessionPrefs.ensureLoaded()
        val token = sessionPrefs.token?.takeIf { it.isNotBlank() } ?: return null
        val userId = sessionPrefs.user?.id?.takeIf { it.isNotBlank() } ?: return null
        if (token.isBlank()) return null
        return LocalCacheStore.buildScopeKey(sessionPrefs.baseUrl, userId)
    }

    private fun bumpDataVersion() {
        _dataVersion.update { it + 1L }
    }

    private fun refreshQueueStatusInternal() {
        val scopeKey = activeScopeKey()
        if (scopeKey == null) {
            _syncStatus.value = SyncStatus(isOnline = networkMonitor.computeHasActiveTransport())
            _remappedIds.value = emptyMap()
            return
        }
        val summary = cacheStore.getPendingSummary(scopeKey)
        _remappedIds.value = cacheStore.getAllIdMappings(scopeKey)
        _syncStatus.update { current ->
            current.copy(
                isOnline = if (!networkMonitor.computeHasActiveTransport()) false else current.isOnline,
                pendingCount = summary.pendingCount,
                pendingNoteIds = summary.pendingNoteIds
            )
        }
    }

    // -------------------------------------------------------------------------
    // Fast Local Cache Reads (Never Block on Network)
    // -------------------------------------------------------------------------

    suspend fun loadCachedDashboard(): CachedDashboardData = withContext(Dispatchers.IO) {
        val scopeKey = activeScopeKey()
            ?: return@withContext CachedDashboardData(emptyList(), emptyList(), emptyMap())
        refreshQueueStatusInternal()
        CachedDashboardData(
            folders = cacheStore.getFolders(scopeKey),
            notes = cacheStore.getNotes(scopeKey),
            outgoingSharesByNote = cacheStore.getAllOutgoingSharesByNote(scopeKey)
        )
    }

    suspend fun getCachedFolders(): List<Folder> = withContext(Dispatchers.IO) {
        val scopeKey = activeScopeKey() ?: return@withContext emptyList()
        cacheStore.getFolders(scopeKey)
    }

    suspend fun getCachedNotes(): List<Note> = withContext(Dispatchers.IO) {
        val scopeKey = activeScopeKey() ?: return@withContext emptyList()
        cacheStore.getNotes(scopeKey)
    }

    suspend fun getCachedNote(noteId: String): Note? = withContext(Dispatchers.IO) {
        val scopeKey = activeScopeKey() ?: return@withContext null
        cacheStore.getNote(scopeKey, noteId)
    }

    suspend fun getCachedAttachments(noteId: String): List<AttachmentMetadata> = withContext(Dispatchers.IO) {
        val scopeKey = activeScopeKey() ?: return@withContext emptyList()
        cacheStore.getAttachments(scopeKey, noteId)
    }

    suspend fun getCachedNoteShares(noteId: String): List<NoteShareInfo> = withContext(Dispatchers.IO) {
        val scopeKey = activeScopeKey() ?: return@withContext emptyList()
        cacheStore.getNoteShares(scopeKey, noteId)
    }

    suspend fun resolveId(rawId: String?): String? = withContext(Dispatchers.IO) {
        if (rawId.isNullOrBlank()) return@withContext rawId
        val scopeKey = activeScopeKey() ?: return@withContext rawId
        cacheStore.resolveId(scopeKey, rawId)
    }

    // -------------------------------------------------------------------------
    // Offline-First Mutations (Instant Local Persistence + Background Sync)
    // -------------------------------------------------------------------------

    suspend fun createFolder(name: String): Result<Folder> = withContext(Dispatchers.IO) {
        val scopeKey = activeScopeKey()
            ?: return@withContext Result.failure(IllegalStateException("Sesión no iniciada."))
        val userId = sessionPrefs.user?.id ?: ""
        val res = cacheStore.createFolderOfflineFirst(scopeKey, userId, name)
        if (res.isSuccess) {
            refreshQueueStatusInternal()
            bumpDataVersion()
            requestSync(refreshFromServer = false)
        }
        res
    }

    suspend fun renameFolder(folderId: String, newName: String): Result<Folder> = withContext(Dispatchers.IO) {
        val scopeKey = activeScopeKey()
            ?: return@withContext Result.failure(IllegalStateException("Sesión no iniciada."))
        val res = cacheStore.renameFolderOfflineFirst(scopeKey, folderId, newName)
        if (res.isSuccess) {
            refreshQueueStatusInternal()
            bumpDataVersion()
            requestSync(refreshFromServer = false)
        }
        res
    }

    suspend fun deleteFolder(folderId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val scopeKey = activeScopeKey()
            ?: return@withContext Result.failure(IllegalStateException("Sesión no iniciada."))
        val res = cacheStore.deleteFolderOfflineFirst(scopeKey, folderId)
        if (res.isSuccess) {
            refreshQueueStatusInternal()
            bumpDataVersion()
            requestSync(refreshFromServer = false)
        }
        res
    }

    suspend fun createNote(
        title: String,
        body: String = "",
        tags: List<String> = emptyList(),
        folderId: String? = null
    ): Result<Note> = withContext(Dispatchers.IO) {
        val scopeKey = activeScopeKey()
            ?: return@withContext Result.failure(IllegalStateException("Sesión no iniciada."))
        val user = sessionPrefs.user
        val note = cacheStore.createNoteOfflineFirst(
            scopeKey = scopeKey,
            ownerId = user?.id ?: "",
            ownerUsername = user?.username ?: "",
            title = title,
            body = body,
            tags = tags,
            folderId = folderId
        )
        refreshQueueStatusInternal()
        bumpDataVersion()
        requestSync(refreshFromServer = false)
        Result.success(note)
    }

    suspend fun updateNote(
        noteId: String,
        title: String? = null,
        body: String? = null,
        tags: List<String>? = null,
        updateFolder: Boolean = false,
        folderId: String? = null
    ): Result<Note> = withContext(Dispatchers.IO) {
        val scopeKey = activeScopeKey()
            ?: return@withContext Result.failure(IllegalStateException("Sesión no iniciada."))
        val res = cacheStore.updateNoteOfflineFirst(
            scopeKey = scopeKey,
            noteId = noteId,
            title = title,
            body = body,
            tags = tags,
            updateFolder = updateFolder,
            folderId = folderId
        )
        if (res.isSuccess) {
            refreshQueueStatusInternal()
            bumpDataVersion()
            requestSync(refreshFromServer = false)
        }
        res
    }

    suspend fun deleteNote(noteId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val scopeKey = activeScopeKey()
            ?: return@withContext Result.failure(IllegalStateException("Sesión no iniciada."))
        val resolved = cacheStore.resolveId(scopeKey, noteId) ?: noteId
        cacheStore.deleteNoteOfflineFirst(scopeKey, resolved)
        sessionPrefs.removeNoteMetadata(noteId)
        if (resolved != noteId) {
            sessionPrefs.removeNoteMetadata(resolved)
        }
        refreshQueueStatusInternal()
        bumpDataVersion()
        requestSync(refreshFromServer = false)
        Result.success(Unit)
    }

    suspend fun uploadAttachment(
        noteId: String,
        filename: String,
        contentType: String,
        bytes: ByteArray
    ): Result<AttachmentMetadata> = withContext(Dispatchers.IO) {
        val scopeKey = activeScopeKey()
            ?: return@withContext Result.failure(IllegalStateException("Sesión no iniciada."))
        val uploaderId = sessionPrefs.user?.id ?: ""
        val meta = cacheStore.uploadAttachmentOfflineFirst(
            scopeKey = scopeKey,
            noteId = noteId,
            uploaderId = uploaderId,
            filename = filename,
            contentType = contentType,
            bytes = bytes
        )
        refreshQueueStatusInternal()
        bumpDataVersion()
        requestSync(refreshFromServer = false)
        Result.success(meta)
    }

    suspend fun deleteAttachment(noteId: String, attachmentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val scopeKey = activeScopeKey()
            ?: return@withContext Result.failure(IllegalStateException("Sesión no iniciada."))
        cacheStore.deleteAttachmentOfflineFirst(scopeKey, noteId, attachmentId)
        refreshQueueStatusInternal()
        bumpDataVersion()
        requestSync(refreshFromServer = false)
        Result.success(Unit)
    }

    suspend fun downloadAttachmentBytes(noteId: String, attachmentId: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            val scopeKey = activeScopeKey()
                ?: return@withContext Result.failure(IllegalStateException("Sesión no iniciada."))

            // 1. Check persistent local disk + memory cache first
            cacheStore.readAttachmentBytes(scopeKey, attachmentId)?.let {
                return@withContext Result.success(it)
            }

            val resolvedNoteId = cacheStore.resolveId(scopeKey, noteId) ?: noteId
            val resolvedAttId = cacheStore.resolveId(scopeKey, attachmentId) ?: attachmentId

            if (LocalCacheStore.isLocalId(resolvedNoteId) || LocalCacheStore.isLocalId(resolvedAttId)) {
                return@withContext Result.failure(IllegalStateException("Adjunto local no encontrado."))
            }
            if (!networkMonitor.computeHasActiveTransport()) {
                _syncStatus.update { it.copy(isOnline = false) }
                return@withContext Result.failure(IllegalStateException("Sin conexión para descargar la imagen."))
            }

            val res = apiClient.downloadAttachmentBytes(resolvedNoteId, resolvedAttId)
            res.onSuccess { bytes ->
                cacheStore.saveAttachmentBytes(scopeKey, resolvedAttId, bytes)
                _syncStatus.update { it.copy(isOnline = true) }
            }.onFailure { err ->
                if (err.isOfflineOrUnreachable()) {
                    _syncStatus.update { it.copy(isOnline = false) }
                } else if (err.isUnauthorizedError()) {
                    _authExpiredEvents.tryEmit(Unit)
                }
            }
            res
        }

    suspend fun shareNote(
        noteId: String,
        recipient: String,
        permission: String = "read"
    ): Result<ShareNoteOutcome> = withContext(Dispatchers.IO) {
        val scopeKey = activeScopeKey()
            ?: return@withContext Result.failure(IllegalStateException("Sesión no iniciada."))
        val cleanRecipient = recipient.trim()
        if (cleanRecipient.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Ingresa el correo o username del destinatario."))
        }

        // If online and note was created locally moments ago, flush pending queue so it gets a real server UUID
        var resolvedNoteId = cacheStore.resolveId(scopeKey, noteId) ?: noteId
        if (resolvedNoteId.startsWith(LocalCacheStore.LOCAL_NOTE_PREFIX) && networkMonitor.computeHasActiveTransport()) {
            syncNow(refreshFromServer = false)
            resolvedNoteId = cacheStore.resolveId(scopeKey, noteId) ?: noteId
        }

        if (!resolvedNoteId.startsWith(LocalCacheStore.LOCAL_NOTE_PREFIX) && networkMonitor.computeHasActiveTransport()) {
            val onlineRes = apiClient.shareNote(resolvedNoteId, cleanRecipient, permission)
            if (onlineRes.isSuccess) {
                val info = onlineRes.getOrThrow()
                cacheStore.cacheImmediateShareResult(scopeKey, info)
                _syncStatus.update { it.copy(isOnline = true, lastError = null) }
                bumpDataVersion()
                return@withContext Result.success(ShareNoteOutcome(info, queuedOffline = false))
            }
            val err = onlineRes.exceptionOrNull()
            if (err != null && !err.isOfflineOrUnreachable()) {
                if (err.isUnauthorizedError()) {
                    _authExpiredEvents.tryEmit(Unit)
                }
                return@withContext Result.failure(err)
            }
            // Network unreachable -> fall through to offline queue
            _syncStatus.update { it.copy(isOnline = false) }
        }

        val queuedShare = cacheStore.queueShareNoteOffline(scopeKey, resolvedNoteId, cleanRecipient, permission)
        refreshQueueStatusInternal()
        bumpDataVersion()
        requestSync(refreshFromServer = false)
        Result.success(ShareNoteOutcome(queuedShare, queuedOffline = true))
    }

    suspend fun revokeNoteShare(noteId: String, targetUserId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val scopeKey = activeScopeKey()
            ?: return@withContext Result.failure(IllegalStateException("Sesión no iniciada."))
        val myId = sessionPrefs.user?.id
        val resolvedNoteId = cacheStore.resolveId(scopeKey, noteId) ?: noteId
        cacheStore.revokeShareOfflineFirst(scopeKey, resolvedNoteId, targetUserId, myId)
        if (!myId.isNullOrBlank() && targetUserId == myId) {
            sessionPrefs.removeNoteMetadata(noteId)
            sessionPrefs.removeNoteMetadata(resolvedNoteId)
        }
        refreshQueueStatusInternal()
        bumpDataVersion()
        requestSync(refreshFromServer = false)
        Result.success(Unit)
    }

    // -------------------------------------------------------------------------
    // Note Detail Refresh (For NoteEditorScreen)
    // -------------------------------------------------------------------------

    suspend fun refreshNoteDetailFromServer(noteId: String) = withContext(Dispatchers.IO) {
        val scopeKey = activeScopeKey() ?: return@withContext
        var resolvedId = cacheStore.resolveId(scopeKey, noteId) ?: noteId
        if (!networkMonitor.computeHasActiveTransport()) {
            _syncStatus.update { it.copy(isOnline = false) }
            return@withContext
        }

        if (cacheStore.hasPendingChangesForNote(scopeKey, resolvedId)) {
            syncNow(refreshFromServer = false)
            resolvedId = cacheStore.resolveId(scopeKey, noteId) ?: noteId
        }
        if (resolvedId.startsWith(LocalCacheStore.LOCAL_NOTE_PREFIX)) return@withContext

        var changed = false
        apiClient.getNote(resolvedId).onSuccess { freshNote ->
            cacheStore.upsertSingleServerNote(scopeKey, freshNote)
            _syncStatus.update { it.copy(isOnline = true) }
            changed = true
            if (freshNote.isOwner) {
                apiClient.getNoteShares(resolvedId).onSuccess { shares ->
                    cacheStore.mergeServerSharesForNote(scopeKey, resolvedId, shares)
                    changed = true
                }
            }
        }.onFailure { err ->
            if (err.isOfflineOrUnreachable()) {
                _syncStatus.update { it.copy(isOnline = false) }
                return@withContext
            } else if (err.isUnauthorizedError()) {
                _authExpiredEvents.tryEmit(Unit)
                return@withContext
            }
        }

        apiClient.getAttachments(resolvedId).onSuccess { serverAttachments ->
            cacheStore.mergeServerAttachmentsForNote(scopeKey, resolvedId, serverAttachments)
            changed = true
        }

        if (changed) {
            bumpDataVersion()
        }
    }

    // -------------------------------------------------------------------------
    // Background & Explicit Synchronization Engine
    // -------------------------------------------------------------------------

    fun requestSync(refreshFromServer: Boolean = false) {
        if (refreshFromServer) {
            pendingServerRefresh.set(true)
        }
        pendingExtraSyncPass.set(true)
        ioScope.launch {
            runSyncLoop()
        }
    }

    suspend fun syncNow(refreshFromServer: Boolean = true) = withContext(Dispatchers.IO) {
        if (refreshFromServer) {
            pendingServerRefresh.set(true)
        }
        pendingExtraSyncPass.set(true)
        runSyncLoop()
    }

    private suspend fun runSyncLoop() {
        if (!syncMutex.tryLock()) {
            // Active sync loop will observe pendingExtraSyncPass / pendingServerRefresh
            return
        }
        try {
            while (pendingExtraSyncPass.getAndSet(false)) {
                val shouldRefreshServer = pendingServerRefresh.getAndSet(false)
                val scopeKey = activeScopeKey() ?: break

                if (!networkMonitor.computeHasActiveTransport()) {
                    refreshQueueStatusInternal()
                    _syncStatus.update { it.copy(isOnline = false, isSyncing = false) }
                    break
                }

                // Step 1: Verify session if user info was missing or reconnecting
                val queueFlushed = flushPendingQueueLocked(scopeKey)
                refreshQueueStatusInternal()

                if (!queueFlushed) {
                    // Network dropped or unauthorized during queue flush; stop until next trigger
                    break
                }

                // Step 2: Pull fresh folders, notes, and shares from server if requested
                if (shouldRefreshServer) {
                    pullServerSnapshotLocked(scopeKey)
                    refreshQueueStatusInternal()
                }
            }
        } finally {
            _syncStatus.update { it.copy(isSyncing = false) }
            syncMutex.unlock()
        }
    }

    private suspend fun flushPendingQueueLocked(scopeKey: String): Boolean {
        val initialSummary = cacheStore.getPendingSummary(scopeKey)
        if (initialSummary.pendingCount == 0) {
            return true
        }

        _syncStatus.update {
            it.copy(
                isSyncing = true,
                pendingCount = initialSummary.pendingCount,
                pendingNoteIds = initialSummary.pendingNoteIds
            )
        }

        while (true) {
            if (!networkMonitor.computeHasActiveTransport()) {
                _syncStatus.update { it.copy(isOnline = false, isSyncing = false) }
                return false
            }

            val op = cacheStore.acquireNextReadyOp(scopeKey) ?: break
            val continueResult = executeSingleOp(scopeKey, op)
            refreshQueueStatusInternal()
            bumpDataVersion()
            if (!continueResult) {
                _syncStatus.update { it.copy(isSyncing = false) }
                return false
            }
        }

        _syncStatus.update {
            it.copy(
                isOnline = true,
                isSyncing = false,
                lastSyncedAtMillis = System.currentTimeMillis(),
                lastError = null
            )
        }
        return true
    }

    private suspend fun executeSingleOp(scopeKey: String, op: QueuedSyncOp): Boolean {
        val payload = runCatching { JSONObject(op.payloadJson) }.getOrElse { JSONObject() }

        return when (op.opType) {
            SyncOpType.CREATE_FOLDER -> {
                val name = payload.optString("name").trim()
                if (name.isEmpty()) {
                    cacheStore.completeOp(op.opId)
                    return true
                }
                val res = apiClient.createFolder(name)
                res.fold(
                    onSuccess = { serverFolder ->
                        cacheStore.completeCreateFolderOp(scopeKey, op.opId, op.entityId, serverFolder)
                        true
                    },
                    onFailure = { err ->
                        when {
                            err.isOfflineOrUnreachable() -> handleTransientFailure(op, err)
                            err.isUnauthorizedError() -> handleUnauthorizedFailure(op, err)
                            (err as? ApiException)?.statusCode == 409 -> {
                                val existing = apiClient.getFolders().getOrNull()
                                    ?.firstOrNull { it.name.equals(name, ignoreCase = true) }
                                if (existing != null) {
                                    cacheStore.completeCreateFolderOp(scopeKey, op.opId, op.entityId, existing)
                                } else {
                                    cacheStore.completeOp(op.opId)
                                }
                                true
                            }
                            else -> {
                                cacheStore.completeOp(op.opId)
                                true
                            }
                        }
                    }
                )
            }

            SyncOpType.RENAME_FOLDER -> {
                val folderId = cacheStore.resolveId(scopeKey, op.entityId) ?: op.entityId
                val name = payload.optString("name").trim()
                if (name.isEmpty() || folderId.startsWith(LocalCacheStore.LOCAL_FOLDER_PREFIX)) {
                    cacheStore.completeOp(op.opId)
                    return true
                }
                apiClient.renameFolder(folderId, name).fold(
                    onSuccess = { serverFolder ->
                        cacheStore.completeRenameFolderOp(scopeKey, op.opId, serverFolder)
                        true
                    },
                    onFailure = { err ->
                        when {
                            err.isOfflineOrUnreachable() -> handleTransientFailure(op, err)
                            err.isUnauthorizedError() -> handleUnauthorizedFailure(op, err)
                            else -> {
                                cacheStore.completeOp(op.opId)
                                true
                            }
                        }
                    }
                )
            }

            SyncOpType.DELETE_FOLDER -> {
                val folderId = cacheStore.resolveId(scopeKey, op.entityId) ?: op.entityId
                if (folderId.startsWith(LocalCacheStore.LOCAL_FOLDER_PREFIX)) {
                    cacheStore.completeOp(op.opId)
                    return true
                }
                apiClient.deleteFolder(folderId).fold(
                    onSuccess = {
                        cacheStore.completeOp(op.opId)
                        true
                    },
                    onFailure = { err ->
                        when {
                            err.isOfflineOrUnreachable() -> handleTransientFailure(op, err)
                            err.isUnauthorizedError() -> handleUnauthorizedFailure(op, err)
                            else -> {
                                cacheStore.completeOp(op.opId)
                                true
                            }
                        }
                    }
                )
            }

            SyncOpType.CREATE_NOTE -> {
                val title = payload.optString("title")
                val body = payload.optString("body")
                val tags = jsonArrayToList(payload.optJSONArray("tags"))
                val rawFolder = if (payload.isNull("folder_id")) null else payload.optString("folder_id").ifBlank { null }
                val resolvedFolder = cacheStore.resolveId(scopeKey, rawFolder)
                    ?.takeUnless { it.startsWith(LocalCacheStore.LOCAL_FOLDER_PREFIX) }

                var createRes = apiClient.createNote(
                    title = title,
                    body = body,
                    tags = tags,
                    folderId = resolvedFolder
                )

                // If target folder was deleted remotely while offline, retry without folder so note is never lost
                if (createRes.isFailure && resolvedFolder != null) {
                    val code = (createRes.exceptionOrNull() as? ApiException)?.statusCode
                    if (code == 400 || code == 404 || code == 409) {
                        createRes = apiClient.createNote(
                            title = title,
                            body = body,
                            tags = tags,
                            folderId = null
                        )
                    }
                }

                createRes.fold(
                    onSuccess = { serverNote ->
                        cacheStore.completeCreateNoteOp(scopeKey, op.opId, op.entityId, serverNote)
                        sessionPrefs.remapNoteId(op.entityId, serverNote.id)
                        true
                    },
                    onFailure = { err ->
                        when {
                            err.isOfflineOrUnreachable() -> handleTransientFailure(op, err)
                            err.isUnauthorizedError() -> handleUnauthorizedFailure(op, err)
                            else -> {
                                _syncStatus.update { it.copy(lastError = err.message) }
                                cacheStore.completeOp(op.opId)
                                true
                            }
                        }
                    }
                )
            }

            SyncOpType.UPDATE_NOTE -> {
                val noteId = cacheStore.resolveId(scopeKey, op.entityId) ?: op.entityId
                if (noteId.startsWith(LocalCacheStore.LOCAL_NOTE_PREFIX)) {
                    cacheStore.completeOp(op.opId)
                    return true
                }
                val title = if (payload.optBoolean("has_title", false)) payload.optString("title") else null
                val body = if (payload.optBoolean("has_body", false)) payload.optString("body") else null
                val tags = if (payload.optBoolean("has_tags", false)) {
                    jsonArrayToList(payload.optJSONArray("tags"))
                } else {
                    null
                }
                val updateFolder = payload.optBoolean("update_folder", false)
                val rawFolder = if (payload.isNull("folder_id")) null else payload.optString("folder_id").ifBlank { null }
                val resolvedFolder = cacheStore.resolveId(scopeKey, rawFolder)
                    ?.takeUnless { it.startsWith(LocalCacheStore.LOCAL_FOLDER_PREFIX) }

                var updateRes = apiClient.updateNote(
                    noteId = noteId,
                    title = title,
                    body = body,
                    tags = tags,
                    updateFolder = updateFolder,
                    folderId = resolvedFolder
                )

                // If folder move failed because folder was deleted or user is collaborator, retry content-only update
                if (updateRes.isFailure && updateFolder) {
                    val code = (updateRes.exceptionOrNull() as? ApiException)?.statusCode
                    if (code == 400 || code == 403 || code == 404) {
                        updateRes = apiClient.updateNote(
                            noteId = noteId,
                            title = title,
                            body = body,
                            tags = tags,
                            updateFolder = false,
                            folderId = null
                        )
                    }
                }

                updateRes.fold(
                    onSuccess = { serverNote ->
                        cacheStore.completeUpdateNoteOp(scopeKey, op.opId, serverNote)
                        true
                    },
                    onFailure = { err ->
                        when {
                            err.isOfflineOrUnreachable() -> handleTransientFailure(op, err)
                            err.isUnauthorizedError() -> handleUnauthorizedFailure(op, err)
                            (err as? ApiException)?.statusCode in listOf(403, 404) -> {
                                // Shared note deleted or edit access revoked while offline -> rescue user's text!
                                val localCopy = cacheStore.getNote(scopeKey, noteId)
                                if (localCopy != null && (localCopy.title.isNotBlank() || localCopy.body.isNotBlank())) {
                                    val rescuedTitle = "${localCopy.title.ifBlank { "Nota" }} (copia offline)".take(250)
                                    apiClient.createNote(
                                        title = rescuedTitle,
                                        body = localCopy.body,
                                        tags = localCopy.tags,
                                        folderId = null
                                    ).onSuccess { rescued ->
                                        cacheStore.upsertSingleServerNote(scopeKey, rescued)
                                        sessionPrefs.remapNoteId(noteId, rescued.id)
                                    }
                                }
                                cacheStore.removeNoteFromCacheOnly(scopeKey, noteId)
                                cacheStore.completeOp(op.opId)
                                true
                            }
                            else -> {
                                _syncStatus.update { it.copy(lastError = err.message) }
                                cacheStore.completeOp(op.opId)
                                true
                            }
                        }
                    }
                )
            }

            SyncOpType.DELETE_NOTE -> {
                val noteId = cacheStore.resolveId(scopeKey, op.entityId) ?: op.entityId
                if (noteId.startsWith(LocalCacheStore.LOCAL_NOTE_PREFIX)) {
                    cacheStore.completeOp(op.opId)
                    return true
                }
                apiClient.deleteNote(noteId).fold(
                    onSuccess = {
                        cacheStore.completeOp(op.opId)
                        true
                    },
                    onFailure = { err ->
                        when {
                            err.isOfflineOrUnreachable() -> handleTransientFailure(op, err)
                            err.isUnauthorizedError() -> handleUnauthorizedFailure(op, err)
                            else -> {
                                cacheStore.completeOp(op.opId)
                                true
                            }
                        }
                    }
                )
            }

            SyncOpType.UPLOAD_ATTACHMENT -> {
                val noteId = cacheStore.resolveId(scopeKey, op.parentId) ?: op.parentId ?: ""
                val bytes = cacheStore.readAttachmentBytes(scopeKey, op.entityId)
                if (bytes == null || noteId.isBlank() || noteId.startsWith(LocalCacheStore.LOCAL_NOTE_PREFIX)) {
                    cacheStore.completeOp(op.opId)
                    return true
                }
                val filename = payload.optString("filename", "imagen.png")
                val contentType = payload.optString("content_type", "image/png")

                apiClient.uploadAttachment(
                    noteId = noteId,
                    filename = filename,
                    contentType = contentType,
                    bytes = bytes
                ).fold(
                    onSuccess = { serverMeta ->
                        cacheStore.completeUploadAttachmentOp(scopeKey, op.opId, op.entityId, serverMeta)
                        true
                    },
                    onFailure = { err ->
                        when {
                            err.isOfflineOrUnreachable() -> handleTransientFailure(op, err)
                            err.isUnauthorizedError() -> handleUnauthorizedFailure(op, err)
                            else -> {
                                _syncStatus.update { it.copy(lastError = err.message) }
                                cacheStore.completeOp(op.opId)
                                true
                            }
                        }
                    }
                )
            }

            SyncOpType.DELETE_ATTACHMENT -> {
                val noteId = cacheStore.resolveId(scopeKey, op.parentId) ?: op.parentId ?: ""
                val attId = cacheStore.resolveId(scopeKey, op.entityId) ?: op.entityId
                if (noteId.isBlank() || attId.startsWith(LocalCacheStore.LOCAL_ATTACHMENT_PREFIX)) {
                    cacheStore.completeOp(op.opId)
                    return true
                }
                apiClient.deleteAttachment(noteId, attId).fold(
                    onSuccess = {
                        cacheStore.completeOp(op.opId)
                        true
                    },
                    onFailure = { err ->
                        when {
                            err.isOfflineOrUnreachable() -> handleTransientFailure(op, err)
                            err.isUnauthorizedError() -> handleUnauthorizedFailure(op, err)
                            else -> {
                                cacheStore.completeOp(op.opId)
                                true
                            }
                        }
                    }
                )
            }

            SyncOpType.SHARE_NOTE -> {
                val noteId = cacheStore.resolveId(scopeKey, op.parentId) ?: op.parentId ?: ""
                val recipient = payload.optString("recipient").trim()
                val permission = payload.optString("permission", "read")
                if (noteId.isBlank() || recipient.isEmpty() || noteId.startsWith(LocalCacheStore.LOCAL_NOTE_PREFIX)) {
                    cacheStore.completeOp(op.opId)
                    return true
                }
                apiClient.shareNote(noteId, recipient, permission).fold(
                    onSuccess = { serverShare ->
                        cacheStore.completeShareNoteOp(scopeKey, op.opId, op.entityId, serverShare)
                        true
                    },
                    onFailure = { err ->
                        when {
                            err.isOfflineOrUnreachable() -> handleTransientFailure(op, err)
                            err.isUnauthorizedError() -> handleUnauthorizedFailure(op, err)
                            else -> {
                                _syncStatus.update { it.copy(lastError = err.message) }
                                cacheStore.revokeShareOfflineFirst(
                                    scopeKey = scopeKey,
                                    noteId = noteId,
                                    targetUserId = "${LocalCacheStore.PENDING_SHARE_PREFIX}${op.entityId}",
                                    currentUserId = sessionPrefs.user?.id
                                )
                                cacheStore.completeOp(op.opId)
                                true
                            }
                        }
                    }
                )
            }

            SyncOpType.REVOKE_SHARE -> {
                val noteId = cacheStore.resolveId(scopeKey, op.parentId) ?: op.parentId ?: ""
                if (noteId.isBlank() || noteId.startsWith(LocalCacheStore.LOCAL_NOTE_PREFIX)) {
                    cacheStore.completeOp(op.opId)
                    return true
                }
                apiClient.revokeNoteShare(noteId, op.entityId).fold(
                    onSuccess = {
                        cacheStore.completeOp(op.opId)
                        true
                    },
                    onFailure = { err ->
                        when {
                            err.isOfflineOrUnreachable() -> handleTransientFailure(op, err)
                            err.isUnauthorizedError() -> handleUnauthorizedFailure(op, err)
                            else -> {
                                cacheStore.completeOp(op.opId)
                                true
                            }
                        }
                    }
                )
            }
        }
    }

    private fun handleTransientFailure(op: QueuedSyncOp, err: Throwable): Boolean {
        cacheStore.releaseOpForRetry(op.opId, err.message)
        _syncStatus.update {
            it.copy(
                isOnline = false,
                isSyncing = false
            )
        }
        return false
    }

    private fun handleUnauthorizedFailure(op: QueuedSyncOp, err: Throwable): Boolean {
        cacheStore.releaseOpForRetry(op.opId, err.message)
        _syncStatus.update {
            it.copy(
                isSyncing = false,
                lastError = "Tu sesión expiró. Inicia sesión nuevamente para sincronizar."
            )
        }
        _authExpiredEvents.tryEmit(Unit)
        return false
    }

    private suspend fun pullServerSnapshotLocked(scopeKey: String) {
        val foldersDeferred = coroutineScope {
            val fJob = async { apiClient.getFolders() }
            val nJob = async { apiClient.getNotes() }
            fJob.await() to nJob.await()
        }

        val (foldersRes, notesRes) = foldersDeferred

        val failure = foldersRes.exceptionOrNull() ?: notesRes.exceptionOrNull()
        if (failure != null) {
            when {
                failure.isOfflineOrUnreachable() -> {
                    _syncStatus.update { it.copy(isOnline = false) }
                }
                failure.isUnauthorizedError() -> {
                    _authExpiredEvents.tryEmit(Unit)
                }
                else -> {
                    _syncStatus.update { it.copy(lastError = failure.message) }
                }
            }
            return
        }

        val serverFolders = foldersRes.getOrDefault(emptyList())
        val serverNotes = notesRes.getOrDefault(emptyList())

        cacheStore.mergeServerFolders(scopeKey, serverFolders)
        cacheStore.mergeServerNotes(scopeKey, sessionPrefs.user?.id, serverNotes)

        // Refresh outgoing shares for owned notes concurrently
        val ownedNotes = serverNotes.filter { it.isOwner }
        if (ownedNotes.isNotEmpty()) {
            coroutineScope {
                ownedNotes.map { owned ->
                    async {
                        apiClient.getNoteShares(owned.id).onSuccess { shares ->
                            cacheStore.mergeServerSharesForNote(scopeKey, owned.id, shares)
                        }
                    }
                }.awaitAll()
            }
        }

        _syncStatus.update {
            it.copy(
                isOnline = true,
                lastSyncedAtMillis = System.currentTimeMillis(),
                lastError = null
            )
        }
        bumpDataVersion()
    }

    private fun jsonArrayToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i).trim()
            if (v.isNotEmpty()) out.add(v)
        }
        return out
    }
}

