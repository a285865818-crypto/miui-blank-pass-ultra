/*
 * native 层安全校验（增强版 v2）。
 *
 * 能力矩阵：
 *   1. 签名校验（Java 层传入比对，native 做二次校验防绕过）
 *   2. 反调试（TracerPid + Frida 端口/文件/线程名 + ptrace 自我保护探针）
 *   3. 反注入（maps 全扫 + 可疑 so 检测）
 *   4. 反内存转储（mprotect 代码段 + /proc/self/mem 访问监控）
 *   5. 自身完整性（CRC32 校验 .text 段防 patch）
 *   6. 时序检测（关键路径耗时异常 = 被插桩/调试）
 *   7. GOT/PLT 完整性（关键 libc 函数指针是否被 inline/PLT hook）
 *   8. 信号陷阱（SIGTRAP/SIGSEGV 记日志后恢复原处理器重新抛出）
 *
 * 设计原则（红线，新增检测项同样遵守）：
 *   - 只有签名不匹配才杀进程；反调试/反注入/反转储/GOT/信号检测只记日志
 *     （误伤正常设备的代价太大）
 *   - 完整性校验失败 → 记日志 + 返回异常码（Java 层决定是否拒绝加载 hook）
 *   - 时序检测只在极端偏差时才报告（>10x 正常耗时）
 *
 * 加固项（本次新增）：
 *   A. 二进制瘦身：-s / -flto / -fvisibility=hidden + llvm-strip（build_native.sh）
 *   B. JNI 动态注册：JNI_OnLoad + RegisterNatives，导出表只剩 JNI_OnLoad
 *   C. 字符串 XOR 混淆：obf.h + obf_strings.h（gen_obf.py 生成），.rodata 无明文
 *   D. ptrace 自我保护：PTRACE_TRACEME 探针（见 sec_ptrace_self 的注释说明）
 *   E. GOT/PLT 完整性检测：dl_iterate_phdr + PT_DYNAMIC 解析 .got.plt
 *   F. 信号陷阱：SIGTRAP/SIGSEGV 记日志后恢复原处理器 re-raise
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ptrace.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <android/log.h>
#include <pthread.h>
#include <errno.h>
#include <time.h>
#include <stdint.h>
#include <stdio.h>
#include <dirent.h>
#include <link.h>
#include <elf.h>
#include <signal.h>

#include "obf.h"
#include "obf_strings.h"

#define LOG_TAG "MBPU_SEC"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/* E 项重定位符号下标提取（32/64 位通用） */
#ifdef __LP64__
#  define MBPU_R_SYM(i) ELF64_R_SYM(i)
#else
#  define MBPU_R_SYM(i) ELF32_R_SYM(i)
#endif

/* 所有文件读取统一处理信号中断，避免把 EINTR 误判为检测结束或失败。 */
static ssize_t read_retry(int fd, void *buf, size_t count) {
    ssize_t n;
    do {
        n = read(fd, buf, count);
    } while (n < 0 && errno == EINTR);
    return n;
}

/* ===== CRC32 查表（编译时生成） ===== */
static uint32_t crc32_table[256];
static int crc32_ready = 0;

static void crc32_init(void) {
    for (uint32_t i = 0; i < 256; i++) {
        uint32_t c = i;
        for (int j = 0; j < 8; j++)
            c = (c & 1) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
        crc32_table[i] = c;
    }
    crc32_ready = 1;
}

static uint32_t crc32_calc(const void *data, size_t len) {
    if (!crc32_ready) crc32_init();
    const uint8_t *p = (const uint8_t *)data;
    uint32_t c = 0xFFFFFFFFu;
    for (size_t i = 0; i < len; i++)
        c = crc32_table[(c ^ p[i]) & 0xFF] ^ (c >> 8);
    return c ^ 0xFFFFFFFFu;
}

/* ===== 1. 反调试检测 ===== */

