package ma.mystix.mobile.devicehub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigurationCodecTest {
    @Test
    fun `a validated configuration survives a round trip`() {
        val saved = validated(HoneywellDataIntentAdapterBase.ID).copy(profileId = "p", profileVersion = 3)
        assertEquals(saved, ConfigurationCodec.decode(ConfigurationCodec.encode(saved)))
    }

    @Test
    fun `an unreadable or foreign value means no configuration`() {
        val encoded = ConfigurationCodec.encode(validated("a"))
        assertNull(ConfigurationCodec.decode(emptyMap()))
        assertNull(ConfigurationCodec.decode(encoded + ("codec" to "999")))
        assertNull(ConfigurationCodec.decode(encoded + ("configuredBy" to "ROBOT")))
        assertNull(ConfigurationCodec.decode(encoded - "adapterId"))
    }
}

class HoneywellDataIntentTest {
    private val adapter = TestHoneywellIntentAdapter()

    @Test
    fun `a broadcast becomes a read with its symbology`() {
        val extras = mapOf("data" to "3760001234567\r\n", "codeId" to "d", "timestamp" to "2026-10-19T02:00:00.123")
        val event = adapter.toScanEvent(extras::get, 42)!!
        assertEquals("3760001234567", event.data)
        assertEquals("d", event.symbology)
        assertEquals(HoneywellDataIntentAdapterBase.ID, event.adapterId)
        assertEquals("2026-10-19T02:00:00.123|3760001234567", event.deliveryId)
        assertEquals(false, event.simulated)
    }

    @Test
    fun `an empty or malformed broadcast is ignored`() {
        assertNull(adapter.toScanEvent(emptyMap<String, Any>()::get, 0))
        assertNull(adapter.toScanEvent(mapOf("data" to "")::get, 0))
        assertNull(adapter.toScanEvent(mapOf("data" to 12)::get, 0))
    }

    @Test
    fun `only Honeywell terminals are candidates, and never without a test`() {
        val acme = adapter.checkCompatibility(snapshot(device("Acme", "X1")))
        assertEquals(CompatibilityStatus.INCOMPATIBLE, acme.status)
        val ck67 = adapter.checkCompatibility(snapshot())
        assertEquals(CompatibilityStatus.NEEDS_CONFIGURATION, ck67.status)
        assertEquals(true, ck67.physicalTestRequired)
    }
}
