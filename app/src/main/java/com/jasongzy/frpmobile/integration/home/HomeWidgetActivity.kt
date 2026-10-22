package com.jasongzy.frpmobile.integration.home

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.jasongzy.frpmobile.core.runtime.AutomationCommand
import com.jasongzy.frpmobile.integration.AUTO_START_TARGET
import com.jasongzy.frpmobile.integration.ControlActionFields
import com.jasongzy.frpmobile.integration.controlCommand
import com.jasongzy.frpmobile.integration.selectedId
import com.jasongzy.frpmobile.R
import com.jasongzy.frpmobile.core.data.AppGraph
import com.jasongzy.frpmobile.ui.components.FrpIconButton
import com.jasongzy.frpmobile.ui.theme.FrpMobileTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeWidgetViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppGraph.repository(application)
    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val profiles = repository.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val saved = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    fun save(id: Int, action: AutomationCommand) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    check(id in HomeWidgets.ids(context)) { "WIDGET_MISSING" }
                    check(action.profileId.isEmpty() || repository.getProfile(action.profileId) != null) { context.getString(R.string.home_profile_missing) }
                    HomeWidgets.save(context, id, action)
                    HomeWidgets.refresh(context.homeContext(repository.settings.first().languageMode), intArrayOf(id))
                }
                saved.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: e.javaClass.simpleName
            } finally { busy.value = false }
        }
    }
}

class HomeWidgetActivity : AppCompatActivity() {
    private val model: HomeWidgetViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        setResult(RESULT_CANCELED, result)
        if (id !in HomeWidgets.ids(this)) { finish(); return }
        enableEdgeToEdge()
        lifecycleScope.launch {
            val initial = withContext(Dispatchers.IO) { HomeWidgets.read(this@HomeWidgetActivity, id) }
            setContent {
                val settings by model.settings.collectAsStateWithLifecycle()
                val profiles by model.profiles.collectAsStateWithLifecycle()
                val busy by model.busy.collectAsStateWithLifecycle()
                var selectedId by rememberSaveable { mutableStateOf(initial?.selectedId ?: AUTO_START_TARGET) }
                var operation by rememberSaveable { mutableStateOf(initial?.operation ?: "toggle") }
                settings?.let {
                    FrpMobileTheme(it.themeSeedColor, it.themeMode, it.languageMode) {
                        Scaffold(topBar = {
                            TopAppBar(title = { Text(stringResource(R.string.home_widget)) }, navigationIcon = {
                                FrpIconButton(stringResource(android.R.string.cancel), { finish() }) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                                }
                            }, actions = {
                                TextButton(enabled = !busy && (operation == "stopAll" || selectedId == AUTO_START_TARGET || profiles.any { profile -> profile.id == selectedId }),
                                    onClick = { model.save(id, controlCommand(operation, selectedId)) }) {
                                    Text(stringResource(R.string.tasker_save))
                                }
                            })
                        }) { padding ->
                            Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
                                ControlActionFields(profiles, operation, selectedId, { operation = it }, { selectedId = it }, !busy)
                                if (busy) CircularWavyProgressIndicator(Modifier.padding(16.dp))
                            }
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            model.saved.collect { if (it) { setResult(RESULT_OK, result); finish() } }
        }
        lifecycleScope.launch {
            model.error.collect { error ->
                if (error != null) {
                    Toast.makeText(this@HomeWidgetActivity, getString(R.string.settings_operation_failed, error), Toast.LENGTH_LONG).show()
                    model.error.value = null
                }
            }
        }
    }
}
