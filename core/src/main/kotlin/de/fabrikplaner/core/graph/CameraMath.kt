package de.fabrikplaner.core.graph

import kotlin.math.min

data class Camera(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
)

object CameraMath {
    fun screenToWorld(screenX: Float, screenY: Float, camera: Camera, density: Float): Pair<Float, Float> {
        val worldX = (screenX - camera.offsetX) / camera.scale / density
        val worldY = (screenY - camera.offsetY) / camera.scale / density
        return worldX to worldY
    }

    fun worldToScreen(worldX: Float, worldY: Float, camera: Camera, density: Float): Pair<Float, Float> {
        val screenX = worldX * density * camera.scale + camera.offsetX
        val screenY = worldY * density * camera.scale + camera.offsetY
        return screenX to screenY
    }

    fun fit(minX: Float, minY: Float, maxX: Float, maxY: Float, viewW: Float, viewH: Float, density: Float): Camera {
        val pad = 48f
        val worldW = (maxX - minX + pad).coerceAtLeast(1f)
        val worldH = (maxY - minY + pad).coerceAtLeast(1f)
        val scale = min(viewW / density / worldW, viewH / density / worldH).coerceIn(0.25f, 1.4f)
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f
        val offsetX = viewW / 2f - centerX * density * scale
        val offsetY = viewH / 2f - centerY * density * scale
        return Camera(scale, offsetX, offsetY)
    }
}
