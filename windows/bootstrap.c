/* Native Android launcher handoff. Only a one-use game session reaches Wine. */
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <tlhelp32.h>
#include <stdio.h>
#include <string.h>
#include <wchar.h>
#include <time.h>
#include <stdlib.h>

static wchar_t folder[32768], diagnostic_folder[32768];
static HWND setup_window, setup_label;
static HANDLE lock_file = INVALID_HANDLE_VALUE;
#define WM_SETUP_STATUS (WM_APP + 1)
#define WM_SETUP_DONE (WM_APP + 2)

static void log_event(const char *event, DWORD code) {
    if (!diagnostic_folder[0]) return;
    wchar_t path[32768]; swprintf(path, 32768, L"%ls\\bootstrap.log", diagnostic_folder);
    FILE *f = _wfopen(path, L"ab");
    if (f) {
        SYSTEMTIME t; GetLocalTime(&t);
        fprintf(f, "%04u-%02u-%02u %02u:%02u:%02u pid=%lu %s code=%lu\n", t.wYear, t.wMonth, t.wDay,
            t.wHour, t.wMinute, t.wSecond, (unsigned long)GetCurrentProcessId(), event, (unsigned long)code);
        fclose(f);
    }
}
static void status(const char *phase, DWORD code) {
    log_event(phase, code);
    if (setup_window) PostMessageW(setup_window, WM_SETUP_STATUS, !strcmp(phase, "game") ? 1 : 0, 0);
    wchar_t path[32768], temp[32768];
    swprintf(path, 32768, L"%ls\\status.json", diagnostic_folder);
    swprintf(temp, 32768, L"%ls\\status.tmp", diagnostic_folder);
    FILE *f = _wfopen(temp, L"wb");
    if (f) { fprintf(f, "{\"phase\":\"%s\",\"code\":%lu}\n", phase, (unsigned long)code); fclose(f);
        MoveFileExW(temp, path, MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH); }
}
static int fail(const char *phase, DWORD code) { status(phase, code); return 1; }
static BOOL start(const wchar_t *exe, const wchar_t *args, const wchar_t *cwd, PROCESS_INFORMATION *pi) {
    wchar_t cmd[32768]; swprintf(cmd, 32768, L"\"%ls\" %ls", exe, args);
    STARTUPINFOW si = {0}; si.cb = sizeof(si); ZeroMemory(pi, sizeof(*pi));
    /* Suppress console-subsystem children without hiding the engine's GUI. */
    BOOL ok = CreateProcessW(exe, cmd, NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, cwd, &si, pi);
    DWORD error = ok ? ERROR_SUCCESS : GetLastError();
    log_event(ok ? "child_started_pid" : "child_start_failed", ok ? pi->dwProcessId : error);
    SetLastError(error); return ok;
}

