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
        installResolver();
        if (Config.has(mask, Config.LOCATION)) installLocation();
        if (Config.has(mask, Config.CLIPBOARD)) installClipboard();
        if (Config.has(mask, Config.IDENT)) installIdentifiers();
        if (Config.has(mask, Config.APPS)) installInstalledApps();
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

    private static boolean blockedFile(Uri uri, String[] authorities) throws FileNotFoundException {
        if (!blocked(uri, authorities)) return false;
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
        if (projection == null || projection.length == 0) return new String[]{"_id"};
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
            // 只有请求中至少一个权限被本模块管理时才介入。
            // 受管权限伪授予 GRANTED（后续 hook 返回空数据），
            // 非受管权限保持 DENIED（让应用走系统真实流程）。
            boolean anyManaged = false;
            for (String perm : perms) {
                if (perm != null && granted.contains(perm)) { anyManaged = true; break; }
            }
            if (anyManaged) {
                int[] results = new int[perms.length];
                for (int i = 0; i < perms.length; i++) {
                    boolean managed = perms[i] != null && granted.contains(perms[i]);
                    results[i] = managed
                            ? PackageManager.PERMISSION_GRANTED
                            : PackageManager.PERMISSION_DENIED;
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

            // 只有请求中至少一个权限被本模块管理时才介入，否则走系统真实流程。
            boolean anyManaged = false;
            for (String perm : perms) {
                if (perm != null && granted.contains(perm)) { anyManaged = true; break; }
            }
            if (!anyManaged) return chain.proceed();
            // 混合请求按位区分：受管 GRANTED，非受管 DENIED。
            int[] results = new int[perms.length];
            for (int i = 0; i < perms.length; i++) {
                boolean managed = perms[i] != null && granted.contains(perms[i]);
                results[i] = managed
                        ? PackageManager.PERMISSION_GRANTED
                        : PackageManager.PERMISSION_DENIED;
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

    /** 外部存储根路径；只拦截这些前缀，避免影响应用读写自己的私有目录。 */
    private static final String[] STORAGE_ROOTS = {
            "/storage/emulated/0", "/sdcard", "/storage/self/primary",
    };

    /** 判断路径是否属于"该被屏蔽的外部存储"，排除应用自己的 Android/data|media 目录。 */
    private boolean blockedStoragePath(String path) {
        if (path == null) return false;
        String p = path;
        boolean underStorage = false;
        for (String root : STORAGE_ROOTS) {
            if (p.equals(root) || p.startsWith(root + "/")) {
                underStorage = true;
                break;
            }
        }
        if (!underStorage) return false;
        // 自己的目录放行，否则应用连自身缓存/SDCard 目录都写不了，会直接崩。
        // p 是绝对路径（/storage/emulated/0/Android/data/<pkg>/...），
        // 不能直接 startsWith("/Android/data/<pkg>")——那永远 false。
        // 用 contains 判断段边界。
        String selfData = "/Android/data/" + pkg + "/";
        String selfMedia = "/Android/media/" + pkg + "/";
        return !(p.contains(selfData) || p.contains(selfMedia));
    }

    private static String pathOf(File f) {
        try {
            return f == null ? null : f.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 文件级屏蔽：应用自检显示"有全部文件权限"，但列目录为空、读文件失败。
     * 只作用于外部存储，且放行应用自身目录，保证应用本身还能正常运行。
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
                    hook(m, chain -> {
                        if (!blockedAuthority((String) chain.getArg(0), authorities)) {
                            return chain.proceed();
                        }
                        return emptyBatchResult(chain.getArgs());
                    });
                    continue;
                }
                // call(String authority, String method, String arg, Bundle extras)：
                // 与 applyBatch 同类，首参是 authority 不是 Uri，必须在 Uri 过滤前拦。
                if (m.getName().equals("call") && pt[0] == String.class) {
                    hook(m, chain -> blockedAuthority((String) chain.getArg(0), authorities)
                            ? null : chain.proceed());
                    continue;
                }
                if (pt[0] != Uri.class) continue;
                switch (m.getName()) {
                    case "query":
                        hook(m, chain -> blocked((Uri) chain.getArg(0), authorities)
                                ? emptyCursor(chain.getArgs()) : chain.proceed());
                        break;
                    case "insert":
                        hook(m, chain -> blocked((Uri) chain.getArg(0), authorities)
                                ? null : chain.proceed());
                        break;
                    case "update":
                    case "delete":
                    case "bulkInsert":
                        hook(m, chain -> blocked((Uri) chain.getArg(0), authorities)
                                ? 0 : chain.proceed());
                        break;
                    case "openInputStream":
                        hook(m, chain -> blocked((Uri) chain.getArg(0), authorities)
                                ? new ByteArrayInputStream(new byte[0]) : chain.proceed());
                        break;
                    case "openOutputStream":
                        hook(m, chain -> blocked((Uri) chain.getArg(0), authorities)
                                ? new ByteArrayOutputStream() : chain.proceed());
                        break;
                    case "openFileDescriptor":
                    case "openAssetFileDescriptor":
                    case "openTypedAssetFileDescriptor":
                    case "openTypedAssetFile":
                        hook(m, chain -> {
                            if (blockedFile((Uri) chain.getArg(0), authorities)) return null;
                            return chain.proceed();
                        }, true);
                        break;
                    case "loadThumbnail":
                        hook(m, chain -> {
                            if (!blocked((Uri) chain.getArg(0), authorities)) return chain.proceed();
                            throw new java.io.IOException("blocked by PermNull");
                        }, true);
                        break;
                    case "call":
                        hook(m, chain -> blocked((Uri) chain.getArg(0), authorities)
                                ? null : chain.proceed());
                        break;
                    case "openFile":
                        hook(m, chain -> {
                            if (blockedFile((Uri) chain.getArg(0), authorities)) return null;
                            return chain.proceed();
                        }, true);
                        break;
                    case "getStreamTypes":
                        hook(m, chain -> blocked((Uri) chain.getArg(0), authorities)
                                ? new String[0] : chain.proceed());
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
                    hook(m, chain -> blockedAuthority((String) chain.getArg(0), authorities)
                            ? null : chain.proceed());
                }
                continue;
            }
            if (m.getName().equals("applyBatch")) {
                if (pt[0] == String.class) {
                    hook(m, chain -> {
                        if (!blockedAuthority((String) chain.getArg(0), authorities)) {
                            return chain.proceed();
                        }
                        return emptyBatchResult(chain.getArgs());
                    });
                } else {
                    // applyBatch(ArrayList<ContentProviderOperation>) 没有 authority 形参：
                    // 按每个 operation 的 URI 判断，只吞命中屏蔽范围的；
                    // 其余操作交给系统。原实现无条件返回空数组，会把目标应用对自己
                    // 私有 provider 的批量写入也一起静默吞掉。
                    hook(m, chain -> {
                        Object filtered = filterBatchByUri(chain.getArgs(), authorities);
                        // null 表示没有命中屏蔽范围，必须显式走原调用，
                        // 不能把 null 当作返回值（那等于吞掉整个批量操作）。
                        return filtered != null ? filtered : chain.proceed();
                    });
                }
                continue;
            }
            if (pt[0] != Uri.class) continue;
            switch (m.getName()) {
                case "query":
                    hook(m, chain -> blocked((Uri) chain.getArg(0), authorities)
                            ? emptyCursor(chain.getArgs()) : chain.proceed());
                    break;
                case "insert":
                    hook(m, chain -> blocked((Uri) chain.getArg(0), authorities)
                            ? null : chain.proceed());
                    break;
                case "update":
                case "delete":
                case "bulkInsert":
                    hook(m, chain -> blocked((Uri) chain.getArg(0), authorities)
                            ? 0 : chain.proceed());
                    break;
                case "openFile":
                case "openAssetFile":
                case "openTypedAssetFile":
                case "openTypedAssetFileDescriptor":
                    hook(m, chain -> {
                        if (blockedFile((Uri) chain.getArg(0), authorities)) return null;
                        return chain.proceed();
                    }, true);
                    break;
                case "getStreamTypes":
                    hook(m, chain -> blocked((Uri) chain.getArg(0), authorities)
                            ? new String[0] : chain.proceed());
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
            if (n.equals("getLastKnownLocation") || n.equals("getLastLocation")) {
                if (m.getReturnType() == Location.class) {
                    hook(m, chain -> null);
                }
            } else if (n.equals("requestLocationUpdates") || n.equals("requestSingleUpdate")
                    || n.equals("getCurrentLocation")) {
                hook(m, chain -> null);
            }
        }
        log("location hooks installed");
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
                hook(m, chain -> null);
            } else if (n.equals("hasPrimaryClip")) {
                hook(m, chain -> false);
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
                    hook(m, chain -> "");
                }
            }
        }

        Class<?> secure = load("android.provider.Settings$Secure");
        Method getString = find(secure, "getString",
                android.content.ContentResolver.class, String.class);
        hook(getString, chain -> {
            Object k = chain.getArg(1);
            if ("android_id".equals(k) || "advertising_id".equals(k)) {
                return "0000000000000000";
            }
            return chain.proceed();
        });

        Class<?> build = load("android.os.Build");
        hook(find(build, "getSerial"), chain -> "unknown");

        log("identifier hooks installed");
    }

    // ---------------------------------------------------------- installed apps

    private void installInstalledApps() {
        Class<?> apm = load("android.app.ApplicationPackageManager");
        if (apm == null) return;
        for (Method m : apm.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            String n = m.getName();
            boolean listing = n.equals("getInstalledPackages") || n.equals("getInstalledApplications");
            boolean querying = n.equals("queryIntentActivities") || n.equals("queryIntentServices")
                    || n.equals("queryIntentReceivers") || n.equals("queryIntentContentProviders")
                    || n.equals("queryBroadcastReceivers");
            if (!listing && !querying) continue;
            if (!List.class.isAssignableFrom(m.getReturnType())) continue;
            hook(m, chain -> Collections.emptyList());
        }
        log("installed-app hooks installed");
    }

    // -------------------------------------------------------------- accounts

    private void installAccounts() {
        Class<?> am = load("android.accounts.AccountManager");
        if (am == null) return;
        for (Method m : am.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            if (m.getReturnType() == Account[].class) {
                hook(m, chain -> new Account[0]);
            } else if (m.getReturnType() == android.accounts.AccountManagerFuture.class) {
                // 只拦真正返回 Account[] 的方法。
                // AccountManagerFuture<T> 的 getResult() 会被调用方转成 T：
                //   getAccountsByTypeAndFeatures -> Account[]
                //   hasFeatures               -> Boolean
                //   getAuthToken / addAccount -> Bundle
                // 原来一律返回 Account[]，hasFeatures()/getAuthToken() 会在目标应用里
                // 抛 ClassCastException，是模块直接制造的崩溃。
                if (m.getName().equals("getAccountsByTypeAndFeatures")) {
                    hook(m, chain -> emptyAccountFuture());
                } else if (m.getName().equals("hasFeatures")) {
                    // 没有任何账户 => 不存在任何 feature。
                    hook(m, chain -> completedFuture(Boolean.FALSE));
                } else {
                    // 其余（getAuthToken/addAccount/...）不伪装：交给系统走真实失败路径，
                    // 比返回错误类型的 Future 安全得多。
                    continue;
                }
            } else if (java.util.Map.class.isAssignableFrom(m.getReturnType())
                    && m.getName().contains("Visibility")) {
                hook(m, chain -> Collections.emptyMap());
            }
        }
        log("account hooks installed");
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
                    Arrays.fill(buf, off, off + size, (short) 0);
                    return size;
                });
            } else if (pt.length >= 4 && pt[0] == float[].class) {
                hook(m, chain -> {
                    Object error = validRead(chain, 4);
                    if (error != null) return error;
                    float[] buf = (float[]) chain.getArg(0);
                    int off = ((Number) chain.getArg(1)).intValue();
                    int size = ((Number) chain.getArg(2)).intValue();
                    if (buf == null || off < 0 || size < 0 || off > buf.length - size) {
                        return android.media.AudioRecord.ERROR_BAD_VALUE;
                    }
                    Arrays.fill(buf, off, off + size, 0f);
                    return size;
                });
            } else if (pt.length >= 1 && pt[0] == ByteBuffer.class) {
                hook(m, chain -> {
                    Object error = validRead(chain, 2);
                    if (error != null) return error;
                    ByteBuffer buf = (ByteBuffer) chain.getArg(0);
                    int size = ((Number) chain.getArg(1)).intValue();
                    if (buf == null || buf.isReadOnly() || size < 0 || size > buf.remaining()) {
                        return android.media.AudioRecord.ERROR_BAD_VALUE;
                    }
                    byte silence = ((android.media.AudioRecord) chain.getThisObject()).getAudioFormat()
                            == android.media.AudioFormat.ENCODING_PCM_8BIT ? (byte) 0x80 : 0;
                    int start = buf.position();
                    int end = start + size;
                    for (int i = start; i < end; i++) buf.put(i, silence);
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
                    throw new RuntimeException("Fail to connect to camera service");
                }, true);
            }
        }

        log("camera hooks installed");
    }
}
