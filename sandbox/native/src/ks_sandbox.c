/*
 * Keelson 沙盒的原生件（A-47）：Landlock 文件系统加固 + seccomp 网络隔离。
 *
 * 参照 OpenAI Codex CLI（Apache-2.0，Copyright 2025 OpenAI）的借鉴项：
 *   codex-rs/linux-sandbox/src/seccomp.rs（no_new_privs + seccomp 过滤器的规则集）
 *   codex-rs/linux-sandbox/src/linux_run_main.rs（Landlock 的规则集：根只读、可写根可写）
 * 见 native/sandbox/README.md 与 tasks/A-47.md。
 *
 * 对外入口：
 *   ks_probe()                     Landlock ABI 版本（>=1 可用；0 不支持）
 *   ks_apply(blob, len)            对当前进程套用策略（no_new_privs + Landlock + seccomp）
 *   ks_spawn(argv, envp, blob...)  fork -> 套用策略 -> execve（不经 LD_PRELOAD 的路子）
 *   ks_write_seccomp(fd, mode)     把 seccomp 过滤器写成 bwrap --seccomp 认的 sock_fprog
 *
 * LD_PRELOAD 子进程：环境变量 KS_SANDBOX_POLICY 指向策略文件或 hex: blob => 构造函数读它并套用；
 * 套不上就 _exit(127)（fail closed：宁可不跑，也不要裸跑）。
 */

#define _GNU_SOURCE

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <netinet/in.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

/* ── Landlock ABI（内核 >= 5.13；本文件自带定义，不依赖内核头文件的版本） ───────── */

#ifndef LANDLOCK_CREATE_RULESET_VERSION
#define LANDLOCK_CREATE_RULESET_VERSION (1U << 0)
#endif

#define KS_LL_CREATE_RULESET 444
#define KS_LL_ADD_RULE 445
#define KS_LL_RESTRICT_SELF 446

#define KS_LL_RULE_PATH_BENEATH 1

/* CMake 默认把符号藏起来；P/Invoke 只认下面这四个入口。 */
#define KS_EXPORT __attribute__((visibility("default")))

#define KS_LL_ACCESS_FS_EXECUTE (1ULL << 0)
#define KS_LL_ACCESS_FS_WRITE_FILE (1ULL << 1)
#define KS_LL_ACCESS_FS_READ_FILE (1ULL << 2)
#define KS_LL_ACCESS_FS_READ_DIR (1ULL << 3)
#define KS_LL_ACCESS_FS_REMOVE_DIR (1ULL << 4)
#define KS_LL_ACCESS_FS_REMOVE_FILE (1ULL << 5)
#define KS_LL_ACCESS_FS_MAKE_CHAR (1ULL << 6)
#define KS_LL_ACCESS_FS_MAKE_DIR (1ULL << 7)
#define KS_LL_ACCESS_FS_MAKE_REG (1ULL << 8)
#define KS_LL_ACCESS_FS_MAKE_SOCK (1ULL << 9)
#define KS_LL_ACCESS_FS_MAKE_FIFO (1ULL << 10)
#define KS_LL_ACCESS_FS_MAKE_BLOCK (1ULL << 11)
#define KS_LL_ACCESS_FS_MAKE_SYM (1ULL << 12)
#define KS_LL_ACCESS_FS_REFER (1ULL << 13)    /* ABI 2 */
#define KS_LL_ACCESS_FS_TRUNCATE (1ULL << 14) /* ABI 3 */
#define KS_LL_ACCESS_FS_IOCTL_DEV (1ULL << 15) /* ABI 4 */

struct ks_landlock_ruleset_attr {
    uint64_t handled_access_fs;
};

struct ks_landlock_path_beneath_attr {
    uint64_t allowed_access;
    int32_t parent_fd;
};

/* ── 策略 blob（托管侧写、原生侧读；小端） ─────────────────────────────────────────
 *   u32 magic 'KSPB' | u32 version | u32 net_mode | u32 fs_enabled | u32 rule_count
 *   rule_count x { u64 access | u32 path_len | path 字节（不含结尾 NUL） }
 * access 用 Landlock 的位（READ_FILE / READ_DIR / WRITE_FILE / ...）。
 */

