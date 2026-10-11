/*
 * C wrapper around Signalsmith Stretch (MIT) for Grooveo desktop: time-stretching
 * with pitch kept, so a DJ transition can match the incoming track's tempo to the
 * outgoing one. Kotlin calls it through JNA with interleaved stereo float buffers.
 *
 * The caller decides how many input frames each call consumes and how many output
 * frames it produces; their ratio is the playback rate. That makes the source
 * position of every output frame exact, which beat sync relies on.
 *
 * Copyright (C) 2026 lcbs181, GPL-3.0-or-later (same as Grooveo).
 */
#include <vector>
#include <signalsmith-stretch/signalsmith-stretch.h>

#if defined(_WIN32)
#define GROOVEO_API extern "C" __declspec(dllexport)
#else
#define GROOVEO_API extern "C" __attribute__((visibility("default")))
#endif

namespace {
struct Stretch {
  signalsmith::stretch::SignalsmithStretch<float> s;
  int channels = 2;
  std::vector<std::vector<float>> in, out;
  std::vector<float *> inPtr, outPtr;

  void ensure(int inFrames, int outFrames) {
    for (int c = 0; c < channels; c++) {
      if ((int)in[c].size() < inFrames) in[c].resize(inFrames);
      if ((int)out[c].size() < outFrames) out[c].resize(outFrames);
      inPtr[c] = in[c].data();
      outPtr[c] = out[c].data();
    }
  }
};
}

GROOVEO_API void *grooveo_stretch_new(int channels, float sample_rate) {
  auto *st = new Stretch();
  st->channels = channels;
  st->in.resize(channels);
  st->out.resize(channels);
  st->inPtr.resize(channels);
  st->outPtr.resize(channels);
  st->s.presetDefault(channels, sample_rate);
  return st;
}

GROOVEO_API int grooveo_stretch_input_latency(void *h) { return ((Stretch *)h)->s.inputLatency(); }
GROOVEO_API int grooveo_stretch_output_latency(void *h) { return ((Stretch *)h)->s.outputLatency(); }

/** Pitch shift in semitones (0 = none), independent of the playback rate. */
GROOVEO_API void grooveo_stretch_set_transpose(void *h, float semitones) {
  ((Stretch *)h)->s.setTransposeSemitones(semitones);
}

/** Consumes in_frames interleaved frames and writes out_frames interleaved frames. */
GROOVEO_API void grooveo_stretch_process(void *h, const float *in, int in_frames, float *out, int out_frames) {
  auto *st = (Stretch *)h;
  int ch = st->channels;
  st->ensure(in_frames, out_frames);
  for (int i = 0; i < in_frames; i++)
    for (int c = 0; c < ch; c++) st->in[c][i] = in[i * ch + c];
  st->s.process(st->inPtr.data(), in_frames, st->outPtr.data(), out_frames);
  for (int i = 0; i < out_frames; i++)
    for (int c = 0; c < ch; c++) out[i * ch + c] = st->out[c][i];
}

GROOVEO_API void grooveo_stretch_reset(void *h) { ((Stretch *)h)->s.reset(); }

GROOVEO_API void grooveo_stretch_free(void *h) { delete (Stretch *)h; }