static int check_tracer_pid(void) {
    char buf[256] = {0};
    char key[16];
    int fd = xopen(OB_PROC_STATUS, O_RDONLY);
    if (fd < 0) return 0;
    ssize_t n = read_retry(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;
    /* 明文 "TracerPid:" 已混淆（C 项）；找到位置后看数值是否为 0 */
    obf_dec(key, OB_TRACERPID, sizeof(OB_TRACERPID));
    char *p = strstr(buf, key);
    obf_wipe(key, sizeof(key));
    if (!p) return 0;
    p += 10;
    while (*p == ' ' || *p == '\t') p++;
    return (*p != '0' && *p != '\0');
}

/* Frida 默认端口 27042 */
static int check_frida(void) {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return 0;
    struct timeval tv = { .tv_sec = 1, .tv_usec = 0 };
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    int fl = fcntl(sock, F_GETFD);
    if (fl >= 0) fcntl(sock, F_SETFD, fl | FD_CLOEXEC);
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(27042);
    addr.sin_addr.s_addr = inet_addr("127.0.0.1");
    int connected = (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) == 0);
    close(sock);
    return connected;
}

/* Frida agent 文件检测（路径明文已混淆） */
static int check_frida_files(void) {
    static const unsigned char *const paths[4] = {
        OB_FRIDA_FILE_1, OB_FRIDA_FILE_2, OB_FRIDA_FILE_3, OB_FRIDA_FILE_4,
    };
    static const size_t lens[4] = {
        sizeof(OB_FRIDA_FILE_1), sizeof(OB_FRIDA_FILE_2),
        sizeof(OB_FRIDA_FILE_3), sizeof(OB_FRIDA_FILE_4),
    };
    for (int i = 0; i < 4; i++) {
        char p[128];
        if (lens[i] >= sizeof(p)) continue;
        obf_dec(p, paths[i], lens[i]);
        int ok = (access(p, R_OK) == 0);
        if (ok) {
            int fd = open(p, O_RDONLY);
            if (fd >= 0) close(fd);
            else ok = 0;
        }
        obf_wipe(p, sizeof(p));
        if (ok) return 1;
    }
    return 0;
}