#define KS_BLOB_MAGIC 0x4250534Bu /* 'K','S','P','B' */
#define KS_BLOB_VERSION 1u

#define KS_NET_NONE 0u
#define KS_NET_RESTRICTED 1u

#define KS_MAX_RULES 256

struct ks_rule {
    uint64_t access;
    char path[PATH_MAX];
};

static int ks_read_file(const char *path, unsigned char **out, size_t *out_len)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -errno;
    size_t cap = 4096, len = 0;
    unsigned char *buf = (unsigned char *)malloc(cap);
    if (!buf) {
        close(fd);
        return -ENOMEM;
    }
    for (;;) {
        if (len == cap) {
            size_t next = cap * 2;
            unsigned char *grown = (unsigned char *)realloc(buf, next);
            if (!grown) {
                free(buf);
                close(fd);
                return -ENOMEM;
            }
            buf = grown;
            cap = next;
        }
        ssize_t n = read(fd, buf + len, cap - len);
        if (n < 0) {
            int e = -errno;
            free(buf);
            close(fd);
            return e;
        }
        if (n == 0) break;
        len += (size_t)n;
    }
    close(fd);
    *out = buf;
    *out_len = len;
    return 0;
}

static int ks_parse_blob(const unsigned char *blob, size_t len, struct ks_rule *rules, size_t *rule_count,
                          uint32_t *net_mode, uint32_t *fs_enabled)
{
    if (len < 20) return -EINVAL;
    uint32_t magic, version, net, fs, count;
    memcpy(&magic, blob + 0, 4);
    memcpy(&version, blob + 4, 4);
    memcpy(&net, blob + 8, 4);
    memcpy(&fs, blob + 12, 4);
    memcpy(&count, blob + 16, 4);
    if (magic != KS_BLOB_MAGIC) return -EINVAL;
    if (version != KS_BLOB_VERSION) return -ENOTSUP;
    if (count > KS_MAX_RULES) return -E2BIG;
    size_t off = 20;
    for (uint32_t i = 0; i < count; i++) {
        if (off + 12 > len) return -EINVAL;
        uint64_t access;
        uint32_t path_len;
        memcpy(&access, blob + off, 8);
        memcpy(&path_len, blob + off + 8, 4);
        off += 12;
        if (path_len == 0 || path_len >= PATH_MAX) return -EINVAL;
        if (off + path_len > len) return -EINVAL;
        memcpy(rules[i].path, blob + off, path_len);
        rules[i].path[path_len] = '\0';
        rules[i].access = access;
        off += path_len;
    }
    *rule_count = count;
    *net_mode = net;
    *fs_enabled = fs;
    return 0;
}

/* ── Landlock ───────────────────────────────────────────────────────────────────── */

static int ks_ll_create_ruleset(uint64_t handled)
{
    struct ks_landlock_ruleset_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.handled_access_fs = handled;
    return (int)syscall(KS_LL_CREATE_RULESET, &attr, sizeof(attr), 0);
}

static int ks_ll_add_rule(int ruleset_fd, uint64_t access, int path_fd)
{
    struct ks_landlock_path_beneath_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.allowed_access = access;
    attr.parent_fd = path_fd;
    return (int)syscall(KS_LL_ADD_RULE, ruleset_fd, KS_LL_RULE_PATH_BENEATH, &attr, 0);
}

static int ks_ll_restrict_self(int ruleset_fd)
{
    return (int)syscall(KS_LL_RESTRICT_SELF, ruleset_fd, 0);
}

