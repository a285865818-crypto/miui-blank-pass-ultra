package io.github.sweetpotato_digua.mbpu;

import android.content.ContentResolver;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 每应用权限配置的版本化 JSON 备份。设置、日志、时间戳与运行状态均不属于此格式。 */
public final class ConfigBackup {
    public static final String FORMAT = "mbpu-permission-config";
    public static final int VERSION = 1;
    public static final String MIME_TYPE = "application/json";
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_APPS = 10000;

    private ConfigBackup() {}

    public static final class FormatException extends Exception {
        public FormatException(String message) { super(message); }
    }

    public static String export(Map<String, Integer> snapshot) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        JSONArray apps = new JSONArray();
        for (Map.Entry<String, Integer> entry : new TreeMap<>(snapshot).entrySet()) {
            int mask = entry.getValue();
            if (!Store.isValidPackage(entry.getKey()) || mask <= 0) continue;
            List<Integer> categories = categoryIds(mask);
            if (categories.isEmpty()) continue;
            JSONObject app = new JSONObject();
            app.put("package", entry.getKey());
            app.put("categories", new JSONArray(categories));
            apps.put(app);
        }
        root.put("apps", apps);
        return root.toString(2) + "\n";
    }

    /** 先完整解析并拒绝未知/重复字段，再交给 Store 一次性替换，解析失败不会改配置。 */
    public static Map<String, Integer> parse(String text) throws FormatException {
        if (text == null || text.length() > MAX_BYTES) throw new FormatException("backup too large");
        try {
            Object parsed = new StrictParser(text).parse();
            if (!(parsed instanceof JSONObject)) throw new FormatException("root is not an object");
            JSONObject root = (JSONObject) parsed;
            strictFields(root, "format", "version", "apps");
            if (!(root.get("format") instanceof String)
                    || !FORMAT.equals(root.getString("format"))) throw new FormatException("wrong format");
            Object version = root.get("version");
            if (!(version instanceof Integer) || (Integer) version != VERSION) {
                throw new FormatException("unsupported version");
            }
            JSONArray apps = root.getJSONArray("apps");
            if (apps.length() > MAX_APPS) throw new FormatException("too many apps");
            Map<String, Integer> out = new LinkedHashMap<>();
            for (int i = 0; i < apps.length(); i++) {
                JSONObject app = apps.getJSONObject(i);
                strictFields(app, "package", "categories");
                if (!(app.get("package") instanceof String)) throw new FormatException("invalid package");
                String pkg = app.getString("package");
                if (!Store.isValidPackage(pkg)) throw new FormatException("invalid package");
                if (out.containsKey(pkg)) throw new FormatException("duplicate package");
                JSONArray values = app.getJSONArray("categories");
                if (values.length() == 0 || values.length() > Config.CATS.length) {
                    throw new FormatException("invalid category count");
                }
                Set<Integer> seen = new HashSet<>();
                int mask = 0;
                for (int j = 0; j < values.length(); j++) {
                    Object value = values.get(j);
                    if (!(value instanceof Integer)) throw new FormatException("category is not integer");
                    int id = (Integer) value;
                    if (!known(id) || !seen.add(id)) throw new FormatException("unknown category");
                    mask |= Config.bit(id);
                }
                out.put(pkg, mask);
            }
            return Collections.unmodifiableMap(out);
        } catch (JSONException | RuntimeException e) {
            throw new FormatException("malformed backup");
        }
    }

    public static void write(ContentResolver resolver, Uri uri, String json) throws IOException {
        OutputStream stream = resolver.openOutputStream(uri, "wt");
        if (stream == null) throw new IOException("cannot open destination");
        try (OutputStream out = stream) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /** 按字节限制读取，拒绝超大文件，避免未知来源 SAF 文档造成内存压力。 */
    public static String read(ContentResolver resolver, Uri uri) throws IOException, FormatException {
        InputStream stream = resolver.openInputStream(uri);
        if (stream == null) throw new IOException("cannot open source");
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > MAX_BYTES) throw new FormatException("backup too large");
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static List<String> hasRemovedCategory(Map<String, Integer> before, Map<String, Integer> restored) {
        List<String> stopped = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : before.entrySet()) {
            int beforeMask = entry.getValue();
            int afterMask = restored.getOrDefault(entry.getKey(), 0);
            // 类别被移除（含减少、替换、清零）都需要重启目标应用才能卸载对应 hook。
            if (beforeMask != 0 && (beforeMask & ~afterMask) != 0) {
                stopped.add(entry.getKey());
            }
        }
        return stopped;
    }

    private static List<Integer> categoryIds(int mask) {
        List<Integer> ids = new ArrayList<>();
        for (Config.Cat cat : Config.CATS) if (Config.has(mask, cat.id)) ids.add(cat.id);
        return ids;
    }

    private static boolean known(int id) {
        for (Config.Cat cat : Config.CATS) if (cat.id == id) return true;
        return false;
    }

    private static void strictFields(JSONObject object, String... allowed) throws FormatException {
        Set<String> fields = new HashSet<>();
        Collections.addAll(fields, allowed);
        for (java.util.Iterator<String> keys = object.keys(); keys.hasNext();) {
            if (!fields.contains(keys.next())) throw new FormatException("unknown field");
        }
        for (String required : allowed) if (!object.has(required)) throw new FormatException("missing field");
    }

    /** 仅接受 JSON 整数、对象、数组、字符串等标准值；在构造 JSONObject 前拒绝重复键。 */
    private static final class StrictParser {
        private final String text;
        private int pos;
        private int depth;
        StrictParser(String text) { this.text = text; }

        Object parse() throws FormatException {
            skipSpace();
            Object value = value();
            skipSpace();
            if (pos != text.length()) throw new FormatException("trailing data");
            return value;
        }

        private Object value() throws FormatException {
            if (++depth > 32) throw new FormatException("too deeply nested");
            try {
                if (pos >= text.length()) throw new FormatException("missing value");
                char c = text.charAt(pos);
                if (c == '{') return object();
                if (c == '[') return array();
                if (c == '"') return string();
                if (text.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
                if (text.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
                if (text.startsWith("null", pos)) { pos += 4; return JSONObject.NULL; }
                return number();
            } finally { depth--; }
        }

        private JSONObject object() throws FormatException {
            JSONObject out = new JSONObject();
            Set<String> names = new HashSet<>();
            pos++;
            skipSpace();
            if (take('}')) return out;
            while (true) {
                if (pos >= text.length() || text.charAt(pos) != '"') throw new FormatException("object key");
                String name = string();
                if (!names.add(name)) throw new FormatException("duplicate field");
                skipSpace();
                if (!take(':')) throw new FormatException("missing colon");
                skipSpace();
                try { out.put(name, value()); } catch (JSONException e) { throw new FormatException("bad value"); }
                skipSpace();
                if (take('}')) return out;
                if (!take(',')) throw new FormatException("missing comma");
                skipSpace();
            }
        }

        private JSONArray array() throws FormatException {
            JSONArray out = new JSONArray();
            pos++;
            skipSpace();
            if (take(']')) return out;
            while (true) {
                out.put(value());
                skipSpace();
                if (take(']')) return out;
                if (!take(',')) throw new FormatException("missing comma");
                skipSpace();
            }
        }

        private String string() throws FormatException {
            pos++;
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') return out.toString();
                if (c == '\\') {
                    if (pos >= text.length()) throw new FormatException("bad escape");
                    char escaped = text.charAt(pos++);
                    switch (escaped) {
                        case '"': case '\\': case '/': out.append(escaped); break;
                        case 'b': out.append('\b'); break;
                        case 'f': out.append('\f'); break;
                        case 'n': out.append('\n'); break;
                        case 'r': out.append('\r'); break;
                        case 't': out.append('\t'); break;
                        case 'u':
                            if (pos + 4 > text.length()) throw new FormatException("bad unicode");
                            try { out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16)); }
                            catch (NumberFormatException e) { throw new FormatException("bad unicode"); }
                            pos += 4;
                            break;
                        default: throw new FormatException("bad escape");
                    }
                } else {
                    if (c < 0x20) throw new FormatException("control character");
                    out.append(c);
                }
            }
            throw new FormatException("unterminated string");
        }

        private Object number() throws FormatException {
            int start = pos;
            if (pos < text.length() && text.charAt(pos) == '-') pos++;
            int digits = pos;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
            if (digits == pos) throw new FormatException("bad number");
            if (text.charAt(digits) == '0' && pos - digits > 1) throw new FormatException("leading zero");
            if (pos < text.length() && (text.charAt(pos) == '.' || text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                throw new FormatException("only integers allowed");
            }
            try { return Integer.valueOf(text.substring(start, pos)); }
            catch (NumberFormatException e) { throw new FormatException("integer out of range"); }
        }

        private void skipSpace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        private boolean take(char c) {
            if (pos < text.length() && text.charAt(pos) == c) { pos++; return true; }
            return false;
        }
    }
}
