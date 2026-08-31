package com.chunkops.gui;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.EnumFacing;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

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
            IBakedModel model = Minecraft.getMinecraft().getBlockRendererDispatcher().getModelForState(state);
            if (model == null) return 0xFF888888;
            List<BakedQuad> quads = model.getQuads(state, EnumFacing.UP, 0L);
            if (quads.isEmpty()) {
                quads = model.getQuads(state, null, 0L);
            }
            if (quads.isEmpty()) return 0xFF888888;
            TextureAtlasSprite sprite = quads.get(0).getSprite();
            if (sprite == null) return 0xFF888888;
            int argb = sampleCenter(sprite);
            return argb != 0 ? argb : 0xFF888888;
        } catch (Exception e) {
            return 0xFF888888;
        }
    }

    /** 采样 sprite 纹理中心像素（兼容 frameTextureData 的一维/二维布局）。 */
    static int sampleCenter(TextureAtlasSprite sprite) {
        int w = sprite.getIconWidth();
        int h = sprite.getIconHeight();
        if (w <= 0 || h <= 0) return 0;
        int[][] frame = sprite.getFrameTextureData(0);
        if (frame == null) return 0;
        int cx = w / 2;
        int cy = h / 2;
        // 布局 A：frame[0] = mipmap0 一维像素数组 int[width*height]
        if (frame.length > 0 && frame[0] != null) {
            int[] mip0 = frame[0];
            if (mip0.length >= w * h) return mip0[cy * w + cx];
            if (mip0.length >= w) return mip0[cx]; // 布局 B：一行
        }
        // 布局 C：frame[y] = 行数组
        if (frame.length > cy && frame[cy] != null && frame[cy].length > cx) {
            return frame[cy][cx];
        }
        return 0;
    }

    /** 颜色 × 高度明暗（y 越高越亮）。 */
    public static int shade(int argb, int y) {
        if (argb == 0) return 0;
        double f = 0.72 + 0.28 * (y / 255.0);
        int a = (argb >>> 24) & 0xFF;
        int r = (int) (((argb >> 16) & 0xFF) * f);
        int g = (int) (((argb >> 8) & 0xFF) * f);
        int b = (int) ((argb & 0xFF) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