/* 内核支持的位（按 ABI 版本掩掉） */
static uint64_t ks_ll_supported_access(int abi)
{
    uint64_t bits = KS_LL_ACCESS_FS_EXECUTE | KS_LL_ACCESS_FS_WRITE_FILE | KS_LL_ACCESS_FS_READ_FILE |
                    KS_LL_ACCESS_FS_READ_DIR | KS_LL_ACCESS_FS_REMOVE_DIR | KS_LL_ACCESS_FS_REMOVE_FILE |
                    KS_LL_ACCESS_FS_MAKE_CHAR | KS_LL_ACCESS_FS_MAKE_DIR | KS_LL_ACCESS_FS_MAKE_REG |
                    KS_LL_ACCESS_FS_MAKE_SOCK | KS_LL_ACCESS_FS_MAKE_FIFO | KS_LL_ACCESS_FS_MAKE_BLOCK |
                    KS_LL_ACCESS_FS_MAKE_SYM;
    if (abi >= 2) bits |= KS_LL_ACCESS_FS_REFER;
    if (abi >= 3) bits |= KS_LL_ACCESS_FS_TRUNCATE;
    if (abi >= 4) bits |= KS_LL_ACCESS_FS_IOCTL_DEV;
    return bits;
}

static int ks_apply_landlock(const struct ks_rule *rules, size_t count)
{
    int abi = (int)syscall(KS_LL_CREATE_RULESET, NULL, 0, LANDLOCK_CREATE_RULESET_VERSION);
    if (abi < 1) return abi < 0 ? -errno : -ENOSYS;

    uint64_t supported = ks_ll_supported_access(abi);
    if (count == 0) return 0; /* 没有要管的路径 => 不用套 */
    /* Landlock 是缺省拒绝：规则没给的权利就禁；可写根再显式放行。 */
    uint64_t handled = supported;

    int ruleset = ks_ll_create_ruleset(handled);
    if (ruleset < 0) return ruleset;

    for (size_t i = 0; i < count; i++) {
        uint64_t access = rules[i].access & supported;
        if (access == 0) continue;
        int path_fd = open(rules[i].path, O_PATH | O_CLOEXEC);
        if (path_fd < 0) continue;
        int rc = ks_ll_add_rule(ruleset, access, path_fd);
        close(path_fd);
        if (rc < 0) {
            close(ruleset);
            return rc;
        }
    }

    int rc = ks_ll_restrict_self(ruleset);
    close(ruleset);
    return rc < 0 ? rc : 0;
}

/* ── seccomp ────────────────────────────────────────────────────────────────────── */

#if defined(__x86_64__)
#define KS_AUDIT_ARCH AUDIT_ARCH_X86_64
#elif defined(__aarch64__)
#define KS_AUDIT_ARCH AUDIT_ARCH_AARCH64
#else
#define KS_AUDIT_ARCH 0
#endif

#define KS_RET_ERRNO (SECCOMP_RET_ERRNO | (EPERM & SECCOMP_RET_DATA))

#define KS_EMIT_JUMP(k, jt, jf) insns[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (k), (jt), (jf));
#define KS_EMIT_RET(val) insns[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, (val));
#define KS_EMIT_LD(off) insns[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, (uint32_t)(off));

#define KS_DENY_NR(nr)                                                                                    \
    KS_EMIT_JUMP((nr), 0, 1)                                                                            \
    KS_EMIT_RET(KS_RET_ERRNO)

#define KS_MAX_INSNS 96

/* 断网模式（Codex 的 NetworkSeccompMode::Restricted）：connect / bind / listen 等一律 EPERM，
 * socket 只准 AF_UNIX；ptrace / process_vm_* / io_uring 一律 EPERM（io_uring 能绕过 socket 造 AF_VSOCK）。 */
