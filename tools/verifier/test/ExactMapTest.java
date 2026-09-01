package com.chunkops.verify;

import com.chunkops.core.ExactMapFile;

import java.io.File;

/**
 * COPM 格式离线验证（round-trip + 边界）：
 * 构建产物不经模组（纯 core 类），可离线快速验证格式正确性。
 * 用法: javac -encoding UTF-8 -d out (src 全量) && java -cp out com.chunkops.verify.ExactMapTest
 */
public class ExactMapTest {

    public static void main(String[] args) throws Exception {
        int n = 256;
        ExactMapFile.ExactRegion src = new ExactMapFile.ExactRegion(n, n);
        // 造数据：对角渐变 + 重复块（RLE）+ 空气列 + 0 值边界
        for (int dz = 0; dz < n; dz++) {
            for (int dx = 0; dx < n; dx++) {
                int i = src.idx(dx, dz);
                if ((dx / 4 + dz / 4) % 3 == 0) {
                    src.color[i] = 0;
                    src.heightY[i] = 0;
                    src.blockIdx[i] = 0;
                    src.biomeIdx[i] = 0;
                    continue;
                }
                int mi = dx % 5;
                if (mi == 0) src.blockNames.add("modid:a#" + (dx % 16));
                if (mi == 1) src.blockNames.add("modid:b#" + (dx % 16));
                src.blockIdx[i] = (short) (mi == 0 ? 1 : (mi == 1 ? 2 : 0));
                src.biomeIdx[i] = dx % 2 == 0 ? (byte) 0 : (byte) 1;
                src.color[i] = 0xFF000000 | (dx << 16) | (dz << 8) | ((dx + dz) & 0xFF);
                src.heightY[i] = (byte) ((dx + dz * 7) & 0xFF);
            }
        }
        src.biomeNames.add("minecraft:plains");

        File dir = new File("test-data/copm-test");
        File f = new File(dir, "r.4.-1.dat");
        ExactMapFile.write(f, src);
        System.out.println("write OK: " + f.getAbsolutePath() + " size=" + f.length());
        if (!f.exists()) throw new IllegalStateException("文件未写入");

        ExactMapFile.ExactRegion dst = ExactMapFile.read(f);
        if (dst == null) throw new IllegalStateException("读回为 null");
        if (dst.width != n || dst.height != n) throw new IllegalStateException("尺寸不符");
        int diff = 0;
        int dataCols = 0;
        int emptyCols = 0;
        for (int dz = 0; dz < n; dz++) {
            for (int dx = 0; dx < n; dx++) {
                int i = dst.idx(dx, dz);
                if (dst.hasData(i)) dataCols++; else emptyCols++;
                if (src.hasData(i)) {
                    if (!(dst.color[i] == src.color[i] && dst.heightY[i] == src.heightY[i]
                            && dst.blockIdx[i] == src.blockIdx[i] && dst.biomeIdx[i] == src.biomeIdx[i])) {
                        diff++;
                    }
                }
            }
        }
        System.out.println("round-trip diff=" + diff + " (期望 0), dataCols=" + dataCols + ", emptyCols=" + emptyCols);
        if (diff != 0) throw new IllegalStateException("round-trip 不一致");
        if (dataCols + emptyCols != n * n) throw new IllegalStateException("列数不符");
        if (dataCols == 0) throw new IllegalStateException("应存在数据列");

        // 覆盖写（增量合并场景：先写一个全空 region，再覆盖；读回应为全空）
        File f2 = new File(dir, "r.5.0.dat");
        ExactMapFile.ExactRegion empty = new ExactMapFile.ExactRegion(n, n);
        ExactMapFile.write(f2, empty);
        System.out.println("overwrite OK");
        ExactMapFile.ExactRegion dstEmpty = ExactMapFile.read(f2);
        if (dstEmpty == null) throw new IllegalStateException("覆盖写读回为 null");
        for (int i = 0; i < n * n; i++) {
            if (dstEmpty.hasData(i)) throw new IllegalStateException("覆盖写后应全空: " + i);
        }
        System.out.println("overwrite readback OK (全空)");

        // 缺失文件 → null（不抛异常）
        if (ExactMapFile.read(new File("test-data/copm-test/bad.dat")) != null) {
            System.out.println("WARN: 缺失文件应返回 null");
        }
        try {
            File corrupt = new File(dir, "bad.dat");
            java.nio.file.Files.write(corrupt.toPath(), new byte[]{'C','O','P','M',1,2,3,4});
            ExactMapFile.read(corrupt);
            System.out.println("ERROR: 损坏文件未抛异常");
            System.exit(1);
        } catch (java.io.IOException expected) {
            System.out.println("corrupt detect OK: " + expected.getMessage());
        }

        // 清理
        for (File x : dir.listFiles()) x.delete();
        dir.delete();
        System.out.println("ExactMapTest PASS");
    }
}
