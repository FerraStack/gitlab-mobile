package com.cboyxd.gitlab.ui

import android.content.Context
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import okhttp3.HttpUrl.Companion.toHttpUrl
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.CallMerge
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemColors
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cboyxd.gitlab.data.api.normalizeBaseUrl
import com.cboyxd.gitlab.openExternalUrl
import com.cboyxd.gitlab.data.auth.GitLabAuth
import com.cboyxd.gitlab.ui.components.EmptyState
import com.cboyxd.gitlab.ui.components.ImageAuth
import com.cboyxd.gitlab.ui.components.LocalImageAuth
import com.cboyxd.gitlab.ui.components.FullScreenState
import com.cboyxd.gitlab.ui.components.GitLabTanukiIcon
import com.cboyxd.gitlab.ui.components.GlDivider
import com.cboyxd.gitlab.ui.components.GlRow
import com.cboyxd.gitlab.ui.components.GlSectionHeader
import com.cboyxd.gitlab.ui.screens.FileViewerScreen
import com.cboyxd.gitlab.ui.screens.RunPipelineScreen
import com.cboyxd.gitlab.ui.screens.StorageErrorState
import com.cboyxd.gitlab.ui.viewmodel.GitLabUiState
import com.cboyxd.gitlab.ui.viewmodel.ProjectSection
import com.cboyxd.gitlab.ui.viewmodel.emptyFeed
import com.cboyxd.gitlab.data.model.GitLabLabel
import com.cboyxd.gitlab.ui.components.MarkdownContext
import com.cboyxd.gitlab.ui.screens.UserDetailScreen
import com.cboyxd.gitlab.ui.screens.SearchScreen
import com.cboyxd.gitlab.ui.screens.ReleaseDetailScreen
import com.cboyxd.gitlab.ui.screens.ProjectSectionScreen
import com.cboyxd.gitlab.ui.screens.ProjectCodeScreen
import com.cboyxd.gitlab.ui.screens.PipelineDetailScreen
import com.cboyxd.gitlab.ui.screens.JobDetailScreen
import com.cboyxd.gitlab.ui.screens.GroupDetailScreen
import com.cboyxd.gitlab.ui.screens.CommitDetailScreen
import com.cboyxd.gitlab.ui.screens.GroupsScreen
import com.cboyxd.gitlab.ui.screens.HomeScreen
import com.cboyxd.gitlab.ui.screens.HomeUiSlice
import com.cboyxd.gitlab.ui.screens.InboxScreen
import com.cboyxd.gitlab.ui.screens.InboxUiSlice
import com.cboyxd.gitlab.ui.screens.InstancesScreen
import com.cboyxd.gitlab.ui.screens.IssueDetailScreen
import com.cboyxd.gitlab.ui.screens.IssuesScreen
import com.cboyxd.gitlab.ui.screens.MergeRequestDetailScreen
import com.cboyxd.gitlab.ui.screens.MergeRequestsScreen
import com.cboyxd.gitlab.ui.screens.PipelinesScreen
import com.cboyxd.gitlab.ui.screens.ProjectDetailScreen
import com.cboyxd.gitlab.ui.screens.ProjectsScreen
import com.cboyxd.gitlab.ui.screens.UserProfileScreen
import com.cboyxd.gitlab.ui.theme.Gl
import com.cboyxd.gitlab.ui.theme.GlMotion
import com.cboyxd.gitlab.ui.theme.glTween
import com.cboyxd.gitlab.ui.viewmodel.BottomTab
import com.cboyxd.gitlab.ui.viewmodel.GitLabViewModel
import com.cboyxd.gitlab.ui.viewmodel.ScreenDestination
import com.cboyxd.gitlab.ui.screens.NotificationDiagnosticsRoute
import com.cboyxd.gitlab.ui.screens.NotificationSettingsRoute
import kotlinx.coroutines.launch

internal val GlMaxContentWidth = 840.dp
internal val GlMaxTwoPaneWidth = 1280.dp

