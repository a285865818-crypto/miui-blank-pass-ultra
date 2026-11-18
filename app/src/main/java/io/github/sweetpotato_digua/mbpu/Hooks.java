package io.github.sweetpotato_digua.mbpu;

import android.accounts.Account;
import android.content.pm.PackageManager;
import android.database.MatrixCursor;
import android.hardware.camera2.CameraAccessException;
import android.location.Location;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 实际安装 hook 的地方。所有 hook 都包了 try/catch，单个失败不影响其它。
 */
final class Hooks {

    private static final String TAG = "PermNull";

    private final XposedModule module;
    private final String pkg;
    private final int mask;
    private final ClassLoader cl;
    /** 安装成功/失败的 hook 数，用于诊断面板回传。 */
    private int okCount;
    private int failCount;

    Hooks(XposedModule module, String pkg, int mask, ClassLoader cl) {
        this.module = module;
        this.pkg = pkg;
        this.mask = mask;
        this.cl = cl;
        BlockStats.configure(module, pkg);
    }

    int okCount() {
        return okCount;
    }

    int failCount() {
        return failCount;
    }

    void install() {
        // 每个勾选的类别自带对应权限伪装，勾了数据类别就等于同时授予了权限。
        Set<String> granted = new HashSet<>(Config.grantedPerms(mask));
        // MANAGE_EXTERNAL_STORAGE 是特殊权限，不走普通 checkSelfPermission，
        // 但应用仍会查它；加入集合让它自检通过，真正的读取拦截在 installStorageFiles。
        if (Config.has(mask, Config.STORAGE)) {
            granted.add("android.permission.MANAGE_EXTERNAL_STORAGE");
        }
        if (!granted.isEmpty()) {
            installPermission(granted);
            installPermissionRequest(granted);
        }
        if (Config.has(mask, Config.STORAGE)) installStorageFiles();
        // H5 修复：SMS 类别会把 SEND_SMS 伪装成已授予，但发送路径没拦——
        // 应用真持权限时短信会真发出去（扣费/隐私）。补 SmsManager 全部 no-op。
        if (Config.has(mask, Config.SMS)) installSmsSend();
        installResolver();
        if (Config.has(mask, Config.LOCATION)) installLocation();
        if (Config.has(mask, Config.CLIPBOARD)) installClipboard();
        if (Config.has(mask, Config.IDENT)) installIdentifiers();
        if (Config.has(mask, Config.APPS)) installInstalledApps(granted);
        if (Config.has(mask, Config.ACCOUNTS)) installAccounts();
        if (Config.has(mask, Config.MIC)) installMic();
        if (Config.has(mask, Config.CAMERA)) installCamera();
    }

    // ---------------------------------------------------------------- helpers

    private void log(String msg) {
        try {
            module.log(Log.INFO, TAG, "[" + pkg + "] " + msg);
        } catch (Throwable ignored) {
        }
    }