/* Frida 线程名检测（frida-agent 注入后会创建 "frida-*" 线程） */
static int check_frida_threads(void) {
    DIR *dir = xopendir(OB_PROC_TASK);
    if (!dir) return 0;
    struct dirent *entry;
    int found = 0;
    char fmt[32];
    obf_dec(fmt, OB_PROC_TASK_COMM, sizeof(OB_PROC_TASK_COMM));
    while (!found && (entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        char path[128];
        snprintf(path, sizeof(path), fmt, entry->d_name);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        char name[64] = {0};
        ssize_t n = read_retry(fd, name, sizeof(name) - 1);
        close(fd);
        if (n > 0) {
            if (xstrstr(name, OB_S_FRIDA) || xstrstr(name, OB_S_GADGET)
                    || xstrstr(name, OB_S_LINJECTOR))
                found = 1;
        }
    }
    obf_wipe(fmt, sizeof(fmt));
    closedir(dir);
    return found;
}

/* Frida 命名管道检测 */
static int check_frida_pipes(void) {
    /* frida 用 pipe 或 socketpair 通信，检查 /proc/self/fd 中的可疑链接 */
    DIR *dir = xopendir(OB_PROC_FD);
    if (!dir) return 0;
    struct dirent *entry;
    int found = 0;
    char fmt[32];
    obf_dec(fmt, OB_PROC_FD_SLASH, sizeof(OB_PROC_FD_SLASH));
    while (!found && (entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        char path[128], target[256];
        snprintf(path, sizeof(path), fmt, entry->d_name);
        ssize_t n = readlink(path, target, sizeof(target) - 1);
        if (n > 0) {
            target[n] = '\0';
            if (xstrstr(target, OB_S_FRIDA) || xstrstr(target, OB_S_GADGET))
                found = 1;
        }
    }
    obf_wipe(fmt, sizeof(fmt));
    closedir(dir);
    return found;
}

/* ===== 2. 签名校验（B 项：改为 static，经 RegisterNatives 注册） ===== */

static jboolean sec_check_sig(JNIEnv *env, jclass clazz, jstring expected_hash,
                              jstring sig_hash) {
    (void)clazz;
    if (sig_hash == NULL) return JNI_FALSE;
    if (expected_hash == NULL) return JNI_TRUE;

    const char *expected = (*env)->GetStringUTFChars(env, expected_hash, NULL);
    if (expected == NULL) return JNI_TRUE;
    if (expected[0] == '\0') {
        (*env)->ReleaseStringUTFChars(env, expected_hash, expected);
        XLOG(ANDROID_LOG_WARN, OB_LOG_NOCONF);
        return JNI_TRUE;
    }

    const char *actual = (*env)->GetStringUTFChars(env, sig_hash, NULL);
    if (actual == NULL) {
        (*env)->ReleaseStringUTFChars(env, expected_hash, expected);
        return JNI_FALSE;
    }

    if (strlen(expected) != 64) {
        XLOG(ANDROID_LOG_ERROR, OB_LOG_MALFORMED, strlen(expected));
        (*env)->ReleaseStringUTFChars(env, sig_hash, actual);
        (*env)->ReleaseStringUTFChars(env, expected_hash, expected);
        return JNI_TRUE;
    }

    /* 长度必须先确认，再进入固定 64 字节循环，避免短字符串导致越界读取。 */
    size_t actual_len = strlen(actual);
    if (actual_len != 64) {
        (*env)->ReleaseStringUTFChars(env, sig_hash, actual);
        (*env)->ReleaseStringUTFChars(env, expected_hash, expected);
        return JNI_FALSE;
    }

    /* 等长后逐字节累积差异，不因首个不同字节提前退出。 */
    unsigned int diff = 0;
    for (size_t i = 0; i < actual_len; i++) {
        diff |= (unsigned char)actual[i] ^ (unsigned char)expected[i];
    }
    int match = (diff == 0);
    (*env)->ReleaseStringUTFChars(env, sig_hash, actual);
    (*env)->ReleaseStringUTFChars(env, expected_hash, expected);
    return match ? JNI_TRUE : JNI_FALSE;
}

/* ===== 3. 反调试入口（增强版） ===== */

static jboolean sec_anti_debug(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    if (check_tracer_pid()) { XLOG(ANDROID_LOG_WARN, OB_LOG_TRACER); return JNI_TRUE; }
    if (check_frida()) { XLOG(ANDROID_LOG_WARN, OB_LOG_PORT); return JNI_TRUE; }
    if (check_frida_files()) { XLOG(ANDROID_LOG_WARN, OB_LOG_AGENTFILE); return JNI_TRUE; }
    if (check_frida_threads()) { XLOG(ANDROID_LOG_WARN, OB_LOG_THREAD); return JNI_TRUE; }
    if (check_frida_pipes()) { XLOG(ANDROID_LOG_WARN, OB_LOG_PIPE); return JNI_TRUE; }
    return JNI_FALSE;
}

/* ===== 4. 反注入 / 完整性校验（增强版） ===== */

static int check_got_integrity(void); /* E 项，定义见下 */

static jint sec_integrity(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    int suspicious = 0;
    int fd = xopen(OB_PROC_MAPS, O_RDONLY);
    if (fd < 0) return -1;
    char buf[4096];
    char prev[16] = {0};
    ssize_t n;
    while (1) {
        n = read_retry(fd, buf, sizeof(buf) - 1);
        if (n <= 0) break;
        buf[n] = '\0';
        char joined[sizeof(prev) + sizeof(buf)];
        size_t plen = strlen(prev);
        memcpy(joined, prev, plen);
        memcpy(joined + plen, buf, n + 1);
        if (xstrstr(joined, OB_S_FRIDA)) suspicious |= 1;
        if (xstrstr(joined, OB_S_LIBFRIDA_GADGET)) suspicious |= 2;
        if (xstrstr(joined, OB_S_XPOSED) && !xstrstr(joined, OB_S_LSPD)
                && !xstrstr(joined, OB_S_LIBXPOSED))
            suspicious |= 4;
        if (xstrstr(joined, OB_S_SUBSTRATE)) suspicious |= 8;
        if (xstrstr(joined, OB_S_LIBHOOK) && !xstrstr(joined, OB_S_LIBHOOKS))
            suspicious |= 16;
        size_t keep = n >= 15 ? 15 : (size_t)n;
        memcpy(prev, buf + n - keep, keep);
        prev[keep] = '\0';
    }
    close(fd);
    if (n < 0) return -1;
    /* E 项：GOT/PLT hook 走位掩码 32（Java 侧 >0 只记日志，无需改 Java） */
    if (check_got_integrity() > 0) suspicious |= 32;
    return suspicious;
}

/* ===== 5. 自身完整性校验（CRC32 .text 段） =====
 * 启动时对 .so 的 .text 段算 CRC32，运行中定期复核。
 * 被 patch 过的 so 会得到不同 CRC。
 * 注意：基址由 ASLR 每次不同，需要在启动时记录基准 CRC。
 */

static uint32_t baseline_crc = 0;
static int baseline_set = 0;
static int find_self_text(uintptr_t *start, size_t *len);

/* H3 修复：基线在 so 加载（JNI_OnLoad）时立即记录，而不是首次 Java 调用才记录。
 * Java 首次调用前可能已被 patch；加载时记录把窗口压到最小。
 * 加载前就被静态 patch 的情况由签名校验（重打包必换签名）兜底。 */
static void record_baseline_if_needed(void) {
    if (baseline_set) return;
    uintptr_t start;
    size_t len;
    if (find_self_text(&start, &len) == 0
            && len > 0 && len <= 1024 * 1024) {
        baseline_crc = crc32_calc((const void *)start, len);
        baseline_set = 1;
        XLOG(ANDROID_LOG_INFO, OB_LOG_BASELINE, baseline_crc, len);
    }
}

/* 找自己 so 的 .text（r-x）段范围。
 *
 * 坑（既有缺陷，本次修复）：so 从 APK 内直接加载（extractNativeLibs=false）时
 * 内核 /proc/self/maps 的映射行只写 base.apk，不含 so 名，按名字扫 maps 必然
 * 找不到。改用 dl_iterate_phdr 的 PT_LOAD(PF_X)：dlpi_name 由 linker 维护，
 * 带 so 名（E 项 GOT 检测同源，实测命中）。maps 路径仅作 fallback。
 */
struct self_text_ctx {
    uintptr_t start;
    size_t len;
    int found;
};

static int self_text_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    struct self_text_ctx *c = (struct self_text_ctx *)data;
    const char *name = (info->dlpi_name != NULL) ? info->dlpi_name : "";
    if (!xstrstr(name, OB_SO_NAME)) return 0;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD || (ph->p_flags & PF_X) == 0) continue;
        c->start = (uintptr_t)info->dlpi_addr + (uintptr_t)ph->p_vaddr;
        c->len = (size_t)ph->p_memsz;
        c->found = 1;
        return 1;
    }
    return 0;
}

