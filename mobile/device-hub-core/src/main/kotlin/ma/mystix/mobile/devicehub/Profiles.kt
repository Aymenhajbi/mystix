package ma.mystix.mobile.devicehub

/** Why the resolver or an adapter decided something. The UI translates the code; [detail] is for diagnostics. */
data class Reason(val code: ReasonCode, val detail: String = "")

enum class ReasonCode {
    SDK_BELOW_MINIMUM,
    MANUFACTURER_NOT_SUPPORTED,
    SDK_NOT_BUNDLED,
    CONFIGURATION_NOT_DETECTABLE,
    KEYBOARD_INPUT_ONLY,
    SCANNER_SERVICE_UNAVAILABLE,
    SAVED_CONFIGURATION_VALID,
    SAVED_CONFIGURATION_OTHER_INSTALLATION,
    SAVED_CONFIGURATION_OTHER_DEVICE,
    SAVED_CONFIGURATION_ADAPTER_MISSING,
    SAVED_CONFIGURATION_INCOMPATIBLE,
    PROFILE_VALIDATED,
    PROFILE_EXPERIMENTAL,
    PROFILE_RETIRED,
    PROFILE_ADAPTER_UNAVAILABLE,
    PROFILE_RECOMMENDS_OTHER_ADAPTER,
    NO_PROFILE,
    SEVERAL_CANDIDATES,
    SINGLE_CANDIDATE_LOW_CONFIDENCE,
    NO_COMPATIBLE_ADAPTER,
}

enum class ProfileStatus { VALIDATED, EXPERIMENTAL, RETIRED }

enum class ProfileScope { INSTALLATION, MODEL, MANUFACTURER }

/**
 * A selection rule, written by an administrator or shipped with the app. It recommends an adapter; it never contains
 * code and never activates anything by itself.
 */
data class CompatibilityProfile(
    val id: String,
    val version: Int,
    val scope: ProfileScope,
    val manufacturer: String,
    /** Exact models (case-insensitive) for [ProfileScope.MODEL]; ignored otherwise. */
    val models: Set<String> = emptySet(),
    /** For [ProfileScope.INSTALLATION]. */
    val installationId: String? = null,
    val minSdk: Int? = null,
    val maxSdk: Int? = null,
    val recommendedAdapterId: String,
    val prerequisites: List<String> = emptyList(),
    val confidence: Confidence,
    val status: ProfileStatus,
    val testProcedure: String = "",
) {
    fun matches(device: DeviceDescriptor): Boolean {
        if (!manufacturer.equals(device.manufacturer.trim(), ignoreCase = true)) return false
        if (minSdk != null && device.sdkInt < minSdk) return false
        if (maxSdk != null && device.sdkInt > maxSdk) return false
        return when (scope) {
            ProfileScope.INSTALLATION -> installationId == device.installationId
            ProfileScope.MODEL -> models.any { it.equals(device.model.trim(), ignoreCase = true) }
            ProfileScope.MANUFACTURER -> true
        }
    }

    /** Installation beats model beats manufacturer. */
    val specificity: Int
        get() = when (scope) {
            ProfileScope.INSTALLATION -> 3
            ProfileScope.MODEL -> 2
            ProfileScope.MANUFACTURER -> 1
        }
}

/** Who chose a configuration. */
enum class ConfiguredBy { OPERATOR, ADMINISTRATOR }

/** The physical read that validated a configuration. Only a real scan ([simulated] false) validates. */
data class PhysicalScanTest(val passed: Boolean, val simulated: Boolean, val testedAtMillis: Long, val symbology: String?)

/** The integration actually activated on one installation, as opposed to the one a profile recommends. */
data class ValidatedConfiguration(
    val installationId: String,
    val deviceFingerprint: String,
    val adapterId: String,
    val configuredBy: ConfiguredBy,
    val test: PhysicalScanTest,
    val profileId: String? = null,
    val profileVersion: Int? = null,
) {
    init {
        require(test.passed && !test.simulated) { "Only a passed, physical scan test validates a configuration" }
    }
}

/** Local storage of the configuration and of the cached profiles; it must work without network. */
interface DeviceProfileRepository {
    fun loadConfiguration(installationId: String): ValidatedConfiguration?

    fun saveConfiguration(configuration: ValidatedConfiguration)

    fun clearConfiguration(installationId: String)

    fun profiles(): List<CompatibilityProfile>

    /** Replaces the cached profiles (from the backend). The validated configuration is kept as is. */
    fun replaceProfiles(profiles: List<CompatibilityProfile>)
}

class InMemoryDeviceProfileRepository(profiles: List<CompatibilityProfile> = emptyList()) : DeviceProfileRepository {
    private val configurations = mutableMapOf<String, ValidatedConfiguration>()
    private var cached = profiles.toList()

    @Synchronized
    override fun loadConfiguration(installationId: String) = configurations[installationId]

    @Synchronized
    override fun saveConfiguration(configuration: ValidatedConfiguration) {
        configurations[configuration.installationId] = configuration
    }

    @Synchronized
    override fun clearConfiguration(installationId: String) {
        configurations.remove(installationId)
    }

    @Synchronized
    override fun profiles() = cached

    @Synchronized
    override fun replaceProfiles(profiles: List<CompatibilityProfile>) {
        cached = profiles.toList()
    }
}
