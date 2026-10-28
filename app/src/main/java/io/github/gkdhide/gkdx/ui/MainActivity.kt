package io.github.gkdhide.gkdx.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gkdhide.gkdx.BuildConfig
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
            GkdxTheme { GkdxApp() }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Dashboard("仪表盘", Icons.Default.Home),
    Apps("应用", Icons.Default.List),
    Subs("订阅", Icons.Default.Star),
    Settings("设置", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GkdxApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("config", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(Tab.Dashboard) }
    var detailApp by remember { mutableStateOf<String?>(null) }
    var subs by remember { mutableStateOf<List<SubscriptionItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var versions by remember { mutableStateOf(0) }
    var disabled by remember { mutableStateOf(DisabledStore.load(context)) }

    var sources by remember { mutableStateOf(ConfigStore.load(context)) }
    var updating by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }

    var hideMaster by remember { mutableStateOf(prefs.getBoolean("hide_master", true)) }
    var strict by remember { mutableStateOf(prefs.getBoolean("strict", true)) }
    var hideInstalled by remember { mutableStateOf(prefs.getBoolean("hide_installed", false)) }
    var logEnabled by remember { mutableStateOf(prefs.getBoolean("log", false)) }
    var targets by remember { mutableStateOf(prefs.getString("targets", "") ?: "") }
    var excludes by remember { mutableStateOf(prefs.getString("excludes", "") ?: "") }

    LaunchedEffect(versions) {
        loading = true
        subs = RuleRepository.load(context, force = versions > 0)
        loading = false
    }

    fun toggleDisabled(key: String, on: Boolean) {
        disabled = disabled.toMutableSet().also { if (on) it.remove(key) else it.add(key) }
        DisabledStore.save(context, disabled)
    }

    val currentDetail = detailApp
    if (currentDetail != null) {
        BackHandler { detailApp = null }
        AppDetailScreen(
            pkg = currentDetail,
            subs = subs,
            disabled = disabled,
            onToggleGroup = { key, on -> toggleDisabled(key, on) },
            onToggleApp = { pkg, on -> toggleDisabled(DisabledStore.appKey(pkg), on) },
            onBack = { detailApp = null },
        )
    } else {
        Scaffold(
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
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { inner ->
            val padding = Modifier.padding(inner)
            when (tab) {
                Tab.Dashboard -> DashboardTab(subs = subs, loading = loading, modifier = padding)

                Tab.Apps -> AppsTab(
                    subs = subs,
                    loading = loading,
                    disabled = disabled,
                    modifier = padding,
                    onToggle = { pkg, on -> toggleDisabled(DisabledStore.appKey(pkg), on) },
                    onOpen = { detailApp = it },
                )

                Tab.Subs -> SubsTab(
                    sources = sources,
                    updating = updating,
                    updateResult = updateResult,
                    statusText = ConfigStore.lastUpdateText(context),
                    modifier = padding,
                    onToggleSource = { idx, on ->
                        sources = sources.toMutableList().also { it[idx] = it[idx].copy(enabled = on) }
                        ConfigStore.save(context, sources)
                    },
                    onDeleteSource = { idx ->
                        sources = sources.toMutableList().also { it.removeAt(idx) }
                        ConfigStore.save(context, sources)
                    },
                    onAddClick = { showAddDialog = true },
                    onUpdate = {
                        if (sources.none { it.enabled }) {
                            Toast.makeText(context, "请至少启用一个订阅源", Toast.LENGTH_SHORT).show()
                        } else {
                            scope.launch {
                                updating = true
                                updateResult = ""
                                val result = SubscriptionUpdater.update(context, sources)
                                updating = false
                                updateResult =
                                    if (result.ruleCount > 0) "更新完成：${result.summary}"
                                    else "全部订阅源下载失败，请检查网络"
                                RuleRepository.invalidate()
                                versions++
                            }
                        }
                    },
                )

                Tab.Settings -> SettingsTab(
                    hideMaster = hideMaster,
                    strict = strict,
                    hideInstalled = hideInstalled,
                    logEnabled = logEnabled,
                    targets = targets,
                    excludes = excludes,
                    modifier = padding,
                    onHideMaster = { hideMaster = it; saveHide(prefs, it, strict, hideInstalled, logEnabled, targets, excludes) },
                    onStrict = { strict = it; saveHide(prefs, hideMaster, it, hideInstalled, logEnabled, targets, excludes) },
                    onHideInstalled = { hideInstalled = it; saveHide(prefs, hideMaster, strict, it, logEnabled, targets, excludes) },
                    onLog = { logEnabled = it; saveHide(prefs, hideMaster, strict, hideInstalled, it, targets, excludes) },
                    onTargets = { targets = it },
                    onExcludes = { excludes = it },
                    onSaveTexts = {
                        saveHide(prefs, hideMaster, strict, hideInstalled, logEnabled, targets, excludes)
                        Toast.makeText(context, "已保存，约 3 秒内热生效", Toast.LENGTH_SHORT).show()
                    },
                )
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
                        if (!url.startsWith("http")) {
                            Toast.makeText(context, "请填写完整 http(s) 地址", Toast.LENGTH_SHORT).show()
                        } else {
                            if (sources.none { it.url == url }) {
                                sources = sources.toMutableList().also { it.add(Source(url)) }
                                ConfigStore.save(context, sources)
                            }
                            showAddDialog = false
                        }
                    }) { Text("添加") }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) { Text("取消") }
                },
            )
        }
    }
}

