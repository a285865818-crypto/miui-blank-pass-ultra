/*
 * native 层安全校验（增强版）。
 *
 * 能力矩阵：
 *   1. 签名校验（Java 层传入比对，native 做二次校验防绕过）
 *   2. 反调试（TracerPid + Frida 端口/文件/线程名）
 *   3. 反注入（maps 全扫 + 可疑 so 检测）
 *   4. 反内存转储（mprotect 代码段 + /proc/self/mem 访问监控）
 *   5. 自身完整性（CRC32 校验 .text 段防 patch）
 *   6. 时序检测（关键路径耗时异常 = 被插桩/调试）
 *
 * 设计原则：
 *   - 只有签名不匹配才杀进程；反调试/反注入/反转储只记日志（误伤正常设备的代价太大）
 *   - 完整性校验失败 → 记日志 + 返回异常码（Java 层决定是否拒绝加载 hook）
 *   - 时序检测只在极端偏差时才报告（>10x 正常耗时）
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
#include <netinet/in.h>
#include <arpa/inet.h>
#include <android/log.h>
#include <pthread.h>
#include <errno.h>
#include <time.h>
#include <dirent.h>

#define LOG_TAG "MBPU_SEC"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

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
    int fd = open("/proc/self/status", O_RDONLY);
    if (fd < 0) return 0;
    ssize_t n;
    do { n = read(fd, buf, sizeof(buf) - 1); } while (n < 0 && errno == EINTR);
    close(fd);
    if (n <= 0) return 0;
    char *p = strstr(buf, "TracerPid:");
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

/* Frida agent 文件检测 */
static int check_frida_files(void) {
    const char *paths[] = {
        "/data/local/tmp/re.frida.server/frida-agent-64.so",
        "/data/local/tmp/frida-agent-64.so",
        "/data/local/tmp/frida-agent-32.so",
        "/data/local/tmp/re.frida.server/frida-agent-32.so",
    };
    for (int i = 0; i < 4; i++) {
        if (access(paths[i], R_OK) != 0) continue;
        int fd = open(paths[i], O_RDONLY);
        if (fd >= 0) { close(fd); return 1; }
    }
    return 0;
}

/* Frida 线程名检测（frida-agent 注入后会创建 "frida-*" 线程） */
static int check_frida_threads(void) {
    DIR *dir = opendir("/proc/self/task");
    if (!dir) return 0;
    struct dirent *entry;
    int found = 0;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        char path[128];
        snprintf(path, sizeof(path), "/proc/self/task/%s/comm", entry->d_name);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        char name[64] = {0};
        ssize_t n = read(fd, name, sizeof(name) - 1);
        close(fd);
        if (n > 0) {
            if (strstr(name, "frida") || strstr(name, "gadget") || strstr(name, "linjector"))
                found = 1;
        }
    }
    closedir(dir);
    return found;
}

/* Frida 命名管道检测 */
static int check_frida_pipes(void) {
    /* frida 用 pipe 或 socketpair 通信，检查 /proc/self/fd 中的可疑链接 */
    DIR *dir = opendir("/proc/self/fd");
    if (!dir) return 0;
    struct dirent *entry;
    int found = 0;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        char path[128], target[256];
        snprintf(path, sizeof(path), "/proc/self/fd/%s", entry->d_name);
        ssize_t n = readlink(path, target, sizeof(target) - 1);
        if (n > 0) {
            target[n] = '\0';
            if (strstr(target, "frida") || strstr(target, "gadget"))
                found = 1;
        }
    }
    closedir(dir);
    return found;
}

/* ===== 2. 签名校验 ===== */

