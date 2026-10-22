package com.jasongzy.frpmobile.core.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.jasongzy.frpmobile.core.data.AppGraph
import com.jasongzy.frpmobile.core.frp.FrpType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.io.IOException

class FrpConfigProvider : ContentProvider() {
    private val repository get() = AppGraph.repository(requireNotNull(context))

    override fun onCreate() = true

    private fun profileId(uri: Uri): String? {
        require(uri.scheme == "content" && uri.authority == "${requireNotNull(context).packageName}.configs")
        require(uri.query == null && uri.fragment == null)
        val path = uri.pathSegments
        require(path.size in 1..2 && path[0] == "profiles") { "INVALID_URI" }
        return path.getOrNull(1)?.also {
            require(it.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "INVALID_PROFILE_ID" }
        }
    }

    override fun getType(uri: Uri): String =
        if (profileId(uri) == null) "vnd.android.cursor.dir/vnd.frpmobile.profile" else "text/plain"

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor = runBlocking(Dispatchers.IO) {
        require(selection == null && selectionArgs.isNullOrEmpty() && sortOrder == null) { "UNSUPPORTED_QUERY" }
        val id = profileId(uri)
        val columns = projection ?: arrayOf("id", "name", "type", "autoStart", "updatedAt")
        require(columns.isNotEmpty() && columns.distinct().size == columns.size &&
            columns.all { it in setOf("id", "name", "type", "autoStart", "updatedAt", "toml") })
        require(id != null || "toml" !in columns) { "QUERY_ONE_PROFILE_FOR_TOML" }
        MatrixCursor(columns).apply {
            repository.readExternalProfiles(id).forEach { profile ->
                if ("toml" in columns) check(!profile.storageError) { "CONFIG_DECRYPT_FAILED" }
                addRow(columns.map {
                    when (it) {
                        "id" -> profile.id
                        "name" -> profile.name
                        "type" -> profile.type.name
                        "autoStart" -> if (profile.autoStart) 1 else 0
                        "updatedAt" -> profile.updatedAt
                        else -> profile.toml
                    }
                })
            }
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Use insert/update to write a validated configuration" }
        val id = requireNotNull(profileId(uri)) { "PROFILE_REQUIRED" }
        val content = runBlocking(Dispatchers.IO) {
            val profile = repository.readExternalProfiles(id).singleOrNull() ?: throw FileNotFoundException("PROFILE_MISSING")
            check(!profile.storageError) { "CONFIG_DECRYPT_FAILED" }
            profile.toml.toByteArray()
        }
        return openPipeHelper(uri, "text/plain", null, content) { output, _, _, _, bytes ->
            try { ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(bytes) } }
            catch (_: IOException) { /* The receiving app can close its pipe before consuming the content. */ }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = runBlocking(Dispatchers.IO) {
        require(profileId(uri) == null) { "USE_PROFILES_URI" }
        val input = requireNotNull(values)
        require(input.keySet().all { it in setOf("name", "type", "toml") }) { "UNKNOWN_FIELD" }
        val id = repository.writeExternalProfile(
            null, input.getAsString("name"), FrpType.valueOf(requireNotNull(input.getAsString("type"))),
            requireNotNull(input.getAsString("toml")), null,
        )
        Uri.withAppendedPath(uri, id)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        runBlocking(Dispatchers.IO) {
            require(selection == null && selectionArgs.isNullOrEmpty()) { "UNSUPPORTED_QUERY" }
            val id = requireNotNull(profileId(uri)) { "PROFILE_REQUIRED" }
            val input = requireNotNull(values)
            require(input.keySet().all { it in setOf("name", "toml", "updatedAt") }) { "UNKNOWN_FIELD" }
            repository.writeExternalProfile(
                id, input.getAsString("name"), null, requireNotNull(input.getAsString("toml")),
                requireNotNull(input.getAsLong("updatedAt")) { "REVISION_REQUIRED" },
            )
            1
        }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Delete profiles in FRP Mobile")
}
