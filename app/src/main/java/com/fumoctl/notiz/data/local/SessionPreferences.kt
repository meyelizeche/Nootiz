package com.fumoctl.notiz.data.local

import android.content.Context
import android.content.SharedPreferences
import com.fumoctl.notiz.data.model.NoteReminder
import com.fumoctl.notiz.data.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class SessionPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var prefsInstance: SharedPreferences? = null

    @Volatile
    private var cachedBaseUrl: String = DEFAULT_EMULATOR_URL

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cachedUser: User? = null

    @Volatile
    private var cachedReminders: Map<String, NoteReminder> = emptyMap()

    @Volatile
    private var cachedPinnedNoteIds: List<String> = emptyList()

    companion object {
        private const val PREFS_NAME = "notiz_session_prefs"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_JWT_TOKEN = "jwt_token"
        private const val KEY_USER_JSON = "user_json"
        private const val KEY_REMINDERS_JSON = "reminders_json"
        private const val KEY_PINNED_NOTES_JSON = "pinned_notes_json"

        const val DEFAULT_EMULATOR_URL = "http://10.0.2.2:3000"

        fun normalizeBaseUrl(raw: String): String {
            var cleaned = raw.trim().trimEnd('/')
            if (cleaned.endsWith("/api")) {
                cleaned = cleaned.removeSuffix("/api").trimEnd('/')
            }
            if (cleaned.isNotEmpty() && !cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
                cleaned = "http://$cleaned"
            }
            return cleaned.ifEmpty { DEFAULT_EMULATOR_URL }
        }
    }

    init {
        ioScope.launch {
            ensureLoaded()
        }
    }

    fun ensureLoaded() {
        if (prefsInstance != null) return
        synchronized(this) {
            if (prefsInstance != null) return
            val sp = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefsInstance = sp
            cachedBaseUrl = sp.getString(KEY_BASE_URL, DEFAULT_EMULATOR_URL) ?: DEFAULT_EMULATOR_URL
            cachedToken = sp.getString(KEY_JWT_TOKEN, null)
            cachedUser = sp.getString(KEY_USER_JSON, null)?.let { parseUserJson(it) }
            cachedReminders = parseRemindersJson(sp.getString(KEY_REMINDERS_JSON, null))
            cachedPinnedNoteIds = parsePinnedNotesJson(sp.getString(KEY_PINNED_NOTES_JSON, null))
        }
    }

    var baseUrl: String
        get() = cachedBaseUrl
        set(value) {
            val normalized = normalizeBaseUrl(value)
            cachedBaseUrl = normalized
            ioScope.launch {
                ensureLoaded()
                prefsInstance?.edit()?.putString(KEY_BASE_URL, normalized)?.apply()
            }
        }

    var token: String?
        get() = cachedToken
        set(value) {
            cachedToken = value
            ioScope.launch {
                ensureLoaded()
                prefsInstance?.edit()?.putString(KEY_JWT_TOKEN, value)?.apply()
            }
        }

    var user: User?
        get() = cachedUser
        set(value) {
            cachedUser = value
            ioScope.launch {
                ensureLoaded()
                val editor = prefsInstance?.edit() ?: return@launch
                if (value == null) {
                    editor.remove(KEY_USER_JSON).apply()
                } else {
                    val obj = JSONObject().apply {
                        put("id", value.id)
                        put("username", value.username)
                        put("email", value.email)
                        put("created_at", value.createdAt)
                    }
                    editor.putString(KEY_USER_JSON, obj.toString()).apply()
                }
            }
        }

    fun saveReminder(reminder: NoteReminder) {
        val updated = cachedReminders.toMutableMap()
        updated[reminder.noteId] = reminder
        cachedReminders = updated
        ioScope.launch {
            ensureLoaded()
            val arr = JSONArray()
            updated.values.forEach { r ->
                arr.put(
                    JSONObject().apply {
                        put("noteId", r.noteId)
                        put("noteTitle", r.noteTitle)
                        put("year", r.year)
                        put("month", r.month)
                        put("dayOfMonth", r.dayOfMonth)
                        put("hour", r.hour)
                        put("minute", r.minute)
                        put("localAlert", r.localAlert)
                        put("googleCalendar", r.googleCalendar)
                    }
                )
            }
            prefsInstance?.edit()?.putString(KEY_REMINDERS_JSON, arr.toString())?.apply()
        }
    }

    fun getReminderForNote(noteId: String): NoteReminder? = cachedReminders[noteId]

    fun getPinnedNoteIds(): List<String> = cachedPinnedNoteIds

    fun setPinnedNoteIds(ids: List<String>) {
        val clean = ids.distinct()
        cachedPinnedNoteIds = clean
        ioScope.launch {
            ensureLoaded()
            val arr = JSONArray()
            clean.forEach { arr.put(it) }
            prefsInstance?.edit()?.putString(KEY_PINNED_NOTES_JSON, arr.toString())?.apply()
        }
    }

    fun remapNoteId(oldNoteId: String, newNoteId: String) {
        if (oldNoteId == newNoteId || oldNoteId.isBlank() || newNoteId.isBlank()) return
        var pinsChanged = false
        if (cachedPinnedNoteIds.contains(oldNoteId)) {
            cachedPinnedNoteIds = cachedPinnedNoteIds.map { if (it == oldNoteId) newNoteId else it }.distinct()
            pinsChanged = true
        }
        var remindersChanged = false
        val existingReminder = cachedReminders[oldNoteId]
        if (existingReminder != null) {
            val updatedMap = cachedReminders.toMutableMap()
            updatedMap.remove(oldNoteId)
            updatedMap[newNoteId] = existingReminder.copy(noteId = newNoteId)
            cachedReminders = updatedMap
            remindersChanged = true
        }
        if (pinsChanged || remindersChanged) {
            val pinsSnapshot = cachedPinnedNoteIds
            val remindersSnapshot = cachedReminders
            ioScope.launch {
                ensureLoaded()
                val editor = prefsInstance?.edit() ?: return@launch
                if (pinsChanged) {
                    val pinsArr = JSONArray()
                    pinsSnapshot.forEach { pinsArr.put(it) }
                    editor.putString(KEY_PINNED_NOTES_JSON, pinsArr.toString())
                }
                if (remindersChanged) {
                    val remArr = JSONArray()
                    remindersSnapshot.values.forEach { r ->
                        remArr.put(
                            JSONObject().apply {
                                put("noteId", r.noteId)
                                put("noteTitle", r.noteTitle)
                                put("year", r.year)
                                put("month", r.month)
                                put("dayOfMonth", r.dayOfMonth)
                                put("hour", r.hour)
                                put("minute", r.minute)
                                put("localAlert", r.localAlert)
                                put("googleCalendar", r.googleCalendar)
                            }
                        )
                    }
                    editor.putString(KEY_REMINDERS_JSON, remArr.toString())
                }
                editor.apply()
            }
        }
    }

    fun removeNoteMetadata(noteId: String) {
        var changed = false
        if (cachedPinnedNoteIds.contains(noteId)) {
            cachedPinnedNoteIds = cachedPinnedNoteIds - noteId
            changed = true
        }
        if (cachedReminders.containsKey(noteId)) {
            val updated = cachedReminders.toMutableMap()
            updated.remove(noteId)
            cachedReminders = updated
            changed = true
        }
        if (changed) {
            val pinsSnapshot = cachedPinnedNoteIds
            val remindersSnapshot = cachedReminders
            ioScope.launch {
                ensureLoaded()
                val editor = prefsInstance?.edit() ?: return@launch
                val pinsArr = JSONArray()
                pinsSnapshot.forEach { pinsArr.put(it) }
                editor.putString(KEY_PINNED_NOTES_JSON, pinsArr.toString())
                val remArr = JSONArray()
                remindersSnapshot.values.forEach { r ->
                    remArr.put(
                        JSONObject().apply {
                            put("noteId", r.noteId)
                            put("noteTitle", r.noteTitle)
                            put("year", r.year)
                            put("month", r.month)
                            put("dayOfMonth", r.dayOfMonth)
                            put("hour", r.hour)
                            put("minute", r.minute)
                            put("localAlert", r.localAlert)
                            put("googleCalendar", r.googleCalendar)
                        }
                    )
                }
                editor.putString(KEY_REMINDERS_JSON, remArr.toString())
                editor.apply()
            }
        }
    }

    fun clearAuth() {
        cachedToken = null
        cachedUser = null
        ioScope.launch {
            ensureLoaded()
            prefsInstance?.edit()
                ?.remove(KEY_JWT_TOKEN)
                ?.remove(KEY_USER_JSON)
                ?.apply()
        }
    }

    private fun parseUserJson(raw: String): User? = try {
        val obj = JSONObject(raw)
        User(
            id = obj.optString("id"),
            username = obj.optString("username"),
            email = obj.optString("email"),
            createdAt = obj.optString("created_at")
        )
    } catch (_: Exception) {
        null
    }

    private fun parseRemindersJson(raw: String?): Map<String, NoteReminder> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<String, NoteReminder>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val r = NoteReminder(
                    noteId = o.optString("noteId"),
                    noteTitle = o.optString("noteTitle"),
                    year = o.optInt("year", 2026),
                    month = o.optInt("month", 10),
                    dayOfMonth = o.optInt("dayOfMonth", 16),
                    hour = o.optInt("hour", 10),
                    minute = o.optInt("minute", 0),
                    localAlert = o.optBoolean("localAlert", true),
                    googleCalendar = o.optBoolean("googleCalendar", false)
                )
                result[r.noteId] = r
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun parsePinnedNotesJson(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val list = mutableListOf<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (id.isNotBlank()) list.add(id)
            }
        } catch (_: Exception) {
        }
        return list.distinct()
    }
}
