/*
 * Seccomp fix for Kyocera KY-42C (kernel 4.9, no statx syscall).
 *
 * The Rust stdlib's file metadata path calls statx (syscall 397 on ARM),
 * which the device's seccomp filter blocks with SIGSYS. This installs a
 * SIGSYS handler that converts the killed syscall into an ENOSYS return,
 * letting the Rust code fall back to fstatat/stat.
 *
 * Must be called before any file I/O from native code.
 */
#include <signal.h>
#include <errno.h>
#include <android/log.h>

#define TAG "SeccompFix"
#define SYS_statx 397  // ARM32 syscall number for statx

static void sigsys_handler(int signum, siginfo_t *info, void *ctx) {
    ucontext_t *uc = (ucontext_t *)ctx;
    // Set return value to -ENOSYS and skip the syscall
    uc->uc_mcontext.arm_r0 = -ENOSYS;
    // Advance PC past the svc instruction (4 bytes on ARM)
    uc->uc_mcontext.arm_pc += 4;
}

void install_seccomp_fix(void) {
    struct sigaction sa;
    sa.sa_sigaction = sigsys_handler;
    sa.sa_flags = SA_SIGINFO | SA_RESTART;
    sigemptyset(&sa.sa_mask);
    if (sigaction(SIGSYS, &sa, NULL) == 0) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "SIGSYS handler installed (statx fallback to ENOSYS)");
    } else {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to install SIGSYS handler");
    }
}
