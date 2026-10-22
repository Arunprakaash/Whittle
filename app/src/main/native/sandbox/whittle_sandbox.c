/*
 * Whittle's command sandbox. Starts a command behind a seccomp filter it and everything it starts keep for
 * life:
 *   - always: no ptrace and no process_vm_readv/writev, so a command can't read or change the memory of
 *     Whittle itself (where the user's key is decrypted while in use); no io_uring, which bypasses filters;
 *   - unless --network is given: no sockets of any kind. That covers the internet and also name lookups,
 *     which Android routes through a local socket and could otherwise carry data out. Pipes and
 *     socketpair still work, so ordinary programs are unaffected.
 *
 * Usage: whittle_sandbox [--network] -- program [args...]
 * Fails closed: if the filter can't be installed, the command doesn't run.
 *
 * With --session it only starts the program in a new session, and so its own process group, with no filter:
 * Whittle starts proot that way, and the command it runs inside Linux stays in that group, so Stop and
 * timeouts end everything the command started. proot needs ptrace itself, so the filter goes on inside.
 *
 * Usage: whittle_sandbox --session -- program [args...]
 */
#include <errno.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

#if defined(__aarch64__)
#define CURRENT_AUDIT_ARCH AUDIT_ARCH_AARCH64
#elif defined(__x86_64__)
#define CURRENT_AUDIT_ARCH AUDIT_ARCH_X86_64
#else
#error "whittle_sandbox supports arm64 and x86_64"
#endif

#define EXIT_USAGE 125
#define EXIT_SANDBOX 126

#define LOAD_ARCH BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch))
#define LOAD_NR BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr))
#define ALLOW BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)
#define DENY(err) BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ((err) & SECCOMP_RET_DATA))
/* If the syscall number equals nr, return `err`; otherwise fall through. */
#define BLOCK(nr, err) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (nr), 0, 1), DENY(err)

static int install_filter(int network) {
    struct sock_filter always[] = {
        LOAD_ARCH,
        /* A different syscall table (e.g. 32-bit code) would dodge every rule below. */
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, CURRENT_AUDIT_ARCH, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        LOAD_NR,
#if defined(__x86_64__)
        /* x32 syscalls share the table under a high bit; refuse them outright. */
        BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, 0x40000000, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
#endif
        BLOCK(__NR_ptrace, EPERM),
        BLOCK(__NR_process_vm_readv, EPERM),
        BLOCK(__NR_process_vm_writev, EPERM),
#ifdef __NR_io_uring_setup
        BLOCK(__NR_io_uring_setup, ENOSYS),
#endif
    };
    struct sock_filter offline[] = {
        BLOCK(__NR_socket, EACCES),
    };
    struct sock_filter allow[] = { ALLOW };

    struct sock_filter program[sizeof(always) / sizeof(always[0]) + sizeof(offline) / sizeof(offline[0]) + 1];
    size_t length = 0;
    memcpy(program, always, sizeof(always));
    length += sizeof(always) / sizeof(always[0]);
    if (!network) {
        memcpy(program + length, offline, sizeof(offline));
        length += sizeof(offline) / sizeof(offline[0]);
    }
    memcpy(program + length, allow, sizeof(allow));
    length += 1;

    struct sock_fprog fprog = { .len = (unsigned short) length, .filter = program };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) return -1;
    return (int) syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, 0, &fprog);
}

int main(int argc, char **argv) {
    int network = 0;
    int session = 0;
    int index = 1;
    if (index < argc && strcmp(argv[index], "--session") == 0) {
        session = 1;
        index++;
    } else if (index < argc && strcmp(argv[index], "--network") == 0) {
        network = 1;
        index++;
    }
    if (index >= argc || strcmp(argv[index], "--") != 0 || index + 1 >= argc) {
        fprintf(stderr, "usage: whittle_sandbox [--network | --session] -- program [args...]\n");
        return EXIT_USAGE;
    }
    index++;
    if (session) {
        if (setsid() < 0) {
            fprintf(stderr, "whittle_sandbox: can't start a session: %s\n", strerror(errno));
            return EXIT_SANDBOX;
        }
    } else if (install_filter(network) != 0) {
        fprintf(stderr, "whittle_sandbox: can't install the command sandbox: %s\n", strerror(errno));
        return EXIT_SANDBOX;
    }
    execvp(argv[index], argv + index);
    fprintf(stderr, "whittle_sandbox: can't run %s: %s\n", argv[index], strerror(errno));
    return EXIT_SANDBOX;
}
