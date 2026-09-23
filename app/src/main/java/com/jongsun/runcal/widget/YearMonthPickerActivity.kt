package com.jongsun.runcal.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.jongsun.runcal.ui.theme.RunCalTheme
import java.time.YearMonth
import kotlinx.coroutines.launch

private const val TAG = "RunCal"

/**
 * 위젯 헤더 2단의 연월 텍스트를 탭하면 뜨는 투명 팝업 Activity.
 * 위젯은 자체적으로 다이얼로그를 띄울 수 없어 전용 Activity + 반투명 테마로 구현한다.
 */
class YearMonthPickerActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 열고 닫을 때 일반 액티비티 전환 애니메이션이 보이면 "풀스크린 앱 화면"처럼 느껴지므로 제거한다.
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        setFinishOnTouchOutside(true)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // 콜백/액티비티가 실제로 실행되는지부터 확인하기 위해 가장 먼저 찍는다.
        Log.d(TAG, "callback=YearMonthPickerActivity id=$appWidgetId")

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Log.e(TAG, "callback=YearMonthPickerActivity invalid appWidgetId, finishing")
            finish()
            return
        }

        setContent {
            RunCalTheme {
                YearMonthPickerScreen(appWidgetId = appWidgetId, onDismiss = { finish() })
            }
        }
    }
}

@Composable
private fun YearMonthPickerScreen(appWidgetId: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentYearMonth by remember { mutableStateOf(YearMonth.now()) }
    var year by remember { mutableIntStateOf(currentYearMonth.year) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(appWidgetId) {
        val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
        val settings = loadWidgetFilterSettings(context, glanceId)
        val yearMonth = settings.viewingYearMonth ?: YearMonth.now()
        currentYearMonth = yearMonth
        year = yearMonth.year
        loaded = true
    }

    fun confirm(target: YearMonth?) {
        Log.d(TAG, "callback=YearMonthPickerScreen.confirm id=$appWidgetId target=$target")
        WidgetCallbackTiming.markCallback(appWidgetId)
        scope.launch {
            applyWidgetState(context, appWidgetId) { current ->
                current.copy(viewingYearMonth = target, lastNavigatedAtMillis = System.currentTimeMillis())
            }
            onDismiss()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .padding(32.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
        ) {
            if (!loaded) {
                Box(modifier = Modifier.padding(32.dp)) { CircularProgressIndicator() }
            } else {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(text = "날짜 이동", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { year-- }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "이전 연도")
                        }
                        Text(text = "${year}년", style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { year++ }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "다음 연도")
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    for (row in 0 until 4) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            for (col in 0 until 3) {
                                val month = row * 3 + col + 1
                                val isCurrentSelection = year == currentYearMonth.year && month == currentYearMonth.monthValue
                                TextButton(
                                    onClick = { confirm(YearMonth.of(year, month)) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text = "${month}월",
                                        fontWeight = if (isCurrentSelection) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = { confirm(null) }) { Text("오늘로") }
                        TextButton(onClick = onDismiss) { Text("닫기") }
                    }
                }
            }
        }
    }
}
