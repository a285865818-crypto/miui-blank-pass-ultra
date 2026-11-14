# 代码审查清单

本清单来自一轮全量静态审查（2026-11-14），覆盖工程全部约 7856 行代码：
Java 2573 行、Kotlin 4989 行、native 182 行、构建脚本 112 行。

分四路独立审查，**所有报告的条目均由主审逐条回到源码抽查复核**，抽查 8 处关键项全部属实。

统计：**高 11 / 中 24 / 低 31 = 66 条**。

状态标记：`[ ]` 待修 · `[x]` 已修 · `[~]` 确认不修（附理由）

---

## 高（11）

会崩溃宿主应用、导致模块静默失效、或让隐私承诺落空的缺陷。

### H1 `[ ]` Hooks.java:147 — projection 为 null 时只返回 `_id` 一列，宿主 `getColumnIndexOrThrow` 崩溃

```java
if (projection == null || projection.length == 0) return new String[]{"_id"};
```

应用用 `cr.query(uri, null, null, null, null)` 是**系统合法用法**，随后 `getColumnIndexOrThrow("display_name")` 抛 `IllegalArgumentException`，崩在目标应用进程里。

空 cursor 的**列**比行更重要。建议按 authority 预置常用列集合（contacts/sms/media 各自一套）。

### H2 `[ ]` Hooks.java:459-461、622-624 — insert 命中屏蔽时返回 null，`ContentUris.parseId` 立刻 NPE

同文件 `emptyBatchResult`（535 行）注释明确写了「insert 返回 URI 形态（调用方 parseId 不会 NPE）」，**但单条 insert 的两个入口仍返回 null**，文件内自相矛盾。

`Uri u = cr.insert(...); ContentUris.parseId(u)` 是标准写法，parseId 内部直接解引用。

建议与 `emptyBatchResult` 对齐，返回合成 URI。

### H3 `[ ]` Hooks.java:469-471 — openInputStream 返回空流，与 openFileDescriptor 抛异常不一致

空流 → `BitmapFactory.decodeStream` 返回 null → 调用方 `.getWidth()` NPE。同一 switch 里 `openFileDescriptor`/`openFile` 走的是抛 `FileNotFoundException`。

建议统一成抛 `FileNotFoundException`，应用自带的 IO 错误分支（Glide/Coil 都有）能接住。

### H4 `[ ]` Hooks.java:662-676 — 定位只 hook 进程内 LocationManager，GMS FusedLocation 完全绕过

现代应用主流用 `FusedLocationProviderClient`，走 Binder 直连 GMS Core 进程，不经过本进程任何 LocationManager 方法，拿到真实坐标。

**这属于隐私承诺失效，不是崩溃。** README 写「定位：返回空值且不再上报位置」，但未披露此绕过。

建议：要么 hook `isProviderEnabled`/`getProviders` 让应用认为无可用 provider，要么在「已知限制」里明确写出。

### H5 `[ ]` Config.java:69-74 + Hooks 无 SmsManager — 伪装 SEND_SMS 已授予但发送路径完全不拦

`Config` 的 SMS 类别 perms 含 `SEND_SMS`，`installPermission` 伪装成 GRANTED，但全文件无 `SmsManager`/`sendTextMessage` hook。

两种结局都不好：应用真持权限 → 短信真发出去（扣费/隐私）；无真权限 → system_server 抛 `SecurityException` 崩宿主。

建议：hook `SmsManager.sendTextMessage*` 改为 no-op 或抛异常，并把 `SEND_SMS`/`PROCESS_OUTGOING_CALLS` 从伪装名单剔除。

### H6 `[ ]` Store.java:86-96 — finally 无条件清 `syncRequested`，吞掉同步期间到达的修改

```java
synchronized (lock) {
    syncScheduled = false;
    syncRequested = false;      // ← 无条件清零
    next = remote;
}
if (next != null) {
    synchronized (lock) { if (!syncRequested) next = null; }   // ← 恒为 false，next 必被清空
}
```

`scheduleSync` 里 `syncRequested = true; if (syncScheduled) return;` 设的标记，被这里清掉。症状正是「开关改了但屏蔽不生效」，且要等下一次任何 put 才补写。

建议：先读 `syncRequested` 的值再清零，用读到的值决定是否再调度。

### H7 `[ ]` RootShell.java:78-96 — `destroyForcibly` 杀不掉 root 进程，超时后进程树残留

