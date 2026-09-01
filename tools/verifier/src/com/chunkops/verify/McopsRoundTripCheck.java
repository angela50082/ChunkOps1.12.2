package com.chunkops.verify;

import com.chunkops.core.Mcops;
import com.chunkops.core.RegistrySnapshot;
import com.chunkops.core.RegionWriter;
import com.chunkops.core.SectionCodec;
import com.chunkops.verify.NbtNode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * .mcops 导入安全验证（游戏可加载性）：
 * 读真实 region chunk → 导出 → 导入 → 检查 Sections 的 SkyLight/BlockLight 均为 2048 字节。
 * 背景：1.12.2 AnvilChunkLoader 对缺失光照抛 "ChunkNibbleArrays should be 2048 bytes not: 0"。
 * 用法: java -cp out com.chunkops.verify.McopsRoundTripCheck <region.mca> [index]
 */
public class McopsRoundTripCheck {

    public static void main(String[] args) throws Exception {
        File region = new File(args[0]);
        int index = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        RegionReader rr = new RegionReader(region);
        byte[] payload = rr.readChunkData(index);
        if (payload == null) {
            System.out.println("chunk 不存在: index=" + index);
            return;
        }
        NbtNode root = RegionWriter.unpackChunk(payload);

        // 导出（需一个快照——用假快照：名称化仅需要 id→name；用最小实现占位）
        RegistrySnapshot snap = new RegistrySnapshot();
        // 这里不能离线获得活注册表；但导出只查 lookupBlockName(id)，Air 直接映射。
        // 用 ID 自映射快照验证结构即可（名称格式 modid:block#meta 以 unknown:id#meta 兜底）。
        List<NbtNode> roots = new ArrayList<NbtNode>();
        roots.add(root);
        byte[] blob = Mcops.exportChunks(roots, snap, "", "check");
        System.out.println("export OK, blob=" + blob.length + "B");

        Mcops.ImportReport report = new Mcops.ImportReport();
        List<NbtNode> imported = Mcops.importChunks(blob, snap, report);
        NbtNode level = imported.get(0).get("Level");
        System.out.println("import OK: chunks=" + report.chunks + " sections=" + report.sections);

        int bad = 0;
        NbtNode secs = level.get("Sections");
        int secCount = 0;
        if (secs != null && secs.type == NbtNode.TAG_LIST) {
            for (NbtNode sec : secs.asList()) {
                secCount++;
                for (String key : new String[]{"SkyLight", "BlockLight"}) {
                    NbtNode l = sec.get(key);
                    if (l == null || l.type != NbtNode.TAG_BYTE_ARRAY
                            || ((byte[]) l.value).length != 2048) {
                        bad++;
                        System.out.println("BAD: section Y=" + SectionCodec.sectionY(sec)
                                + " " + key + " = " + (l == null ? "缺失" : ((byte[]) l.value).length + "B"));
                    }
                }
            }
        }
        System.out.println("sections=" + secCount + ", 光照缺失数=" + bad);
        if (bad > 0) {
            System.out.println("FAIL: 仍有光照缺失（游戏将崩溃）");
            System.exit(1);
        }
        // 方块重解码验证
        int total = 0;
        for (NbtNode sec : secs.asList()) {
            int[] ids = SectionCodec.decode(sec);
            if (ids != null) total += ids.length;
        }
        System.out.println("sections 方块解码总数=" + total);
        System.out.println("PASS: .mcops 导入产物含 2048B 光照，游戏可加载");
    }
}