typedef struct { DWORD pid; HANDLE handle; } child;
static child children[512]; static int count;
static BOOL known(DWORD pid) { for (int i = 0; i < count; i++) if (children[i].pid == pid) return TRUE; return FALSE; }
static BOOL client_opened;
static HWND client_window;
static BOOL close_requested, startup_timeout;
static BOOL close_request(void) {
    wchar_t path[32768]; swprintf(path,32768,L"%ls\\close-client",folder);
    FILE *f=_wfopen(path,L"rb");if(!f)return FALSE;
    char value[32]={0};size_t n=fread(value,1,sizeof(value)-1,f);fclose(f);DeleteFileW(path);
    return n==11&&!memcmp(value,"TTR-CLOSE1\n",11);
}
static BOOL CALLBACK close_client(HWND window,LPARAM unused){
    (void)unused;DWORD pid=0;GetWindowThreadProcessId(window,&pid);
    if(known(pid))PostMessageW(window,WM_CLOSE,0,0);return TRUE;
}
static BOOL CALLBACK find_client(HWND window, LPARAM unused) {
    (void)unused; DWORD pid = 0; GetWindowThreadProcessId(window, &pid);
    if (!known(pid) || !IsWindowVisible(window) || GetWindow(window, GW_OWNER)) return TRUE;
    wchar_t name[128]; GetClassNameW(window, name, 128);
    if (wcsstr(name, L"Console") || wcsstr(name, L"console")) return TRUE;
    RECT bounds; if (!GetClientRect(window, &bounds) || bounds.right < 320 || bounds.bottom < 240) return TRUE;
    client_window = window;
    /* The X display follows Android's viewport. Resize the actual client, not
       its pixels, so square portrait and wide landscape keep correct geometry. */
    int width = GetSystemMetrics(SM_CXSCREEN), height = GetSystemMetrics(SM_CYSCREEN);
    /* Wine may cache desktop metrics after XRandR changes. Android supplies
       the desired dimensions so rotation/quality also resize the real HWND. */
    wchar_t viewport_path[32768]; swprintf(viewport_path,32768,L"%ls\\viewport.txt",diagnostic_folder);
    FILE *viewport = _wfopen(viewport_path,L"rb");
    if(viewport) {
        int requested_width=0,requested_height=0;
        if(fscanf(viewport,"%d %d",&requested_width,&requested_height)==2 &&
           requested_width>=320 && requested_width<=8192 && requested_height>=320 && requested_height<=8192) {
            width=requested_width;height=requested_height;
        }
        fclose(viewport);
    }
    LONG_PTR style = GetWindowLongPtrW(window, GWL_STYLE);
    LONG_PTR borderless = (style & ~(WS_CAPTION | WS_THICKFRAME | WS_MINIMIZEBOX | WS_MAXIMIZEBOX)) | WS_POPUP;
    if (style != borderless) SetWindowLongPtrW(window, GWL_STYLE, borderless);
    RECT outer; GetWindowRect(window, &outer);
    if (outer.left || outer.top || outer.right != width || outer.bottom != height || style != borderless)
        SetWindowPos(window, NULL, 0, 0, width, height, SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
    /* The backing desktop is wider than the visible game. Its initial cursor
       position (or a position retained across rotation) may be in that crop. */
    RECT pointer_bounds={0,0,width,height};
    POINT pointer;
    if(GetCursorPos(&pointer) && (pointer.x<0 || pointer.y<0 || pointer.x>=width || pointer.y>=height))
        SetCursorPos(width/2,height/2);
    ClipCursor(&pointer_bounds);
    if (!client_opened) { client_opened = TRUE; status("game", 0); }
    return FALSE;
}
static void wait_for_tree(PROCESS_INFORMATION *pi) {
    children[0] = (child){pi->dwProcessId, pi->hProcess}; count = 1; CloseHandle(pi->hThread);
    client_opened = FALSE; client_window = NULL;
    int empty = 0;ULONGLONG started=GetTickCount64(),closing_at=0,last_snapshot=0;
    close_requested=FALSE;startup_timeout=FALSE;
    for (;;) {
        ULONGLONG now=GetTickCount64();
        if(!close_requested&&(close_request()||(!client_opened&&now-started>=120000))){
            close_requested=TRUE;startup_timeout=now-started>=120000&&!client_opened;
            closing_at=now;status(startup_timeout?"window_timeout":"closing_game",0);
        }
        if(now-last_snapshot>=250){last_snapshot=now;
        HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if (snapshot != INVALID_HANDLE_VALUE) {
            PROCESSENTRY32W entry = {0}; entry.dwSize = sizeof(entry);
            if (Process32FirstW(snapshot, &entry)) do {
                if (!known(entry.th32ProcessID) && known(entry.th32ParentProcessID) && count < 512) {
                    HANDLE h = OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_TERMINATE, FALSE, entry.th32ProcessID);
                    if (h) children[count++] = (child){entry.th32ProcessID, h};
                }
            } while (Process32NextW(snapshot, &entry));
            CloseHandle(snapshot);
        }
        }
        int alive = 0;
        for (int i = 0; i < count; i++) if (WaitForSingleObject(children[i].handle, 0) == WAIT_TIMEOUT) alive++;
        if(alive&&close_requested){
            EnumWindows(close_client,0);
            if(now-closing_at>=3000)for(int i=count-1;i>=0;i--)
                if(WaitForSingleObject(children[i].handle,0)==WAIT_TIMEOUT)TerminateProcess(children[i].handle,ERROR_CANCELLED);
        }else if (alive) EnumWindows(find_client, 0);
        if (alive) empty = 0; else if (++empty >= 4) break;
        Sleep(100);
    }
    ClipCursor(NULL);
    for (int i = 0; i < count; i++) CloseHandle(children[i].handle);
}

