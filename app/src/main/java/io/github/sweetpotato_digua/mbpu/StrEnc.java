package io.github.sweetpotato_digua.mbpu;

/**
 * Java 层字符串加密。
 *
 * 原理：构建时对敏感字符串做 XOR 加密，运行时解密。
 * 静态分析（jadx/JEB）看到的只有 byte[] 而非明文。
 * key 由构建脚本随机生成烧入字节码。
 *
 * 用法：
 *   StrEnc.dec(new byte[]{0x1a, 0x2b, ...})  → "敏感字符串"
 *
 * 注意：这不是强加密（key 在代码里），但能挡住 90% 的自动化静态扫描。
 * 真正的安全边界在 native 层的签名校验和完整性校验。
 */
public final class StrEnc {

    /** XOR 密钥（构建时替换为随机值） */
    private static final byte[] KEY = {
        (byte) 0xA7, (byte) 0x3F, (byte) 0x91, (byte) 0x5C,
        (byte) 0xE2, (byte) 0x08, (byte) 0xD4, (byte) 0x6B,
    };

    private StrEnc() {
    }

    /**
     * 解密 XOR 加密的字符串。
     * @param enc 加密的字节数组（每字节 XOR KEY[i % KEY.length]）
     * @return 解密后的 UTF-8 字符串
     */
    public static String dec(byte[] enc) {
        if (enc == null || enc.length == 0) return "";
        byte[] out = new byte[enc.length];
        for (int i = 0; i < enc.length; i++) {
            out[i] = (byte) (enc[i] ^ KEY[i % KEY.length]);
        }
        try {
            return new String(out, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return new String(out);
        }
    }

    /**
     * 加密字符串（仅供构建工具调用，运行时不需要）。
     */
    public static byte[] enc(String plain) {
        if (plain == null) return new byte[0];
        byte[] in;
        try {
            in = plain.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            in = plain.getBytes();
        }
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = (byte) (in[i] ^ KEY[i % KEY.length]);
        }
        return out;
    }
}
