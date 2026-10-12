package de.fabrikplaner.app.ui.icon

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * In-game icons shipped under assets `icons/`. A missing file returns null so
 * the monogram fallback can draw instead.
 */
object GameIcons {
    private val cache = HashMap<String, ImageBitmap?>()

    fun item(context: Context, id: String): ImageBitmap? = load(context, "icons/items/$id.png")

    fun building(context: Context, id: String): ImageBitmap? = load(context, "icons/buildings/$id.png")

    private fun load(context: Context, path: String): ImageBitmap? {
        synchronized(cache) {
            if (cache.containsKey(path)) return cache[path]
            val bitmap = runCatching {
                context.assets.open(path).use { stream ->
                    val bytes = stream.readBytes()
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }.getOrNull()
            cache[path] = bitmap
            return bitmap
        }
    }
}
