package com.chunkops.verify;

import com.chunkops.core.ExactMapFile;

import java.io.File;

/**
 * COPM 数据文件诊断（P2 调试）：
 * 打印尺寸/字典/覆盖分布（每 chunk 16×16 列计数，32×32 网格）/水列颜色样本/红色系比例。
 * 用法: java -cp out com.chunkops.verify.ExactMapDump <file.dat>
 */
public class ExactMapDump {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("用法: ExactMapDump <file.dat>");
            return;
        }
        ExactMapFile.ExactRegion r = ExactMapFile.read(new File(args[0]));
        if (r == null) {
            System.out.println("null（文件不存在或不可读）");
            return;
        }
        System.out.println("尺寸: " + r.width + "x" + r.height + "  列总数=" + r.width * r.height);
        System.out.println("方块字典 (" + r.blockNames.size() + "):");
        for (int i = 0; i < r.blockNames.size(); i++) System.out.println("  [" + i + "] " + r.blockNames.get(i));
        System.out.println("群系字典 (" + r.biomeNames.size() + "): " + r.biomeNames);

        int cw = r.width / 16, ch = r.height / 16;
        System.out.println("每 chunk 数据列数（" + cw + "x" + ch + " 网格，行=z）：");
        for (int cz = 0; cz < ch; cz++) {
            StringBuilder sb = new StringBuilder();
            for (int cx = 0; cx < cw; cx++) {
                int cnt = 0;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int gi = (cz * 16 + z) * r.width + (cx * 16 + x);
                        if (gi < r.color.length && r.hasData(gi)) cnt++;
                    }
                }
                sb.append(String.format("%4d", cnt));
            }
            System.out.println(sb);
        }

        // 水列颜色样本
        for (int i = 0; i < r.blockNames.size(); i++) {
            String n = r.blockNames.get(i);
            if (n.contains("water")) {
                System.out.println("water 索引 " + i + " (" + n + ") 颜色样本:");
                java.util.Map<Integer, Integer> histo = new java.util.TreeMap<Integer, Integer>();
                for (int gi = 0; gi < r.color.length; gi++) {
                    if (r.blockIdx[gi] == i) {
                        Integer c = histo.get(r.color[gi]);
                        histo.put(r.color[gi], c == null ? 1 : c + 1);
                    }
                }
                for (java.util.Map.Entry<Integer, Integer> e : histo.entrySet()) {
                    int c = e.getKey();
                    System.out.println(String.format("  color=%08X (R=%d G=%d B=%d) count=%d",
                            c, (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, e.getValue()));
                }
            }
        }

        // 红色系/蓝色系统计（整体）
        int reds = 0, blues = 0, n = 0;
        for (int gi = 0; gi < r.color.length; gi++) {
            if (!r.hasData(gi)) continue;
            n++;
            int rr = (r.color[gi] >> 16) & 0xFF;
            int gg = (r.color[gi] >> 8) & 0xFF;
            int bb = r.color[gi] & 0xFF;
            if (rr > 120 && rr > bb * 2 && gg < rr) reds++;
            if (bb > 120 && bb > rr * 2) blues++;
        }
        System.out.println("有数据列=" + n + "  红色系=" + reds + "  蓝色系=" + blues);
    }
}
