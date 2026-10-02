package com.jongsun.runcal.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.jongsun.runcal.data.AccountEventColor

/**
 * 색 선택 줄. [restricted]가 있으면(구글 계정 캘린더 등) 그 색만 고를 수 있고 자유색은 없다. 아니면 추천색 [recommended]
 * + 자유색(+ 버튼). [noneLabel]이 있으면 맨 앞에 "기본(캘린더 색)" 선택지를 둔다(선택값 null).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorChoiceRow(
    selected: Int?,
    recommended: List<Int>,
    restricted: List<AccountEventColor>?,
    onSelect: (Int?) -> Unit,
    noneLabel: String? = null,
    noneColor: Int? = null,
) {
    var showFree by remember { mutableStateOf(false) }
    val options = restricted?.map { it.argb } ?: recommended
    // 목록에 없는 자유색이 선택돼 있으면 맨 앞에 보여 줘서 지금 무엇이 골라져 있는지 알 수 있게 한다.
    val customSelected = selected?.takeIf { s -> options.none { sameColor(it, s) } }
    Column {
        if (noneLabel != null) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(null) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Swatch(argb = noneColor ?: 0xFF9E9E9E.toInt(), selected = selected == null, onClick = { onSelect(null) })
                Text(noneLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            customSelected?.let { Swatch(argb = it, selected = true, onClick = {}) }
            options.forEach { argb ->
                Swatch(argb = argb, selected = selected != null && sameColor(selected, argb), onClick = { onSelect(argb) })
            }
            if (restricted == null) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { showFree = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Add, contentDescription = "자유색") }
            }
        }
        if (restricted != null) {
            Text(
                "구글 계정 캘린더는 구글이 허용하는 일정 색만 쓸 수 있습니다(PC의 구글 캘린더에도 같은 색으로 보입니다).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    if (showFree) {
        FreeColorDialog(initial = selected ?: recommended.firstOrNull() ?: 0xFF1A73E8.toInt(), onDismiss = { showFree = false }) {
            onSelect(it)
            showFree = false
        }
    }
}

private fun sameColor(a: Int, b: Int): Boolean = (a or 0xFF000000.toInt()) == (b or 0xFF000000.toInt())

@Composable
fun Swatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(Color(argb), CircleShape)
            .border(width = if (selected) 3.dp else 0.dp, color = MaterialTheme.colorScheme.onSurface, shape = CircleShape)
            .clickable(onClick = onClick),
    )
}

/** 자유색: 색상·채도·명도 슬라이더와 #RRGGBB 입력. */
@Composable
private fun FreeColorDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val hsl = remember { FloatArray(3).also { ColorUtils.colorToHSL(initial, it) } }
    var hue by remember { mutableFloatStateOf(hsl[0]) }
    var sat by remember { mutableFloatStateOf(hsl[1]) }
    var light by remember { mutableFloatStateOf(hsl[2]) }
    val current = ColorUtils.HSLToColor(floatArrayOf(hue, sat, light))
    var hexText by remember(current) { mutableStateOf("%06X".format(current and 0xFFFFFF)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("자유색") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(modifier = Modifier.fillMaxWidth().height(40.dp).background(Color(current), RoundedCornerShape(8.dp)))
                Text("색상", style = MaterialTheme.typography.labelMedium)
                Box(
                    modifier = Modifier.fillMaxWidth().height(8.dp).background(
                        Brush.horizontalGradient((0..6).map { Color(ColorUtils.HSLToColor(floatArrayOf(it * 60f, 0.8f, 0.5f))) }),
                        RoundedCornerShape(4.dp),
                    ),
                )
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..359f)
                Text("채도", style = MaterialTheme.typography.labelMedium)
                Slider(value = sat, onValueChange = { sat = it }, valueRange = 0f..1f)
                Text("밝기", style = MaterialTheme.typography.labelMedium)
                Slider(value = light, onValueChange = { light = it }, valueRange = 0.15f..0.85f)
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { text ->
                        hexText = text.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                        if (hexText.length == 6) {
                            val parsed = (0xFF000000 or hexText.toLong(16)).toInt()
                            val out = FloatArray(3).also { ColorUtils.colorToHSL(parsed, it) }
                            hue = out[0]; sat = out[1]; light = out[2].coerceIn(0.15f, 0.85f)
                        }
                    },
                    label = { Text("#RRGGBB") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(current) }) { Text("선택") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
