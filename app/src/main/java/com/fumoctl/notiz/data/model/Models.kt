package com.fumoctl.notiz.data.model

data class User(
    val id: String,
    val username: String,
    val email: String,
    val createdAt: String
)

data class AuthResponse(
    val token: String,
    val user: User
)

data class Folder(
    val id: String,
    val userId: String,
    val name: String,
    val createdAt: String
)

data class Note(
    val id: String,
    val ownerId: String,
    val ownerUsername: String,
    val folderId: String?,
    val title: String,
    val body: String,
    val tags: List<String>,
    val accessRole: String, // "owner", "edit", "read"
    val createdAt: String,
    val updatedAt: String
) {
    val canEdit: Boolean
        get() = accessRole == "owner" || accessRole == "edit"

    val isOwner: Boolean
        get() = accessRole == "owner"
}

data class NoteShareInfo(
    val noteId: String,
    val sharedWithUserId: String,
    val username: String,
    val email: String,
    val permission: String, // "read" | "edit"
    val createdAt: String
)

data class AttachmentMetadata(
    val id: String,
    val noteId: String,
    val uploaderId: String,
    val filename: String,
    val contentType: String,
    val sizeBytes: Int,
    val url: String,
    val markdown: String,
    val createdAt: String
)

data class NoteReminder(
    val noteId: String,
    val noteTitle: String,
    val year: Int,
    val month: Int, // 1..12
    val dayOfMonth: Int,
    val hour: Int,
    val minute: Int,
    val localAlert: Boolean,
    val googleCalendar: Boolean
)

data class SyncStatus(
    val isOnline: Boolean = true,
    val isSyncing: Boolean = false,
    val pendingCount: Int = 0,
    val pendingNoteIds: Set<String> = emptySet(),
    val lastSyncedAtMillis: Long? = null,
    val lastError: String? = null
)
