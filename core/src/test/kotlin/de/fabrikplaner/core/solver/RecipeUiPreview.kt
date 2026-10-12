package de.fabrikplaner.core.solver

import de.fabrikplaner.core.data.GameData
import de.fabrikplaner.core.data.GameDataLoader
import de.fabrikplaner.core.model.TargetRate
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.round
import kotlin.test.Test

/**
 * Headless stand-in for a phone screenshot. Draws the alternate-recipe list and
 * the comparison dialog from the real solver data. Skipped unless RECIPE_UI_OUT is set,
 * so CI does not write files.
 */
class RecipeUiPreviewTest {
    @Test
    fun renderWhenRequested() {
        val dir = System.getenv("RECIPE_UI_OUT") ?: return
        renderRecipeUi(File(dir))
    }
}

fun renderRecipeUi(out: File) {
    out.mkdirs()
    val game = GameDataLoader.loadResource()
    val solver = ProductionSolver(game)
    val targets = listOf(TargetRate("motor", 10.0))
    val selection = mapOf("screw" to "cast_screw")
    val solved = solver.solve(targets, selection)
    val items = orderedChoiceItemIds(game, solved) { id -> game.item(id).name.de }
    val comparison = compareRecipes(
        game,
        solve = { chosen -> solver.solve(targets, chosen) },
        selection = selection,
        itemId = "screw",
        alternateRecipeId = "cast_screw",
    ) ?: error("cast screw comparison missing")
    drawList(File(out, "rezeptliste.png"), game, items, solved)
    drawComparison(File(out, "rezeptvergleich.png"), game, comparison)
}

private const val SCALE = 3f
private const val PHONE_W = 1080

private val Scrim = Color(0xFF0E1114.toInt())
private val Surface = Color(0xFF1C2024.toInt())
private val SurfaceVariant = Color(0xFF2A3036.toInt())
private val OnSurface = Color(0xFFE6E1E5.toInt())
private val Muted = Color(0xFFB7B2B8.toInt())
private val Primary = Color(0xFFE8A317.toInt())
private val Divider = Color(0xFF3A4148.toInt())
private val RadioOff = Color(0xFF8E8A90.toInt())

private fun dp(value: Int): Int = (value * SCALE).toInt()

/** Fonts passed in as raw pixel guesses are scaled up so a 1080-wide image stays readable. */
private fun ui(px: Int): Int = (px * 2.5f).toInt().coerceIn(28, 64)

