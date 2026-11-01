/*
 * Minimal libasound (ALSA) for Wine's winealsa driver, backed by Android AAudio.
 *
 * Wine's ALSA driver only needs a playback PCM: open "default", negotiate
 * format/rate/channels, then write interleaved frames as space frees up.
 * Frames go into a ring buffer that AAudio's realtime callback drains. Card,
 * control, capture and MIDI (sequencer) calls report "no device", so Wine shows
 * a single output and no input or MIDI ports.
 */
#include <aaudio/AAudio.h>
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <unistd.h>

#define EXPORT __attribute__((visibility("default")))
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "TtrAudio", __VA_ARGS__)

typedef unsigned long snd_pcm_uframes_t;
typedef long snd_pcm_sframes_t;
enum { STREAM_PLAYBACK = 0 };
enum { STATE_OPEN = 0, STATE_SETUP = 1, STATE_PREPARED = 2, STATE_RUNNING = 3, STATE_XRUN = 4 };
enum { FORMAT_S16_LE = 2, FORMAT_FLOAT_LE = 14 };
enum { RATE_MIN = 8000, RATE_MAX = 48000, CHANNELS_MAX = 2 };

typedef struct {
    int format, channels;
    unsigned rate, period_us, periods;
    snd_pcm_uframes_t buffer_frames, period_frames;
} hw_params;
typedef struct { snd_pcm_uframes_t start_threshold, stop_threshold; } sw_params;
typedef struct { unsigned char bits[8]; } format_mask;

typedef struct {
    pthread_mutex_t lock;
    hw_params hw;
    sw_params sw;
    int state, frame_bytes;
    AAudioStream *stream;
    atomic_int disconnected;
    unsigned char *ring;
    snd_pcm_uframes_t size;          /* ring capacity in frames (= ALSA buffer size) */
    atomic_ulong written, played;    /* monotonic frame counters */
} pcm_t;

/*
 * Background mute: the app creates the file named by TTR_AUDIO_PAUSE_FILE while it is in the background.
 * A watcher thread checks it a few times a second (no file calls in the realtime callback); while it exists
 * the callback still consumes the game's audio, so the game never blocks, but plays silence.
 */
static atomic_int muted;
static void *watch_pause(void *path) {
    for (;;) { atomic_store(&muted, access((const char *)path, F_OK) == 0); usleep(200000); }
    return NULL;
}
static void start_pause_watch(void) {
    const char *path = getenv("TTR_AUDIO_PAUSE_FILE"); if (!path) return;
    pthread_t t; if (pthread_create(&t, NULL, watch_pause, strdup(path)) == 0) pthread_detach(t);
}
static pthread_once_t pause_once = PTHREAD_ONCE_INIT;

