package com.example.blackoutlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.blackoutlauncher.ui.theme.BlackoutLauncherTheme
import java.util.Locale

data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BlackoutLauncherTheme {
                BlackoutLauncherScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlackoutLauncherScreen() {
    val context = LocalContext.current
    val activity = context as Activity
    val packageManager = context.packageManager
    var selectedPackage by remember { mutableStateOf(loadSelectedPackage(context)) }
    var selectedApp by remember { mutableStateOf(loadAppEntry(context, selectedPackage)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var writeGranted by remember { mutableStateOf(Settings.System.canWrite(context)) }
    var hapticAvailable by remember { mutableStateOf(hasVibrator(context)) }
    var hapticEnabled by remember { mutableStateOf(hasVibrator(context)) }
    var exitTapCount by remember { mutableStateOf(loadExitTapCount(context)) }
    val versionName = remember { loadVersionName(context) }
    val appPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val pkg = result.data?.getStringExtra(AppListActivity.EXTRA_SELECTED_PACKAGE)
                if (!pkg.isNullOrBlank()) {
                    selectedPackage = pkg
                    saveSelectedPackage(context, pkg)
                    selectedApp = loadAppEntry(context, pkg)
                }
            }
        }

    fun refreshState() {
        overlayGranted = Settings.canDrawOverlays(context)
        writeGranted = Settings.System.canWrite(context)
        hapticAvailable = hasVibrator(context)
        hapticEnabled = hapticAvailable
        exitTapCount = loadExitTapCount(context)
        val storedPackage = loadSelectedPackage(context)
        selectedPackage = storedPackage
        selectedApp = loadAppEntry(context, storedPackage)
        if (storedPackage != null && selectedApp == null) {
            selectedPackage = null
            saveSelectedPackage(context, null)
        }
    }

    LaunchedEffect(Unit) {
        refreshState()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val startEnabled = selectedPackage != null && overlayGranted && writeGranted

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text(text = stringResource(R.string.app_name)) })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            PermissionRow(
                title = stringResource(R.string.permission_overlay_title),
                granted = overlayGranted,
                onRequest = { requestOverlayPermission(activity) },
            )
            Spacer(modifier = Modifier.height(8.dp))
            PermissionRow(
                title = stringResource(R.string.permission_write_settings_title),
                granted = writeGranted,
                onRequest = { requestWriteSettingsPermission(activity) },
            )
            Spacer(modifier = Modifier.height(12.dp))
            VibrationRow(
                available = hapticAvailable,
                enabled = hapticEnabled,
                onOpenSettings = { openVibrationSettings(activity) },
            )
            Spacer(modifier = Modifier.height(12.dp))
            TapCountRow(
                count = exitTapCount,
                onDecrease = {
                    val next = (exitTapCount - 1).coerceAtLeast(MIN_EXIT_TAP_COUNT)
                    if (next != exitTapCount) {
                        exitTapCount = next
                        saveExitTapCount(context, next)
                    }
                },
                onIncrease = {
                    val next = (exitTapCount + 1).coerceAtMost(MAX_EXIT_TAP_COUNT)
                    if (next != exitTapCount) {
                        exitTapCount = next
                        saveExitTapCount(context, next)
                    }
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
            Divider()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.selected_app_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            SelectedAppCard(
                app = selectedApp,
                onChoose = {
                    val intent = Intent(context, AppListActivity::class.java).putExtra(
                        AppListActivity.EXTRA_SELECTED_PACKAGE,
                        selectedPackage,
                    )
                    appPickerLauncher.launch(intent)
                },
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    startBlackout(
                        context = context,
                        packageManager = packageManager,
                        packageName = selectedPackage,
                        overlayGranted = overlayGranted,
                        writeGranted = writeGranted,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = startEnabled,
            ) {
                Text(text = stringResource(R.string.start_button))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Divider()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.about_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            AboutCard(
                versionName = versionName,
                onOpenLink = { openGithub(context) },
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    val statusText =
        if (granted) stringResource(R.string.permission_granted)
        else stringResource(R.string.permission_required)
    val actionText =
        if (granted) stringResource(R.string.permission_granted)
        else stringResource(R.string.permission_grant)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = onRequest, enabled = !granted) {
            Text(text = actionText)
        }
    }
}

@Composable
private fun VibrationRow(
    available: Boolean,
    enabled: Boolean,
    onOpenSettings: () -> Unit,
) {
    val statusText = when {
        !available -> stringResource(R.string.vibration_status_unavailable)
        enabled -> stringResource(R.string.vibration_status_on)
        else -> stringResource(R.string.vibration_status_off)
    }
    val statusColor = when {
        !available -> MaterialTheme.colorScheme.onSurfaceVariant
        enabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.permission_vibration_title), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
            )
        }
        Button(onClick = onOpenSettings, enabled = available) {
            Text(text = stringResource(R.string.vibration_open_settings))
        }
    }
}

@Composable
private fun TapCountRow(
    count: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.exit_tap_count_title), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.exit_tap_count_value, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onDecrease, enabled = count > MIN_EXIT_TAP_COUNT) {
                Text(text = "-")
            }
            Button(onClick = onIncrease, enabled = count < MAX_EXIT_TAP_COUNT) {
                Text(text = "+")
            }
        }
    }
}

@Composable
private fun SelectedAppCard(
    app: AppEntry?,
    onChoose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (app != null) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                update = { imageView ->
                    imageView.setImageDrawable(app.icon)
                },
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.no_app_selected),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Button(onClick = onChoose) {
            Text(text = stringResource(R.string.select_app_button))
        }
    }
}