su 是 uid=0，app 是 uid 10xxx，Linux 的 kill 权限只看 uid 不看父子关系 → EPERM，连直接子进程都杀不掉。

`doRestart` 每点一次叠一棵孤儿进程树。

建议：脚本自限时（`timeout 10 am force-stop`），或取 pgid 后按进程组收。

### H8 `[ ]` RootShell.java:159-160 — ps 输出 64K 截断后仍当完整结果，误报「未运行」

`readOutput` 检测到截断只往文本里追加 `[output truncated]`，`Result` 没有 truncated 字段，`ok()` 仍为 true。

截断后果：后半截包判为未运行 → 漏杀 → hook 未卸载；更糟的是 `computeRestartPlan` 据空集清掉 `pendingStop` 标记，**标记永久丢失**。

当前实测 20745 字节（余量 3.1 倍），暂不触发，但进程数或长名进程翻 3 倍就到。

### H9 `[ ]` Security.java:119 — `computeSigHash` 返回空串被当「签名不符」直接杀进程

`computeSigHash` 的 `catch (Throwable) { return ""; }` 把「PM 查询异常」「signingInfo 为 null」「sigs 为空」三类合并成同一个返回值，全部按篡改处理。

PM 侧抖动、split 安装未完成、系统 dexopt 期间都可能触发 → 用户看到「一打开就退出」，清数据重装均无效。

对照：`ModuleIntegrity:73-76` 对同一失败模式处理正确（返回 null 延后重试）。两处处置相反。

### H10 `[ ]` Security.java:119 + security.c:125 + ModuleIntegrity.java:78 — 期望值无归一化，两层比对规则还不一致

- `security.c:125`：`strlen(actual)==64 && strcmp(actual, expected)==0` —— 严格、区分大小写
- `ModuleIntegrity:78`：`expected.equalsIgnoreCase(actual)` —— 容忍大小写

而 `local.properties.example` 教用户用 `keytool | grep SHA256`，输出是**大写 + 冒号分隔的 96 字符**。照做填进去后：UI 进程秒退（strcmp 失败），目标进程静默不装 hook（冒号去不掉）。

**两种失效同时存在会互相掩护**，极难定位。

建议：构建期统一 `trim().replace(":","").toLowerCase(Locale.ROOT)`，长度非 64 视为未配置并告警；两层共用同一个规范化函数。

### H11 `[ ]` PermNullModule.java:102-112 — 配置读取失败静默 return，与「未启用」无法区分

```java
} catch (Throwable t) {
    safeLog(Log.WARN, ...);
    return;          // ← 该包永久放弃
}
if (mask == 0) return;    // ← 与上一种彻底无法区分
```

remote prefs 不可用（回调早于 attach、框架不支持、类型异常）时，等于对全部应用静默失效，用户完全看不出原因。

建议：把「配置不可用」和「配置为空」分开，前者走延后重试。

---

## 中（24）

按文件分组，简述。

### Hooks.java
- `[ ]` :84-200 等 43 处回调 — 仅 1 处自带 try/catch，其余依赖框架 DEFAULT 异常模式，工程未显式声明 PROTECTIVE
- `[ ]` :734-749 — 应用列表只拦列举与 Intent 查询，`getPackageInfo`/`getApplicationInfo` 未拦，QUERY_ALL_PACKAGES 反被伪装授予
- `[ ]` :492-495 — `ContentResolver.call` 返回 null，调用方解引用 Bundle 时 NPE；建议返回 `new Bundle()`
- `[ ]` :554-574 — `filterBatchByUri` 命中任一条就整批伪成功，混合批次里自有 provider 的写入被静默丢弃（数据丢失）
- `[ ]` :768-772 — 自造 AccountManagerFuture 不投递 callback，用 callback 风格调用的应用永久卡在 loading
- `[ ]` :158-200 — `PackageManager.checkPermission` 实际可 hook（运行期实例是 ApplicationPackageManager），当前只遍历了 ContextImpl，用 pm 自检的应用读到 DENIED
- `[ ]` :364-375 — 只伪装 isExternalStorageManager，无 File 级拦截；对已真持 READ_EXTERNAL_STORAGE 的应用全部放行

