package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.ha.HaClient
import com.engabd.sendpin.ha.HaMediaPlayer
import com.engabd.sendpin.ha.LightArea
import com.engabd.sendpin.ha.LightSyncRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Light-sync control over the **Home Assistant** WebSocket API (the Hue Synco
 * integration's per-area entities). Needs an HA base URL + long-lived token; MA-
 * only stack by design (light sync lives in HA next to Music Assistant).
 */
class LightSyncViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings(app)
    private val ha = HaClient()
    private val repo = LightSyncRepository(ha)

    private val _areas = MutableStateFlow<List<LightArea>>(emptyList()); val areas: StateFlow<List<LightArea>> = _areas
    private val _mediaPlayers = MutableStateFlow<List<HaMediaPlayer>>(emptyList()); val mediaPlayers: StateFlow<List<HaMediaPlayer>> = _mediaPlayers
    private val _selectedId = MutableStateFlow<String?>(null)
    private val _error = MutableStateFlow<String?>(null); val error: StateFlow<String?> = _error
    private val _needsConfig = MutableStateFlow(false); val needsConfig: StateFlow<Boolean> = _needsConfig

    val connected: StateFlow<Boolean> = ha.state
        .map { it == HaClient.State.CONNECTED }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val selectedArea: StateFlow<LightArea?> = combine(_areas, _selectedId) { areas, id ->
        areas.firstOrNull { it.id == id } ?: areas.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Persisted HA fields for the inline connect form. The URL defaults to the MA
    // host on HA's standard port 8123 (HA usually lives next to Music Assistant),
    // so the user typically only needs to paste a token.
    val haUrl: StateFlow<String> = combine(settings.haUrl, settings.maBaseUrl) { ha, ma ->
        ha.ifBlank { deriveHaUrl(ma) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val haToken: StateFlow<String> = settings.haToken.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private fun deriveHaUrl(maBase: String): String {
        if (maBase.isBlank()) return ""
        return try {
            val schemeEnd = maBase.indexOf("://").let { if (it >= 0) it + 3 else 0 }
            val scheme = if (schemeEnd > 0) maBase.substring(0, schemeEnd) else "http://"
            val hostPort = maBase.substring(schemeEnd).substringBefore('/')
            val host = hostPort.substringBefore(':')
            "$scheme$host:8123"
        } catch (_: Exception) { "" }
    }

    init {
        viewModelScope.launch {
            val url = settings.haUrl.first()
            val token = settings.haToken.first()
            if (url.isNotBlank() && token.isNotBlank()) ha.connect(url, token) else _needsConfig.value = true
        }
        viewModelScope.launch {
            ha.state.collect { st ->
                when (st) {
                    HaClient.State.CONNECTED -> { _needsConfig.value = false; refresh() }
                    HaClient.State.ERROR -> _error.value = "Couldn't reach Home Assistant (check URL + token)"
                    else -> {}
                }
            }
        }
    }

    /** Save + connect from the inline form. */
    fun connect(url: String, token: String) {
        _error.value = null
        viewModelScope.launch { settings.setHomeAssistant(url.trim(), token.trim()) }
        if (url.isNotBlank() && token.isNotBlank()) {
            _needsConfig.value = false
            ha.connect(url.trim(), token.trim())
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val areas = repo.discover()
                _areas.value = areas
                _mediaPlayers.value = repo.mediaPlayers()
                if (_selectedId.value == null || areas.none { it.id == _selectedId.value }) {
                    _selectedId.value = areas.firstOrNull()?.id
                }
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun selectArea(id: String) { _selectedId.value = id }

    fun setEnabled(on: Boolean) = patch({ it.copy(enabled = on) }) { repo.setEnabled(it, on) }
    fun setMode(option: String) = patch({ it.copy(mode = option) }) { repo.setMode(it, option) }
    fun setEffect(option: String) = patch({ it.copy(effect = option) }) { repo.setEffect(it, option) }
    fun setColour(option: String) = patch({ it.copy(colour = option) }) { repo.setColour(it, option) }
    // Continuous sliders → debounced so a drag doesn't flood HA.
    fun setBrightness(pct: Int) = patchDebounced("brightness", { it.copy(brightnessPct = pct.coerceIn(5, 100)) }) { repo.setBrightness(it, pct) }
    fun setTiming(ms: Int) = patch({ it.copy(timingMs = ms.coerceIn(-500, 500)) }) { repo.setTiming(it, ms) }

    fun changeTiming(deltaMs: Int) {
        val ms = (selectedArea.value?.timingMs ?: 0) + deltaMs
        setTiming(ms)
    }

    // --- advanced settings (via hue_music_sync.set_options) ----------------

    fun setFollowPlayer(entityId: String) =
        patch({ it.copy(mediaPlayer = entityId) }) { repo.setFollowPlayer(it, entityId) }

    /** Toggle one rung in the Auto ladder (keeps at least one selected). */
    fun toggleAutoLevel(level: String) {
        val area = selectedArea.value ?: return
        val next = if (level in area.autoLevels) area.autoLevels - level else area.autoLevels + level
        if (next.isEmpty()) return
        val ordered = LightSyncRepository.AUTO_RUNGS.filter { it in next }
        patch({ it.copy(autoLevels = ordered) }) { repo.setAutoLevels(it, ordered) }
    }

    fun setAutoTiming(on: Boolean) =
        patch({ it.copy(autoTiming = on) }) { repo.setAutoTiming(it, on) }

    fun setAdvanced(on: Boolean) =
        patch({ it.copy(advanced = on) }) { repo.setAdvanced(it, on) }

    /** Set one tunable factor (0f..2f); sends the full map, debounced for drags. */
    fun setTunable(key: String, factor: Float) {
        val area = selectedArea.value ?: return
        val full = LightSyncRepository.TUNABLE_DEFS.associate { (k, _) ->
            k to if (k == key) factor else (area.tunables[k] ?: 1f)
        }
        patchDebounced("tunable", { it.copy(tunables = full) }) { repo.setTunables(it, full) }
    }

    /** Optimistically patch the selected area locally, then send the command to HA. */
    private inline fun patch(crossinline local: (LightArea) -> LightArea, crossinline remote: suspend (LightArea) -> Unit) {
        val target = selectedArea.value ?: return
        _areas.update { list -> list.map { if (it.id == target.id) local(it) else it } }
        viewModelScope.launch {
            try { remote(local(target)) } catch (e: Exception) { _error.value = e.message }
        }
    }

    // Debounced variant for sliders (brightness, tunables) so a drag sends one call.
    private val debounceJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private inline fun patchDebounced(key: String, crossinline local: (LightArea) -> LightArea, crossinline remote: suspend (LightArea) -> Unit) {
        val target = selectedArea.value ?: return
        _areas.update { list -> list.map { if (it.id == target.id) local(it) else it } }
        debounceJobs[key]?.cancel()
        debounceJobs[key] = viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            try { remote(local(target)) } catch (e: Exception) { _error.value = e.message }
        }
    }

    override fun onCleared() {
        super.onCleared()
        ha.disconnect()
    }
}