@Composable
private fun AboutCard(
    versionName: String,
    onOpenLink: () -> Unit,
) {
    val context = LocalContext.current
    val appIcon = remember {
        context.applicationInfo.loadIcon(context.packageManager)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onOpenLink() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { imageView ->
                imageView.setImageDrawable(appIcon)
            },
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.version_label, versionName),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.developer_label, stringResource(R.string.developer_name)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun AppRow(
    app: AppEntry,
    selected: Boolean,
    onSelect: (String) -> Unit,
) {
    val background =
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val textColor =
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val secondaryTextColor =
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable { onSelect(app.packageName) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { imageView ->
                imageView.setImageDrawable(app.icon)
            },
            modifier = Modifier.size(40.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = app.label, style = MaterialTheme.typography.bodyLarge, color = textColor)
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = secondaryTextColor,
            )
        }
    }
}

fun loadLaunchableApps(packageManager: PackageManager): List<AppEntry> {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }
    val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
    }
    val initialList = resolveInfos.map { info ->
        AppEntry(
            label = info.loadLabel(packageManager).toString(),
            packageName = info.activityInfo.packageName,
            icon = info.loadIcon(packageManager),
        )
    }
    if (initialList.size > 1) {
        return initialList.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }
    val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getInstalledApplications(
            PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getInstalledApplications(PackageManager.MATCH_ALL)
    }
    val fallbackList = installedApps.mapNotNull { appInfo ->
        val launchIntent = packageManager.getLaunchIntentForPackage(appInfo.packageName)
        if (launchIntent != null) {
            AppEntry(
                label = appInfo.loadLabel(packageManager).toString(),
                packageName = appInfo.packageName,
                icon = appInfo.loadIcon(packageManager),
            )
        } else {
            null
        }
    }
    return fallbackList
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase(Locale.getDefault()) }
}

private fun requestOverlayPermission(activity: Activity) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${activity.packageName}"),
    )
    activity.startActivity(intent)
}

private fun requestWriteSettingsPermission(activity: Activity) {
    val intent = Intent(
        Settings.ACTION_MANAGE_WRITE_SETTINGS,
        Uri.parse("package:${activity.packageName}"),
    )
    activity.startActivity(intent)
}

private fun openVibrationSettings(activity: Activity) {
    activity.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
}

private fun loadAppEntry(context: Context, packageName: String?): AppEntry? {
    if (packageName.isNullOrBlank()) {
        return null
    }
    val packageManager = context.packageManager
    return runCatching {
        val appInfo = packageManager.getApplicationInfo(packageName, 0)
        AppEntry(
            label = appInfo.loadLabel(packageManager).toString(),
            packageName = packageName,
            icon = appInfo.loadIcon(packageManager),
        )
    }.getOrNull()
}

private fun loadVersionName(context: Context): String {
    val packageManager = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(context.packageName, 0)
    }
    return info.versionName ?: "1.0"
}

private fun openGithub(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
    context.startActivity(intent)
}

private fun startBlackout(
    context: Context,
    packageManager: PackageManager,
    packageName: String?,
    overlayGranted: Boolean,
    writeGranted: Boolean,
) {
    if (packageName == null) {
        Toast.makeText(
            context,
            context.getString(R.string.toast_select_app),
            Toast.LENGTH_SHORT,
        ).show()
        return
    }
    if (!overlayGranted || !writeGranted) {
        Toast.makeText(
            context,
            context.getString(R.string.toast_grant_permissions),
            Toast.LENGTH_SHORT,
        ).show()
        return
    }
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
    if (launchIntent == null) {
        Toast.makeText(
            context,
            context.getString(R.string.toast_launch_failed),
            Toast.LENGTH_SHORT,
        ).show()
        return
    }
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(launchIntent)
    val serviceIntent = Intent(context, BlackoutService::class.java).putExtra(
        BlackoutService.EXTRA_TARGET_PACKAGE,
        packageName,
    )
    ContextCompat.startForegroundService(context, serviceIntent)
}

private fun preferences(context: Context): SharedPreferences {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

private fun loadSelectedPackage(context: Context): String? {
    return preferences(context).getString(KEY_SELECTED_PACKAGE, null)
}

private fun saveSelectedPackage(context: Context, packageName: String?) {
    preferences(context).edit().putString(KEY_SELECTED_PACKAGE, packageName).apply()
}

private fun loadExitTapCount(context: Context): Int {
    return preferences(context)
        .getInt(KEY_EXIT_TAP_COUNT, DEFAULT_EXIT_TAP_COUNT)
        .coerceIn(MIN_EXIT_TAP_COUNT, MAX_EXIT_TAP_COUNT)
}

private fun saveExitTapCount(context: Context, count: Int) {
    preferences(context).edit().putInt(KEY_EXIT_TAP_COUNT, count).apply()
}

private fun hasVibrator(context: Context): Boolean {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    return vibrator.hasVibrator()
}

private const val PREFS_NAME = "blackout_launcher_prefs"
private const val KEY_SELECTED_PACKAGE = "selected_package"
private const val KEY_EXIT_TAP_COUNT = "exit_tap_count"
private const val DEFAULT_EXIT_TAP_COUNT = 2
private const val MIN_EXIT_TAP_COUNT = 1
private const val MAX_EXIT_TAP_COUNT = 6
private const val GITHUB_URL = "https://github.com/leocallidus"
