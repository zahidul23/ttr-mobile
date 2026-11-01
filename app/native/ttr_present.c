/*
 * Zero-readback presentation for GL4ES's pbuffer windows (built into libttrgl4es).
 *
 * GL4ES normally reads every frame back to the CPU, flips it and sends it to the X server with
 * XPutImage. Here each frame is copied on the GPU (one flipped blit) into an Android hardware buffer
 * that the X server (Termux:X11) already shares through DRI3, and shown with a Present copy that the
 * X server performs on the GPU. Nothing crosses the CPU or the X socket except small requests.
 *
 * Uses its own X connection, so Wine's Xlib connection and event queue are never touched.
 * Any failure disables this path and GL4ES's original copy takes over. TTR_PRESENT=0 turns it off.
 */
#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <dlfcn.h>
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define BUFFERS 3
#define AHB_SOCKET_MODIFIER 1255ull
#ifndef AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM
#define AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM 5 /* HAL_PIXEL_FORMAT_BGRA_8888; accepted by the X server */
#endif
#define GL_READ_FRAMEBUFFER_ 0x8CA8
#define GL_DRAW_FRAMEBUFFER_ 0x8CA9
#define GL_READ_FRAMEBUFFER_BINDING_ 0x8CAA

typedef void (*BlitFn)(GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLbitfield, GLenum);
typedef struct {
    AHardwareBuffer *ahb; EGLImageKHR image; GLuint texture, fbo; uint32_t pixmap; int busy; unsigned long long used;
} Buffer;

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int state; /* 0 untried, 1 ready, -1 disabled */
static int xfd = -1;
static uint16_t seq;
static uint32_t idBase, idMask, idNext = 1, eventId;
static uint8_t dri3Op, presentOp;
static Buffer buffers[BUFFERS];
static uint32_t window, presentSerial;
static int width, height;
static EGLContext context;
static unsigned long long frames;
int ttr_gl_log_enabled(void); void ttr_gl_log(const char *format, ...);
static void (*glReadPixels_)(GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, void *);
/* BGRA (X byte order) lets the server swap channels into its RGBX screen; TTR_PRESENT_FORMAT=rgba for tests. */
static uint32_t bufferFormat = AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM;
/*
 * Two ways to fill the shared buffer: 1 = one flipped glBlitFramebuffer (fast; BGRA buffer), 2 = copy into a
 * texture and draw it flipped with red and blue swapped (RGBA buffer). Some GPUs (PowerVR) silently ignore the
 * blit, so frames with picture content are checked until one matches; drawing takes over if the buffer stays empty.
 */
static int method = 1, verified;
static GLuint copyTexture, program, vertexBuffer, vertexArray; static int copyWidth, copyHeight; static EGLContext drawContext;
static void (*glCopyTexSubImage2D_)(GLenum, GLint, GLint, GLint, GLint, GLint, GLsizei, GLsizei);
static void (*glTexImage2D_)(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void *);
static void (*glTexParameteri_)(GLenum, GLenum, GLint), (*glActiveTexture_)(GLenum);
static GLuint (*glCreateShader_)(GLenum), (*glCreateProgram_)(void);
static void (*glShaderSource_)(GLuint, GLsizei, const GLchar *const *, const GLint *), (*glCompileShader_)(GLuint);
static void (*glAttachShader_)(GLuint, GLuint), (*glBindAttribLocation_)(GLuint, GLuint, const GLchar *), (*glLinkProgram_)(GLuint);
static void (*glGetProgramiv_)(GLuint, GLenum, GLint *), (*glUniform1i_)(GLint, GLint), (*glUseProgram_)(GLuint);
static GLint (*glGetUniformLocation_)(GLuint, const GLchar *);
static void (*glGenBuffers_)(GLsizei, GLuint *), (*glBindBuffer_)(GLenum, GLuint), (*glBufferData_)(GLenum, GLsizeiptr, const void *, GLenum);
static void (*glGenVertexArrays_)(GLsizei, GLuint *), (*glBindVertexArray_)(GLuint), (*glBindSampler_)(GLuint, GLuint);
static void (*glVertexAttribPointer_)(GLuint, GLint, GLenum, GLboolean, GLsizei, const void *), (*glEnableVertexAttribArray_)(GLuint);
static void (*glDrawArrays_)(GLenum, GLint, GLsizei), (*glViewport_)(GLint, GLint, GLsizei, GLsizei);
static void (*glColorMask_)(GLboolean, GLboolean, GLboolean, GLboolean), (*glGetBooleanv_)(GLenum, GLboolean *);

