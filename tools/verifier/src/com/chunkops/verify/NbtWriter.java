package com.chunkops.verify;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * NBT 写出器（与 Minecraft 1.12.2 格式一致；字符串用 modified UTF-8）。
 */
public class NbtWriter {

    public static byte[] writeRoot(NbtNode root) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeByte(root.type);
        dos.writeUTF(root.name);
        writePayload(dos, root);
        dos.flush();
        return bos.toByteArray();
    }

    private static void writePayload(DataOutputStream dos, NbtNode n) throws IOException {
        switch (n.type) {
            case NbtNode.TAG_BYTE:
                dos.writeByte(((Number) n.value).byteValue());
                break;
            case NbtNode.TAG_SHORT:
                dos.writeShort(((Number) n.value).shortValue());
                break;
            case NbtNode.TAG_INT:
                dos.writeInt(((Number) n.value).intValue());
                break;
            case NbtNode.TAG_LONG:
                dos.writeLong(((Number) n.value).longValue());
                break;
            case NbtNode.TAG_FLOAT:
                dos.writeFloat(((Number) n.value).floatValue());
                break;
            case NbtNode.TAG_DOUBLE:
                dos.writeDouble(((Number) n.value).doubleValue());
                break;
            case NbtNode.TAG_BYTE_ARRAY: {
                byte[] arr = (byte[]) n.value;
                dos.writeInt(arr.length);
                dos.write(arr);
                break;
            }
            case NbtNode.TAG_STRING:
                dos.writeUTF((String) n.value);
                break;
            case NbtNode.TAG_LIST: {
                dos.writeByte(n.listElemType);
                dos.writeInt(n.asList().size());
                for (NbtNode e : n.asList()) writePayload(dos, e);
                break;
            }
            case NbtNode.TAG_COMPOUND: {
                for (Map.Entry<String, NbtNode> e : n.asMap().entrySet()) {
                    dos.writeByte(e.getValue().type);
                    dos.writeUTF(e.getKey());
                    writePayload(dos, e.getValue());
                }
                dos.writeByte(NbtNode.TAG_END);
                break;
            }
            case NbtNode.TAG_INT_ARRAY: {
                int[] arr = (int[]) n.value;
                dos.writeInt(arr.length);
                for (int v : arr) dos.writeInt(v);
                break;
            }
            case NbtNode.TAG_LONG_ARRAY: {
                long[] arr = (long[]) n.value;
                dos.writeInt(arr.length);
                for (long v : arr) dos.writeLong(v);
                break;
            }
            default:
                throw new IOException("无法写出 NBT 类型: " + n.type);
        }
    }
}
