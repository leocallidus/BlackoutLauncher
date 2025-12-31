package com.example.blackoutlauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.blackoutlauncher.ui.theme.BlackoutLauncherTheme
import java.util.Locale

class AppListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val selectedPackage = intent.getStringExtra(EXTRA_SELECTED_PACKAGE)
        setContent {
            BlackoutLauncherTheme {
                AppListScreen(
                    selectedPackage = selectedPackage,
                    onSelect = { pkg ->
                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SELECTED_PACKAGE, pkg))
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_SELECTED_PACKAGE = "extra_selected_package"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppListScreen(
    selectedPackage: String?,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = loadLaunchableApps(packageManager)
    }

    val filteredApps = remember(apps, searchQuery) {
        val query = searchQuery.trim().lowercase(Locale.getDefault())
        if (query.isEmpty()) {
            apps
        } else {
            apps.filter { app ->
                app.label.lowercase(Locale.getDefault()).contains(query) ||
                    app.packageName.lowercase(Locale.getDefault()).contains(query)
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text(text = stringResource(R.string.select_app_title)) })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(text = stringResource(R.string.search_label)) },
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (filteredApps.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.search_empty),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    items(filteredApps, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            selected = app.packageName == selectedPackage,
                            onSelect = { onSelect(it) },
                        )
                    }
                }
            }
        }
    }
}
