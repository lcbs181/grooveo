/*
 * Thin C wrapper around aubio's beat tracker for Grooveo desktop.
 *
 * Kotlin calls these few functions through JNA instead of binding aubio's
 * fvec/object API directly: samples go in as plain float arrays, beat times
 * come out as seconds. aubio itself is compiled into the same shared library
 * (see CMakeLists.txt), so the app ships a single native file per OS.
 *
 * Copyright (C) 2026 lcbs181, GPL-3.0-or-later (same as Grooveo).
 */
#include <stdlib.h>
#include <aubio.h>

#if defined(_WIN32)
#define GROOVEO_API __declspec(dllexport)
#else
#define GROOVEO_API __attribute__((visibility("default")))
#endif

typedef struct {
  aubio_tempo_t *tempo;
  fvec_t *in;
  fvec_t *out;
  unsigned hop;
  unsigned filled;
} grooveo_beats_t;

GROOVEO_API void *grooveo_beats_new(unsigned samplerate, unsigned win, unsigned hop) {
  grooveo_beats_t *b = calloc(1, sizeof(grooveo_beats_t));
  if (!b) return NULL;
  b->tempo = new_aubio_tempo("specflux", win, hop, samplerate);
  b->in = new_fvec(hop);
  b->out = new_fvec(2);
  b->hop = hop;
  if (!b->tempo || !b->in || !b->out) {
    if (b->tempo) del_aubio_tempo(b->tempo);
    if (b->in) del_fvec(b->in);
    if (b->out) del_fvec(b->out);
    free(b);
    return NULL;
  }
  return b;
}

/*
 * Feeds n mono samples (any chunk size; partial hops are kept for the next
 * call). Writes the time in seconds of each beat detected in this chunk to
 * beats (at most max_beats) and returns how many were written.
 */
GROOVEO_API int grooveo_beats_process(void *handle, const float *samples, int n, double *beats, int max_beats) {
  grooveo_beats_t *b = handle;
  int found = 0;
  for (int i = 0; i < n; i++) {
    b->in->data[b->filled++] = samples[i];
    if (b->filled < b->hop) continue;
    b->filled = 0;
    aubio_tempo_do(b->tempo, b->in, b->out);
    if (b->out->data[0] != 0 && found < max_beats) beats[found++] = aubio_tempo_get_last_s(b->tempo);
  }
  return found;
}

GROOVEO_API float grooveo_beats_bpm(void *handle) {
  return aubio_tempo_get_bpm(((grooveo_beats_t *)handle)->tempo);
}

GROOVEO_API float grooveo_beats_confidence(void *handle) {
  return aubio_tempo_get_confidence(((grooveo_beats_t *)handle)->tempo);
}

GROOVEO_API void grooveo_beats_free(void *handle) {
  grooveo_beats_t *b = handle;
  if (!b) return;
  del_aubio_tempo(b->tempo);
  del_fvec(b->in);
  del_fvec(b->out);
  free(b);
}