@Immutable
private data class NavKey(
  val destination: ScreenDestination,
  val tab: BottomTab,
  val depth: Int,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun MainScreen(viewModel: GitLabViewModel, onExit: () -> Unit) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val screenWidthDp = LocalConfiguration.current.screenWidthDp
  val useRail = screenWidthDp >= 600
  val isExpanded = screenWidthDp >= 840
  val snackbarHostState = remember { SnackbarHostState() }
  var showSwitcher by rememberSaveable { mutableStateOf(false) }
  var openAddServer by rememberSaveable { mutableStateOf(false) }
  val c = Gl.colors
  var showOpening by remember { mutableStateOf(false) }
  LaunchedEffect(uiState.pendingDestination) {
    showOpening = false
    if (uiState.pendingDestination != null) {
      delay(400)
      showOpening = true
    }
  }

  LaunchedEffect(viewModel) {
    launch { viewModel.externalLinks.collect { openUrl(context, it) } }
    var snackbarJob: kotlinx.coroutines.Job? = null
    viewModel.messages.collect { message ->
      snackbarJob?.cancel()
      snackbarHostState.currentSnackbarData?.dismiss()
      snackbarJob = launch {
        val result = snackbarHostState.showSnackbar(
          message = message.text,
          actionLabel = message.actionLabel,
          withDismissAction = message.isError,
          duration = if (message.isError) SnackbarDuration.Indefinite else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) message.action?.invoke()
      }
    }
  }

  BackHandler(enabled = uiState.pendingDestination != null || uiState.destination !is ScreenDestination.MainTabs || uiState.currentTab != BottomTab.HOME) {
    if (!viewModel.navigateBack()) {
      if (uiState.currentTab != BottomTab.HOME) viewModel.switchTab(BottomTab.HOME) else onExit()
    }
  }

  val openServers: (Boolean) -> Unit = remember(viewModel) {
    { add ->
      openAddServer = add
      viewModel.navigateTo(ScreenDestination.Instances)
    }
  }
  val openSwitcher: () -> Unit = remember { { showSwitcher = true } }
  val onAddServerConsumed: () -> Unit = remember { { openAddServer = false } }

  val imageAuth = remember(uiState.activeInstance) {
    uiState.activeInstance?.let { instance ->
      val baseUrl = normalizeBaseUrl(instance.url).toHttpUrl().toString()
      val token = instance.token.trim()
      if (instance.authKind == GitLabAuth.KIND_OAUTH || token.startsWith("Bearer ", ignoreCase = true)) {
        val value = if (token.startsWith("Bearer ", ignoreCase = true)) token.substringAfter(' ').trim() else token
        ImageAuth(baseUrl, instance.id, "Authorization", "Bearer $value")
      } else {
        ImageAuth(baseUrl, instance.id, "PRIVATE-TOKEN", token)
      }
    }
  }

  val showTopNav = uiState.destination is ScreenDestination.MainTabs && uiState.bootstrapped && uiState.storageError == null

  CompositionLocalProvider(LocalImageAuth provides imageAuth) {
  Scaffold(
    // Exposes test tags as resource ids so UI Automator (and screenshot redaction) can find them.
    modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
    containerColor = c.bg,
    snackbarHost = {
      SnackbarHost(snackbarHostState) { data ->
        Snackbar(
          snackbarData = data,
          containerColor = c.textPrimary,
          contentColor = c.bg,
          actionColor = c.bg,
          dismissActionContentColor = c.bg,
          shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        )
      }
    },
    bottomBar = {
      if (showTopNav && !useRail) {
        Column {
          GlDivider()
          NavigationBar(containerColor = c.surface, tonalElevation = 0.dp, modifier = Modifier.testTag("main_bottom_nav")) {
            val itemColors = NavigationBarItemDefaults.colors(
              selectedIconColor = c.accent,
              selectedTextColor = c.textPrimary,
              unselectedIconColor = c.textSecondary,
              unselectedTextColor = c.textPrimary,
              indicatorColor = c.accentBg,
            )
            TabItem(BottomTab.HOME, uiState.currentTab, Icons.Filled.Home, Icons.Outlined.Home, itemColors, viewModel::switchTab)
            TabItem(BottomTab.PROJECTS, uiState.currentTab, Icons.Filled.Folder, Icons.Outlined.Folder, itemColors, viewModel::switchTab)
            NavigationBarItem(
              selected = uiState.currentTab == BottomTab.INBOX,
              onClick = { viewModel.switchTab(BottomTab.INBOX) },
              icon = { InboxTabIcon(uiState.currentTab == BottomTab.INBOX, uiState.unreadCount) },
              label = { Text(BottomTab.INBOX.title, style = Gl.type.badge) },
              colors = itemColors,
              modifier = Modifier
                .testTag("nav_item_inbox")
                .semantics { contentDescription = if (uiState.unreadCount > 0) "Inbox, ${uiState.unreadCount} to-dos" else "Inbox" },
            )
          }
        }
      }
    },
  ) { innerPadding ->
    Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      if (showTopNav && useRail) {
        NavigationRail(
          containerColor = c.surface,
          windowInsets = WindowInsets(0, 0, 0, 0),
          modifier = Modifier.testTag("main_nav_rail"),
        ) {
          val railColors = NavigationRailItemDefaults.colors(
            selectedIconColor = c.accent,
            selectedTextColor = c.textPrimary,
            unselectedIconColor = c.textSecondary,
            unselectedTextColor = c.textPrimary,
            indicatorColor = c.accentBg,
          )
          Spacer(Modifier.size(8.dp))
          RailTabItem(BottomTab.HOME, uiState.currentTab, Icons.Filled.Home, Icons.Outlined.Home, railColors, viewModel::switchTab)
          RailTabItem(BottomTab.PROJECTS, uiState.currentTab, Icons.Filled.Folder, Icons.Outlined.Folder, railColors, viewModel::switchTab)
          NavigationRailItem(
            selected = uiState.currentTab == BottomTab.INBOX,
            onClick = { viewModel.switchTab(BottomTab.INBOX) },
            icon = { InboxTabIcon(uiState.currentTab == BottomTab.INBOX, uiState.unreadCount) },
            label = { Text(BottomTab.INBOX.title, style = Gl.type.badge) },
            colors = railColors,
            modifier = Modifier
              .testTag("nav_item_inbox")
              .semantics { contentDescription = if (uiState.unreadCount > 0) "Inbox, ${uiState.unreadCount} to-dos" else "Inbox" },
          )
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
      }

      Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
        when {
          !uiState.bootstrapped -> Unit
          uiState.storageError != null -> FullScreenState {
            StorageErrorState(
              message = uiState.storageError ?: "",
              onRetry = viewModel::retryBootstrap,
              onReset = viewModel::resetSavedAccounts,
            )
          }
          else -> {
            val reduced = Gl.reducedMotion
            // Key AnimatedContent only on route/tab/depth so data updates never re-trigger screen transitions
            // or recompose exiting screens.
            val presentedState = remember { mutableStateOf(uiState) }
            val activeKeyState = remember {
              mutableStateOf(NavKey(uiState.destination, uiState.currentTab, uiState.backStack.size))
            }
            if (uiState.pendingDestination == null) {
              presentedState.value = uiState
              val nextKey = NavKey(uiState.destination, uiState.currentTab, uiState.backStack.size)
              if (activeKeyState.value != nextKey) activeKeyState.value = nextKey
            }
            val activeKey = activeKeyState.value
            AnimatedContent(
              targetState = activeKey,
              contentKey = { it.destination to it.tab },
              modifier = Modifier.fillMaxSize(),
              transitionSpec = {
                if (reduced) {
                  fadeIn(glTween(GlMotion.MEDIUM, true)) togetherWith fadeOut(glTween(GlMotion.MEDIUM, true))
                } else {
                  val direction = when {
                    targetState.depth > initialState.depth -> 1
                    targetState.depth < initialState.depth -> -1
                    else -> if (targetState.tab.ordinal >= initialState.tab.ordinal) 1 else -1
                  }
                  ((slideInHorizontally(tween(340, easing = GlMotion.Standard)) { direction * it / 7 } +
                    fadeIn(tween(280, delayMillis = 50))) togetherWith
                    (slideOutHorizontally(tween(340, easing = GlMotion.Standard)) { -direction * it / 12 } +
                      fadeOut(tween(240)))).using(SizeTransform(clip = false))
                }
              },
              label = "screen",
            ) { navKey ->
              val sceneState = remember(navKey) { mutableStateOf(presentedState.value) }
              if (navKey == activeKey) sceneState.value = presentedState.value
              Destination(
                uiState = sceneState.value,
                destination = navKey.destination,
                tab = navKey.tab,
                isExpanded = isExpanded,
                viewModel = viewModel,
                context = context,
                openSwitcher = openSwitcher,
                openServers = openServers,
                openAddServer = openAddServer,
                onAddServerConsumed = onAddServerConsumed,
              )
            }
          }
        }
      if (showOpening && uiState.pendingDestination != null) {
        Surface(
          modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
          shape = RoundedCornerShape(16.dp),
          color = c.surface,
          tonalElevation = 4.dp,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            // Polite live region: screen readers hear that the next screen is still loading.
            Text(
              "Opening…",
              style = Gl.type.meta,
              color = c.textSecondary,
              modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = { viewModel.navigateBack() }) {
              Text("Cancel", style = Gl.type.label, color = c.accent)
            }
          }
        }
      }
    }
  }
  }
  }

  if (showSwitcher) {
    InstanceSwitcherSheet(
      viewModel = viewModel,
      onDismiss = { showSwitcher = false },
      onManage = { add ->
        showSwitcher = false
        openServers(add)
      },
    )
  }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
  tab: BottomTab,
  current: BottomTab,
  selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  colors: androidx.compose.material3.NavigationBarItemColors,
  onSelect: (BottomTab) -> Unit,
) {
  NavigationBarItem(
    selected = current == tab,
    onClick = { onSelect(tab) },
    icon = { Icon(if (current == tab) selectedIcon else icon, contentDescription = null) },
    label = { Text(tab.title, style = Gl.type.badge) },
    colors = colors,
    modifier = Modifier.testTag("nav_item_${tab.name.lowercase()}"),
  )
}

