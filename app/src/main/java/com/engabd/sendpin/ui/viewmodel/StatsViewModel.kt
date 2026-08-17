package com.engabd.sendpin.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.local.db.ArtistPlayCount
import com.engabd.sendpin.local.db.FormatPlayCount
import com.engabd.sendpin.local.db.LocalMediaDatabase
import com.engabd.sendpin.local.db.ProviderPlayCount
import com.engabd.sendpin.local.db.TrackPlayCount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** "This week" — a simple, listener-legible window rather than a picker for v1. */
private const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000

class StatsViewModel(app: Application) : AndroidViewModel(app) {

    data class State(
        val loading: Boolean = true,
        val topArtists: List<ArtistPlayCount> = emptyList(),
        val totalListeningMs: Long = 0,
        val mostPlayed: TrackPlayCount? = null,
        val formats: List<FormatPlayCount> = emptyList(),
        val providers: List<ProviderPlayCount> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val dao by lazy { LocalMediaDatabase.get(getApplication()).playHistoryDao() }

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val since = System.currentTimeMillis() - WINDOW_MS
            _state.value = State(
                loading = false,
                topArtists = dao.topArtists(since),
                totalListeningMs = dao.totalListeningMs(since),
                mostPlayed = dao.mostPlayedTrack(since),
                formats = dao.formatBreakdown(since),
                providers = dao.providerBreakdown(since),
            )
        }
    }
}

/** "3h 24m", or "12m" under an hour, or "0m" — never a bare "0". */
fun formatListeningTime(ms: Long): String {
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

/** "FLAC 96/24", "MP3", "Opus" — the label the format-breakdown pie uses. */
fun FormatPlayCount.label(): String {
    val name = codec?.uppercase()?.takeIf { it.isNotBlank() } ?: return "Unknown"
    if (sampleRate <= 0 || bitDepth <= 0) return name
    val khz = sampleRate / 1000f
    val khzLabel = if (khz == khz.toInt().toFloat()) khz.toInt().toString() else "%.1f".format(khz)
    return "$name $khzLabel/$bitDepth"
}
