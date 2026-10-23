package com.hermes.client.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.client.HermesApp
import com.hermes.client.R
import com.hermes.client.data.api.SessionInfo
import com.hermes.client.ui.ds.Hermes
import com.hermes.client.ui.ds.HermesDialog
import com.hermes.client.ui.ds.HermesIconButton
import com.hermes.client.ui.ds.HermesScaffold
import com.hermes.client.ui.ds.HermesSnackbarHost
import com.hermes.client.ui.ds.HermesTextField
import com.hermes.client.ui.ds.HermesTopBar
import com.hermes.client.ui.ds.HermesTypingDots
import com.hermes.client.ui.ds.rememberHermesSnackbarState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationListScreen(
    onOpenChat: (String?, String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as HermesApp
    val vm: ConversationListViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ConversationListViewModel(app.container) as T
        }
    )
    val snackbar = rememberHermesSnackbarState()
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refresh() }
    // Auto-update the list whenever returning to this screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(vm.error) {
        vm.error?.let { snackbar.show(it); vm.dismissError() }
    }

    HermesScaffold(
        topBar = {
            HermesTopBar(title = "Hermes") {
                HermesIconButton(
                    onClick = vm::refresh,
                    desc = stringResource(R.string.refresh),
                    modifier = Modifier.testTag("refreshButton"),
                ) {
                    Image(imageVector = Icons.Filled.Refresh, contentDescription = null)
                }
                HermesIconButton(
                    onClick = onOpenSettings,
                    desc = stringResource(R.string.settings_desc),
                    modifier = Modifier.testTag("settingsButton"),
                ) {
                    Image(imageVector = Icons.Filled.Settings, contentDescription = null)
                }
            }
        },
        snackbar = { HermesSnackbarHost(snackbar) },
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                SearchField(query = query, onQuery = { query = it })
                Box(Modifier.fillMaxSize().weight(1f)) {
                    ConversationListBody(
                        loading = vm.loading,
                        sessions = vm.sessions.filterQuery(query),
                        onOpenChat = onOpenChat,
                        onAskDelete = { pendingDelete = it },
                    )
                }
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .size(56.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Hermes.colors.primary)
                    .clickable { onOpenChat(null, null) }
                    .testTag("newChat"),
            ) {
                Image(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.new_chat),
                )
            }
        }
    }

    pendingDelete?.let { id ->
        DeleteDialog(
            label = vm.sessions.find { it.key == id }?.title?.ifBlank { null }
                ?: stringResource(R.string.new_chat),
            onDismiss = { pendingDelete = null },
            onConfirm = { vm.delete(id); pendingDelete = null },
        )
    }
}