@Composable
private fun RailTabItem(
  tab: BottomTab,
  current: BottomTab,
  selectedIcon: ImageVector,
  icon: ImageVector,
  colors: NavigationRailItemColors,
  onSelect: (BottomTab) -> Unit,
) {
  NavigationRailItem(
    selected = current == tab,
    onClick = { onSelect(tab) },
    icon = { Icon(if (current == tab) selectedIcon else icon, contentDescription = null) },
    label = { Text(tab.title, style = Gl.type.badge) },
    colors = colors,
    modifier = Modifier.testTag("nav_item_${tab.name.lowercase()}"),
  )
}

@Composable
private fun InboxTabIcon(selected: Boolean, unread: Int) {
  val c = Gl.colors
  BadgedBox(badge = {
    if (unread > 0) {
      Badge(containerColor = c.info, contentColor = c.surface) {
        Text(if (unread > 99) "99+" else unread.toString(), style = Gl.type.badge)
      }
    }
  }) {
    Icon(if (selected) Icons.Filled.Inbox else Icons.Outlined.Inbox, contentDescription = null)
  }
}

@Composable
private fun Destination(
  uiState: GitLabUiState,
  destination: ScreenDestination,
  tab: BottomTab,
  isExpanded: Boolean,
  viewModel: GitLabViewModel,
  context: Context,
  openSwitcher: () -> Unit,
  openServers: (add: Boolean) -> Unit,
  openAddServer: Boolean,
  onAddServerConsumed: () -> Unit,
) {
  val c = Gl.colors
  val back: () -> Unit = remember(viewModel) { { viewModel.navigateBack() } }
  val browser: (String) -> Unit = remember(context) { { openUrl(context, it) } }
  val onLink: (String) -> Boolean = remember(viewModel) { viewModel.explore::handleLink }
  val refreshDashboard: () -> Unit = remember(viewModel) { { viewModel.refreshDashboard(userInitiated = true) } }
  val addServer: () -> Unit = remember(openServers) { { openServers(true) } }
  val project = uiState.project
  // Relative links and images in project documents resolve against the project and current ref.
  val projectMarkdown = remember(project.webUrl, project.effectiveRef, onLink) {
    MarkdownContext(
      webBase = project.webUrl,
      rawBase = project.webUrl?.let { web -> project.effectiveRef?.let { "$web/-/raw/$it/" } },
      onLink = onLink,
    )
  }
  val labelsFor: (Long) -> Map<String, GitLabLabel> = remember(uiState.labels) {
    { projectId -> uiState.labels[projectId].orEmpty() }
  }
  val baseWebUrl = uiState.activeInstance?.url?.trimEnd('/')?.takeIf { it.isNotBlank() }
  val isTwoPane = isExpanded && (
    (destination == ScreenDestination.MainTabs && tab == BottomTab.INBOX) ||
      destination == ScreenDestination.IssuesList ||
      destination == ScreenDestination.MergeRequestsList
    )
  val maxWidth = if (isTwoPane) GlMaxTwoPaneWidth else GlMaxContentWidth

  Box(
    modifier = Modifier.fillMaxSize().background(c.bg),
    contentAlignment = Alignment.TopCenter,
  ) {
    Box(modifier = Modifier.fillMaxHeight().widthIn(max = maxWidth).fillMaxWidth()) {
      when (destination) {
        ScreenDestination.MainTabs -> when (tab) {
          BottomTab.HOME -> HomeScreen(
            state = uiState,
            onRefresh = refreshDashboard,
            onOpenProfile = { if (uiState.isSignedIn) viewModel.navigateTo(ScreenDestination.UserProfile) else addServer() },
            onOpenSearch = { viewModel.explore.openSearch() },
            onAddServer = addServer,
            onSignInAgain = addServer,
            onNavigate = viewModel::navigateTo,
            onSwitchTab = viewModel::switchTab,
            onToggleStar = viewModel::toggleProjectStar,
          )
          BottomTab.INBOX -> if (isExpanded) {
            var selectedPaneType by rememberSaveable(uiState.activeInstance?.id) { mutableStateOf<String?>(null) }
            Row(Modifier.fillMaxSize()) {
              InboxScreen(
                state = uiState,
                onRefresh = refreshDashboard,
                onFilterChange = viewModel::setInboxFilter,
                onTypeFilterChange = viewModel::setInboxTypeFilter,
                onOpen = { notification ->
                  val (kind, url) = viewModel.openNotificationInPane(notification)
                  if (kind != null) selectedPaneType = kind
                  else url?.let(browser)
                },
                onMarkDone = viewModel::markNotificationDone,
                onMarkAllDone = viewModel::markAllNotificationsDone,
                onAddServer = addServer,
                modifier = Modifier.weight(0.42f).fillMaxHeight(),
              )
              Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
              Box(Modifier.weight(0.58f).fillMaxHeight().testTag("two_pane_detail")) {
                when (selectedPaneType) {
                  "Issue" -> IssueDetailPane(
                    uiState = uiState,
                    labelsFor = labelsFor,
                    onLink = onLink,
                    viewModel = viewModel,
                    browser = browser,
                    back = back,
                    showBack = false,
                  )
                  "MergeRequest" -> MergeRequestDetailPane(
                    uiState = uiState,
                    labelsFor = labelsFor,
                    onLink = onLink,
                    viewModel = viewModel,
                    browser = browser,
                    back = back,
                    showBack = false,
                  )
                  else -> EmptyState(
                    icon = Icons.Outlined.Inbox,
                    title = "Select a to-do",
                    body = "Pick an issue or merge request from your inbox to read it here.",
                  )
                }
              }
            }
          } else {
            InboxScreen(
              state = uiState,
              onRefresh = refreshDashboard,
              onFilterChange = viewModel::setInboxFilter,
              onTypeFilterChange = viewModel::setInboxTypeFilter,
              onOpen = { notification -> viewModel.openNotification(notification)?.let(browser) },
              onMarkDone = viewModel::markNotificationDone,
              onMarkAllDone = viewModel::markAllNotificationsDone,
              onAddServer = addServer,
            )
          }
          BottomTab.PROJECTS -> ProjectsScreen(
            title = "Projects",
            projects = uiState.projects,
            searchQuery = uiState.searchQuery,
            searchResults = uiState.searchResults,
            pendingStars = uiState.pendingStars,
            canStar = uiState.isSignedIn,
            canCreate = uiState.isSignedIn,
            hasServer = uiState.activeInstance != null,
            onSearchChange = viewModel::setSearchQuery,
            onRefresh = refreshDashboard,
            refreshing = uiState.refreshing,
            onSelectProject = { viewModel.navigateTo(ScreenDestination.ProjectDetail(it)) },
            onToggleStar = viewModel::toggleProjectStar,
            onCreateProject = viewModel::createProject,
            onAddServer = addServer,
            onBack = null,
          )
        }
        ScreenDestination.IssuesList -> if (isExpanded) {
          var selectedIssueId by rememberSaveable(uiState.activeInstance?.id) { mutableStateOf<Long?>(null) }
          val hasSelection = selectedIssueId != null && uiState.issueDetail.projectId > 0
          Row(Modifier.fillMaxSize()) {
            IssuesScreen(
              issues = uiState.issues,
              projects = uiState.projects.data,
              onSelectIssue = { issue ->
                selectedIssueId = issue.id
                viewModel.openIssueInPane(issue)
              },
              onCreateIssue = viewModel::createIssue,
              onRetry = refreshDashboard,
              onBack = back,
              modifier = Modifier.weight(0.42f).fillMaxHeight(),
              truncated = uiState.issuesTruncated,
              onOpenInBrowser = baseWebUrl?.let { base -> { browser("$base/dashboard/issues") } },
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
            Box(Modifier.weight(0.58f).fillMaxHeight().testTag("two_pane_detail")) {
              if (hasSelection) {
                IssueDetailPane(
                  uiState = uiState,
                  labelsFor = labelsFor,
                  onLink = onLink,
                  viewModel = viewModel,
                  browser = browser,
                  back = back,
                  showBack = false,
                )
              } else {
                EmptyState(
                  icon = Icons.Outlined.Adjust,
                  title = "Select an issue",
                  body = "Pick an issue from the list to view its details and comments.",
                )
              }
            }
          }
        } else {
          IssuesScreen(
            issues = uiState.issues,
            projects = uiState.projects.data,
            onSelectIssue = viewModel::openIssue,
            onCreateIssue = viewModel::createIssue,
            onRetry = refreshDashboard,
            onBack = back,
            truncated = uiState.issuesTruncated,
            onOpenInBrowser = baseWebUrl?.let { base -> { browser("$base/dashboard/issues") } },
          )
        }
        ScreenDestination.MergeRequestsList -> if (isExpanded) {
          var selectedMrId by rememberSaveable(uiState.activeInstance?.id) { mutableStateOf<Long?>(null) }
          val hasSelection = selectedMrId != null && uiState.mergeRequestDetail.projectId > 0
          Row(Modifier.fillMaxSize()) {
            MergeRequestsScreen(
              mergeRequests = uiState.mergeRequests,
              projects = uiState.projects.data,
              onSelectMergeRequest = { mr ->
                selectedMrId = mr.id
                viewModel.review.openInPane(mr)
              },
              onCreateMergeRequest = viewModel.review::create,
              onLoadBranches = viewModel.review::branches,
              onRetry = refreshDashboard,
              onBack = back,
              modifier = Modifier.weight(0.42f).fillMaxHeight(),
              truncated = uiState.mergeRequestsTruncated,
              onOpenInBrowser = baseWebUrl?.let { base -> { browser("$base/dashboard/merge_requests") } },
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
            Box(Modifier.weight(0.58f).fillMaxHeight().testTag("two_pane_detail")) {
              if (hasSelection) {
                MergeRequestDetailPane(
                  uiState = uiState,
                  labelsFor = labelsFor,
                  onLink = onLink,
                  viewModel = viewModel,
                  browser = browser,
                  back = back,
                  showBack = false,
                )
              } else {
                EmptyState(
                  icon = Icons.Outlined.CallMerge,
                  title = "Select a merge request",
                  body = "Pick a merge request from the list to review its changes and discussion.",
                )
              }
            }
          }
        } else {
          MergeRequestsScreen(
            mergeRequests = uiState.mergeRequests,
            projects = uiState.projects.data,
            onSelectMergeRequest = viewModel::openMergeRequest,
            onCreateMergeRequest = viewModel.review::create,
            onLoadBranches = viewModel.review::branches,
            onRetry = refreshDashboard,
            onBack = back,
            truncated = uiState.mergeRequestsTruncated,
            onOpenInBrowser = baseWebUrl?.let { base -> { browser("$base/dashboard/merge_requests") } },
          )
        }
        ScreenDestination.PipelinesList -> PipelinesScreen(
          pipelines = uiState.pipelines,
          refreshing = uiState.refreshing,
          onRefresh = refreshDashboard,
          onRetryPipeline = viewModel::retryPipeline,
          onOpenPipeline = { viewModel.ci.openPipeline(it.projectId, it.id) },
          onBack = back,
          truncated = uiState.pipelinesTruncated,
          onOpenInBrowser = baseWebUrl?.let { base -> { browser(base) } },
        )
        ScreenDestination.GroupsList -> GroupsScreen(
          groups = uiState.groups,
          onSelect = { viewModel.navigateTo(ScreenDestination.GroupProjects(it)) },
          onRetry = refreshDashboard,
          onBack = back,
        )
        is ScreenDestination.GroupProjects -> GroupDetailScreen(
          state = uiState.group,
          onBack = back,
          onRetry = viewModel.explore::retryGroup,
          onOpenGroup = { viewModel.navigateTo(ScreenDestination.GroupProjects(it)) },
          onOpenProject = { viewModel.navigateTo(ScreenDestination.ProjectDetail(it)) },
          onOpenInBrowser = browser,
        )
        is ScreenDestination.Search -> SearchScreen(
          state = uiState.search,
          isSignedIn = uiState.isSignedIn,
          onBack = back,
          onQueryChange = viewModel.explore::setQuery,
          onScopeChange = viewModel.explore::setScope,
          onOpenProject = { viewModel.navigateTo(ScreenDestination.ProjectDetail(it)) },
          onOpenIssue = viewModel::openIssue,
          onOpenMergeRequest = viewModel::openMergeRequest,
          onOpenUser = viewModel.explore::openUser,
          onOpenBlob = { blob ->
            if (blob.projectId > 0L && blob.path.isNotBlank()) {
              viewModel.navigateTo(
                ScreenDestination.FileViewer(
                  projectId = blob.projectId,
                  filePath = blob.path,
                  ref = blob.ref.takeIf { it.isNotBlank() },
                )
              )
            }
          },
          onOpenCommit = { commit ->
            val pid = commit.projectId ?: uiState.search.projectId ?: 0L
            if (pid > 0L) viewModel.code.openCommit(pid, commit.id)
          },
          onOpenNote = { note ->
            val iid = note.noteableIid
            if (note.projectId > 0L && iid != null) {
              when (note.noteableType) {
                "Issue" -> viewModel.navigateTo(ScreenDestination.IssueDetail(note.projectId, iid))
                "MergeRequest" -> viewModel.navigateTo(ScreenDestination.MergeRequestDetail(note.projectId, iid))
              }
            }
          },
        )
        ScreenDestination.UserProfile -> UserProfileScreen(
          activeInstance = uiState.activeInstance,
          user = uiState.currentUser,
          status = uiState.userStatus,
          profileReadme = uiState.profileReadme,
          onBack = back,
          onOpenSwitcher = openSwitcher,
          onManageServers = { openServers(false) },
          onSetStatus = viewModel::setUserStatus,
          onOpenInBrowser = browser,
          onRetry = refreshDashboard,
        )
        is ScreenDestination.UserDetail -> UserDetailScreen(
          state = uiState.userDetail,
          onBack = back,
          onRetry = viewModel.explore::retryUser,
          onOpenProject = { viewModel.navigateTo(ScreenDestination.ProjectDetail(it)) },
          onNavigate = viewModel::navigateTo,
          onOpenInBrowser = browser,
          markdown = MarkdownContext(onLink = onLink),
        )
        ScreenDestination.Instances -> InstancesScreen(
          activeInstance = uiState.activeInstance,
          savedInstances = uiState.savedInstances,
          loginState = uiState.loginState,
          savedClientId = uiState.gitLabComClientId,
          openAddOnStart = openAddServer,
          onAddOpened = onAddServerConsumed,
          onSwitchInstance = viewModel::switchInstance,
          onDeleteInstance = viewModel::deleteInstance,
          onSignInWithPassword = viewModel::signInWithPassword,
          onSubmitTwoFactor = viewModel::submitTwoFactor,
          onSignInWithOAuth = viewModel::startOAuthSignIn,
          onSignInWithToken = viewModel::signInWithToken,
          onClearLogin = viewModel::clearLoginState,
          onBack = back,
          onOpenNotifications = { viewModel.navigateTo(ScreenDestination.NotificationSettings) },
        )
        ScreenDestination.NotificationSettings -> NotificationSettingsRoute(
          onBack = back,
          onOpenDiagnostics = { viewModel.navigateTo(ScreenDestination.NotificationDiagnostics) },
        )
        ScreenDestination.NotificationDiagnostics -> NotificationDiagnosticsRoute(onBack = back)
        is ScreenDestination.ProjectDetail -> ProjectDetailScreen(
          state = project,
          pendingStar = project.projectId in uiState.pendingStars,
          canWrite = uiState.isSignedIn,
          markdown = projectMarkdown,
          onBack = back,
          onRetry = viewModel.code::retry,
          onToggleStar = viewModel::toggleProjectStar,
          onOpenSection = viewModel.code::openSection,
          onOpenCode = viewModel.code::openCode,
          onOpenRelease = viewModel.code::openRelease,
          onOpenInBrowser = browser,
          onChangeRef = viewModel.code::setRef,
          onLoadRefs = viewModel.code::loadRefs,
          onFilterRefs = viewModel.code::filterRefs,
          onRefresh = viewModel.code::refresh,
          onOpenSearch = { viewModel.explore.openSearch(project.projectId, project.project.data?.name) },
        )
        is ScreenDestination.ProjectCode -> ProjectCodeScreen(
          state = project,
          onBack = back,
          onOpenPath = viewModel.code::openPath,
          onOpenFile = viewModel.code::openFile,
          onChangeRef = viewModel.code::setRef,
          onLoadRefs = viewModel.code::loadRefs,
          onFilterRefs = viewModel.code::filterRefs,
          onOpenCommits = { viewModel.code.openSection(ProjectSection.COMMITS) },
          onOpenInBrowser = browser,
        )
        is ScreenDestination.ProjectSectionList -> ProjectSectionScreen(
          section = destination.section,
          state = project,
          labels = labelsFor(project.projectId),
          canWrite = uiState.isSignedIn,
          onBack = back,
          onRetry = viewModel.code::retrySection,
          onOpenIssue = viewModel::openIssue,
          onOpenMergeRequest = viewModel::openMergeRequest,
          onCreateMergeRequest = viewModel.review::create,
          onLoadBranches = viewModel.review::branches,
          onOpenPipeline = { viewModel.ci.openPipeline(it.projectId, it.id) },
          onRetryPipeline = viewModel::retryPipeline,
          onOpenCommit = { viewModel.code.openCommit(project.projectId, it) },
          onBrowseRef = { ref ->
            viewModel.code.setRef(ref)
            viewModel.code.openCode()
          },
          onOpenRelease = viewModel.code::openRelease,
          onNavigate = viewModel::navigateTo,
          onOpenInBrowser = browser,
          onSetIssueState = viewModel.code::setIssueState,
          onSetMergeRequestState = viewModel.code::setMergeRequestState,
          onLoadMore = viewModel.code::loadMore,
        )
        is ScreenDestination.ReleaseDetail -> ReleaseDetailScreen(
          state = project,
          tagName = destination.tagName,
          markdown = projectMarkdown,
          onBack = back,
          onRetry = { viewModel.code.retrySection(ProjectSection.RELEASES) },
          onOpenCommit = { viewModel.code.openCommit(project.projectId, it) },
          onOpenInBrowser = browser,
        )
        is ScreenDestination.FileViewer -> FileViewerScreen(
          state = uiState.file,
          webUrl = project.webUrl?.takeIf { project.projectId == uiState.file.projectId },
          markdown = projectMarkdown,
          onRetry = viewModel.code::retryFile,
          onOpenInBrowser = browser,
          onBack = back,
        )
        is ScreenDestination.CommitDetail -> CommitDetailScreen(
          state = uiState.commitDetail,
          onRetry = viewModel.code::retryCommit,
          onOpenPipeline = { viewModel.ci.openPipeline(it.projectId.takeIf { id -> id > 0 } ?: uiState.commitDetail.projectId, it.id) },
          onOpenInBrowser = browser,
          onBack = back,
        )
        is ScreenDestination.IssueDetail -> IssueDetailPane(
          uiState = uiState,
          labelsFor = labelsFor,
          onLink = onLink,
          viewModel = viewModel,
          browser = browser,
          back = back,
          showBack = true,
        )
        is ScreenDestination.MergeRequestDetail -> MergeRequestDetailPane(
          uiState = uiState,
          labelsFor = labelsFor,
          onLink = onLink,
          viewModel = viewModel,
          browser = browser,
          back = back,
          showBack = true,
        )
        is ScreenDestination.RunPipeline -> RunPipelineScreen(
          projectPath = project.project.data?.pathWithNamespace,
          defaultRef = project.effectiveRef.orEmpty(),
          onRun = { ref, done ->
            viewModel.ci.runPipeline(destination.projectId, ref) { error ->
              if (error == null) {
                // Replace the form with the new pipeline (opened by runPipeline) and refresh the list.
                viewModel.navigateBack()
                viewModel.code.retrySection(ProjectSection.PIPELINES)
              }
              done(error)
            }
          },
          onBack = back,
        )
        is ScreenDestination.PipelineDetail -> PipelineDetailScreen(
          state = uiState.pipelineDetail,
          canWrite = uiState.isSignedIn,
          onBack = back,
          onRetryLoad = viewModel.ci::retryLoadPipeline,
          onRetryPipeline = viewModel::retryPipeline,
          onCancel = viewModel.ci::cancelPipeline,
          onOpenJob = { viewModel.ci.openJob(uiState.pipelineDetail.projectId, it.id) },
          onOpenCommit = { viewModel.code.openCommit(uiState.pipelineDetail.projectId, it) },
          onOpenInBrowser = browser,
          onPlayJob = { viewModel.ci.playJob(uiState.pipelineDetail.projectId, it.id) },
        )
        is ScreenDestination.JobDetail -> {
          // The user picks where to save; the app streams the archive there itself, so the token
          // never goes to the system download manager.
          val saveArtifacts = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
              viewModel.ci.downloadArtifacts(
                open = { context.contentResolver.openOutputStream(uri) },
                discard = { DocumentsContract.deleteDocument(context.contentResolver, uri) },
              )
            }
          }
          JobDetailScreen(
            state = uiState.jobDetail,
            canWrite = uiState.isSignedIn,
            onBack = back,
            onRetryLoad = viewModel.ci::retryLoadJob,
            onRetryJob = viewModel.ci::retryJob,
            onOpenInBrowser = browser,
            onPlayJob = { viewModel.ci.playJob(uiState.jobDetail.projectId, uiState.jobDetail.jobId) },
            onCancelJob = viewModel.ci::cancelJob,
            onDownloadArtifacts = { saveArtifacts.launch(it) },
          )
        }
      }
    }
  }
}