static int (*ahbAllocate)(const AHardwareBuffer_Desc *, AHardwareBuffer **);
static void (*ahbRelease)(AHardwareBuffer *);
static void (*ahbDescribe)(const AHardwareBuffer *, AHardwareBuffer_Desc *);
static int (*ahbSend)(const AHardwareBuffer *, int);
static EGLClientBuffer (*nativeBuffer)(const AHardwareBuffer *);
static PFNEGLCREATEIMAGEKHRPROC createImage;
static PFNEGLDESTROYIMAGEKHRPROC destroyImage;
static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC imageTexture;
static BlitFn blit;
static void (*glGenTextures_)(GLsizei, GLuint *), (*glDeleteTextures_)(GLsizei, const GLuint *), (*glBindTexture_)(GLenum, GLuint);
static void (*glGenFramebuffers_)(GLsizei, GLuint *), (*glDeleteFramebuffers_)(GLsizei, const GLuint *), (*glBindFramebuffer_)(GLenum, GLuint);
static void (*glFramebufferTexture2D_)(GLenum, GLenum, GLenum, GLuint, GLint), (*glGetIntegerv_)(GLenum, GLint *);
static GLenum (*glCheckFramebufferStatus_)(GLenum);
static GLboolean (*glIsEnabled_)(GLenum);
static void (*glEnable_)(GLenum), (*glDisable_)(GLenum), (*glFinish_)(void);
static const GLubyte *(*glGetString_)(GLenum);

static __eglMustCastToProperFunctionPointerType (*getProc)(const char *);
static EGLContext (*currentContext)(void);
static EGLDisplay (*currentDisplay)(void);
static EGLSurface (*currentSurface)(EGLint);
static EGLBoolean (*querySurface)(EGLDisplay, EGLSurface, EGLint, EGLint *);
static void note(const char *what) { fprintf(stderr, "TTR present: %s\n", what); }
static uint32_t newId(void) { return idBase | (idNext++ & idMask); }
static void put16(uint8_t *b, uint16_t v) { memcpy(b, &v, 2); }
static void put32(uint8_t *b, uint32_t v) { memcpy(b, &v, 4); }
static void put64(uint8_t *b, uint64_t v) { memcpy(b, &v, 8); }
static uint16_t get16(const uint8_t *b) { uint16_t v; memcpy(&v, b, 2); return v; }
static uint32_t get32(const uint8_t *b) { uint32_t v; memcpy(&v, b, 4); return v; }

static int writeAll(const void *data, size_t size) {
    const uint8_t *p = data;
    while (size) { ssize_t n = write(xfd, p, size); if (n < 0 && errno == EINTR) continue; if (n <= 0) return -1; p += n; size -= n; }
    return 0;
}
static int readAll(void *data, size_t size) {
    uint8_t *p = data;
    while (size) { ssize_t n = read(xfd, p, size); if (n < 0 && errno == EINTR) continue; if (n <= 0) return -1; p += n; size -= n; }
    return 0;
}
/** Sends one request (optionally carrying a file descriptor); returns its sequence number. */
static int request(const uint8_t *data, size_t size, int fd) {
    if (fd < 0) { if (writeAll(data, size)) return -1; }
    else {
        struct iovec io = {(void *) data, size};
        char control[CMSG_SPACE(sizeof(int))]; memset(control, 0, sizeof control);
        struct msghdr msg = {0}; msg.msg_iov = &io; msg.msg_iovlen = 1; msg.msg_control = control; msg.msg_controllen = sizeof control;
        struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(c), &fd, sizeof(int));
        ssize_t n; do n = sendmsg(xfd, &msg, MSG_NOSIGNAL); while (n < 0 && errno == EINTR);
        if (n != (ssize_t) size) return -1;
    }
    return ++seq;
}
static void idle(uint32_t pixmap) { for (int i = 0; i < BUFFERS; i++) if (buffers[i].pixmap == pixmap) buffers[i].busy = 0; }
/** Reads one packet; fills reply (32 bytes + extra up to max) and returns its type, or -1. */
static int packet(uint8_t *out, size_t max) {
    uint8_t head[32]; if (readAll(head, 32)) return -1;
    size_t extra = (head[0] == 1 || (head[0] & 127) == 35) ? (size_t) get32(head + 4) * 4 : 0;
    if (extra > (1 << 20)) return -1;
    uint8_t *rest = extra ? malloc(extra) : NULL; if (extra && (!rest || readAll(rest, extra))) { free(rest); return -1; }
    if ((head[0] & 127) == 35 && head[1] == presentOp && get16(head + 8) == 2) idle(get32(head + 24)); /* PresentIdleNotify */
    if (out) { memcpy(out, head, 32); if (extra && max > 32) memcpy(out + 32, rest, extra < max - 32 ? extra : max - 32); }
    free(rest); return head[0];
}
/** Waits for the reply (or error) to request number s; events are handled along the way. */
static int reply(int s, uint8_t *out, size_t max) {
    if (s < 0) return -1;
    for (int i = 0; i < 4096; i++) {
        uint8_t r[64]; int type = packet(r, sizeof r); if (type < 0) return -1;
        if ((type == 0 || type == 1) && get16(r + 2) == (uint16_t) s) { if (out) memcpy(out, r, max < sizeof r ? max : sizeof r); return type == 1 ? 0 : -1; }
    }
    return -1;
}
static int sync_(void) { uint8_t q[4] = {43, 0}; put16(q + 2, 1); return reply(request(q, 4, -1), NULL, 0); } /* GetInputFocus */

