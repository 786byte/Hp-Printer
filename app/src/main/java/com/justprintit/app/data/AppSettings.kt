package com.justprintit.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Paper(val keyword: String, val label: String, val widthMm: Double, val heightMm: Double, val isPhoto: Boolean = false) {
    companion object {
        val ALL = listOf(
            Paper("iso_a4_210x297mm", "A4", 210.0, 297.0),
            Paper("na_letter_8.5x11in", "Letter", 215.9, 279.4),
            Paper("na_legal_8.5x14in", "Legal", 215.9, 355.6),
            Paper("iso_a5_148x210mm", "A5", 148.0, 210.0),
            Paper("iso_a6_105x148mm", "A6", 105.0, 148.0),
            Paper("na_index-4x6_4x6in", "4×6 in photo (10×15 cm)", 101.6, 152.4, isPhoto = true),
            Paper("na_5x7_5x7in", "5×7 in photo (13×18 cm)", 127.0, 177.8, isPhoto = true),
            Paper("oe_photo-l_3.5x5in", "3.5×5 in photo (L)", 88.9, 127.0, isPhoto = true),
        )
        fun of(keyword: String) = ALL.firstOrNull { it.keyword == keyword } ?: ALL[0]
    }
}

class AppSettings(context: Context) {
    data class Values(
        val printerHost: String = "",
        val printerName: String = "",
        val printerPort: Int = 631,
        val printerPath: String = "/ipp/print",
        val defaultPaper: String = "iso_a4_210x297mm",
        val defaultQuality: Int = 4,
        val defaultMono: Boolean = false,
        val userName: String = "Android",
        val duplexReverseBacks: Boolean = true,
        val duplexRotateBacks: Boolean = true,
        val duplexBlankForOdd: Boolean = true,
        val scanDpi: Int = 300,
        val scanColor: Boolean = true,
    )

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _values = MutableStateFlow(load())
    val values: StateFlow<Values> = _values.asStateFlow()
    val current: Values get() = _values.value

    private fun load(): Values {
        val d = Values()
        return Values(
            printerHost = prefs.getString("printerHost", d.printerHost)!!,
            printerName = prefs.getString("printerName", d.printerName)!!,
            printerPort = prefs.getInt("printerPort", d.printerPort),
            printerPath = prefs.getString("printerPath", d.printerPath)!!,
            defaultPaper = prefs.getString("defaultPaper", d.defaultPaper)!!,
            defaultQuality = prefs.getInt("defaultQuality", d.defaultQuality),
            defaultMono = prefs.getBoolean("defaultMono", d.defaultMono),
            userName = prefs.getString("userName", d.userName)!!,
            duplexReverseBacks = prefs.getBoolean("duplexReverseBacks", d.duplexReverseBacks),
            duplexRotateBacks = prefs.getBoolean("duplexRotateBacks", d.duplexRotateBacks),
            duplexBlankForOdd = prefs.getBoolean("duplexBlankForOdd", d.duplexBlankForOdd),
            scanDpi = prefs.getInt("scanDpi", d.scanDpi),
            scanColor = prefs.getBoolean("scanColor", d.scanColor),
        )
    }

    fun update(transform: (Values) -> Values) {
        val v = transform(_values.value)
        prefs.edit()
            .putString("printerHost", v.printerHost.trim())
            .putString("printerName", v.printerName)
            .putInt("printerPort", v.printerPort)
            .putString("printerPath", v.printerPath)
            .putString("defaultPaper", v.defaultPaper)
            .putInt("defaultQuality", v.defaultQuality)
            .putBoolean("defaultMono", v.defaultMono)
            .putString("userName", v.userName)
            .putBoolean("duplexReverseBacks", v.duplexReverseBacks)
            .putBoolean("duplexRotateBacks", v.duplexRotateBacks)
            .putBoolean("duplexBlankForOdd", v.duplexBlankForOdd)
            .putInt("scanDpi", v.scanDpi)
            .putBoolean("scanColor", v.scanColor)
            .apply()
        _values.value = v.copy(printerHost = v.printerHost.trim())
    }
}
