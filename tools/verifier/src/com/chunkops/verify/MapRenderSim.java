package com.chunkops.verify;

import com.chunkops.core.ExactMapFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 精确层离线渲染模拟（P2 调试）：按 ChunkMapRenderer 的 tile 逻辑
 * （gi = (baseZ+z)*width + (baseX+x)，base=(c&31)*16）把 COPM 数据渲染成 PNG，
 * 用于与游戏内截图对比定位"红水/错位/重影"。
 * 用法: java -cp out com.chunkops.verify.MapRenderSim <dat> <out.png> [scale]
 */
public class MapRenderSim {

    public static void main(String[] args) throws Exception {
        File dat = new File(args[0]);
        File out = new File(args[1]);
        int scale = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        ExactMapFile.ExactRegion r = ExactMapFile.read(dat);
        if (r == null) {
            System.out.println("null dat");
            return;
        }
        // 复现显示端（含越界行为）：逐 chunk 渲染 16×16
        int chunksX = r.width / 16, chunksZ = r.height / 16;
        BufferedImage img = new BufferedImage(r.width * scale, r.height * scale, BufferedImage.TYPE_INT_ARGB);
        for (int cz = 0; cz < chunksZ; cz++) {
            for (int cx = 0; cx < chunksX; cx++) {
                int baseX = (cx & 31) * 16;
                int baseZ = (cz & 31) * 16;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int gi = (baseZ + z) * r.width + (baseX + x);
                        int c = 0xFF333333; // 无数据：中性灰
                        boolean ok = false;
                        if (gi >= 0 && gi < r.color.length) {
                            ok = true;
                            c = r.color[gi];
                        }
                        if (!r.hasData(gi) || gi >= r.color.length) c = 0xFF333333;
                        for (int dy = 0; dy < scale; dy++) {
                            for (int dx = 0; dx < scale; dx++) {
                                int px = (cx * 16 + x) * scale + dx;
                                int py = (cz * 16 + z) * scale + dy;
                                img.setRGB(px, py, c);
                            }
                        }
                    }
                }
            }
        }
        ImageIO.write(img, "png", out);
        System.out.println("sim written: " + out.getAbsolutePath() + " (" + img.getWidth() + "x" + img.getHeight() + ")");
    }
}
