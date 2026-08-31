package com.chunkops.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析/序列化（仅支持对象/数组/字符串/数字/布尔/null，UTF-8）。
 * 用于 registry-snapshot.json 等小型配置文件；Java 8 兼容，无外部依赖。
 */
public class Json {

    /**
     * 解析 JSON 文本 → Map/List/String/Double/Long/Boolean/null。
     * 兼容 mcmod.info 常见的行注释（双斜杠）与块注释。
     */
    public static Object parse(String text) {
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1); // BOM
        Parser p = new Parser(text);
        Object v = p.parseValue();
        p.skipWs();
        return v;
    }

    /** 序列化（紧凑格式，UTF-8 安全）。 */
    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, v);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object v) {
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object v) {
        return (List<Object>) v;
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : null;
    }

    public static Long lng(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : null;
    }

    // ------------------------------------------------------------ write

    static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(v.toString());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, o);
            }
            sb.append(']');
        } else {
            writeString(sb, v.toString());
        }
    }

    static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------ parse

    static class Parser {
        final String s;
        int i = 0;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                if (c == '/' && i + 1 < s.length()) {
                    char n = s.charAt(i + 1);
                    if (n == '/') { // 行注释
                        while (i < s.length() && s.charAt(i) != '\n') i++;
                        continue;
                    }
                    if (n == '*') { // 块注释
                        i += 2;
                        while (i + 1 < s.length() && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                        i += 2;
                        continue;
                    }
                }
                break;
            }
        }

        Object parseValue() {
            skipWs();
            if (i >= s.length()) throw new IllegalArgumentException("JSON 意外结束");
            char c = s.charAt(i);
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return parseNumber();
            }
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            i++; // {
            skipWs();
            if (i < s.length() && s.charAt(i) == '}') { i++; return map; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                if (i >= s.length() || s.charAt(i) != ':') throw new IllegalArgumentException("JSON 缺少冒号");
                i++;
                map.put(key, parseValue());
                skipWs();
                if (i >= s.length()) throw new IllegalArgumentException("JSON 意外结束");
                char c = s.charAt(i++);
                if (c == '}') break;
                if (c != ',') throw new IllegalArgumentException("JSON 缺少逗号");
            }
            return map;
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<Object>();
            i++; // [
            skipWs();
            if (i < s.length() && s.charAt(i) == ']') { i++; return list; }
            while (true) {
                list.add(parseValue());
                skipWs();
                if (i >= s.length()) throw new IllegalArgumentException("JSON 意外结束");
                char c = s.charAt(i++);
                if (c == ']') break;
                if (c != ',') throw new IllegalArgumentException("JSON 缺少逗号");
            }
            return list;
        }

        String parseString() {
            if (i >= s.length() || s.charAt(i) != '"') throw new IllegalArgumentException("JSON 缺少引号");
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) break;
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw new IllegalArgumentException("JSON \\u 转义不完整");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw new IllegalArgumentException("JSON 非法转义 \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("JSON 字符串未闭合");
        }

        Number parseNumber() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String num = s.substring(start, i);
            if (num.isEmpty()) throw new IllegalArgumentException("JSON 非法数字");
            try {
                if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
                    return Double.parseDouble(num);
                }
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("JSON 非法数字: " + num);
            }
        }

        void expect(String word) {
            if (!s.startsWith(word, i)) throw new IllegalArgumentException("JSON 非法字面量");
            i += word.length();
        }
    }
}
