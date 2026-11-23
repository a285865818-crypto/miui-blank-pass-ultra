package io.github.sweetpotato_digua.mbpu;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Root 工具：仅对经过包名白名单校验的目标执行 force-stop。 */
final class RootShell {

    /** 单次 su 会话的整体上限，包含会话内所有命令。 */
    private static final long TIMEOUT_SECONDS = 15;
    /** 批量脚本中每条命令的独立上限，避免一条命令耗尽整批预算。 */
    private static final long COMMAND_TIMEOUT_SECONDS = 5;
    /** 进程退出后仍须限制输出读取，不能因管道未关闭而无限等待。 */
    private static final long OUTPUT_TIMEOUT_SECONDS = 2;
    private static final int MAX_OUTPUT_CHARS = 64 * 1024;
    /** force-stop 分批大小：每批一次 exec，控制在单次超时预算内。 */
    private static final int BATCH_SIZE = 5;
    // 固定 4 个读取线程：cachedThreadPool 无界，每个 exec 占一个阻塞读线程，
    // 并发 su 超时会把线程数堆到不可控；4 足够覆盖同步批量场景。
    private static final ExecutorService OUTPUT_READER = Executors.newFixedThreadPool(4, r -> {
        Thread thread = new Thread(r, "MBPU-root-output");
        thread.setDaemon(true);
        return thread;
    });

    private RootShell() {
    }

    static final class Result {
        final int exitCode;
        final String output;
        /** 输出是否被 MAX_OUTPUT_CHARS 截断；截断的名单类输出不能当完整结果用。 */
        final boolean truncated;

        Result(int exitCode, String output) {
            this(exitCode, output, false);
        }

        Result(int exitCode, String output, boolean truncated) {
            this.exitCode = exitCode;
            this.output = output;
            this.truncated = truncated;
        }

        boolean ok() {
            return exitCode == 0;
        }
    }

    private static boolean validPackage(String pkg) {
        return pkg != null && pkg.matches("[A-Za-z0-9._]+");
    }

