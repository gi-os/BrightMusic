#include <signal.h>
#include <errno.h>
#include <unistd.h>
#include <string.h>
#include <fcntl.h>
#include <android/log.h>
#include <sys/uio.h>

#define TAG "SeccompFix"

/*
 * SIGSYS handler for Kyocera KY-42C (kernel 4.9, no statx).
 *
 * The seccomp filter traps the statx syscall and delivers SIGSYS.
 * We need to:
 * 1. Set r0 = -ENOSYS so the caller falls back
 * 2. Skip past the svc instruction
 *
 * To determine ARM (4 bytes) vs Thumb (2 bytes), we read the
 * instruction at the faulting PC. If bit 0 of PC is set, it's Thumb.
 * If not, we check the CPSR T bit. As a fallback, we try reading
 * the instruction bytes — svc in ARM is 0xef000000, in Thumb is 0xdf00.
 */
static void sigsys_handler(int signum, siginfo_t *info, void *ctx) {
    ucontext_t *uc = (ucontext_t *)ctx;
    
    unsigned long pc = uc->uc_mcontext.arm_pc;
    unsigned long cpsr = uc->uc_mcontext.arm_cpsr;
    unsigned long r7 = uc->uc_mcontext.arm_r7;
    
    /* Set return value to -ENOSYS */
    uc->uc_mcontext.arm_r0 = (unsigned long)(-ENOSYS);
    
    /* Determine Thumb vs ARM mode:
     * 1. Check CPSR T bit (bit 5 = 0x20)
     * 2. Check PC Thumb bit (bit 0)
     * 3. Read the instruction at PC and check if it's a Thumb svc (0xdf00)
     *    or ARM svc (0xef000000)
     */
    int is_thumb = 0;
    
    if (cpsr & 0x20) {
        is_thumb = 1;
    } else if (pc & 1) {
        is_thumb = 1;
        pc &= ~1UL;  /* Clear Thumb bit for actual address */
    } else {
        /* Try reading the instruction to determine mode */
        unsigned short instr16 = 0;
        /* Use /proc/self/mem to read the instruction safely */
        int fd = open("/proc/self/mem", O_RDONLY);
        if (fd >= 0) {
            if (lseek(fd, (off_t)pc, SEEK_SET) == (off_t)pc) {
                if (read(fd, &instr16, 2) == 2) {
                    /* Thumb svc is 0xdf00 (little-endian: 00 df) */
                    if (instr16 == 0xdf00) {
                        is_thumb = 1;
                    }
                    /* ARM svc is 0xef000000 — check if the 16-bit half
                     * is 0x0000 (first half of ARM svc in LE) */
                    if (instr16 == 0x0000) {
                        /* Read the next 2 bytes to confirm */
                        unsigned short instr16b = 0;
                        if (read(fd, &instr16b, 2) == 2 && instr16b == 0xef00) {
                            is_thumb = 0;
                        }
                    }
                }
            }
            close(fd);
        }
    }
    
    unsigned long next_pc;
    if (is_thumb) {
        next_pc = (pc & ~1UL) + 2;
    } else {
        next_pc = pc + 4;
    }
    
    uc->uc_mcontext.arm_pc = next_pc;
    
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "SIGSYS: pc=0x%lx r7=%lu thumb=%d -> pc=0x%lx",
        pc, r7, is_thumb, next_pc);
}

__attribute__((visibility("default")))
void install_seccomp_fix(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = sigsys_handler;
    sa.sa_flags = SA_SIGINFO | SA_NODEFER;
    sigemptyset(&sa.sa_mask);
    if (sigaction(SIGSYS, &sa, NULL) == 0) {
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "SIGSYS handler installed");
    } else {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "Failed to install SIGSYS handler");
    }
}
