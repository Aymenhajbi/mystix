package ma.mystix.mobile.devicehub

/**
 * Flat key/value form of a [ValidatedConfiguration], for key/value stores such as Android SharedPreferences.
 * A stored value that cannot be read back is treated as no configuration: the resolver then asks again.
 */
object ConfigurationCodec {
    private const val VERSION = "1"

    fun encode(c: ValidatedConfiguration): Map<String, String> = buildMap {
        put("codec", VERSION)
        put("installationId", c.installationId)
        put("deviceFingerprint", c.deviceFingerprint)
        put("adapterId", c.adapterId)
        put("configuredBy", c.configuredBy.name)
        put("testedAtMillis", c.test.testedAtMillis.toString())
        c.test.symbology?.let { put("symbology", it) }
        c.profileId?.let { put("profileId", it) }
        c.profileVersion?.let { put("profileVersion", it.toString()) }
    }

    fun decode(values: Map<String, String>): ValidatedConfiguration? {
        if (values["codec"] != VERSION) return null
        return runCatching {
            ValidatedConfiguration(
                installationId = values.getValue("installationId"),
                deviceFingerprint = values.getValue("deviceFingerprint"),
                adapterId = values.getValue("adapterId"),
                configuredBy = ConfiguredBy.valueOf(values.getValue("configuredBy")),
                // Only a passed, physical test is ever encoded (the constructor refuses anything else).
                test = PhysicalScanTest(passed = true, simulated = false,
                    testedAtMillis = values.getValue("testedAtMillis").toLong(), symbology = values["symbology"]),
                profileId = values["profileId"],
                profileVersion = values["profileVersion"]?.toInt(),
            )
        }.getOrNull()
    }

    val keys = listOf("codec", "installationId", "deviceFingerprint", "adapterId", "configuredBy", "testedAtMillis",
        "symbology", "profileId", "profileVersion")
}