private fun saveHide(
    prefs: android.content.SharedPreferences,
    master: Boolean,
    strict: Boolean,
    installed: Boolean,
    log: Boolean,
    targets: String,
    excludes: String,
) {
    prefs.edit()
        .putBoolean("hide_master", master)
        .putBoolean("strict", strict)
        .putBoolean("hide_installed", installed)
        .putBoolean("log", log)
        .putString("targets", targets)
        .putString("excludes", excludes)
        .apply()
}

// ---------------- 仪表盘 ----------------

@Composable
private fun DashboardTab(subs: List<SubscriptionItem>, loading: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rootRunning by remember { mutableStateOf(RootEngine.running) }
    var rootStatus by remember { mutableStateOf(RootEngine.status) }
    val appCount = remember(subs) { subs.sumOf { it.apps.size } }
    val ruleCount = remember(subs) { subs.sumOf { it.ruleCount } }

    LaunchedEffect(Unit) {
        while (true) {
            rootRunning = RootEngine.running
            rootStatus = RootEngine.status
            delay(900)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("运行状态", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    if (loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        StatRow("订阅", "${subs.size} 份")
                        StatRow("应用", "$appCount 个")
                        StatRow("规则", "$ruleCount 条")
                        StatRow("更新", ConfigStore.lastUpdateText(context))
                    }
                }
            }
        }
        item {
            SectionCard("Root 模式") {
                Text(
                    "不勾作用域、不注入任何应用：以 root 读屏并点击，可覆盖 WebView 内部节点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (rootRunning) "运行中" else "未运行",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (rootRunning) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    Toast.makeText(
                                        context,
                                        "未获得 root 权限，请在 root 管理器中授予本应用",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } else {
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
                            }
                        }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.width(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("启动")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
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
            SectionCard("Xposed 模式") {
                Text(
                    "在 LSPosed 中启用本模块，作用域勾选目标应用后强停重开即可。\n" +
                            "订阅里没有规则的应用不会安装任何 Hook，作用域可以放心多勾。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            modifier = Modifier.width(56.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------- 应用列表 ----------------

private data class AppRow(
    val id: String,
    val label: String?,
    val groupCount: Int,
    val ruleCount: Int,
)

@Composable
private fun AppsTab(
    subs: List<SubscriptionItem>,
    loading: Boolean,
    disabled: Set<String>,
    modifier: Modifier,
    onToggle: (String, Boolean) -> Unit,
    onOpen: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val rows = remember(subs) {
        val map = LinkedHashMap<String, AppRow>()
        subs.forEach { sub ->
            sub.apps.forEach { app ->
                val old = map[app.id]
                map[app.id] = AppRow(
                    id = app.id,
                    label = app.label ?: old?.label,
                    groupCount = (old?.groupCount ?: 0) + app.groups.size,
                    ruleCount = (old?.ruleCount ?: 0) + app.ruleCount,
                )
            }
        }
        map.values.sortedBy { (it.label ?: it.id).lowercase() }
    }
    val filtered = remember(rows, query) {
        if (query.isBlank()) rows
        else rows.filter { it.id.contains(query, true) || (it.label?.contains(query, true) == true) }
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索应用") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            "共 ${filtered.size} 个应用（点击进入规则组）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(filtered, key = { it.id }) { row ->
                val enabled = !DisabledStore.isAppDisabled(disabled, row.id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(row.id) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.label ?: row.id,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${row.id} · ${row.groupCount} 组 / ${row.ruleCount} 条",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (row.id != RuleRepository.GLOBAL_APP_ID) {
                        Switch(checked = enabled, onCheckedChange = { onToggle(row.id, it) })
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

// ---------------- 应用详情（规则组） ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDetailScreen(
    pkg: String,
    subs: List<SubscriptionItem>,
    disabled: Set<String>,
    onToggleGroup: (String, Boolean) -> Unit,
    onToggleApp: (String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val app = remember(subs, pkg) { subs.flatMap { it.apps }.firstOrNull { it.id == pkg } }
    val groups = remember(app) { app?.groups.orEmpty().distinctBy { it.uid } }
    val isGlobal = pkg == RuleRepository.GLOBAL_APP_ID
    val appEnabled = !DisabledStore.isAppDisabled(disabled, pkg)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(app?.label ?: pkg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            pkg,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
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
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (isGlobal) "全局规则组（对所有应用生效）" else "本应用全部规则",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "${groups.size} 个规则组 / ${app?.ruleCount ?: 0} 条规则",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                            )
                        }
                        if (!isGlobal) {
                            Switch(checked = appEnabled, onCheckedChange = { onToggleApp(pkg, it) })
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            items(groups, key = { it.uid }) { group ->
                val groupEnabled = !disabled.contains(group.disabledKey)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                        Text(
                            "${group.ruleCount} 条" + if (!group.desc.isNullOrBlank()) " · ${group.desc}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = groupEnabled && (isGlobal || appEnabled),
                        enabled = isGlobal || appEnabled,
                        onCheckedChange = { onToggleGroup(group.disabledKey, it) },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

// ---------------- 订阅管理 ----------------

@Composable
private fun SubsTab(
    sources: List<Source>,
    updating: Boolean,
    updateResult: String,
    statusText: String,
    modifier: Modifier,
    onToggleSource: (Int, Boolean) -> Unit,
    onDeleteSource: (Int) -> Unit,
    onAddClick: () -> Unit,
    onUpdate: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard("订阅源") {
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                sources.forEachIndexed { index, source ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = source.enabled, onCheckedChange = { onToggleSource(index, it) })
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
                        IconButton(onClick = { onDeleteSource(index) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAddClick) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.width(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加")
                    }
                    Button(onClick = onUpdate, enabled = !updating) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.width(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (updating) "更新中…" else "更新订阅")
                    }
                }
                if (updating) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (updateResult.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        updateResult,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (updateResult.startsWith("更新完成")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        item {
            SectionCard("说明") {
                Text(
                    "订阅格式与 GKD 完全一致（JSON5）。更新后规则立即生效；已下载的订阅保存在本地，离线也能用。\n" +
                            "想加更多订阅：在订阅作者的发布页复制 gkd.json5 地址，点\"添加\"粘贴即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------- 设置 ----------------

@Composable
private fun SettingsTab(
    hideMaster: Boolean,
    strict: Boolean,
    hideInstalled: Boolean,
    logEnabled: Boolean,
    targets: String,
    excludes: String,
    modifier: Modifier,
    onHideMaster: (Boolean) -> Unit,
    onStrict: (Boolean) -> Unit,
    onHideInstalled: (Boolean) -> Unit,
    onLog: (Boolean) -> Unit,
    onTargets: (String) -> Unit,
    onExcludes: (String) -> Unit,
    onSaveTexts: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard("无障碍隐藏") {
                Text(
                    "同时使用 GKD 本体时生效：在作用域应用里隐藏 GKD 的无障碍状态，默认隐藏 li.songe.gkd。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow("对外隐藏 GKD 的无障碍服务状态", hideMaster, onHideMaster)
                SwitchRow("完全伪装无障碍未开启（推荐）", strict, onStrict)
                SwitchRow("隐藏已安装服务列表（更彻底）", hideInstalled, onHideInstalled)
                SwitchRow("输出日志（LSPosed 日志过滤 GKDX）", logEnabled, onLog)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = targets,
                    onValueChange = onTargets,
                    label = { Text("额外隐藏目标（每行一个包名，可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = excludes,
                    onValueChange = onExcludes,
                    label = { Text("排除的应用（这些应用内不隐藏）") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onSaveTexts) { Text("保存") }
            }
        }
        item { UpdateCard() }
        item { AboutCard() }
    }
}

@Composable
private fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<UpdateChecker.Info?>(null) }

    SectionCard("更新") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "当前版本 ${BuildConfig.VERSION_NAME}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = {
                    if (checking) return@Button
                    scope.launch {
                        checking = true
                        message = ""
                        try {
                            val latest = UpdateChecker.check()
                            info = latest
                            message = if (UpdateChecker.isNewer(latest.tagName, BuildConfig.VERSION_NAME)) {
                                "发现新版本 ${latest.tagName}"
                            } else {
                                "已是最新版本"
                            }
                        } catch (t: Throwable) {
                            message = "检查失败：${t.message ?: "网络错误"}（GitHub 直连可能需要代理）"
                        }
                        checking = false
                    }
                },
                enabled = !checking,
            ) { Text(if (checking) "检查中…" else "检查更新") }
        }
        if (checking) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (message.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (message.startsWith("发现")) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        info?.let { i ->
            if (i.notes.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    i.notes,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASE_PAGE)))
                }) { Text("打开发布页") }
                if (UpdateChecker.isNewer(i.tagName, BuildConfig.VERSION_NAME)) {
                    Button(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(i.apkUrl ?: UpdateChecker.RELEASE_PAGE)))
                    }) { Text("下载新版本") }
                }
            }
        }
    }
}

// ---------------- 通用组件 ----------------

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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
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
            "版本 ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
