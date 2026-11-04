package com.tridente.nootiz.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tridente.nootiz.R
import com.tridente.nootiz.data.local.LocalCacheStore
import com.tridente.nootiz.data.local.SessionPreferences
import com.tridente.nootiz.data.model.Folder
import com.tridente.nootiz.data.model.Note
import com.tridente.nootiz.data.model.NoteShareInfo
import com.tridente.nootiz.data.model.User
import com.tridente.nootiz.data.sync.NotizRepository
import com.tridente.nootiz.ui.theme.ErrorRedDark
import com.tridente.nootiz.ui.theme.IceBlueCard
import com.tridente.nootiz.ui.theme.IceBlueChip
import com.tridente.nootiz.ui.theme.IceBlueSurface
import com.tridente.nootiz.ui.theme.LimeAccent
import com.tridente.nootiz.ui.theme.NavyToast
import com.tridente.nootiz.ui.theme.OffWhiteBackground
import com.tridente.nootiz.ui.theme.PureWhite
import com.tridente.nootiz.ui.theme.RoyalBlue
import com.tridente.nootiz.ui.theme.TextPrimaryNavy
import com.tridente.nootiz.ui.theme.TextSecondarySlate
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private sealed class DashboardFolderSelection {
    data object All : DashboardFolderSelection()
    data object SharedByMe : DashboardFolderSelection()
    data object SharedWithMe : DashboardFolderSelection()
    data class FolderItem(val folder: Folder) : DashboardFolderSelection()
}

private sealed class DashboardDateFilter(val label: String) {
    data object All : DashboardDateFilter("Todas las fechas")
    data object RecentTodayYesterday : DashboardDateFilter("M\u00e1s recientes")
    data object Last7Days : DashboardDateFilter("\u00daltimos 7 d\u00edas")
    data object Last30Days : DashboardDateFilter("\u00daltimos 30 d\u00edas")
    data class ByMonth(val yearMonth: YearMonth, val displayLabel: String) : DashboardDateFilter(displayLabel)
    data class ByYear(val year: Int) : DashboardDateFilter("A\u00f1o $year")
}

private val SPANISH_MONTHS_FULL = listOf(
    "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
    "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"
)

private val SPANISH_MONTHS_SHORT = listOf(
    "ene", "feb", "mar", "abr", "may", "jun",
    "jul", "ago", "sep", "oct", "nov", "dic"
)

fun parseNoteLocalDateTime(isoTimestamp: String): LocalDateTime? {
    val clean = isoTimestamp.trim()
    if (clean.isBlank()) return null
    val sysZone = ZoneId.systemDefault()
    try {
        return Instant.parse(clean).atZone(sysZone).toLocalDateTime()
    } catch (_: Exception) {
    }
    try {
        return OffsetDateTime.parse(clean).atZoneSameInstant(sysZone).toLocalDateTime()
    } catch (_: Exception) {
    }
    try {
        val normalized = clean.replace(" ", "T").take(19)
        return LocalDateTime.parse(normalized)
    } catch (_: Exception) {
    }
    try {
        val dateOnly = LocalDate.parse(clean.take(10))
        return dateOnly.atStartOfDay()
    } catch (_: Exception) {
    }
    return null
}

