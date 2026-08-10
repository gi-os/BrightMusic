fn main() {
    // Compile the seccomp fix C file into the shared library.
    // This installs a SIGSYS handler that converts the statx syscall
    // (blocked by the Kyocera's seccomp filter) to ENOSYS.
    cc::Build::new()
        .file("src/seccomp_fix.c")
        .compile("seccomp_fix");
}