private fun drawList(file: File, game: GameData, items: List<String>, solved: SolverResult) {
    val dialogX = dp(12)
    val dialogW = PHONE_W - dp(24)
    val left = dialogX + dp(16)
    val contentW = dialogW - dp(32)
    val body = BufferedImage(contentW, dp(4200), BufferedImage.TYPE_INT_ARGB)
    val bg = body.createGraphics()
    prepare(bg)
    var cy = 0
    for (itemId in items) {
        cy = drawItemBlock(bg, game, solved, itemId, 0, cy, contentW)
        cy += dp(6)
    }
    bg.dispose()
    val scratch = BufferedImage(contentW, dp(400), BufferedImage.TYPE_INT_RGB).createGraphics()
    prepare(scratch)
    var measured = text(scratch, "Rezepte für die Kette", 0, 0, contentW, Font.SANS_SERIF, dp(8), Font.BOLD, OnSurface)
    measured += dp(4)
    measured = text(
        scratch,
        "Diese Teile haben Alternativen. Die Auswahl gilt für die ganze Produktionskette, danach wird der Graph neu aufgebaut.",
        0,
        measured,
        contentW,
        Font.SANS_SERIF,
        dp(5),
        Font.PLAIN,
        Muted,
    )
    scratch.dispose()
    val footerH = dp(88)
    val dialogH = dp(16) + measured + dp(12) + cy + footerH
    val image = BufferedImage(PHONE_W, dialogH + dp(28), BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    prepare(g)
    g.color = Scrim
    g.fillRect(0, 0, image.width, image.height)
    val dialogY = dp(14)
    roundFill(g, Surface, dialogX, dialogY, dialogW, dialogH, dp(16))
    var y = dialogY + dp(16)
    y = text(g, "Rezepte für die Kette", left, y, contentW, Font.SANS_SERIF, dp(8), Font.BOLD, OnSurface)
    y += dp(4)
    y = text(
        g,
        "Diese Teile haben Alternativen. Die Auswahl gilt für die ganze Produktionskette, danach wird der Graph neu aufgebaut.",
        left,
        y,
        contentW,
        Font.SANS_SERIF,
        dp(5),
        Font.PLAIN,
        Muted,
    )
    g.drawImage(body.getSubimage(0, 0, contentW, cy.coerceAtLeast(1)), left, y + dp(8), null)
    val buttonY = dialogY + dialogH - dp(64)
    g.color = Divider
    g.fillRect(dialogX + dp(8), buttonY - dp(10), dialogW - dp(16), dp(1))
    text(g, "Standardrezepte", left, buttonY, dp(160), Font.SANS_SERIF, dp(5), Font.PLAIN, Primary)
    roundFill(g, Primary, left + contentW - dp(230), buttonY - dp(8), dp(230), dp(40), dp(20))
    val label = "Übernehmen und Graph bauen"
    g.font = Font(Font.SANS_SERIF, Font.BOLD, ui(dp(4)))
    g.color = Color(0xFF1A1200.toInt())
    val fm = g.fontMetrics
    val buttonX = left + contentW - dp(230)
    g.drawString(label, buttonX + (dp(230) - fm.stringWidth(label)) / 2, buttonY + dp(16))
    g.dispose()
    ImageIO.write(image, "png", file)
}

private fun drawItemBlock(g: Graphics2D, game: GameData, solved: SolverResult, itemId: String, x: Int, y: Int, width: Int): Int {
    val item = game.item(itemId)
    var cursor = y
    val glyph = dp(28)
    roundFill(g, glyphColor(itemId), x, cursor, glyph, glyph, dp(7))
    g.color = Color.WHITE
    g.font = Font(Font.SANS_SERIF, Font.BOLD, ui(dp(4)))
    val mono = monogram(item.name.de)
    val fm = g.fontMetrics
    g.drawString(mono, x + (glyph - fm.stringWidth(mono)) / 2, cursor + glyph / 2 + fm.ascent / 2 - dp(1))
    g.color = OnSurface
    g.font = Font(Font.SANS_SERIF, Font.BOLD, ui(dp(5)))
    g.drawString(item.name.de, x + glyph + dp(8), cursor + dp(22))
    cursor += glyph + dp(4)
    val active = solved.machines.firstOrNull { machine ->
        val recipe = machine.recipeId?.let { game.recipeOrNull(it) }
        recipe?.outputs?.firstOrNull()?.itemId == itemId
    }?.recipeId
    val defaultId = game.recipeFor(itemId)?.id
    for (recipe in game.recipesProducing(itemId)) {
        val rowTop = cursor
        val selected = recipe.id == active
        drawRadio(g, x + dp(4), rowTop + dp(10), selected)
        val building = game.buildingOrNull(recipe.buildingId)?.name?.de.orEmpty()
        val bg = dp(22)
        val bx = x + dp(36)
        roundFill(g, glyphColor("building:${recipe.buildingId}"), bx, rowTop + dp(8), bg, bg, dp(4))
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, ui(dp(3)))
        val letters = monogram(building)
        val bfm = g.fontMetrics
        g.drawString(letters, bx + (bg - bfm.stringWidth(letters)) / 2, rowTop + dp(8) + bg / 2 + bfm.ascent / 2 - dp(1))
        val badge = if (recipe.alternate) "Alternativ" else "Standard"
        val label = "${recipe.name.de} · $badge · $building"
        g.color = OnSurface
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, ui(dp(4)))
        val textX = bx + bg + dp(8)
        val textW = if (recipe.id == defaultId) width - textX else width - textX - dp(40)
        g.drawString(ellipsize(g, label, textW), textX, rowTop + dp(32))
        if (recipe.id != defaultId) {
            drawHelp(g, width - dp(32), rowTop + dp(6))
        }
        cursor += dp(48)
    }
    g.color = Divider
    g.fillRect(x, cursor, width, dp(1))
    return cursor + dp(8)
}