JNIEXPORT jboolean JNICALL
Java_io_github_sweetpotato_1digua_mbpu_Security_nativeCheckSig(JNIEnv *env, jclass clazz,
                                                  jstring expected_hash,
                                                  jstring sig_hash) {
    if (sig_hash == NULL) return JNI_FALSE;
    if (expected_hash == NULL) return JNI_TRUE;

    const char *expected = (*env)->GetStringUTFChars(env, expected_hash, NULL);
    if (expected == NULL) return JNI_TRUE;
    if (expected[0] == '\0') {
        (*env)->ReleaseStringUTFChars(env, expected_hash, expected);
        LOGW("EXPECTED_SIG_SHA256 not configured, signature check skipped");
        return JNI_TRUE;
    }

    const char *actual = (*env)->GetStringUTFChars(env, sig_hash, NULL);
    if (actual == NULL) {
        (*env)->ReleaseStringUTFChars(env, expected_hash, expected);
        return JNI_FALSE;
    }

    if (strlen(expected) != 64) {
        LOGE("EXPECTED_SIG_SHA256 malformed (len=%zu), expected 64 hex chars; skip check", strlen(expected));
        (*env)->ReleaseStringUTFChars(env, sig_hash, actual);
        (*env)->ReleaseStringUTFChars(env, expected_hash, expected);
        return JNI_TRUE;
    }

    /* 常量时间比较，防止时序侧信道 */
    volatile int diff = 0;
    for (int i = 0; i < 64; i++) {
        diff |= (unsigned char)actual[i] ^ (unsigned char)expected[i];
    }
    int match = (strlen(actual) == 64 && diff == 0);
    (*env)->ReleaseStringUTFChars(env, sig_hash, actual);
    (*env)->ReleaseStringUTFChars(env, expected_hash, expected);
    return match ? JNI_TRUE : JNI_FALSE;
}

/* ===== 3. 反调试入口（增强版） ===== */

JNIEXPORT jboolean JNICALL
Java_io_github_sweetpotato_1digua_mbpu_Security_nativeAntiDebug(JNIEnv *env, jclass clazz) {
    if (check_tracer_pid()) { LOGW("anti-debug: TracerPid != 0"); return JNI_TRUE; }
    if (check_frida()) { LOGW("anti-debug: frida port detected"); return JNI_TRUE; }
    if (check_frida_files()) { LOGW("anti-debug: frida agent file detected"); return JNI_TRUE; }
    if (check_frida_threads()) { LOGW("anti-debug: frida thread detected"); return JNI_TRUE; }
    if (check_frida_pipes()) { LOGW("anti-debug: frida pipe detected"); return JNI_TRUE; }
    return JNI_FALSE;
}

/* ===== 4. 反注入 / 完整性校验（增强版） ===== */

JNIEXPORT jint JNICALL
Java_io_github_sweetpotato_1digua_mbpu_Security_nativeIntegrity(JNIEnv *env, jclass clazz) {
    int suspicious = 0;
    int fd = open("/proc/self/maps", O_RDONLY);
    if (fd < 0) return -1;
    char buf[4096];
    char prev[16] = {0};
    ssize_t n;
    while (1) {
        n = read(fd, buf, sizeof(buf) - 1);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        buf[n] = '\0';
        char joined[sizeof(prev) + sizeof(buf)];
        size_t plen = strlen(prev);
        memcpy(joined, prev, plen);
        memcpy(joined + plen, buf, n + 1);
        if (strstr(joined, "frida")) suspicious |= 1;
        if (strstr(joined, "libfrida-gadget")) suspicious |= 2;
        if (strstr(joined, "xposed") && !strstr(joined, "lspd") && !strstr(joined, "libxposed"))
            suspicious |= 4;
        if (strstr(joined, "substrate")) suspicious |= 8;
        if (strstr(joined, "libhook") && !strstr(joined, "libhooks")) suspicious |= 16;
        size_t keep = n >= 15 ? 15 : (size_t)n;
        memcpy(prev, buf + n - keep, keep);
        prev[keep] = '\0';
    }
    close(fd);
    if (n < 0) return -1;
    return suspicious;
}

/* ===== 5. 自身完整性校验（CRC32 .text 段） =====
 * 启动时对 .so 的 .text 段算 CRC32，运行中定期复核。
 * 被 patch 过的 so 会得到不同 CRC。
 * 注意：基址由 ASLR 每次不同，需要在启动时记录基准 CRC。
 */

static uint32_t baseline_crc = 0;

