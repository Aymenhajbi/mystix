package ma.mystix.atelys.mobile

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.KeyEvent
import ma.mystix.mobile.devicehub.HoneywellDataIntentAdapterBase
import ma.mystix.mobile.devicehub.KeyboardWedgeAdapterBase
import ma.mystix.mobile.devicehub.KeyboardWedgeDecoder
import ma.mystix.mobile.devicehub.ScanEvent
import ma.mystix.mobile.devicehub.ScanListener
import ma.mystix.mobile.devicehub.ScannerErrorCode
import ma.mystix.mobile.devicehub.ScannerException
import ma.mystix.mobile.devicehub.ScannerSession
import ma.mystix.mobile.devicehub.WedgeOutput

/**
 * Receives the broadcast that the Honeywell scanner profile sends when Data Intent is enabled with
 * [HoneywellDataIntentAdapterBase.ACTION]. The receiver is registered while the session is open and removed once.
 */
class HoneywellDataIntentAdapter(private val context: Context) : HoneywellDataIntentAdapterBase() {
    override fun open(listener: ScanListener): ScannerSession {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val extras = intent.extras ?: return
                @Suppress("DEPRECATION")
                toScanEvent({ key -> extras.get(key) }, System.currentTimeMillis())?.let(listener::onScan)
            }
        }
        val filter = IntentFilter(ACTION)
        try {
            // The broadcast comes from the scanner service, another application: the receiver must be exported.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                // Before Android 13 the export flag does not exist; the receiver is exported by default.
                @SuppressLint("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        } catch (e: SecurityException) {
            throw ScannerException(ScannerErrorCode.PERMISSION_DENIED, "Cannot register the scan receiver", e)
        }
        return object : ScannerSession {
            private var closed = false
            override val adapterId = id

            @Synchronized
            override fun close() {
                if (closed) return
                closed = true
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }
}

/** Key events of the Activity in front, handed to the keyboard wedge session if one is open. */
object WedgeKeySource {
    private var sink: ((KeyEvent) -> Boolean)? = null

    @Synchronized
    fun set(handler: ((KeyEvent) -> Boolean)?) {
        sink = handler
    }

    /** Removes [handler] only if it is still the current one (a newer session keeps its own). */
    @Synchronized
    fun clear(handler: (KeyEvent) -> Boolean) {
        if (sink === handler) sink = null
    }

    /** Called by Activity.dispatchKeyEvent; true when the key was taken by the wedge decoder. */
    fun dispatch(event: KeyEvent): Boolean = synchronized(this) { sink }?.invoke(event) ?: false
}

class KeyboardWedgeAdapter : KeyboardWedgeAdapterBase() {
    override fun open(listener: ScanListener): ScannerSession {
        val decoder = KeyboardWedgeDecoder()
        val handler: (KeyEvent) -> Boolean = handler@{ event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@handler false
            val c = when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> '\n'
                else -> event.unicodeChar.takeIf { it != 0 }?.toChar() ?: return@handler false
            }
            var consumed = false
            decoder.onChar(c, event.eventTime).forEach { out ->
                when (out) {
                    is WedgeOutput.Read -> {
                        consumed = true
                        listener.onScan(ScanEvent(out.data, null, id, System.currentTimeMillis()))
                    }
                    WedgeOutput.Held -> consumed = true
                    // Typed text is not a read; this screen has no field to give it back to (lot C: scan fields).
                    is WedgeOutput.Typed -> Unit
                }
            }
            consumed
        }
        WedgeKeySource.set(handler)
        return object : ScannerSession {
            override val adapterId = id

            override fun close() {
                WedgeKeySource.clear(handler)
            }
        }
    }
}