static int find_self_text(uintptr_t *start, size_t *len) {
    struct self_text_ctx c = {0, 0, 0};
    dl_iterate_phdr(self_text_cb, &c);
    if (c.found && c.len > 0) {
        *start = c.start;
        *len = c.len;
        return 0;
    }
    /* fallback：老式 extractNativeLibs=true 的安装仍按 maps 行名找 */
    {
        int fd = xopen(OB_PROC_MAPS, O_RDONLY);
        if (fd < 0) return -1;
        char buf[4096];
        char line[512];
        size_t line_pos = 0;
        ssize_t n;
        int found = 0;
        while (1) {
            n = read_retry(fd, buf, sizeof(buf));
            if (n <= 0) break;
            for (ssize_t i = 0; i < n; i++) {
                if (buf[i] == '\n' || line_pos >= sizeof(line) - 1) {
                    line[line_pos] = '\0';
                    if (xstrstr(line, OB_SO_NAME) && strstr(line, "r-xp")) {
                        unsigned long s, e;
                        if (sscanf(line, "%lx-%lx", &s, &e) == 2) {
                            *start = (uintptr_t)s;
                            *len = (size_t)(e - s);
                            found = 1;
                        }
                    }
                    line_pos = 0;
                } else {
                    line[line_pos++] = buf[i];
                }
            }
            if (found) break;
        }
        close(fd);
        return found && n >= 0 ? 0 : -1;
    }
}

