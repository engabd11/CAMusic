package com.engabd.sendpin.wake

import android.content.Context
import android.util.Log
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.hue.HueBridgeClient
import com.engabd.sendpin.hue.HueBridgeException
import com.engabd.sendpin.hue.HueGroup
import com.engabd.sendpin.hue.HueGroupState
import com.engabd.sendpin.service.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The two things Wake & Sleep ever asks of a room. */
interface RoomLights {
    suspend fun state(groupedLightId: String): HueGroupState?
    suspend fun put(groupedLightId: String, body: String)
    suspend fun rooms(): List<HueGroup>
}

/**
 * [RoomLights] over the paired bridge's CLIP API. Throws [HueBridgeException] when there is
 * no bridge paired, and whatever OkHttp throws when it cannot be reached; callers treat
 * every failure as "the lights could not be reached" and carry on.
 */
class BridgeRoomLights(context: Context) : RoomLights {
    private val app = context.applicationContext
    private val settings = AppSettings(app)

    private data class Creds(val host: String, val key: String, val bridgeId: String)

    private var client: HueBridgeClient? = null
    private var clientFor: String = ""

    private suspend fun creds(): Creds {
        val host = settings.hueBridgeIp.first()
        val key = settings.hueAppKey.first()
        if (host.isBlank() || key.isBlank()) throw HueBridgeException("No Hue Bridge is paired")
        return Creds(host, key, settings.hueBridgeId.first())
    }

    @Synchronized
    private fun clientFor(id: String): HueBridgeClient {
        client?.takeIf { clientFor == id }?.let { return it }
        return HueBridgeClient(app, expectedBridgeId = { id }).also { client = it; clientFor = id }
    }

    override suspend fun state(groupedLightId: String): HueGroupState? {
        val c = creds()
        return clientFor(c.bridgeId).lights.groupState(c.host, c.key, groupedLightId)
    }

    override suspend fun put(groupedLightId: String, body: String) {
        val c = creds()
        clientFor(c.bridgeId).lights.putGroup(c.host, c.key, groupedLightId, body)
    }

    override suspend fun rooms(): List<HueGroup> {
        val c = creds()
        return clientFor(c.bridgeId).lights.listGroups(c.host, c.key)
    }
}

/** What the listener chose for bedtime. */
data class WindDownConfig(
    val dim: Boolean = false,
    val roomId: String = "",
    /** Ambience scene (wire name) to start when the music ends, or blank. */
    val ambience: String = "",
) {
    val dims: Boolean get() = dim && roomId.isNotBlank()
}

/**
 * Dims a room along with the sleep timer, and can hand over to an ambience scene.
 *
 * What it sends, in order, for a timer of T:
 *  1. one PUT: warm amber (about 2200 K) at the room's current brightness, or 40 percent
 *     when that is not known;
 *  2. one PUT with `dynamics.duration` = the time left: down to 1 percent, which the
 *     bridge carries out by itself;
 *  3. when the timer ends, one PUT: off over two seconds.
 *
 * A room that is off when the timer starts is left off: bedtime should never switch a lamp
 * on. Cancelling the timer sends the room's current brightness back to it with no
 * duration, which freezes the fade where it has got to. The colour stays amber and the
 * brightness stays where it stopped; there is no memory of the lights before the dim to
 * restore, and the bridge has no "undo". A "stop after this song" timer has no end time,
 * so it dims to amber and holds there until the music ends, then goes off.
 */
class WindDownController(
    private val scope: CoroutineScope,
    private val config: suspend () -> WindDownConfig,
    private val lights: RoomLights,
    private val ambience: (String) -> Unit = WakeAmbience::request,
) : SleepTimer.Listener {
    private var job: Job? = null
    @Volatile private var dimmedRoom: String? = null

    override fun onStarted(totalMs: Long?) {
        val previous = job
        job = scope.launch {
            previous?.join()
            val cfg = config()
            if (!cfg.dims) return@launch
            try {
                val now = lights.state(cfg.roomId)
                if (now == null || !now.on) return@launch
                lights.put(cfg.roomId, WindDownPlan.amberBody(now.brightness))
                dimmedRoom = cfg.roomId
                if (totalMs != null) {
                    lights.put(cfg.roomId, WindDownPlan.dimBody(totalMs - WindDownPlan.AMBER_MS))
                }
            } catch (e: Exception) {
                // Music and the timer carry on; only the lights are lost.
                warn("dim failed: ${e.message}")
            }
        }
    }

    override fun onCancelled() {
        val previous = job
        job = scope.launch {
            previous?.join()
            val room = dimmedRoom ?: return@launch
            dimmedRoom = null
            try {
                val now = lights.state(room)
                if (now != null && now.on) {
                    lights.put(room, WindDownPlan.holdBody(now.brightness ?: WindDownPlan.FALLBACK_BRIGHTNESS))
                }
            } catch (e: Exception) {
                warn("hold failed: ${e.message}")
            }
        }
    }

    override fun onEnded() {
        val previous = job
        job = scope.launch {
            previous?.join()
            val cfg = config()
            val room = dimmedRoom
            dimmedRoom = null
            if (room != null) {
                try {
                    lights.put(room, WindDownPlan.offBody())
                } catch (e: Exception) {
                    warn("off failed: ${e.message}")
                }
            }
            if (cfg.ambience.isNotBlank()) {
                // A moment for the lights' fade to land before a scene takes the room.
                delay(AMBIENCE_GAP_MS)
                ambience(cfg.ambience)
            }
        }
    }

    companion object {
        private const val TAG = "WindDown"

        /** Logging is best effort: a JVM test has no Log. */
        private fun warn(msg: String) { runCatching { Log.w(TAG, msg) } }
        const val AMBIENCE_GAP_MS = 3_000L

        fun forApp(app: Context, scope: CoroutineScope): WindDownController {
            val settings = AppSettings(app)
            return WindDownController(
                scope = scope,
                config = {
                    WindDownConfig(
                        dim = settings.windDownDim.first(),
                        roomId = settings.windDownRoomId.first(),
                        ambience = settings.windDownAmbience.first(),
                    )
                },
                lights = BridgeRoomLights(app),
            )
        }
    }
}
