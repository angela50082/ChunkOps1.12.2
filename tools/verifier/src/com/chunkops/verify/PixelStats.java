package com.chunkops.verify;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 截图像素分析（P2 调试）：统计红系/蓝系像素按屏幕列（chunk 桶）分布，
 * 对比左右半（以 x=0 中线为界）是否镜像重复。
 * 用法: java -cp out com.chunkops.verify.PixelStats <png> [ppb=2]
 */
public class PixelStats {

    public static void main(String[] args) throws Exception {
        BufferedImage img = ImageIO.read(new File(args[0]));
        double ppb = args.length > 1 ? Double.parseDouble(args[1]) : 2.0;
        int w = img.getWidth(), h = img.getHeight();
        int cx0 = w / 2; // 屏幕中心（viewX 处）
        System.out.println("size=" + w + "x" + h + " ppb=" + ppb + " chunkPx=" + (int) (16 * ppb));

        // 按 chunk 桶（屏幕列）统计
        int bucketPx = (int) Math.round(16 * ppb);
        int n = (w + bucketPx - 1) / bucketPx;
        int[] red = new int[n], blue = new int[n], total = new int[n];
        java.util.Map<Integer, Integer> histo = new java.util.TreeMap<Integer, Integer>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r < 30 && g < 30 && b < 30) continue; // 背景/网格暗色过滤
                int bucket = x / bucketPx;
                total[bucket]++;
                Integer c = histo.get(rgb & 0xFFFFFF);
                histo.put(rgb & 0xFFFFFF, c == null ? 1 : c + 1);
                if (r > 40 && r > g + 8 && r > b + 8) red[bucket]++;
                if (b > 40 && b > r + 8 && b > g + 8) blue[bucket]++;
            }
        }
        System.out.println("Top20 颜色 (RGB, count):");
        java.util.List<java.util.Map.Entry<Integer, Integer>> list =
                new java.util.ArrayList<java.util.Map.Entry<Integer, Integer>>(histo.entrySet());
        java.util.Collections.sort(list, new java.util.Comparator<java.util.Map.Entry<Integer, Integer>>() {
            public int compare(java.util.Map.Entry<Integer, Integer> a, java.util.Map.Entry<Integer, Integer> b) {
                return Integer.compare(b.getValue(), a.getValue());
            }
        });
        for (int i = 0; i < Math.min(20, list.size()); i++) {
            java.util.Map.Entry<Integer, Integer> e = list.get(i);
            int c = e.getKey();
            System.out.println(String.format("  #%06X (R=%3d G=%3d B=%3d) x%d", c, (c >> 16) & 0xFF,
                    (c >> 8) & 0xFF, c & 0xFF, e.getValue()));
        }
        System.out.println("屏幕列→方块x  | 红 | 蓝 | 总(亮)");
        for (int i = 0; i < n; i++) {
            long bx = Math.round((i * bucketPx + bucketPx / 2 - cx0) / ppb); // 相对中心（viewX）的方块 x
            System.out.println(String.format("col%2d bx~%4d | %4d | %4d | %4d", i, bx, red[i], blue[i], total[i]));
        }
        // 左右半汇总
        int lr = 0, lb = 0, rr = 0, rb = 0;
        for (int i = 0; i < n; i++) {
            if ((i * bucketPx + bucketPx / 2) < cx0) { lr += red[i]; lb += blue[i]; }
            else { rr += red[i]; rb += blue[i]; }
        }
        System.out.println("左半(x<center): red=" + lr + " blue=" + lb + " | 右半: red=" + rr + " blue=" + rb);
    }
}
