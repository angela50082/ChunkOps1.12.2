package com.chunkops.verify;

import com.chunkops.core.RegionWriter;
import com.chunkops.verify.NbtNode;

import java.io.File;
import java.util.List;

/**
 * 检查 chunk 内 TE/实体坐标与 chunk 坐标的一致性（粘贴平移诊断）。
 * 用法: java -cp out com.chunkops.verify.TeCoordCheck <region.mca> <chunkIndex> [显示全部]
 */
public class TeCoordCheck {

    public static void main(String[] args) throws Exception {
        RegionReader rr = new RegionReader(new File(args[0]));
        int index = Integer.parseInt(args[1]);
        byte[] payload = rr.readChunkData(index);
        if (payload == null) {
            System.out.println("chunk 不存在");
            return;
        }
        NbtNode root = RegionWriter.unpackChunk(payload);
        NbtNode level = root.get("Level");
        int cx = ((Number) level.get("xPos").value).intValue();
        int cz = ((Number) level.get("zPos").value).intValue();
        System.out.println("chunk 坐标 = (" + cx + "," + cz + ")  → 块坐标范围 x[" + (cx * 16) + ".." + (cx * 16 + 15)
                + "] z[" + (cz * 16) + ".." + (cz * 16 + 15) + "]");
        NbtNode tes = level.get("TileEntities");
        if (tes != null && tes.type == NbtNode.TAG_LIST) {
            List<NbtNode> list = tes.asList();
            System.out.println("TileEntities 数量=" + list.size());
            for (NbtNode te : list) {
                int x = te.get("x") != null ? ((Number) te.get("x").value).intValue() : -1;
                int y = te.get("y") != null ? ((Number) te.get("y").value).intValue() : -1;
                int z = te.get("z") != null ? ((Number) te.get("z").value).intValue() : -1;
                String id = te.get("id") != null ? (String) te.get("id").value : "?";
                boolean inX = x >= cx * 16 && x < cx * 16 + 16;
                boolean inZ = z >= cz * 16 && z < cz * 16 + 16;
                System.out.println("  TE " + id + " pos=(" + x + "," + y + "," + z + ")"
                        + (inX && inZ ? " ✓在chunk内" : " ✗越界！"));
            }
        } else {
            System.out.println("无 TileEntities");
        }
        NbtNode ents = level.get("Entities");
        if (ents != null && ents.type == NbtNode.TAG_LIST) {
            List<NbtNode> list = ents.asList();
            System.out.println("Entities 数量=" + list.size());
            int bad = 0;
            for (NbtNode ent : list) {
                NbtNode pos = ent.get("Pos");
                if (pos != null && pos.type == NbtNode.TAG_LIST && pos.asList().size() >= 3) {
                    double x = ((Number) pos.asList().get(0).value).doubleValue();
                    double z = ((Number) pos.asList().get(2).value).doubleValue();
                    boolean inX = x >= cx * 16 && x < cx * 16 + 16;
                    boolean inZ = z >= cz * 16 && z < cz * 16 + 16;
                    if (!inX || !inZ) {
                        bad++;
                        System.out.println("  实体越界 pos=(" + x + ",?," + z + ")");
                    }
                }
            }
            System.out.println("实体越界数=" + bad);
        } else {
            System.out.println("无 Entities");
        }
    }
}
