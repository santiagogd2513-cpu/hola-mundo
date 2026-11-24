package com.bdfiles.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.bdfiles.AppViewModel
import com.bdfiles.ui.theme.BD
import kotlinx.coroutines.launch

/**
 * Barra de pestañas: una pastilla por pestaña, con su número, su color propio
 * y el sitio donde está. Tocar la cambia; mantener pulsado la devuelve al inicio.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabsBar(vm: AppViewModel) {
    if (!vm.prefs.tabsEnabled || vm.tabs.size <= 1) return
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    @Suppress("UNUSED_EXPRESSION") vm.uiRevision

    // Al cambiar de pestaña, se desplaza para que se vea.
    LaunchedEffect(vm.activeTabIndex) {
        scope.launch {
            val approx = vm.activeTabIndex * 130
            scroll.animateScrollTo((approx - 100).coerceAtLeast(0))
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        vm.tabs.forEachIndexed { i, tab ->
            val active = i == vm.activeTabIndex
            val color = BD.tabColor(i)
            val bg by animateColorAsState(
                if (active) color else BD.SurfaceHigh,
                label = "fondoPestana",
            )
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .border(
                        width = if (active) 0.dp else 1.dp,
                        color = if (active) Color.Transparent else color.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(50),
                    )
                    .combinedClickable(
                        onClick = { vm.selectTab(i) },
                        onLongClick = { vm.closeTab(i) },
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .widthIn(max = 150.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${i + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (active) textOn(color) else color,
                )
                Text(
                    " · ",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active) textOn(color).copy(alpha = 0.6f) else BD.TextLow,
                )
                Text(
                    vm.tabLabel(tab),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active) textOn(color) else BD.TextLow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}

/** Texto oscuro sobre colores claros y blanco sobre los oscuros. */
private fun textOn(background: Color): Color {
    val luminance = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
    return if (luminance > 0.6f) Color(0xFF0D0A18) else Color.White
}
