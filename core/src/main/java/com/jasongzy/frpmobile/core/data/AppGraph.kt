package com.jasongzy.frpmobile.core.data

import android.content.Context
import com.jasongzy.frpmobile.core.frp.FrpRuntimeManager
import java.io.File

object AppGraph {
    private val cipher = KeystoreConfigCipher()
    @Volatile private var repository: FrpRepository? = null

    fun repository(context: Context): FrpRepository =
        repository ?: synchronized(this) {
            repository ?: FrpRepository(
                dao = FrpDatabase.get(context).frpDao(),
                settingsStore = SettingsStore(FrpDatabase.get(context)),
                runtimeManager = FrpRuntimeManager(context.applicationContext),
                cipher = cipher,
            ).also { repository = it }
        }

    fun editorDrafts(context: Context) = EditorDraftStore(File(context.noBackupFilesDir, "editor-drafts"), cipher)
}