private fun drawComparison(file: File, game: GameData, comparison: RecipeComparison) {
    val width = PHONE_W
    val measure = BufferedImage(width, 100, BufferedImage.TYPE_INT_RGB).createGraphics()
    prepare(measure)
    val bodyH = measureComparison(measure, game, comparison, width)
    measure.dispose()
    val image = BufferedImage(width, bodyH + dp(24), BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    prepare(g)
    g.color = Scrim
    g.fillRect(0, 0, image.width, image.height)
    val dialogX = dp(8)
    val dialogY = dp(12)
    val dialogW = width - dp(16)
    roundFill(g, Surface, dialogX, dialogY, dialogW, bodyH, dp(16))
    paintComparison(g, game, comparison, dialogX + dp(12), dialogY + dp(14), dialogW - dp(24))
    g.dispose()
    ImageIO.write(image, "png", file)
}

private fun measureComparison(g: Graphics2D, game: GameData, comparison: RecipeComparison, width: Int): Int {
    val dialogW = width - dp(16)
    return paintComparison(g, game, comparison, dp(20), dp(26), dialogW - dp(24)) + dp(8)
}

private fun paintComparison(g: Graphics2D, game: GameData, comparison: RecipeComparison, left: Int, top: Int, width: Int): Int {
    var y = top
    y = text(g, "Vergleich", left, y, width, Font.SANS_SERIF, dp(8), Font.BOLD, OnSurface)
    y += dp(2)
    y = text(g, game.item(comparison.itemId).name.de, left, y, width, Font.SANS_SERIF, dp(6), Font.BOLD, OnSurface)
    y += dp(2)
    y = text(g, "Angaben je Maschine bei 100 % Takt", left, y, width, Font.SANS_SERIF, dp(4), Font.PLAIN, Muted)
    y += dp(10)
    val gap = dp(8)
    val colW = (width - gap) / 2
    val leftBottom = paintSheet(g, game, comparison.baseline, "Standard", left, y, colW)
    val rightBottom = paintSheet(g, game, comparison.alternate, "Alternative", left + colW + gap, y, colW)
    y = maxOf(leftBottom, rightBottom) + dp(14)
    y = text(g, "Auswirkung auf den aktuellen Plan", left, y, width, Font.SANS_SERIF, dp(6), Font.BOLD, OnSurface)
    y += dp(6)
    y = text(g, "Rohstoffe", left, y, width, Font.SANS_SERIF, dp(5), Font.BOLD, OnSurface)
    val raw = comparison.raw.sortedBy { game.item(it.key).name.de }
    for (delta in raw) {
        y = deltaBlock(g, game.item(delta.key).name.de, perMinute(delta), delta, left, y, width)
    }
    y += dp(6)
    y = text(g, "Maschinen", left, y, width, Font.SANS_SERIF, dp(5), Font.BOLD, OnSurface)
    if (comparison.machines.isEmpty()) {
        y = text(g, "Maschinenanzahl bleibt gleich.", left, y, width, Font.SANS_SERIF, dp(4), Font.PLAIN, OnSurface)
    } else {
        val machines = comparison.machines.sortedBy { game.recipeOrNull(it.key)?.name?.de ?: it.key }
        for (delta in machines) {
            val name = game.recipeOrNull(delta.key)?.name?.de ?: delta.key
            y = deltaBlock(g, name, countLine(delta), delta, left, y, width)
        }
    }
    y += dp(6)
    y = text(g, "Leistung", left, y, width, Font.SANS_SERIF, dp(5), Font.BOLD, OnSurface)
    val power = RateDelta("power", comparison.powerBaselineMw, comparison.powerAlternateMw)
    y = deltaBlock(
        g,
        "Leistung",
        "${mw(comparison.powerBaselineMw)} → ${mw(comparison.powerAlternateMw)} (${signed(power.delta)})",
        power,
        left,
        y,
        width,
    )
    y += dp(12)
    g.color = Primary
    g.font = Font(Font.SANS_SERIF, Font.BOLD, ui(dp(5)))
    g.drawString("Schließen", left, y + g.fontMetrics.ascent)
    return y + dp(28)
}

private fun paintSheet(g: Graphics2D, game: GameData, sheet: RecipeSheet, heading: String, x: Int, y: Int, width: Int): Int {
    val bottom = sheetHeight(g, game, sheet, width)
    roundFill(g, SurfaceVariant, x, y, width, bottom, dp(12))
    var cursor = y + dp(10)
    val inner = x + dp(8)
    val innerW = width - dp(16)
    cursor = text(g, heading, inner, cursor, innerW, Font.SANS_SERIF, dp(4), Font.BOLD, Primary)
    val recipeName = game.recipe(sheet.recipeId).name.de
    val building = game.building(sheet.buildingId).name.de
    cursor = text(g, recipeName, inner, cursor, innerW, Font.SANS_SERIF, dp(5), Font.BOLD, OnSurface)
    cursor = text(g, building, inner, cursor, innerW, Font.SANS_SERIF, dp(4), Font.PLAIN, Muted)
    cursor = text(g, "Taktzeit: ${formatNumber(sheet.cycleSeconds)} s", inner, cursor, innerW, Font.SANS_SERIF, dp(4), Font.PLAIN, OnSurface)
    cursor = text(g, "Leistung je Maschine: ${formatNumber(sheet.powerMw)} MW", inner, cursor, innerW, Font.SANS_SERIF, dp(4), Font.PLAIN, OnSurface)
    cursor = text(g, "Eingänge", inner, cursor, innerW, Font.SANS_SERIF, dp(4), Font.BOLD, OnSurface)
    for (flow in sheet.inputs) {
        val name = game.item(flow.itemId).name.de
        cursor = text(g, "$name  ${formatNumber(flow.perMinute)} / min", inner, cursor, innerW, Font.SANS_SERIF, dp(4), Font.PLAIN, OnSurface)
    }
    cursor = text(g, "Ausgänge", inner, cursor, innerW, Font.SANS_SERIF, dp(4), Font.BOLD, OnSurface)
    for (flow in sheet.outputs) {
        val name = game.item(flow.itemId).name.de
        cursor = text(g, "$name  ${formatNumber(flow.perMinute)} / min", inner, cursor, innerW, Font.SANS_SERIF, dp(4), Font.PLAIN, OnSurface)
    }
    return y + bottom
}

private fun sheetHeight(g: Graphics2D, game: GameData, sheet: RecipeSheet, width: Int): Int {
    val innerW = width - dp(16)
    var h = dp(10)
    h += lineHeight(g, "Standard", dp(4), Font.BOLD, innerW)
    h += lineHeight(g, game.recipe(sheet.recipeId).name.de, dp(5), Font.BOLD, innerW)
    h += lineHeight(g, game.building(sheet.buildingId).name.de, dp(4), Font.PLAIN, innerW)
    h += lineHeight(g, "Taktzeit", dp(4), Font.PLAIN, innerW) * 2
    h += lineHeight(g, "Eingänge", dp(4), Font.BOLD, innerW)
    h += lineHeight(g, "Eisenstange  10 / min", dp(4), Font.PLAIN, innerW) * sheet.inputs.size.coerceAtLeast(1)
    h += lineHeight(g, "Ausgänge", dp(4), Font.BOLD, innerW)
    h += lineHeight(g, "Schraube  40 / min", dp(4), Font.PLAIN, innerW) * sheet.outputs.size
    return h + dp(28)
}

private fun deltaBlock(g: Graphics2D, label: String, value: String, delta: RateDelta, x: Int, y: Int, width: Int): Int {
    var cursor = text(g, label, x, y, width, Font.SANS_SERIF, dp(4), Font.PLAIN, OnSurface)
    val color = if (abs(delta.delta) > 1e-3) Primary else Muted
    cursor = text(g, value, x, cursor, width, Font.SANS_SERIF, dp(4), Font.PLAIN, color)
    return cursor + dp(4)
}

private fun perMinute(delta: RateDelta): String =
    "${formatNumber(delta.baseline)} / min → ${formatNumber(delta.alternate)} / min (${signed(delta.delta)})"

private fun countLine(delta: RateDelta): String =
    "${formatNumber(delta.baseline)} → ${formatNumber(delta.alternate)} (${signed(delta.delta)})"

private fun mw(value: Double): String = "${formatNumber(value)} MW"

private fun signed(delta: Double): String {
    if (abs(delta) < 1e-3) return formatNumber(0.0)
    return if (delta > 0) "+${formatNumber(delta)}" else formatNumber(delta)
}

private fun formatNumber(value: Double): String {
    if (!value.isFinite()) return "–"
    val nearest = round(value)
    if (abs(value - nearest) < 1e-3) {
        return NumberFormat.getIntegerInstance(Locale.GERMAN).format(nearest.toLong())
    }
    val format = NumberFormat.getNumberInstance(Locale.GERMAN)
    format.minimumFractionDigits = 0
    format.maximumFractionDigits = 2
    return format.format(value)
}

private fun drawRadio(g: Graphics2D, x: Int, y: Int, selected: Boolean) {
    val d = dp(18)
    g.stroke = BasicStroke(dp(1).toFloat())
    if (selected) {
        g.color = Primary
        g.drawOval(x, y, d, d)
        g.fillOval(x + dp(4), y + dp(4), d - dp(8), d - dp(8))
    } else {
        g.color = RadioOff
        g.drawOval(x, y, d, d)
    }
}

private fun drawHelp(g: Graphics2D, x: Int, y: Int) {
    val d = dp(28)
    g.color = Primary
    g.stroke = BasicStroke(dp(1).toFloat())
    g.drawOval(x, y, d, d)
    g.font = Font(Font.SANS_SERIF, Font.BOLD, ui(dp(5)))
    val fm = g.fontMetrics
    val mark = "?"
    g.drawString(mark, x + (d - fm.stringWidth(mark)) / 2, y + d / 2 + fm.ascent / 2 - dp(2))
}

private fun text(g: Graphics2D, value: String, x: Int, y: Int, maxWidth: Int, family: String, size: Int, style: Int, color: Color): Int {
    g.font = Font(family, style, ui(size))
    g.color = color
    val fm = g.fontMetrics
    var cursor = y
    var line = ""
    for (word in value.split(" ")) {
        val trial = if (line.isEmpty()) word else "$line $word"
        if (fm.stringWidth(trial) > maxWidth && line.isNotEmpty()) {
            g.drawString(line, x, cursor + fm.ascent)
            cursor += fm.height
            line = word
        } else {
            line = trial
        }
    }
    if (line.isNotEmpty()) {
        g.drawString(line, x, cursor + fm.ascent)
        cursor += fm.height
    }
    return cursor
}

private fun lineHeight(g: Graphics2D, value: String, size: Int, style: Int, maxWidth: Int): Int {
    g.font = Font(Font.SANS_SERIF, style, ui(size))
    val fm = g.fontMetrics
    val lines = value.split(" ").fold(1) { count, _ -> count }
    val width = fm.stringWidth(value)
    val wrapped = if (width > maxWidth) (width / maxWidth) + 1 else 1
    return fm.height * maxOf(lines, wrapped)
}

private fun ellipsize(g: Graphics2D, value: String, maxWidth: Int): String {
    val fm = g.fontMetrics
    if (fm.stringWidth(value) <= maxWidth) return value
    var cut = value
    while (cut.isNotEmpty() && fm.stringWidth("$cut…") > maxWidth) cut = cut.dropLast(1)
    return "$cut…"
}

private fun roundFill(g: Graphics2D, color: Color, x: Int, y: Int, w: Int, h: Int, arc: Int) {
    g.color = color
    g.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), arc.toFloat(), arc.toFloat()))
}

private fun prepare(g: Graphics2D) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
}

private fun glyphColor(key: String): Color {
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

private fun monogram(name: String): String {
    val parts = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    val raw = when {
        parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}"
        parts.size == 1 -> parts[0].take(2)
        else -> "?"
    }
    return raw.uppercase()
}
