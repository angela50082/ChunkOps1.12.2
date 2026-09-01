package com.chunkops.verify;

import com.chunkops.core.RegionWriter;
import com.chunkops.verify.NbtNode;

import java.io.File;

/**
 * 原始 palette 条目 dump（JEID 编码确认）：
 * 读 chunk 首个含 Palette 的 section，打印 Palette 前 30 项原始 int + 文件头信息。
 * 用法: java -cp out com.chunkops.verify.PaletteDump <region.mca> <chunkIndex>
 */
public class PaletteDump {

    public static void main(String[] args) throws Exception {
        RegionReader rr = new RegionReader(new File(args[0]));
        byte[] payload = rr.readChunkData(Integer.parseInt(args[1]));
        if (payload == null) {
            System.out.println("chunk 不存在");
            return;
        }
        NbtNode root = RegionWriter.unpackChunk(payload);
        NbtNode level = root.get("Level");
        System.out.println("Level tags: " + level.asMap().keySet());
        NbtNode secs = level.get("Sections");
        if (secs == null || secs.type != NbtNode.TAG_LIST || secs.asList().isEmpty()) {
            System.out.println("无 Sections");
            return;
        }
        NbtNode first = secs.asList().get(0);
        System.out.println("section0 tags: " + first.asMap().keySet());
        NbtNode pal = first.get("Palette");
        if (pal != null) {
            System.out.println("Palette type=" + NbtNode.typeName(pal.type)
                    + " len=" + (pal.value instanceof int[] ? ((int[]) pal.value).length
                    : (pal.value instanceof byte[] ? ((byte[]) pal.value).length : "?")));
            int[] pv = pal.value instanceof int[] ? (int[]) pal.value : null;
            if (pv != null) {
                for (int i = 0; i < Math.min(20, pv.length); i++) {
                    System.out.println("  pal[" + i + "] = " + pv[i] + " (0x" + Integer.toHexString(pv[i]) + ")");
                }
            }
        } else {
            System.out.println("Palette 缺失");
        }
        NbtNode blocks = first.get("Blocks");
        if (blocks != null && blocks.type == NbtNode.TAG_BYTE_ARRAY) {
            byte[] b = (byte[]) blocks.value;
            StringBuilder sb = new StringBuilder("Blocks[0..15] =");
            for (int i = 0; i < 16; i++) sb.append(" ").append(b[i] & 0xFF);
            System.out.println(sb);
        }
        NbtNode data = first.get("Data");
        if (data != null && data.type == NbtNode.TAG_BYTE_ARRAY) {
            byte[] d = (byte[]) data.value;
            StringBuilder sb = new StringBuilder("Data[0..7] =");
            for (int i = 0; i < 8; i++) sb.append(" ").append(String.format("%02X", d[i]));
            System.out.println(sb);
        }
        NbtNode add = first.get("Add");
        if (add != null) {
            byte[] a = (byte[]) add.value;
            StringBuilder sb = new StringBuilder("Add[0..3] =");
            for (int i = 0; i < 4; i++) sb.append(" ").append(String.format("%02X", a[i]));
            System.out.println(sb);
        }
        NbtNode bs = first.get("BlockStates");
        if (bs != null) System.out.println("BlockStates longs=" + ((long[]) bs.value).length);
        else System.out.println("BlockStates 缺失");
        // 找有实体的 section（方块多的）
        for (NbtNode sec : secs.asList()) {
            long used = 0;
            if (sec.get("Palette") != null && sec.get("Palette").type == NbtNode.TAG_LIST) {
                used = sec.get("Palette").asList().size();
            }
            System.out.println("  section Y=" + (sec.get("Y") == null ? "?" : sec.get("Y").value)
                    + " palette=" + used + " tags=" + sec.asMap().keySet());
        }
    }
}