    /**
     * 读取并合并输出。
     *
     * 不能用 BufferedReader.readLine()：它会先为一整行分配内存，遇到没有换行的
     * 超长输出（比如一次 dumpsys）时，字符上限根本拦不住 OOM。这里按字符流读取，
     * 超出上限后继续丢弃剩余字节，但仍然把流读完，避免对端因管道写满而阻塞。
     */
    private static String[] readOutput(Process process) throws Exception {
        StringBuilder out = new StringBuilder();
        boolean truncated = false;
        try (java.io.Reader reader = new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                if (out.length() < MAX_OUTPUT_CHARS) {
                    int remaining = MAX_OUTPUT_CHARS - out.length();
                    out.append(buf, 0, Math.min(remaining, n));
                    // R12：本次读就超界（部分字符被丢弃）也要置位——否则最后一次
                    // 读恰好跨 64K 边界后紧接 EOF，truncated 永远为 false。
                    if (n > remaining) truncated = true;
                } else {
                    truncated = true;
                }
            }
        }
        if (truncated) out.append("[output truncated]");
        // 返回 {输出, 截断标志}，由 exec 装进 Result。
        return new String[]{out.toString().trim(), Boolean.toString(truncated)};
    }

    /**
     * M6/M7 fix: bounded output read that keeps the TAIL.
     * readOutput keeps the head and drops the tail, but log semantics are "newest is
     * tail" - over 64 KiB the newest lines were exactly what got dropped while the UI
     * claimed to show the recent log. This drops the head instead.
     */
    private static String[] readOutputTail(Process process, int maxChars) throws Exception {
        StringBuilder out = new StringBuilder();
        boolean truncated = false;
        try (java.io.Reader reader = new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                out.append(buf, 0, n);
                if (out.length() > maxChars) {
                    out.delete(0, out.length() - maxChars);
                    truncated = true;
                }
            }
        }
        return new String[]{out.toString().trim(), Boolean.toString(truncated)};
    }

    /** Same as exec, but keeps the tail when output exceeds the limit. Use for logs. */
    static Result execTail(String script, int maxChars) {
        if (script == null || script.trim().isEmpty()) return new Result(-1, "empty command");
        Process process = null;
        Future<String[]> output = null;
        try {
            process = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
            Process running = process;
            int limit = Math.max(maxChars, 4096);
            output = OUTPUT_READER.submit((Callable<String[]>) () -> readOutputTail(running, limit));
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return new Result(124, "su session timed out after " + TIMEOUT_SECONDS + "s");
            }
            String[] read = output.get(OUTPUT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return new Result(process.exitValue(), read[0], Boolean.parseBoolean(read[1]));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "interrupted");
        } catch (TimeoutException e) {
            return new Result(124, "output read timed out after " + OUTPUT_TIMEOUT_SECONDS + "s");
        } catch (ExecutionException e) {
            return new Result(-1, "output read failed: " + e.getCause());
        } catch (Exception e) {
            return new Result(-1, "exec failed: " + e);
        } finally {
            if (process != null) killAndReap(process);
        }
    }

    /**
     * 终止进程并回收资源。
     * 只 destroy 直接 su 进程不够，其子 shell / am 可能还活着；同时必须关闭三个流，
     * 否则阻塞在读取上的线程永远不会退出，反复超时就会累积进程与线程。
     */
    private static void killAndReap(Process process) {
        if (process == null) return;
        // Android 的 java.lang.Process 没有 ProcessHandle（API 26+ 的 toHandle 在
        // Android 运行时不存在）。退而求其次：destroyForcibly + 关闭三个流，让读取线程
        // 拿到 EOF 退出，再显式 waitFor 回收。无法保证子进程全死，但不会泄漏线程。
        // 注意：uid=0 的 su 进程无法从 app 侧（uid 10xxx）kill，kill(2) 只看 uid，
        // destroyForcibly 对它必然 EPERM。超时残留的 root 进程只能靠脚本内自限时
        // （见 forceStop 的 timeout 包裹）控制，这里不改 uid 逻辑。
        process.destroyForcibly();
        closeQuietly(process.getOutputStream());
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    /**
     * POSIX shell 单参数引用：把任意字符串安全地包成一个字面量。
     * 当前 exec 的调用点脚本全为常量、包名已过 validPackage 白名单，暂无调用方；
     * 保留给未来拼接动态参数的场景，避免手写转义出注入。
     */
    static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\'\\''") + "'";
    }

    /**
     * 合并输出，限制等待时间与输出大小，防止 su/am 卡死悬挂调用协程。
     * <p>
     * 注意：这是裸脚本 API，script 原样交给 su -c 执行，调用方必须自己保证
     * 参数已正确转义（建议用 {@link #shellQuote}），或只传入常量脚本。
     */
    static Result exec(String script) {
        if (script == null || script.trim().isEmpty()) return new Result(-1, "empty command");
        Process process = null;
        Future<String[]> output = null;
        try {
            process = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
            Process running = process;
            output = OUTPUT_READER.submit((Callable<String[]>) () -> readOutput(running));
            // 整体超时约束整个 su 会话；超时返回后 finally 会销毁该进程并关闭管道。
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return new Result(124, "su session timed out after " + TIMEOUT_SECONDS + "s");
            }
            // 即使 su 已退出，也不能信任对端一定关闭输出管道。
            String[] read = output.get(OUTPUT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return new Result(process.exitValue(), read[0], Boolean.parseBoolean(read[1]));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "interrupted");
        } catch (TimeoutException e) {
            return new Result(124, "output read timed out after " + OUTPUT_TIMEOUT_SECONDS + "s");
        } catch (ExecutionException e) {
            return new Result(-1, String.valueOf(e.getCause() == null ? e : e.getCause()));
        } catch (Throwable t) {
            return new Result(-1, String.valueOf(t));
        } finally {
            if (output != null && !output.isDone()) output.cancel(true);
            killAndReap(process);
        }
    }

    static boolean available() {
        Result r = exec("id -u 2>/dev/null");
        if (!r.ok()) return false;
        String[] lines = r.output.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String s = lines[i].trim();
            if (!s.isEmpty()) return "0".equals(s);
        }
        return false;
    }

    /**
     * 单条命令失败不会被最后一条成功覆盖；任何失败均使批量结果失败。
     * <p>
     * 每条 am force-stop 用 timeout 5 包裹（toybox 自带 timeout）：app 侧杀不掉
     * uid=0 的 su 进程树，只能靠脚本内自限时兜住 am 卡死。分批执行（每批最多
     * {@link #BATCH_SIZE} 个包），避免整批共用 exec 的 15s 预算，包多时半途超时。
     */
    static Result forceStop(Set<String> packages) {
        if (packages.isEmpty()) return new Result(0, "");
        java.util.List<String> valid = new java.util.ArrayList<>();
        for (String pkg : packages) {
            if (validPackage(pkg)) valid.add(pkg);
        }
        if (valid.isEmpty()) return new Result(-1, "no valid package");
        StringBuilder out = new StringBuilder();
        boolean failed = false;
        for (int from = 0; from < valid.size(); from += BATCH_SIZE) {
            StringBuilder script = new StringBuilder("failed=0\n");
            for (String pkg : valid.subList(from, Math.min(from + BATCH_SIZE, valid.size()))) {
                script.append("timeout ").append(COMMAND_TIMEOUT_SECONDS)
                        .append(" am force-stop ").append(pkg).append(" || failed=1\n");
            }
            script.append("exit $failed");
            Result r = exec(script.toString());
            out.append(r.output);
            if (!r.ok()) failed = true;
        }
        return new Result(failed ? 1 : 0, out.toString());
    }

    static boolean isRunning(String pkg) {
        if (!validPackage(pkg)) return false;
        return runningOf(java.util.Collections.singleton(pkg)).contains(pkg);
    }

    /**
     * 枚举主进程与 pkg:sub 子进程。
     * M11 修复：原先只依赖 `ps -A -o NAME=`——toybox 上它可能成功退出但输出为空，
     * NAME 若取自 15 字符 comm 还会截断包名，两者都导致假阴性（跳过 force-stop）。
     * 现在以 /proc/[pid]/cmdline 为准（完整包名 + :子进程），ps 只做补充，取并集。
     */
    static Set<String> runningOf(Set<String> packages) {
        Set<String> valid = new LinkedHashSet<>();
        for (String pkg : packages) {
            if (validPackage(pkg)) valid.add(pkg);
        }
        if (valid.isEmpty()) return new LinkedHashSet<>();
        Set<String> running = new LinkedHashSet<>();

        // 主通道：/proc 扫描。Java File API 直读，不拼 shell，无注入面。
        try {
            java.io.File proc = new java.io.File("/proc");
            String[] pids = proc.list();
            if (pids != null) {
                int scanned = 0;
                for (String pid : pids) {
                    if (scanned++ >= 4096) break;
                    if (pid.length() == 0 || pid.charAt(0) < '0' || pid.charAt(0) > '9') continue;
                    byte[] raw = readCmdline(new java.io.File(proc, pid + "/cmdline"));
                    if (raw == null) continue;
                    int len = 0;
                    while (len < raw.length && raw[len] != 0) len++;
                    if (len == 0) continue;
                    String name = new String(raw, 0, len, StandardCharsets.UTF_8);
                    int colon = name.indexOf(':');
                    String pkg = colon >= 0 ? name.substring(0, colon) : name;
                    if (valid.contains(pkg)) {
                        running.add(pkg);
                        if (running.size() == valid.size()) break;
                    }
                }
            }
        } catch (Throwable ignored) {
            // /proc 读不动就退回 ps 补充通道。
        }

        // 补充通道：ps。输出为空/截断时不抛错也不当“全部未运行”，只是没补充信息。
        try {
            Result result = exec("ps -A -o NAME=");
            if (result.ok() && !result.truncated) {
                for (String line : result.output.split("\n")) {
                    String name = line.trim();
                    int colon = name.indexOf(':');
                    String pkg = colon >= 0 ? name.substring(0, colon) : name;
                    if (valid.contains(pkg)) running.add(pkg);
                }
            }
        } catch (Throwable ignored) {
        }
        return running;
    }

    /** 读取 /proc/[pid]/cmdline（NUL 分隔），失败返回 null。 */
    private static byte[] readCmdline(java.io.File file) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[256];
            int off = 0;
            int n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return off > 0 ? buf : null;
        } catch (Throwable t) {
            return null;
        }
    }
}