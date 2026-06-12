#ifndef SENDSPIN_AUDIO_ENGINE_H
#define SENDSPIN_AUDIO_ENGINE_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// Start audio engine with given format. Returns 0 on success, negative on error.
int engine_start(int sample_rate, int channels, int bit_depth);

// Write raw PCM bytes to the ring buffer. Data must be packed 3-byte i24 LE.
// Returns bytes actually written (may be less than byte_count if buffer full).
int engine_write(const uint8_t* pcm_data, int byte_count);

// Stop audio engine and release resources.
void engine_stop(void);

// Set output volume (0.0 to 1.0).
void engine_set_volume(float volume);

// Get the current playback position in frames written to hardware.
int64_t engine_get_frames_written(void);

// Get the buffer fill level as fraction 0.0-1.0.
float engine_get_buffer_fill(void);

#ifdef __cplusplus
}
#endif

#endif // SENDSPIN_AUDIO_ENGINE_H