fun formatNoteTime(isoTimestamp: String): String {
    val dt = parseNoteLocalDateTime(isoTimestamp)
    if (dt != null) {
        val today = LocalDate.now()
        val noteDate = dt.toLocalDate()
        val timePart = "%02d:%02d".format(dt.hour, dt.minute)
        return when (noteDate) {
            today -> "Hoy, $timePart"
            today.minusDays(1) -> "Ayer, $timePart"
            else -> {
                val monthShort = SPANISH_MONTHS_SHORT.getOrElse(noteDate.monthValue - 1) { "" }
                if (noteDate.year == today.year) {
                    "${noteDate.dayOfMonth} $monthShort, $timePart"
                } else {
                    "${noteDate.dayOfMonth} $monthShort ${noteDate.year}, $timePart"
                }
            }
        }
    }
    if (isoTimestamp.isBlank()) return "Hoy, 10:24"
    val tIndex = isoTimestamp.indexOf('T')
    if (tIndex != -1 && isoTimestamp.length >= tIndex + 6) {
        return "${isoTimestamp.take(10)}, ${isoTimestamp.substring(tIndex + 1, tIndex + 6)}"
    }
    return isoTimestamp.take(10)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    currentUser: User?,
    serverUrl: String,
    sessionPrefs: SessionPreferences,
    repository: NotizRepository,
    onOpenNote: (Note) -> Unit,
    onCreateNewNote: (selectedFolderId: String?) -> Unit,
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val syncStatus by repository.syncStatus.collectAsState()
    val dataVersion by repository.dataVersion.collectAsState()
    val remappedIds by repository.remappedIds.collectAsState()

    var folders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
    val outgoingSharesByNoteId = remember { mutableStateMapOf<String, List<NoteShareInfo>>() }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Dropdown filter states (Folder, Tag, Date)
    var folderSelection by remember { mutableStateOf<DashboardFolderSelection>(DashboardFolderSelection.All) }
    var showFolderDropdown by remember { mutableStateOf(false) }

    var selectedTag by remember { mutableStateOf<String?>(null) }
    var showTagDropdown by remember { mutableStateOf(false) }

    var dateFilter by remember { mutableStateOf<DashboardDateFilter>(DashboardDateFilter.All) }
    var showDateFilterMenu by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }

    // Pinned notes state (max 3)
    var pinnedNoteIds by remember { mutableStateOf(sessionPrefs.getPinnedNoteIds()) }
    var swipedOpenNoteId by remember { mutableStateOf<String?>(null) }

    // Folder dialogs & Logout confirmation
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var folderToEdit by remember { mutableStateOf<Folder?>(null) }
    var editFolderName by remember { mutableStateOf("") }
    var showLogoutPendingWarning by remember { mutableStateOf(false) }

    val pullToRefreshState = rememberPullToRefreshState()

    val selectedFolderIdForApi = (folderSelection as? DashboardFolderSelection.FolderItem)?.folder?.id

    suspend fun reloadFromCache() {
        val cached = repository.loadCachedDashboard()
        folders = cached.folders
        notes = cached.notes
        outgoingSharesByNoteId.clear()
        outgoingSharesByNoteId.putAll(cached.outgoingSharesByNote)
        pinnedNoteIds = sessionPrefs.getPinnedNoteIds()

        // Seamlessly update selected folder if an offline-created folder was synced to a server UUID
        val currentFolderSel = folderSelection as? DashboardFolderSelection.FolderItem
        if (currentFolderSel != null) {
            val mappedId = remappedIds[currentFolderSel.folder.id] ?: currentFolderSel.folder.id
            val updatedFolder = cached.folders.find { it.id == mappedId }
            if (updatedFolder != null && updatedFolder != currentFolderSel.folder) {
                folderSelection = DashboardFolderSelection.FolderItem(updatedFolder)
            }
        }
    }

    fun loadData(isPullRefresh: Boolean = false) {
        if (isPullRefresh) {
            isRefreshing = true
        }
        errorMessage = null
        scope.launch {
            reloadFromCache()
            if (notes.isNotEmpty() || folders.isNotEmpty()) {
                isLoading = false
            }
            repository.syncNow(refreshFromServer = true)
            reloadFromCache()
            isLoading = false
            isRefreshing = false
        }
    }

    LaunchedEffect(Unit) {
        loadData(isPullRefresh = false)
    }

    LaunchedEffect(dataVersion) {
        reloadFromCache()
    }

    val allAvailableTags = remember(notes, selectedTag) {
        val base = notes.flatMap { it.tags }.distinct().sorted()
        if (selectedTag != null && !base.contains(selectedTag)) {
            (listOf(selectedTag!!) + base).distinct()
        } else {
            base
        }
    }

    val availableMonths = remember(notes) {
        notes.mapNotNull { n ->
            parseNoteLocalDateTime(n.updatedAt.ifBlank { n.createdAt })?.let { YearMonth.from(it) }
        }
            .distinct()
            .sortedDescending()
    }

    val availableYears = remember(notes) {
        notes.mapNotNull { n ->
            parseNoteLocalDateTime(n.updatedAt.ifBlank { n.createdAt })?.year
        }
            .distinct()
            .sortedDescending()
    }

    val filteredNotes = remember(
        notes,
        searchQuery,
        folderSelection,
        selectedTag,
        dateFilter,
        pinnedNoteIds,
        outgoingSharesByNoteId.toMap()
    ) {
        val byFolderOrShare = when (val sel = folderSelection) {
            is DashboardFolderSelection.All -> notes
            is DashboardFolderSelection.SharedWithMe -> notes.filter { !it.isOwner }
            is DashboardFolderSelection.SharedByMe -> notes.filter { n ->
                n.isOwner && !outgoingSharesByNoteId[n.id].isNullOrEmpty()
            }
            is DashboardFolderSelection.FolderItem -> notes.filter { it.folderId == sel.folder.id }
        }

        val byTag = if (selectedTag == null) {
            byFolderOrShare
        } else {
            byFolderOrShare.filter { n ->
                n.tags.any { t -> t.equals(selectedTag, ignoreCase = true) }
            }
        }

        val today = LocalDate.now()
        val byDate = when (val df = dateFilter) {
            is DashboardDateFilter.All -> byTag
            is DashboardDateFilter.RecentTodayYesterday -> byTag.filter { n ->
                val d = parseNoteLocalDateTime(n.updatedAt.ifBlank { n.createdAt })?.toLocalDate() ?: return@filter false
                d == today || d == today.minusDays(1)
            }
            is DashboardDateFilter.Last7Days -> byTag.filter { n ->
                val d = parseNoteLocalDateTime(n.updatedAt.ifBlank { n.createdAt })?.toLocalDate() ?: return@filter false
                val diff = ChronoUnit.DAYS.between(d, today)
                diff in 0..7
            }
            is DashboardDateFilter.Last30Days -> byTag.filter { n ->
                val d = parseNoteLocalDateTime(n.updatedAt.ifBlank { n.createdAt })?.toLocalDate() ?: return@filter false
                val diff = ChronoUnit.DAYS.between(d, today)
                diff in 0..30
            }
            is DashboardDateFilter.ByMonth -> byTag.filter { n ->
                val ym = parseNoteLocalDateTime(n.updatedAt.ifBlank { n.createdAt })?.let { YearMonth.from(it) } ?: return@filter false
                ym == df.yearMonth
            }
            is DashboardDateFilter.ByYear -> byTag.filter { n ->
                val y = parseNoteLocalDateTime(n.updatedAt.ifBlank { n.createdAt })?.year ?: return@filter false
                y == df.year
            }
        }

        val bySearch = if (searchQuery.isBlank()) {
            byDate
        } else {
            val q = searchQuery.trim().lowercase()
            byDate.filter { n ->
                n.title.lowercase().contains(q) ||
                    n.body.lowercase().contains(q) ||
                    n.tags.any { t -> t.lowercase().contains(q) }
            }
        }

        // Pinned notes appear at the top in pinned order, followed by unpinned notes
        val pinnedSet = pinnedNoteIds.toSet()
        val (pinned, unpinned) = bySearch.partition { pinnedSet.contains(it.id) }
        val sortedPinned = pinned.sortedBy { pinnedNoteIds.indexOf(it.id) }
        sortedPinned + unpinned
    }

    fun togglePinNote(note: Note) {
        val currentlyPinned = pinnedNoteIds.contains(note.id)
        if (currentlyPinned) {
            val updated = pinnedNoteIds - note.id
            pinnedNoteIds = updated
            sessionPrefs.setPinnedNoteIds(updated)
            errorMessage = null
        } else {
            // Count only pinned notes that still exist in the user's current notes
            val activePinnedIds = pinnedNoteIds.filter { id -> notes.any { it.id == id } }
            if (activePinnedIds.size >= 3) {
                errorMessage = "Solo puedes fijar un m\u00e1ximo de 3 notas."
                return
            }
            val updated = activePinnedIds + note.id
            pinnedNoteIds = updated
            sessionPrefs.setPinnedNoteIds(updated)
            errorMessage = null
        }
        swipedOpenNoteId = null
    }

    fun deleteOrLeaveNote(note: Note) {
        swipedOpenNoteId = null
        scope.launch {
            val result = if (note.isOwner) {
                repository.deleteNote(note.id)
            } else {
                val myId = currentUser?.id ?: return@launch
                repository.revokeNoteShare(note.id, myId)
            }
            result.onSuccess {
                reloadFromCache()
            }.onFailure { err ->
                errorMessage = err.message ?: "No se pudo eliminar la nota"
            }
        }
    }

    Scaffold(
        containerColor = OffWhiteBackground,
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onCreateNewNote(selectedFolderIdForApi) },
                containerColor = RoyalBlue,
                contentColor = PureWhite,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.size(58.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Nueva nota",
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 22.dp)
        ) {
            Spacer(modifier = Modifier.height(14.dp))

            // Top User Pill, Sync/Offline Status Pill & Logout Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(IceBlueSurface)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(RoyalBlue),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = (currentUser?.username?.firstOrNull()?.uppercaseChar() ?: 'U').toString(),
                                color = PureWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = currentUser?.username ?: "Usuario",
                            color = TextPrimaryNavy,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Offline / Pending Sync Status Indicator Pill
                    when {
                        !syncStatus.isOnline -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(NavyToast)
                                    .clickable { repository.requestSync(refreshFromServer = true) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_cloud_off),
                                    contentDescription = "Sin conexi\u00f3n",
                                    tint = LimeAccent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (syncStatus.pendingCount > 0) {
                                        "Offline \u2022 ${syncStatus.pendingCount} pend."
                                    } else {
                                        "Modo offline"
                                    },
                                    color = PureWhite,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        syncStatus.isSyncing -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(IceBlueChip)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_cloud_sync),
                                    contentDescription = "Sincronizando",
                                    tint = RoyalBlue,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Sincronizando (${syncStatus.pendingCount})...",
                                    color = RoyalBlue,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        syncStatus.pendingCount > 0 -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(LimeAccent)
                                    .clickable { repository.requestSync(refreshFromServer = true) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_cloud_sync),
                                    contentDescription = "Pendiente de sincronizar",
                                    tint = TextPrimaryNavy,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "${syncStatus.pendingCount} por sincronizar",
                                    color = TextPrimaryNavy,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                IconButton(
                    onClick = {
                        if (syncStatus.pendingCount > 0) {
                            showLogoutPendingWarning = true
                        } else {
                            onLogout()
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = "Cerrar sesi\u00f3n",
                        tint = TextSecondarySlate
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))


            // Search Bar ("Buscar en tus notas" / "Buscar una nota")
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = "Buscar en tus notas",
                        color = TextSecondarySlate.copy(alpha = 0.75f),
                        fontSize = 14.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Buscar",
                        tint = TextSecondarySlate
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Limpiar b\u00fasqueda",
                                tint = TextSecondarySlate
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = IceBlueSurface,
                    unfocusedContainerColor = IceBlueSurface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = TextPrimaryNavy,
                    unfocusedTextColor = TextPrimaryNavy
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Dropdown Filters Row: 1) Carpetas Dropdown, 2) Etiquetas Dropdown, 3) Fechas Dropdown
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. CARPETAS DROPDOWN
                Box {
                    val isFolderActive = folderSelection !is DashboardFolderSelection.All
                    val folderLabel = when (val sel = folderSelection) {
                        is DashboardFolderSelection.All -> "Todas"
                        is DashboardFolderSelection.SharedByMe -> "Compartidas por m\u00ed"
                        is DashboardFolderSelection.SharedWithMe -> "Compartidas conmigo"
                        is DashboardFolderSelection.FolderItem -> sel.folder.name
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (isFolderActive) RoyalBlue else PureWhite)
                            .border(
                                width = 1.dp,
                                color = if (isFolderActive) RoyalBlue else IceBlueCard,
                                shape = RoundedCornerShape(50)
                            )
                            .clickable { showFolderDropdown = true }
                            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                    ) {
                        Text(
                            text = folderLabel,
                            color = if (isFolderActive) PureWhite else TextPrimaryNavy,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (folderSelection is DashboardFolderSelection.FolderItem) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Editar carpeta",
                                tint = PureWhite.copy(alpha = 0.9f),
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable {
                                        val f = (folderSelection as DashboardFolderSelection.FolderItem).folder
                                        folderToEdit = f
                                        editFolderName = f.name
                                    }
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Seleccionar carpeta",
                            tint = if (isFolderActive) PureWhite else TextPrimaryNavy,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showFolderDropdown,
                        onDismissRequest = { showFolderDropdown = false },
                        modifier = Modifier.background(PureWhite)
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Todas",
                                    color = TextPrimaryNavy,
                                    fontWeight = if (folderSelection is DashboardFolderSelection.All) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingIcon = {
                                if (folderSelection is DashboardFolderSelection.All) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = RoyalBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Spacer(modifier = Modifier.size(18.dp))
                                }
                            },
                            onClick = {
                                folderSelection = DashboardFolderSelection.All
                                showFolderDropdown = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Compartidas por m\u00ed",
                                    color = TextPrimaryNavy,
                                    fontWeight = if (folderSelection is DashboardFolderSelection.SharedByMe) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingIcon = {
                                if (folderSelection is DashboardFolderSelection.SharedByMe) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = RoyalBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Spacer(modifier = Modifier.size(18.dp))
                                }
                            },
                            onClick = {
                                folderSelection = DashboardFolderSelection.SharedByMe
                                showFolderDropdown = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Compartidas conmigo",
                                    color = TextPrimaryNavy,
                                    fontWeight = if (folderSelection is DashboardFolderSelection.SharedWithMe) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingIcon = {
                                if (folderSelection is DashboardFolderSelection.SharedWithMe) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = RoyalBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Spacer(modifier = Modifier.size(18.dp))
                                }
                            },
                            onClick = {
                                folderSelection = DashboardFolderSelection.SharedWithMe
                                showFolderDropdown = false
                            }
                        )

                        if (folders.isNotEmpty()) {
                            HorizontalDivider(color = IceBlueCard)
                            Text(
                                text = "Mis carpetas",
                                color = TextSecondarySlate,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            folders.forEach { folder ->
                                val isSelected = (folderSelection as? DashboardFolderSelection.FolderItem)?.folder?.id == folder.id
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = folder.name,
                                            color = TextPrimaryNavy,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    leadingIcon = {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = RoyalBlue,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.size(18.dp))
                                        }
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = {
                                                showFolderDropdown = false
                                                folderToEdit = folder
                                                editFolderName = folder.name
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Gestionar carpeta",
                                                tint = TextSecondarySlate,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    },
                                    onClick = {
                                        folderSelection = DashboardFolderSelection.FolderItem(folder)
                                        showFolderDropdown = false
                                    }
                                )
                            }
                        }

                        HorizontalDivider(color = IceBlueCard)
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Nueva carpeta...",
                                    color = RoyalBlue,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = RoyalBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                showFolderDropdown = false
                                newFolderName = ""
                                showCreateFolderDialog = true
                            }
                        )
                    }
                }

                // 2. ETIQUETAS (TAGS) DROPDOWN
                Box {
                    val isTagActive = selectedTag != null
                    val tagLabel = if (selectedTag != null) "#$selectedTag" else "Etiquetas"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (isTagActive) RoyalBlue else PureWhite)
                            .border(
                                width = 1.dp,
                                color = if (isTagActive) RoyalBlue else IceBlueCard,
                                shape = RoundedCornerShape(50)
                            )
                            .clickable { showTagDropdown = true }
                            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                    ) {
                        Text(
                            text = tagLabel,
                            color = if (isTagActive) PureWhite else TextPrimaryNavy,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Seleccionar etiqueta",
                            tint = if (isTagActive) PureWhite else TextPrimaryNavy,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showTagDropdown,
                        onDismissRequest = { showTagDropdown = false },
                        modifier = Modifier.background(PureWhite)
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Todas las etiquetas",
                                    color = TextPrimaryNavy,
                                    fontWeight = if (selectedTag == null) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingIcon = {
                                if (selectedTag == null) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = RoyalBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Spacer(modifier = Modifier.size(18.dp))
                                }
                            },
                            onClick = {
                                selectedTag = null
                                showTagDropdown = false
                            }
                        )
                        if (allAvailableTags.isNotEmpty()) {
                            HorizontalDivider(color = IceBlueCard)
                            allAvailableTags.forEach { tag ->
                                val isSelected = selectedTag == tag
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "#$tag",
                                            color = TextPrimaryNavy,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    leadingIcon = {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = RoyalBlue,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.size(18.dp))
                                        }
                                    },
                                    onClick = {
                                        selectedTag = if (isSelected) null else tag
                                        showTagDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                // 3. FECHAS DROPDOWN
                Box {
                    val isDateActive = dateFilter != DashboardDateFilter.All
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (isDateActive) RoyalBlue else PureWhite)
                            .border(
                                width = 1.dp,
                                color = if (isDateActive) RoyalBlue else IceBlueCard,
                                shape = RoundedCornerShape(50)
                            )
                            .clickable { showDateFilterMenu = true }
                            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = "Filtrar por fecha",
                            tint = if (isDateActive) PureWhite else RoyalBlue,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = dateFilter.label,
                            color = if (isDateActive) PureWhite else TextPrimaryNavy,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = if (isDateActive) PureWhite else TextPrimaryNavy,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showDateFilterMenu,
                        onDismissRequest = { showDateFilterMenu = false },
                        modifier = Modifier.background(PureWhite)
                    ) {
                        val presetFilters = listOf(
                            DashboardDateFilter.All,
                            DashboardDateFilter.RecentTodayYesterday,
                            DashboardDateFilter.Last7Days,
                            DashboardDateFilter.Last30Days
                        )
                        presetFilters.forEach { option ->
                            val isSelected = dateFilter == option
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = option.label,
                                        color = TextPrimaryNavy,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                leadingIcon = {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = RoyalBlue,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.size(18.dp))
                                    }
                                },
                                onClick = {
                                    dateFilter = option
                                    showDateFilterMenu = false
                                }
                            )
                        }

                        if (availableMonths.isNotEmpty()) {
                            HorizontalDivider(color = IceBlueCard)
                            Text(
                                text = "Por mes",
                                color = TextSecondarySlate,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            availableMonths.forEach { ym ->
                                val monthName = SPANISH_MONTHS_FULL.getOrElse(ym.monthValue - 1) { "" }
                                val label = "$monthName ${ym.year}"
                                val option = DashboardDateFilter.ByMonth(ym, label)
                                val isSelected = (dateFilter as? DashboardDateFilter.ByMonth)?.yearMonth == ym
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = label,
                                            color = TextPrimaryNavy,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    leadingIcon = {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = RoyalBlue,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.size(18.dp))
                                        }
                                    },
                                    onClick = {
                                        dateFilter = option
                                        showDateFilterMenu = false
                                    }
                                )
                            }
                        }

                        if (availableYears.isNotEmpty()) {
                            HorizontalDivider(color = IceBlueCard)
                            Text(
                                text = "Por a\u00f1o",
                                color = TextSecondarySlate,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            availableYears.forEach { yr ->
                                val option = DashboardDateFilter.ByYear(yr)
                                val isSelected = (dateFilter as? DashboardDateFilter.ByYear)?.year == yr
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option.label,
                                            color = TextPrimaryNavy,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    leadingIcon = {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = RoyalBlue,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.size(18.dp))
                                        }
                                    },
                                    onClick = {
                                        dateFilter = option
                                        showDateFilterMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (errorMessage != null) {
                Surface(
                    color = ErrorRedDark.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            color = ErrorRedDark,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { errorMessage = null },
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cerrar aviso",
                                tint = ErrorRedDark,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Notes List with Pull-to-Refresh + Swipe-left actions (Pin + Delete)
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { loadData(isPullRefresh = true) },
                state = pullToRefreshState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullToRefreshState,
                        isRefreshing = isRefreshing,
                        containerColor = PureWhite,
                        color = RoyalBlue,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (isLoading && !isRefreshing && notes.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = RoyalBlue)
                    }
                } else if (filteredNotes.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = when {
                                    searchQuery.isNotBlank() -> "No se encontraron notas para \"$searchQuery\""
                                    dateFilter != DashboardDateFilter.All -> "No hay notas en \"${dateFilter.label}\""
                                    folderSelection is DashboardFolderSelection.SharedByMe -> "A\u00fan no est\u00e1s compartiendo ninguna nota"
                                    folderSelection is DashboardFolderSelection.SharedWithMe -> "Nadie ha compartido notas contigo a\u00fan"
                                    else -> "A\u00fan no hay notas aqu\u00ed"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimaryNavy
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Toca el bot\u00f3n + o desliza hacia abajo para actualizar.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondarySlate
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(filteredNotes, key = { _, n -> n.id }) { _, note ->
                            val folderName = folders.find { it.id == note.folderId }?.name
                            val outgoingShares = outgoingSharesByNoteId[note.id].orEmpty()
                            val isPinned = pinnedNoteIds.contains(note.id)
                            val isPendingSync = syncStatus.pendingNoteIds.contains(note.id) ||
                                LocalCacheStore.isLocalId(note.id)
                            SwipeableNoteDashboardCard(
                                note = note,
                                folderName = folderName,
                                outgoingShares = outgoingShares,
                                isPinned = isPinned,
                                isPendingSync = isPendingSync,
                                isSwipedOpen = swipedOpenNoteId == note.id,
                                onSwipeOpenChanged = { open ->
                                    swipedOpenNoteId = if (open) note.id else null
                                },
                                onTogglePin = { togglePinNote(note) },
                                onDeleteNote = { deleteOrLeaveNote(note) },
                                onClick = {
                                    if (swipedOpenNoteId != null) {
                                        swipedOpenNoteId = null
                                    } else {
                                        onOpenNote(note)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Create Folder Dialog
    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Nueva carpeta", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    placeholder = { Text("Ej. Trabajo, Proyectos...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = newFolderName.trim()
                        if (clean.isEmpty()) return@Button
                        showCreateFolderDialog = false
                        scope.launch {
                            repository.createFolder(clean).onSuccess { created ->
                                reloadFromCache()
                                folderSelection = DashboardFolderSelection.FolderItem(created)
                            }.onFailure { err ->
                                errorMessage = err.message
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue)
                ) {
                    Text("Crear", color = PureWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Edit / Delete Folder Dialog
    folderToEdit?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToEdit = null },
            title = { Text("Gestionar carpeta", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editFolderName,
                        onValueChange = { editFolderName = it },
                        label = { Text("Nombre de la carpeta") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        onClick = {
                            folderToEdit = null
                            scope.launch {
                                repository.deleteFolder(folder.id).onSuccess {
                                    if ((folderSelection as? DashboardFolderSelection.FolderItem)?.folder?.id == folder.id) {
                                        folderSelection = DashboardFolderSelection.All
                                    }
                                    reloadFromCache()
                                }.onFailure { err ->
                                    errorMessage = err.message
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = ErrorRedDark
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Eliminar carpeta (conserva sus notas)", color = ErrorRedDark)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = editFolderName.trim()
                        if (clean.isEmpty()) return@Button
                        folderToEdit = null
                        scope.launch {
                            repository.renameFolder(folder.id, clean).onSuccess { updated ->
                                if ((folderSelection as? DashboardFolderSelection.FolderItem)?.folder?.id == folder.id) {
                                    folderSelection = DashboardFolderSelection.FolderItem(updated)
                                }
                                reloadFromCache()
                            }.onFailure { err ->
                                errorMessage = err.message
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue)
                ) {
                    Text("Guardar", color = PureWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToEdit = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Logout Warning Dialog when offline changes are pending sync
    if (showLogoutPendingWarning) {
        AlertDialog(
            onDismissRequest = { showLogoutPendingWarning = false },
            title = { Text("Cambios pendientes de sincronizar", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Tienes ${syncStatus.pendingCount} cambio(s) guardado(s) localmente sin conexi\u00f3n. " +
                        "Se sincronizar\u00e1n autom\u00e1ticamente en cuanto vuelvas a iniciar sesi\u00f3n con esta cuenta y tengas red, " +
                        "pero te recomendamos conectar antes de salir."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutPendingWarning = false
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRedDark)
                ) {
                    Text("Cerrar sesi\u00f3n", color = PureWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutPendingWarning = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun SwipeableNoteDashboardCard(
    note: Note,
    folderName: String?,
    outgoingShares: List<NoteShareInfo>,
    isPinned: Boolean,
    isPendingSync: Boolean,
    isSwipedOpen: Boolean,
    onSwipeOpenChanged: (Boolean) -> Unit,
    onTogglePin: () -> Unit,
    onDeleteNote: () -> Unit,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val maxRevealPx = remember(density) { with(density) { 128.dp.toPx() } }
    var rawOffsetX by remember(note.id) { mutableFloatStateOf(0f) }

    LaunchedEffect(isSwipedOpen, maxRevealPx) {
        rawOffsetX = if (isSwipedOpen) -maxRevealPx else 0f
    }

    val animatedOffsetX by animateFloatAsState(
        targetValue = rawOffsetX,
        label = "swipeOffset"
    )

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd
    ) {
        // Background circular action buttons revealed when swiping left (matching reference image: Pin + Delete)
        Row(
            modifier = Modifier
                .padding(end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pin button (Blue circle with pushpin icon)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (isPinned) RoyalBlue else Color(0xFF3293FC))
                    .clickable(onClick = onTogglePin),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_push_pin),
                    contentDescription = if (isPinned) "Desfijar nota" else "Fijar nota",
                    tint = PureWhite,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Delete button (Red circle with trash icon)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFE4438))
                    .clickable(onClick = onDeleteNote),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Eliminar nota",
                    tint = PureWhite,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Foreground Note Card that slides left
        Box(
            modifier = Modifier
                .offset { IntOffset(animatedOffsetX.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val next = (rawOffsetX + delta).coerceIn(-maxRevealPx, 0f)
                        rawOffsetX = next
                    },
                    onDragStopped = {
                        val shouldOpen = rawOffsetX < -maxRevealPx * 0.35f
                        rawOffsetX = if (shouldOpen) -maxRevealPx else 0f
                        onSwipeOpenChanged(shouldOpen)
                    }
                )
        ) {
            NoteDashboardCard(
                note = note,
                folderName = folderName,
                outgoingShares = outgoingShares,
                isPinned = isPinned,
                isPendingSync = isPendingSync,
                onClick = onClick
            )
        }
    }
}

@Composable
private fun NoteDashboardCard(
    note: Note,
    folderName: String?,
    outgoingShares: List<NoteShareInfo>,
    isPinned: Boolean,
    isPendingSync: Boolean,
    onClick: () -> Unit
) {
    val isSharedByMe = note.isOwner && outgoingShares.isNotEmpty()
    val isSharedWithMe = !note.isOwner

    val cleanPreview = remember(note.body) {
        note.body
            .lines()
            .map { rawLine ->
                var w = rawLine.trimStart()
                when {
                    w.startsWith("- [ ] ") -> w = w.removePrefix("- [ ] ")
                    w.startsWith("- [x] ") || w.startsWith("- [X] ") -> w = w.substring(6)
                }
                when {
                    w.startsWith("### ") -> w = w.removePrefix("### ")
                    w.startsWith("## ") -> w = w.removePrefix("## ")
                    w.startsWith("# ") -> w = w.removePrefix("# ")
                }
                w.removePrefix("\u200B").trim()
            }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Sin contenido adicional" }
    }

    // All notes have the exact same uniform #E7F0F7 (IceBlueCard) background without any border
    Surface(
        color = IceBlueCard,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isPinned) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_push_pin),
                            contentDescription = "Nota fijada",
                            tint = RoyalBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = note.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimaryNavy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = formatNoteTime(note.updatedAt.ifBlank { note.createdAt }),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimaryNavy.copy(alpha = 0.65f)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = cleanPreview,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimaryNavy.copy(alpha = 0.82f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (folderName != null || note.tags.isNotEmpty() || isSharedWithMe || isSharedByMe || isPendingSync) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isPendingSync) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(PureWhite.copy(alpha = 0.85f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_cloud_sync),
                                contentDescription = null,
                                tint = RoyalBlue,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Pendiente",
                                color = RoyalBlue,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    // 1. BADGE FOR NOTES SHARED WITH ME (incoming share)
                    if (isSharedWithMe) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(NavyToast)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = LimeAccent,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "De @${note.ownerUsername} \u2022 ${if (note.accessRole == "edit") "Editor" else "Lector"}",
                                color = PureWhite,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // 2. BADGE FOR NOTES SHARED BY ME (outgoing share)
                    if (isSharedByMe) {
                        val recipientsSummary = if (outgoingShares.size == 1) {
                            "Compartida con @${outgoingShares.first().username}"
                        } else {
                            "Compartida con @${outgoingShares.first().username} +${outgoingShares.size - 1}"
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(LimeAccent)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                tint = TextPrimaryNavy,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = recipientsSummary,
                                color = TextPrimaryNavy,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (folderName != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(PureWhite.copy(alpha = 0.65f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = folderName,
                                color = TextPrimaryNavy,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    note.tags.take(3).forEach { tag ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(PureWhite.copy(alpha = 0.55f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "#$tag",
                                color = TextPrimaryNavy,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}