### Store.java / RootShell.java / Languages.java
- `[ ]` RootShell:129 — `available()` 用 output 全等 `"0"` 判根，stderr 一合并就误判
- `[ ]` RootShell:133-142 — 整批 force-stop 共用 15s 预算，包多必半途超时且状态不确定
- `[ ]` RootShell:20 — cachedThreadPool 无界，每个 exec 占一个阻塞读线程
- `[ ]` Store:197/207、224/237/247 — 持 lock 做跨进程 `getAll()`，且 dirty 循环里重复 getAll
- `[ ]` Languages:118 — `Locale.setDefault` 改全局默认 Locale，切回「跟随系统」时不恢复

### PermNullModule / ModuleIntegrity / App
- `[ ]` PermNullModule:128-131 — 安装失败只移除去重标记，已装上的 hook 不回滚，下次回调重复安装
- `[ ]` PermNullModule:154-155 — `sigRetryPending` 只按包名去重，吞掉 onPackageReady 带来的新 ClassLoader（**未确认 loader 是否真会不同**）
- `[ ]` PermNullModule:114 — 去重键是 `(pkg, ClassLoader 实例)`，两阶段若给不同实例必然装两遍
- `[ ]` ModuleIntegrity:32/54 — 校验结果 FALSE 被永久缓存，无过期复核
- `[ ]` ModuleIntegrity:50-58 — `check()` 在类锁内做 PM 反射 + APK 解析 + SHA256，阻塞其它调用方
- `[ ]` App:75-84 — `onServiceBind` 在全局监视器内、很可能在主线程同步做 IPC
- `[ ]` App:36 — `Security.verify` 无 try/catch，`loadLibrary` 失败直接终结 onCreate

### native / 构建
- `[ ]` security.c:154 — 反注入只读 maps 前 4096 字节，实测 maps 有 565451 字节，**覆盖 0.7%，功能实质失效**
- `[ ]` security.c:125 — 签名比对无归一化（同 H10）
- `[ ]` build.sh:41 — AAPT2_OVERRIDE 不可执行时静默忽略
- `[ ]` build.sh:45-54 — 不产出也不校验 native 产物，改了 security.c 会静默打包旧 so
- `[ ]` build_native.sh:42-54 — 不清旧产物、不校验三 ABI 齐，部分失败留混合版本

---

## 低（31）

### Hooks.java
- `[ ]` :123-126 — `blockedFile` 只抛异常，`if (blockedFile(...))` 是死分支
- `[ ]` :327-357 — `blockedStoragePath`/`pathOf`/`STORAGE_ROOTS` 全是死代码，注释仍宣称有文件级屏蔽
- `[ ]` :668-671 — `getLastLocation` 分支永不命中（该 API 不存在）
- `[ ]` :863-874 — float 分支 `validRead` 的 modeIndex off-by-one
- `[ ]` :876-894 — ByteBuffer 分支逐字节 put，高频音频回调开销可观
- `[ ]` :746、780 — 返回不可变空集合，应用 add/put 时抛 UnsupportedOperationException

### Store / RootShell / Languages
- `[ ]` RootShell:57 — InputStreamReader 未指定字符集
- `[ ]` RootShell:103 — `exec(String)` 是裸脚本 API，当前无注入路径但形状危险，建议加 `shellQuote()`
- `[ ]` Store:279 — mask==0 删除配置时仍写 `changed_at_<pkg>`，该键永不清
- `[ ]` Store:303-306 — `clearPendingStop(Set)` 未判空
- `[ ]` Store:154/171/180/188 — `catch (Throwable ignored)` 连 Error 一起吞
- `[ ]` Languages:65-70 — `labelOf` 未知 code 返回「跟随系统」，误导
- `[ ]` Languages:81 — es 系区域回退错位（拉美用户看到「Español (España)」）
- `[ ]` Languages:121 — `SDK_INT >= N` 恒真，且 setLocale 与 setLocales 重复

### 生命周期与安全
- `[ ]` App:33-36 — 注释说「abort()」，实际是 killProcess + exit(1)，无 tombstone
- `[ ]` PermNullModule:135 — `hookStatus(total, ok)` 死方法，参数顺序与 `reportStatus(ok, total)` 相反
- `[ ]` PermNullModule:66 — `SDK_INT >= Q` 在 minSdk 29 下恒真
- `[ ]` PermNullModule:81 — 自身包名硬编码，未用 `BuildConfig.APPLICATION_ID`（刚做过一次包名迁移）
- `[ ]` PermNullModule:25 — `installed` 永久持有目标应用 ClassLoader 强引用
- `[ ]` PermNullModule:52-59 — `currentProcessName` 默认 `"?"`，赋值失败被空 catch 吞
- `[ ]` Security:78 / ModuleIntegrity:132 — 取 `sigs[0]` 未排序，多签名 APK 下顺序不保证