static int ks_build_restricted_filter(struct sock_filter *out, size_t cap, size_t *out_len)
{
    struct sock_filter insns[KS_MAX_INSNS];
    size_t n = 0;

    KS_EMIT_LD(offsetof(struct seccomp_data, arch));
    KS_EMIT_JUMP(KS_AUDIT_ARCH, 1, 0);
    KS_EMIT_RET(SECCOMP_RET_KILL_PROCESS);
    KS_EMIT_LD(offsetof(struct seccomp_data, nr));

#ifdef SYS_ptrace
    KS_DENY_NR(SYS_ptrace);
#endif
#ifdef SYS_process_vm_readv
    KS_DENY_NR(SYS_process_vm_readv);
#endif
#ifdef SYS_process_vm_writev
    KS_DENY_NR(SYS_process_vm_writev);
#endif
#ifdef SYS_io_uring_setup
    KS_DENY_NR(SYS_io_uring_setup);
    KS_DENY_NR(SYS_io_uring_enter);
    KS_DENY_NR(SYS_io_uring_register);
#endif
    KS_DENY_NR(SYS_connect);
    KS_DENY_NR(SYS_accept);
    KS_DENY_NR(SYS_accept4);
    KS_DENY_NR(SYS_bind);
    KS_DENY_NR(SYS_listen);
    KS_DENY_NR(SYS_getpeername);
    KS_DENY_NR(SYS_getsockname);
    KS_DENY_NR(SYS_shutdown);
    KS_DENY_NR(SYS_sendto);
    KS_DENY_NR(SYS_sendmmsg);
    KS_DENY_NR(SYS_recvmmsg);
    KS_DENY_NR(SYS_getsockopt);
    KS_DENY_NR(SYS_setsockopt);

    /* socket / socketpair：domain 不是 AF_UNIX 就 EPERM
     *   [i+0] JEQ nr            （不是 => 跳 4 条到下一段）
     *   [i+1] LD  args[0]
     *   [i+2] JEQ AF_UNIX       （是 => 跳 1 条，落到下一段）
     *   [i+3] RET EPERM */
    const uint8_t socket_block = 3;
    KS_EMIT_JUMP(SYS_socket, 0, socket_block);
    KS_EMIT_LD(offsetof(struct seccomp_data, args[0]));
    KS_EMIT_JUMP(AF_UNIX, 1, 0);
    KS_EMIT_RET(KS_RET_ERRNO);

    KS_EMIT_JUMP(SYS_socketpair, 0, socket_block);
    KS_EMIT_LD(offsetof(struct seccomp_data, args[0]));
    KS_EMIT_JUMP(AF_UNIX, 1, 0);
    KS_EMIT_RET(KS_RET_ERRNO);

    KS_EMIT_RET(SECCOMP_RET_ALLOW);

    if (n > cap) return -E2BIG;
    memcpy(out, insns, n * sizeof(struct sock_filter));
    *out_len = n;
    return 0;
}

static int ks_install_seccomp(int mode)
{
    if (mode == KS_NET_NONE) return 0;
    if (mode != KS_NET_RESTRICTED) return -ENOTSUP;

    struct sock_filter insns[KS_MAX_INSNS];
    size_t len = 0;
    int rc = ks_build_restricted_filter(insns, KS_MAX_INSNS, &len);
    if (rc < 0) return rc;

    struct sock_fprog prog;
    memset(&prog, 0, sizeof(prog));
    prog.len = (unsigned short)len;
    prog.filter = insns;

#ifdef SYS_seccomp
    if (syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &prog) != 0) return -errno;
#else
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) != 0) return -errno;
#endif
    return 0;
}

/* bwrap --seccomp 认的格式：sock_fprog（u16 len + 过滤器数组） */
static int ks_write_seccomp_bpf(int fd, int mode)
{
    if (mode != KS_NET_RESTRICTED) return -ENOTSUP;
    struct sock_filter insns[KS_MAX_INSNS];
    size_t len = 0;
    int rc = ks_build_restricted_filter(insns, KS_MAX_INSNS, &len);
    if (rc < 0) return rc;

    struct sock_fprog prog;
    memset(&prog, 0, sizeof(prog));
    prog.len = (unsigned short)len;
    prog.filter = insns;
    size_t total = sizeof(prog) + len * sizeof(struct sock_filter);
    unsigned char *buf = (unsigned char *)malloc(total);
    if (!buf) return -ENOMEM;
    memcpy(buf, &prog, sizeof(prog));
    memcpy(buf + sizeof(prog), insns, len * sizeof(struct sock_filter));
    size_t off = 0;
    while (off < total) {
        ssize_t w = write(fd, buf + off, total - off);
        if (w < 0) {
            if (errno == EINTR) continue;
            free(buf);
            return -errno;
        }
        off += (size_t)w;
    }
    free(buf);
    return 0;
}

/* ── 对外入口 ───────────────────────────────────────────────────────────────────── */

/* Landlock ABI 版本（>=1 可用）；不支持 => 0。 */
KS_EXPORT int ks_probe(void)
{
    int abi = (int)syscall(KS_LL_CREATE_RULESET, NULL, 0, LANDLOCK_CREATE_RULESET_VERSION);
    if (abi < 0) return 0;
    return abi;
}

