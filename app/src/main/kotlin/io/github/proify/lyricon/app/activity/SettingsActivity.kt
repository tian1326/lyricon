/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.app.activity

import android.content.Intent
import android.os.Build
import android.os.Bundle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.proify.android.extensions.defaultSharedPreferences
import io.github.proify.android.extensions.inflate
import io.github.proify.lyricon.app.AppBackup
import io.github.proify.lyricon.app.BuildConfig
import io.github.proify.lyricon.app.LyriconApp
import io.github.proify.lyricon.app.R
import io.github.proify.lyricon.app.bridge.AppBridgeConstants
import io.github.proify.lyricon.app.bridge.LyriconBridge
import io.github.proify.lyricon.common.PackageNames
import io.github.proify.lyricon.app.compose.AppToolBarListContainer
import io.github.proify.lyricon.app.compose.IconActions
import io.github.proify.lyricon.app.compose.preference.rememberBooleanPreference
import io.github.proify.lyricon.app.event.SettingChangedEvent
import io.github.proify.lyricon.app.util.AppLangUtils
import io.github.proify.lyricon.app.util.AppThemeUtils
import io.github.proify.lyricon.app.util.EventBus
import io.github.proify.lyricon.app.util.resolveLanguageName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

class SettingsActivity : BaseActivity() {

