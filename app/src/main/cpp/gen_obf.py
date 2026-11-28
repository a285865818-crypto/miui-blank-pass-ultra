#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 obf_strings.h：security.c 全部敏感字符串的 XOR 密文表（C 项字符串混淆）。

背景：C 预处理器无法对字符串字面量逐字符展开（做不到编译期 map），
所以采用「char 数组逐字节 XOR」路线：这里离线把每个字符串按 0x5A XOR
成 unsigned char 数组写入头文件（.rodata 里只有密文），运行时由 obf.h 的
obf_dec() 解密到栈上缓冲、用完 obf_wipe() 清零。明文只存在于本脚本与
security.c 的注释中，不进二进制。

用法：python3 gen_obf.py > obf_strings.h   （或不带参直接内嵌输出路径）
"""
import sys

KEY = 0x5A

# name -> 明文（与 security.c 用法一一对应）
STRINGS = [
    # --- 路径 /proc 特征 ---
    ("PROC_STATUS",      "/proc/self/status"),
    ("TRACERPID",        "TracerPid:"),
    ("PROC_TASK",        "/proc/self/task"),
    ("PROC_TASK_COMM",   "/proc/self/task/%s/comm"),
    ("PROC_FD",          "/proc/self/fd"),
    ("PROC_FD_SLASH",    "/proc/self/fd/%s"),
    ("PROC_MAPS",        "/proc/self/maps"),
    # --- Frida / 注入器文件路径 ---
    ("FRIDA_FILE_1",     "/data/local/tmp/re.frida.server/frida-agent-64.so"),
    ("FRIDA_FILE_2",     "/data/local/tmp/frida-agent-64.so"),
    ("FRIDA_FILE_3",     "/data/local/tmp/frida-agent-32.so"),
    ("FRIDA_FILE_4",     "/data/local/tmp/re.frida.server/frida-agent-32.so"),
    # --- 检测特征子串 ---
    ("S_FRIDA",          "frida"),
    ("S_GADGET",         "gadget"),
    ("S_LINJECTOR",      "linjector"),
    ("S_LIBFRIDA_GADGET", "libfrida-gadget"),
    ("S_XPOSED",         "xposed"),
    ("S_LSPD",           "lspd"),
    ("S_LIBXPOSED",      "libxposed"),
    ("S_SUBSTRATE",      "substrate"),
    ("S_LIBHOOK",        "libhook"),
    ("S_LIBHOOKS",       "libhooks"),
    ("SO_NAME",          "libmbpu_security.so"),
    ("S_LIBC_SO",        "libc.so"),
    # --- E 项 GOT 检测的函数名 ---
    ("S_READ",           "read"),
    ("S_OPEN",           "open"),
    ("S_MPROTECT",       "mprotect"),
    ("S_CLOSE",          "close"),
    ("S_MMAP",           "mmap"),
    ("S_STRSTR",         "strstr"),
    # --- JNI 动态注册（B 项）：类名 / 方法名 / 签名 ---
    ("JNI_CLASS",        "io/github/sweetpotato_digua/mbpu/Security"),
    ("JNI_N_ANTIDEBUG",  "nativeAntiDebug"),
    ("JNI_N_CHECKSIG",   "nativeCheckSig"),
    ("JNI_N_INTEGRITY",  "nativeIntegrity"),
    ("JNI_N_ITGSELF",    "nativeIntegritySelf"),
    ("JNI_N_PROTECTMEM", "nativeProtectMemory"),
    ("JNI_N_TIMING",     "nativeTimingCheck"),
    ("JNI_SIG_Z",        "()Z"),
    ("JNI_SIG_SS_Z",     "(Ljava/lang/String;Ljava/lang/String;)Z"),
    ("JNI_SIG_I",        "()I"),
    # --- 日志文本（含检测特征词的全部过混淆） ---
    ("LOG_TRACER",       "anti-debug: TracerPid != 0"),
    ("LOG_PORT",         "anti-debug: frida port detected"),
    ("LOG_AGENTFILE",    "anti-debug: frida agent file detected"),
    ("LOG_THREAD",       "anti-debug: frida thread detected"),
    ("LOG_PIPE",         "anti-debug: frida pipe detected"),
    ("LOG_NOCONF",       "EXPECTED_SIG_SHA256 not configured, signature check skipped"),
    ("LOG_MALFORMED",    "EXPECTED_SIG_SHA256 malformed (len=%zu), expected 64 hex chars; skip check"),
    ("LOG_BASELINE",     "integrity: baseline CRC32=0x%08x recorded at load, .text size=%zu"),
    ("LOG_NOTEXT",       "integrity: cannot find own .text section"),
    ("LOG_NOBASE",       "integrity: baseline unavailable, skip compare"),
    ("LOG_MISMATCH",     "integrity: CRC32 mismatch! baseline=0x%08x current=0x%08x \u2014 .so may be patched"),
    ("LOG_PROT_OK",      "memory protection: .text locked to RX (%zu bytes)"),
    ("LOG_PROT_FAIL",    "memory protection: mprotect failed (errno=%d), SELinux may block"),
    ("LOG_TIMING",       "timing: suspicious delay detected (%lld s %lld ns) \u2014 possible debugging"),
    ("LOG_PTRACE_FAIL",  "ptrace: TRACEME failed in probe \u2014 debugger attached (logged only)"),
    ("LOG_PTRACE_OK",    "ptrace: self-probe ok, no tracer attached"),
    ("LOG_PTRACE_FORK",  "ptrace: probe fork failed (errno=%d)"),
    ("LOG_GOT_HOOK",     "got: %s entry %s=0x%lx outside libc (0x%lx..0x%lx) \u2014 possible PLT/GOT hook"),
    ("LOG_GOT_SKIP",     "got: integrity scan skipped (ELF parse failed)"),
    ("LOG_SIG_CAUGHT",   "signal: caught sig=%d at %p \u2014 possible breakpoint/single-step (logged only)"),
    ("LOG_SIG_INSTALLED", "signal: SIGSEGV/SIGTRAP handlers installed"),
]


def enc(s: str) -> list:
    return [b ^ KEY for b in s.encode("utf-8")]


def main():
    out = sys.stdout
    w = out.write
    w("/* 自动生成，勿手改 —— 由 gen_obf.py 生成（C 项字符串 XOR 混淆）。\n"
      " * 明文只存在于 gen_obf.py / 源码注释，不进二进制。\n"
      " * key = 0x5A，运行时 obf.h 的 obf_dec() 解密到栈，obf_wipe() 清零。 */\n"
      "#ifndef MBPU_OBF_STRINGS_H\n#define MBPU_OBF_STRINGS_H\n\n")
    for name, plain in STRINGS:
        data = enc(plain)
        w("/* %s */\n" % plain.replace("*/", "* /"))
        w("static const unsigned char OB_%s[%d] = { %s };\n\n"
          % (name, len(data), ", ".join("0x%02X" % b for b in data)))
    w("#endif /* MBPU_OBF_STRINGS_H */\n")


if __name__ == "__main__":
    main()