static int connectX(void) {
    const char *display = getenv("DISPLAY"), *auth = getenv("XAUTHORITY");
    if (!display || display[0] != '/' || !auth) return -1;
    struct sockaddr_un addr = {0}; addr.sun_family = AF_UNIX;
    if (strlen(display) >= sizeof addr.sun_path) return -1;
    strcpy(addr.sun_path, display);
    xfd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (xfd < 0 || connect(xfd, (struct sockaddr *) &addr, sizeof addr)) return -1;
    uint8_t file[1024]; FILE *f = fopen(auth, "rb"); if (!f) return -1;
    size_t n = fread(file, 1, sizeof file, f); fclose(f);
    size_t at = 2; uint16_t len[4];
    for (int i = 0; i < 4; i++) { if (at + 2 > n) return -1; len[i] = (file[at] << 8) | file[at + 1]; at += 2; if (i < 3) at += len[i]; }
    /* Fields: address, number, name, data; name and data are the last two. */
    size_t nameAt = 2 + 2 + len[0] + 2 + len[1] + 2, dataAt = nameAt + len[2] + 2;
    if (dataAt + len[3] > n || len[2] > 64 || len[3] > 64) return -1;
    uint8_t setup[12 + 64 + 64] = {0};
    setup[0] = 'l'; put16(setup + 2, 11); put16(setup + 6, len[2]); put16(setup + 8, len[3]);
    memcpy(setup + 12, file + nameAt, len[2]); memcpy(setup + 12 + ((len[2] + 3) & ~3), file + dataAt, len[3]);
    memset(file, 0, sizeof file);
    if (writeAll(setup, 12 + ((len[2] + 3) & ~3) + ((len[3] + 3) & ~3))) return -1;
    memset(setup, 0, sizeof setup);
    uint8_t head[8]; if (readAll(head, 8) || head[0] != 1) return -1;
    size_t size = (size_t) get16(head + 6) * 4; uint8_t *data = malloc(size); if (!data || readAll(data, size)) { free(data); return -1; }
    idBase = get32(data + 4); idMask = get32(data + 8); free(data);
    return 0;
}
static int extension(const char *name, uint8_t *opcode) {
    uint8_t q[32] = {98}; size_t n = strlen(name); put16(q + 2, 2 + (n + 3) / 4); put16(q + 4, n); memcpy(q + 8, name, n);
    uint8_t r[32]; if (reply(request(q, 8 + ((n + 3) & ~3), -1), r, sizeof r) || !r[8]) return -1;
    *opcode = r[9]; return 0;
}
static void *sym(void *lib, const char *name) { void *p = lib ? dlsym(lib, name) : NULL; if (!p && getProc) p = (void *) getProc(name); return p; }

