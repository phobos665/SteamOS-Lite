/*
 * ADPF (Android 13+ NDK): a performance hint session over the compositor thread, told the panel's
 * frame period as its target and each drawn frame's interval as the actual work. When a frame runs
 * long the power HAL raises the clocks for the next one at once, instead of the governor waiting
 * for the load to climb. Only this process's threads may join, so gamescope and Steam are covered
 * by the app's game category and sustained mode instead. Looked up at run time: the app runs from
 * Android 8, where libandroid has none of this.
 */
#include <dlfcn.h>
#include <stdint.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include "adpf.h"
#include "banner_ext.h"

typedef void *(*get_manager_fn)(void);
typedef void *(*create_session_fn)(void *, const int32_t *, size_t, int64_t);
typedef int (*update_target_fn)(void *, int64_t);
typedef int (*report_fn)(void *, int64_t);

static int g_state; /* 0 = not tried, 1 = session open, -1 = unavailable */
static void *g_session;
static update_target_fn g_update_target;
static report_fn g_report;
static int64_t g_target_ns, g_last_ns;

static int64_t mono_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static void open_session(int64_t target_ns) {
    g_state = -1;
    void *lib = dlopen("libandroid.so", RTLD_NOW);
    if (!lib) return;
    get_manager_fn get_manager = (get_manager_fn)dlsym(lib, "APerformanceHint_getManager");
    create_session_fn create = (create_session_fn)dlsym(lib, "APerformanceHint_createSession");
    g_update_target = (update_target_fn)dlsym(lib, "APerformanceHint_updateTargetWorkDuration");
    g_report = (report_fn)dlsym(lib, "APerformanceHint_reportActualWorkDuration");
    if (!get_manager || !create || !g_update_target || !g_report) {
        banner_log("perf", "adpf: needs Android 13");
        return;
    }
    void *manager = get_manager();
    int32_t tid = (int32_t)gettid();
    g_session = manager ? create(manager, &tid, 1, target_ns) : NULL;
    if (!g_session) {
        banner_log("perf", "adpf: unsupported here (the power HAL has no hint sessions)");
        return;
    }
    g_target_ns = target_ns;
    g_state = 1;
    banner_log("perf", "adpf: session on compositor thread %d, target %lld us", (int)tid, (long long)(target_ns / 1000));
}

void adpf_frame(int64_t target_ns) {
    if (g_state == 0) open_session(target_ns);
    if (g_state != 1) return;
    /* Retarget only on a real change of refresh rate, not the measured period's jitter. */
    int64_t diff = target_ns - g_target_ns;
    if (diff > 500000 || diff < -500000) {
        g_target_ns = target_ns;
        g_update_target(g_session, target_ns);
    }
    int64_t now = mono_ns(), prev = g_last_ns;
    g_last_ns = now;
    if (!prev) return;
    int64_t dt = now - prev;
    /* Under half a millisecond is a double draw; over half a second is a pause, not a frame. */
    if (dt < 500000 || dt > 500000000) return;
    if (g_report(g_session, dt) != 0) {
        banner_log("perf", "adpf: report failed; session dropped");
        g_state = -1;
    }
}
