package de.fabrikplaner.app.ui.icon

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.fabrikplaner.core.model.ItemForm
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Item and building art from the bundled wiki export. Missing files keep the
 * two-letter monogram. See docs/gamedata.md.
 */
fun monogram(name: String): String {
    val parts = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    val raw = when {
        parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}"
        parts.size == 1 -> parts[0].take(2)
        else -> "?"
    }
    return raw.uppercase()
}

fun glyphColor(key: String): Color {
    val palette = intArrayOf(
        0xFF5C6BC0.toInt(),
        0xFF00897B.toInt(),
        0xFFEF6C00.toInt(),
        0xFF8D6E63.toInt(),
        0xFF1E88E5.toInt(),
        0xFF8E24AA.toInt(),
        0xFF546E7A.toInt(),
        0xFFC0A000.toInt(),
        0xFF43A047.toInt(),
        0xFFD81B60.toInt(),
    )
    return Color(palette[abs(key.hashCode()) % palette.size])
}

@Composable
fun ItemGlyph(
    name: String,
    key: String,
    form: ItemForm?,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    val context = LocalContext.current
    val image = remember(key) { GameIcons.item(context, key) }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = name,
            modifier = modifier.size(size),
            contentScale = ContentScale.Fit,
        )
    } else {
        val shape = if (form == ItemForm.LIQUID || form == ItemForm.GAS) CircleShape else RoundedCornerShape(7.dp)
        Box(
            modifier.size(size).clip(shape).background(glyphColor(key)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                monogram(name),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
fun BuildingGlyph(
    name: String,
    buildingId: String,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val context = LocalContext.current
    val image = remember(buildingId) { GameIcons.building(context, buildingId) }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = name,
            modifier = modifier.size(size),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(
            modifier.size(size).clip(RoundedCornerShape(4.dp)).background(glyphColor("building:$buildingId")),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                monogram(name),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

fun DrawScope.drawItemArt(
    topLeft: Offset,
    sizePx: Float,
    image: ImageBitmap?,
    letters: String,
    color: Color,
    textMeasurer: TextMeasurer,
    circular: Boolean,
) {
    if (image != null) {
        val side = sizePx.roundToInt().coerceAtLeast(1)
        drawImage(
            image = image,
            dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
            dstSize = IntSize(side, side),
            filterQuality = FilterQuality.Medium,
        )
        return
    }
    drawMonogram(topLeft, sizePx, letters, color, textMeasurer, circular)
}

fun DrawScope.drawMonogram(
    topLeft: Offset,
    sizePx: Float,
    letters: String,
    color: Color,
    textMeasurer: TextMeasurer,
    circular: Boolean,
) {
    if (circular) {
        drawCircle(color, radius = sizePx / 2f, center = topLeft + Offset(sizePx / 2f, sizePx / 2f))
    } else {
        drawRoundRect(
            color = color,
            topLeft = topLeft,
            size = Size(sizePx, sizePx),
            cornerRadius = CornerRadius(sizePx * 0.22f, sizePx * 0.22f),
        )
    }
    val fontSp = (sizePx / density * 0.38f).coerceIn(8f, 14f)
    val layout = textMeasurer.measure(
        text = letters,
        style = TextStyle(color = Color.White, fontSize = fontSp.sp, fontWeight = FontWeight.Bold),
    )
    drawText(
        layout,
        topLeft = topLeft + Offset(
            (sizePx - layout.size.width) / 2f,
            (sizePx - layout.size.height) / 2f,
        ),
    )
}
