package com.sweetpotato.MBPU;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 类别定义与配置序列化。
 * 配置值是一个逗号分隔的类别 id 字符串，例如 "2,8"。
 * <p>
 * 每个数据类别自带「对应权限伪装」：勾选某个类别时，该类别的权限检查会返回已授予，
 * 同时该类别的查询一律返回空。应用因此认为自己有权限，但拿不到任何内容。
 * <p>
 * 注意：id 是持久化到配置里的，编号必须保持稳定，不要因为增删类别而重排。
 * 名称和描述用字符串资源 id，切换语言时才会跟着变。
 */
public final class Config {

    /** RemotePreferences 的 group 名，模块 app 与 hook 进程共用 */
    /** 模块自身包名。列表里要排除，重启计划里也要排除。 */
    public static final String MODULE_PKG = "com.sweetpotato.MBPU";
    public static final String GROUP = "config";
    public static final String KEY_PREFIX = "cfg:";

    // id 1 原为「其余敏感权限」，因为语义误导（勾了反而是放开权限）已移除。
    // 保留编号空位，避免已保存的配置在下标上发生错位。
    public static final int CONTACTS = 2;   // 通讯录
    public static final int SMS = 3;        // 短信
    public static final int CALLS = 4;      // 通话记录
    public static final int CALENDAR = 5;   // 日历
    public static final int MEDIA = 6;      // 相册媒体
    public static final int FILES = 7;      // 文件与下载
    public static final int LOCATION = 8;   // 定位
    public static final int CLIPBOARD = 9;  // 剪贴板
    public static final int IDENT = 10;     // 设备标识
    public static final int APPS = 11;      // 应用列表
    public static final int ACCOUNTS = 12;  // 账户
    public static final int MIC = 13;       // 麦克风
    public static final int CAMERA = 14;    // 相机
    /**
     * 外部存储浏览（com.android.externalstorage 的目录列举）。
     * 单独拆出来：很多应用启动时会扫描外部存储找缓存，
     * 一旦这里返回空结果就会自行退出（实测 ACfan 等）。
     * 编号必须保持稳定，新增只能用未占用的 15。
     */
    public static final int STORAGE = 15;   // 外部存储浏览

    public static final class Cat {
        public final int id;
        /** 名称字符串资源，见 R.string.cat_* */
        public final int nameRes;
        /** 描述字符串资源，见 R.string.cat_*_desc */
        public final int descRes;
        /** 勾选本类别时，一并向应用伪装授予的权限 */
        final String[] perms;

        Cat(int id, int nameRes, int descRes, String... perms) {
            this.id = id;
            this.nameRes = nameRes;
            this.descRes = descRes;
            this.perms = perms;
        }
    }

    public static final Cat[] CATS = {
            new Cat(CONTACTS, R.string.cat_contacts, R.string.cat_contacts_desc,
                    "android.permission.READ_CONTACTS",
                    "android.permission.WRITE_CONTACTS"),
            new Cat(SMS, R.string.cat_sms, R.string.cat_sms_desc,
                    "android.permission.READ_SMS",
                    "android.permission.RECEIVE_SMS",
                    "android.permission.SEND_SMS",
                    "android.permission.RECEIVE_MMS",
                    "android.permission.RECEIVE_WAP_PUSH"),
            new Cat(CALLS, R.string.cat_calls, R.string.cat_calls_desc,
                    "android.permission.READ_CALL_LOG",
                    "android.permission.WRITE_CALL_LOG",
                    "android.permission.PROCESS_OUTGOING_CALLS"),
            new Cat(CALENDAR, R.string.cat_calendar, R.string.cat_calendar_desc,
                    "android.permission.READ_CALENDAR",
                    "android.permission.WRITE_CALENDAR"),
            new Cat(MEDIA, R.string.cat_media, R.string.cat_media_desc,
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.READ_MEDIA_IMAGES",
                    "android.permission.READ_MEDIA_VIDEO",
                    "android.permission.READ_MEDIA_AUDIO",
                    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
                    "android.permission.ACCESS_MEDIA_LOCATION"),
            new Cat(FILES, R.string.cat_files, R.string.cat_files_desc,
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE"),
            new Cat(STORAGE, R.string.cat_storage, R.string.cat_storage_desc,
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.MANAGE_EXTERNAL_STORAGE"),
            new Cat(LOCATION, R.string.cat_location, R.string.cat_location_desc,
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION",
                    "android.permission.ACCESS_BACKGROUND_LOCATION"),
            new Cat(CLIPBOARD, R.string.cat_clipboard, R.string.cat_clipboard_desc),
            new Cat(IDENT, R.string.cat_ident, R.string.cat_ident_desc,
                    "android.permission.READ_PHONE_STATE",
                    "android.permission.READ_PHONE_NUMBERS"),
            new Cat(APPS, R.string.cat_apps, R.string.cat_apps_desc,
                    "android.permission.QUERY_ALL_PACKAGES"),
            new Cat(ACCOUNTS, R.string.cat_accounts, R.string.cat_accounts_desc,
                    "android.permission.GET_ACCOUNTS"),
            new Cat(MIC, R.string.cat_mic, R.string.cat_mic_desc,
                    "android.permission.RECORD_AUDIO"),
            new Cat(CAMERA, R.string.cat_camera, R.string.cat_camera_desc,
                    "android.permission.CAMERA"),
    };

    private static final Set<Integer> KNOWN_IDS;
    private static final int KNOWN_MASK;

    static {
        Set<Integer> ids = new HashSet<>();
        int knownMask = 0;
        for (Cat c : CATS) {
            ids.add(c.id);
            knownMask |= bit(c.id);
        }
        KNOWN_IDS = Collections.unmodifiableSet(ids);
        KNOWN_MASK = knownMask;
    }

    private Config() {
    }

    public static int bit(int id) {
        return 1 << (id - 1);
    }

    public static boolean has(int mask, int id) {
        return (mask & bit(id)) != 0;
    }

    /** 判断某个 id 是否是当前定义中的类别 */
    private static boolean isKnown(int id) {
        return KNOWN_IDS.contains(id);
    }

    /**
     * 汇总当前掩码下所有需要伪装成「已授予」的权限。
     * <p>
     * 每个勾选的数据类别自带其权限，所以不需要再单独勾某个权限总开关。
     */
    public static Set<String> grantedPerms(int mask) {
        if (mask == 0) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (Cat c : CATS) {
            if ((mask & bit(c.id)) == 0) continue;
            Collections.addAll(out, c.perms);
        }
        return out;
    }

    public static int parse(String s) {
        if (s == null || s.isEmpty() || s.length() > 512) return 0;
        int mask = 0;
        for (String part : s.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                int id = Integer.parseInt(part);
                // 必须按已定义类别逐个判断，不能再用 id <= CATS.length：
                // 编号并不连续（1 已废弃），用长度做上界会把最大编号的类别漏掉。
                if (id >= 1 && isKnown(id)) mask |= bit(id);
            } catch (NumberFormatException ignored) {
            }
        }
        return mask;
    }

    public static String serialize(int mask) {
        StringBuilder sb = new StringBuilder();
        for (Cat c : CATS) {
            if ((mask & bit(c.id)) == 0) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(c.id);
        }
        return sb.toString();
    }

    public static int count(int mask) {
        return Integer.bitCount(mask & KNOWN_MASK);
    }
}
