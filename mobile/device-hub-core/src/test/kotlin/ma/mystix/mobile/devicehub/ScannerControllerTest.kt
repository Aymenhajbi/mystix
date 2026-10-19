package ma.mystix.mobile.devicehub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScannerControllerTest {
    private val a = FakeAdapter("a")
    private val b = FakeAdapter("b")
    private val guard = MutableGuard()
    private val controller = ScannerController(ScannerAdapterRegistry(listOf(a, b)), guard)
    private val screen = RecordingConsumer()

    @Test
    fun `activating the same adapter twice registers one listener`() {
        controller.attach(screen)
        assertIs<SwitchResult.Switched>(controller.switchTo("a"))
        assertIs<SwitchResult.AlreadyActive>(controller.switchTo("a"))
        assertEquals(1, a.sessions.size)
        a.emit("123456")
        assertEquals(1, screen.scans.size)
    }

    @Test
    fun `the adapter is not changed during a WMS operation`() {
        controller.switchTo("a")
        guard.busy = true
        assertEquals(SwitchResult.DeferredTransactionInProgress, controller.switchTo("b"))
        assertEquals("a", controller.activeAdapterId)
        assertTrue(b.sessions.isEmpty())
        guard.busy = false
        assertIs<SwitchResult.Switched>(controller.switchTo("b"))
        assertTrue(a.sessions.single().closed)
    }

    @Test
    fun `when the new adapter fails to open, the previous one is restored`() {
        controller.switchTo("a")
        b.failOpen = ScannerErrorCode.INIT_FAILED
        val result = controller.switchTo("b")
        assertIs<SwitchResult.Failed>(result)
        assertEquals(ScannerErrorCode.INIT_FAILED, result.error.code)
        assertTrue(result.restored)
        assertEquals("a", controller.activeAdapterId)
        assertEquals(2, a.sessions.size)
    }

    @Test
    fun `an unknown adapter id changes nothing`() {
        controller.switchTo("a")
        assertIs<SwitchResult.UnknownAdapter>(controller.switchTo("zebra"))
        assertEquals("a", controller.activeAdapterId)
    }

    @Test
    fun `events of a released session are ignored`() {
        controller.attach(screen)
        controller.switchTo("a")
        val old = a.sessions.single()
        controller.release()
        assertTrue(old.closed)
        assertNull(controller.activeAdapterId)
        a.emit("late", session = old)
        assertTrue(screen.scans.isEmpty())
    }

    @Test
    fun `scans without a screen are counted, not delivered later`() {
        controller.switchTo("a")
        a.emit("123456")
        assertEquals(1, controller.droppedWithoutConsumer)
        controller.attach(screen)
        assertTrue(screen.scans.isEmpty())
    }

    @Test
    fun `a late detach does not remove the next screen`() {
        val next = RecordingConsumer()
        controller.switchTo("a")
        controller.attach(screen)
        controller.attach(next)
        controller.detach(screen)
        a.emit("123456")
        assertEquals(1, next.scans.size)
    }

    @Test
    fun `adapter errors reach the screen`() {
        controller.attach(screen)
        controller.switchTo("a")
        a.sessions.single().listener.onError(ScannerException(ScannerErrorCode.CLAIMED_BY_OTHER_APP, "busy"))
        assertEquals(ScannerErrorCode.CLAIMED_BY_OTHER_APP, screen.errors.single().code)
    }

    @Test
    fun `one physical read delivered twice is interpreted once, two reads of one label are both kept`() {
        val dedup = ScanDeduplicator(echoWindowMillis = 300)
        assertTrue(dedup.accept(ScanEvent("A1", null, "intent", 1000, "d1")))
        // Same delivery id again (listener fired twice).
        assertEquals(false, dedup.accept(ScanEvent("A1", null, "intent", 1001, "d1")))
        // Same read echoed by the keyboard wedge.
        assertEquals(false, dedup.accept(ScanEvent("A1", null, "wedge", 1100)))
        // The operator scans the same carton label again (a re-scan takes longer than the echo window).
        assertTrue(dedup.accept(ScanEvent("A1", null, "intent", 1600, "d2")))
        // Out of the echo window, another adapter is a real read.
        assertTrue(dedup.accept(ScanEvent("A1", null, "wedge", 2500)))
    }
}

class KeyboardWedgeDecoderTest {
    private val decoder = KeyboardWedgeDecoder(WedgeSettings(maxInterKeyMillis = 50, minLength = 3))