static BOOL utf8(const char *value, wchar_t *out, int size) { return MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value, -1, out, size) > 0; }
static BOOL printable(const char *s) {
    if (!*s || strlen(s) > 8192) return FALSE;
    for (; *s; s++) if ((unsigned char)*s < 33 || (unsigned char)*s > 126) return FALSE;
    return TRUE;
}
typedef struct { wchar_t server[512], cookie[8193], game[32768]; } game_session;
static BOOL read_session(game_session *session) {
    wchar_t path[32768]; swprintf(path, 32768, L"%ls\\session.bin", folder);
    char data[24576] = {0}; FILE *f = _wfopen(path, L"rb");
    if (!f) return FALSE;
    size_t n = fread(data, 1, sizeof(data) - 1, f); BOOL too_long = fgetc(f) != EOF; fclose(f);
    /* Consume before parsing or spawning: a crash/retry cannot replay this file. */
    if (!DeleteFileW(path) || too_long || !n || memchr(data, 0, n)) { SecureZeroMemory(data, sizeof(data)); return FALSE; }
    char *lines[5], *p = data; BOOL ok = TRUE;
    for (int i = 0; i < 5; i++) { lines[i] = p; char *end = strchr(p, '\n'); if (!end) { ok = FALSE; break; } *end = 0; p = end + 1; }
    if (ok) {
        char *end = NULL; long long expiry = strtoll(lines[1], &end, 10); long long now = (long long)time(NULL);
        ok = !strcmp(lines[0], "TTR4") && !*p && end != lines[1] && !*end && expiry >= now && expiry <= now + 300
            && strlen(lines[2]) < 512 && printable(lines[2]) && printable(lines[3])
            && !strchr(lines[4], '\r') && strlen(lines[4]) > 3
            && utf8(lines[2], session->server, 512) && utf8(lines[3], session->cookie, 8193) && utf8(lines[4], session->game, 32768);
    }
    SecureZeroMemory(data, sizeof(data));
    if (!ok) SecureZeroMemory(session, sizeof(*session)); return ok;
}
static DWORD run_game(void) {
    game_session *session = calloc(1, sizeof(game_session));
    if (!session) return fail("session_invalid", ERROR_OUTOFMEMORY);
    if (!read_session(session)) { free(session); return fail("session_invalid", ERROR_INVALID_DATA); }
    close_request(); /* discard an old request before starting a new owned tree */
    wchar_t exe[32768]; swprintf(exe, 32768, L"%ls\\TTREngine64.exe", session->game);
    status("starting_game", 0);
    BOOL configured = SetEnvironmentVariableW(L"TTR_GAMESERVER", session->server) && SetEnvironmentVariableW(L"TTR_PLAYCOOKIE", session->cookie);
    PROCESS_INFORMATION pi; BOOL started = configured && start(exe, L"", session->game, &pi);
    DWORD error = started ? 0 : GetLastError();
    SetEnvironmentVariableW(L"TTR_GAMESERVER", NULL); SetEnvironmentVariableW(L"TTR_PLAYCOOKIE", NULL);
    SecureZeroMemory(session, sizeof(*session)); free(session);
    if (!started) return fail("game_start_failed", error);
    status("client_started",0);
    wait_for_tree(&pi);
    if(startup_timeout)return fail("window_timeout",WAIT_TIMEOUT);
    status("closed", 0); return 0;
}
static LRESULT CALLBACK setup_proc(HWND window, UINT message, WPARAM wp, LPARAM lp) {
    switch (message) {
    case WM_CREATE:
        setup_label = CreateWindowW(L"STATIC", L"Opening Toontown Rewritten...", WS_CHILD | WS_VISIBLE | SS_CENTER,
            24, 42, 512, 100, window, NULL, ((LPCREATESTRUCTW)lp)->hInstance, NULL); return 0;
    case WM_SETUP_STATUS:
        if (wp == 1) ShowWindow(window, SW_HIDE); return 0;
    case WM_CLOSE: return 0;
    case WM_SETUP_DONE: DestroyWindow(window); return 0;
    case WM_DESTROY: PostQuitMessage(0); return 0;
    }
    return DefWindowProcW(window, message, wp, lp);
}
static HWND create_setup_window(HINSTANCE instance, int show) {
    WNDCLASSW wc = {0}; wc.lpfnWndProc = setup_proc; wc.hInstance = instance; wc.lpszClassName = L"TtrMobileGame";
    wc.hCursor = LoadCursor(NULL, IDC_ARROW); wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    if (!RegisterClassW(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) return NULL;
    HWND window = CreateWindowW(wc.lpszClassName, L"Toontown Rewritten", WS_OVERLAPPED | WS_CAPTION,
        CW_USEDEFAULT, CW_USEDEFAULT, 580, 210, NULL, NULL, instance, NULL);
    if (window) { ShowWindow(window, show); UpdateWindow(window); } return window;
}
static BOOL resolve_diagnostics(void) {
    wchar_t path[32768]; swprintf(path, 32768, L"%ls\\diagnostics-path.txt", folder);
    char data[32768] = {0}; FILE *f = _wfopen(path, L"rb");
    if (!f) return FALSE;
    size_t n = fread(data, 1, sizeof(data) - 1, f); BOOL too_long = fgetc(f) != EOF; fclose(f);
    if (too_long || !n || memchr(data, 0, n)) return FALSE;
    data[strcspn(data, "\r\n")] = 0;
    if (!utf8(data, diagnostic_folder, 32768)) return FALSE;
    return CreateDirectoryW(diagnostic_folder, NULL) || GetLastError() == ERROR_ALREADY_EXISTS;
}
static DWORD WINAPI game_worker(LPVOID unused) {
    (void)unused;
    /* Windows can be ready long before authentication. No session is created
       or consumed until Android explicitly requests Play. */
    status("windows_ready", 0);
    for (;;) {
        wchar_t path[32768]; swprintf(path, 32768, L"%ls\\session.bin", folder);
        if (GetFileAttributesW(path) != INVALID_FILE_ATTRIBUTES) {
            DWORD result = run_game(); log_event("client_finished", result);
            if (result == 0) status("windows_ready", 0);
        }
        Sleep(150);
    }
}
int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR command, int show) {
    (void)previous; (void)command; (void)show;
    DWORD n = GetModuleFileNameW(NULL, folder, 32768); if (!n || n >= 32768) return 1;
    wchar_t *slash = wcsrchr(folder, L'\\'); if (!slash) return 1; *slash = 0;
    if (!resolve_diagnostics()) return 1;
    wchar_t path[32768], old[32768]; swprintf(path, 32768, L"%ls\\session.lock", folder);
    lock_file = CreateFileW(path, GENERIC_WRITE, 0, NULL, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (lock_file == INVALID_HANDLE_VALUE) return 0;
    swprintf(path, 32768, L"%ls\\bootstrap.log", diagnostic_folder); swprintf(old, 32768, L"%ls\\bootstrap-previous.log", diagnostic_folder);
    MoveFileExW(path, old, MOVEFILE_REPLACE_EXISTING); log_event("helper_started_0.4.0", 0);
    setup_window = create_setup_window(instance, SW_HIDE);
    if (!setup_window) { CloseHandle(lock_file); return fail("setup_window_failed", GetLastError()); }
    HANDLE worker = CreateThread(NULL, 0, game_worker, NULL, 0, NULL);
    if (!worker) { CloseHandle(lock_file); return fail("setup_worker_failed", GetLastError()); }
    MSG message; while (GetMessageW(&message, NULL, 0, 0) > 0) { TranslateMessage(&message); DispatchMessageW(&message); }
    WaitForSingleObject(worker, INFINITE); DWORD result = 1; GetExitCodeThread(worker, &result);
    CloseHandle(worker); CloseHandle(lock_file); return (int)result;
}