/* 对当前进程套用策略：Landlock -> seccomp。0 成功，负数是 -errno。 */
KS_EXPORT int ks_apply(const void *blob, size_t len)
{
    struct ks_rule rules[KS_MAX_RULES];
    size_t count = 0;
    uint32_t net_mode = KS_NET_NONE, fs_enabled = 0;
    int rc = ks_parse_blob((const unsigned char *)blob, len, rules, &count, &net_mode, &fs_enabled);
    if (rc < 0) return rc;

    /* no_new_privs 先设：Landlock 的 restrict_self 要求它（除非有 CAP_SYS_ADMIN），
     * seccomp 也要求它。 */
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) return -errno;

    if (fs_enabled) {
        rc = ks_apply_landlock(rules, count);
        if (rc < 0) return rc;
    }
    rc = ks_install_seccomp((int)net_mode);
    if (rc < 0) return rc;
    return 0;
}

/* fork -> 套用策略 -> execve；返回子进程 pid（父进程），失败返回 -errno。 */
KS_EXPORT pid_t ks_spawn(char *const argv[], char *const envp[], const void *blob, size_t len, int in_fd, int out_fd, int err_fd)
{
    if (!argv || !argv[0]) return -EINVAL;
    pid_t pid = fork();
    if (pid < 0) return -errno;
    if (pid == 0) {
        if (in_fd >= 0 && in_fd != 0) {
            if (dup2(in_fd, 0) < 0) _exit(127);
            close(in_fd);
        }
        if (out_fd >= 0 && out_fd != 1) {
            if (dup2(out_fd, 1) < 0) _exit(127);
            close(out_fd);
        }
        if (err_fd >= 0 && err_fd != 2) {
            if (dup2(err_fd, 2) < 0) _exit(127);
            close(err_fd);
        }
        int rc = ks_apply(blob, len);
        if (rc < 0) _exit(127);
        execve(argv[0], argv, envp);
        _exit(127);
    }
    return pid;
}

/* 把 seccomp 过滤器写到 fd（给 bwrap --seccomp 用）。 */
KS_EXPORT int ks_write_seccomp(int fd, int mode)
{
    return ks_write_seccomp_bpf(fd, mode);
}

/* ── netns 代理桥（A-49）───────────────────────────────────────────────────────────
 * 目标进程已在 bwrap 的空 netns 里时，LD_PRELOAD 构造函数建一个只听 127.0.0.1 的监听 fd，
 * 经宿主预先放进沙盒的 AF_UNIX 控制 socket 用 SCM_RIGHTS 交给宿主；宿主把该 fd 当作代理入口。
 * 宿主 ack 后才继续，并把这些进程的代理变量改写成本地入口（⛔ 泄漏宿主端口）。
 */

#define KS_BRIDGE_ENV "KS_PROXY_BRIDGE_SOCKET"

static int ks_bridge_create_listener(void)
{
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -errno;
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = 0;
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0 ||
        listen(fd, 64) < 0) {
        int e = -errno;
        close(fd);
        return e;
    }
    return fd;
}

static int ks_bridge_send_fd(int control, int fd)
{
    char trigger = 'K';
    struct iovec iov = { .iov_base = &trigger, .iov_len = 1 };
    union {
        struct cmsghdr header;
        unsigned char bytes[CMSG_SPACE(sizeof(int))];
    } ancillary;
    memset(&ancillary, 0, sizeof(ancillary));
    struct msghdr message;
    memset(&message, 0, sizeof(message));
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    message.msg_control = ancillary.bytes;
    message.msg_controllen = sizeof(ancillary.bytes);
    struct cmsghdr *header = CMSG_FIRSTHDR(&message);
    header->cmsg_level = SOL_SOCKET;
    header->cmsg_type = SCM_RIGHTS;
    header->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(header), &fd, sizeof(fd));
    if (sendmsg(control, &message, 0) < 0) return -errno;
    return 0;
}

