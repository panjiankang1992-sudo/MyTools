package com.yuyutian.mytools.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 头像生成工具类。
 * 根据用户名或昵称首字符生成彩色头像。
 *
 * @author mytools
 * @since 2026-05-14
 */
public class AvatarUtils {

    private static final String[] COLORS = {
        "#FF6B6B", "#4ECDC4", "#45B7D1", "#96CEB4",
        "#FFEAA7", "#DDA0DD", "#98D8C8", "#F7DC6F",
        "#BB8FCE", "#85C1E9", "#F8B500", "#00CED1"
    };

    /**
     * 根据昵称或用户名生成头像URL。
     * 如果提供昵称则使用昵称首字符，否则使用用户名首字符。
     *
     * @param nickname 昵称（可选）
     * @param username 用户名
     * @return 头像URL（SVG Data URL）
     */
    public static String generateAvatar(String nickname, String username) {
        String charToShow;
        if (nickname != null && !nickname.isEmpty()) {
            charToShow = String.valueOf(nickname.charAt(0));
        } else if (username != null && !username.isEmpty()) {
            charToShow = String.valueOf(username.charAt(0)).toUpperCase();
        } else {
            charToShow = "?";
        }

        String color = getColorForString(username);
        String svg = buildSvgAvatar(charToShow, color);

        return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(
            svg.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * 根据字符串获取固定颜色。
     * 相同字符串总是返回相同颜色。
     */
    private static String getColorForString(String str) {
        if (str == null || str.isEmpty()) {
            return COLORS[0];
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(str.getBytes(StandardCharsets.UTF_8));
            int hash = (digest[0] & 0xFF) + ((digest[1] & 0xFF) << 8);
            int index = Math.abs(hash) % COLORS.length;
            return COLORS[index];
        } catch (Exception e) {
            return COLORS[0];
        }
    }

    /**
     * 构建SVG头像。
     */
    private static String buildSvgAvatar(String charToShow, String color) {
        return String.format(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\" viewBox=\"0 0 100 100\">" +
            "<rect width=\"100\" height=\"100\" fill=\"%s\" rx=\"50\"/>" +
            "<text x=\"50\" y=\"50\" text-anchor=\"middle\" dy=\"0.35em\" " +
            "font-family=\"Arial, sans-serif\" font-size=\"50\" font-weight=\"bold\" fill=\"white\">%s</text>" +
            "</svg>",
            color, escapeXml(charToShow)
        );
    }

    /**
     * 转义XML特殊字符。
     */
    private static String escapeXml(String str) {
        if (str == null) return "";
        return str.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }
}