### native / 构建
- `[ ]` security.c:44-49 + :136 — `check_ptrace` 恒返回 0 是死分支；`PTRACE_DETACH, 0` 的 pid=0 不指向自己
- `[ ]` security.c:81-92 — frida-agent 文件检测在普通设备必然 EACCES，永久假阴性
- `[ ]` security.c:67-78 — 主线程无超时 `connect(27042)`，无 SOCK_CLOEXEC
- `[ ]` security.c:156/159/170 — 错误码 -1 与位掩码混用，Java 侧把「检测失败」打成「suspicious so: -1」
- `[ ]` build_native.sh:46 — `-nostdlib` 多余，丢掉 crtbegin_so.o
- `[ ]` build_native.sh:33+45 — 用 PATH 的系统 clang + 借 NDK sysroot，组合未固定
- `[ ]` build.sh:12 — VARIANT 只认 debug，拼错静默走 release
- `[ ]` build.sh:25 — `tail -1` 取字典序，gradle-10 排在 gradle-9 前
- `[ ]` build.sh:41 vs :45 — AAPT2_OVERRIDE 相对路径校验与使用的基准目录不一致
- `[ ]` Android.mk/Application.mk — 死配置，Gradle 没接 externalNativeBuild

---

## 已排除（不必再查）

以下项目经实测确认**不是** bug，避免后续重复怀疑：

- **Config 位图逻辑**：`bit(id)=1<<(id-1)`，最大 id=15 → bit14，全程正 int 范围；`has()` 用 `!= 0` 正确；`parse` 用 `isKnown` 白名单校验，无越界可达路径
- **`String.format("%02x", b)` 对负字节**：`Formatter` 对 `Byte` 用 8 位无符号转换，输出恒 64 字符，正确
- **MatrixCursor 每次 new**：Cursor 有状态且会被 close，复用会串数据，新建是对的
- **`AccountManagerFuture` 匿名实现完整**：该接口不 extends `java.util.concurrent.Future`，5 个方法全部覆写
- **AudioRecord 6 个重载边界检查**：`off > buf.length - size` 无 int 溢出
- **Binder UID/PID 过滤**：`checkCallingPermission` 用 `getCallingUid/Pid` 兜住对端，符合意图
- **`AtomicBoolean` 防重**：实际是 `ConcurrentHashMap.newKeySet()` + `add()`，比 AtomicBoolean 更强
- **`HookTarget.equals/hashCode` 一致性**：不会出现 HashSet 里「equals 相等但桶不同」
- **`addListener`/`removeListener` 配对**：`DisposableEffect` + `onDispose`，正确
- **JNI 名与签名**：三个符号与 Security.java 完全对应，`_` → `_1` 正确，.so 符号表实证 GLOBAL DEFAULT
- **JNI 引用释放**：五条路径全部成对释放，NULL 分支也释放了
- **.so 加载属性**：arm64/x86_64 LOAD `Align=0x4000`，16KB 页兼容
- **当前签名值**：`local.properties` 实测 64 位全小写无尾空白，与 APK 证书一致
- **主线程阻塞**：`RootShell.exec` 所有调用点实测都在 `Dispatchers.IO`
- **命令注入**：`validPackage` 字符集不含 shell 元字符，exec 的三个调用点脚本全为常量
- **`ps -A -o` 返回空**：实测 998 行 20745 字节非空（**旧记忆需更新**）

---

## 备注

- **`check_ptrace` 自证式误报**（security.c:44-49）：`PTRACE_TRACEME` 让父进程成为 tracer，`PTRACE_DETACH` 用 pid=0 报 ESRCH 撤不掉，紧接着 `check_tracer_pid` 读到自己刚造的 tracer → 每次启动都判 `anti-debug detected`。只写日志不杀进程，属噪音，但会掩盖真实检测。
- **Kotlin UI 层**已在前一轮审过，4 个中等问题已于 1.3.1 修复（诊断页文案写反、日志裁剪旧包名、回顶按钮协程不复位、Hook 状态误报）。