static int ks_bridge_wait_ack(int control)
{
    char ack = 0;
    for (;;) {
        ssize_t n = recv(control, &ack, 1, 0);
        if (n == 1) return ack == 'A' ? 0 : -EPROTO;
        if (n == 0) return -ECONNRESET;
        if (errno != EINTR) return -errno;
    }
}

static int ks_bridge_activate(void)
{
    /* bwrap 自己也会吃 LD_PRELOAD，但那时还没进 netns；宿主传 bwrap 的 exe 路径，
     * 用 dev+inode 识别这一层并跳过，等它 exec 的目标进程再激活。 */
    const char *skip_path = getenv("KS_PROXY_BRIDGE_SKIP_EXE");
    if (skip_path && *skip_path) {
        struct stat self_exe, skip_exe;
        if (stat("/proc/self/exe", &self_exe) == 0 && stat(skip_path, &skip_exe) == 0 &&
            self_exe.st_dev == skip_exe.st_dev && self_exe.st_ino == skip_exe.st_ino) {
            return 0;
        }
    }
    const char *path = getenv(KS_BRIDGE_ENV);
    if (!path || !*path) return 0;

    int control = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (control < 0) return -errno;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    if (strlen(path) >= sizeof(addr.sun_path)) {
        close(control);
        return -ENAMETOOLONG;
    }
    memcpy(addr.sun_path, path, strlen(path) + 1);
    if (connect(control, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        int e = -errno;
        close(control);
        return e;
    }

    int listener = ks_bridge_create_listener();
    if (listener < 0) {
        close(control);
        return listener;
    }
    int rc = ks_bridge_send_fd(control, listener);
    if (rc == 0) rc = ks_bridge_wait_ack(control);
    close(control);
    if (rc < 0) {
        close(listener);
        return rc;
    }

    struct sockaddr_in bound;
    socklen_t bound_len = sizeof(bound);
    if (getsockname(listener, (struct sockaddr *)&bound, &bound_len) < 0) {
        rc = -errno;
        close(listener);
        return rc;
    }
    char proxy[64];
    int n = snprintf(proxy, sizeof(proxy), "http://127.0.0.1:%d", (int)ntohs(bound.sin_port));
    if (n <= 0 || n >= (int)sizeof(proxy)) {
        close(listener);
        return -EOVERFLOW;
    }
    /* 大小写两种拼法都覆盖：许多程序只读其中一套。NO_PROXY 置空，避免本地桥被排除。 */
    setenv("HTTP_PROXY", proxy, 1);
    setenv("http_proxy", proxy, 1);
    setenv("HTTPS_PROXY", proxy, 1);
    setenv("https_proxy", proxy, 1);
    setenv("ALL_PROXY", proxy, 1);
    setenv("all_proxy", proxy, 1);
    setenv("NO_PROXY", "", 1);
    setenv("no_proxy", "", 1);
    return 1;
}

/* 宿主侧：从已 accept 的控制连接收一个监听 fd。返回非负 fd（归宿主管）；负数是 -errno。 */
KS_EXPORT int ks_bridge_receive_listener(int control_fd)
{
    if (control_fd < 0) return -EBADF;
    char trigger = 0;
    struct iovec iov = { .iov_base = &trigger, .iov_len = 1 };
    union {
        struct cmsghdr header;
        unsigned char bytes[CMSG_SPACE(sizeof(int))];
    } ancillary;
    struct msghdr message;
    memset(&message, 0, sizeof(message));
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    message.msg_control = ancillary.bytes;
    message.msg_controllen = sizeof(ancillary.bytes);
    ssize_t n;
    do {
        n = recvmsg(control_fd, &message, MSG_CMSG_CLOEXEC);
    } while (n < 0 && errno == EINTR);
    if (n < 0) return -errno;
    if (n != 1) return -EPROTO;
    struct cmsghdr *header = CMSG_FIRSTHDR(&message);
    if (!header || header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS ||
        header->cmsg_len < CMSG_LEN(sizeof(int)) || (message.msg_flags & MSG_CTRUNC)) {
        return -EPROTO;
    }
    int fd;
    memcpy(&fd, CMSG_DATA(header), sizeof(fd));
    if (fd < 0) return -EPROTO;

    /* 只收 AF_INET / TCP / 已经 listen 的 IPv4 回环 fd，避免控制面变成任意 fd 注入。 */
    int domain = 0, type = 0, protocol = 0, listening = 0;
    socklen_t len = sizeof(domain);
    if (getsockopt(fd, SOL_SOCKET, SO_DOMAIN, &domain, &len) < 0 || domain != AF_INET) {
        close(fd);
        return -EPROTO;
    }
    len = sizeof(type);
    if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &len) < 0 || type != SOCK_STREAM) {
        close(fd);
        return -EPROTO;
    }
    len = sizeof(protocol);
    if (getsockopt(fd, SOL_SOCKET, SO_PROTOCOL, &protocol, &len) < 0 || protocol != IPPROTO_TCP) {
        close(fd);
        return -EPROTO;
    }
    len = sizeof(listening);
    if (getsockopt(fd, SOL_SOCKET, SO_ACCEPTCONN, &listening, &len) < 0 || listening == 0) {
        close(fd);
        return -EPROTO;
    }
    struct sockaddr_in local;
    len = sizeof(local);
    if (getsockname(fd, (struct sockaddr *)&local, &len) < 0 ||
        local.sin_family != AF_INET || local.sin_addr.s_addr != htonl(INADDR_LOOPBACK) ||
        local.sin_port == 0) {
        close(fd);
        return -EPROTO;
    }
    /* 收下就不再让子进程 exec 时带走；宿主负责 close。 */
    int flags = fcntl(fd, F_GETFD, 0);
    if (flags >= 0) (void)fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
    return fd;
}

