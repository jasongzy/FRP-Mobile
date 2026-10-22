package com.jasongzy.frpmobile.core.data

import android.util.AtomicFile
import com.jasongzy.frpmobile.core.frp.FrpProfile
import com.jasongzy.frpmobile.core.frp.FrpType
import java.io.File
import kotlin.io.encoding.Base64
import org.json.JSONObject

data class EditorDraft(
    val original: FrpProfile,
    val current: FrpProfile,
    val isNew: Boolean,
    val selectionStart: Int,
    val selectionEnd: Int,
)

class EditorDraftStore(private val directory: File, private val cipher: ConfigCipher) {
    private fun file(id: String): AtomicFile {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return AtomicFile(File(directory, "$id.draft"))
    }

    fun read(id: String): EditorDraft? {
        val file = file(id)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return decodeDraft(cipher.decrypt(file.readFully().toString(Charsets.UTF_8), "draft/$id"))
    }

    fun write(id: String, draft: EditorDraft) {
        val bytes = cipher.encrypt(encodeDraft(draft), "draft/$id").toByteArray()
        val file = file(id)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    fun delete(id: String) = file(id).delete()

    fun prune(except: String) {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        directory.listFiles()?.filter {
            it.name.matches(Regex("[a-f0-9-]{36}\\.draft(?:\\.bak|\\.new)?")) &&
                !it.name.startsWith("$except.draft") && it.lastModified() < cutoff
        }?.forEach { it.delete() }
    }
}

internal fun encodeDraft(draft: EditorDraft): ByteArray {
    fun profile(value: FrpProfile) = JSONObject()
        .put("id", value.id).put("name", value.name).put("type", value.type.name)
        .put("toml", value.toml).put("autoStart", value.autoStart).put("updatedAt", value.updatedAt)
        .put("files", JSONObject(value.files.mapValues { Base64.encode(it.value) }))
    return JSONObject().put("version", 1).put("original", profile(draft.original))
        .put("current", profile(draft.current)).put("isNew", draft.isNew)
        .put("selectionStart", draft.selectionStart).put("selectionEnd", draft.selectionEnd)
        .toString().toByteArray()
}

internal fun decodeDraft(bytes: ByteArray): EditorDraft {
    val json = JSONObject(bytes.toString(Charsets.UTF_8))
    require(json.getInt("version") == 1) { "DRAFT_VERSION_UNSUPPORTED" }
    fun profile(value: JSONObject): FrpProfile {
        val files = value.getJSONObject("files")
        return FrpProfile(
            value.getString("id"), value.getString("name"), FrpType.valueOf(value.getString("type")),
            value.getString("toml"), value.getBoolean("autoStart"), value.getLong("updatedAt"),
            files.keys().asSequence().associateWith { Base64.decode(files.getString(it)) },
        )
    }
    return EditorDraft(profile(json.getJSONObject("original")), profile(json.getJSONObject("current")),
        json.getBoolean("isNew"), json.getInt("selectionStart"), json.getInt("selectionEnd"))
}
