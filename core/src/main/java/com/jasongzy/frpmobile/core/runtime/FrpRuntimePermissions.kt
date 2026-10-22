package com.jasongzy.frpmobile.core.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object FrpRuntimePermissions {
    private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    fun canStart(context: Context): Boolean = Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    fun missingPermissions(context: Context, rootMode: Boolean): Array<String> =
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!rootMode && Build.VERSION.SDK_INT >= 37) {
                add(ACCESS_LOCAL_NETWORK)
            }
        }.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
}