    private fun type(text: String, start: Long, step: Long): List<WedgeOutput> =
        text.flatMapIndexed { i, c -> decoder.onChar(c, start + i * step) }.filter { it != WedgeOutput.Held }

    @Test
    fun `a fast burst ended by Enter is a read`() {
        assertEquals(listOf(WedgeOutput.Read("3760001234567")), type("3760001234567\n", 0, 5))
    }

    @Test
    fun `slow typing goes back to the field`() {
        val out = type("12\n", 0, 200)
        assertEquals(listOf(WedgeOutput.Typed("1"), WedgeOutput.Typed("2"), WedgeOutput.Typed("\n")), out)
    }

    @Test
    fun `a too short burst is not a read`() {
        assertEquals(listOf(WedgeOutput.Typed("ab\n")), type("ab\n", 0, 5))
    }

    @Test
    fun `two consecutive reads are kept apart`() {
        val out = type("AAA111\n", 0, 5) + type("BBB222\n", 100, 5)
        assertEquals(listOf(WedgeOutput.Read("AAA111"), WedgeOutput.Read("BBB222")), out)
    }

    @Test
    fun `held characters are released when typing stops`() {
        type("7", 0, 5)
        assertNull(decoder.flushIfIdle(20))
        assertEquals(WedgeOutput.Typed("7"), decoder.flushIfIdle(100))
    }

    @Test
    fun `an endless burst is released instead of swallowed`() {
        val small = KeyboardWedgeDecoder(WedgeSettings(maxLength = 4))
        val out = "ABCDE".flatMapIndexed { i, c -> small.onChar(c, i.toLong()) }.filter { it != WedgeOutput.Held }
        assertEquals(listOf(WedgeOutput.Typed("ABCDE")), out)
    }
}

class ScannerDiagnosticServiceTest {
    private var now = 0L
    private val a = FakeAdapter("a")
    private val guard = MutableGuard()
    private val controller = ScannerController(ScannerAdapterRegistry(listOf(a)), guard)
    private val service = ScannerDiagnosticService(controller, guard) { now }

    private fun run(expected: String? = null): ScannerDiagnosticService.Run =
        (service.start(a, expected, timeoutMillis = 1000) as ScannerDiagnosticService.StartResult.Started).run

    @Test
    fun `a simulated read passes the test but never validates a configuration`() {
        controller.switchTo("a")
        val run = run()
        assertNull(controller.activeAdapterId, "the WMS session is released during the test")
        a.emit("123456")
        val result = run.poll()!!
        assertEquals(DiagnosticOutcome.PASSED, result.outcome)
        assertTrue(result.simulated)
        assertEquals(6, result.dataLength)
        assertTrue(a.sessions.last().closed)
        assertNull(service.toValidatedConfiguration(result, device(), ConfiguredBy.OPERATOR, null))
    }

    @Test
    fun `a physical read validates the configuration of this installation`() {
        val run = run()
        a.sessions.last().listener.onScan(ScanEvent("123456", "EAN13", "a", 5, simulated = false))
        val saved = service.toValidatedConfiguration(run.poll()!!, device(), ConfiguredBy.ADMINISTRATOR, profile("a"))!!
        assertEquals("a", saved.adapterId)
        assertEquals(device().fingerprint, saved.deviceFingerprint)
        assertEquals("p-a", saved.profileId)
    }

    @Test
    fun `a wrong label, a timeout and an open failure do not pass`() {
        val wrong = run(expected = "EXPECTED")
        a.emit("OTHER")
        assertEquals(DiagnosticOutcome.MISMATCH, wrong.poll()!!.outcome)

        val slow = run()
        assertNull(slow.poll())
        now = 2000
        assertEquals(DiagnosticOutcome.TIMEOUT, slow.poll()!!.outcome)
        assertTrue(a.sessions.last().closed)

        a.failOpen = ScannerErrorCode.NOT_AVAILABLE
        val failed = run().poll()!!
        assertEquals(DiagnosticOutcome.OPEN_FAILED, failed.outcome)
        assertEquals(ScannerErrorCode.NOT_AVAILABLE, failed.errorCode)
    }

    @Test
    fun `no test during a WMS operation`() {
        controller.switchTo("a")
        guard.busy = true
        assertEquals(ScannerDiagnosticService.StartResult.TransactionInProgress, service.start(a))
        assertEquals("a", controller.activeAdapterId)
    }
}
