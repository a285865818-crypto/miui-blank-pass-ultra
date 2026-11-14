package io.github.sweetpotato_digua.mbpu;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 语言代码与 Locale 的映射。code 与 res/values-* 目录一一对应。
 */
public final class Languages {

    public static final String SYSTEM = "system";

    /**
     * code, 显示名。
     * <p>
     * 显示名一律用该语言自己的书写方式（endonym），不加任何语言的括注——
     * 之前维吾尔语、藏语后面挂着中文括注，切成德语或阿拉伯语时那一行就是乱码。
     * 中文/英文的代码在括号里注明地区，便于区分两套变体。
     */
    private static final String[][] TABLE = {
            {SYSTEM, "跟随系统"},
            {"zh-CN", "简体中文"},
            {"zh-HK", "繁體中文（香港）"},
            {"zh-TW", "繁體中文（台灣）"},
            {"ug", "ئۇيغۇرچە"},
            {"bo", "བོད་སྐད་"},
            {"en-US", "English (United States)"},
            {"en-GB", "English (United Kingdom)"},
            {"fr", "Français"},
            {"de", "Deutsch"},
            {"it", "Italiano"},
            {"tr", "Türkçe"},
            {"nl", "Nederlands"},
            {"ru", "Русский"},
            {"es-ES", "Español (España)"},
            {"es-419", "Español (Latinoamérica)"},
            {"pt-PT", "Português (Portugal)"},
            {"pt-BR", "Português (Brasil)"},
            {"ar", "العربية"},
            {"ja", "日本語"},
            {"ko", "한국어"},
    };

    private Languages() {
    }

    public static List<String> codes() {
        List<String> out = new ArrayList<>(TABLE.length);
        for (String[] row : TABLE) out.add(row[0]);
        return out;
    }

    public static List<String> labels() {
        List<String> out = new ArrayList<>(TABLE.length);
        for (String[] row : TABLE) out.add(row[1]);
        return out;
    }

    public static String labelOf(String code) {
        for (String[] row : TABLE) {
            if (row[0].equals(code)) return row[1];
        }
        // 未知代码回显原值；返回「跟随系统」会把异常配置伪装成正常状态。
        return code;
    }

    /** 根据当前 Locale 猜一个默认语言，用于「跟随系统」时显示 */
    public static String currentSystemCode() {
        Locale system = AppThemeState.INSTANCE.systemLocale();
        String tag = system.toLanguageTag();
        for (String[] row : TABLE) {
            if (row[0].equalsIgnoreCase(tag)) return row[0];
        }
        String lang = system.getLanguage();
        // 西语系先认 419（拉丁美洲），再退任意区域，避免拉美用户落到 es-ES。
        if ("es".equals(lang)) {
            for (String[] row : TABLE) {
                if (row[0].equals(lang + "-419")) return row[0];
            }
        }
        for (String[] row : TABLE) {
            if (row[0].startsWith(lang + "-")) return row[0];
        }
        return SYSTEM;
    }

    public static boolean isSupported(String code) {
        for (String[] row : TABLE) {
            if (row[0].equals(code)) return true;
        }
        return false;
    }

    public static Locale toLocale(String code) {
        if (code == null || code.isEmpty() || SYSTEM.equals(code)) return null;
        int dash = code.indexOf('-');
        if (dash < 0) return new Locale(code);
        String lang = code.substring(0, dash);
        String rest = code.substring(dash + 1);
        if ("419".equals(rest)) return Locale.forLanguageTag("es-419");
        return new Locale(lang, rest);
    }

    /**
     * Activity.attachBaseContext 用：按已保存的语言包装 base context。
     * 必须在 attachBaseContext 里做，Resources 才会从一开始就用对语言；
     * 在 onCreate 里调 resources.updateConfiguration 是过时且不可靠的。
     */
    public static Context wrapIfNeeded(Context base) {
        String code = Store.languageOf(base);
        if (SYSTEM.equals(code)) return base;
        return wrap(base, code);
    }

    /** 用指定语言包装 Context，用于重建 Activity 前更新配置。 */
    public static Context wrap(Context context, String code) {
        Locale locale = toLocale(code);
        if (locale == null) return context;
        // 不在 wrap 里 Locale.setDefault：那是改全局默认 Locale，切回「跟随系统」
        // 时不恢复。全局默认的维护在 AppTheme 统一负责。
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocales(new LocaleList(locale));
        return context.createConfigurationContext(config);
    }
}
