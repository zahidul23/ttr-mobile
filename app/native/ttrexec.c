/*
 * Direct-Wine proof of concept: Android forbids apps from execve()-ing files in their own data
 * directory, but the system linker may load them ("linker64 /path/prog args"). Wine starts
 * wineboot, services and the game as child processes; this preload library rewrites those
 * launches. Programs outside the app (e.g. /system/bin/sh) are executed unchanged.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <spawn.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define LINKER "/system/bin/linker64"
extern char **environ;

/*
 * The first Wine process launched by the app starts a new session, so Wine and everything it starts
 * live in their own process group. A signal Wine sends to its group (console Ctrl events) can then
 * never reach the app; on Android 17 one did, and the app was interrupted (SIGINT) at startup.
 */
__attribute__((constructor)) static void own_session(void) {
    if (getenv("TTR_NEW_SESSION")) { unsetenv("TTR_NEW_SESSION"); setsid(); }
}
static int app_elf(const char *path) {
    if (!path || strncmp(path, "/data/", 6) != 0) return 0;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    unsigned char magic[4] = {0};
    int ok = read(fd, magic, 4) == 4 && magic[0] == 0x7f && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
    close(fd);
    return ok;
}

/* Resolve a bare program name against PATH, as execvp/posix_spawnp would. */
static const char *search(const char *file, char *buffer, size_t size) {
    if (!file || strchr(file, '/')) return file;
    const char *path = getenv("PATH");
    if (!path) return file;
    while (*path) {
        const char *end = strchr(path, ':'); size_t n = end ? (size_t)(end - path) : strlen(path);
        if (n + strlen(file) + 2 <= size) {
            memcpy(buffer, path, n); buffer[n] = '/'; strcpy(buffer + n + 1, file);
            if (access(buffer, X_OK) == 0 || app_elf(buffer)) return buffer;
        }
        if (!end) break; path = end + 1;
    }
    return file;
}

/* Wine's preloader is a static executable the linker cannot load. It only reserves address
 * space before running the loader named in argv[1]; with WINELOADERNOEXEC=1 that loader copes
 * without it, so run argv[1] (and the remaining arguments) through the linker instead. */
static int preloader(const char *path) {
    size_t n = strlen(path);
    return n >= 15 && strcmp(path + n - 15, "/wine-preloader") == 0;
}

static char **through_linker(const char *path, char *const argv[]) {
    size_t n = 0; while (argv && argv[n]) n++;
    char **out = calloc(n + 3, sizeof(char *));
    if (!out) return NULL;
    out[0] = (char *)LINKER;
    if (preloader(path) && n >= 2) { for (size_t i = 1; i < n; i++) out[i] = argv[i]; return out; }
    out[1] = (char *)path;
    for (size_t i = 1; i < n; i++) out[i + 1] = argv[i];
    return out;
}

typedef int (*execve_fn)(const char *, char *const[], char *const[]);
typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *, const posix_spawnattr_t *, char *const[], char *const[]);

int execve(const char *path, char *const argv[], char *const envp[]) {
    static execve_fn real; if (!real) real = (execve_fn)dlsym(RTLD_NEXT, "execve");
    if (app_elf(path)) { char **args = through_linker(path, argv); if (!args) { errno = ENOMEM; return -1; } return real(LINKER, args, envp); }
    return real(path, argv, envp);
}
int execv(const char *path, char *const argv[]) { return execve(path, argv, environ); }
int execvpe(const char *file, char *const argv[], char *const envp[]) {
    char buffer[4096]; const char *path = search(file, buffer, sizeof buffer);
    if (strchr(path, '/')) return execve(path, argv, envp);
    static int (*real)(const char *, char *const[], char *const[]);
    if (!real) real = dlsym(RTLD_NEXT, "execvpe");
    return real(file, argv, envp);
}
int execvp(const char *file, char *const argv[]) { return execvpe(file, argv, environ); }

static int spawn(const char *symbol, pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                 const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    spawn_fn real = (spawn_fn)dlsym(RTLD_NEXT, symbol);
    if (app_elf(path)) {
        char **args = through_linker(path, argv); if (!args) return ENOMEM;
        spawn_fn direct = (spawn_fn)dlsym(RTLD_NEXT, "posix_spawn");
        int result = direct(pid, LINKER, actions, attr, args, envp); free(args); return result;
    }
    return real(pid, path, actions, attr, argv, envp);
}
int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    return spawn("posix_spawn", pid, path, actions, attr, argv, envp);
}
int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *actions,
                 const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    char buffer[4096]; const char *path = search(file, buffer, sizeof buffer);
    return spawn(strchr(path, '/') ? "posix_spawn" : "posix_spawnp", pid, path, actions, attr, argv, envp);
}

