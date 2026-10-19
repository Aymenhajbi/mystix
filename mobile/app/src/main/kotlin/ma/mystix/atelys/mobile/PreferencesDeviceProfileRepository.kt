package ma.mystix.atelys.mobile

import android.content.Context
import ma.mystix.mobile.devicehub.CompatibilityProfile
import ma.mystix.mobile.devicehub.ConfigurationCodec
import ma.mystix.mobile.devicehub.DeviceProfileRepository
import ma.mystix.mobile.devicehub.ValidatedConfiguration

/**
 * Keeps the validated configuration on the terminal, so the scanner starts without network.
 * Profiles are not cached yet (lot C): the list stays empty until the backend provides them.
 */
class PreferencesDeviceProfileRepository(context: Context) : DeviceProfileRepository {
    private val prefs = context.getSharedPreferences("atelys.scanner", Context.MODE_PRIVATE)
    private var cachedProfiles: List<CompatibilityProfile> = emptyList()

    @Synchronized
    override fun loadConfiguration(installationId: String): ValidatedConfiguration? {
        val values = ConfigurationCodec.keys.mapNotNull { key -> prefs.getString(key, null)?.let { key to it } }.toMap()
        return ConfigurationCodec.decode(values)?.takeIf { it.installationId == installationId }
    }

    @Synchronized
    override fun saveConfiguration(configuration: ValidatedConfiguration) {
        val editor = prefs.edit()
        ConfigurationCodec.keys.forEach { editor.remove(it) }
        ConfigurationCodec.encode(configuration).forEach { (k, v) -> editor.putString(k, v) }
        editor.apply()
    }

    @Synchronized
    override fun clearConfiguration(installationId: String) {
        val editor = prefs.edit()
        ConfigurationCodec.keys.forEach { editor.remove(it) }
        editor.apply()
    }

    @Synchronized
    override fun profiles(): List<CompatibilityProfile> = cachedProfiles

    @Synchronized
    override fun replaceProfiles(profiles: List<CompatibilityProfile>) {
        cachedProfiles = profiles.toList()
    }
}