    private val settingsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val backupExportLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            uri ?: return@registerForActivityResult
            applySettingsWithNotify {
                contentResolver.openOutputStream(uri)?.let { output ->
                    AppBackup.export(output)
                }
            }
        }

    private val backupImportLauncher =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri ?: return@registerForActivityResult
            applySettingsWithNotify {
                contentResolver.openInputStream(uri)?.let { input ->
                    AppBackup.restore(input)
                }
            }
        }

    /** 待写入的日志正文（取到日志后再让用户选择保存位置） */
    @Volatile
    private var pendingLog: String? = null

    private val logExportLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain")
        ) { uri ->
            uri ?: return@registerForActivityResult
            val content = pendingLog ?: return@registerForActivityResult
            pendingLog = null

            settingsScope.launch {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(content.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }

    /**
     * 导出日志：向 SystemUI 进程索取其内存日志缓冲，再让用户选择保存位置。
     *
     * 模块未激活或跨进程调用失败时，仍然导出一份 App 侧信息 + 排查提示，
     * 避免点了没反应。
     */
    private fun exportLog() {
        settingsScope.launch {
            val systemUiLog = runCatching {
                val response = LyriconBridge.with(this@SettingsActivity)
                    .to(PackageNames.SYSTEM_UI)
                    .key(AppBridgeConstants.REQUEST_EXPORT_LOG)
                    .await(5000)

                response.getByteArray("result")?.inflate()?.toString(Charsets.UTF_8)
            }.getOrNull()

            val content = systemUiLog ?: buildUnavailableLog()

            pendingLog = content
            withContext(Dispatchers.Main) {
                logExportLauncher.launch("lyricon_log_${logFileTimestamp()}.txt")
            }
        }
    }

    /** 模块无法提供日志时的兜底内容，至少告诉用户下一步该看什么 */
    private fun buildUnavailableLog(): String = buildString {
        appendLine("===== Lyricon 日志导出 =====")
        appendLine("无法从 SystemUI 进程获取日志。")
        appendLine()
        appendLine("可能原因：")
        appendLine("1. 模块未激活（检查 LSPosed 中是否已启用本模块并勾选 SystemUI）")
        appendLine("2. SystemUI 尚未重启，模块代码还没加载")
        appendLine("3. 模块进入了安全模式")
        appendLine()
        appendLine("App 版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("安全模式: ${LyriconApp.isSafeMode}")
        appendLine("导出时间: ${logFileTimestamp()}")
        appendLine()
        appendLine("提示：也可通过 adb 直接抓取日志：adb logcat -s Lyricon")
    }

    private fun logFileTimestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun applySettingsWithNotify(
        task: CoroutineScope.() -> Unit
    ) {
        settingsScope.launch {
            task()
            withContext(Dispatchers.Main) {
                EventBus.post(SettingChangedEvent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SettingsScreen(
                onSettingsApplied = ::restartSelf,
                onBackupExport = { backupExportLauncher.launch("lyricon_backup.bin") },
                onBackupImport = { backupImportLauncher.launch(arrayOf("*/*")) },
                onExportLog = ::exportLog
            )
        }
    }

    private fun restartSelf() {
        EventBus.post(SettingChangedEvent)
        startActivity(Intent(this, SettingsActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        finish()
    }


    @Composable
    private fun SettingsScreen(
        onSettingsApplied: () -> Unit,
        onBackupExport: () -> Unit,
        onBackupImport: () -> Unit,
        onExportLog: () -> Unit
    ) {
        AppToolBarListContainer(
            title = stringResource(R.string.activity_settings),
            canBack = true
        ) {
            item("language") {
                SettingsSectionCard {
                    LanguageSetting(onSettingsApplied)
                }
            }
            item("theme") {
                SettingsSectionCard(topPadding = 16.dp) {
                    ThemeSetting(onSettingsApplied)
                }
            }
            item("core_service") {
                SettingsSectionCard(topPadding = 16.dp) {
                    CoreServiceSetting()
                }
            }
            item("backup") {
                SettingsSectionCard(topPadding = 16.dp) {
                    BackupSetting(onBackupExport, onBackupImport)
                }
            }
            item("diagnostics") {
                SettingsSectionCard(topPadding = 16.dp) {
                    DiagnosticsSetting(onExportLog)
                }
            }
        }
    }

    @Composable
    private fun DiagnosticsSetting(onExportLog: () -> Unit) {
        ArrowPreference(
            startAction = { IconActions(painterResource(R.drawable.file_24px)) },
            title = stringResource(R.string.item_export_log),
            summary = stringResource(R.string.item_summary_export_log),
            onClick = onExportLog
        )
    }

    @Composable
    private fun CoreServiceSetting() {
        val context = LocalContext.current
        var enable by rememberBooleanPreference(
            context.defaultSharedPreferences,
            "core_service_disable",
            false
        )

        SwitchPreference(
            checked = enable,
            startAction = { IconActions(painterResource(R.drawable.ic_core_bear)) },
            title = stringResource(R.string.item_disable_builtin_services),
            summary = stringResource(R.string.item_summary_disable_builtin_services),
            onCheckedChange = {
                enable = it
            }
        )
    }

    @Composable
    private fun BackupSetting(
        onExport: () -> Unit,
        onImport: () -> Unit
    ) {
        ArrowPreference(
            startAction = { IconActions(painterResource(R.drawable.ic_save)) },
            title = stringResource(R.string.item_app_backup),
            onClick = onExport
        )
        ArrowPreference(
            startAction = { IconActions(painterResource(R.drawable.ic_settings_backup_restore)) },
            title = stringResource(R.string.item_app_restore),
            onClick = onImport
        )
    }

    @Composable
    private fun SettingsSectionCard(
        topPadding: Dp = 0.dp,
        content: @Composable () -> Unit
    ) {
        Card(
            modifier = Modifier
                .padding(start = 16.dp, top = topPadding, end = 16.dp)
                .fillMaxWidth()
        ) {
            content()
        }
    }

    @Composable
    private fun ThemeSetting(onApplied: () -> Unit) {
        val context = LocalContext.current

        val themeModeOptions = listOf(
            R.string.option_app_theme_mode_system to AppThemeUtils.MODE_SYSTEM,
            R.string.option_app_theme_mode_light to AppThemeUtils.MODE_LIGHT,
            R.string.option_app_theme_mode_dark to AppThemeUtils.MODE_DARK
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val monetEnabled = remember {
                AppThemeUtils.isEnableMonet(context)
            }
            SwitchPreference(
                startAction = { IconActions(painterResource(R.drawable.ic_palette)) },
                title = stringResource(R.string.item_app_theme_monet_color),
                checked = monetEnabled,
                onCheckedChange = {
                    AppThemeUtils.setEnableMonet(context, it)
                    onApplied()
                }
            )
        }

        val currentThemeMode = remember {
            AppThemeUtils.getMode(context)
        }

        val selectedIndex = remember(currentThemeMode) {
            themeModeOptions.indexOfFirst { it.second == currentThemeMode }
                .coerceAtLeast(0)
        }

        OverlayDropdownPreference(
            startAction = { IconActions(painterResource(R.drawable.ic_routine)) },
            title = stringResource(R.string.item_app_theme_mode),
            items = themeModeOptions.map { stringResource(it.first) },
            selectedIndex = selectedIndex,
            onSelectedIndexChange = { index ->
                if (index != selectedIndex) {
                    AppThemeUtils.setMode(context, themeModeOptions[index].second)
                    onApplied()
                }
            }
        )
    }

    @Composable
    private fun LanguageSetting(onApplied: () -> Unit) {
        val context = LocalContext.current

        val languageCodes = remember {
            buildList {
                addAll(getSupportedLanguageCodes().sorted())
                add(0, AppLangUtils.DEFAULT_LANGUAGE)
            }
        }

        val currentLanguage = remember {
            AppLangUtils.getCustomizeLang(context)
        }

        val spinnerItems = remember(languageCodes) {
            languageCodes.map { code ->
                val primaryName = context.resolveLanguageName(code)
                val fallbackName =
                    context.resolveLanguageName(code, AppLangUtils.DEFAULT_LOCALE)
                DropdownItem(
                    title = primaryName,
                    summary = if (primaryName == fallbackName) null else fallbackName
                )
            }
        }

        val selectedIndex = remember(currentLanguage) {
            languageCodes.indexOf(currentLanguage).coerceAtLeast(0)
        }

        OverlaySpinnerPreference(
            startAction = { IconActions(painterResource(R.drawable.ic_language)) },
            title = stringResource(R.string.item_app_language),
            items = spinnerItems,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = { index ->
                AppLangUtils.saveCustomizeLanguage(context, languageCodes[index])
                onApplied()
            }
        )
    }

    private fun getSupportedLanguageCodes(): List<String> =
        LyriconApp.instance.resources.getStringArray(R.array.language_codes).toList()

}
