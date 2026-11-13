package com.sweetpotato.MBPU;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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

    private static final long TIMEOUT_SECONDS = 15;
    private static final int MAX_OUTPUT_CHARS = 64 * 1024;
    private static final ExecutorService OUTPUT_READER = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "MBPU-root-output");
        thread.setDaemon(true);
        return thread;
    });

    private RootShell() {
    }

    static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
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
    private static String readOutput(Process process) throws Exception {
        StringBuilder out = new StringBuilder();
        boolean truncated = false;
        try (java.io.Reader reader = new InputStreamReader(process.getInputStream())) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                if (out.length() < MAX_OUTPUT_CHARS) {
                    int remaining = MAX_OUTPUT_CHARS - out.length();
                    out.append(buf, 0, Math.min(remaining, n));
                } else {
                    truncated = true;
                }
            }
        }
        if (truncated) out.append("[output truncated]");
        return out.toString().trim();
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

    /** 合并输出，限制等待时间与输出大小，防止 su/am 卡死悬挂调用协程。 */
    static Result exec(String script) {
        if (script == null || script.trim().isEmpty()) return new Result(-1, "empty command");
        Process process = null;
        Future<String> output = null;
        try {
            process = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
            Process running = process;
            output = OUTPUT_READER.submit((Callable<String>) () -> readOutput(running));
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return new Result(124, "command timed out");
            }
            return new Result(process.exitValue(), output.get(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "interrupted");
        } catch (TimeoutException | ExecutionException e) {
            return new Result(-1, String.valueOf(e.getCause() == null ? e : e.getCause()));
        } catch (Throwable t) {
            return new Result(-1, String.valueOf(t));
        } finally {
            if (output != null && !output.isDone()) output.cancel(true);
            killAndReap(process);
        }
    }

    static boolean available() {
        return exec("id -u").output.equals("0");
    }

    /** 单条命令失败不会被最后一条成功覆盖；任何失败均使批量结果失败。 */
    static Result forceStop(Set<String> packages) {
        if (packages.isEmpty()) return new Result(0, "");
        StringBuilder script = new StringBuilder("failed=0\n");
        int valid = 0;
        for (String pkg : packages) {
            if (!validPackage(pkg)) continue;
            script.append("am force-stop ").append(pkg).append(" || failed=1\n");
            valid++;
        }
        if (valid == 0) return new Result(-1, "no valid package");
        script.append("exit $failed");
        return exec(script.toString());
    }

    static boolean isRunning(String pkg) {
        if (!validPackage(pkg)) return false;
        return runningOf(java.util.Collections.singleton(pkg)).contains(pkg);
    }

    /** 枚举主进程与 pkg:sub 子进程；ps 失败时抛错，绝不误报“全部未运行”。 */
    static Set<String> runningOf(Set<String> packages) {
        Set<String> valid = new LinkedHashSet<>();
        for (String pkg : packages) {
            if (validPackage(pkg)) valid.add(pkg);
        }
        if (valid.isEmpty()) return new LinkedHashSet<>();
        Result result = exec("ps -A -o NAME=");
        if (!result.ok()) throw new IllegalStateException("ps failed: " + result.output);

        Set<String> running = new LinkedHashSet<>();
        for (String line : result.output.split("\\n")) {
            String name = line.trim();
            int colon = name.indexOf(':');
            String pkg = colon >= 0 ? name.substring(0, colon) : name;
            if (valid.contains(pkg)) running.add(pkg);
        }
        return running;
    }
}
