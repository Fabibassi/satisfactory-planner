package de.fabrikplaner.app.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import de.fabrikplaner.core.model.Item
import de.fabrikplaner.core.model.LocalizedName
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round

@Composable
fun currentLocale(): Locale {
    val configuration = LocalConfiguration.current
    return localeOf(configuration)
}

fun localeOf(configuration: Configuration): Locale =
    if (configuration.locales.isEmpty) Locale.GERMAN else configuration.locales[0]

fun LocalizedName.localized(locale: Locale): String = if (locale.language == "en") en else de

fun Item.localized(locale: Locale): String = name.localized(locale)

fun formatNumber(value: Double, locale: Locale, decimals: Int = 2): String {
    if (!value.isFinite()) return "–"
    val nearest = round(value)
    if (abs(value - nearest) < 1e-3) {
        return NumberFormat.getIntegerInstance(locale).format(nearest.toLong())
    }
    val format = NumberFormat.getNumberInstance(locale)
    format.minimumFractionDigits = 0
    format.maximumFractionDigits = decimals
    return format.format(value)
}

fun machinesToPlace(count: Double): Int {
    if (!count.isFinite() || count <= 0.0) return 0
    val nearest = round(count)
    if (abs(count - nearest) < 1e-3) return nearest.toInt()
    return ceil(count).toInt()
}

fun parsePositive(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
