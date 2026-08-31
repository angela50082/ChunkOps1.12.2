package com.chunkops.verify;

import com.chunkops.core.SectionCodec;

import java.io.File;
import java.util.Map;
import java.util.HashMap;

/**
 * 验证 1.12.2 HeightMap 的存储语义（最高方块 y 还是 y+1）：
 * 打印指定 chunk 的 HeightMap 值（前 8 列）与对应列的实际最高非空气方块 y，
 * 两者对比得出结论。用真实存档数据，不依赖游戏运行时。
 *
 * 用法：HeightMapCheck <worldDir> <chunkX> <chunkZ>
 */
public class HeightMapCheck {

    public static void main(String[] args) throws Exception {
        File world = new File(args[0]);
        int cx = Integer.parseInt(args[1]);
        int cz = Integer.parseInt(args[2]);
        int rx = Math.floorDiv(cx, 32);
        int rz = Math.floorDiv(cz, 32);
        File region = new File(new File(world, "region"), "r." + rx + "." + rz + ".mca");
        if (!region.isFile()) {
            System.out.println("no region: " + region);
            return;
        }
        RegionReader rr = new RegionReader(region);
        byte[] payload = rr.readChunkData((cz & 31) * 32 + (cx & 31));
        if (payload == null) {
            System.out.println("chunk not present");
            return;
        }
        NbtNode root = NbtReader.readChunkPayload(payload);
        NbtNode level = root.get("Level");
        if (level == null) {
            System.out.println("no Level");
            return;
        }
        int[] heightMap = null;
        NbtNode hm = level.get("HeightMap");
        System.out.println("HeightMap type: " + (hm != null ? NbtNode.typeName(hm.type) : "ABSENT"));
        if (hm != null && hm.type == NbtNode.TAG_INT_ARRAY) heightMap = (int[]) hm.value;

        Map<Integer, int[]> secStates = new HashMap<Integer, int[]>();
        for (NbtNode sec : SectionCodec.sectionsOf(level)) {
            int[] ids = SectionCodec.decode(sec);
            if (ids != null) secStates.put(SectionCodec.sectionY(sec), ids);
        }
        System.out.println("sections: " + secStates.keySet());

        for (int col = 0; col < 8; col++) {
            int x = col % 16;
            int z = col / 16;
            int actualTop = -1;
            int actualTopStateId = 0;
            // 从高到低找第一个非空气方块（每 section 内列）
            outer:
            for (int secY = 15; secY >= 0; secY--) {
                int[] ids = secStates.get(secY);
                if (ids == null) continue;
                for (int i = 15; i >= 0; i--) {
                    int s = ids[(i << 8) | col];
                    if (s != 0) {
                        actualTop = secY * 16 + i;
                        actualTopStateId = s;
                        break outer;
                    }
                }
            }
            int hmVal = heightMap != null ? heightMap[col] : -1;
            System.out.println("col " + col + " (x=" + x + ",z=" + z + "): HeightMap=" + hmVal
                    + " | 实际最高非空气 y=" + actualTop + " (stateId=" + actualTopStateId + ")"
                    + " | 差值=" + (hmVal - actualTop));
        }
    }
}