static int setup(void) {
    const char *env = getenv("TTR_PRESENT"); if (env && env[0] == '0') return -1;
    void *android = dlopen("libandroid.so", RTLD_NOW), *gles = dlopen("libGLESv2.so", RTLD_NOW), *egl = dlopen("libEGL.so", RTLD_NOW);
    if (!egl) return -1;
    getProc = dlsym(egl, "eglGetProcAddress"); currentContext = dlsym(egl, "eglGetCurrentContext"); currentDisplay = dlsym(egl, "eglGetCurrentDisplay");
    currentSurface = dlsym(egl, "eglGetCurrentSurface"); querySurface = dlsym(egl, "eglQuerySurface");
    if (!getProc || !currentContext || !currentDisplay) return -1;
    ahbAllocate = sym(android, "AHardwareBuffer_allocate"); ahbRelease = sym(android, "AHardwareBuffer_release");
    ahbDescribe = sym(android, "AHardwareBuffer_describe"); ahbSend = sym(android, "AHardwareBuffer_sendHandleToUnixSocket");
    nativeBuffer = (void *) getProc("eglGetNativeClientBufferANDROID");
    createImage = (void *) getProc("eglCreateImageKHR"); destroyImage = (void *) getProc("eglDestroyImageKHR");
    imageTexture = (void *) getProc("glEGLImageTargetTexture2DOES");
    blit = sym(gles, "glBlitFramebuffer");
    glGenTextures_ = sym(gles, "glGenTextures"); glDeleteTextures_ = sym(gles, "glDeleteTextures"); glBindTexture_ = sym(gles, "glBindTexture");
    glGenFramebuffers_ = sym(gles, "glGenFramebuffers"); glDeleteFramebuffers_ = sym(gles, "glDeleteFramebuffers"); glBindFramebuffer_ = sym(gles, "glBindFramebuffer");
    glFramebufferTexture2D_ = sym(gles, "glFramebufferTexture2D"); glGetIntegerv_ = sym(gles, "glGetIntegerv");
    glCheckFramebufferStatus_ = sym(gles, "glCheckFramebufferStatus"); glIsEnabled_ = sym(gles, "glIsEnabled");
    glEnable_ = sym(gles, "glEnable"); glDisable_ = sym(gles, "glDisable"); glFinish_ = sym(gles, "glFinish"); glGetString_ = sym(gles, "glGetString"); glReadPixels_ = sym(gles, "glReadPixels");
    glCopyTexSubImage2D_ = sym(gles, "glCopyTexSubImage2D"); glTexImage2D_ = sym(gles, "glTexImage2D"); glTexParameteri_ = sym(gles, "glTexParameteri");
    glActiveTexture_ = sym(gles, "glActiveTexture"); glCreateShader_ = sym(gles, "glCreateShader"); glCreateProgram_ = sym(gles, "glCreateProgram");
    glShaderSource_ = sym(gles, "glShaderSource"); glCompileShader_ = sym(gles, "glCompileShader"); glAttachShader_ = sym(gles, "glAttachShader");
    glBindAttribLocation_ = sym(gles, "glBindAttribLocation"); glLinkProgram_ = sym(gles, "glLinkProgram"); glGetProgramiv_ = sym(gles, "glGetProgramiv");
    glUniform1i_ = sym(gles, "glUniform1i"); glUseProgram_ = sym(gles, "glUseProgram"); glGetUniformLocation_ = sym(gles, "glGetUniformLocation");
    glGenBuffers_ = sym(gles, "glGenBuffers"); glBindBuffer_ = sym(gles, "glBindBuffer"); glBufferData_ = sym(gles, "glBufferData");
    glGenVertexArrays_ = sym(gles, "glGenVertexArrays"); glBindVertexArray_ = sym(gles, "glBindVertexArray"); glBindSampler_ = sym(gles, "glBindSampler");
    glVertexAttribPointer_ = sym(gles, "glVertexAttribPointer"); glEnableVertexAttribArray_ = sym(gles, "glEnableVertexAttribArray");
    glDrawArrays_ = sym(gles, "glDrawArrays"); glViewport_ = sym(gles, "glViewport"); glColorMask_ = sym(gles, "glColorMask"); glGetBooleanv_ = sym(gles, "glGetBooleanv");
    { const char *m = getenv("TTR_PRESENT_METHOD"); if (m && !strcmp(m, "draw")) { method = 2; verified = 1; bufferFormat = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM; } }
    if (!ahbAllocate || !ahbRelease || !ahbDescribe || !ahbSend || !nativeBuffer || !createImage || !destroyImage || !imageTexture || !blit
        || !glGenTextures_ || !glGenFramebuffers_ || !glFramebufferTexture2D_ || !glGetIntegerv_ || !glCheckFramebufferStatus_ || !glIsEnabled_ || !glFinish_ || !glGetString_) {
        note("graphics functions missing; using the standard copy"); return -1;
    }
    const char *version = (const char *) glGetString_(GL_VERSION);
    if (!version || !strstr(version, "OpenGL ES 3")) { note("GLES 3 context unavailable; using the standard copy"); return -1; }
    const char *format = getenv("TTR_PRESENT_FORMAT");
    if (format && !strcmp(format, "rgba")) bufferFormat = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    if (connectX() || extension("DRI3", &dri3Op) || extension("Present", &presentOp)) { note("X server lacks DRI3/Present; using the standard copy"); return -1; }
    uint8_t q[12]; q[0] = dri3Op; q[1] = 0; put16(q + 2, 3); put32(q + 4, 1); put32(q + 8, 2);
    if (reply(request(q, 12, -1), NULL, 0)) return -1;
    q[0] = presentOp; if (reply(request(q, 12, -1), NULL, 0)) return -1;
    return 0;
}

