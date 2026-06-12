#ifndef SENDSPIN_FLAC_DECODER_H
#define SENDSPIN_FLAC_DECODER_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// Create a FLAC decoder configured for the given format.
// Returns opaque handle, or NULL on failure.
void* flac_decoder_create(int sample_rate, int channels, int bit_depth);

// Decode a single FLAC frame into packed 3-byte i24 PCM.
// pcm_out receives 3 bytes per sample (packed i24 LE interleaved).
// Returns number of SAMPLES decoded (not bytes!). Negative = error.
int flac_decode(void* handle,
                const uint8_t* flac_data, int flac_bytes,
                uint8_t* pcm_out, int max_pcm_bytes);

// Reset decoder state (e.g., after format change or error).
void flac_decoder_reset(void* handle);

// Destroy the decoder and free resources.
void flac_decoder_destroy(void* handle);

#ifdef __cplusplus
}
#endif

#endif // SENDSPIN_FLAC_DECODER_H
