package com.jongsun.runcal.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch

private const val TAG = "RunCal"

/**
 * 위젯 헤더 1단의 프리셋 칩을 탭하면 뜨는 투명 팝업 Activity.
 * 순환식 탭(연타)이 Glance 세션 경합을 유발해, YearMonthPickerActivity와 동일한 구조로
 * "한 번 골라서 1회만 반영"하는 방식으로 바꾼 것이다.
 */
class PresetPickerActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        setFinishOnTouchOutside(true)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // 콜백/액티비티가 실제로 실행되는지부터 확인하기 위해 가장 먼저 찍는다.
        Log.d(TAG, "callback=PresetPickerActivity id=$appWidgetId")

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Log.e(TAG, "callback=PresetPickerActivity invalid appWidgetId, finishing")
            finish()
            return
        }

        setContent {
            RunCalTheme {
                PresetPickerScreen(appWidgetId = appWidgetId, onDismiss = { finish() })
            }
        }
    }
}

@Composable
private fun PresetPickerScreen(appWidgetId: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var presets by remember { mutableStateOf<List<WidgetPreset>>(emptyList()) }
    var currentPresetIndex by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(appWidgetId) {
        val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
        val settings = loadWidgetFilterSettings(context, glanceId)
        presets = settings.presets
        currentPresetIndex = settings.currentPresetIndex
        loaded = true
    }

    fun select(index: Int) {
        Log.d(TAG, "callback=PresetPickerScreen.select id=$appWidgetId index=$index")
        WidgetCallbackTiming.markCallback(appWidgetId)
        scope.launch {
            applyWidgetState(context, appWidgetId) { current -> current.copy(currentPresetIndex = index) }
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
                Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                    Text(text = "프리셋 선택", style = MaterialTheme.typography.titleMedium)
                    presets.forEachIndexed { index, preset ->
                        val isSelected = index == currentPresetIndex
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { select(index) }
                                .padding(vertical = 12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(Color(preset.colorArgb), CircleShape),
                            )
                            Text(
                                text = preset.name,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(start = 12.dp).weight(1f),
                            )
                            if (isSelected) {
                                Icon(Icons.Filled.Check, contentDescription = "현재 선택됨")
                            }
                        }
                    }
                }
            }
        }
    }
}
