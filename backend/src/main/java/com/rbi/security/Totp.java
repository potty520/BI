package com.rbi.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * TOTP（RFC 6238，HMAC-SHA1 / 6 位 / 30 秒）纯 Java 实现，不引入额外依赖。
 * 兼容 Google Authenticator、Microsoft Authenticator、1Password 等。
 */
public final class Totp {

    private static final int DIGITS = 6;
    private static final int PERIOD = 30;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** 生成 20 字节随机密钥（Base32，无填充） */
    public static String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /** 计算当前时间窗的验证码 */
    public static String code(String secret, long timeMillis) {
        long counter = timeMillis / 1000L / PERIOD;
        return hotp(base32Decode(secret), counter);
    }

    /** 校验验证码，允许前后各 1 个时间窗（时钟漂移） */
    public static boolean verify(String secret, String code, long timeMillis) {
        if (secret == null || code == null) return false;
        String c = code.trim().replace(" ", "");
        if (!c.matches("\\d{6}")) return false;
        long counter = timeMillis / 1000L / PERIOD;
        for (long offset = -1; offset <= 1; offset++) {
            if (hotp(base32Decode(secret), counter + offset).equals(c)) return true;
        }
        return false;
    }

    /** otpauth:// 链接，供客户端扫码/手动录入 */
    public static String otpauthUrl(String issuer, String account, String secret) {
        String label = urlEncode(issuer) + ":" + urlEncode(account);
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD;
    }

    private static String hotp(byte[] key, long counter) {
        try {
            byte[] data = new byte[8];
            for (int i = 7; i >= 0; i--) {
                data[i] = (byte) (counter & 0xff);
                counter >>= 8;
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = binary % 1000000;
            return String.format("%06d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP 计算失败", e);
        }
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    public static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
        return sb.toString();
    }

    public static byte[] base32Decode(String s) {
        if (s == null) return new byte[0];
        String v = s.trim().replace("=", "").replace(" ", "").toUpperCase();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : v.toCharArray()) {
            int idx = BASE32.indexOf(c);
            if (idx < 0) continue;
            buffer = (buffer << 5) | idx;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
