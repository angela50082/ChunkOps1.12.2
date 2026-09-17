package com.chunkops.verify;

import com.chunkops.core.RegistrySnapshot;
import com.chunkops.core.SnapshotStore;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 快照归档 / 世界指纹 自检（阶段 A）。
 *
 * 用法：SnapshotStoreCheck &lt;快照.json&gt; &lt;临时gameDir&gt; &lt;临时世界目录&gt;
 *
 * 检查项：
 *   1) SnapshotStore.mappingHash 与导出器写下的 registry-snapshot.hash 边车是否一致
 *      （两处算法必须完全一致，否则会误报「编号漂移」）；
 *   2) 归档幂等：同一内容重复归档只保留一份；
 *   3) 世界指纹写入/读回/解析归档文件；
 *   4) 改一份快照后，指纹与当前会话比对应判定为「不一致」（模拟漂移）。
 */
public class SnapshotStoreCheck {

    /**
     * 完全复刻导出器（ChunkOpsExport.mappingHash）的算法：直接读原始 JSON 的 blocks/biomes 数组顺序，
     * 行格式 name=id / biome:name=id，排序后 '\n' 连接，sha256。
     */
    @SuppressWarnings("unchecked")
    private static String exporterAlgorithmHash(File snapshotFile) throws Exception {
        String text = new String(Files.readAllBytes(snapshotFile.toPath()), StandardCharsets.UTF_8);
        java.util.Map<String, Object> root = com.chunkops.core.Json.asMap(com.chunkops.core.Json.parse(text));
        java.util.List<String> lines = new java.util.ArrayList<String>();
        Object blocks = root.get("blocks");
        if (blocks instanceof java.util.List) {
            for (Object o : (java.util.List<Object>) blocks) {
                java.util.Map<String, Object> m = com.chunkops.core.Json.asMap(o);
                lines.add(com.chunkops.core.Json.str(m, "name") + "=" + com.chunkops.core.Json.lng(m, "id").intValue());
            }
        }
        Object biomes = root.get("biomes");
        if (biomes instanceof java.util.List) {
            for (Object o : (java.util.List<Object>) biomes) {
                java.util.Map<String, Object> m = com.chunkops.core.Json.asMap(o);
                lines.add("biome:" + com.chunkops.core.Json.str(m, "name") + "="
                        + com.chunkops.core.Json.lng(m, "id").intValue());
            }
        }
        java.util.Collections.sort(lines);
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        return SnapshotStore.sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        File snapFile = new File(args[0]);
        File gameDir = new File(args[1]);
        File worldDir = new File(args[2]);
        int fail = 0;

        RegistrySnapshot snap = RegistrySnapshot.load(snapFile);
        String hashFromCore = SnapshotStore.mappingHash(snap);
        System.out.println("快照: " + snapFile.getName() + "  blocks=" + snap.blocks.size()
                + "  biomes=" + snap.biomes.size());
        System.out.println("mappingHash(core) = " + hashFromCore);

        File sidecar = new File(snapFile.getParentFile(), "registry-snapshot.hash");
        if (sidecar.isFile()) {
            String fromExporter = new String(Files.readAllBytes(sidecar.toPath()), StandardCharsets.UTF_8).trim();
            System.out.println("mappingHash(导出器边车) = " + fromExporter);
            if (fromExporter.equals(hashFromCore)) {
                System.out.println("  ✅ 两处哈希算法一致");
            } else {
                System.out.println("  ❌ 两处哈希算法不一致！世界指纹会误报漂移");
                fail++;
            }
        } else {
            System.out.println("  （没有边车文件，改用手工重建导出器算法比对）");
        }

        // 1b) 手工用导出器的算法（直接读原始 JSON 数组顺序 + 同样的行格式与排序）重算一遍，
        //     验证它和 SnapshotStore.mappingHash 完全一致——两者不一致就会误报「编号漂移」。
        String hashFromExporterAlgo = exporterAlgorithmHash(snapFile);
        System.out.println("mappingHash(模拟导出器算法) = " + hashFromExporterAlgo);
        if (hashFromExporterAlgo.equals(hashFromCore)) {
            System.out.println("  ✅ 导出器算法与 core 一致");
        } else {
            System.out.println("  ❌ 导出器算法与 core 不一致！");
            fail++;
        }

        // 2) 归档幂等
        int before = SnapshotStore.listArchived(gameDir).size();
        File a1 = SnapshotStore.archiveFile(gameDir, snapFile);
        File a2 = SnapshotStore.archiveFile(gameDir, snapFile);
        System.out.println("归档: " + a1.getName() + " / 再归档: " + a2.getName());
        if (a1.equals(a2)) {
            System.out.println("  ✅ 归档幂等（同内容只留一份）");
        } else {
            System.out.println("  ❌ 归档不幂等");
            fail++;
        }
        int after = SnapshotStore.listArchived(gameDir).size();
        if (after == before + 1) {
            System.out.println("  ✅ 归档目录只增加 1 份（" + before + " → " + after + "）");
        } else {
            System.out.println("  ❌ 归档目录份数异常: " + before + " → " + after);
            fail++;
        }

        // 3) 世界指纹
        SnapshotStore.writeWorldMark(worldDir, new SnapshotStore.WorldMark(hashFromCore, a1.getName(), 1234567890L));
        SnapshotStore.WorldMark mark = SnapshotStore.readWorldMark(worldDir);
        if (mark != null && hashFromCore.equals(mark.mappingHash) && a1.getName().equals(mark.snapshot)) {
            System.out.println("  ✅ 世界指纹写入/读回一致（" + SnapshotStore.worldMarkFile(worldDir).getName() + "）");
        } else {
            System.out.println("  ❌ 世界指纹读回不一致");
            fail++;
        }
        File resolved = SnapshotStore.markSnapshotFile(gameDir, mark);
        if (resolved != null && resolved.getName().equals(a1.getName())) {
            System.out.println("  ✅ 指纹能解析到归档快照: " + resolved.getName());
        } else {
            System.out.println("  ❌ 指纹解析不到归档快照");
            fail++;
        }

        // 4) 模拟漂移：把快照改一个 id，再进行比对
        RegistrySnapshot drifted = RegistrySnapshot.load(snapFile);
        if (!drifted.blocks.isEmpty()) {
            drifted.blocks.get(0).id += 16;
        }
        String driftedHash = SnapshotStore.mappingHash(drifted);
        System.out.println("漂移后 hash = " + driftedHash);
        if (driftedHash.equals(hashFromCore)) {
            System.out.println("  ❌ 改了 id 哈希却没变");
            fail++;
        } else {
            System.out.println("  ✅ 编号变化能被检出（指纹不同 ⇒ 会提示漂移）");
        }

        System.out.println(fail == 0 ? "全部通过" : ("失败 " + fail + " 项"));
        if (fail > 0) System.exit(1);
    }
}