@Composable
private fun IssueDetailPane(
  uiState: GitLabUiState,
  labelsFor: (Long) -> Map<String, GitLabLabel>,
  onLink: (String) -> Boolean,
  viewModel: GitLabViewModel,
  browser: (String) -> Unit,
  back: () -> Unit,
  showBack: Boolean,
) {
  IssueDetailScreen(
    state = uiState.issueDetail,
    labels = labelsFor(uiState.issueDetail.projectId) + uiState.issueDetail.issue.data?.labelDetails.orEmpty().associateBy { it.name },
    canWrite = uiState.isSignedIn,
    onLink = onLink,
    onRetry = viewModel::retryIssue,
    onComment = viewModel::postIssueNote,
    onSetClosed = viewModel::setIssueClosed,
    onEdit = viewModel::updateIssue,
    onOpenUser = viewModel.explore::openUser,
    onOpenInBrowser = browser,
    onBack = back,
    showBack = showBack,
    currentUsername = (uiState.currentUser.data?.username ?: uiState.activeInstance?.username)?.takeIf { it.isNotBlank() },
    currentUserId = uiState.currentUser.data?.id ?: 0L,
    drafts = uiState.drafts,
    onDraftChange = viewModel::setDraft,
    onClearDraft = viewModel::clearDraft,
    onToggleReaction = viewModel::toggleIssueReaction,
    onToggleNoteReaction = viewModel::toggleIssueNoteReaction,
    projectMembers = uiState.projectMembers[uiState.issueDetail.projectId] ?: emptyFeed(),
    projectMilestones = uiState.projectMilestones[uiState.issueDetail.projectId] ?: emptyFeed(),
    onLoadTriageData = {
      val pid = uiState.issueDetail.projectId
      viewModel.code.ensureMembers(pid)
      viewModel.code.ensureMilestones(pid)
    },
    onTriage = viewModel::triageIssue,
  )
}