static void release(int keepX) {
    for (int i = 0; i < BUFFERS; i++) {
        Buffer *b = &buffers[i];
        if (b->pixmap && keepX) { uint8_t q[8] = {54}; put16(q + 2, 2); put32(q + 4, b->pixmap); request(q, 8, -1); }
        if (context == currentContext()) { if (b->fbo) glDeleteFramebuffers_(1, &b->fbo); if (b->texture) glDeleteTextures_(1, &b->texture); }
        if (b->image) destroyImage(currentDisplay(), b->image);
        if (b->ahb) ahbRelease(b->ahb);
        memset(b, 0, sizeof *b);
    }
}
/** Window size from the X server (the window may be smaller than the pbuffer). */
static int geometry(uint32_t target, int *w, int *h) {
    uint8_t q[8] = {14}; put16(q + 2, 2); put32(q + 4, target); uint8_t r[32];
    if (reply(request(q, 8, -1), r, sizeof r)) return -1;
    *w = get16(r + 16); *h = get16(r + 18); return 0;
}
static int allocate(uint32_t target, int w, int h) {
    EGLDisplay display = currentDisplay(); GLint texture = 0, framebuffer = 0;
    glGetIntegerv_(GL_TEXTURE_BINDING_2D, &texture); glGetIntegerv_(GL_FRAMEBUFFER_BINDING, &framebuffer);
    int ok = 0;
    for (int i = 0; i < BUFFERS; i++) {
        Buffer *b = &buffers[i];
        AHardwareBuffer_Desc d = {0}; d.width = w; d.height = h; d.layers = 1; d.format = bufferFormat;
        d.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
        if (ahbAllocate(&d, &b->ahb)) goto done;
        EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
        b->image = createImage(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, nativeBuffer(b->ahb), attributes);
        if (b->image == EGL_NO_IMAGE_KHR) { b->image = 0; goto done; }
        glGenTextures_(1, &b->texture); glBindTexture_(GL_TEXTURE_2D, b->texture); imageTexture(GL_TEXTURE_2D, b->image);
        glGenFramebuffers_(1, &b->fbo); glBindFramebuffer_(GL_FRAMEBUFFER, b->fbo);
        glFramebufferTexture2D_(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, b->texture, 0);
        if (glCheckFramebufferStatus_(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) goto done;
        /* Hand the buffer to the X server: DRI3 PixmapFromBuffers with a socket the buffer is sent through. */
        int pair[2]; if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, pair)) goto done;
        ahbDescribe(b->ahb, &d);
        b->pixmap = newId();
        uint8_t q[64] = {0}; q[0] = dri3Op; q[1] = 7; put16(q + 2, 16); put32(q + 4, b->pixmap); put32(q + 8, target); q[12] = 1;
        put16(q + 16, w); put16(q + 18, h); put32(q + 20, d.stride * 4); q[52] = 24; q[53] = 32; put64(q + 56, AHB_SOCKET_MODIFIER);
        int sent = request(q, 64, pair[1]) > 0 && ahbSend(b->ahb, pair[0]) == 0;
        close(pair[1]);
        int pw = 0, ph = 0; /* a query on the new pixmap confirms the server accepted the buffer */
        sent = sent && geometry(b->pixmap, &pw, &ph) == 0 && pw == w && ph == h;
        close(pair[0]);
        if (!sent) { b->pixmap = 0; goto done; }
    }
    { /* PresentSelectInput (no reply): report when the X server is done with each buffer. */
      eventId = newId();
      uint8_t q[16] = {0}; q[0] = presentOp; q[1] = 3; put16(q + 2, 4); put32(q + 4, eventId); put32(q + 8, target); put32(q + 12, 4);
      ok = request(q, 16, -1) > 0 && sync_() == 0; }
done:
    glBindTexture_(GL_TEXTURE_2D, texture); glBindFramebuffer_(GL_FRAMEBUFFER, framebuffer);
    return ok ? 0 : -1;
}
static Buffer *freeBuffer(void) {
    for (int attempt = 0; attempt < 8; attempt++) {
        Buffer *best = NULL;
        for (int i = 0; i < BUFFERS; i++) if (!buffers[i].busy && (!best || buffers[i].used < best->used)) best = &buffers[i];
        if (best) return best;
        struct pollfd p = {xfd, POLLIN, 0};
        if (poll(&p, 1, 25) > 0 && packet(NULL, 0) < 0) return NULL;
    }
    /* The X server never reported the buffers idle; reuse the oldest rather than stall. */
    Buffer *oldest = &buffers[0]; for (int i = 1; i < BUFFERS; i++) if (buffers[i].used < oldest->used) oldest = &buffers[i];
    return oldest;
}

