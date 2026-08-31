package com.chunkops.verify;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * NBT 读取器（兼容 gzip / zlib / 原始流），与 Minecraft 1.12.2 的写入一致。
 */
public class NbtReader {

    /**
     * 从字节流读取一个具名根节点。
     * @param in 输入流（已就位到 NBT 开始处）
     * @param allowCompression 是否尝试自动识别 gzip/zlib
     */
    public static NbtNode read(InputStream in, boolean allowCompression) throws IOException {
        if (allowCompression) {
            BufferedInputStream bin = new BufferedInputStream(in);
            bin.mark(2);
            int b1 = bin.read();
            int b2 = bin.read();
            bin.reset();
            if (b1 == 0x1f && b2 == 0x8b) {
                in = new GZIPInputStream(bin);
            } else if (b1 == 0x78) {
                in = new InflaterInputStream(bin);
            }
        }
        DataInputStream din = new DataInputStream(in);
        int type = din.readByte();
        String name = din.readUTF();
        NbtNode root = readPayload(din, type);
        root.name = name;
        return root;
    }

    public static NbtNode readRaw(byte[] data) throws IOException {
        return read(new ByteArrayInputStream(data), false);
    }

    private static NbtNode readPayload(DataInputStream din, int type) throws IOException {
        switch (type) {
            case NbtNode.TAG_BYTE:
                return scalar(NbtNode.TAG_BYTE, din.readByte());
            case NbtNode.TAG_SHORT:
                return scalar(NbtNode.TAG_SHORT, din.readShort());
            case NbtNode.TAG_INT:
                return scalar(NbtNode.TAG_INT, din.readInt());
            case NbtNode.TAG_LONG:
                return scalar(NbtNode.TAG_LONG, din.readLong());
            case NbtNode.TAG_FLOAT:
                return scalar(NbtNode.TAG_FLOAT, din.readFloat());
            case NbtNode.TAG_DOUBLE:
                return scalar(NbtNode.TAG_DOUBLE, din.readDouble());
            case NbtNode.TAG_BYTE_ARRAY: {
                int len = din.readInt();
                byte[] arr = new byte[len];
                din.readFully(arr);
                NbtNode n = new NbtNode(NbtNode.TAG_BYTE_ARRAY);
                n.value = arr;
                return n;
            }
            case NbtNode.TAG_STRING: {
                NbtNode n = new NbtNode(NbtNode.TAG_STRING);
                n.value = din.readUTF();
                return n;
            }
            case NbtNode.TAG_LIST: {
                int elemType = din.readByte();
                int len = din.readInt();
                List<NbtNode> list = new ArrayList<NbtNode>(len);
                for (int i = 0; i < len; i++) {
                    list.add(readPayload(din, elemType));
                }
                NbtNode n = new NbtNode(NbtNode.TAG_LIST);
                n.value = list;
                n.listElemType = elemType;
                return n;
            }
            case NbtNode.TAG_COMPOUND: {
                Map<String, NbtNode> map = new LinkedHashMap<String, NbtNode>();
                while (true) {
                    int t = din.readByte();
                    if (t == NbtNode.TAG_END) break;
                    String k = din.readUTF();
                    NbtNode child = readPayload(din, t);
                    child.name = k;
                    map.put(k, child);
                }
                NbtNode n = new NbtNode(NbtNode.TAG_COMPOUND);
                n.value = map;
                return n;
            }
            case NbtNode.TAG_INT_ARRAY: {
                int len = din.readInt();
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) arr[i] = din.readInt();
                NbtNode n = new NbtNode(NbtNode.TAG_INT_ARRAY);
                n.value = arr;
                return n;
            }
            case NbtNode.TAG_LONG_ARRAY: {
                int len = din.readInt();
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) arr[i] = din.readLong();
                NbtNode n = new NbtNode(NbtNode.TAG_LONG_ARRAY);
                n.value = arr;
                return n;
            }
            default:
                throw new IOException("未知 NBT 类型: " + type);
        }
    }

    private static NbtNode scalar(int type, Object v) {
        NbtNode n = new NbtNode(type);
        n.value = v;
        return n;
    }

    /**
     * 从 chunk 的压缩字节（长度前缀后的 payload）读取：长度4字节 + 压缩类型1字节 + NBT。
     */
    public static NbtNode readChunkPayload(byte[] chunkData) throws IOException {
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(chunkData));
        int len = din.readInt();
        int compression = din.readByte();
        byte[] payload = new byte[len - 1];
        din.readFully(payload);
        InputStream in = new ByteArrayInputStream(payload);
        switch (compression) {
            case 1: in = new GZIPInputStream(in); break;
            case 2: in = new InflaterInputStream(in); break;
            case 3: break; // 无压缩
            default: throw new IOException("未知压缩类型: " + compression);
        }
        return read(in, false);
    }

    @SuppressWarnings("unused")
    private static void skipFully(DataInputStream din, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            int skipped = (int) Math.min(remaining, Integer.MAX_VALUE);
            int s = din.skipBytes(skipped);
            if (s <= 0) throw new EOFException("无法跳过 " + n + " 字节");
            remaining -= s;
        }
    }
}
