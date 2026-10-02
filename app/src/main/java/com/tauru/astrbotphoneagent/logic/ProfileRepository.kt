package com.tauru.astrbotphoneagent.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import com.tauru.astrbotphoneagent.data.ActionFeedback
import com.tauru.astrbotphoneagent.data.CompanionProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Local presentation only: this profile never changes or uploads a server persona. */
class ProfileRepository(private val context: Context) {
    private val directory = File(context.filesDir, "profile").apply { mkdirs() }
    private val store = AtomicFile(File(directory, "profile.json"))
    private val lock = Mutex()
    private val _profile = MutableStateFlow(load())
    val profile: StateFlow<CompanionProfile> = _profile.asStateFlow()

    private fun load(): CompanionProfile = runCatching {
        val value = JSONObject(store.readFully().toString(Charsets.UTF_8))
        val filename = value.optString("avatar_file")
        val file = if (Regex("avatar-[a-f0-9-]+\\.jpg").matches(filename)) File(directory, filename) else null
        CompanionProfile(value.optString("name").take(40), "", "", file?.takeIf { it.isFile }?.let { Uri.fromFile(it).toString() })
    }.getOrDefault(CompanionProfile("", "", "", null))

    suspend fun save(name: String, avatarUri: String?): ActionFeedback = withContext(Dispatchers.IO) {
        lock.withLock {
            val trimmed = name.trim()
            if (trimmed.length > 40) return@withLock ActionFeedback(false, "名称请控制在 40 字以内")
            val previous = _profile.value
            var created: File? = null
            try {
                val savedUri = when {
                    avatarUri.isNullOrBlank() -> null
                    avatarUri == previous.avatarUri -> previous.avatarUri
                    else -> {
                        val selected = Uri.parse(avatarUri)
                        require(selected.scheme == "content")
                        val bytes = context.contentResolver.openInputStream(selected)?.use { stream ->
                            stream.readBytesLimited(12 * 1024 * 1024)
                        } ?: throw IllegalArgumentException()
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        require(bounds.outWidth > 0 && bounds.outHeight > 0)
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 1
                            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1024) inSampleSize *= 2
                        }
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IllegalArgumentException()
                        val output = File(directory, "avatar-${UUID.randomUUID()}.jpg")
                        created = output
                        try { output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) } }
                        finally { bitmap.recycle() }
                        Uri.fromFile(output).toString()
                    }
                }
                val filename = savedUri?.let { File(Uri.parse(it).path.orEmpty()).name }.orEmpty()
                val bytes = JSONObject().put("name", trimmed).put("avatar_file", filename).toString().toByteArray(Charsets.UTF_8)
                val stream = store.startWrite()
                try { stream.write(bytes); store.finishWrite(stream) }
                catch (error: Exception) { store.failWrite(stream); throw error }
                _profile.value = CompanionProfile(trimmed, "", "", savedUri)
                // Remove only the previous private copy after committing the new profile.
                previous.avatarUri?.takeIf { it != savedUri }?.let {
                    val old = File(Uri.parse(it).path.orEmpty())
                    if (old.canonicalFile.parentFile == directory.canonicalFile) old.delete()
                }
                ActionFeedback(true, "名称和头像已保存")
            } catch (cancelled: CancellationException) { created?.delete(); throw cancelled }
            catch (_: Exception) { created?.delete(); ActionFeedback(false, "无法读取头像，请选择不超过 12 MB 的图片后重试") }
        }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(result.size() + count <= limit)
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }
}