    private Class<?> load(String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private void hook(Method m, XposedInterface.Hooker hooker) {
        hook(m, hooker, false);
    }

    /** 只有故意向调用者抛出已声明异常的拦截才使用 passthrough，其余保持 fail-safe。 */
    private void hook(Method m, XposedInterface.Hooker hooker, boolean passthrough) {
        if (m == null) return;
        // 抽象方法没有方法体，hook 不了，直接跳过免得刷警告日志。
        if (Modifier.isAbstract(m.getModifiers())) return;
        try {
            XposedInterface.HookBuilder builder = module.hook(m);
            if (passthrough) {
                builder.setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH);
            } else {
                // M1 修复：显式声明 PROTECTIVE（fail-safe），不依赖框架对 DEFAULT 的解析。
                // hooker 里未捕获异常被吞成默认返回值，不会原样抛进宿主调用栈。
                builder.setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE);
            }
            builder.intercept(hooker);
            okCount++;
        } catch (Throwable t) {
            failCount++;
            try {
                module.log(Log.WARN, TAG, "hook failed: " + m, t);
            } catch (Throwable ignored) {
            }
        }
    }

    // L1 修复：此方法只抛异常、不返回 true，原先调用点写成
    // `if (...) return null;` 的分支永不执行，掩盖真实语义。改为 void，命中即抛。
    private static void failIfBlocked(Uri uri, String[] authorities) throws FileNotFoundException {
        if (!blocked(uri, authorities)) return;
        throw new FileNotFoundException("blocked by PermNull");
    }

    private static Method find(Class<?> c, String name, Class<?>... params) {
        if (c == null) return null;
        try {
            Method m = c.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String[] emptyCursorProjection(List<Object> args) {
        String[] projection = null;
        for (Object o : args) {
            if (o instanceof String[]) {
                projection = (String[]) o;
                break;
            }
        }
        if (projection == null || projection.length == 0) {
            // H1 修复：projection 为 null 是 ContentResolver.query 的合法用法
            // （系统真实 provider 此时返回全列）。之前只返回 _id，调用方
            // getColumnIndexOrThrow("display_name") 会抛 IllegalArgumentException 崩宿主。
            // 这里返回一份「安全兜底超集」——不是精确 schema，只是让常见列名查找不炸。
            return new String[]{
                    "_id", "display_name", "address", "body", "date", "type", "thread_id",
                    "data1", "lookup", "_data", "mime_type", "width", "height", "size",
                    "name", "value",
            };
        }
        // 不直接复用调用者数组；空结果最多保留合法的有限 schema。
        int count = Math.min(projection.length, 256);
        String[] result = new String[count];
        for (int i = 0; i < count; i++) {
            String column = projection[i];
            result[i] = column == null || column.isEmpty() ? "_null_" + i : column;
        }
        return result;
    }

    private static MatrixCursor emptyCursor(List<Object> args) {
        return new MatrixCursor(emptyCursorProjection(args), 0);
    }

    /** H2：insert 命中屏蔽时返回的合成 URI，避免调用方 ContentUris.parseId(null) NPE。 */
    private static Uri blockedInsertUri() {
        return Uri.parse("content://mbpu-blocked/0");
    }

    // ------------------------------------------------------------- permission

    private void installPermission(Set<String> granted) {
        Class<?> impl = load("android.app.ContextImpl");
        if (impl != null) {
            for (Method m : impl.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers())) continue;
                if (m.getReturnType() != int.class) continue;
                String n = m.getName();
                if (!n.equals("checkPermission") && !n.equals("checkCallingPermission")
                        && !n.equals("checkCallingOrSelfPermission") && !n.equals("checkSelfPermission")) {
                    continue;
                }
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 0 || pt[0] != String.class) continue;
                hook(m, chain -> {
                    Object perm = chain.getArg(0);
                    if (!(perm instanceof String) || !granted.contains(perm)) return chain.proceed();
                    // calling 检查针对 Binder 对端，不能把其他 UID 也伪装成已授权。
                    if (n.equals("checkCallingPermission") || n.equals("checkCallingOrSelfPermission")) {
                        if (android.os.Binder.getCallingUid() != android.os.Process.myUid()
                                || android.os.Binder.getCallingPid() != android.os.Process.myPid()) {
                            return chain.proceed();
                        }
                    } else if (n.equals("checkPermission") && pt.length == 3) {
                        int pid = ((Number) chain.getArg(1)).intValue();
                        int uid = ((Number) chain.getArg(2)).intValue();
                        if (pid != android.os.Process.myPid() || uid != android.os.Process.myUid()) {
                            return chain.proceed();
                        }
                    }
                    return PackageManager.PERMISSION_GRANTED;
                });
            }
        }

        // PackageManager.checkPermission 是抽象方法，不能安装；实际 self-check 已在上面覆盖。
        log("permission hooks installed: " + granted.size() + " perms");
    }

    // -------------------------------------------------- permission request

    /**
     * 拦截运行时权限请求，直接回调"已授予"。
     * <p>
     * 背景：不少应用用 RxPermissions / ActivityCompat.requestPermissions 申请权限，
     * 结果来自系统的 onRequestPermissionsResult(grantResults)。系统对 targetSdk 33+
     * 的存储权限一律拒绝，即使客户端自检被我们伪装成已授予，也会卡在申请环节。
     * 这里在请求真正发往系统前短路：按原样回调 onRequestPermissionsResult，
     * 只把本模块负责的权限填成 PERMISSION_GRANTED，其余保持拒绝，避免越权。
     */
    private void installPermissionRequest(Set<String> granted) {
        // Activity.requestPermissions(String[], int)
        Class<?> activity = load("android.app.Activity");
        Method request = find(activity, "requestPermissions", String[].class, int.class);
        hook(request, chain -> {
            Object arg = chain.getArg(0);
            if (!(arg instanceof String[])) return chain.proceed();
            String[] perms = (String[]) arg;
            int requestCode = ((Number) chain.getArg(1)).intValue();
            Object self = chain.getThisObject();
            if (!(self instanceof android.app.Activity)) return chain.proceed();

            java.util.List<String> toGrant = new java.util.ArrayList<>();
            java.util.List<String> toAsk = new java.util.ArrayList<>();
            for (String perm : perms) {
                if (perm != null && granted.contains(perm)) toGrant.add(perm);
                else if (perm != null) toAsk.add(perm);
            }
            // 全部由本模块接管：直接回调成功，不发真实请求。
            if (toAsk.isEmpty() && !toGrant.isEmpty()) {
                int[] results = new int[perms.length];
                for (int i = 0; i < perms.length; i++) {
                    results[i] = PackageManager.PERMISSION_GRANTED;
                }
                postResult((android.app.Activity) self, perms, results, requestCode);
                return null;
            }
            // N5 修复：混合权限请求不能把无关权限也 DENIED。
            // 只有「全部权限都受管」才短路并伪授予 GRANTED；
            // 只要有非受管权限，就交给系统走真实流程（受管权限由后续 hook 拦数据）。
            boolean allManaged = true;
            for (String perm : perms) {
                if (perm != null && !granted.contains(perm)) { allManaged = false; break; }
            }
            if (allManaged && perms.length > 0) {
                int[] results = new int[perms.length];
                for (int i = 0; i < perms.length; i++) {
                    results[i] = PackageManager.PERMISSION_GRANTED;
                }
                postResult((android.app.Activity) self, perms, results, requestCode);
                return null;
            }
            return chain.proceed();
        }, false);
        // Fragment.requestPermissions：RxPermissions 实际走这条。
        // 模块不依赖 androidx.fragment，全部走反射。
        Class<?> fragment = load("androidx.fragment.app.Fragment");
        Method fragReq = find(fragment, "requestPermissions", String[].class, int.class);
        Method getActivity = find(fragment, "getActivity");
        Method onResult = find(fragment, "onRequestPermissionsResult",
                int.class, String[].class, int[].class);
        hook(fragReq, chain -> {
            Object arg = chain.getArg(0);
            if (!(arg instanceof String[])) return chain.proceed();
            String[] perms = (String[]) arg;
            int requestCode = ((Number) chain.getArg(1)).intValue();
            Object self = chain.getThisObject();
            if (self == null) return chain.proceed();

            // N5 修复：Fragment 分支同样只短路「全部受管」的请求，混合请求走系统流程。
            boolean allManaged = true;
            for (String perm : perms) {
                if (perm != null && !granted.contains(perm)) { allManaged = false; break; }
            }
            if (!allManaged || perms.length == 0) return chain.proceed();
            int[] results = new int[perms.length];
            for (int i = 0; i < perms.length; i++) {
                results[i] = PackageManager.PERMISSION_GRANTED;
            }
            try {
                Object host = getActivity == null ? null : getActivity.invoke(self);
                if (host instanceof android.app.Activity) {
                    // 必须 post 而不是 runOnUiThread：后者在当前已在主线程时会立即执行，
                    // 于是 onRequestPermissionsResult 会在 requestPermissions() 返回之前
                    // 同步回调，应用常见的「先发请求再置 waiting=true」状态机会被打乱。
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        try {
                            if (onResult != null) onResult.invoke(self, requestCode, perms, results);
                        } catch (Throwable ignored) {
                        }
                    });
                    return null;
                }
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        }, false);

        log("permission request hooks installed");
    }

    /** 回到主线程回调 Activity.onRequestPermissionsResult，保证不在请求调用栈内重入。 */
    private void postResult(android.app.Activity activity, String[] perms, int[] results, int code) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    activity.onRequestPermissionsResult(code, perms, results);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ storage files
    // L2 修复：原 STORAGE_ROOTS / blockedStoragePath / pathOf 三个成员全部无调用者（死代码），
    // 但 Javadoc 仍宣称存在文件级屏蔽，误导后来者。已删除；实际语义见 installStorageFiles。


    /**
    /**
     * 文件级屏蔽（实际只做「特殊权限自检」伪装）：
     * <p>M7 说明：不 hook {@code java.io.File} / {@code FileInputStream}，
     * 真实文件读取靠系统 fuse 层拒绝。对已真实持有 READ_EXTERNAL_STORAGE 的应用，
     * File API 会全通——这是有意的行为边界。若要更严格屏蔽需 hook File.list/listFiles，
     * 但实测会让应用启动扫描外部存储时直接退出。
     */
    private void installStorageFiles() {
        // 1) 特殊权限自检：Environment.isExternalStorageManager() 恒为 true
        Class<?> env = load("android.os.Environment");
        hook(find(env, "isExternalStorageManager"), chain -> true);
        hook(find(env, "isExternalStorageManager", File.class), chain -> true);

        // 2) 不再 hook File.list / listFiles / FileInputStream。
        //    实测这类拦截会让应用在启动扫描外部存储时直接退出（无 FATAL 日志）。
        //    只伪装"权限已开启"，真实读取由系统 fuse 层拒绝，天然得到空结果。

        log("storage file hooks installed");
    }

    // --------------------------------------------------------------- resolver

    private String[] resolverPrefixes() {
        Set<String> set = new HashSet<>();
        if (Config.has(mask, Config.CONTACTS)) {
            Collections.addAll(set, "com.android.contacts", "contacts", "com.android.providers.contacts");
        }
        if (Config.has(mask, Config.SMS)) {
            Collections.addAll(set, "sms", "mms", "mms-sms", "com.android.providers.telephony");
        }
        if (Config.has(mask, Config.CALLS)) {
            Collections.addAll(set, "call_log", "calls", "com.android.calllogbackup");
        }
        if (Config.has(mask, Config.CALENDAR)) {
            Collections.addAll(set, "com.android.calendar", "calendar");
        }
        if (Config.has(mask, Config.MEDIA)) {
            Collections.addAll(set, "media", "com.android.providers.media",
                    "com.google.android.apps.photos.contentprovider");
        }
        if (Config.has(mask, Config.FILES)) {
            // 只拦下载与 shell，不碰 externalstorage：
            // 目录列举被拦住会让启动即扫描外部存储的应用自行退出。
            Collections.addAll(set, "downloads", "com.android.providers.downloads",
                    "com.android.shell");
        }
        if (Config.has(mask, Config.STORAGE)) {
            Collections.addAll(set, "com.android.externalstorage");
        }
        return set.toArray(new String[0]);
    }

    private static boolean blocked(Uri uri, String[] prefixes) {
        if (uri == null) return false;
        String auth;
        try {
            auth = uri.getAuthority();
        } catch (Throwable t) {
            return false;
        }
        if (auth == null) return false;
        auth = auth.toLowerCase(Locale.ROOT);
        for (String p : prefixes) {
            if (auth.equals(p) || auth.startsWith(p + ".")) return true;
        }
        return false;
    }

    /** 仅在已确认命中后解析类别；未命中的正常调用没有额外统计开销。 */
    private void recordResolver(Uri uri) {
        if (uri == null) return;
        String authority;
        try {
            authority = uri.getAuthority();
        } catch (Throwable ignored) {
            return;
        }
        recordResolver(authority);
    }

    private void recordResolver(String authority) {
        if (authority == null) return;
        String a = authority.toLowerCase(Locale.ROOT);
        if (Config.has(mask, Config.CONTACTS) && authorityMatches(a,
                "com.android.contacts", "contacts", "com.android.providers.contacts")) {
            BlockStats.record(Config.CONTACTS);
        } else if (Config.has(mask, Config.SMS) && authorityMatches(a,
                "sms", "mms", "mms-sms", "com.android.providers.telephony")) {
            BlockStats.record(Config.SMS);
        } else if (Config.has(mask, Config.CALLS) && authorityMatches(a,
                "call_log", "calls", "com.android.calllogbackup")) {
            BlockStats.record(Config.CALLS);
        } else if (Config.has(mask, Config.CALENDAR) && authorityMatches(a,
                "com.android.calendar", "calendar")) {
            BlockStats.record(Config.CALENDAR);
        } else if (Config.has(mask, Config.MEDIA) && authorityMatches(a,
                "media", "com.android.providers.media", "com.google.android.apps.photos.contentprovider")) {
            BlockStats.record(Config.MEDIA);
        } else if (Config.has(mask, Config.FILES) && authorityMatches(a,
                "downloads", "com.android.providers.downloads", "com.android.shell")) {
            BlockStats.record(Config.FILES);
        } else if (Config.has(mask, Config.STORAGE)
                && authorityMatches(a, "com.android.externalstorage")) {
            BlockStats.record(Config.STORAGE);
        }
    }

    private static boolean authorityMatches(String authority, String... prefixes) {
        for (String prefix : prefixes) {
            if (authority.equals(prefix) || authority.startsWith(prefix + ".")) return true;
        }
        return false;
    }

    private void installResolver() {
        final String[] authorities = resolverPrefixes();
        if (authorities.length == 0) return;
        Class<?> cr = load("android.content.ContentResolver");
        if (cr != null) {
            for (Method m : cr.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers())) continue;
                final Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 0) continue;
                // applyBatch(String authority, ArrayList<ContentProviderOperation>)
                // 首参是 authority 而不是 Uri，必须在 Uri 过滤之前单独处理，
                // 否则这个入口被 pt[0]!=Uri.class 直接跳过，批量操作完全拦不到。
                if (m.getName().equals("applyBatch") && pt[0] == String.class) {
                    // R1：filterBatchByUri 会抛 OperationApplicationException，两处
                    // applyBatch 统一 PASSTHROUGH，保持本方法签名一致。
                    hook(m, chain -> {
                        if (!blockedAuthority((String) chain.getArg(0), authorities)) {
                            return chain.proceed();
                        }
                        recordResolver((String) chain.getArg(0));
                        return emptyBatchResult(chain.getArgs());
                    }, true);
                    continue;
                }
                // call(String authority, String method, String arg, Bundle extras)：
                // 与 applyBatch 同类，首参是 authority 不是 Uri，必须在 Uri 过滤前拦。
                if (m.getName().equals("call") && pt[0] == String.class) {
                    hook(m, chain -> {
                        if (!blockedAuthority((String) chain.getArg(0), authorities)) {
                            return chain.proceed();
                        }
                        recordResolver((String) chain.getArg(0));
                        return new android.os.Bundle();
                    });
                    continue;
                }
                if (pt[0] != Uri.class) continue;
                switch (m.getName()) {
                    case "query":
                        hook(m, chain -> {
                            if (!blocked((Uri) chain.getArg(0), authorities)) return chain.proceed();
                            recordResolver((Uri) chain.getArg(0));
                            return emptyCursor(chain.getArgs());
                        });
                        break;
                    case "insert":
                        // H2 修复：之前返回 null，调用方 ContentUris.parseId(res.uri) 立刻 NPE。
                        // 与 emptyBatchResult 一致，返回 URI 形态。
                        hook(m, chain -> {
                            if (!blocked((Uri) chain.getArg(0), authorities)) return chain.proceed();
                            recordResolver((Uri) chain.getArg(0));
                            return blockedInsertUri();
                        });
                        break;
                    case "update":
                    case "delete":
                    case "bulkInsert":
                        hook(m, chain -> {
                            if (!blocked((Uri) chain.getArg(0), authorities)) return chain.proceed();
                            recordResolver((Uri) chain.getArg(0));
                            return 0;
                        });
                        break;
                    case "openInputStream":
                    case "openOutputStream":
                        // H3 修复：之前返回空流，BitmapFactory.decodeStream 得到 null 后
                        // 调用方 .getWidth() NPE。改为与 openFileDescriptor 一致抛
                        // FileNotFoundException，应用自带的 IO 错误分支能接住。
                        // R2：主动抛 FileNotFoundException 必须 PASSTHROUGH，
                        // 否则被 PROTECTIVE 吞成 null 返回值（H3 曾因此没修好）。
                        hook(m, chain -> {
                            Uri u = (Uri) chain.getArg(0);
                            if (blocked(u, authorities)) {
                                recordResolver(u);
                                throw new java.io.FileNotFoundException("blocked by MBPU: " + u);
                            }
                            return chain.proceed();
                        }, true);
                        break;
                    case "openFileDescriptor":
                    case "openAssetFileDescriptor":
                    case "openTypedAssetFileDescriptor":
                    case "openTypedAssetFile":
                        hook(m, chain -> {
                            if (blocked((Uri) chain.getArg(0), authorities)) {
                                recordResolver((Uri) chain.getArg(0));
                            }
                            failIfBlocked((Uri) chain.getArg(0), authorities);
                            return chain.proceed();
                        }, true);
                        break;
                    case "loadThumbnail":
                        hook(m, chain -> {
                            if (!blocked((Uri) chain.getArg(0), authorities)) return chain.proceed();
                            recordResolver((Uri) chain.getArg(0));
                            throw new java.io.IOException("blocked by PermNull");
                        }, true);
                        break;
                    case "call":
                        hook(m, chain -> {
                            if (!blocked((Uri) chain.getArg(0), authorities)) return chain.proceed();
                            recordResolver((Uri) chain.getArg(0));
                            return null;
                        });
                        break;
                    case "openFile":
                        hook(m, chain -> {
                            if (blocked((Uri) chain.getArg(0), authorities)) {
                                recordResolver((Uri) chain.getArg(0));
                            }
                            failIfBlocked((Uri) chain.getArg(0), authorities);
                            return chain.proceed();
                        }, true);
                        break;
                    case "getStreamTypes":
                        hook(m, chain -> {
                            if (!blocked((Uri) chain.getArg(0), authorities)) return chain.proceed();
                            recordResolver((Uri) chain.getArg(0));
                            return new String[0];
                        });
                        break;
                    default:
                        break;
                }
            }
        }
        installProviderClient(authorities);
        log("resolver hooks installed: " + Arrays.toString(authorities));
    }

    /**
     * 模拟一次全部成功的批量写入。
     * 返回数组长度必须与操作数一致：调用方按 results[i] 读取，固定返回长度 0 会越界。
     */
    private static android.content.ContentProviderResult[] emptyBatchResult(java.util.List<?> args) {
        java.util.List<?> ops = null;
        for (Object a : args) {
            if (a instanceof java.util.List) {
                ops = (java.util.List<?>) a;
                break;
            }
        }
        int count = ops == null ? 0 : ops.size();
        android.content.ContentProviderResult[] out =
                new android.content.ContentProviderResult[count];
        for (int i = 0; i < count; i++) {
            Object op = ops != null && i < ops.size() ? ops.get(i) : null;
            if (op instanceof android.content.ContentProviderOperation) {
                android.content.ContentProviderOperation o =
                        (android.content.ContentProviderOperation) op;
                // insert 返回 URI 形态（调用方 parseId(res.uri) 不会 NPE），
                // update/delete 返回 count 形态。
                if (o.isInsert()) {
                    out[i] = new android.content.ContentProviderResult(
                            android.net.Uri.parse("content://mbpu-blocked/" + i));
                } else {
                    out[i] = new android.content.ContentProviderResult(0);
                }
            } else {
                out[i] = new android.content.ContentProviderResult(0);
            }
        }
        return out;
    }

    /**
     * 不带 authority 形参的 applyBatch：逐个 operation 检查 URI。
     * 只要有任意一个命中屏蔽范围就整批伪装成功（不真正写入），否则走真实调用。
     */
    private static Object filterBatchByUri(java.util.List<?> args, String[] authorities) throws Throwable {
        java.util.List<?> ops = null;
        for (Object a : args) {
            if (a instanceof java.util.List) {
                ops = (java.util.List<?>) a;
                break;
            }
        }
        if (ops == null) return null; // 参数异常，交由调用方 proceed
        boolean any = false;
        for (Object op : ops) {
            if (!(op instanceof android.content.ContentProviderOperation)) continue;
            android.net.Uri uri = ((android.content.ContentProviderOperation) op).getUri();
            if (uri != null && blocked(uri, authorities)) {
                any = true;
                break;
            }
        }
        if (!any) return null;
        // M4 修复：原来是「命中任一条就整批伪成功」，混合批次里非屏蔽 provider 的
        // 写入被静默丢弃（数据丢失）。改为：全部命中才伪成功（无数据可丢），
        // 混合批次抛 OperationApplicationException 让应用走失败回滚路径。
        int blocked = 0;
        for (Object op : ops) {
            if (!(op instanceof android.content.ContentProviderOperation)) continue;
            android.net.Uri uri = ((android.content.ContentProviderOperation) op).getUri();
            if (uri != null && blocked(uri, authorities)) blocked++;
        }
        if (blocked < ops.size()) {
            throw new android.content.OperationApplicationException(
                    "blocked by MBPU: mixed batch has non-blocked ops", 0);
        }
        return emptyBatchResult(args);
    }

    /** ContentProviderClient 直接走 Binder，不经过 ContentResolver，必须覆盖同一语义入口。 */
    private void installProviderClient(String[] authorities) {
        Class<?> client = load("android.content.ContentProviderClient");
        if (client == null) return;
        for (Method m : client.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length == 0) continue;
            // call(String authority, String method, String arg, Bundle extras) 才有 authority；
            // 三参数 call(String method, String arg, Bundle extras) 首参是方法名，
            // 当成 authority 会既拦不住受保护 provider，又可能误伤无关 provider。
            if (m.getName().equals("call") && pt[0] == String.class) {
                if (pt.length >= 4 && pt[1] == String.class && pt[2] == String.class) {
                    hook(m, chain -> {
                        if (!blockedAuthority((String) chain.getArg(0), authorities)) {
                            return chain.proceed();
                        }
                        recordResolver((String) chain.getArg(0));
                        return new android.os.Bundle();
                    });
                }
                continue;
            }
            if (m.getName().equals("applyBatch")) {
                if (pt[0] == String.class) {
                    // R1：filterBatchByUri 会抛 OperationApplicationException，两处
                    // applyBatch 统一 PASSTHROUGH，保持本方法签名一致。
                    hook(m, chain -> {
                        if (!blockedAuthority((String) chain.getArg(0), authorities)) {
                            return chain.proceed();
                        }
                        recordResolver((String) chain.getArg(0));
                        return emptyBatchResult(chain.getArgs());
                    }, true);
                } else {
                    // applyBatch(ArrayList<ContentProviderOperation>) 没有 authority 形参：
                    // 按每个 operation 的 URI 判断，只吞命中屏蔽范围的；
                    // 其余操作交给系统。原实现无条件返回空数组，会把目标应用对自己
                    // 私有 provider 的批量写入也一起静默吞掉。
                    hook(m, chain -> {
                        Object filtered;
                        try {
                            filtered = filterBatchByUri(chain.getArgs(), authorities);
                        } catch (Throwable batchErr) {
                            // 混合批次抛 OperationApplicationException 也是真实拦截，rethrow 前补记。
                            for (Object arg : chain.getArgs()) {
                                if (!(arg instanceof java.util.List)) continue;
                                for (Object op : (java.util.List<?>) arg) {
                                    if (!(op instanceof android.content.ContentProviderOperation)) continue;
                                    Uri uri = ((android.content.ContentProviderOperation) op).getUri();
                                    if (blocked(uri, authorities)) {
                                        recordResolver(uri);
                                        break;
                                    }
                                }
                                break;
                            }
                            throw batchErr;
                        }
                        if (filtered == null) return chain.proceed();
                        for (Object arg : chain.getArgs()) {
                            if (!(arg instanceof java.util.List)) continue;
                            for (Object op : (java.util.List<?>) arg) {
                                if (!(op instanceof android.content.ContentProviderOperation)) continue;
                                Uri uri = ((android.content.ContentProviderOperation) op).getUri();
                                if (blocked(uri, authorities)) {
                                    recordResolver(uri);
                                    break;
                                }
                            }
                            break;
                        }
                        return filtered;
                    }, true);   // R1：混合批次抛的异常必须穿透给调用方
                }
                continue;
            }
            if (pt[0] != Uri.class) continue;
            switch (m.getName()) {
                case "query":
                    hook(m, chain -> {
                        if (!blocked((Uri) chain.getArg(0), authorities)) return chain.proceed();
                        recordResolver((Uri) chain.getArg(0));
                        return emptyCursor(chain.getArgs());
                    });
                    break;
                case "insert":
                    // H2 修复（第二处）：同上，返回 URI 形态避免 NPE。
                    hook(m, chain -> {
                        if (!blocked((Uri) chain.getArg(0), authorities)) return chain.proceed();
                        recordResolver((Uri) chain.getArg(0));
                        return blockedInsertUri();
                    });
                    break;
                case "update":
                case "delete":
                case "bulkInsert":
                    hook(m, chain -> {
                        if (!blocked((Uri) chain.getArg(0), authorities)) return chain.proceed();
                        recordResolver((Uri) chain.getArg(0));
                        return 0;
                    });
                    break;
                case "openFile":
                case "openAssetFile":
                case "openTypedAssetFile":
                case "openTypedAssetFileDescriptor":
                    hook(m, chain -> {
                        if (blocked((Uri) chain.getArg(0), authorities)) {
                            recordResolver((Uri) chain.getArg(0));
                        }
                        failIfBlocked((Uri) chain.getArg(0), authorities);
                        return chain.proceed();
                    }, true);
                    break;
                case "getStreamTypes":
                    hook(m, chain -> {
                        if (!blocked((Uri) chain.getArg(0), authorities)) return chain.proceed();
                        recordResolver((Uri) chain.getArg(0));
                        return new String[0];
                    });
                    break;
                default:
                    break;
            }
        }
    }

    private static boolean blockedAuthority(String authority, String[] authorities) {
        if (authority == null) return false;
        String normalized = authority.toLowerCase(Locale.ROOT);
        for (String candidate : authorities) {
            if (normalized.equals(candidate) || normalized.startsWith(candidate + ".")) return true;
        }
        return false;
    }

    // --------------------------------------------------------------- location

    private void installLocation() {
        Class<?> lm = load("android.location.LocationManager");
        if (lm == null) return;
        for (Method m : lm.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            String n = m.getName();
            if (n.equals("getLastKnownLocation")) {
                if (m.getReturnType() == Location.class) {
                    hook(m, chain -> {
                        BlockStats.record(Config.LOCATION);
                        return null;
                    });
                }
            } else if (n.equals("requestLocationUpdates") || n.equals("requestSingleUpdate")
                    || n.equals("getCurrentLocation")) {
                hook(m, chain -> {
                    BlockStats.record(Config.LOCATION);
                    return null;
                });
            } else if (n.equals("isProviderEnabled")) {
                // H4 补充：让应用判断「有无可用定位 provider」时得到「无」。
                // 注意：GMS FusedLocationProviderClient 走 Binder 直连 GMS Core，
                // 不在本进程内，本模块不拦截——这是有意的行为边界。
                if (m.getReturnType() == boolean.class) {
                    hook(m, chain -> {
                        BlockStats.record(Config.LOCATION);
                        return false;
                    });
                }
            } else if (n.equals("getProviders")) {
                if (List.class.isAssignableFrom(m.getReturnType())) {
                    hook(m, chain -> {
                        BlockStats.record(Config.LOCATION);
                        return new java.util.ArrayList<String>();
                    });
                }
            }
        }
        log("location hooks installed");
    }

    // ------------------------------------------------------------------- sms

    /**
     * H5 修复：SMS 类别把 SEND_SMS 伪装成已授予（installPermission），
     * 但原来没拦发送路径——应用自检通过后调 SmsManager.sendTextMessage，
     * 真持权限时短信真的发出去（扣费/隐私），无权限时 system_server 抛 SecurityException 崩宿主。
     * 这里把发送类方法全部 no-op，语义「已授权但发送失败」。
     */
    private void installSmsSend() {
        Class<?> sms = load("android.telephony.SmsManager");
        if (sms == null) return;
        for (Method m : sms.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            String n = m.getName();
            // N1 修复：补全所有发送类重载，包括彩信与"不持久化"变体。
            // 之前漏掉 sendMultipartTextMessageWithoutPersisting / sendMultimediaMessage，
            // 勾选 SMS 的应用仍能真实发出彩信。
            if (n.equals("sendTextMessage") || n.equals("sendMultipartTextMessage")
                    || n.equals("sendTextMessageWithoutPersisting")
                    || n.equals("sendMultipartTextMessageWithoutPersisting")
                    || n.equals("sendMultimediaMessage")
                    || n.equals("sendMultimediaMessageWithoutPersisting")
                    || n.equals("sendDataMessage")) {
                hook(m, chain -> {
                    BlockStats.record(Config.SMS);
                    return null;       // no-op：不抛异常，避免崩宿主
                });
            }
            // N4 修复：SmsManager 的 ICC 读取接口完全没拦，能绕过 ContentResolver
            // 空结果直接读 SIM 卡短信。这些查询返回 List<SmsMessage>，给空列表。
            if (n.equals("getMessagesFromIcc") || n.equals("getAllMessagesFromIcc")
                    || n.equals("getSmsMessagesForFinancialApp")) {
                if (List.class.isAssignableFrom(m.getReturnType())) {
                    hook(m, chain -> {
                        BlockStats.record(Config.SMS);
                        return new java.util.ArrayList<>();
                    });
                }
            }
        }
        log("sms send hooks installed (no-op)");
    }

    // -------------------------------------------------------------- clipboard

    private void installClipboard() {
        Class<?> cb = load("android.content.ClipboardManager");
        if (cb == null) return;
        for (Method m : cb.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            String n = m.getName();
            if (n.equals("getPrimaryClip") || n.equals("getPrimaryClipDescription")
                    || n.equals("getText")) {
                hook(m, chain -> {
                    BlockStats.record(Config.CLIPBOARD);
                    return null;
                });
            } else if (n.equals("hasPrimaryClip")) {
                hook(m, chain -> {
                    BlockStats.record(Config.CLIPBOARD);
                    return false;
                });
            }
        }
        log("clipboard hooks installed");
    }

    // ------------------------------------------------------------ identifiers

    private void installIdentifiers() {
        Class<?> tm = load("android.telephony.TelephonyManager");
        if (tm != null) {
            for (Method m : tm.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers())) continue;
                if (m.getReturnType() != String.class) continue;
                String n = m.getName();
                if (n.equals("getDeviceId") || n.equals("getImei") || n.equals("getMeid")
                        || n.equals("getSubscriberId") || n.equals("getSimSerialNumber")
                        || n.equals("getLine1Number") || n.equals("getIccId")) {
                    hook(m, chain -> {
                        BlockStats.record(Config.IDENT);
                        return "";
                    });
                }
            }
        }

        Class<?> secure = load("android.provider.Settings$Secure");
        Method getString = find(secure, "getString",
                android.content.ContentResolver.class, String.class);
        hook(getString, chain -> {
            Object k = chain.getArg(1);
            if ("android_id".equals(k) || "advertising_id".equals(k)) {
                BlockStats.record(Config.IDENT);
                return "0000000000000000";
            }
            return chain.proceed();
        });

        Class<?> build = load("android.os.Build");
        hook(find(build, "getSerial"), chain -> {
            BlockStats.record(Config.IDENT);
            return "unknown";
        });

        log("identifier hooks installed");
    }

    // ---------------------------------------------------------- installed apps

    private void installInstalledApps(Set<String> granted) {
        Class<?> apm = load("android.app.ApplicationPackageManager");
        if (apm == null) return;
        for (Method m : apm.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            String n = m.getName();
            // N3 修复：补全所有能探测安装状态的公开查询 API，不只那 4 个方法名。
            // 枚举类：返回 List/数组的查询全部给空
            boolean listing = n.startsWith("getInstalledPackages") || n.startsWith("getInstalledApplications");
            boolean querying = n.startsWith("queryIntentActivities") || n.startsWith("queryIntentServices")
                    || n.startsWith("queryIntentReceivers") || n.startsWith("queryIntentContentProviders")
                    || n.startsWith("queryBroadcastReceivers") || n.startsWith("queryIntentActivityOptions");
            // 逐包探测类：getPackageInfo / getApplicationInfo / getLaunchIntentForPackage
            // 以及它们的 AsUser 变体。getLaunchIntentForPackage 返回 null 即"未安装"。
            boolean perPkg = n.startsWith("getPackageInfo") || n.startsWith("getApplicationInfo")
                    || n.equals("getLaunchIntentForPackage");
            // M6 修复：checkPermission 也在 ApplicationPackageManager 上有具体实现
            // （不是抽象 PackageManager），可 hook。原来只遍历了 ContextImpl，
            // 用 pm.checkPermission 自检的应用读到 DENIED，走「无权限」分支。
            boolean permCheck = n.equals("checkPermission") && m.getReturnType() == int.class;
            if (perPkg) {
                // N3 细分：getLaunchIntentForPackage 返回 Intent，null = 未安装（安全）；
                // 其余返回 PackageInfo/ApplicationInfo，抛 NameNotFoundException 才是系统语义。
                boolean isLaunchQuery = n.equals("getLaunchIntentForPackage");
                hook(m, chain -> {
                    Object arg0 = chain.getArgs().isEmpty() ? null : chain.getArg(0);
                    String target = null;
                    if (arg0 instanceof String) target = (String) arg0;
                    else if (arg0 instanceof android.content.pm.VersionedPackage) {
                        target = ((android.content.pm.VersionedPackage) arg0).getPackageName();
                    }
                    // 自身包名放行，其余一律「未安装」（应用已有处理路径）
                    if (target != null && !target.equals(pkg)) {
                        BlockStats.record(Config.APPS);
                        if (isLaunchQuery) return null;   // Intent 查询返回 null 是合法语义
                        throw new android.content.pm.PackageManager.NameNotFoundException(target);
                    }
                    return chain.proceed();
                }, true);
                continue;
            }
            if (permCheck) {
                hook(m, chain -> {
                    // R3：只伪装「受管权限集合」内的权限。原来不看权限名一律 GRANTED，
                    // 只勾应用列表也会把 RECORD_AUDIO/CAMERA 等敏感权限伪授出去，
                    // 诱导应用进入没有配套数据拦截的敏感功能路径。
                    Object permArg = chain.getArgs().isEmpty() ? null : chain.getArg(0);
                    String targetPkg = chain.getArgs().size() > 1 ? (String) chain.getArg(1) : null;
                    if (permArg instanceof String && granted.contains(permArg)
                            && pkg.equals(targetPkg)) {
                        return android.content.pm.PackageManager.PERMISSION_GRANTED;
                    }
                    return chain.proceed();
                }, true);
                continue;
            }
            if (!listing && !querying) continue;
            if (!List.class.isAssignableFrom(m.getReturnType())) continue;
            hook(m, chain -> {
                BlockStats.record(Config.APPS);
                return new java.util.ArrayList<>();
            });
        }
        log("installed-app hooks installed");
    }

    // -------------------------------------------------------------- accounts

    private void installAccounts() {
        Class<?> am = load("android.accounts.AccountManager");
        if (am == null) return;
        for (Method m : am.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            // N2 修复：addOnAccountsUpdatedListener 返回 void，之前完全没拦。
            // 应用注册后回调直接拿到真实 Account[]，updateImmediately=true 时注册即泄露。
            // 这里拦截注册：不给系统注册 listener，但立即用空数组回调一次，
            // 让应用认为「已注册且当前无账户」。之后系统不会再回调（因为没注册）。
            if (m.getName().equals("addOnAccountsUpdatedListener")) {
                hook(m, chain -> {
                    Object listener = chain.getArg(0);
                    if (listener == null) return null;
                    // updateImmediately 参数：找到第一个 boolean 参数
                    boolean immediate = false;
                    for (int i = 0; i < chain.getArgs().size(); i++) {
                        if (chain.getArg(i) instanceof Boolean) {
                            immediate = (Boolean) chain.getArg(i);
                            break;
                        }
                    }
                    if (immediate) {
                        // 投到主线程，模拟系统行为
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                            try {
                                listener.getClass().getMethod("onAccountsUpdated", Account[].class)
                                        .invoke(listener, new Account[0]);
                            } catch (Throwable ignored) {
                            }
                        });
                    }
                    BlockStats.record(Config.ACCOUNTS);
                    return null;   // 不真正注册，阻断后续回调
                });
                continue;
            }
            // removeOnAccountsUpdatedListener 不需要拦：没注册过，系统自然找不到。
            if (m.getReturnType() == Account[].class) {
                hook(m, chain -> {
                    BlockStats.record(Config.ACCOUNTS);
                    return new Account[0];
                });
            } else if (m.getReturnType() == android.accounts.AccountManagerFuture.class) {
                // 只拦真正返回 Account[] 的方法。
                // AccountManagerFuture<T> 的 getResult() 会被调用方转成 T：
                //   getAccountsByTypeAndFeatures -> Account[]
                //   hasFeatures               -> Boolean
                //   getAuthToken / addAccount -> Bundle
                // 原来一律返回 Account[]，hasFeatures()/getAuthToken() 会在目标应用里
                // 抛 ClassCastException，是模块直接制造的崩溃。
                if (m.getName().equals("getAccountsByTypeAndFeatures")) {
                    // M5 修复：原实现只返回 Future 不投递 callback，用 callback 风格
                    // 调用的应用会永久卡在 loading。补投递（参数顺序：type, features, callback, handler）。
                    hook(m, chain -> {
                        BlockStats.record(Config.ACCOUNTS);
                        Object f = emptyAccountFuture();
                        deliverAccountCallback(chain, f);
                        return f;
                    });
                } else if (m.getName().equals("hasFeatures")) {
                    // 没有任何账户 => 不存在任何 feature。
                    hook(m, chain -> {
                        BlockStats.record(Config.ACCOUNTS);
                        Object f = completedFuture(Boolean.FALSE);
                        deliverAccountCallback(chain, f);
                        return f;
                    });
                } else {
                    // 其余（getAuthToken/addAccount/...）不伪装：交给系统走真实失败路径，
                    // 比返回错误类型的 Future 安全得多。
                    continue;
                }
            } else if (java.util.Map.class.isAssignableFrom(m.getReturnType())
                    && m.getName().contains("Visibility")) {
                hook(m, chain -> {
                    BlockStats.record(Config.ACCOUNTS);
                    return new java.util.HashMap<>();
                });
            }
        }
        log("account hooks installed");
    }

    /**
     * M5：把构造好的 Future 通过 AccountManagerCallback 投递出去。
     * 参数顺序（AccountManager.hasFeatures / getAccountsByTypeAndFeatures）：
     *   arg0=account|type, arg1=features, arg2=callback, arg3=handler
     * callback 或 handler 为 null 时退化为当前线程直接回调。
     */
    private static void deliverAccountCallback(XposedInterface.Chain chain, Object future) {
        try {
            java.util.List<Object> args = chain.getArgs();
            if (args.size() < 3) return;
            Object cb = args.get(2);
            Object handler = args.size() > 3 ? args.get(3) : null;
            if (cb == null) return;
            final android.accounts.AccountManagerCallback<Object> callback =
                    (android.accounts.AccountManagerCallback<Object>) cb;
            @SuppressWarnings("unchecked")
            final android.accounts.AccountManagerFuture<Object> f =
                    (android.accounts.AccountManagerFuture<Object>) future;
            Runnable r = () -> callback.run(f);
            if (handler instanceof android.os.Handler) {
                ((android.os.Handler) handler).post(r);
            } else {
                // R7：系统语义是 null handler 投主线程异步回调，不是同步执行。
                // 同步回调会在调用方返回前重入，打乱「先存 Future 再等回调」的状态机。
                new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
            }
        } catch (Throwable ignored) {
            // 投递失败不影响 Future 本身，调用方仍可走 getResult()。
        }
    }

    /** 已完成的泛型 Future：用于 hasFeatures 这类返回标量的方法。 */
    private static <T> android.accounts.AccountManagerFuture<T> completedFuture(T value) {
        return new android.accounts.AccountManagerFuture<T>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
            @Override public boolean isCancelled() { return false; }
            @Override public boolean isDone() { return true; }
            @Override public T getResult() { return value; }
            @Override public T getResult(long timeout, java.util.concurrent.TimeUnit unit) { return value; }
        };
    }

    /** 已完成的空账户 Future，行为等价于“没有任何账户”。 */
    private static android.accounts.AccountManagerFuture<Account[]> emptyAccountFuture() {
        return new android.accounts.AccountManagerFuture<Account[]>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public boolean isDone() {
                return true;
            }

            @Override
            public Account[] getResult() {
                return new Account[0];
            }

            @Override
            public Account[] getResult(long timeout, java.util.concurrent.TimeUnit unit) {
                return new Account[0];
            }
        };
    }

    // -------------------------------------------------------------- microphone

    private void installMic() {
        Class<?> ar = load("android.media.AudioRecord");
        if (ar == null) return;
        for (Method m : ar.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || !m.getName().equals("read")) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length >= 3 && pt[0] == byte[].class) {
                hook(m, chain -> {
                    Object error = validRead(chain, 3);
                    if (error != null) return error;
                    byte[] buf = (byte[]) chain.getArg(0);
                    int off = ((Number) chain.getArg(1)).intValue();
                    int size = ((Number) chain.getArg(2)).intValue();
                    if (buf == null || off < 0 || size < 0 || off > buf.length - size) {
                        return android.media.AudioRecord.ERROR_BAD_VALUE;
                    }
                    byte silence = ((android.media.AudioRecord) chain.getThisObject()).getAudioFormat()
                            == android.media.AudioFormat.ENCODING_PCM_8BIT ? (byte) 0x80 : 0;
                    BlockStats.record(Config.MIC);
                    Arrays.fill(buf, off, off + size, silence);
                    return size;
                });
            } else if (pt.length >= 3 && pt[0] == short[].class) {
                hook(m, chain -> {
                    Object error = validRead(chain, 3);
                    if (error != null) return error;
                    short[] buf = (short[]) chain.getArg(0);
                    int off = ((Number) chain.getArg(1)).intValue();
                    int size = ((Number) chain.getArg(2)).intValue();
                    if (buf == null || off < 0 || size < 0 || off > buf.length - size) {
                        return android.media.AudioRecord.ERROR_BAD_VALUE;
                    }
                    BlockStats.record(Config.MIC);
                    Arrays.fill(buf, off, off + size, (short) 0);
                    return size;
                });
            } else if (pt.length >= 4 && pt[0] == float[].class) {
                hook(m, chain -> {
                    // L4 修复：read(float[],int,int,int) 参数个数是 4，
                    // validRead 的 modeIndex 用 4 时 `size > modeIndex` 恒 false，mode 校验被跳过。
                    // 与其他分支对齐，传 3。
                    Object error = validRead(chain, 3);
                    if (error != null) return error;
                    float[] buf = (float[]) chain.getArg(0);
                    int off = ((Number) chain.getArg(1)).intValue();
                    int size = ((Number) chain.getArg(2)).intValue();
                    if (buf == null || off < 0 || size < 0 || off > buf.length - size) {
                        return android.media.AudioRecord.ERROR_BAD_VALUE;
                    }
                    BlockStats.record(Config.MIC);
                    Arrays.fill(buf, off, off + size, 0f);
                    return size;
                });
            } else if (pt.length >= 1 && pt[0] == ByteBuffer.class) {
                hook(m, chain -> {
                    Object error = validRead(chain, 2);
                    if (error != null) return error;
                    ByteBuffer buf = (ByteBuffer) chain.getArg(0);
                    int size = ((Number) chain.getArg(1)).intValue();
                    // N13 修复：系统 API 要求 direct ByteBuffer，非 direct 应报参数错误。
                    // 之前没校验 isDirect()，堆缓冲区被当成功处理，语义不一致。
                    if (buf == null || buf.isReadOnly() || !buf.isDirect()
                            || size < 0 || size > buf.remaining()) {
                        return android.media.AudioRecord.ERROR_BAD_VALUE;
                    }
                    byte silence = ((android.media.AudioRecord) chain.getThisObject()).getAudioFormat()
                            == android.media.AudioFormat.ENCODING_PCM_8BIT ? (byte) 0x80 : 0;
                    BlockStats.record(Config.MIC);
                    int start = buf.position();
                    int end = start + size;
                    // L5 修复：原逐字节 put 在高频音频回调下开销可观，改为 bulk 填充。
                    if (buf.hasArray()) {
                        Arrays.fill(buf.array(), buf.arrayOffset() + start, buf.arrayOffset() + end, silence);
                    } else {
                        byte[] tmp = new byte[size];
                        Arrays.fill(tmp, silence);
                        buf.position(start);
                        buf.put(tmp);
                    }
                    // 平台成功读取后会推进 position；不推进的话调用方下一次 read
                    // 还会覆盖同一段缓冲，依赖 position/flip 的编码器会拿到错误长度。
                    buf.position(end);
                    return size;
                });
            }
        }
        log("microphone hooks installed");
    }

    /** 非法参数/状态不能走 proceed() 读取真实音频，也不能把部分填充伪报成完整成功。 */
    private static Integer validRead(XposedInterface.Chain chain, int modeIndex) {
        Object receiver = chain.getThisObject();
        if (!(receiver instanceof android.media.AudioRecord)
                || ((android.media.AudioRecord) receiver).getState()
                != android.media.AudioRecord.STATE_INITIALIZED) {
            return android.media.AudioRecord.ERROR_INVALID_OPERATION;
        }
        if (chain.getArgs().size() > modeIndex) {
            int mode = ((Number) chain.getArg(modeIndex)).intValue();
            if (mode != android.media.AudioRecord.READ_BLOCKING
                    && mode != android.media.AudioRecord.READ_NON_BLOCKING) {
                return android.media.AudioRecord.ERROR_BAD_VALUE;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ camera

    private void installCamera() {
        Class<?> cm = load("android.hardware.camera2.CameraManager");
        if (cm == null) return;
        for (Method m : cm.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            if (!m.getName().equals("openCamera")) continue;
            hook(m, chain -> {
                BlockStats.record(Config.CAMERA);
                throw new CameraAccessException(CameraAccessException.CAMERA_DISABLED,
                        "camera disabled by PermNull");
            }, true);
        }

        // 旧 Camera API：必须覆盖所有 open 重载，否则 Camera.open(int cameraId) 仍能
        // 打开真实相机，构成直接的隐私绕过。
        // 返回 null 不符合 Camera.open() 的成功契约，调用方紧接着 setPreviewDisplay()
        // 就会 NPE；改成抛 RuntimeException，和相机被占用/禁用时平台的表现一致。
        Class<?> old = load("android.hardware.Camera");
        if (old != null) {
            for (Method m : old.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers())) continue;
                // openLegacy(int, int) 也是 public static 返回 Camera，不能漏
                if (!m.getName().equals("open") && !m.getName().equals("openLegacy")) continue;
                if (m.getReturnType() != old) continue;
                hook(m, chain -> {
                    BlockStats.record(Config.CAMERA);
                    throw new RuntimeException("Fail to connect to camera service");
                }, true);
            }
        }

        log("camera hooks installed");
    }
}
