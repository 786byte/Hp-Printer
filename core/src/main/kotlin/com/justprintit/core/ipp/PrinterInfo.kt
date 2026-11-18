package com.justprintit.core.ipp

/** One ink tank / cartridge as reported by IPP "marker-*" attributes. */
data class InkMarker(
    val name: String,
    /** "#RRGGBB" colours; a tri-colour cartridge reports several. */
    val colors: List<String>,
    /** 0..100, or negative when the printer cannot tell (-1 unknown, -2 unknown, -3 "some ink remaining"). */
    val level: Int,
    val lowLevel: Int,
    val type: String,
) {
    val isKnown get() = level >= 0
    val isLow get() = isKnown && level <= lowLevel.coerceAtLeast(1)
    val isEmpty get() = isKnown && level == 0
}

enum class Severity { INFO, WARNING, ERROR }

data class StateReason(val keyword: String, val severity: Severity, val text: String)

data class PrinterInfo(
    val makeAndModel: String,
    /** 3 idle, 4 processing, 5 stopped. */
    val state: Int,
    val stateMessage: String?,
    val reasons: List<StateReason>,
    val markers: List<InkMarker>,
    val acceptingJobs: Boolean,
    val documentFormats: List<String>,
    val pwgRasterTypes: List<String>,
    val pwgResolutions: List<Int>,
    val media: List<String>,
    val mediaDefault: String?,
    val mediaTypes: List<String>,
    val colorModes: List<String>,
    val qualities: List<Int>,
    /** Smallest supported margin on any side, in 1/100 mm; 0 means borderless is possible. */
    val minMargin: Int,
    val supportsBorderless: Boolean,
    val firmware: String?,
    val webPage: String?,
) {
    val stateText: String get() = when (state) {
        3 -> "Ready"
        4 -> "Printing"
        5 -> "Stopped"
        else -> "Unknown"
    }
    val errors get() = reasons.filter { it.severity == Severity.ERROR }
    val canPrint get() = acceptingJobs && state != 5 && errors.isEmpty()
    val supportsPwg get() = "image/pwg-raster" in documentFormats
    val supportsJpeg get() = "image/jpeg" in documentFormats

    companion object {
        val REQUESTED = arrayOf(
            "printer-make-and-model", "printer-state", "printer-state-reasons", "printer-state-message",
            "printer-is-accepting-jobs", "marker-names", "marker-colors", "marker-levels", "marker-low-levels",
            "marker-types", "document-format-supported", "pwg-raster-document-type-supported",
            "pwg-raster-document-resolution-supported", "media-supported", "media-default", "media-type-supported",
            "print-color-mode-supported", "print-quality-supported", "media-left-margin-supported",
            "media-right-margin-supported", "media-top-margin-supported", "media-bottom-margin-supported",
            "printer-firmware-string-version", "printer-more-info", "printer-supply-info-uri",
        )

        fun parse(msg: IppMessage): PrinterInfo {
            val names = msg["marker-names"]?.strings.orEmpty()
            val colors = msg["marker-colors"]?.strings.orEmpty()
            val levels = msg["marker-levels"]?.ints.orEmpty()
            val lows = msg["marker-low-levels"]?.ints.orEmpty()
            val types = msg["marker-types"]?.strings.orEmpty()
            val markers = names.indices.map { i ->
                InkMarker(
                    name = names[i],
                    colors = colors.getOrNull(i)?.split('#')?.filter { it.isNotBlank() }?.map { "#$it" }.orEmpty(),
                    level = levels.getOrElse(i) { -1 },
                    lowLevel = lows.getOrElse(i) { 10 },
                    type = types.getOrElse(i) { "ink" },
                )
            }
            val margins = listOf("left", "right", "top", "bottom").map { side ->
                msg["media-$side-margin-supported"]?.ints.orEmpty()
            }
            val minNonZero = margins.map { side -> side.filter { it > 0 }.minOrNull() ?: 300 }.max()
            val borderless = margins.all { 0 in it }
            val resolutions = msg["pwg-raster-document-resolution-supported"]?.values.orEmpty()
                .mapNotNull { (it as? IppValue.Resolution)?.takeIf { r -> r.units == 3 }?.x }
            return PrinterInfo(
                makeAndModel = msg["printer-make-and-model"]?.string ?: "Printer",
                state = msg["printer-state"]?.int ?: 0,
                stateMessage = msg["printer-state-message"]?.string?.takeIf { it.isNotBlank() },
                reasons = msg["printer-state-reasons"]?.strings.orEmpty().filter { it != "none" }.map(::describeReason),
                markers = markers,
                acceptingJobs = (msg["printer-is-accepting-jobs"]?.values?.firstOrNull() as? IppValue.Bool)?.value ?: true,
                documentFormats = msg["document-format-supported"]?.strings.orEmpty(),
                pwgRasterTypes = msg["pwg-raster-document-type-supported"]?.strings.orEmpty(),
                pwgResolutions = resolutions,
                media = msg["media-supported"]?.strings.orEmpty(),
                mediaDefault = msg["media-default"]?.string,
                mediaTypes = msg["media-type-supported"]?.strings.orEmpty(),
                colorModes = msg["print-color-mode-supported"]?.strings.orEmpty(),
                qualities = msg["print-quality-supported"]?.ints.orEmpty(),
                minMargin = minNonZero,
                supportsBorderless = borderless,
                firmware = msg["printer-firmware-string-version"]?.string,
                webPage = msg["printer-more-info"]?.string,
            )
        }

        private val reasonText = mapOf(
            "media-empty" to "Out of paper — load paper in the input tray",
            "media-needed" to "Paper needed — load paper in the input tray",
            "media-jam" to "Paper jam — open the printer and remove the jammed paper",
            "media-low" to "Paper is running low",
            "door-open" to "A door or cover is open",
            "cover-open" to "A cover is open",
            "input-tray-missing" to "Input tray is missing",
            "output-tray-missing" to "Output tray is missing",
            "output-area-full" to "Output tray is full",
            "marker-supply-low" to "Ink is running low",
            "marker-supply-empty" to "An ink tank is empty",
            "marker-waste-almost-full" to "Ink waste area almost full",
            "marker-waste-full" to "Ink waste area full",
            "printer-nearing-end-of-life" to "Printhead nearing end of life",
            "offline" to "Printer is offline",
            "paused" to "Printer is paused",
            "shutdown" to "Printer is shutting down",
            "spool-area-full" to "Printer memory is full — wait for jobs to finish",
            "moving-to-paused" to "Printer is pausing",
            "connecting-to-device" to "Printer is connecting",
            "other" to "Printer needs attention — check its control panel",
        )

        fun describeReason(raw: String): StateReason {
            val severity = when {
                raw.endsWith("-error") -> Severity.ERROR
                raw.endsWith("-warning") -> Severity.WARNING
                raw.endsWith("-report") -> Severity.INFO
                else -> null
            }
            val key = raw.removeSuffix("-error").removeSuffix("-warning").removeSuffix("-report")
            val fallbackSeverity = when (key) {
                "media-empty", "media-needed", "media-jam", "door-open", "cover-open", "input-tray-missing",
                "marker-supply-empty", "offline", "shutdown" -> Severity.ERROR
                else -> Severity.WARNING
            }
            val text = reasonText[key] ?: key.replace('-', ' ').replaceFirstChar { it.uppercase() }
            return StateReason(key, severity ?: fallbackSeverity, text)
        }
    }
}
