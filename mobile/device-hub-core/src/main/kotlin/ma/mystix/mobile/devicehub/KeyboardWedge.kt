package ma.mystix.mobile.devicehub

/**
 * Settings of a scanner that types its reads as keystrokes. A read is a burst of characters, each within
 * [maxInterKeyMillis] of the previous one, ended by one of [terminators]. Defaults are a starting point, to be
 * checked on the terminal (ADR-0019, hardware test plan).
 */
data class WedgeSettings(
    val terminators: Set<Char> = setOf('\n', '\r'),
    val maxInterKeyMillis: Long = 50,
    val minLength: Int = 3,
    val maxLength: Int = 256,
)

sealed interface WedgeOutput {
    /** The character is held: it may be part of a read. */
    data object Held : WedgeOutput

    /** A complete read. */
    data class Read(val data: String) : WedgeOutput

    /** Characters that are not a read (typed by hand, too short, too long): give them back to the focused field. */
    data class Typed(val text: String) : WedgeOutput
}

/**
 * Separates scanner bursts from typing, so a read never ends up in a business field and typing is never lost.
 * Not thread-safe: feed it from the UI thread that receives the key events.
 */
class KeyboardWedgeDecoder(private val settings: WedgeSettings = WedgeSettings()) {
    private val buffer = StringBuilder()
    private var lastAt = 0L

    fun onChar(c: Char, atMillis: Long): List<WedgeOutput> {
        val out = mutableListOf<WedgeOutput>()
        if (buffer.isNotEmpty() && atMillis - lastAt > settings.maxInterKeyMillis) {
            out += WedgeOutput.Typed(drain())
        }
        lastAt = atMillis
        if (c in settings.terminators) {
            val text = drain()
            out += when {
                text.length >= settings.minLength -> WedgeOutput.Read(text)
                else -> WedgeOutput.Typed(text + c)
            }
            return out
        }
        buffer.append(c)
        if (buffer.length > settings.maxLength) {
            out += WedgeOutput.Typed(drain())
        } else if (out.isEmpty()) {
            out += WedgeOutput.Held
        }
        return out
    }

    /** Call when no key arrived for a while: held characters were typed by hand and go back to the field. */
    fun flushIfIdle(nowMillis: Long): WedgeOutput.Typed? =
        if (buffer.isNotEmpty() && nowMillis - lastAt > settings.maxInterKeyMillis) WedgeOutput.Typed(drain()) else null

    private fun drain(): String = buffer.toString().also { buffer.setLength(0) }
}

/**
 * Keyboard emulation works on any Android terminal that types into the app, so its check cannot prove a scanner is
 * there: always LOW confidence, always a physical test. The platform layer implements [open].
 */
abstract class KeyboardWedgeAdapterBase : ScannerAdapter {
    final override val id = ID
    final override val support = AdapterSupport(
        displayName = "Keyboard wedge",
        manufacturers = emptySet(),
        minSdk = null,
        knownConstraints = listOf(
            "The scanner must type the read followed by Enter (suffix setting of the terminal).",
            "Reads are only taken on screens that declare a scan field; elsewhere keystrokes are left to the field.",
        ),
        detectionConfidence = Confidence.LOW,
    )

    override fun checkCompatibility(snapshot: DeviceSnapshot): CompatibilityCheck =
        if (snapshot.has(CapabilityIds.KEY_EVENTS)) {
            CompatibilityCheck(CompatibilityStatus.COMPATIBLE, Confidence.LOW, listOf(Reason(ReasonCode.KEYBOARD_INPUT_ONLY)),
                missingPrerequisites = listOf("Scanner set to keyboard emulation with an Enter suffix"))
        } else {
            CompatibilityCheck(CompatibilityStatus.INCOMPATIBLE, Confidence.HIGH, listOf(Reason(ReasonCode.KEYBOARD_INPUT_ONLY, "no key events")),
                physicalTestRequired = false)
        }

    companion object {
        const val ID = "keyboard-wedge"
    }
}

/**
 * Honeywell "Data Intent": the scanner profile of the terminal broadcasts each read with an action chosen by Atelys.
 * Whether the profile is set cannot be read by the app, so a Honeywell terminal is at best NEEDS_CONFIGURATION until a
 * scan proves it. Action and extras: to check against Honeywell documentation for the target Android version.
 */
abstract class HoneywellDataIntentAdapterBase : ScannerAdapter {
    final override val id = ID
    final override val support = AdapterSupport(
        displayName = "Honeywell Data Intent",
        manufacturers = setOf("Honeywell"),
        minSdk = null,
        knownConstraints = listOf(
            "Data Intent must be enabled in the scanner profile used by Atelys, with the action of this build.",
            "Supported Android versions not verified on hardware.",
        ),
        detectionConfidence = Confidence.MEDIUM,
    )

    override fun checkCompatibility(snapshot: DeviceSnapshot): CompatibilityCheck {
        if (!support.acceptsManufacturer(snapshot.device.manufacturer)) {
            return CompatibilityCheck(CompatibilityStatus.INCOMPATIBLE, Confidence.HIGH,
                listOf(Reason(ReasonCode.MANUFACTURER_NOT_SUPPORTED, snapshot.device.manufacturer)), physicalTestRequired = false)
        }
        return CompatibilityCheck(CompatibilityStatus.NEEDS_CONFIGURATION, Confidence.MEDIUM,
            listOf(Reason(ReasonCode.CONFIGURATION_NOT_DETECTABLE)),
            missingPrerequisites = listOf("Enable Data Intent in the scanner profile with action $ACTION"))
    }

    /**
     * Turns the extras of one broadcast into a read. [extra] reads an extra of the intent. Returns null when the read
     * is empty, so a malformed broadcast is ignored instead of entering a business field.
     */
    fun toScanEvent(extra: (String) -> Any?, atMillis: Long): ScanEvent? {
        val data = (extra(DATA_EXTRA) as? String)?.trimEnd('\r', '\n')
        if (data.isNullOrEmpty()) return null
        val symbology = (extra(CODE_ID_EXTRA) as? String) ?: (extra(AIM_ID_EXTRA) as? String)
        val timestamp = extra(TIMESTAMP_EXTRA)?.toString()
        return ScanEvent(data, symbology, id, atMillis, deliveryId = timestamp?.let { "$it|$data" })
    }

    companion object {
        const val ID = "honeywell-data-intent"

        /** Chosen by Atelys and typed in the scanner profile; not a Honeywell constant. */
        const val ACTION = "ma.mystix.atelys.SCAN"

        // Extra names seen in third-party Honeywell samples. TODO(HW-SPEC): check against Honeywell documentation and
        // a real CK67 before the pilot; until then this adapter is experimental.
        const val DATA_EXTRA = "data"
        const val CODE_ID_EXTRA = "codeId"
        const val AIM_ID_EXTRA = "aimId"
        const val TIMESTAMP_EXTRA = "timestamp"
    }
}