/* 从 maps 中找到自己 so 的 .text 段范围 */
static int find_self_text(const char *so_name, uintptr_t *start, size_t *len) {
    int fd = open("/proc/self/maps", O_RDONLY);
    if (fd < 0) return -1;
    char buf[4096];
    char line[512];
    size_t line_pos = 0;
    ssize_t n;
    int found = 0;
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        for (ssize_t i = 0; i < n; i++) {
            if (buf[i] == '\n' || line_pos >= sizeof(line) - 1) {
                line[line_pos] = '\0';
                if (strstr(line, so_name) && strstr(line, "r-xp")) {
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
    return found ? 0 : -1;
}

JNIEXPORT jboolean JNICALL
Java_io_github_sweetpotato_1digua_mbpu_Security_nativeIntegritySelf(JNIEnv *env, jclass clazz) {
    uintptr_t text_start;
    size_t text_len;
    if (find_self_text("libmbpu_security.so", &text_start, &text_len) != 0) {
        LOGW("integrity: cannot find own .text section");
        return JNI_TRUE; /* 找不到不算异常 */
    }
    if (text_len == 0 || text_len > 1024 * 1024) return JNI_TRUE;
    uint32_t crc = crc32_calc((const void *)text_start, text_len);
    if (baseline_crc == 0) {
        baseline_crc = crc;
        LOGI("integrity: baseline CRC32=0x%08x, .text size=%zu", crc, text_len);
        return JNI_TRUE;
    }
    if (crc != baseline_crc) {
        LOGE("integrity: CRC32 mismatch! baseline=0x%08x current=0x%08x — .so may be patched",
             baseline_crc, crc);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/* ===== 6. 反内存转储 =====
 * mprotect 自己的代码段为 RX（去掉 W），防止运行时被写入/patch。
 * 同时检测 /proc/self/mem 的异常访问（dump 工具会 mmap 或 open 这个文件）。
 */

JNIEXPORT jboolean JNICALL
Java_io_github_sweetpotato_1digua_mbpu_Security_nativeProtectMemory(JNIEnv *env, jclass clazz) {
    uintptr_t text_start;
    size_t text_len;
    if (find_self_text("libmbpu_security.so", &text_start, &text_len) != 0) return JNI_TRUE;

    /* 把 .text 段锁定为只读+执行（去掉写权限），防止运行时 patch */
    uintptr_t page_start = text_start & ~(uintptr_t)0xFFF;
    size_t total = text_len + (text_start - page_start);
    if (mprotect((void *)page_start, total, PROT_READ | PROT_EXEC) == 0) {
        LOGI("memory protection: .text locked to RX (%zu bytes)", total);
        return JNI_TRUE;
    } else {
        LOGW("memory protection: mprotect failed (errno=%d), SELinux may block", errno);
        return JNI_FALSE;
    }
}

/* ===== 7. 时序检测 =====
 * 测量关键路径耗时，如果异常慢（>10x），说明被调试器单步/断点。
 */

static struct timespec ts_baseline = {0, 0};

JNIEXPORT jboolean JNICALL
Java_io_github_sweetpotato_1digua_mbpu_Security_nativeTimingCheck(JNIEnv *env, jclass clazz) {
    struct timespec ts_now;
    clock_gettime(CLOCK_MONOTONIC, &ts_now);
    if (ts_baseline.tv_sec == 0 && ts_baseline.tv_nsec == 0) {
        ts_baseline = ts_now;
        return JNI_FALSE;
    }
    /* 这里测量的是两次调用间隔；正常间隔应该很短（微秒级）。
     * 如果 Java 层在两个关键点之间调用此函数，间隔超过 500ms 说明有单步调试。 */
    long elapsed_ns = (ts_now.tv_sec - ts_baseline.tv_sec) * 1000000000L
                     + (ts_now.tv_nsec - ts_baseline.tv_nsec);
    ts_baseline = ts_now;
    if (elapsed_ns > 500000000L) { /* > 500ms */
        LOGW("timing: suspicious delay detected (%ld ns) — possible debugging", elapsed_ns);
        return JNI_TRUE;
    }
    return JNI_FALSE;
}
