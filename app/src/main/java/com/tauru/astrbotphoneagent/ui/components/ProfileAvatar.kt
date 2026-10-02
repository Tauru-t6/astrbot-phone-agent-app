package com.tauru.astrbotphoneagent.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local images only; no bundled identity or network image requests. */
@Composable
fun ProfileAvatar(name: String, avatarUri: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, avatarUri) {
        value = null
        if (!avatarUri.isNullOrBlank()) value = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(avatarUri)
                if (uri.scheme !in setOf("content", "file")) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (bounds.outWidth / inSampleSize > 256 || bounds.outHeight / inSampleSize > 256) inSampleSize *= 2
                }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options)?.asImageBitmap() }
            }.getOrNull()
        }
    }
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
        bitmap?.let { image ->
            Image(image, contentDescription = if (name.isBlank()) "已设置的头像" else "$name 的头像", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } ?: if (name.isNotBlank()) {
            Text(name.trim().take(1), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        } else {
            Icon(Icons.Outlined.PersonOutline, contentDescription = "未设置头像", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize().padding(6.dp))
        }
    }
}
