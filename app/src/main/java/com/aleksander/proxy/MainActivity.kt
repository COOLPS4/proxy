package com.aleksander.proxy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

// ---- palette ---------------------------------------------------------------

private val BgTop = Color(0xFF0B1020)
private val BgBottom = Color(0xFF10182E)
private val CardBg = Color(0xFF182241)
private val Accent = Color(0xFF4C8DFF)
private val Accent2 = Color(0xFF6EE7F0)
private val TextMuted = Color(0xFF9AA6C4)
private val Good = Color(0xFF3DDC84)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AleksanderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProxyScreen()
                }
            }
        }
    }
}

@Composable
private fun AleksanderTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Accent,
        background = BgTop,
        surface = CardBg,
        onPrimary = Color.White,
        onBackground = Color.White,
        onSurface = Color.White
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun ProxyScreen(vm: MainViewModel = viewModel()) {
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { Header() }
                item {
                    TypeSelector(
                        selected = vm.type,
                        enabled = !vm.loading,
                        onSelect = vm::selectType
                    )
                }
                item {
                    FindButton(loading = vm.loading, onClick = vm::find)
                }

                vm.error?.let { msg ->
                    item {
                        Text(
                            text = msg,
                            color = Color(0xFFFF8A8A),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                if (vm.proxies.isEmpty() && !vm.loading && vm.error == null) {
                    item { EmptyState(searched = vm.searched) }
                }

                itemsIndexed(vm.proxies) { index, proxy ->
                    ProxyCard(index = index + 1, proxy = proxy) {
                        clipboard.setText(AnnotatedString(proxy.link))
                        scope.launch { snackbar.showSnackbar("Прокси #${index + 1} скопирован") }
                    }
                }

                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Accent, Accent2))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Send, contentDescription = null, tint = Color.White)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                "Aleksander proxy",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Актуальные прокси для Telegram",
                color = TextMuted,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun TypeSelector(
    selected: ProxyType,
    enabled: Boolean,
    onSelect: (ProxyType) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ProxyType.values().forEach { type ->
            val active = type == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (active) Accent else Color.Transparent)
                    .clickableIf(enabled) { onSelect(type) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    type.title,
                    color = if (active) Color.White else TextMuted,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun FindButton(loading: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !loading,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent,
            disabledContainerColor = Accent.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text("Проверяем прокси…", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        } else {
            Icon(Icons.Filled.Search, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Найти прокси", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EmptyState(searched: Boolean) {
    val text = if (searched) {
        "Рабочих прокси не найдено. Нажмите «Найти прокси» ещё раз."
    } else {
        "Нажмите «Найти прокси», чтобы получить 10 рабочих прокси. Каждый можно скопировать одной кнопкой."
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = TextMuted, fontSize = 14.sp)
    }
}

@Composable
private fun ProxyCard(index: Int, proxy: Proxy, onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text("$index", color = Accent2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                proxy.label,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(proxy.type.title, color = TextMuted, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                val latency = proxy.latencyMs
                if (latency != null) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = Good,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(" $latency мс", color = Good, fontSize = 12.sp)
                } else {
                    Text("не проверен", color = TextMuted, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onCopy,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Accent.copy(alpha = 0.18f))
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = "Копировать", tint = Accent2)
        }
    }
}

/** Clickable only when [enabled]; keeps composable code tidy. */
private fun Modifier.clickableIf(enabled: Boolean, onClick: () -> Unit): Modifier =
    if (enabled) this.clickable(onClick = onClick) else this