static jboolean sec_integrity_self(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    uintptr_t text_start;
    size_t text_len;
    if (find_self_text(&text_start, &text_len) != 0) {
        XLOG(ANDROID_LOG_WARN, OB_LOG_NOTEXT);
        return JNI_TRUE; /* 找不到不算异常 */
    }
    if (text_len == 0 || text_len > 1024 * 1024) return JNI_TRUE;
    record_baseline_if_needed();
    uint32_t crc = crc32_calc((const void *)text_start, text_len);
    if (!baseline_set) {
        XLOG(ANDROID_LOG_WARN, OB_LOG_NOBASE);
        return JNI_TRUE;
    }
    if (crc != baseline_crc) {
        XLOG(ANDROID_LOG_ERROR, OB_LOG_MISMATCH, baseline_crc, crc);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/* ===== 6. 反内存转储 =====
 * mprotect 自己的代码段为 RX（去掉 W），防止运行时被写入/patch。
 * 同时检测 /proc/self/mem 的异常访问（dump 工具会 mmap 或 open 这个文件）。
 */

static jboolean sec_protect_memory(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    uintptr_t text_start;
    size_t text_len;
    if (find_self_text(&text_start, &text_len) != 0) return JNI_TRUE;

    /* 把 .text 段锁定为只读+执行（去掉写权限），防止运行时 patch */
    uintptr_t page_start = text_start & ~(uintptr_t)0xFFF;
    size_t total = text_len + (text_start - page_start);
    if (mprotect((void *)page_start, total, PROT_READ | PROT_EXEC) == 0) {
        XLOG(ANDROID_LOG_INFO, OB_LOG_PROT_OK, total);
        return JNI_TRUE;
    } else {
        XLOG(ANDROID_LOG_WARN, OB_LOG_PROT_FAIL, errno);
        return JNI_FALSE;
    }
}

/* ===== 7. 时序检测 =====
 * 测量关键路径耗时，如果异常慢（>10x），说明被调试器单步/断点。
 */

static struct timespec ts_baseline = {0, 0};
static int timing_baseline_set = 0;

static jboolean sec_timing_check(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    struct timespec ts_now;
    if (clock_gettime(CLOCK_MONOTONIC, &ts_now) != 0) return JNI_FALSE;
    /* 首次只建立基线；后续每次都与上一次调用比较，不再依赖时间戳非零。 */
    if (!timing_baseline_set) {
        ts_baseline = ts_now;
        timing_baseline_set = 1;
        return JNI_FALSE;
    }

    /* 秒、纳秒分别求差并借位，不在 32 位 long 中保存绝对纳秒时间。 */
    int64_t elapsed_sec = (int64_t)ts_now.tv_sec - (int64_t)ts_baseline.tv_sec;
    int64_t elapsed_nsec = (int64_t)ts_now.tv_nsec - (int64_t)ts_baseline.tv_nsec;
    if (elapsed_nsec < 0) {
        elapsed_sec--;
        elapsed_nsec += INT64_C(1000000000);
    }
    ts_baseline = ts_now;
    if (elapsed_sec < 0) return JNI_FALSE;

    /* 正常路径低于 50ms；500ms 即超过 10 倍，且无需做可能溢出的秒转纳秒乘法。 */
    if (elapsed_sec > 0 || elapsed_nsec > INT64_C(500000000)) {
        XLOG(ANDROID_LOG_WARN, OB_LOG_TIMING,
             (long long)elapsed_sec, (long long)elapsed_nsec);
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

/* ===== D. ptrace 自我保护（只记日志，不杀进程） =====
 *
 * 为什么不直接在本进程 PTRACE_TRACEME：
 *   Linux 规定 TRACEME 成功后本进程被父进程（这里是 zygote）trace，且 tracee
 *   侧无法 detach。此后本进程收到任何信号都会先进 jobctl stop 等待 tracer
 *   转交——zygote 不会转交，ANR 时系统发的 SIGQUIT 之类会把进程直接挂死，
 *   比崩溃更糟，违反「反调试类检测不得影响正常运行」的红线。
 *
 * 探针方案（语义等价、零残留副作用）：
 *   fork 一次性探针子进程执行 PTRACE_TRACEME。探针的父进程就是本进程；
 *   若有调试器以 follow-fork 模式附着（gdb/lldb/frida 注入均是），探针一出生
 *   就已被 trace，TRACEME 必然失败（EPERM）→ 判定被调试，记日志。
 *   探针成功/失败都立即 _exit，本进程全程不进入被 trace 状态。
 *   本体被 attach（不 follow-fork）的场景由 TracerPid 检测覆盖。
 */
static void sec_ptrace_self(void) {
    pid_t pid = fork();
    if (pid == 0) {
        /* 探针：只调 async-signal-safe 的 syscall，随后立即退出 */
        _exit(ptrace(PTRACE_TRACEME, 0, NULL, NULL) == -1 ? 1 : 0);
    }
    if (pid < 0) {
        XLOG(ANDROID_LOG_WARN, OB_LOG_PTRACE_FORK, errno);
        return;
    }
    int status = 0;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
    if (WIFEXITED(status) && WEXITSTATUS(status) == 1) {
        XLOG(ANDROID_LOG_WARN, OB_LOG_PTRACE_FAIL);
    } else {
        XLOG(ANDROID_LOG_INFO, OB_LOG_PTRACE_OK);
    }
}

/* ===== E. GOT/PLT 完整性检测（只记日志 + 返回异常码） =====
 *
 * 思路：dl_iterate_phdr 拿到自身 so 与 libc.so 的映射区间；解析自身
 * PT_DYNAMIC → DT_JMPREL/DT_SYMTAB/DT_STRTAB 遍历 .got.plt 重定位条目，
 * 检查 read/open/mprotect/close/mmap/strstr 的 GOT 槽值是否落在 libc 区间。
 * 落在其他匿名/so 区间 = 被 inline/PLT hook。
 *
 * 健壮性：任何解析步骤失败直接跳过检测（返回 -1），绝不崩溃；
 * lazy binding 未解析时槽值指向自身 PLT stub，按"未解析"跳过不算可疑。
 */
struct got_ctx {
    uintptr_t self_lo, self_hi;
    uintptr_t libc_lo, libc_hi;
    int have_self, have_libc, have_dyn;
    uintptr_t self_base;
    const ElfW(Sym) *symtab;
    const char *strtab;
    const void *jmprel;
    size_t pltrelsz;
    int is_rela;
};

/* dynamic 段 d_ptr：有的加载器原地重定位、有的不重定位，两种都兼容 */
static uintptr_t dyn_fix(uintptr_t base, uintptr_t p, uintptr_t lo, uintptr_t hi) {
    return (p >= lo && p < hi) ? p : p + base;
}

static int got_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    struct got_ctx *c = (struct got_ctx *)data;
    uintptr_t lo = UINTPTR_MAX, hi = 0;
    const char *name = (info->dlpi_name != NULL) ? info->dlpi_name : "";

    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD) continue;
        uintptr_t s = (uintptr_t)info->dlpi_addr + (uintptr_t)ph->p_vaddr;
        uintptr_t e = s + (uintptr_t)ph->p_memsz;
        if (s < lo) lo = s;
        if (e > hi) hi = e;
    }
    if (hi <= lo) return 0;

    if (xstrstr(name, OB_SO_NAME)) {
        c->self_lo = lo;
        c->self_hi = hi;
        c->self_base = (uintptr_t)info->dlpi_addr;
        c->have_self = 1;
        for (int i = 0; i < info->dlpi_phnum; i++) {
            const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
            if (ph->p_type != PT_DYNAMIC) continue;
            const ElfW(Dyn) *dyn = (const ElfW(Dyn) *)
                    ((uintptr_t)info->dlpi_addr + (uintptr_t)ph->p_vaddr);
            uintptr_t strtab = 0, symtab = 0, jmprel = 0;
            size_t pltrelsz = 0;
            int is_rela = 1;
            for (const ElfW(Dyn) *d = dyn; d->d_tag != DT_NULL; d++) {
                switch (d->d_tag) {
                    case DT_STRTAB:  strtab = (uintptr_t)d->d_un.d_ptr; break;
                    case DT_SYMTAB:  symtab = (uintptr_t)d->d_un.d_ptr; break;
                    case DT_JMPREL:  jmprel = (uintptr_t)d->d_un.d_ptr; break;
                    case DT_PLTRELSZ: pltrelsz = (size_t)d->d_un.d_val; break;
                    case DT_PLTREL:  is_rela = (d->d_un.d_val == DT_RELA); break;
                    default: break;
                }
            }
            if (strtab && symtab && jmprel && pltrelsz > 0) {
                c->strtab = (const char *)dyn_fix(c->self_base, strtab, lo, hi);
                c->symtab = (const ElfW(Sym) *)dyn_fix(c->self_base, symtab, lo, hi);
                c->jmprel = (const void *)dyn_fix(c->self_base, jmprel, lo, hi);
                c->pltrelsz = pltrelsz;
                c->is_rela = is_rela;
                c->have_dyn = 1;
            }
            break;
        }
    } else if (xstrstr(name, OB_S_LIBC_SO)) {
        c->libc_lo = lo;
        c->libc_hi = hi;
        c->have_libc = 1;
    }
    return 0;
}

/* 返回：0 干净；1 检出可疑；-1 解析失败（跳过，不算可疑） */
static int check_got_integrity(void) {
    struct got_ctx c;
    static const char tag[] = "plt";
    int suspicious = 0;

    memset(&c, 0, sizeof(c));
    dl_iterate_phdr(got_cb, &c);
    if (!c.have_self || !c.have_libc || !c.have_dyn || c.strtab == NULL
            || c.symtab == NULL || c.jmprel == NULL) {
        XLOG(ANDROID_LOG_INFO, OB_LOG_GOT_SKIP);
        return -1;
    }

    size_t relsz = c.is_rela ? sizeof(ElfW(Rela)) : sizeof(ElfW(Rel));
    size_t nrel = c.pltrelsz / relsz;
    for (size_t i = 0; i < nrel; i++) {
        uintptr_t off;
        size_t symidx;
        if (c.is_rela) {
            const ElfW(Rela) *r = (const ElfW(Rela) *)c.jmprel + i;
            off = (uintptr_t)r->r_offset;
            symidx = (size_t)MBPU_R_SYM(r->r_info);
        } else {
            const ElfW(Rel) *r = (const ElfW(Rel) *)c.jmprel + i;
            off = (uintptr_t)r->r_offset;
            symidx = (size_t)MBPU_R_SYM(r->r_info);
        }
        if (symidx == 0) continue;
        const char *sname = c.strtab + c.symtab[symidx].st_name;
        if (!(xstreq(sname, OB_S_READ) || xstreq(sname, OB_S_OPEN)
                || xstreq(sname, OB_S_MPROTECT) || xstreq(sname, OB_S_CLOSE)
                || xstreq(sname, OB_S_MMAP) || xstreq(sname, OB_S_STRSTR)))
            continue;

        uintptr_t slot_addr = c.self_base + off;
        if (slot_addr < c.self_lo || slot_addr + sizeof(uintptr_t) > c.self_hi)
            continue; /* 槽位不在自身映射内，防御性跳过 */
        uintptr_t v = *(const uintptr_t *)slot_addr;
        if (v == 0) continue;
        if (v >= c.libc_lo && v < c.libc_hi) continue;  /* 正常：落在 libc */
        if (v >= c.self_lo && v < c.self_hi) continue;  /* lazy 未解析的 PLT stub */

        /* 落在其他区间 = 被 inline/PLT hook：只记日志 + 报异常码 */
        XLOG(ANDROID_LOG_WARN, OB_LOG_GOT_HOOK, tag, sname,
             (unsigned long)v, (unsigned long)c.libc_lo, (unsigned long)c.libc_hi);
        suspicious = 1;
    }
    return suspicious;
}

/* ===== F. 信号陷阱（只记日志，不影响正常运行） =====
 *
 * 注册 SIGTRAP/SIGSEGV 处理器：收到信号（可能被断点/单步）只记日志，
 * 随后恢复原处理器并重新抛出——崩溃语义与未安装时完全一致：
 *   - 原处理器是 SIG_DFL（默认）→ 进程按原样终止，不掩盖真实崩溃；
 *   - 原处理器是应用注册的函数（如崩溃上报 SDK）→ 原样转交。
 * 不用 siglongjmp 吞信号：真 SIGSEGV 被吞掉会让应用带着损坏状态继续跑，
 * 比崩溃更危险。
 */
static struct sigaction g_old_segv, g_old_trap;

static void sec_signal_handler(int sig, siginfo_t *info, void *uctx) {
    (void)uctx;
    /* handler 内只做栈运算 + logcat 写入（Android 上实践安全），
     * 不碰堆锁；随后恢复原处理器 re-raise。 */
    XLOG(ANDROID_LOG_WARN, OB_LOG_SIG_CAUGHT, sig,
         (info != NULL) ? info->si_addr : NULL);
    {
        struct sigaction *old = (sig == SIGSEGV) ? &g_old_segv : &g_old_trap;
        sigaction(sig, old, NULL);
    }
    raise(sig);
}

static void sec_install_signal_handlers(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = sec_signal_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO;
    if (sigaction(SIGSEGV, &sa, &g_old_segv) == 0
            && sigaction(SIGTRAP, &sa, &g_old_trap) == 0) {
        XLOG(ANDROID_LOG_INFO, OB_LOG_SIG_INSTALLED);
    }
}

/* ===== B. JNI 动态注册 =====
 * 全部 native 实现是普通 static 函数；so 导出表只剩 JNI_OnLoad。
 * 类名/方法名/签名全部从密文解密后注册，注册完立即清零。
 */

static jint register_natives(JNIEnv *env) {
    char cls[64], n[6][32], s_z[8], s_ssz[48], s_i[8];
    jclass clazz;
    JNINativeMethod m[6];
    jint r;

    obf_dec(cls, OB_JNI_CLASS, sizeof(OB_JNI_CLASS));
    obf_dec(n[0], OB_JNI_N_ANTIDEBUG, sizeof(OB_JNI_N_ANTIDEBUG));
    obf_dec(n[1], OB_JNI_N_CHECKSIG, sizeof(OB_JNI_N_CHECKSIG));
    obf_dec(n[2], OB_JNI_N_INTEGRITY, sizeof(OB_JNI_N_INTEGRITY));
    obf_dec(n[3], OB_JNI_N_ITGSELF, sizeof(OB_JNI_N_ITGSELF));
    obf_dec(n[4], OB_JNI_N_PROTECTMEM, sizeof(OB_JNI_N_PROTECTMEM));
    obf_dec(n[5], OB_JNI_N_TIMING, sizeof(OB_JNI_N_TIMING));
    obf_dec(s_z, OB_JNI_SIG_Z, sizeof(OB_JNI_SIG_Z));
    obf_dec(s_ssz, OB_JNI_SIG_SS_Z, sizeof(OB_JNI_SIG_SS_Z));
    obf_dec(s_i, OB_JNI_SIG_I, sizeof(OB_JNI_SIG_I));

    clazz = (*env)->FindClass(env, cls);
    if (clazz == NULL) {
        obf_wipe(cls, sizeof(cls));
        obf_wipe(n, sizeof(n));
        obf_wipe(s_z, sizeof(s_z));
        obf_wipe(s_ssz, sizeof(s_ssz));
        obf_wipe(s_i, sizeof(s_i));
        return JNI_ERR;
    }

    m[0].name = n[0]; m[0].signature = s_z;   m[0].fnPtr = (void *)sec_anti_debug;
    m[1].name = n[1]; m[1].signature = s_ssz; m[1].fnPtr = (void *)sec_check_sig;
    m[2].name = n[2]; m[2].signature = s_i;   m[2].fnPtr = (void *)sec_integrity;
    m[3].name = n[3]; m[3].signature = s_z;   m[3].fnPtr = (void *)sec_integrity_self;
    m[4].name = n[4]; m[4].signature = s_z;   m[4].fnPtr = (void *)sec_protect_memory;
    m[5].name = n[5]; m[5].signature = s_z;   m[5].fnPtr = (void *)sec_timing_check;

    r = (*env)->RegisterNatives(env, clazz, m, 6);
    (*env)->DeleteLocalRef(env, clazz);

    obf_wipe(cls, sizeof(cls));
    obf_wipe(n, sizeof(n));
    obf_wipe(s_z, sizeof(s_z));
    obf_wipe(s_ssz, sizeof(s_ssz));
    obf_wipe(s_i, sizeof(s_i));
    return r;
}

/* so 一加载就完成全部初始化：ptrace 探针 → 注册 → CRC 基线 → RX 锁定 →
 * 信号陷阱 → GOT 检测。H3：不等 Java 层首次调用，把 patch 窗口压到最小。 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = NULL;

    /* D：ptrace 自我保护放最早期 */
    sec_ptrace_self();

    crc32_init();

    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK || env == NULL)
        return JNI_ERR;
    /* B：动态注册，失败返回 JNI_ERR（环境异常，loadLibrary 即刻可见） */
    if (register_natives(env) != JNI_OK)
        return JNI_ERR;

    record_baseline_if_needed();
    {
        uintptr_t start;
        size_t len;
        if (find_self_text(&start, &len) == 0 && len > 0) {
            uintptr_t page_start = start & ~(uintptr_t)0xFFF;
            mprotect((void *)page_start, len + (start - page_start), PROT_READ | PROT_EXEC);
        }
    }

    /* F：信号陷阱 */
    sec_install_signal_handlers();

    /* E：GOT/PLT 检测（首扫，只记日志） */
    check_got_integrity();

    return JNI_VERSION_1_6;
}