/** Drawer content: new chat + search items, then history, then quiet settings. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationDrawerContent(
    selectedSessionId: String?,
    onOpenChat: (String?, String?) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onCloseDrawer: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as HermesApp
    val vm: ConversationListViewModel = viewModel(
        key = "drawer-list",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ConversationListViewModel(app.container) as T
        }
    )
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxHeight()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        ) {
            HermesIconButton(onCloseDrawer, desc = stringResource(R.string.back), modifier = Modifier.testTag("closeDrawer")) {
                Image(imageVector = Icons.Filled.Close, contentDescription = null)
            }
        }
        // Primary drawer items: new chat, then chat search (opens the search screen).
        DrawerItemRow(
            icon = { Image(imageVector = Icons.Filled.Add, contentDescription = null) },
            label = stringResource(R.string.new_chat),
            testTag = "drawerNewChat",
            onClick = { onOpenChat(null, null) },
        )
        DrawerItemRow(
            icon = { Image(imageVector = Icons.Filled.Search, contentDescription = null) },
            label = stringResource(R.string.search_chats),
            testTag = "drawerSearch",
            onClick = onOpenSearch,
        )
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
            ConversationListBody(
                loading = vm.loading,
                sessions = vm.sessions,
                selectedKey = selectedSessionId,
                compact = true,
                onOpenChat = onOpenChat,
                onAskDelete = { pendingDelete = it },
            )
        }
        // Subtle footer: icon-sized settings, no longer a full-width button.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            HermesIconButton(
                onClick = onOpenSettings,
                desc = stringResource(R.string.settings_desc),
                modifier = Modifier.testTag("drawerSettings").size(40.dp),
            ) {
                Image(imageVector = Icons.Filled.Settings, contentDescription = null)
            }
            BasicText(
                stringResource(R.string.settings),
                style = Hermes.type.labelSmall.copy(color = Hermes.colors.onSurfaceVariant),
            )
            Spacer(Modifier.weight(1f))
            BasicText(
                "v" + com.hermes.client.BuildConfig.VERSION_NAME,
                style = Hermes.type.labelSmall.copy(
                    color = Hermes.colors.onSurfaceVariant.copy(alpha = 0.6f)
                ),
            )
        }
    }

    pendingDelete?.let { id ->
        DeleteDialog(
            label = vm.sessions.find { it.key == id }?.title?.ifBlank { null }
                ?: stringResource(R.string.new_chat),
            onDismiss = { pendingDelete = null },
            onConfirm = { vm.delete(id); pendingDelete = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerItemRow(
    icon: @Composable () -> Unit,
    label: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { icon() }
        BasicText(
            label,
            style = Hermes.type.body.copy(color = Hermes.colors.onSurface),
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** Full chat-search screen: query field plus the filtered conversation history. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationSearchScreen(
    onOpenChat: (String?, String?) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as HermesApp
    val vm: ConversationListViewModel = viewModel(
        key = "search-list",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ConversationListViewModel(app.container) as T
        }
    )
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refresh() }

    HermesScaffold(
        topBar = {
            HermesTopBar(
                title = stringResource(R.string.search_chats),
                onBack = onNavigateBack,
                backDesc = stringResource(R.string.back),
                backTestTag = "searchBack",
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            SearchField(query = query, onQuery = { query = it }, fieldTag = "searchScreenInput")
            Box(Modifier.fillMaxSize().weight(1f)) {
                ConversationListBody(
                    loading = vm.loading,
                    sessions = vm.sessions.filterQuery(query),
                    onOpenChat = onOpenChat,
                    onAskDelete = { pendingDelete = it },
                )
            }
        }
    }

    pendingDelete?.let { id ->
        DeleteDialog(
            label = vm.sessions.find { it.key == id }?.title?.ifBlank { null }
                ?: stringResource(R.string.new_chat),
            onDismiss = { pendingDelete = null },
            onConfirm = { vm.delete(id); pendingDelete = null },
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
    fieldTag: String = "searchInput",
) {
    HermesTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = stringResource(R.string.search_hint),
        singleLine = true,
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(fieldTag),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationListBody(
    loading: Boolean,
    sessions: List<SessionInfo>,
    onOpenChat: (String?, String?) -> Unit,
    onAskDelete: (String) -> Unit,
    selectedKey: String? = null,
    compact: Boolean = false,
) {
    when {
        loading && sessions.isEmpty() ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                HermesTypingDots()
            }
        sessions.isEmpty() ->
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
                BasicText(
                    stringResource(R.string.empty_conversations),
                    style = Hermes.type.body.copy(color = Hermes.colors.onSurfaceVariant),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            // Sessions without a server timestamp render with no bucket header
            // instead of a misleading label. Headers re-emit after a gap.
            var lastBucket: HistoryBucket? = null
            var bucketActive = false
            sessions.forEach { s ->
                val bucket = s.updatedAt?.let { bucketFor(it) }
                if (bucket != null && (!bucketActive || bucket != lastBucket)) {
                    lastBucket = bucket
                    bucketActive = true
                    item(key = "header-$bucket") {
                        BasicText(
                            stringResource(bucket.titleRes),
                            style = Hermes.type.labelSmall.copy(color = Hermes.colors.onSurfaceVariant),
                            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
                        )
                    }
                } else if (bucket == null) {
                    bucketActive = false
                }
                item(key = "session-${s.key.ifBlank { s.displayTitle }}") {
                    val selected = selectedKey != null && s.key == selectedKey
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (compact) 0.dp else 4.dp, vertical = 1.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) Hermes.colors.primaryContainer
                                else androidx.compose.ui.graphics.Color.Transparent
                            )
                            .combinedClickable(
                                onClick = { onOpenChat(s.key, s.title) },
                                onLongClick = { onAskDelete(s.key) },
                            )
                            .animateItem()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                BasicText(
                                    s.title?.ifBlank { null } ?: stringResource(R.string.new_chat),
                                    style = Hermes.type.body.copy(color = Hermes.colors.onSurface),
                                    maxLines = 1,
                                )
                                formatDateTime(s.updatedAt)?.let { date ->
                                    BasicText(
                                        date,
                                        style = Hermes.type.labelSmall.copy(color = Hermes.colors.onSurfaceVariant),
                                        maxLines = 1,
                                    )
                                }
                            }
                            HermesIconButton(
                                onClick = { onAskDelete(s.key) },
                                desc = stringResource(R.string.delete),
                            ) {
                                Image(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteDialog(label: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    HermesDialog(
        visible = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.delete_title),
        confirmLabel = stringResource(R.string.delete),
        onConfirm = onConfirm,
        dismissLabel = stringResource(R.string.cancel),
        onDismissAction = onDismiss,
    ) {
        BasicText(
            label.take(40),
            style = Hermes.type.body.copy(color = Hermes.colors.onSurface),
        )
    }
}

/** Short date for conversation rows. Null when the server sent no timestamp. */
private fun formatDateTime(updatedAt: Double?): String? {
    if (updatedAt == null) return null
    var epochSec = updatedAt
    while (epochSec > 1e11) epochSec /= 1000.0
    return try {
        java.time.Instant.ofEpochSecond(epochSec.toLong())
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm"))
    } catch (_: Exception) {
        null
    }
}

private fun List<SessionInfo>.filterQuery(q: String): List<SessionInfo> {
    if (q.isBlank()) return this
    return filter { it.displayTitle.contains(q, ignoreCase = true) || it.key.contains(q, ignoreCase = true) }
}

/** History sections. Undated sessions deliberately get no bucket. */
private enum class HistoryBucket(val titleRes: Int) {
    TODAY(R.string.history_today),
    WEEK(R.string.history_week),
    MONTH(R.string.history_month),
    OLDER(R.string.history_older),
}

private fun bucketFor(updatedAt: Double): HistoryBucket {
    // Server sends epoch seconds; tolerate millis/micros just in case.
    var epochSec = updatedAt
    while (epochSec > 1e11) epochSec /= 1000.0
    val ageDays = (System.currentTimeMillis() / 1000.0 - epochSec) / 86400.0
    return when {
        ageDays < 1 -> HistoryBucket.TODAY
        ageDays < 7 -> HistoryBucket.WEEK
        ageDays < 30 -> HistoryBucket.MONTH
        else -> HistoryBucket.OLDER
    }
}
