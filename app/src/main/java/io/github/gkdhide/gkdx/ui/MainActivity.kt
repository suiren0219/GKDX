package io.github.gkdhide.gkdx.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gkdhide.gkdx.RootEngine
import io.github.gkdhide.gkdx.RootEngineService
import io.github.gkdhide.gkdx.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GkdxTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("config", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var sources by remember { mutableStateOf(ConfigStore.load(context)) }
    var subsText by remember { mutableStateOf(ConfigStore.lastUpdateText(context)) }
    var updating by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }

    var rootRunning by remember { mutableStateOf(RootEngine.running) }
    var rootStatus by remember { mutableStateOf(RootEngine.status) }

    var hideMaster by remember { mutableStateOf(prefs.getBoolean("hide_master", true)) }
    var strict by remember { mutableStateOf(prefs.getBoolean("strict", true)) }
    var hideInstalled by remember { mutableStateOf(prefs.getBoolean("hide_installed", false)) }
    var logEnabled by remember { mutableStateOf(prefs.getBoolean("log", false)) }
    var targets by remember { mutableStateOf(prefs.getString("targets", "") ?: "") }
    var excludes by remember { mutableStateOf(prefs.getString("excludes", "") ?: "") }

    // Root 模式状态实时刷新
    LaunchedEffect(Unit) {
        while (true) {
            rootRunning = RootEngine.running
            rootStatus = RootEngine.status
            delay(800)
        }
    }

    fun saveHide() {
        prefs.edit()
            .putBoolean("hide_master", hideMaster)
            .putBoolean("strict", strict)
            .putBoolean("hide_installed", hideInstalled)
            .putBoolean("log", logEnabled)
            .putString("targets", targets)
            .putString("excludes", excludes)
            .apply()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GKDX", fontWeight = FontWeight.SemiBold)
                        Text(
                            "订阅引擎 · 无障碍隐藏",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(
                    subsText = subsText,
                    rootRunning = rootRunning,
                    rootStatus = rootStatus,
                )
            }

            item {
                SectionCard("订阅") {
                    Text(
                        subsText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    sources.forEachIndexed { index, source ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = source.enabled,
                                onCheckedChange = { checked ->
                                    sources = sources.toMutableList().also {
                                        it[index] = it[index].copy(enabled = checked)
                                    }
                                    ConfigStore.save(context, sources)
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    source.shortName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    source.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = {
                                sources = sources.toMutableList().also { it.removeAt(index) }
                                ConfigStore.save(context, sources)
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.width(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("添加")
                        }
                        Button(
                            onClick = {
                                if (sources.none { it.enabled }) {
                                    Toast.makeText(context, "请至少启用一个订阅源", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                scope.launch {
                                    updating = true
                                    updateResult = ""
                                    val result = SubscriptionUpdater.update(context, sources)
                                    updating = false
                                    subsText = ConfigStore.lastUpdateText(context)
                                    updateResult = if (result.ruleCount > 0) {
                                        "更新完成：${result.summary}"
                                    } else {
                                        "全部订阅源下载失败，请检查网络"
                                    }
                                }
                            },
                            enabled = !updating,
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.width(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (updating) "更新中…" else "更新订阅")
                        }
                    }
                    if (updating) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (updateResult.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            updateResult,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (updateResult.startsWith("更新完成")) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }

            item {
                SectionCard("Root 模式") {
                    Text(
                        "不勾作用域、不注入任何应用：以 root 读屏（uiautomator dump）并点击（input tap），可覆盖 WebView 内部节点。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (rootRunning) "运行中" else "未运行",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (rootRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        if (rootRunning) {
                            OutlinedButton(onClick = {
                                context.stopService(Intent(context, RootEngineService::class.java))
                            }) { Text("停止") }
                        } else {
                            Button(onClick = {
                                scope.launch {
                                    val root = withContext(Dispatchers.IO) { RootShell.isRoot() }
                                    if (!root) {
                                        Toast.makeText(context, "未获得 root 权限，请在 root 管理器中授予本应用", Toast.LENGTH_LONG).show()
                                        return@launch
                                    }
                                    val intent = Intent(context, RootEngineService::class.java)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                    delay(400)
                                    rootRunning = RootEngine.running
                                    rootStatus = RootEngine.status
                                }
                            }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.width(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("启动")
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        rootStatus,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            item {
                SectionCard("无障碍隐藏") {
                    Text(
                        "同时使用 GKD 本体时生效：在作用域应用里隐藏 GKD 的无障碍状态。默认隐藏 li.songe.gkd。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SwitchRow("对外隐藏 GKD 的无障碍服务状态", hideMaster) {
                        hideMaster = it; saveHide()
                    }
                    SwitchRow("完全伪装无障碍未开启（推荐）", strict) {
                        strict = it; saveHide()
                    }
                    SwitchRow("隐藏已安装服务列表（更彻底）", hideInstalled) {
                        hideInstalled = it; saveHide()
                    }
                    SwitchRow("输出日志（LSPosed 日志过滤 GKDX）", logEnabled) {
                        logEnabled = it; saveHide()
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = targets,
                        onValueChange = { targets = it },
                        label = { Text("额外隐藏目标（每行一个包名，可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 2,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = excludes,
                        onValueChange = { excludes = it },
                        label = { Text("排除的应用（这些应用内不隐藏）") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 2,
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = {
                        saveHide()
                        Toast.makeText(context, "已保存，约 3 秒内热生效", Toast.LENGTH_SHORT).show()
                    }) { Text("保存") }
                }
            }

            item { AboutCard() }
        }
    }

    if (showAddDialog) {
        var urlInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加订阅源") },
            text = {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("订阅 JSON5 地址") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = urlInput.trim()
                    if (url.startsWith("http")) {
                        if (sources.none { it.url == url }) {
                            sources = sources.toMutableList().also { it.add(Source(url)) }
                            ConfigStore.save(context, sources)
                        }
                        showAddDialog = false
                    } else {
                        Toast.makeText(context, "请填写完整 http(s) 地址", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun StatusCard(subsText: String, rootRunning: Boolean, rootStatus: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("运行状态", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            StatusRow("订阅规则", subsText)
            StatusRow("Root 模式", if (rootRunning) rootStatus else "未运行")
            StatusRow("Xposed 模式", "作用域应用启动时自动生效（无规则的应用不装 Hook）")
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            modifier = Modifier.width(84.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AboutCard() {
    SectionCard("关于 / 原作者") {
        Text(
            "GKD 原作者：lisonge（GitHub @lisonge）\n" +
                    "https://github.com/gkd-kit/gkd\n\n" +
                    "选择器引擎：取自 gkd-kit/gkd 的 gkd-selector 模块，原样内嵌，遵循 GPL-3.0\n\n" +
                    "内置社区订阅：甘霖 @ganlinte、AIsouler、Adpro（版权归各自作者）\n\n" +
                    "无障碍隐藏思路参考：AoEiuV020/IAmNotDisabled\n\n" +
                    "本模块：github.com/suiren0219/GKDX（GPL-3.0）\n" +
                    "第三方实现，与 GKD 官方无关联；跳广告的功劳属于 GKD 与各位订阅作者。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(
            "版本 ${io.github.gkdhide.gkdx.BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