/** Method 2: copy the frame into a texture, then draw it flipped with red and blue swapped. GL state is restored. */
static int drawCopy(Buffer *b, int w, int h) {
    if (!glCopyTexSubImage2D_ || !glTexImage2D_ || !glCreateShader_ || !glGenVertexArrays_ || !glBindVertexArray_ || !glDrawArrays_ || !glColorMask_ || !glGetBooleanv_) return -1;
    GLint saved[9]; GLboolean mask[4]; GLint viewport[4];
    const GLenum caps[] = {GL_BLEND, GL_DEPTH_TEST, GL_CULL_FACE, GL_SCISSOR_TEST, GL_STENCIL_TEST, GL_DITHER, GL_POLYGON_OFFSET_FILL, 0x8C89 /* RASTERIZER_DISCARD */, GL_SAMPLE_ALPHA_TO_COVERAGE, GL_SAMPLE_COVERAGE};
    GLboolean enabled[10];
    glGetIntegerv_(0x8B8D, &saved[0]); /* CURRENT_PROGRAM */ glGetIntegerv_(GL_ARRAY_BUFFER_BINDING, &saved[1]);
    glGetIntegerv_(0x85B5, &saved[2]); /* VERTEX_ARRAY_BINDING */ glGetIntegerv_(GL_ACTIVE_TEXTURE, &saved[3]);
    glGetIntegerv_(GL_READ_FRAMEBUFFER_BINDING_, &saved[4]); glGetIntegerv_(GL_FRAMEBUFFER_BINDING, &saved[5]);
    glGetIntegerv_(0x88EF, &saved[6]); /* PIXEL_UNPACK_BUFFER_BINDING */
    glGetIntegerv_(GL_VIEWPORT, viewport); glGetBooleanv_(GL_COLOR_WRITEMASK, mask);
    for (int i = 0; i < 10; i++) enabled[i] = glIsEnabled_(caps[i]);
    glActiveTexture_(GL_TEXTURE0); glGetIntegerv_(GL_TEXTURE_BINDING_2D, &saved[7]); saved[8] = 0;
    if (glBindSampler_) glGetIntegerv_(0x8919, &saved[8]); /* SAMPLER_BINDING */
    for (int i = 0; i < 10; i++) if (enabled[i]) glDisable_(caps[i]);
    if (glBindSampler_) glBindSampler_(0, 0);
    if (saved[6]) glBindBuffer_(0x88EC, 0); /* PIXEL_UNPACK_BUFFER */
    int ok = 1;
    if (drawContext != currentContext()) { copyTexture = program = vertexBuffer = vertexArray = 0; copyWidth = copyHeight = 0; drawContext = currentContext(); }
    if (!program) {
        static const char *vs = "attribute vec2 p; varying vec2 uv; void main() { uv = vec2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5); gl_Position = vec4(p, 0.0, 1.0); }";
        static const char *fs = "precision mediump float; varying vec2 uv; uniform sampler2D t; void main() { gl_FragColor = texture2D(t, uv).bgra; }";
        GLuint v = glCreateShader_(GL_VERTEX_SHADER), f = glCreateShader_(GL_FRAGMENT_SHADER);
        glShaderSource_(v, 1, &vs, NULL); glCompileShader_(v); glShaderSource_(f, 1, &fs, NULL); glCompileShader_(f);
        program = glCreateProgram_(); glAttachShader_(program, v); glAttachShader_(program, f); glBindAttribLocation_(program, 0, "p"); glLinkProgram_(program);
        GLint linked = 0; glGetProgramiv_(program, GL_LINK_STATUS, &linked);
        if (!linked) { program = 0; ok = 0; } else { glUseProgram_(program); glUniform1i_(glGetUniformLocation_(program, "t"), 0); }
        static const GLfloat quad[] = {-1, -1, 1, -1, -1, 1, 1, 1};
        glGenVertexArrays_(1, &vertexArray); glBindVertexArray_(vertexArray);
        glGenBuffers_(1, &vertexBuffer); glBindBuffer_(GL_ARRAY_BUFFER, vertexBuffer); glBufferData_(GL_ARRAY_BUFFER, sizeof quad, quad, GL_STATIC_DRAW);
        glVertexAttribPointer_(0, 2, GL_FLOAT, GL_FALSE, 0, 0); glEnableVertexAttribArray_(0);
    }
    if (ok && (!copyTexture || copyWidth != w || copyHeight != h)) {
        if (copyTexture) glDeleteTextures_(1, &copyTexture);
        glGenTextures_(1, &copyTexture); glBindTexture_(GL_TEXTURE_2D, copyTexture);
        glTexImage2D_(GL_TEXTURE_2D, 0, GL_RGB, w, h, 0, GL_RGB, GL_UNSIGNED_BYTE, NULL);
        glTexParameteri_(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri_(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri_(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri_(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        copyWidth = w; copyHeight = h;
    }
    if (ok) {
        glBindFramebuffer_(GL_FRAMEBUFFER, 0); glBindTexture_(GL_TEXTURE_2D, copyTexture);
        glCopyTexSubImage2D_(GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        glBindFramebuffer_(GL_FRAMEBUFFER, b->fbo); glViewport_(0, 0, w, h); glColorMask_(1, 1, 1, 1);
        glUseProgram_(program); glBindVertexArray_(vertexArray); glDrawArrays_(GL_TRIANGLE_STRIP, 0, 4);
    }
    /* Restore everything that was changed. */
    glBindVertexArray_(saved[2]); glBindBuffer_(GL_ARRAY_BUFFER, saved[1]); glUseProgram_(saved[0]);
    if (saved[6]) glBindBuffer_(0x88EC, saved[6]);
    glBindTexture_(GL_TEXTURE_2D, saved[7]); if (glBindSampler_) glBindSampler_(0, saved[8]); glActiveTexture_(saved[3]);
    glViewport_(viewport[0], viewport[1], viewport[2], viewport[3]); glColorMask_(mask[0], mask[1], mask[2], mask[3]);
    for (int i = 0; i < 10; i++) if (enabled[i]) glEnable_(caps[i]);
    glBindFramebuffer_(GL_READ_FRAMEBUFFER_, saved[4]); glBindFramebuffer_(GL_DRAW_FRAMEBUFFER_, saved[5]);
    return ok ? 0 : -1;
}

/** Shows the current pbuffer in X window `target`. Returns 1 when presented, 0 to use GL4ES's copy. */
int ttr_present(unsigned long target, int surfaceWidth, int surfaceHeight) {
    pthread_mutex_lock(&lock);
    int done = 0;
    if (state == 0) { state = setup() == 0 ? 1 : -1; if (state < 0 && xfd >= 0) { close(xfd); xfd = -1; } }
    if (state < 0) goto out;
    /* Drain pending events (idle notifications) without blocking. */
    for (struct pollfd p = {xfd, POLLIN, 0}; poll(&p, 1, 0) > 0;) if (packet(NULL, 0) < 0) { state = -1; goto out; }
    int w, h, windowW = 0, windowH = 0;
    if (geometry((uint32_t) target, &w, &h)) goto out; /* window gone: let GL4ES handle it */
    windowW = w; windowH = h;
    if (currentSurface && querySurface) { /* the surface actually drawn to (GL4ES may have made a new one after a resize) */
        EGLint sw = 0, sh = 0; EGLSurface surface = currentSurface(EGL_DRAW);
        if (querySurface(currentDisplay(), surface, EGL_WIDTH, &sw) && querySurface(currentDisplay(), surface, EGL_HEIGHT, &sh) && sw > 0 && sh > 0) { surfaceWidth = sw; surfaceHeight = sh; }
    }
    if (w > surfaceWidth) w = surfaceWidth;
    if (h > surfaceHeight) h = surfaceHeight;
    if (w < 1 || h < 1) goto out;
    if ((uint32_t) target != window || w != width || h != height || currentContext() != context || !buffers[0].pixmap) {
        release(1);
        window = (uint32_t) target; width = w; height = h; context = currentContext();
        if (allocate(window, w, h)) { release(1); note("buffer setup failed; using the standard copy"); state = -1; goto out; }
        char line[160]; snprintf(line, sizeof line, "GPU presentation active at %dx%d (window %dx%d, surface %dx%d)", w, h, windowW, windowH, surfaceWidth, surfaceHeight); note(line);
    }
    Buffer *b = freeBuffer(); if (!b) { state = -1; goto out; }
    if (method == 2) { if (drawCopy(b, w, h)) { note("drawing copy failed; using the standard copy"); release(1); state = -1; goto out; } }
    else {
        GLint read = 0, draw = 0; glGetIntegerv_(GL_READ_FRAMEBUFFER_BINDING_, &read); glGetIntegerv_(GL_FRAMEBUFFER_BINDING, &draw);
        GLboolean scissor = glIsEnabled_(GL_SCISSOR_TEST); if (scissor) glDisable_(GL_SCISSOR_TEST);
        glBindFramebuffer_(GL_READ_FRAMEBUFFER_, 0); glBindFramebuffer_(GL_DRAW_FRAMEBUFFER_, b->fbo);
        /* GL rows start at the bottom; X rows at the top. The window's top-left is (0, h) in the pbuffer. */
        blit(0, 0, w, h, 0, h, w, 0, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer_(GL_READ_FRAMEBUFFER_, read); glBindFramebuffer_(GL_DRAW_FRAMEBUFFER_, draw);
        if (scissor) glEnable_(GL_SCISSOR_TEST);
    }
    if (glReadPixels_ && ((!verified && frames % 10 == 0) || (ttr_gl_log_enabled() && frames % 300 == 0))) {
        /* Compare one pixel of the game frame with the same pixel of the (flipped) shared buffer. */
        GLint read = 0, draw = 0; glGetIntegerv_(GL_READ_FRAMEBUFFER_BINDING_, &read); glGetIntegerv_(GL_FRAMEBUFFER_BINDING, &draw);
        uint8_t source[4] = {0}, copy[4] = {0};
        glBindFramebuffer_(GL_FRAMEBUFFER, 0); glReadPixels_(w / 2, h / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, source);
        glBindFramebuffer_(GL_FRAMEBUFFER, b->fbo); glReadPixels_(w / 2, h - 1 - h / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, copy);
        glBindFramebuffer_(GL_READ_FRAMEBUFFER_, read); glBindFramebuffer_(GL_DRAW_FRAMEBUFFER_, draw);
        ttr_gl_log("present frame %llu method %d: game %02x%02x%02x buffer %02x%02x%02x %s", frames, method, source[0], source[1], source[2],
            copy[0], copy[1], copy[2], (const char *) glGetString_(GL_RENDERER));
        if (!verified && (source[0] | source[1] | source[2])) {
            if (copy[0] | copy[1] | copy[2]) { verified = 1; note(method == 1 ? "GPU blit verified" : "GPU drawing copy verified"); }
            else if (method == 1) {
                /* This GPU ignored the blit: draw instead (RGBA buffers) from the next frame. */
                note("GPU blit left the buffer empty; switching to the drawing copy");
                method = 2; bufferFormat = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM; release(1); width = 0; goto out;
            } else { note("drawing copy left the buffer empty; using the standard copy"); release(1); state = -1; goto out; }
        }
    }
    glFinish_(); /* the X server samples the buffer from another process */
    uint8_t q[72] = {0}; q[0] = presentOp; q[1] = 1; put16(q + 2, 18); put32(q + 4, window); put32(q + 8, b->pixmap);
    put32(q + 12, ++presentSerial); put32(q + 40, 3); /* PresentOptionAsync | PresentOptionCopy */
    if (request(q, 72, -1) < 0) { state = -1; goto out; }
    b->busy = 1; b->used = ++frames; done = 1;
out:
    pthread_mutex_unlock(&lock);
    return done;
}

/** Pbuffers for windows are made at least this large, so a window can grow (rotation) without a new surface. */
int ttr_surface_size(int value, const char *name) {
    const char *env = getenv(name); int minimum = env ? atoi(env) : 0;
    return minimum > value && minimum <= 8192 ? minimum : value;
}

/* Developer diagnostics: TTR_GL_LOG=<file> records texture uploads and driver errors (see texture.c patch). */
#include <stdarg.h>
int ttr_gl_log_enabled(void) {
    static int state = -1;
    if (state < 0) { const char *p = getenv("TTR_GL_LOG"); state = p && *p ? 1 : 0; }
    return state;
}
void ttr_gl_log(const char *format, ...) {
    static FILE *out; static int lines;
    if (!ttr_gl_log_enabled() || lines > 20000) return;
    if (!out) { out = fopen(getenv("TTR_GL_LOG"), "a"); if (!out) return; }
    va_list args; va_start(args, format); vfprintf(out, format, args); va_end(args);
    fputc('\n', out); fflush(out); lines++;
}
#include <fcntl.h>
/* With TTR_GL_LOG set, GL4ES's own messages (shader errors etc.) go to the same file. */
__attribute__((constructor)) static void ttr_gl_log_console(void) {
    const char *path = getenv("TTR_GL_LOG");
    if (!path || !*path) return;
    int fd = open(path, O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0600);
    if (fd >= 0) { dup2(fd, 1); dup2(fd, 2); close(fd); }
}
