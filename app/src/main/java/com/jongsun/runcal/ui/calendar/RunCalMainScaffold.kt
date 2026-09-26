package com.jongsun.runcal.ui.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jongsun.runcal.DeepLinkTarget
import com.jongsun.runcal.ai.AiPrefs
import com.jongsun.runcal.ai.aiKeyPresent
import com.jongsun.runcal.ui.assistant.AssistantScreen
import com.jongsun.runcal.ui.assistant.AssistantViewModel

enum class RunCalTab {
    MONTHLY,
    DAILY,
    LIST,
    SETTINGS,
}

private fun appFontScaleMultiplier(step: Int): Float = when (step) {
    1 -> 0.9f
    2 -> 1.0f
    3 -> 1.1f
    4 -> 1.25f
    else -> 1.0f
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunCalMainScaffold(
    pendingDeepLinkTarget: DeepLinkTarget?,
    onDeepLinkConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CalendarViewModel = viewModel()
    var selectedTab by rememberSaveable { mutableStateOf(RunCalTab.MONTHLY) }
    var showMonthPicker by remember { mutableStateOf(false) }
    // AI 자연어 명령: 키가 있고 사용자가 설정에서 켰을 때만 ✨ 아이콘/화면이 나타난다(기본 꺼짐).
    val context = LocalContext.current
    val aiSettings by AiPrefs.state(context).collectAsStateWithLifecycle()
    val aiAvailable = aiSettings.enabled && aiKeyPresent()
    val assistantViewModel: AssistantViewModel = viewModel()
    var showAssistant by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(aiAvailable) { if (!aiAvailable) showAssistant = false }
    var autoOpenEventId by remember { mutableStateOf<Long?>(null) }
    var autoOpenOccurrenceBegin by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(pendingDeepLinkTarget) {
        when (val target = pendingDeepLinkTarget) {
            is DeepLinkTarget.Day -> {
                viewModel.selectDate(target.date)
                selectedTab = RunCalTab.DAILY
                onDeepLinkConsumed()
            }
            is DeepLinkTarget.Assistant -> {
                // AI를 꺼뒀거나 키가 없으면 무시한다(바로가기도 그때는 등록되지 않는다).
                if (aiAvailable) {
                    showAssistant = true
                    target.debugPrompt?.let { assistantViewModel.send(it, viewModel) }
                }
                onDeepLinkConsumed()
            }
            is DeepLinkTarget.Month -> {
                viewModel.requestJumpToMonth(target.yearMonth)
                selectedTab = RunCalTab.MONTHLY
                onDeepLinkConsumed()
            }
            is DeepLinkTarget.Event -> {
                viewModel.selectDate(target.date)
                selectedTab = RunCalTab.DAILY
                autoOpenOccurrenceBegin = target.occurrenceBeginMillis
                autoOpenEventId = target.eventId
                onDeepLinkConsumed()
            }
            null -> Unit
        }
    }

    val visibleYearMonth by viewModel.visibleYearMonth.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val appFontScaleStep by viewModel.appFontScaleStep.collectAsStateWithLifecycle()

    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, appFontScaleStep) {
        Density(
            density = baseDensity.density,
            fontScale = baseDensity.fontScale * appFontScaleMultiplier(appFontScaleStep),
        )
    }

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = when (selectedTab) {
                                RunCalTab.MONTHLY -> visibleYearMonth.titleKorean()
                                RunCalTab.DAILY -> selectedDate.titleKorean()
                                RunCalTab.LIST -> "목록"
                                RunCalTab.SETTINGS -> "설정"
                            },
                        )
                    },
                    actions = {
                        if (aiAvailable && (selectedTab == RunCalTab.MONTHLY || selectedTab == RunCalTab.DAILY)) {
                            IconButton(onClick = { showAssistant = true }) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = "AI 명령")
                            }
                        }
                        if (selectedTab == RunCalTab.MONTHLY || selectedTab == RunCalTab.DAILY) {
                            IconButton(onClick = { viewModel.requestJumpToday() }) {
                                Icon(Icons.Default.Today, contentDescription = "오늘로 이동")
                            }
                            IconButton(onClick = { showMonthPicker = true }) {
                                Icon(Icons.Default.DateRange, contentDescription = "날짜 점프")
                            }
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == RunCalTab.MONTHLY,
                        onClick = { selectedTab = RunCalTab.MONTHLY },
                        icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                        label = { Text("월간") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == RunCalTab.DAILY,
                        onClick = { selectedTab = RunCalTab.DAILY },
                        icon = { Icon(Icons.Default.ViewDay, contentDescription = null) },
                        label = { Text("일간") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == RunCalTab.LIST,
                        onClick = { selectedTab = RunCalTab.LIST },
                        icon = { Icon(Icons.Default.List, contentDescription = null) },
                        label = { Text("목록") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == RunCalTab.SETTINGS,
                        onClick = { selectedTab = RunCalTab.SETTINGS },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("설정") },
                    )
                }
            },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                when (selectedTab) {
                    RunCalTab.MONTHLY -> MonthlyScreen(viewModel)
                    RunCalTab.DAILY -> DailyScreen(
                        viewModel = viewModel,
                        autoOpenEventId = autoOpenEventId,
                        autoOpenOccurrenceBegin = autoOpenOccurrenceBegin,
                        onAutoOpenEventConsumed = { autoOpenEventId = null },
                    )
                    RunCalTab.LIST -> ListScreen(
                        viewModel = viewModel,
                        onNavigateToDate = { date ->
                            viewModel.selectDate(date)
                            selectedTab = RunCalTab.DAILY
                        },
                    )
                    RunCalTab.SETTINGS -> SettingsScreen(viewModel, assistantViewModel)
                }
            }
        }
        if (showAssistant && aiAvailable) {
            AssistantScreen(
                assistant = assistantViewModel,
                calendarViewModel = viewModel,
                onClose = { showAssistant = false },
            )
        }
        }
    }

    if (showMonthPicker) {
        MonthYearPickerDialog(
            initialYearMonth = visibleYearMonth,
            onDismiss = { showMonthPicker = false },
            onConfirm = { yearMonth ->
                viewModel.requestJumpToMonth(yearMonth)
                showMonthPicker = false
            },
        )
    }
}