/* Programs started as "linker64 /path/prog" see /proc/self/exe as the linker. Wine's server
 * locates its data files (NLS tables) from that link, so report the real program instead. */
typedef ssize_t (*readlinkat_fn)(int, const char *, char *, size_t);
static int self_exe(const char *path) {
    return path && (strcmp(path, "/proc/self/exe") == 0 || (strncmp(path, "/proc/", 6) == 0 && strstr(path, "/exe") && atoi(path + 6) == getpid()));
}
static ssize_t real_program(char *buffer, size_t size) {
    char args[4096]; int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n = read(fd, args, sizeof args - 1); close(fd);
    if (n <= 0) return -1;
    args[n] = 0;
    size_t first = strlen(args);
    if (first >= (size_t)n || strcmp(args + (first > 9 ? first - 9 : 0), "/linker64") != 0) return -1;
    const char *program = args + first + 1;
    if (program[0] != '/') return -1;
    size_t length = strlen(program); if (length > size) length = size;
    memcpy(buffer, program, length); return (ssize_t)length;
}
ssize_t readlinkat(int dirfd, const char *path, char *buffer, size_t size) {
    static readlinkat_fn real; if (!real) real = (readlinkat_fn)dlsym(RTLD_NEXT, "readlinkat");
    if (self_exe(path)) { ssize_t n = real_program(buffer, size); if (n > 0) return n; }
    return real(dirfd, path, buffer, size);
}
ssize_t readlink(const char *path, char *buffer, size_t size) { return readlinkat(AT_FDCWD, path, buffer, size); }
/* Android's realpath() resolves through an O_PATH descriptor, bypassing readlink. */
typedef char *(*realpath_fn)(const char *, char *);
char *realpath(const char *path, char *resolved) {
    static realpath_fn real; if (!real) real = (realpath_fn)dlsym(RTLD_NEXT, "realpath");
    if (self_exe(path)) {
        char program[4096]; ssize_t n = real_program(program, sizeof program - 1);
        if (n > 0) { program[n] = 0; return real(program, resolved); }
    }
    return real(path, resolved);
}

/* Android's getaddrinfo rejects AI_V4MAPPED/AI_ALL unless the family is AF_INET6 (EAI_BADFLAGS),
 * which Wine reports as "Invalid argument" even for literal addresses. glibc ignores them there. */
#include <netdb.h>
#include <sys/socket.h>
typedef int (*getaddrinfo_fn)(const char *, const char *, const struct addrinfo *, struct addrinfo **);
int getaddrinfo(const char *node, const char *service, const struct addrinfo *hints, struct addrinfo **result) {
    static getaddrinfo_fn real; if (!real) real = (getaddrinfo_fn)dlsym(RTLD_NEXT, "getaddrinfo");
    if (hints && hints->ai_family != AF_INET6 && (hints->ai_flags & (AI_V4MAPPED | AI_ALL))) {
        struct addrinfo copy = *hints; copy.ai_flags &= ~(AI_V4MAPPED | AI_ALL);
        return real(node, service, &copy, result);
    }
    return real(node, service, hints, result);
}

/*
 * "Signal everyone" never makes sense inside the app: kill(-1, sig) reaches every process of the app's user
 * (the app, its display and all of Wine), and kill(0 / -group, sig) a whole group. On an Android 17 Galaxy S23
 * such an interrupt arrived as the game started: first it closed the app, later it stopped wineserver (which
 * then killed all of Windows). Those broadcasts are dropped and logged; signals to one process pass through.
 */
