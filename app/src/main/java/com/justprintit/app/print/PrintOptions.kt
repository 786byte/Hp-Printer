package com.justprintit.app.print

import com.justprintit.app.data.Paper
import com.justprintit.core.layout.SidesMode
import org.json.JSONObject

enum class ScaleMode(val label: String) { FIT("Fit to page"), FILL("Fill page (crop)") }

data class PrintOptions(
    val pageRange: String = "",
    val perSheet: Int = 1,
    val mono: Boolean = false,
    val copies: Int = 1,
    val paper: String = Paper.ALL[0].keyword,
    /** IPP print-quality: 3 draft, 4 normal, 5 best. */
    val quality: Int = 4,
    val zoomPercent: Int = 100,
    val trimMargins: Boolean = false,
    val sides: SidesMode = SidesMode.ALL,
    val scale: ScaleMode = ScaleMode.FIT,
    val borderless: Boolean = false,
) {
    fun toJson(): String = JSONObject()
        .put("pageRange", pageRange).put("perSheet", perSheet).put("mono", mono).put("copies", copies)
        .put("paper", paper).put("quality", quality).put("zoom", zoomPercent).put("trim", trimMargins)
        .put("sides", sides.name).put("scale", scale.name).put("borderless", borderless)
        .toString()

    companion object {
        fun fromJson(s: String): PrintOptions = runCatching {
            val j = JSONObject(s)
            val d = PrintOptions()
            PrintOptions(
                pageRange = j.optString("pageRange", d.pageRange),
                perSheet = j.optInt("perSheet", d.perSheet),
                mono = j.optBoolean("mono", d.mono),
                copies = j.optInt("copies", d.copies),
                paper = j.optString("paper", d.paper),
                quality = j.optInt("quality", d.quality),
                zoomPercent = j.optInt("zoom", d.zoomPercent),
                trimMargins = j.optBoolean("trim", d.trimMargins),
                sides = runCatching { SidesMode.valueOf(j.optString("sides")) }.getOrDefault(d.sides),
                scale = runCatching { ScaleMode.valueOf(j.optString("scale")) }.getOrDefault(d.scale),
                borderless = j.optBoolean("borderless", d.borderless),
            )
        }.getOrDefault(PrintOptions())
    }
}