/* ── LD_PRELOAD 构造函数 ─────────────────────────────────────────────────────────── */

/* 策略也可以直接放在环境变量里（hex: 前缀）—— 沙盒里（bwrap 的挂载命名空间）未必看得到宿主的临时目录。 */
static int ks_hex_decode(const char *hex, unsigned char **out, size_t *out_len)
{
    size_t n = strlen(hex);
    if (n % 2 != 0) return -EINVAL;
    size_t len = n / 2;
    unsigned char *buf = (unsigned char *)malloc(len ? len : 1);
    if (!buf) return -ENOMEM;
    for (size_t i = 0; i < len; i++) {
        int hi = -1, lo = -1;
        char c = hex[2 * i], d = hex[2 * i + 1];
        if (c >= '0' && c <= '9') hi = c - '0';
        else if (c >= 'a' && c <= 'f') hi = c - 'a' + 10;
        else if (c >= 'A' && c <= 'F') hi = c - 'A' + 10;
        if (d >= '0' && d <= '9') lo = d - '0';
        else if (d >= 'a' && d <= 'f') lo = d - 'a' + 10;
        else if (d >= 'A' && d <= 'F') lo = d - 'A' + 10;
        if (hi < 0 || lo < 0) {
            free(buf);
            return -EINVAL;
        }
        buf[i] = (unsigned char)((hi << 4) | lo);
    }
    *out = buf;
    *out_len = len;
    return 0;
}

__attribute__((constructor)) static void ks_sandbox_preload(void)
{
    /* 桥要在任何 seccomp / Landlock 前完成；bwrap 的 netns 已阻断直连，这是唯一的网络门。 */
    int bridge = ks_bridge_activate();
    if (bridge < 0) _exit(127);

    const char *policy = getenv("KS_SANDBOX_POLICY");
    if (!policy || !*policy) return;

    unsigned char *blob = NULL;
    size_t len = 0;
    int rc;
    if (strncmp(policy, "hex:", 4) == 0) {
        rc = ks_hex_decode(policy + 4, &blob, &len);
    } else {
        rc = ks_read_file(policy, &blob, &len);
    }
    if (rc < 0) _exit(127);
    /* 有桥时网络由空 netns + 回环代理管：不要再装“禁 connect”的 seccomp。
     * Landlock 文件策略保持不变；只把 blob 里的 net_mode 改成 NONE（小端 u32，偏移 8）。 */
    if (bridge != 0) memset((unsigned char *)blob + 8, 0, 4);
    rc = ks_apply(blob, len);
    free(blob);
    if (rc < 0) _exit(127);
}
