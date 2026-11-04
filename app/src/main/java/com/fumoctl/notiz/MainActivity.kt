package com.fumoctl.notiz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.fumoctl.notiz.data.local.SessionPreferences
import com.fumoctl.notiz.data.model.Note
import com.fumoctl.notiz.data.model.User
import com.fumoctl.notiz.data.remote.NotizApiClient
import com.fumoctl.notiz.data.remote.isUnauthorizedError
import com.fumoctl.notiz.data.sync.NotizRepository
import com.fumoctl.notiz.ui.screens.AuthScreen
import com.fumoctl.notiz.ui.screens.DashboardScreen
import com.fumoctl.notiz.ui.screens.NoteEditorScreen
import com.fumoctl.notiz.ui.theme.NotizTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface AppDestination {
    data object Auth : AppDestination
    data object Dashboard : AppDestination
    data class Editor(val note: Note?, val initialFolderId: String?) : AppDestination
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NotizTheme {
                NotizApp()
            }
        }
    }
}

@Composable
fun NotizApp() {
    val context = LocalContext.current
    val sessionPrefs = remember { SessionPreferences(context) }
    val apiClient = remember { NotizApiClient(sessionPrefs) }
    val repository = remember { NotizRepository(context, sessionPrefs, apiClient) }

    var currentUser by remember { mutableStateOf<User?>(null) }
    var destination by remember { mutableStateOf<AppDestination>(AppDestination.Auth) }

    DisposableEffect(repository, context) {
        repository.start()
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                repository.onAppResumed()
            }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            repository.stop()
        }
    }

    LaunchedEffect(repository) {
        repository.authExpiredEvents.collect {
            sessionPrefs.clearAuth()
            repository.onSessionChanged()
            currentUser = null
            destination = AppDestination.Auth
        }
    }

    // Load preferences off the main thread and verify stored token only when reachable
    LaunchedEffect(Unit) {
        val hasToken = withContext(Dispatchers.IO) {
            sessionPrefs.ensureLoaded()
            !sessionPrefs.token.isNullOrBlank()
        }
        if (hasToken) {
            currentUser = sessionPrefs.user
            destination = AppDestination.Dashboard
            repository.onSessionChanged()
            apiClient.getMe().onSuccess { verifiedUser ->
                currentUser = verifiedUser
                sessionPrefs.user = verifiedUser
                repository.onSessionChanged()
            }.onFailure { err ->
                // Only clear session on explicit HTTP 401 Unauthorized; keep session alive when offline
                if (err.isUnauthorizedError()) {
                    sessionPrefs.clearAuth()
                    repository.onSessionChanged()
                    currentUser = null
                    destination = AppDestination.Auth
                }
            }
        }
    }

    BackHandler(enabled = destination is AppDestination.Editor) {
        destination = AppDestination.Dashboard
    }

    when (val screen = destination) {
        is AppDestination.Auth -> {
            AuthScreen(
                sessionPrefs = sessionPrefs,
                apiClient = apiClient,
                onAuthenticated = { auth ->
                    currentUser = auth.user
                    repository.onSessionChanged()
                    destination = AppDestination.Dashboard
                }
            )
        }

        is AppDestination.Dashboard -> {
            DashboardScreen(
                currentUser = currentUser,
                serverUrl = sessionPrefs.baseUrl,
                sessionPrefs = sessionPrefs,
                repository = repository,
                onOpenNote = { note ->
                    destination = AppDestination.Editor(note = note, initialFolderId = note.folderId)
                },
                onCreateNewNote = { folderId ->
                    destination = AppDestination.Editor(note = null, initialFolderId = folderId)
                },
                onLogout = {
                    sessionPrefs.clearAuth()
                    repository.onSessionChanged()
                    currentUser = null
                    destination = AppDestination.Auth
                }
            )
        }

        is AppDestination.Editor -> {
            NoteEditorScreen(
                initialNote = screen.note,
                initialFolderId = screen.initialFolderId,
                currentUser = currentUser,
                sessionPrefs = sessionPrefs,
                repository = repository,
                onBack = {
                    destination = AppDestination.Dashboard
                }
            )
        }
    }
}
