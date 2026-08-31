package com.chunkops.verify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 NBT 节点（与 Minecraft 1.12.2 NBT 格式一致）。
 */
public class NbtNode {

    public static final int TAG_END = 0;
    public static final int TAG_BYTE = 1;
    public static final int TAG_SHORT = 2;
    public static final int TAG_INT = 3;
    public static final int TAG_LONG = 4;
    public static final int TAG_FLOAT = 5;
    public static final int TAG_DOUBLE = 6;
    public static final int TAG_BYTE_ARRAY = 7;
    public static final int TAG_STRING = 8;
    public static final int TAG_LIST = 9;
    public static final int TAG_COMPOUND = 10;
    public static final int TAG_INT_ARRAY = 11;
    public static final int TAG_LONG_ARRAY = 12;

    public final int type;
    public String name = "";
    public Object value;
    public int listElemType = -1; // 仅 TAG_LIST 使用

    public NbtNode(int type) {
        this.type = type;
    }

    public static NbtNode compound() {
        NbtNode n = new NbtNode(TAG_COMPOUND);
        n.value = new LinkedHashMap<String, NbtNode>();
        return n;
    }

    public static NbtNode list() {
        NbtNode n = new NbtNode(TAG_LIST);
        n.value = new ArrayList<NbtNode>();
        return n;
    }

    public static NbtNode byteNode(byte v) {
        NbtNode n = new NbtNode(TAG_BYTE);
        n.value = v;
        return n;
    }

    public static NbtNode intNode(int v) {
        NbtNode n = new NbtNode(TAG_INT);
        n.value = v;
        return n;
    }

    public static NbtNode stringNode(String v) {
        NbtNode n = new NbtNode(TAG_STRING);
        n.value = v;
        return n;
    }

    public static NbtNode byteArrayNode(byte[] v) {
        NbtNode n = new NbtNode(TAG_BYTE_ARRAY);
        n.value = v;
        return n;
    }

    public static NbtNode intArrayNode(int[] v) {
        NbtNode n = new NbtNode(TAG_INT_ARRAY);
        n.value = v;
        return n;
    }

    public static NbtNode longArrayNode(long[] v) {
        NbtNode n = new NbtNode(TAG_LONG_ARRAY);
        n.value = v;
        return n;
    }

    @SuppressWarnings("unchecked")
    public Map<String, NbtNode> asMap() {
        return (Map<String, NbtNode>) value;
    }

    @SuppressWarnings("unchecked")
    public List<NbtNode> asList() {
        return (List<NbtNode>) value;
    }

    public NbtNode get(String key) {
        if (type != TAG_COMPOUND || value == null) return null;
        return asMap().get(key);
    }

    public NbtNode child(String key) {
        return get(key);
    }

    public int intValue() {
        return ((Number) value).intValue();
    }

    public String stringValue() {
        return (String) value;
    }

    public static String typeName(int t) {
        switch (t) {
            case TAG_END: return "End";
            case TAG_BYTE: return "Byte";
            case TAG_SHORT: return "Short";
            case TAG_INT: return "Int";
            case TAG_LONG: return "Long";
            case TAG_FLOAT: return "Float";
            case TAG_DOUBLE: return "Double";
            case TAG_BYTE_ARRAY: return "ByteArray";
            case TAG_STRING: return "String";
            case TAG_LIST: return "List";
            case TAG_COMPOUND: return "Compound";
            case TAG_INT_ARRAY: return "IntArray";
            case TAG_LONG_ARRAY: return "LongArray";
            default: return "Unknown(" + t + ")";
        }
    }

    /** 供调试用：紧凑打印。 */
    public String dump(int indent) {
        StringBuilder sb = new StringBuilder();
        String pad = "";
        for (int i = 0; i < indent; i++) pad += "  ";
        sb.append(pad).append(name.isEmpty() ? "(root)" : name).append(": ").append(typeName(type));
        if (value != null) {
            switch (type) {
                case TAG_COMPOUND:
                    sb.append(" {");
                    for (Map.Entry<String, NbtNode> e : asMap().entrySet()) {
                        sb.append("\n").append(pad).append("  ").append(e.getKey()).append(": ").append(typeName(e.getValue().type));
                    }
                    sb.append("\n").append(pad).append("}");
                    break;
                case TAG_LIST: sb.append("[").append(asList().size()).append("]"); break;
                case TAG_BYTE_ARRAY: sb.append("[").append(((byte[]) value).length).append(" bytes]"); break;
                case TAG_INT_ARRAY: sb.append("[").append(((int[]) value).length).append(" ints]"); break;
                case TAG_LONG_ARRAY: sb.append("[").append(((long[]) value).length).append(" longs]"); break;
                case TAG_STRING: sb.append(" \"").append(value).append("\""); break;
                default: sb.append(" = ").append(value);
            }
        }
        return sb.toString();
    }
}
