package com.chunkops.gui;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.EnumFacing;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 方块颜色缓存（阶段 3 地图渲染核心）：
 * stateId → 顶面 quad 纹理中心像素色（真实模组纹理，非猜测色）。
 *
 * 规则：优先朝上 quad（地表观感）；无顶面（横放机器等）回退任意面；
 * 全无 → 灰色。线程安全（异步加载线程调用）。
 */
public class MapColorCache {

    private final Map<Integer, Integer> cache = new ConcurrentHashMap<Integer, Integer>();
    private int fallbackCount = 0;

    public int colorFor(int stateId) {
        Integer c = cache.get(stateId);
        if (c != null) return c;
        int color = computeColor(stateId);
        cache.put(stateId, color);
        return color;
    }

    int computeColor(int stateId) {
        try {
            IBlockState state = Block.getStateById(stateId);
            if (state == null) return 0xFF888888;
            // MapColor 优先（=原版地图该方块的色：水=蓝/草=浅绿/岩浆=橙…），与精确层底色一致，
            // 解决"纹理中心色"的通道错乱/品红问题（水变紫等）。通用灰/金属色例外（见下）。
            net.minecraft.block.material.MapColor mc = null;
            try {
                mc = state.getMapColor(null, BlockPos.ORIGIN);
            } catch (Exception ignored) {
                // 部分方块 getMapColor(null) 异常 → 走纹理色
            }
            if (mc != null && mc.colorValue != 0 && !isGenericMapColor(mc)) {
                return 0xFF000000 | mc.colorValue;
            }
            // 通用灰/金属色（很多模组机器默认共用）→ 纹理中心色区分它们
            IBakedModel model = Minecraft.getMinecraft().getBlockRendererDispatcher().getModelForState(state);
            if (model != null) {
                for (BakedQuad quad : quadsOf(model, state)) {
                    TextureAtlasSprite sprite = quad.getSprite();
                    if (sprite == null) continue;
                    try {
                        TextureAtlasSprite registered = Minecraft.getMinecraft().getTextureMapBlocks()
                                .getAtlasSprite(sprite.getIconName());
                        if (registered != null) sprite = registered;
                    } catch (Exception ignored) {
                        // 保持原 sprite
                    }
                    int c = quadColor(quad, sprite);
                    if (isValidColor(c)) return c;
                }
            }
            // 兜底：MapColor（即使通用色），否则灰
            return (mc != null && mc.colorValue != 0) ? (0xFF000000 | mc.colorValue) : 0xFF888888;
        } catch (Exception e) {
            return 0xFF888888;
        }
    }

    /** 通用灰/金属 MapColor：这类色被大量模组机器默认共用，用纹理中心色更能区分。 */
    private static boolean isGenericMapColor(net.minecraft.block.material.MapColor mc) {
        return mc.colorIndex == net.minecraft.block.material.MapColor.STONE.colorIndex
                || mc.colorIndex == net.minecraft.block.material.MapColor.IRON.colorIndex
                || mc.colorIndex == net.minecraft.block.material.MapColor.GRAY.colorIndex
                || mc.colorIndex == net.minecraft.block.material.MapColor.SILVER.colorIndex
                || mc.colorIndex == net.minecraft.block.material.MapColor.BLACK.colorIndex
                || mc.colorIndex == net.minecraft.block.material.MapColor.AIR.colorIndex;
    }

    private static java.util.List<BakedQuad> quadsOf(IBakedModel model, IBlockState state) {
        java.util.List<BakedQuad> quads = model.getQuads(state, EnumFacing.UP, 0L);
        if (quads.isEmpty()) quads = model.getQuads(state, null, 0L);
        return quads;
    }

    /** 颜色是否有效：alpha 足够且非品红（缺失纹理标志色）。 */
    private static boolean isValidColor(int c) {
        if (c == 0) return false;
        int a = (c >>> 24) & 0xFF;
        if (a < 128) return false;
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        // 品红/紫黑格（缺失纹理）：R 高、B 高、G 低
        if (r > 180 && b > 180 && g < 100) return false;
        return true;
    }

    /**
     * 采样 sprite 纹理中心 + 周围 3×3 平均（跳过透明像素）。
     * 注意：1.12.2 TextureAtlasSprite 帧数据像素为 **ABGR**（0xAABBGGRR，GL_RGBA 字节序），
     * 须按 r=低字节 提取；原按 ARGB 提取会红蓝交换（与 textureFor 的旧转换双重抵消成
     * 文件级"看起来正确"的假象——色板自检已实证）。
     */
    static int quadColor(BakedQuad quad, TextureAtlasSprite sprite) {
        int w = sprite.getIconWidth();
        int h = sprite.getIconHeight();
        if (w <= 0 || h <= 0) return 0;
        int px = clamp(w / 2, 0, w - 1);
        int py = clamp(h / 2, 0, h - 1);
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int c = pixelAt(sprite, px + dx, py + dy);
                if (c == 0) continue;
                int a = (c >>> 24) & 0xFF;
                if (a < 64) continue;
                // ABGR：低字节=R，bit8-15=G，bit16-23=B
                r += c & 0xFF;
                g += (c >> 8) & 0xFF;
                b += (c >> 16) & 0xFF;
                n++;
            }
        }
        if (n == 0) return 0;
        return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
    }

    /** 取 sprite 帧数据中的一个像素（兼容一维 [mipmap][w*h] / 二维 [h][w] 布局）。 */
    static int pixelAt(TextureAtlasSprite sprite, int x, int y) {
        int w = sprite.getIconWidth();
        int h = sprite.getIconHeight();
        if (w <= 0 || h <= 0 || x < 0 || x >= w || y < 0 || y >= h) return 0;
        int[][] frame = sprite.getFrameTextureData(0);
        if (frame == null) return 0;
        int[] best = null;
        for (int[] row : frame) {
            if (row != null && (best == null || row.length > best.length)) best = row;
        }
        if (best == null) return 0;
        if (best.length >= w * h) return best[y * w + x];
        if (frame.length > y && frame[y] != null && frame[y].length > x) return frame[y][x];
        return 0;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** 颜色 × 高度明暗（y 越高越亮）。0.92 起（用户反馈仍暗 → 接近原色，仅轻微高度 shading）。 */
    public static int shade(int argb, int y) {
        if (argb == 0) return 0;
        double f = 0.92 + 0.08 * (y / 255.0);
        return scale(argb, f);
    }

    /**
     * 立体感明暗（精确层）：高度亮度 + 坡度阴影（光从西北）。
     * 东/南邻域较高 → 该列处于坡地迎光面 → 提亮；反之背光 → 压暗。参数为邻列高度。
     */
    public static int shadeRelief(int argb, int y, int hE, int hW, int hN, int hS) {
        if (argb == 0) return 0;
        double base = 0.92 + 0.08 * (y / 255.0);
        double slope = ((hE - hW) + (hS - hN)) * 0.014;
        double f = base + slope;
        f = f < 0.66 ? 0.66 : (f > 1.24 ? 1.24 : f);
        return scale(argb, f);
    }

    private static int scale(int argb, double f) {
        int a = (argb >>> 24) & 0xFF;
        int r = (int) (((argb >> 16) & 0xFF) * f);
        int g = (int) (((argb >> 8) & 0xFF) * f);
        int b = (int) ((argb & 0xFF) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
