package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.hue.HueGroup
import com.engabd.sendpin.util.runCatchingCancellable
import com.engabd.sendpin.wake.BridgeRoomLights
import com.engabd.sendpin.wake.WakeAlarm
import com.engabd.sendpin.wake.rearmWakeAlarms
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A library item the alarm can wake to. */
data class WakePick(val mediaId: String, val title: String, val subtitle: String)

/** Rooms and zones on the bridge, as far as the screen has asked. */
sealed interface RoomsState {
    data object Idle : RoomsState
    data object Loading : RoomsState
    data class Ready(val groups: List<HueGroup>) : RoomsState
    data class Failed(val message: String) : RoomsState
}

/**
 * Backs the Wake & Sleep screen: the alarm list, the wind-down choices, and the two
 * lookups the editor needs (rooms from the bridge, items from the library).
 *
 * Every change to the alarms re-arms the system alarms straight away, so the clock app's
 * next-alarm line is right the moment a switch is flipped.
 */
class WakeSleepViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = AppSettings(app)
    private val lights = BridgeRoomLights(app)

    val alarms: StateFlow<List<WakeAlarm>> = settings.wakeAlarms
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val windDownDim: StateFlow<Boolean> = settings.windDownDim
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val windDownRoomId: StateFlow<String> = settings.windDownRoomId
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val windDownRoomName: StateFlow<String> = settings.windDownRoomName
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val windDownAmbience: StateFlow<String> = settings.windDownAmbience
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _rooms = MutableStateFlow<RoomsState>(RoomsState.Idle)
    val rooms: StateFlow<RoomsState> = _rooms

    private val _results = MutableStateFlow<List<WakePick>?>(null)
    /** Null before a search; empty when nothing matched. */
    val results: StateFlow<List<WakePick>?> = _results

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    fun loadRooms() {
        if (_rooms.value is RoomsState.Loading) return
        _rooms.value = RoomsState.Loading
        viewModelScope.launch {
            _rooms.value = try {
                RoomsState.Ready(lights.rooms())
            } catch (e: Exception) {
                RoomsState.Failed("Couldn't reach your Hue Bridge. Check this phone is on the same network, and that a bridge is paired under Light Sync.")
            }
        }
    }

    fun save(alarm: WakeAlarm) {
        viewModelScope.launch {
            settings.upsertWakeAlarm(alarm)
            rearmWakeAlarms(getApplication())
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            settings.deleteWakeAlarm(id)
            rearmWakeAlarms(getApplication())
        }
    }

    fun setEnabled(alarm: WakeAlarm, on: Boolean) = save(alarm.copy(enabled = on))

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            _results.value = null
            return
        }
        _searching.value = true
        viewModelScope.launch {
            _results.value = runCatchingCancellable {
                (getApplication<Application>() as SendpinApp).searchLibrary(q)
                    .filter { it.mediaMetadata.isPlayable == true }
                    .map {
                        WakePick(
                            it.mediaId,
                            it.mediaMetadata.title?.toString().orEmpty(),
                            it.mediaMetadata.subtitle?.toString() ?: it.mediaMetadata.artist?.toString().orEmpty(),
                        )
                    }
                    .take(20)
            }.getOrDefault(emptyList())
            _searching.value = false
        }
    }

    fun clearResults() { _results.value = null }

    fun setWindDownDim(on: Boolean) { viewModelScope.launch { settings.setWindDownDim(on) } }
    fun setWindDownRoom(group: HueGroup) {
        viewModelScope.launch { settings.setWindDownRoom(group.groupedLightId, group.name) }
    }
    fun setWindDownAmbience(wire: String) { viewModelScope.launch { settings.setWindDownAmbience(wire) } }

    /** A brand new alarm with the defaults, at a time a sleeper might actually want. */
    fun blank(): WakeAlarm = WakeAlarm(
        id = java.util.UUID.randomUUID().toString(),
        hour = 7,
        minute = 0,
        days = WakeAlarm.WEEKDAYS,
    )

}