#include <signal.h>
#include <stdio.h>
typedef int (*kill_fn)(pid_t, int);
#include <time.h>
/* Wall-clock time for log lines, matching the app log's clock. */
static const char *now(void) {
    static __thread char text[16]; struct timespec t; struct tm local;
    clock_gettime(CLOCK_REALTIME, &t); localtime_r(&t.tv_sec, &local);
    snprintf(text, sizeof text, "%02d:%02d:%02d.%03d", local.tm_hour, local.tm_min, local.tm_sec, (int) (t.tv_nsec / 1000000));
    return text;
}
/* Program name of a process (argv[0] is the linker; the program follows it). */
static void program_name(pid_t pid, char *name, size_t size) {
    char path[64]; snprintf(path, sizeof path, "/proc/%d/cmdline", (int) pid); strcpy(name, "?");
    FILE *f = fopen(path, "r");
    if (!f) { /* other users' command lines are hidden; the short name sometimes is not */
        snprintf(path, sizeof path, "/proc/%d/comm", (int) pid); f = fopen(path, "r"); if (!f) return;
        size_t n = fread(name, 1, size - 1, f); fclose(f); name[n] = 0; if (n && name[n - 1] == '\n') name[n - 1] = 0; return;
    }
    size_t n = fread(name, 1, size - 1, f); fclose(f); name[n] = 0;
    size_t first = strlen(name); if (first + 1 < n) memmove(name, name + first + 1, n - first - 1), name[n - first - 1] = 0;
    char *slash = strrchr(name, '/'), *back = strrchr(name, '\\'); if (back > slash) slash = back;
    if (slash) memmove(name, slash + 1, strlen(slash + 1) + 1);
}
static int broadcast(pid_t pid, int sig, const char *call) {
    char name[256]; program_name(getpid(), name, sizeof name);
    fprintf(stderr, "ttrexec %s: blocked %s(%d, %d) from pid %d (%s)\n", now(), call, (int) pid, sig, (int) getpid(), name);
    return 0;
}
int kill(pid_t pid, int sig) {
    static kill_fn real; if (!real) real = (kill_fn) dlsym(RTLD_NEXT, "kill");
    if (sig != 0 && (pid == -1 || pid == 0 || (pid < -1 && sig != SIGKILL && sig != SIGCONT))) return broadcast(pid, sig, "kill");
    return real(pid, sig);
}
int killpg(pid_t group, int sig) {
    static kill_fn real; if (!real) real = (kill_fn) dlsym(RTLD_NEXT, "killpg");
    if (sig != 0 && sig != SIGKILL && sig != SIGCONT) return broadcast(-group, sig, "killpg");
    return real(group, sig);
}

/*
 * Stop signals from outside the app: on an Android 17 Galaxy S23 a system process sent SIGINT, then SIGTERM, to the
 * game and wineserver as the game started (the storage daemon clearing the shared folder the game used to live in;
 * see TtrUpdater). wineserver takes SIGINT as "shut down". SIGINT/SIGTERM/SIGHUP sent by
 * another user are logged and dropped; the app's own signals still arrive.
 */
#include <sys/types.h>
typedef int (*sigaction_fn)(int, const struct sigaction *, struct sigaction *);
static struct sigaction original[65];
static int watched(int sig) { return sig == SIGINT || sig == SIGTERM || sig == SIGHUP; }
static void received(int sig, siginfo_t *info, void *context) {
    if (info && info->si_code <= 0 && info->si_uid != getuid()) {  /* from outside the app: log and drop */
        char me[256], from[256]; program_name(getpid(), me, sizeof me); program_name(info->si_pid, from, sizeof from);
        char line[700]; int n = snprintf(line, sizeof line, "ttrexec %s: pid %d (%s) ignored signal %d from pid %d (%s, user %d)\n",
            now(), (int) getpid(), me, sig, (int) info->si_pid, from, (int) info->si_uid);
        if (n > 0) write(2, line, (size_t) (n < (int) sizeof line ? n : (int) sizeof line - 1));
        return;
    }
    struct sigaction *o = &original[sig];  /* the app's own signals behave as before */
    if (o->sa_flags & SA_SIGINFO) { if (o->sa_sigaction) o->sa_sigaction(sig, info, context); }
    else if (o->sa_handler == SIG_DFL) { signal(sig, SIG_DFL); raise(sig); }
    else if (o->sa_handler != SIG_IGN && o->sa_handler) o->sa_handler(sig);
}
int sigaction(int sig, const struct sigaction *act, struct sigaction *old) {
    static sigaction_fn real; if (!real) real = (sigaction_fn) dlsym(RTLD_NEXT, "sigaction");
    if (!act || !watched(sig) || act->sa_handler == SIG_IGN || act->sa_handler == SIG_DFL) return real(sig, act, old);
    struct sigaction previous = original[sig], wrapped = *act;
    original[sig] = *act; wrapped.sa_flags |= SA_SIGINFO; wrapped.sa_sigaction = received;
    int result = real(sig, &wrapped, old);
    if (result != 0) original[sig] = previous;
    else if (old && old->sa_sigaction == received) *old = previous;
    return result;
}
/* Processes that never install a handler (most of Windows) get the same protection. */
__attribute__((constructor)) static void watch_signals(void) {
    int sigs[] = { SIGINT, SIGTERM, SIGHUP };
    for (int i = 0; i < 3; i++) {
        struct sigaction current; if (sigaction(sigs[i], NULL, &current) != 0) continue;
        if (current.sa_handler != SIG_DFL || (current.sa_flags & SA_SIGINFO)) continue;
        struct sigaction act; memset(&act, 0, sizeof act); act.sa_handler = SIG_DFL; original[sigs[i]] = act;
        act.sa_flags = SA_SIGINFO; act.sa_sigaction = received; sigemptyset(&act.sa_mask);
        static sigaction_fn real; if (!real) real = (sigaction_fn) dlsym(RTLD_NEXT, "sigaction"); real(sigs[i], &act, NULL);
    }
}