static aaudio_data_callback_result_t on_data(AAudioStream *s, void *user, void *audio, int32_t frames) {
    (void)s;
    pcm_t *p = user;
    unsigned long w = atomic_load_explicit(&p->written, memory_order_acquire), r = atomic_load_explicit(&p->played, memory_order_relaxed);
    unsigned long have = w - r, n = have < (unsigned long)frames ? have : (unsigned long)frames;
    unsigned char *out = audio;
    for (unsigned long done = 0; done < n;) {
        unsigned long at = (r + done) % p->size, chunk = p->size - at;
        if (chunk > n - done) chunk = n - done;
        memcpy(out + done * p->frame_bytes, p->ring + at * p->frame_bytes, chunk * p->frame_bytes);
        done += chunk;
    }
    if (atomic_load_explicit(&muted, memory_order_relaxed)) memset(out, 0, n * p->frame_bytes); /* app in background */
    if (n < (unsigned long)frames) memset(out + n * p->frame_bytes, 0, (frames - n) * p->frame_bytes); /* underrun: silence */
    atomic_store_explicit(&p->played, r + n, memory_order_release);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void on_error(AAudioStream *s, void *user, aaudio_result_t error) {
    (void)s; LOG("stream error %d (e.g. output device changed); reopening", error);
    atomic_store(&((pcm_t *)user)->disconnected, 1);
}

static void close_stream(pcm_t *p) {
    if (p->stream) { AAudioStream_requestStop(p->stream); AAudioStream_close(p->stream); p->stream = NULL; }
}

static int open_stream(pcm_t *p) {
    pthread_once(&pause_once, start_pause_watch);
    AAudioStreamBuilder *b;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return -ENODEV;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setFormat(b, p->hw.format == FORMAT_FLOAT_LE ? AAUDIO_FORMAT_PCM_FLOAT : AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(b, p->hw.channels);
    AAudioStreamBuilder_setSampleRate(b, (int32_t)p->hw.rate); /* AAudio resamples when needed */
    AAudioStreamBuilder_setDataCallback(b, on_data, p);
    AAudioStreamBuilder_setErrorCallback(b, on_error, p);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &p->stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) { LOG("openStream failed: %s", AAudio_convertResultToText(r)); p->stream = NULL; return -ENODEV; }
    int32_t burst = AAudioStream_getFramesPerBurst(p->stream);
    if (burst > 0) AAudioStream_setBufferSizeInFrames(p->stream, burst * 2);
    atomic_store(&p->disconnected, 0);
    return 0;
}

/* ---- PCM ---- */

EXPORT int snd_pcm_open(pcm_t **out, const char *name, int stream, int mode) {
    (void)name; (void)mode;
    if (stream != STREAM_PLAYBACK) return -ENOENT;
    pcm_t *p = calloc(1, sizeof *p);
    if (!p) return -ENOMEM;
    pthread_mutex_init(&p->lock, NULL);
    p->state = STATE_OPEN;
    *out = p;
    return 0;
}

EXPORT int snd_pcm_close(pcm_t *p) {
    if (!p) return 0;
    close_stream(p); free(p->ring); pthread_mutex_destroy(&p->lock); free(p);
    return 0;
}

EXPORT size_t snd_pcm_hw_params_sizeof(void) { return sizeof(hw_params); }
EXPORT int snd_pcm_hw_params_any(pcm_t *p, hw_params *h) {
    (void)p; memset(h, 0, sizeof *h);
    h->format = FORMAT_FLOAT_LE; h->channels = CHANNELS_MAX; h->rate = RATE_MAX;
    return 0;
}
EXPORT int snd_pcm_hw_params_set_access(pcm_t *p, hw_params *h, int access) { (void)p; (void)h; return access == 3 ? 0 : -EINVAL; } /* RW_INTERLEAVED */
EXPORT int snd_pcm_hw_params_set_format(pcm_t *p, hw_params *h, int format) {
    (void)p; if (format != FORMAT_S16_LE && format != FORMAT_FLOAT_LE) return -EINVAL;
    h->format = format; return 0;
}
EXPORT int snd_pcm_hw_params_set_rate_near(pcm_t *p, hw_params *h, unsigned *rate, int *dir) {
    (void)p; if (*rate < RATE_MIN) *rate = RATE_MIN; if (*rate > RATE_MAX) *rate = RATE_MAX;
    h->rate = *rate; if (dir) *dir = 0; return 0;
}
EXPORT int snd_pcm_hw_params_set_channels(pcm_t *p, hw_params *h, unsigned channels) {
    (void)p; if (channels < 1 || channels > CHANNELS_MAX) return -EINVAL;
    h->channels = (int)channels; return 0;
}
EXPORT int snd_pcm_hw_params_set_period_time_near(pcm_t *p, hw_params *h, unsigned *us, int *dir) {
    (void)p; if (*us < 1000) *us = 1000; h->period_us = *us; if (dir) *dir = 0; return 0;
}
EXPORT int snd_pcm_hw_params_set_buffer_size_near(pcm_t *p, hw_params *h, snd_pcm_uframes_t *frames) {
    (void)p; if (*frames < 256) *frames = 256; if (*frames > 1u << 18) *frames = 1u << 18;
    h->buffer_frames = *frames; return 0;
}
EXPORT int snd_pcm_hw_params_set_periods_near(pcm_t *p, hw_params *h, unsigned *periods, int *dir) {
    (void)p; if (*periods < 2) *periods = 2; h->periods = *periods; if (dir) *dir = 0; return 0;
}

EXPORT int snd_pcm_hw_params(pcm_t *p, hw_params *h) {
    if (!h->rate) h->rate = RATE_MAX;
    if (!h->channels) h->channels = CHANNELS_MAX;
    if (!h->format) h->format = FORMAT_FLOAT_LE;
    h->period_frames = (snd_pcm_uframes_t)((unsigned long long)h->rate * (h->period_us ? h->period_us : 10000) / 1000000);
    if (h->period_frames < 64) h->period_frames = 64;
    if (!h->buffer_frames) h->buffer_frames = h->period_frames * (h->periods ? h->periods : 4);
    if (h->buffer_frames < h->period_frames * 2) h->buffer_frames = h->period_frames * 2;
    pthread_mutex_lock(&p->lock);
    close_stream(p);
    p->hw = *h;
    p->frame_bytes = h->channels * (h->format == FORMAT_FLOAT_LE ? 4 : 2);
    free(p->ring);
    p->size = h->buffer_frames;
    p->ring = calloc(p->size, p->frame_bytes);
    atomic_store(&p->written, 0); atomic_store(&p->played, 0);
    p->sw.start_threshold = 1; p->sw.stop_threshold = p->size;
    int r = p->ring ? open_stream(p) : -ENOMEM;
    p->state = r ? STATE_OPEN : STATE_PREPARED;
    pthread_mutex_unlock(&p->lock);
    if (!r) LOG("output %u Hz, %d ch, %s, buffer %lu frames", h->rate, h->channels, h->format == FORMAT_FLOAT_LE ? "float" : "s16", (unsigned long)p->size);
    return r;
}

EXPORT int snd_pcm_hw_params_get_period_size(const hw_params *h, snd_pcm_uframes_t *frames, int *dir) { *frames = h->period_frames; if (dir) *dir = 0; return 0; }
EXPORT int snd_pcm_hw_params_get_buffer_size(const hw_params *h, snd_pcm_uframes_t *frames) { *frames = h->buffer_frames; return 0; }
EXPORT int snd_pcm_hw_params_get_rate_min(const hw_params *h, unsigned *v, int *dir) { (void)h; *v = RATE_MIN; if (dir) *dir = 0; return 0; }
EXPORT int snd_pcm_hw_params_get_rate_max(const hw_params *h, unsigned *v, int *dir) { (void)h; *v = RATE_MAX; if (dir) *dir = 0; return 0; }
EXPORT int snd_pcm_hw_params_get_channels_min(const hw_params *h, unsigned *v) { (void)h; *v = 1; return 0; }
EXPORT int snd_pcm_hw_params_get_channels_max(const hw_params *h, unsigned *v) { (void)h; *v = CHANNELS_MAX; return 0; }
EXPORT size_t snd_pcm_format_mask_sizeof(void) { return sizeof(format_mask); }
EXPORT void snd_pcm_hw_params_get_format_mask(hw_params *h, format_mask *m) {
    (void)h; memset(m, 0, sizeof *m);
    m->bits[FORMAT_S16_LE / 8] |= 1 << (FORMAT_S16_LE % 8);
    m->bits[FORMAT_FLOAT_LE / 8] |= 1 << (FORMAT_FLOAT_LE % 8);
}
EXPORT int snd_pcm_format_mask_test(const format_mask *m, int format) {
    return format >= 0 && format < 64 && (m->bits[format / 8] & (1 << (format % 8)));
}

EXPORT size_t snd_pcm_sw_params_sizeof(void) { return sizeof(sw_params); }
EXPORT int snd_pcm_sw_params_current(pcm_t *p, sw_params *s) { *s = p->sw; return 0; }
EXPORT int snd_pcm_sw_params_set_start_threshold(pcm_t *p, sw_params *s, snd_pcm_uframes_t v) { (void)p; s->start_threshold = v; return 0; }
EXPORT int snd_pcm_sw_params_set_stop_threshold(pcm_t *p, sw_params *s, snd_pcm_uframes_t v) { (void)p; s->stop_threshold = v; return 0; }
EXPORT int snd_pcm_sw_params(pcm_t *p, sw_params *s) { p->sw = *s; if (!p->sw.start_threshold) p->sw.start_threshold = 1; return 0; }

static void reopen_if_needed(pcm_t *p) {
    if (!atomic_load(&p->disconnected)) return;
    int running = p->state == STATE_RUNNING;
    close_stream(p);
    if (open_stream(p) == 0 && running) AAudioStream_requestStart(p->stream);
}

EXPORT int snd_pcm_prepare(pcm_t *p) {
    pthread_mutex_lock(&p->lock);
    if (p->state == STATE_RUNNING && p->stream) AAudioStream_requestStop(p->stream);
    atomic_store(&p->played, atomic_load(&p->written));
    if (p->state != STATE_OPEN) p->state = STATE_PREPARED;
    pthread_mutex_unlock(&p->lock);
    return p->state == STATE_OPEN ? -EBADFD : 0;
}
EXPORT int snd_pcm_drop(pcm_t *p) {
    pthread_mutex_lock(&p->lock);
    if (p->stream) AAudioStream_requestStop(p->stream);
    atomic_store(&p->played, atomic_load(&p->written));
    if (p->state != STATE_OPEN) p->state = STATE_SETUP;
    pthread_mutex_unlock(&p->lock);
    return 0;
}
EXPORT int snd_pcm_reset(pcm_t *p) { atomic_store(&p->played, atomic_load(&p->written)); return 0; }
EXPORT int snd_pcm_state(pcm_t *p) { return p->state; }
EXPORT int snd_pcm_recover(pcm_t *p, int err, int silent) { (void)silent; return err == -EPIPE || err == -ESTRPIPE ? snd_pcm_prepare(p) : err; }

EXPORT snd_pcm_sframes_t snd_pcm_avail_update(pcm_t *p) {
    if (p->state == STATE_OPEN) return -EBADFD;
    pthread_mutex_lock(&p->lock); reopen_if_needed(p); pthread_mutex_unlock(&p->lock);
    return (snd_pcm_sframes_t)(p->size - (atomic_load(&p->written) - atomic_load(&p->played)));
}

EXPORT snd_pcm_sframes_t snd_pcm_writei(pcm_t *p, const void *buffer, snd_pcm_uframes_t frames) {
    if (p->state == STATE_OPEN) return -EBADFD;
    pthread_mutex_lock(&p->lock);
    if (p->state == STATE_SETUP) { pthread_mutex_unlock(&p->lock); return -EBADFD; }
    reopen_if_needed(p);
    unsigned long w = atomic_load(&p->written), free_frames = p->size - (w - atomic_load(&p->played));
    unsigned long n = frames < free_frames ? frames : free_frames;
    const unsigned char *in = buffer;
    for (unsigned long done = 0; done < n;) {
        unsigned long at = (w + done) % p->size, chunk = p->size - at;
        if (chunk > n - done) chunk = n - done;
        memcpy(p->ring + at * p->frame_bytes, in + done * p->frame_bytes, chunk * p->frame_bytes);
        done += chunk;
    }
    atomic_store_explicit(&p->written, w + n, memory_order_release);
    if (p->state == STATE_PREPARED && w + n - atomic_load(&p->played) >= p->sw.start_threshold && p->stream) {
        AAudioStream_requestStart(p->stream); p->state = STATE_RUNNING;
    }
    pthread_mutex_unlock(&p->lock);
    return n ? (snd_pcm_sframes_t)n : -EAGAIN;
}

EXPORT snd_pcm_sframes_t snd_pcm_rewind(pcm_t *p, snd_pcm_uframes_t frames) {
    pthread_mutex_lock(&p->lock);
    unsigned long w = atomic_load(&p->written), queued = w - atomic_load(&p->played);
    unsigned long n = frames < queued ? frames : queued;
    atomic_store(&p->written, w - n);
    pthread_mutex_unlock(&p->lock);
    return (snd_pcm_sframes_t)n;
}

EXPORT snd_pcm_sframes_t snd_pcm_readi(pcm_t *p, void *buffer, snd_pcm_uframes_t frames) { (void)p; (void)buffer; (void)frames; return -EBADFD; }

EXPORT int snd_pcm_format_set_silence(int format, void *buffer, unsigned samples) {
    size_t bytes = format == FORMAT_FLOAT_LE ? 4 : format == FORMAT_S16_LE ? 2 : format == 10 ? 4 : 1;
    memset(buffer, format == 1 ? 0x80 : 0, bytes * samples); /* U8 silence is 0x80 */
    return 0;
}

EXPORT const char *snd_strerror(int error) { return error < 0 ? strerror(-error) : "Unknown error"; }

/* ---- No sound cards or controls: Wine lists only the "default" device. ---- */
EXPORT int snd_card_next(int *card) { *card = -1; return 0; }
EXPORT int snd_card_get_name(int card, char **name) { (void)card; *name = NULL; return -ENOENT; }
EXPORT int snd_ctl_open(void **ctl, const char *name, int mode) { (void)name; (void)mode; *ctl = NULL; return -ENOENT; }
EXPORT int snd_ctl_close(void *ctl) { (void)ctl; return 0; }
EXPORT int snd_ctl_pcm_next_device(void *ctl, int *device) { (void)ctl; *device = -1; return -ENOENT; }
EXPORT int snd_ctl_pcm_info(void *ctl, void *info) { (void)ctl; (void)info; return -ENOENT; }
EXPORT size_t snd_pcm_info_sizeof(void) { return 64; }
EXPORT void snd_pcm_info_set_device(void *info, unsigned v) { (void)info; (void)v; }
EXPORT void snd_pcm_info_set_subdevice(void *info, unsigned v) { (void)info; (void)v; }
EXPORT void snd_pcm_info_set_stream(void *info, int v) { (void)info; (void)v; }
EXPORT const char *snd_pcm_info_get_name(const void *info) { (void)info; return "Android"; }

/* ---- No MIDI sequencer: snd_seq_open fails, so the other calls are never reached. ---- */
static char empty[64];
EXPORT int snd_seq_open(void **seq, const char *name, int streams, int mode) { (void)name; (void)streams; (void)mode; *seq = NULL; return -ENOENT; }
EXPORT int snd_midi_event_new(size_t size, void **out) { (void)size; *out = NULL; return -ENOMEM; }
#define FAIL(name) EXPORT long name(void) { return -ENOSYS; }
FAIL(snd_seq_close) FAIL(snd_seq_set_client_name) FAIL(snd_seq_create_simple_port) FAIL(snd_seq_delete_simple_port)
FAIL(snd_seq_connect_to) FAIL(snd_seq_connect_from) FAIL(snd_seq_disconnect_from) FAIL(snd_seq_event_output_direct)
FAIL(snd_seq_query_next_client) FAIL(snd_seq_query_next_port) FAIL(snd_seq_poll_descriptors_count) FAIL(snd_seq_poll_descriptors)
FAIL(snd_seq_free_event) FAIL(snd_seq_event_input_pending) FAIL(snd_seq_event_input) FAIL(snd_midi_event_init)
FAIL(snd_midi_event_encode_byte) FAIL(snd_midi_event_free) FAIL(snd_seq_client_info_set_client) FAIL(snd_seq_client_info_get_client)
FAIL(snd_seq_client_info_get_type) FAIL(snd_seq_port_info_set_client) FAIL(snd_seq_port_info_set_port) FAIL(snd_seq_port_info_get_port)
FAIL(snd_seq_port_info_get_capability) FAIL(snd_seq_port_info_get_type)
EXPORT size_t snd_seq_client_info_sizeof(void) { return sizeof empty; }
EXPORT size_t snd_seq_port_info_sizeof(void) { return sizeof empty; }
EXPORT const char *snd_seq_client_info_get_name(const void *i) { (void)i; return empty; }
EXPORT const char *snd_seq_port_info_get_name(const void *i) { (void)i; return empty; }
EXPORT const void *snd_seq_port_info_get_addr(const void *i) { (void)i; return empty; }
