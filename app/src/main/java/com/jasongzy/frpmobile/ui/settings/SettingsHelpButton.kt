package com.jasongzy.frpmobile.ui.settings

import com.jasongzy.frpmobile.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.jasongzy.frpmobile.ui.components.FrpIconButton

@Composable
internal fun SettingsHelpButton(onClick: () -> Unit) {
    FrpIconButton(label = stringResource(R.string.settings_help), onClick = onClick) {
        Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = null)
    }
}
