package com.chunkops.verify;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 通用 NBT 文件结构查看器：读取任意 .nbt/.schematic/.dat 文件并打印结构摘要。
 * 用于验证结构文件格式（设计文档清单 7/8 的互操作参考）。
 *
 * 用法：NbtDump <文件> [maxDepth]
 */
public class NbtDump {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: NbtDump <file> [maxDepth]");
            return;
        }
        File f = new File(args[0]);
        if (!f.isFile()) {
            System.out.println("ERROR: not a file: " + f);
            return;
        }
        int maxDepth = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        byte[] raw = RegionReader.readAllBytes(f);
        System.out.println("file: " + f.getName() + " (" + raw.length + " bytes)");
        NbtNode root;
        try {
            root = NbtReader.read(new ByteArrayInputStream(raw), true);
        } catch (IOException e) {
            System.out.println("NBT parse failed: " + e);
            return;
        }
        System.out.println("root tag: " + NbtNode.typeName(root.type) + (root.name.isEmpty() ? "" : " \"" + root.name + "\""));
        dumpNode(root, 0, maxDepth, 0);
    }

    static void dumpNode(NbtNode n, int depth, int maxDepth, int listIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append("- ");
        if (listIndex >= 0) sb.append("[").append(listIndex).append("] ");
        else if (!n.name.isEmpty()) sb.append(n.name).append(": ");
        sb.append(NbtNode.typeName(n.type));
        switch (n.type) {
            case NbtNode.TAG_COMPOUND: {
                Map<String, NbtNode> map = n.asMap();
                sb.append(" (").append(map.size()).append(" keys)");
                System.out.println(sb);
                if (depth < maxDepth) {
                    for (Map.Entry<String, NbtNode> e : map.entrySet()) {
                        dumpNode(e.getValue(), depth + 1, maxDepth, -1);
                    }
                }
                return;
            }
            case NbtNode.TAG_LIST: {
                List<NbtNode> list = n.asList();
                sb.append("<").append(NbtNode.typeName(n.listElemType)).append("> (").append(list.size()).append(")");
                System.out.println(sb);
                if (depth < maxDepth) {
                    int shown = 0;
                    for (int i = 0; i < list.size() && shown < 3; i++, shown++) {
                        dumpNode(list.get(i), depth + 1, maxDepth, i);
                    }
                    if (list.size() > 3) System.out.println(pad(depth + 1) + "... (" + (list.size() - 3) + " more)");
                }
                return;
            }
            case NbtNode.TAG_BYTE_ARRAY:
                sb.append(" [" + ((byte[]) n.value).length + " bytes]");
                break;
            case NbtNode.TAG_INT_ARRAY:
                sb.append(" [" + ((int[]) n.value).length + " ints]");
                break;
            case NbtNode.TAG_LONG_ARRAY:
                sb.append(" [" + ((long[]) n.value).length + " longs]");
                break;
            case NbtNode.TAG_STRING:
                sb.append(" = \"").append(n.value).append("\"");
                break;
            default:
                sb.append(" = ").append(n.value);
        }
        System.out.println(sb);
    }

    static String pad(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("  ");
        return sb.toString();
    }
}