@Composable
private fun MergeRequestDetailPane(
  uiState: GitLabUiState,
  labelsFor: (Long) -> Map<String, GitLabLabel>,
  onLink: (String) -> Boolean,
  viewModel: GitLabViewModel,
  browser: (String) -> Unit,
  back: () -> Unit,
  showBack: Boolean,
) {
  MergeRequestDetailScreen(
    state = uiState.mergeRequestDetail,
    labels = labelsFor(uiState.mergeRequestDetail.projectId) + uiState.mergeRequestDetail.mergeRequest.data?.labelDetails.orEmpty().associateBy { it.name },
    canWrite = uiState.isSignedIn,
    onLink = onLink,
    onRetry = viewModel.review::retry,
    onMerge = viewModel.review::merge,
    onCommentOnLine = viewModel.review::commentOnLine,
    onSetClosed = viewModel.review::setClosed,
    onComment = viewModel.review::postNote,
    onReply = viewModel.review::reply,
    onSetResolved = viewModel.review::setResolved,
    onOpenPipeline = { viewModel.ci.openPipeline(it.projectId.takeIf { id -> id > 0 } ?: uiState.mergeRequestDetail.projectId, it.id) },
    onOpenCommit = { viewModel.code.openCommit(uiState.mergeRequestDetail.projectId, it) },
    onOpenUser = viewModel.explore::openUser,
    onOpenInBrowser = browser,
    onBack = back,
    currentUsername = (uiState.currentUser.data?.username ?: uiState.activeInstance?.username)?.takeIf { it.isNotBlank() },
    currentUserId = uiState.currentUser.data?.id ?: 0L,
    onSetApproved = viewModel.review::setApproved,
    onCancelAutoMerge = viewModel.review::cancelAutoMerge,
    onMarkReady = viewModel.review::markReady,
    onRebase = viewModel.review::rebase,
    showBack = showBack,
    drafts = uiState.drafts,
    onDraftChange = viewModel::setDraft,
    onClearDraft = viewModel::clearDraft,
    onToggleReaction = viewModel.review::toggleReaction,
    onToggleNoteReaction = viewModel.review::toggleNoteReaction,
    projectMembers = uiState.projectMembers[uiState.mergeRequestDetail.projectId] ?: emptyFeed(),
    projectMilestones = uiState.projectMilestones[uiState.mergeRequestDetail.projectId] ?: emptyFeed(),
    onLoadTriageData = {
      val pid = uiState.mergeRequestDetail.projectId
      viewModel.code.ensureMembers(pid)
      viewModel.code.ensureMilestones(pid)
    },
    onUpdate = viewModel.review::updateMergeRequest,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstanceSwitcherSheet(
  viewModel: GitLabViewModel,
  onDismiss: () -> Unit,
  onManage: (add: Boolean) -> Unit,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()
  val c = Gl.colors
  fun close(then: () -> Unit) {
    scope.launch { sheetState.hide() }.invokeOnCompletion { then() }
  }
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.surface) {
    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
      GlSectionHeader(title = "Accounts", modifier = Modifier.padding(top = 0.dp))
      uiState.savedInstances.forEach { instance ->
        val selected = instance.id == uiState.activeInstance?.id
        GlRow(
          title = instance.name,
          subtitle = listOfNotNull(
            instance.username.takeIf { it.isNotBlank() }?.let { "@$it" },
            instance.url.removePrefix("https://"),
          ).joinToString(" · "),
          leading = { GitLabTanukiIcon(size = 24.dp) },
          trailing = {
            if (selected) Icon(Icons.Outlined.Check, contentDescription = "Current account", tint = c.textPrimary)
          },
          onClick = {
            close {
              onDismiss()
              viewModel.switchInstance(instance.id)
            }
          },
          onClickLabel = if (selected) null else "Switch to ${instance.name}",
        )
      }
      if (uiState.savedInstances.isNotEmpty()) GlDivider(inset = 16.dp)
      GlRow(
        title = "Add account",
        leading = { Icon(Icons.Outlined.Add, contentDescription = null, tint = c.textPrimary, modifier = Modifier.size(24.dp)) },
        onClick = { close { onManage(true) } },
      )
      GlRow(
        title = "Manage accounts",
        leading = { Icon(Icons.Outlined.Settings, contentDescription = null, tint = c.textPrimary, modifier = Modifier.size(24.dp)) },
        onClick = { close { onManage(false) } },
      )
    }
  }
}

internal fun openUrl(context: Context, url: String) {
  openExternalUrl(context, url)
}
