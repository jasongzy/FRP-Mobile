package com.jasongzy.frpmobile.integration.home

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.annotation.SuppressLint
import com.jasongzy.frpmobile.core.runtime.AutomationCommand
import com.jasongzy.frpmobile.R
import com.jasongzy.frpmobile.core.frp.LanguageMode
import com.jasongzy.frpmobile.integration.operationLabel
import com.jasongzy.frpmobile.integration.targetName
import java.util.Locale

internal const val EXTRA_HOME_ACTION = "home_action"

internal fun AutomationCommand.encodeHomeAction(): String = "$operation:$profileId:$target"

internal fun AutomationCommand.homeIntent(context: Context): Intent = Intent(context, HomeActionActivity::class.java)
    .setAction(Intent.ACTION_VIEW)
    .putExtra(EXTRA_HOME_ACTION, encodeHomeAction())
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)

internal fun decodeHomeAction(value: String?): AutomationCommand? {
    val parts = value?.split(':') ?: return null
    if (parts.size != 3) return null
    return try {
        AutomationCommand(parts[0], parts[1], parts[2])
    } catch (_: IllegalArgumentException) { null }
}

internal fun operationDrawable(operation: String) = when (operation) {
    "toggle" -> R.drawable.ic_home_toggle
    "start" -> R.drawable.ic_home_start
    "restart" -> R.drawable.ic_home_restart
    else -> R.drawable.ic_home_stop
}

internal fun homeOperationLabel(operation: String) = when (operation) {
    "toggle" -> R.string.home_toggle
    "stopAll" -> R.string.tasker_stop
    else -> operationLabel(operation)
}

internal fun AutomationCommand.homeTargetName(context: Context, profileName: String?) =
    if (target == "autoStart") context.getString(R.string.home_target_autostart) else targetName(context, profileName)

@SuppressLint("AppBundleLocaleChanges") // Releases are distributed as universal APKs.
internal fun Context.homeContext(language: LanguageMode): Context {
    val locale = when (language) {
        LanguageMode.System -> android.content.res.Resources.getSystem().configuration.locales[0]
        LanguageMode.Chinese -> Locale.SIMPLIFIED_CHINESE
        LanguageMode.English -> Locale.ENGLISH
    }
    return createConfigurationContext(Configuration(resources.configuration).apply { setLocale(locale) })
}
