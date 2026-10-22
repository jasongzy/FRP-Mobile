package com.jasongzy.frpmobile.integration.home

import android.content.ComponentName
import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Canvas
import android.graphics.drawable.Icon
import androidx.annotation.RequiresApi
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import com.jasongzy.frpmobile.MainActivity
import com.jasongzy.frpmobile.core.runtime.AutomationCommand
import com.jasongzy.frpmobile.integration.targetName
import com.jasongzy.frpmobile.R
import java.util.UUID
import kotlin.math.roundToInt

@RequiresApi(25)
internal object HomeShortcuts {
    fun list(context: Context): List<ShortcutInfo> = manager(context).dynamicShortcuts.sortedBy { it.rank }

    fun limit(context: Context): Int = minOf(4, manager(context).maxShortcutCountPerActivity)

    fun save(context: Context, id: String?, action: AutomationCommand, profileName: String?) {
        val shortcuts = list(context)
        check(id != null || shortcuts.size < limit(context)) { context.getString(R.string.home_shortcuts_limit) }
        val shortcut = build(context, id ?: UUID.randomUUID().toString(), action, profileName,
            shortcuts.firstOrNull { it.id == id }?.rank ?: shortcuts.size)
        check(manager(context).addDynamicShortcuts(listOf(shortcut))) { context.getString(R.string.home_shortcuts_busy) }
    }

    fun remove(context: Context, id: String) {
        manager(context).disableShortcuts(listOf(id), context.getString(R.string.home_shortcut_removed))
    }

    @RequiresApi(26)
    fun canPin(context: Context): Boolean = manager(context).isRequestPinShortcutSupported

    @RequiresApi(26)
    fun pin(context: Context, action: AutomationCommand, name: String?): Boolean = manager(context).requestPinShortcut(
        build(context, UUID.randomUUID().toString(), action, name, 0), null)

    fun refresh(context: Context, profiles: Map<String, String>) {
        val manager = manager(context)
        val shortcuts = (manager.dynamicShortcuts + manager.pinnedShortcuts).distinctBy { it.id }.filter { it.isEnabled }
        val missing = mutableListOf<String>()
        val updates = shortcuts.mapNotNull { shortcut ->
            val action = decodeHomeAction(shortcut.intent?.getStringExtra(EXTRA_HOME_ACTION)) ?: return@mapNotNull null
            val name = profiles[action.profileId]
            if (action.profileId.isNotEmpty() && name == null) {
                missing += shortcut.id
                null
            } else build(context, shortcut.id, action, name, shortcut.rank)
        }
        if (missing.isNotEmpty()) manager.disableShortcuts(missing, context.getString(R.string.home_profile_missing))
        if (updates.isNotEmpty()) manager.updateShortcuts(updates)
    }

    private fun manager(context: Context) = context.getSystemService(ShortcutManager::class.java)

    private fun build(context: Context, id: String, action: AutomationCommand, name: String?, rank: Int): ShortcutInfo =
        ShortcutInfo.Builder(context, id)
            .setActivity(ComponentName(context, MainActivity::class.java))
            .setShortLabel(context.getString(R.string.home_action_label, action.homeTargetName(context, name),
                context.getString(homeOperationLabel(action.operation))))
            .setLongLabel(context.getString(R.string.home_action_label, action.targetName(context, name),
                context.getString(homeOperationLabel(action.operation))))
            .setIcon(icon(context, action.operation))
            .setIntent(action.homeIntent(context))
            .setRank(rank)
            .build()

    private fun icon(context: Context, operation: String): Icon {
        val size = (108 * context.resources.displayMetrics.density).roundToInt()
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.drawColor(context.getColor(R.color.launcher_background))
        val glyph = requireNotNull(AppCompatResources.getDrawable(context, operationDrawable(operation))).mutate()
        glyph.setTint(context.getColor(R.color.home_shortcut_foreground))
        val inset = size / 4
        glyph.setBounds(inset, inset, size - inset, size - inset)
        glyph.draw(canvas)
        return IconCompat.createWithAdaptiveBitmap(bitmap).toIcon(context)
    }
}
