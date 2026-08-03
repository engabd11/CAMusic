package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.ma.DspConfig
import com.engabd.sendpin.ma.DspFilter
import com.engabd.sendpin.ma.DspPreset
import com.engabd.sendpin.ma.DspParse
import com.engabd.sendpin.ma.EqBand
import com.engabd.sendpin.ma.EqBandType
import com.engabd.sendpin.ma.AudioChannel
import com.engabd.sendpin.ma.MaApiClient
import com.engabd.sendpin.ma.MaRepository
import com.engabd.sendpin.ma.ParametricEQFilter
import com.engabd.sendpin.ma.ToneControlFilter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The DSP / Equalizer screen's state and actions.
 *
 * Reads and writes the per-player DSP configuration on Music Assistant through
 * `config/players/dsp/get` and `config/players/dsp/save` (admin). Changes are
 * applied live by MA — no playback restart needed.
 *
 * Also manages reusable DSP presets via `config/dsp_presets` commands.
 *
 * Works for **any** MA player — not just this phone. The target player follows
 * the same setting the Speakers screen and Now Playing use ("Play here"
 * selection), so DSP adjustments land on whatever the user is listening to.
 */
class DspViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings(app)
    private val myPlayerId = PlayerIdentity.getPlayerId(app)
    private val api = (app as SendpinApp).maApi
    private val repo = MaRepository(api)

    private val _target = MutableStateFlow("")
    private fun targetId() = _target.value.ifBlank { myPlayerId }

    // ── State ─────────────────────────────────────────────────────────────

    /** The player's current DSP config, or null while loading / unavailable. */
    private val _config = MutableStateFlow<DspConfig?>(null)
    val config: StateFlow<DspConfig?> = _config

    /** True while the initial load is in flight. */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    /** True while a save is in flight. */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    /** Error message, or null. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** Available presets. */
    private val _presets = MutableStateFlow<List<DspPreset>>(emptyList())
    val presets: StateFlow<List<DspPreset>> = _presets

    /** Name being typed for "save as preset". */
    private val _presetName = MutableStateFlow("")
    val presetName: StateFlow<String> = _presetName

    /** Show the save-as-preset dialog. */
    private val _showSavePreset = MutableStateFlow(false)
    val showSavePreset: StateFlow<Boolean> = _showSavePreset

    /** Which expandable sections are open. */
    private val _eqExpanded = MutableStateFlow(false)
    val eqExpanded: StateFlow<Boolean> = _eqExpanded

    private val _toneExpanded = MutableStateFlow(false)
    val toneExpanded: StateFlow<Boolean> = _toneExpanded

    private val _gainExpanded = MutableStateFlow(false)
    val gainExpanded: StateFlow<Boolean> = _gainExpanded

    private val _presetsExpanded = MutableStateFlow(false)
    val presetsExpanded: StateFlow<Boolean> = _presetsExpanded

    /** Whether the MA API is connected. */
    val connected: StateFlow<Boolean> = api.state
        .let { state ->
            MutableStateFlow(state.value == MaApiClient.State.CONNECTED).also { msf ->
                viewModelScope.launch {
                    state.collect { msf.value = it == MaApiClient.State.CONNECTED }
                }
            }
        }

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    /** The display name of the player we're configuring. */
    private val _playerName = MutableStateFlow("This phone")
    val playerName: StateFlow<String> = _playerName

    init {
        viewModelScope.launch { settings.targetPlayer.collect { _target.value = it } }
    }

    // ── Load ──────────────────────────────────────────────────────────────

    /**
     * Fetch the current player's DSP config and the preset list.
     * Called when the screen opens, or to refresh after an external change.
     */
    fun load() {
        if (api.state.value != MaApiClient.State.CONNECTED) {
            _error.value = "Not connected to Music Assistant"
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val id = targetId()
                // Read player name for the header.
                runCatching { repo.getPlayer(id) }.getOrNull()?.let { _playerName.value = it.name }
                val cfg = repo.getDspConfig(id)
                _config.value = cfg ?: DspConfig()
                // Expand sections that have enabled content.
                cfg?.let { c ->
                    _eqExpanded.value = c.filters.any { it is DspFilter.Eq && it.enabled }
                    _toneExpanded.value = c.filters.any { it is DspFilter.Tone && it.enabled }
                    _gainExpanded.value = c.inputGain != 0f || c.outputGain != 0f
                }
                // Load presets in parallel.
                loadPresets()
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't load DSP config"
            }
            _loading.value = false
        }
    }

    private suspend fun loadPresets() {
        runCatching { repo.getDspPresets() }
            .onSuccess { _presets.value = it }
            .onFailure { /* presets are optional — silent failure */ }
    }

    // ── Save ──────────────────────────────────────────────────────────────

    /**
     * Persist the current local config to the server. MA validates and applies
     * it live.
     */
    fun save() {
        val cfg = _config.value ?: return
        viewModelScope.launch {
            _saving.value = true
            try {
                val saved = repo.saveDspConfig(targetId(), cfg)
                if (saved != null) {
                    _config.value = saved
                    _toast.tryEmit("DSP applied")
                } else {
                    _toast.tryEmit("DSP saved but server didn't confirm")
                }
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't save DSP config")
            }
            _saving.value = false
        }
    }

    // ── Config mutations (local, saved on `save()`) ───────────────────────

    fun toggleEnabled() {
        val cfg = _config.value ?: DspConfig()
        _config.value = cfg.copy(enabled = !cfg.enabled)
    }

    fun setInputGain(gain: Float) {
        _config.value = _config.value?.copy(inputGain = gain)
    }

    fun setOutputGain(gain: Float) {
        _config.value = _config.value?.copy(outputGain = gain)
    }

    // ── Parametric EQ ─────────────────────────────────────────────────────

    /** The first parametric EQ filter in the chain, or a fresh one if none. */
    private fun eqFilter(): ParametricEQFilter =
        (_config.value?.filters?.firstOrNull { it is DspFilter.Eq } as? DspFilter.Eq)?.filter
            ?: ParametricEQFilter()

    /** The first tone control filter, or a fresh one if none. */
    private fun toneFilter(): ToneControlFilter =
        (_config.value?.filters?.firstOrNull { it is DspFilter.Tone } as? DspFilter.Tone)?.filter
            ?: ToneControlFilter()

    fun toggleEq() {
        val cfg = _config.value ?: DspConfig()
        val eq = eqFilter().copy(enabled = !eqFilter().enabled)
        _config.value = replaceFilter(cfg, DspFilter.Eq(eq))
    }

    fun setEqPreamp(value: Float) {
        val cfg = _config.value ?: DspConfig()
        val eq = eqFilter().copy(preamp = value)
        _config.value = replaceFilter(cfg, DspFilter.Eq(eq))
    }

    fun addBand() {
        val cfg = _config.value ?: DspConfig()
        val eq = eqFilter()
        val newBand = EqBand(frequency = 1000f, q = 1f, gain = 0f, typeWire = "peak")
        _config.value = replaceFilter(cfg, DspFilter.Eq(eq.copy(bands = eq.bands + newBand)))
    }

    fun removeBand(index: Int) {
        val cfg = _config.value ?: DspConfig()
        val eq = eqFilter()
        if (index !in eq.bands.indices) return
        _config.value = replaceFilter(cfg, DspFilter.Eq(eq.copy(bands = eq.bands - eq.bands[index])))
    }

    fun updateBand(index: Int, band: EqBand) {
        val cfg = _config.value ?: DspConfig()
        val eq = eqFilter()
        if (index !in eq.bands.indices) return
        _config.value = replaceFilter(cfg, DspFilter.Eq(eq.copy(bands = eq.bands.mapIndexed { i, b -> if (i == index) band else b })))
    }

    fun setBandFreq(index: Int, freq: Float) {
        updateBand(index, eqFilter().bands.getOrElse(index) { EqBand() }.copy(frequency = freq))
    }

    fun setBandGain(index: Int, gain: Float) {
        updateBand(index, eqFilter().bands.getOrElse(index) { EqBand() }.copy(gain = gain))
    }

    fun setBandQ(index: Int, q: Float) {
        updateBand(index, eqFilter().bands.getOrElse(index) { EqBand() }.copy(q = q))
    }

    fun setBandType(index: Int, type: EqBandType) {
        updateBand(index, eqFilter().bands.getOrElse(index) { EqBand() }.copy(typeWire = type.wire))
    }

    fun setBandChannel(index: Int, channel: AudioChannel) {
        updateBand(index, eqFilter().bands.getOrElse(index) { EqBand() }.copy(channelWire = channel.wire))
    }

    fun toggleBand(index: Int) {
        val b = eqFilter().bands.getOrElse(index) { return }
        updateBand(index, b.copy(enabled = !b.enabled))
    }

    // ── Tone control ──────────────────────────────────────────────────────

    fun toggleTone() {
        val cfg = _config.value ?: DspConfig()
        val tone = toneFilter().copy(enabled = !toneFilter().enabled)
        _config.value = replaceFilter(cfg, DspFilter.Tone(tone))
    }

    fun setBass(level: Float) {
        val cfg = _config.value ?: DspConfig()
        _config.value = replaceFilter(cfg, DspFilter.Tone(toneFilter().copy(bassLevel = level)))
    }

    fun setMid(level: Float) {
        val cfg = _config.value ?: DspConfig()
        _config.value = replaceFilter(cfg, DspFilter.Tone(toneFilter().copy(midLevel = level)))
    }

    fun setTreble(level: Float) {
        val cfg = _config.value ?: DspConfig()
        _config.value = replaceFilter(cfg, DspFilter.Tone(toneFilter().copy(trebleLevel = level)))
    }

    // ── Section expand/collapse ───────────────────────────────────────────

    fun toggleEqExpanded() { _eqExpanded.value = !_eqExpanded.value }
    fun toggleToneExpanded() { _toneExpanded.value = !_toneExpanded.value }
    fun toggleGainExpanded() { _gainExpanded.value = !_gainExpanded.value }
    fun togglePresetsExpanded() { _presetsExpanded.value = !_presetsExpanded.value }

    // ── Presets ───────────────────────────────────────────────────────────

    fun setPresetName(name: String) { _presetName.value = name }
    fun showSavePresetDialog() { _showSavePreset.value = true }
    fun hideSavePresetDialog() { _showSavePreset.value = false; _presetName.value = "" }

    fun saveAsPreset() {
        val name = _presetName.value.trim()
        if (name.isBlank()) return
        val cfg = _config.value ?: return
        viewModelScope.launch {
            try {
                val saved = repo.saveDspPreset(DspPreset(name = name, config = cfg))
                if (saved != null) {
                    _presets.value = _presets.value.filterNot { it.presetId == saved.presetId } + saved
                    _toast.tryEmit("Preset \"$name\" saved")
                }
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't save preset")
            }
            _showSavePreset.value = false
            _presetName.value = ""
        }
    }

    fun applyPreset(preset: DspPreset) {
        _config.value = preset.config
        _eqExpanded.value = preset.config.filters.any { it is DspFilter.Eq && it.enabled }
        _toneExpanded.value = preset.config.filters.any { it is DspFilter.Tone && it.enabled }
        _gainExpanded.value = preset.config.inputGain != 0f || preset.config.outputGain != 0f
        save()
    }

    fun deletePreset(preset: DspPreset) {
        val id = preset.presetId ?: return
        viewModelScope.launch {
            try {
                repo.removeDspPreset(id)
                _presets.value = _presets.value.filterNot { it.presetId == id }
                _toast.tryEmit("Preset \"${preset.name}\" deleted")
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't delete preset")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Replace the first filter of the same type in [cfg], or append if none
     * exists. Keeps the filter chain ordered: EQ first, then tone.
     */
    private fun replaceFilter(cfg: DspConfig, newFilter: DspFilter): DspConfig {
        val others = cfg.filters.filterNot {
            (it is DspFilter.Eq && newFilter is DspFilter.Eq) ||
            (it is DspFilter.Tone && newFilter is DspFilter.Tone)
        }
        // Maintain order: EQ before tone.
        val result = mutableListOf<DspFilter>()
        if (newFilter is DspFilter.Eq) result += newFilter
        result += others.filter { it is DspFilter.Eq }
        if (newFilter is DspFilter.Tone) result += newFilter
        result += others.filter { it is DspFilter.Tone }
        return cfg.copy(filters = result.distinctBy { it::class })
    }
}