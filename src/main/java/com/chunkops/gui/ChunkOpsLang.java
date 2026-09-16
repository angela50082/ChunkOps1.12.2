package com.chunkops.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 界面文案取用（带自载入回退）。
 *
 * 正常路径走 {@link I18n}（= 资源包管线：Forge 的 Locale 遍历 IResourceManager.getResourceDomains()
 * 读各域的 assets/&lt;域&gt;/lang/&lt;语言&gt;.lang）。但实测存在「资源包管线读不到本模组 jar 里的 lang」
 * 的环境（游戏内全部显示成 chunkops.xxx 键名；同一 jar 用真实 FileResourcePack+Locale 离线验证却完全正常），
 * 此时退回**自己读 jar**：类加载器取 /assets/chunkops/lang/*.lang 与资源包是两条独立路径，
 * 不依赖 FML 的 FMLFileResourcePack 域枚举。
 *
 * 因此这里只在 I18n 查不到（返回键名本身）时才启用回退，正常环境行为完全不变。
 */
public final class ChunkOpsLang {

    /** 回退表（en_us 打底 + 当前语言覆盖）与它对应的语言代码。 */
    private static Map<String, String> fallbackMap;
    private static String fallbackCode;
    /** 诊断用：是否用过回退、I18n 是否可用。 */
    private static volatile boolean usedFallback = false;
    private static volatile boolean i18nWorks = true;

    private ChunkOpsLang() {
    }

    /** 取文案：优先 I18n，查不到时用自载入的语言文件。 */
    public static String t(String key, Object... args) {
        String s;
        try {
            s = I18n.format(key, args);
        } catch (Throwable t) {
            s = key;
        }
        if (s != null && !key.equals(s)) {
            i18nWorks = true;
            return s;
        }
        i18nWorks = false;
        usedFallback = true;
        String v = fallback().get(key);
        if (v == null) return key;
        if (args == null || args.length == 0) return v;
        try {
            return String.format(v, args);
        } catch (RuntimeException e) {
            return v;
        }
    }

    public static boolean usedFallback() {
        return usedFallback;
    }

    public static boolean i18nWorks() {
        return i18nWorks;
    }

    /** 当前游戏语言代码（拿不到时按 en_us）。 */
    public static String currentLanguageCode() {
        try {
            net.minecraft.client.resources.LanguageManager lm =
                    Minecraft.getMinecraft().getLanguageManager();
            if (lm != null && lm.getCurrentLanguage() != null) {
                String c = lm.getCurrentLanguage().getLanguageCode();
                if (c != null && !c.isEmpty()) return c;
            }
        } catch (Throwable ignored) {
            // 资源/语言尚未就绪
        }
        return "en_us";
    }

    private static synchronized Map<String, String> fallback() {
        String code = currentLanguageCode();
        if (fallbackMap != null && code.equals(fallbackCode)) return fallbackMap;
        Map<String, String> m = new HashMap<String, String>();
        loadInto("en_us", m);                       // 英文打底
        if (!"en_us".equals(code)) loadInto(code, m); // 当前语言覆盖
        fallbackMap = m;
        fallbackCode = code;
        return m;
    }

    /** 从本模组 jar 里读 assets/chunkops/lang/&lt;code&gt;.lang（类加载器路径，不依赖资源包）。 */
    private static void loadInto(String code, Map<String, String> out) {
        InputStream in = null;
        try {
            in = ChunkOpsLang.class.getResourceAsStream("/assets/chunkops/lang/" + code + ".lang");
            if (in == null) return;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                out.put(line.substring(0, eq), line.substring(eq + 1));
            }
        } catch (Throwable ignored) {
            // 读不到就保持原样（最终返回键名）
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // 关闭失败无所谓
                }
            }
        }
    }
}
