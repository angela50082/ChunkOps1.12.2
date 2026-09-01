package com.chunkops.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.storage.WorldSummary;
import net.minecraftforge.fml.common.Loader;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 主菜单 MCA 式区块编辑器（阶段 3）。
 *
 * 当前骨架：存档列表（Saves 目录）、视图状态（中心坐标/缩放）、区块网格渲染、
 * 状态栏与控制按钮。2D 取色地图渲染、框选与操作菜单在后续增量中实现。
 */
public class ChunkOpsEditorScreen extends GuiScreen {

    private static final int BTN_BACK = 3;
    private static final int BTN_READONLY = 4;

    private final List<WorldSummary> worlds = new ArrayList<WorldSummary>();
    private int selectedWorld = -1;
    private File currentWorldDir = null;
    private final ChunkMapRenderer renderer = new ChunkMapRenderer();

    // 视图状态（方块坐标）
    private double viewX = 8;
    private double viewZ = 8;
    private double zoom = 2.0; // 像素/方块

    // 交互状态
    private int selMinCx = -1, selMaxCx = -1, selMinCz = -1, selMaxCz = -1; // 选区（chunk 坐标，-1=无）
    private boolean selecting = false;
    private boolean moving = false;
    private int lastMouseX, lastMouseY;
    /** 存档下拉菜单（替代左右切换按钮）。 */
    private boolean worldMenuOpen = false;
    private final java.util.List<String> log = new ArrayList<String>();

    private boolean readOnly = false;
    private java.util.Map<Long, byte[]> clipboard = null;
    private int clipOriginCx = 0, clipOriginCz = 0; // 剪贴板源选区左上角
    /** 面板底部同步提示（logLine 时更新）。 */
    private String panelNotice = "";

    // ---- 粘贴预览（MCA 两段式）：预览不写盘，左键直接拖动轮廓定位，确认后才真正粘贴 ----
    private boolean pastePreview = false;
    private int pasteTargetCx = 0, pasteTargetCz = 0; // 预览目标左上角 chunk
    private volatile java.util.Map<Long, ChunkMapRenderer.ChunkMapTile> previewTiles = null; // 后台解码结果
    private int previewW = 0, previewH = 0; // 预览尺寸（chunk 数）
    private boolean dragPreview = false;    // 预览态左键拖动中

    // ------------------------------------------------------------ 右侧工具面板（功能控件）

    private static final int PANEL_W = 190;
    // 滑块：zoom = 0.5 * 64^val（对数映射 0.5..32）
    private double zoomVal = -1; // 初始化为当前 zoom 的反算（initGui 时）
    private boolean zoomDrag = false;
    private int zoomSliderX, zoomSliderY, zoomSliderW = 150;

    // 统计面板
    private java.util.List<String[]> statsEntries = new java.util.ArrayList<String[]>(); // {name,count,pct}
    private String statsSummary = "统计：先框选，再点「统计」";
    private int statsSortCol = 1;      // 0=名称 1=数量
    private boolean statsDesc = true;
    private int statsScroll = 0;       // 行偏移
    private String statsFilter = "";   // 已应用的过滤文本
    private net.minecraft.client.gui.GuiTextField statsBox;
    private volatile Object[] statsResult = null; // 后台统计结果
    private boolean statsBusy = false;

    private boolean helpOpen = false;

    private void logLine(String s) {
        log.add(s);
        while (log.size() > 6) log.remove(0);
        panelNotice = s; // 面板同步提示
    }

    /** 地图画布水平中心（右侧面板之外的区域中心）。 */
    private int mapCenterX() {
        return (this.width - PANEL_W) / 2;
    }

    /** 屏幕坐标 → 方块坐标（地图区域，扣除右侧面板）。 */
    private double screenToBlockX(int mx) {
        return viewX + (mx - mapCenterX()) / zoom;
    }

    private double screenToBlockZ(int my) {
        int mapTop = 30;
        int mapBottom = this.height - 30;
        int mapCenterY = (mapTop + mapBottom) / 2;
        return viewZ + (my - mapCenterY) / zoom;
    }

    private void updateWorldDir() {
        if (selectedWorld >= 0 && selectedWorld < worlds.size()) {
            File savesDir = new File(Loader.instance().getConfigDir().getParentFile(), "saves");
            currentWorldDir = new File(savesDir, worlds.get(selectedWorld).getFileName());
        } else {
            currentWorldDir = null;
        }
        // 切换存档：清除地图缓存与粘贴预览
        pastePreview = false;
        previewTiles = null;
        renderer.clear(); // 关键：切换存档必须清空地图缓存（含进行中的异步任务），否则跨世界串图/闪烁
    }

    @Override
    public void initGui() {
        worlds.clear();
        try {
            List<WorldSummary> list = Minecraft.getMinecraft().getSaveLoader().getSaveList();
            if (list != null) {
                for (WorldSummary w : list) worlds.add(w);
            }
            worlds.sort(new Comparator<WorldSummary>() {
                public int compare(WorldSummary a, WorldSummary b) {
                    return Long.compare(b.getLastTimePlayed(), a.getLastTimePlayed());
                }
            });
        } catch (Exception e) {
            // 存档列表读取失败（保持空列表）
        }
        if (selectedWorld < 0 && !worlds.isEmpty()) selectedWorld = 0;
        updateWorldDir();

        this.buttonList.clear();
        int y = 6;
        this.buttonList.add(new GuiButton(BTN_BACK, this.width - 86, y, 80, 20, "返回主菜单"));
        this.buttonList.add(new GuiButton(BTN_READONLY, this.width - 170, y, 80, 20, "只读: 关"));
        worldMenuOpen = false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // GL 状态防御：确保无残留 color/alpha 影响后续纹理渲染
        net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        // 背景
        drawRect(0, 0, this.width, this.height, 0xFF14181C);
        drawRect(0, 0, this.width, 30, 0xFF20262C);

        // 地图画布区域（顶部 30px 控制条 + 底部 30px 状态条）
        int mapTop = 30;
        int mapBottom = this.height - 30;
        int mapCenterY = (mapTop + mapBottom) / 2;
        double ppb = zoom;

        // ---- 2D 地图：可见 chunk 渲染（异步加载 + 顶点色批渲染） ----
        int minCx = (int) Math.floor((viewX - (double) mapCenterX() / ppb) / 16);
        int maxCx = (int) Math.floor((viewX + (double) mapCenterX() / ppb) / 16);
        int minCz = (int) Math.floor((viewZ - (double) (mapBottom - mapTop) / 2 / ppb) / 16);
        int maxCz = (int) Math.floor((viewZ + (double) (mapBottom - mapTop) / 2 / ppb) / 16);
        int loadedCount = 0;
        int pendingCount = 0;
        int exactCount = 0;
        if (currentWorldDir != null) {
            // 顶点色批量渲染（与 drawRect 同路径，100% 正常；绕开 DynamicTexture 在整合包环境的暗化）
            java.util.List<int[]> visible = new java.util.ArrayList<int[]>(); // [sx, sy, px, py, color]
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    int sx = mapCenterX() + (int) Math.round((cx * 16 - viewX) * ppb);
                    int sy = mapCenterY + (int) Math.round((cz * 16 - viewZ) * ppb);
                    int size = Math.max(1, (int) Math.round(16 * ppb));
                    ChunkMapRenderer.ChunkMapTile tile = renderer.getOrLoad(currentWorldDir, cx, cz);
                    if (tile != null) {
                        collectTileQuads(visible, tile, sx, sy, size);
                        loadedCount++;
                        exactCount += tile.exactCols;
                    } else {
                        visible.add(new int[]{sx, sy, size, size, renderer.pendingColor()});
                        pendingCount++;
                    }
                }
            }
            drawTileQuads(visible);
        }

        // ---- 粘贴预览（幽灵层 + 白绿轮廓框；预览态框选 = 定位，事件中直接更新 pasteTarget） ----
        if (pastePreview && currentWorldDir != null) {
            int ppx = mapCenterX() + (int) Math.round((pasteTargetCx * 16 - viewX) * ppb);
            int ppy = mapCenterY + (int) Math.round((pasteTargetCz * 16 - viewZ) * ppb);
            int psz = (int) Math.round(16 * ppb);
            java.util.Map<Long, ChunkMapRenderer.ChunkMapTile> pt = previewTiles;
            if (pt != null && !pt.isEmpty()) {
                java.util.List<int[]> pv = new java.util.ArrayList<int[]>();
                for (java.util.Map.Entry<Long, ChunkMapRenderer.ChunkMapTile> e : pt.entrySet()) {
                    long k = e.getKey();
                    int dx2 = (int) (k >> 32) - clipOriginCx;
                    int dz2 = (int) (k & 0xFFFFFFFFL) - clipOriginCz;
                    collectTileQuads(pv, e.getValue(),
                            ppx + (int) Math.round(dx2 * 16 * ppb), ppy + (int) Math.round(dz2 * 16 * ppb),
                            psz, 0x50000000);
                }
                drawTileQuads(pv);
            }
            // 轮廓（预览块大小）
            int pW = Math.max(1, (int) Math.round(previewW * 16 * ppb));
            int pH = Math.max(1, (int) Math.round(previewH * 16 * ppb));
            int c1 = 0xFFFFFFFF, c2 = 0xFF88FF88;
            drawRect(ppx, ppy, ppx + pW, ppy + 1, c1);
            drawRect(ppx, ppy + pH - 1, ppx + pW, ppy + pH, c1);
            drawRect(ppx, ppy, ppx + 1, ppy + pH, c2);
            drawRect(ppx + pW - 1, ppy, ppx + pW, ppy + pH, c2);
            this.fontRenderer.drawString(String.format("粘贴预览 %dx%d → (%d,%d) | 左键拖动定位 · Ctrl+V 确认 · Esc 取消",
                    previewW, previewH, pasteTargetCx, pasteTargetCz),
                    mapCenterX() - 120, 34, 0xFFAAFFAA);
        }

        // ---- 区块网格与坐标轴（画在地图之上） ----
        int gridMinX = (int) Math.floor(viewX - (double) mapCenterX() / ppb);
        int gridMaxX = (int) Math.ceil(viewX + (double) mapCenterX() / ppb);
        int gridMinZ = (int) Math.floor(viewZ - (double) (mapBottom - mapTop) / 2 / ppb);
        int gridMaxZ = (int) Math.ceil(viewZ + (double) (mapBottom - mapTop) / 2 / ppb);
        for (int chunkX = (gridMinX >> 4) << 4; chunkX <= gridMaxX; chunkX += 16) {
            int sx = mapCenterX() + (int) Math.round((chunkX - viewX) * ppb);
            if (sx >= 0 && sx <= this.width - PANEL_W) {
                drawVerticalLine(sx, mapTop, mapBottom, 0x663A444E);
            }
        }
        for (int chunkZ = (gridMinZ >> 4) << 4; chunkZ <= gridMaxZ; chunkZ += 16) {
            int sy = mapCenterY + (int) Math.round((chunkZ - viewZ) * ppb);
            if (sy >= mapTop && sy <= mapBottom) {
                drawHorizontalLine(0, this.width - PANEL_W, sy, 0x663A444E);
            }
        }
        int centerX = mapCenterX();
        drawVerticalLine(centerX, mapTop, mapBottom, 0xFF55616C);
        drawHorizontalLine(0, this.width - PANEL_W, mapCenterY, 0xFF55616C);

        // ---- 选区边框（预览态不产生框选，无冲突） ----
        if (selMinCx != -1 && currentWorldDir != null) {
            int sx1 = mapCenterX() + (int) Math.round((selMinCx * 16 - viewX) * ppb);
            int sy1 = mapCenterY + (int) Math.round((selMinCz * 16 - viewZ) * ppb);
            int sx2 = mapCenterX() + (int) Math.round(((selMaxCx + 1) * 16 - viewX) * ppb);
            int sy2 = mapCenterY + (int) Math.round(((selMaxCz + 1) * 16 - viewZ) * ppb);
            if (sx2 > sx1 && sy2 > sy1) {
                drawRect(sx1 + 1, sy1 + 1, sx2 - 1, sy2 - 1, 0x22FFFFFF);
                drawRect(sx1, sy1, sx2, sy1 + 1, 0xFFFFFFFF);
                drawRect(sx1, sy2 - 1, sx2, sy2, 0xFFFFFFFF);
                drawRect(sx1, sy1, sx1 + 1, sy2, 0xFFFFFFFF);
                drawRect(sx2 - 1, sy1, sx2, sy2, 0xFFFFFFFF);
            }
        }

        // 世界信息
        String name = selectedWorld >= 0 && selectedWorld < worlds.size()
                ? worlds.get(selectedWorld).getDisplayName() : "（无存档）";
        // 存档下拉按钮（替代 "<" ">" 切换）
        int wbx = 6, wby = 4, wbw = 214, wbh = 22;
        boolean wbHover = mouseX >= wbx && mouseX < wbx + wbw && mouseY >= wby && mouseY < wby + wbh;
        drawRect(wbx, wby, wbx + wbw, wby + wbh, worldMenuOpen || wbHover ? 0xFF2A323C : 0xFF20262C);
        drawRect(wbx, wby, wbx + wbw, wby + 1, 0xFF3A444E);
        this.fontRenderer.drawString("存档: " + name, wbx + 8, wby + 7, 0xFFFFFFFF);
        this.fontRenderer.drawString("▾", wbx + wbw - 16, wby + 7, 0xFFAAAAAA);
        if (worldMenuOpen) {
            int rows = Math.min(worlds.size(), 12);
            int my0 = wby + wbh + 2;
            int mh = rows * 16 + 4;
            drawRect(wbx, my0, wbx + wbw + 6, my0 + mh, 0xF0181C24);
            drawRect(wbx, my0, wbx + wbw + 6, my0 + 1, 0xFF3A444E);
            drawRect(wbx, my0 + mh - 1, wbx + wbw + 6, my0 + mh, 0xFF3A444E);
            for (int i = 0; i < rows; i++) {
                String wname = worlds.get(i).getDisplayName();
                int ry = my0 + 2 + i * 16;
                boolean hov = mouseX >= wbx && mouseX < wbx + wbw + 6 && mouseY >= ry && mouseY < ry + 16;
                if (hov) drawRect(wbx, ry, wbx + wbw + 6, ry + 16, 0xFF2A323C);
                this.fontRenderer.drawString((i == selectedWorld ? "✔ " : "   ") + wname,
                        wbx + 6, ry + 4, hov ? 0xFFFFFFFF : 0xFFCCCCCC);
            }
        }
        this.fontRenderer.drawString(String.format("%d 个存档 | 已加载 %d 区块 | 加载中 %d | 精确层 %d 列",
                worlds.size(), loadedCount, pendingCount, exactCount), 230, 12, 0xAAAAAA);

        // 状态栏（左下角：中心 + 鼠标指向区块）
        String centerStr = String.format("中心: %.0f, %.0f  缩放: %.1f px/方块", viewX, viewZ, ppb);
        this.fontRenderer.drawString(centerStr, 6, this.height - 24, 0xAAAAAA);
        int cw = this.fontRenderer.getStringWidth(centerStr);
        String ptrStr = "";
        if (mouseX < this.width - PANEL_W && mouseY >= 30 && mouseY < this.height - 30) {
            int pcx = (int) Math.floor(screenToBlockX(mouseX) / 16);
            int pcz = (int) Math.floor(screenToBlockZ(mouseY) / 16);
            int pbx = (int) Math.floor(screenToBlockX(mouseX));
            int pbz = (int) Math.floor(screenToBlockZ(mouseY));
            ptrStr = String.format(" | 指向: 区块 (%d, %d) 方块 (%d, %d)", pcx, pcz, pbx, pbz);
        } else {
            ptrStr = " | 指向: ---";
        }
        this.fontRenderer.drawString(ptrStr, 6 + cw, this.height - 24, 0xFF88CCFF);
        this.fontRenderer.drawString("左键框选 · 中键拖动 · 右键取消框选 · 滚轮缩放 · 快捷键 ? · ESC 返回",
                mapCenterX() - 140, this.height - 12, 0x888888);

        // ---- 右侧工具面板（自绘控件：按钮/滑块/统计列表/过滤框） ----
        // 异步统计结果应用（主线程）
        if (statsResult != null) {
            Object[] r = statsResult;
            statsResult = null;
            statsBusy = false;
            if (r == null) {
                statsSummary = "统计失败";
            } else {
                @SuppressWarnings("unchecked") java.util.List<String[]> list =
                        (java.util.List<String[]>) r[4];
                statsEntries = list;
                statsScroll = 0;
                statsSummary = String.format("统计: %s 区块 / %s 方块（%d 种）",
                        r[0], r[1], list.size());
            }
        }
        drawRightPanel(mouseX, mouseY);

        // 日志（左侧，半透明背景）
        if (!log.isEmpty()) {
            int ly = this.height - 34 - log.size() * 10;
            drawRect(0, ly - 2, 360, this.height - 30, 0x88000000);
            for (String line : log) {
                this.fontRenderer.drawString(line, 4, ly, 0xAAFFAA);
                ly += 10;
            }
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /**
     * 收集一个 tile 的色块 quad：每 ~2px 一个色块（clamp 2..16×16），
     * 用精确边界 (ci*size/cols .. (ci+1)*size/cols) 铺满无缝隙。
     * alphaMask 可叠加透明度（粘贴预览幽灵层用）。
     */
    private void collectTileQuads(java.util.List<int[]> out, ChunkMapRenderer.ChunkMapTile tile,
                                  int sx, int sy, int size) {
        collectTileQuads(out, tile, sx, sy, size, 0xFF000000);
    }

    private void collectTileQuads(java.util.List<int[]> out, ChunkMapRenderer.ChunkMapTile tile,
                                  int sx, int sy, int size, int alphaMask) {
        int cols = Math.max(2, Math.min(16, size / 2));
        for (int cz2 = 0; cz2 < cols; cz2++) {
            int ty = Math.min(15, cz2 * 16 / cols);
            int qy = sy + cz2 * size / cols;
            int qy2 = sy + (cz2 + 1) * size / cols;
            for (int cx2 = 0; cx2 < cols; cx2++) {
                int tx = Math.min(15, cx2 * 16 / cols);
                int qx = sx + cx2 * size / cols;
                int qx2 = sx + (cx2 + 1) * size / cols;
                out.add(new int[]{qx, qy, Math.max(1, qx2 - qx), Math.max(1, qy2 - qy),
                        (tile.colors[ty * 16 + tx] & 0x00FFFFFF) | alphaMask});
            }
        }
    }

    /** 后台解码剪贴板为预览 tile（内存 NBT，无精确层）。 */
    private void startPreviewLoad() {
        previewTiles = null;
        final java.util.Map<Long, byte[]> clip = clipboard;
        Thread t = new Thread(new Runnable() {
            public void run() {
                java.util.Map<Long, ChunkMapRenderer.ChunkMapTile> m =
                        new java.util.HashMap<Long, ChunkMapRenderer.ChunkMapTile>();
                try {
                    for (java.util.Map.Entry<Long, byte[]> e : clip.entrySet()) {
                        com.chunkops.verify.NbtNode root = com.chunkops.core.RegionWriter.unpackChunk(e.getValue());
                        com.chunkops.verify.NbtNode level = root.get("Level");
                        if (level != null) {
                            ChunkMapRenderer.ChunkMapTile t2 = renderer.tileFromMemory(level);
                            if (t2 != null) m.put(e.getKey(), t2);
                        }
                    }
                } catch (Exception ignored) {
                    // 解码失败：仅显示轮廓
                }
                previewTiles = m;
            }
        }, "chunkops-preview");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 批量绘制色块（POSITION_COLOR，与 drawRect 相同渲染路径——对照实验验证 100% 正常；
     * DynamicTexture 渲染在整合包环境被暗化 ×0.533，故弃用）。分批防 BufferBuilder 溢出。
     */
    private void drawTileQuads(java.util.List<int[]> quads) {
        if (quads.isEmpty()) return;
        // 色块全部矩形的边界，先画底（不重复）
        final int BATCH = 12000; // quad 数/批（每 quad 4 顶点，安全余量内）
        net.minecraft.client.renderer.GlStateManager.enableBlend();
        net.minecraft.client.renderer.GlStateManager.tryBlendFuncSeparate(
                net.minecraft.client.renderer.GlStateManager.SourceFactor.SRC_ALPHA,
                net.minecraft.client.renderer.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                net.minecraft.client.renderer.GlStateManager.SourceFactor.ONE,
                net.minecraft.client.renderer.GlStateManager.DestFactor.ZERO);
        Tessellator tess = Tessellator.getInstance();
        for (int off = 0; off < quads.size(); off += BATCH) {
            int end = Math.min(quads.size(), off + BATCH);
            BufferBuilder buf = tess.getBuffer();
            buf.begin(7, DefaultVertexFormats.POSITION_COLOR);
            for (int i = off; i < end; i++) {
                int[] q = quads.get(i);
                int x = q[0], y = q[1], w = q[2], h = q[3];
                int c = q[4];
                float r = ((c >> 16) & 0xFF) / 255.0f;
                float g = ((c >> 8) & 0xFF) / 255.0f;
                float b = (c & 0xFF) / 255.0f;
                float a = ((c >>> 24) & 0xFF) / 255.0f;
                buf.pos(x, y + h, (double) this.zLevel).color(r, g, b, a).endVertex();
                buf.pos(x + w, y + h, (double) this.zLevel).color(r, g, b, a).endVertex();
                buf.pos(x + w, y, (double) this.zLevel).color(r, g, b, a).endVertex();
                buf.pos(x, y, (double) this.zLevel).color(r, g, b, a).endVertex();
            }
            tess.draw();
        }
        net.minecraft.client.renderer.GlStateManager.disableBlend();
    }

    // ------------------------------------------------------------ 右侧工具面板

    private static final String[] TOOL_LABELS = {"移除选区", "清空选区", "剪裁", "统计",
            "复制", "粘贴", "只读", "帮助"};
    private static final int BTN_W = 87, BTN_H = 22, BTN_GAP = 5;

    private int panelX() {
        return this.width - PANEL_W;
    }

    /** 工具按钮矩形（两列 4 行）。 */
    private int[] toolRect(int i) {
        int px = panelX() + (PANEL_W - BTN_W * 2 - BTN_GAP) / 2;
        int py = 58 + (i / 2) * (BTN_H + BTN_GAP);
        return new int[]{px + (i % 2) * (BTN_W + BTN_GAP), py, BTN_W, BTN_H};
    }

    private int sliderY() {
        return 58 + 4 * (BTN_H + BTN_GAP) + 30;
    }

    private int statsY() {
        return sliderY() + 52;
    }

    private int listX() {
        return panelX() + 10;
    }

    private int listY() {
        return statsY() + 62;
    }

    private int listW() {
        return PANEL_W - 22;
    }

    private void drawRightPanel(int mx, int my) {
        int px = panelX();
        int py0 = 30, py1 = this.height - 30;
        drawRect(px, py0, this.width, py1, 0xEE14181C);
        drawRect(px, py0, px + 1, py1, 0xFF3A444E);
        this.fontRenderer.drawString("工具", px + 12, 42, 0xFFFFFFFF);
        this.fontRenderer.drawString("?", px + PANEL_W - 22, 42, helpOpen ? 0xFFAAFFAA : 0xFF888888);

        // 只读按钮显示状态
        String lo = TOOL_LABELS[6] + (readOnly ? "开" : "关");
        for (int i = 0; i < TOOL_LABELS.length; i++) {
            int[] r = toolRect(i);
            String label = i == 6 ? lo : TOOL_LABELS[i];
            boolean hover = mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
            int bg = hover ? 0xFF2A323C : (i == 6 && readOnly ? 0xFF3A4A2A : 0xFF20262C);
            drawRect(r[0], r[1], r[0] + r[2], r[1] + r[3], bg);
            drawRect(r[0], r[1], r[0] + r[2], r[1] + 1, 0xFF3A444E);
            this.fontRenderer.drawString(label, r[0] + (r[2] - this.fontRenderer.getStringWidth(label)) / 2,
                    r[1] + (r[3] - 8) / 2, hover ? 0xFFFFFFFF : 0xFFCCCCCC);
        }

        // ---- 缩放滑块（对数 0.5..32） ----
        int sy = sliderY();
        this.fontRenderer.drawString("缩放", px + 12, sy, 0xFFAAAAAA);
        zoomSliderX = px + 44;
        zoomSliderY = sy + 5;
        zoomSliderW = PANEL_W - 84;
        double v = Math.log(zoom / 0.5) / Math.log(64.0);
        zoomVal = Math.max(0, Math.min(1, v));
        drawRect(zoomSliderX, zoomSliderY + 3, zoomSliderX + zoomSliderW, zoomSliderY + 5, 0xFF3A444E);
        int knob = zoomSliderX + (int) Math.round(zoomVal * zoomSliderW);
        boolean sHover = mx >= zoomSliderX - 4 && mx <= zoomSliderX + zoomSliderW + 4
                && my >= zoomSliderY - 2 && my <= zoomSliderY + 10;
        drawRect(knob - 3, zoomSliderY - 2, knob + 3, zoomSliderY + 10, sHover || zoomDrag ? 0xFFFFFFFF : 0xFFCCCCCC);
        this.fontRenderer.drawString(String.format("%.2f", zoom), px + PANEL_W - 68, sy, 0xFFCCCCCC);

        // ---- 统计区 ----
        int sty = statsY();
        this.fontRenderer.drawString("统计", px + 12, sty, 0xFFAAAAAA);
        drawRect(listX(), sty + 12, listX() + listW(), sty + 28, 0xFF20262C);
        this.fontRenderer.drawString("过滤:", listX() + 2, sty + 15, 0xFF888888);
        if (statsBox == null) {
            statsBox = new net.minecraft.client.gui.GuiTextField(200, this.fontRenderer,
                    listX() + 30, sty + 12, listW() - 32, 16);
        }
        statsBox.drawTextBox();

        // 表头（点击排序）
        int hy = sty + 34;
        drawRect(listX(), hy, listX() + listW(), hy + 11, 0xFF2A323C);
        String nameH = "名称" + (statsSortCol == 0 ? (statsDesc ? " ▼" : " ▲") : "");
        String cntH = "数量" + (statsSortCol == 1 ? (statsDesc ? " ▼" : " ▲") : "");
        this.fontRenderer.drawString(nameH, listX() + 3, hy + 2, 0xFFAAAAAA);
        this.fontRenderer.drawString(cntH, listX() + listW() - 40, hy + 2, 0xFFAAAAAA);

        // 过滤应用
        String newFilter = statsBox.getText().trim().toLowerCase();
        if (!newFilter.equals(statsFilter)) {
            statsFilter = newFilter;
            statsScroll = 0;
        }
        // 列表（scissor 裁剪 + 滚动条）
        int ly = hy + 12;
        int lh = py1 - ly - 4;
        java.util.List<String[]> filtered = applyStatsFilter();
        int maxScroll = Math.max(0, filtered.size() - lh / 11);
        if (statsScroll > maxScroll) statsScroll = maxScroll;
        if (lh > 16) {
            // 行渲染按可视行数截断（不越界），无需 scissor（1.12.2 无此 API）
            for (int i = 0; i < lh / 11; i++) {
                int idx = statsScroll + i;
                if (idx >= filtered.size()) break;
                String[] e = filtered.get(idx);
                int rowY = ly + i * 11;
                boolean rowHover = mx >= listX() && mx < listX() + listW()
                        && my >= rowY && my < rowY + 11;
                if (rowHover) drawRect(listX(), rowY, listX() + listW(), rowY + 11, 0x22FFFFFF);
                String name = e[0];
                if (this.fontRenderer.getStringWidth(name) > listW() - 48) {
                    name = name.substring(0, Math.max(1, name.length() - 2)) + "…";
                }
                this.fontRenderer.drawString(name, listX() + 3, rowY + 2, 0xFFD0D0D0);
                this.fontRenderer.drawString(e[1], listX() + listW() - 8
                        - this.fontRenderer.getStringWidth(e[1]), rowY + 2, 0xFFA0E0A0);
            }
            // 滚动条
            if (maxScroll > 0) {
                drawRect(listX() + listW() + 2, hy, listX() + listW() + 5, py1 - 8, 0xFF2A323C);
                int trackH = py1 - 8 - hy;
                int visible = Math.max(1, lh / 11);
                int barH = Math.max(14, trackH * visible / Math.max(1, filtered.size()));
                int barY = hy + (int) ((double) statsScroll / Math.max(1, maxScroll) * (trackH - barH));
                drawRect(listX() + listW() + 2, barY, listX() + listW() + 5, barY + barH, 0xFF888888);
            }
        }
        this.fontRenderer.drawString(statsSummary, px + 10, py1 - 12, 0xFF888888);

        // 帮助浮层
        if (helpOpen) drawHelpOverlay();
    }

    /** 应用过滤+排序，返回显示列表（重算缓存）。 */
    private java.util.List<String[]> applyStatsFilter() {
        java.util.List<String[]> filtered = new java.util.ArrayList<String[]>();
        for (String[] e : statsEntries) {
            if (statsFilter.isEmpty() || e[0].toLowerCase().contains(statsFilter)) filtered.add(e);
        }
        java.util.Collections.sort(filtered, new java.util.Comparator<String[]>() {
            public int compare(String[] a, String[] b) {
                if (statsSortCol == 0) {
                    int c = a[0].compareTo(b[0]);
                    return statsDesc ? -c : c;
                }
                long av = Long.parseLong(a[1]), bv = Long.parseLong(b[1]);
                int c = Long.compare(av, bv);
                return statsDesc ? -c : c;
            }
        });
        return filtered;
    }

    /** 面板鼠标点击处理；返回 true 表示已消费。 */
    private boolean panelClick(int mx, int my, int button) {
        if (mx < panelX()) return false;
        if (button != 0) return true; // 面板区域右键/中键：消费（不弹地图菜单）
        if (helpOpen) {
            helpOpen = false;
            return true;
        }
        if (button == 0) {
            for (int i = 0; i < TOOL_LABELS.length; i++) {
                int[] r = toolRect(i);
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                    switch (i) {
                        case 0: runSelectionOp("remove"); break;
                        case 1: runSelectionOp("clear"); break;
                        case 2: runSelectionOp("trim"); break;
                        case 3: runStats(); break;
                        case 4: runSelectionOp("copy"); break;
                        case 5: runSelectionOp("paste"); break;
                        case 6: readOnly = !readOnly; logLine("只读: " + (readOnly ? "开" : "关")); break;
                        case 7: helpOpen = true; break;
                    }
                    return true;
                }
            }
            // 缩放滑块
            if (mx >= zoomSliderX - 6 && mx <= zoomSliderX + zoomSliderW + 6
                    && my >= zoomSliderY - 4 && my <= zoomSliderY + 12) {
                zoomDrag = true;
                applyZoomFromSlider(mx);
                return true;
            }
            // 统计列表表头（排序切换）
            int sty = statsY();
            int hy = sty + 34;
            if (my >= hy && my < hy + 11 && mx >= listX() && mx < listX() + listW()) {
                int col = mx > listX() + listW() - 46 ? 1 : 0;
                if (statsSortCol == col) statsDesc = !statsDesc;
                else { statsSortCol = col; statsDesc = true; }
                return true;
            }
            // 过滤框
            if (statsBox != null && statsBox.mouseClicked(mx, my, button)) {
                if (mx >= listX() && mx <= listX() + listW()
                        && my >= sty + 12 && my <= sty + 28) return true;
            }
        }
        return false;
    }

    private void applyZoomFromSlider(int mx) {
        double v = (mx - zoomSliderX) / (double) Math.max(1, zoomSliderW);
        v = Math.max(0, Math.min(1, v));
        zoom = 0.5 * Math.pow(64.0, v);
    }

    /** 面板滚轮处理；返回 true 表示已消费（统计列表滚动）。 */
    private boolean panelWheel(int dWheel, int mx, int my) {
        if (mx < panelX()) return false;
        int sty = statsY();
        int hy = sty + 34;
        int ly = hy + 12;
        int py1 = this.height - 30;
        int lh = py1 - ly - 4;
        java.util.List<String[]> filtered = applyStatsFilter();
        int maxScroll = Math.max(0, filtered.size() - lh / 11);
        if (my >= ly && my <= py1 && lh > 16 && maxScroll > 0) {
            statsScroll = Math.max(0, Math.min(maxScroll, statsScroll - dWheel / 120 * 3));
            return true;
        }
        return false;
    }

    /** 后台统计选区（异步，防大选区卡帧）。 */
    private void runStats() {
        if (currentWorldDir == null || selMinCx == -1) {
            logLine("请先框选区块，再点「统计」");
            return;
        }
        if (statsBusy) return;
        statsBusy = true;
        statsSummary = "统计中…（" + (selMaxCx - selMinCx + 1) + "x"
                + (selMaxCz - selMinCz + 1) + " 区块）";
        final File wd = currentWorldDir;
        final int x1 = selMinCx, x2 = selMaxCx, z1 = selMinCz, z2 = selMaxCz;
        Thread t = new Thread(new Runnable() {
            public void run() {
                statsResult = GuiOps.statsAreaList(wd, x1, x2, z1, z2);
            }
        }, "chunkops-stats");
        t.setDaemon(true);
        t.start();
    }

    private void drawHelpOverlay() {
        net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        int x = mapCenterX() - 210, y = 60, w = 420, h = 300;
        drawRect(x, y, x + w, y + h, 0xF0000000);
        drawRect(x, y, x + w, y + 1, 0xFF3A444E);
        this.fontRenderer.drawString("快捷键 / 操作", x + 16, y + 10, 0xFFFFFFFF);
        String[] lines = {
                "左键拖动    框选区块",
                "中键拖动    移动视图",
                "右键/滚轮  菜单 / 缩放",
                "R           移除选区",
                "C           清空选区",
                "T           剪裁(保留选区)",
                "S           统计选区",
                "Ctrl+C/V    复制 / 预览粘贴(左键拖动定位+再按确认)",
                "Ctrl+滚轮   缩放地图",
                "ESC         返回主菜单",
        };
        int ly = y + 30;
        for (String s : lines) {
            this.fontRenderer.drawString(s, x + 20, ly, 0xFFCCCCCC);
            ly += 15;
        }
        this.fontRenderer.drawString("点击任意处关闭", x + 16, y + h - 20, 0xFF888888);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_BACK) {
            ChunkOpsGuiHandler.backToMainMenu();
        } else if (button.id == BTN_READONLY) {
            readOnly = !readOnly;
            button.displayString = readOnly ? "只读: 开" : "只读: 关";
            logLine(readOnly ? "已开启只读模式（不写入存档）" : "已关闭只读模式");
        }
    }

    /** 执行选区操作（含 session.lock 与只读检查）。 */
    private void runSelectionOp(String op) {
        if (currentWorldDir == null) {
            logLine("未选择存档");
            return;
        }
        boolean writeOp = !op.equals("stats") && !op.equals("copy");
        if (readOnly && writeOp) {
            logLine("[只读] 操作未执行");
            return;
        }
        if (writeOp && GuiOps.hasSessionLock(currentWorldDir)) {
            logLine("[警告] 检测到 session.lock——该存档可能被游戏实例占用，继续操作可能损坏！");
        }
        if (selMinCx == -1 && !op.equals("paste")) {
            logLine("请先框选区块（左键拖动）");
            return;
        }
        if (!op.equals("paste")) {
            int w = selMaxCx - selMinCx + 1;
            int h = selMaxCz - selMinCz + 1;
            logLine("选区 [" + selMinCx + ".." + selMaxCx + ", " + selMinCz + ".." + selMaxCz + "] " + w + "x" + h + " 区块");
        }
        if (op.equals("remove")) {
            for (int cx = selMinCx; cx <= selMaxCx; cx++) {
                for (int cz = selMinCz; cz <= selMaxCz; cz++) {
                    logLine(GuiOps.removeChunk(currentWorldDir, cx, cz, false));
                }
            }
            renderer.clear(); // 数据已变：地图缓存失效重载
        } else if (op.equals("clear")) {
            for (int cx = selMinCx; cx <= selMaxCx; cx++) {
                for (int cz = selMinCz; cz <= selMaxCz; cz++) {
                    logLine(GuiOps.clearChunk(currentWorldDir, cx, cz, false));
                }
            }
            renderer.clear();
        } else if (op.equals("trim")) {
            logLine(GuiOps.trimArea(currentWorldDir, selMinCx, selMaxCx, selMinCz, selMaxCz, false));
            renderer.clear();
        } else if (op.equals("stats")) {
            String s = GuiOps.statsArea(currentWorldDir, selMinCx, selMaxCx, selMinCz, selMaxCz);
            for (String line : s.split("\n")) logLine(line);
        } else if (op.equals("copy")) {
            clipboard = GuiOps.copyArea(currentWorldDir, selMinCx, selMaxCx, selMinCz, selMaxCz);
            clipOriginCx = selMinCx;
            clipOriginCz = selMinCz;
            logLine(clipboard != null ? "已复制 " + clipboard.size() + " 个区块到剪贴板（源 "
                    + clipOriginCx + "," + clipOriginCz + "）" : "复制失败");
        } else if (op.equals("paste")) {
            if (clipboard == null || clipboard.isEmpty()) {
                logLine("剪贴板为空（先复制选区）");
                return;
            }
            if (pastePreview) {
                // 确认：真正写入（目标 = 预览目标 pasteTarget，单一真源，与预览显示严格一致）
                logLine(GuiOps.pasteArea(currentWorldDir, clipboard, clipOriginCx, clipOriginCz,
                        pasteTargetCx, pasteTargetCz));
                pastePreview = false;
                previewTiles = null;
                renderer.clear(); // 数据已变：地图缓存失效重载
            } else {
                // 进入预览态（MCA 式：先预览，框选定位，再确认）
                pastePreview = true;
                pasteTargetCx = selMinCx != -1 ? selMinCx : (int) Math.floor(viewX / 16);
                pasteTargetCz = selMinCz != -1 ? selMinCz : (int) Math.floor(viewZ / 16);
                previewW = previewH = 1;
                int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE;
                int minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;
                for (Long k : clipboard.keySet()) {
                    int ccx = (int) (k >> 32);
                    int ccz = (int) (k & 0xFFFFFFFFL);
                    minCx = Math.min(minCx, ccx);
                    maxCx = Math.max(maxCx, ccx);
                    minCz = Math.min(minCz, ccz);
                    maxCz = Math.max(maxCz, ccz);
                }
                previewW = Math.max(1, maxCx - minCx + 1);
                previewH = Math.max(1, maxCz - minCz + 1);
                startPreviewLoad();
                logLine("粘贴预览：" + previewW + "x" + previewH + " 区块，左键拖动预览定位，再按 Ctrl+V 确认，Esc 取消");
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton); // 先处理按钮
        if (helpOpen) { // 帮助浮层：任意点击关闭
            helpOpen = false;
            return;
        }
        // 存档下拉菜单（按钮/列表项/外部点击关闭）
        int wbx = 6, wby = 4, wbw = 214, wbh = 22;
        if (worldMenuOpen) {
            boolean inList = mouseX >= wbx && mouseX < wbx + wbw + 6
                    && mouseY >= wby + wbh + 2 && mouseY < wby + wbh + 2 + Math.min(worlds.size(), 12) * 16 + 4;
            if (inList) {
                int idx = (mouseY - (wby + wbh + 2)) / 16;
                if (idx < worlds.size()) {
                    selectedWorld = idx;
                    updateWorldDir();
                    logLine("切换到存档: " + worlds.get(idx).getDisplayName());
                }
            }
            worldMenuOpen = false;
            return;
        }
        if (mouseX >= wbx && mouseX < wbx + wbw && mouseY >= wby && mouseY < wby + wbh) {
            worldMenuOpen = true;
            return;
        }
        if (panelClick(mouseX, mouseY, mouseButton)) return; // 右侧面板优先
        if (mouseButton == 0) { // 左键
            if (pastePreview) {
                // 预览态：左键直接拖动预览轮廓（中心跟随鼠标），不进入框选
                dragPreview = true;
                dragPreviewTo(mouseX, mouseY);
                return;
            }
            selecting = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
        } else if (mouseButton == 2) { // 中键：移动视图
            moving = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
        } else if (mouseButton == 1) { // 右键：取消框选
            if (selMinCx != -1 || selMaxCx != -1) {
                selMinCx = selMaxCx = selMinCz = selMaxCz = -1;
                logLine("已取消框选");
            }
        }
    }

    /** 预览拖动：中心跟随鼠标（预览左上角 = 鼠标中心 - 尺寸/2）。 */
    private void dragPreviewTo(int mouseX, int mouseY) {
        int mxC = (int) Math.round(screenToBlockX(mouseX) / 16.0);
        int mzC = (int) Math.round(screenToBlockZ(mouseY) / 16.0);
        pasteTargetCx = mxC - previewW / 2;
        pasteTargetCz = mzC - previewH / 2;
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (zoomDrag) { // 缩放滑块拖动
            applyZoomFromSlider(mouseX);
            return;
        }
        if (dragPreview) { // 预览态：拖动预览轮廓
            dragPreviewTo(mouseX, mouseY);
            return;
        }
        if (selecting) {
            int cx1 = (int) Math.floor(screenToBlockX(lastMouseX) / 16);
            int cz1 = (int) Math.floor(screenToBlockZ(lastMouseY) / 16);
            int cx2 = (int) Math.floor(screenToBlockX(mouseX) / 16);
            int cz2 = (int) Math.floor(screenToBlockZ(mouseY) / 16);
            selMinCx = Math.min(cx1, cx2);
            selMaxCx = Math.max(cx1, cx2);
            selMinCz = Math.min(cz1, cz2);
            selMaxCz = Math.max(cz1, cz2);
        } else if (moving) {
            viewX -= (mouseX - lastMouseX) / zoom;
            viewZ -= (mouseY - lastMouseY) / zoom;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (selecting) {
            selecting = false;
            if (selMinCx == -1) {
                // 单击未拖动：清除选区
                selMinCx = selMaxCx = selMinCz = selMaxCz = -1;
            }
        }
        moving = false;
        zoomDrag = false;
        dragPreview = false;
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        int dWheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (dWheel != 0) {
            int mx = org.lwjgl.input.Mouse.getEventX() * this.width / this.mc.displayWidth;
            int my = this.height - org.lwjgl.input.Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
            if (panelWheel(dWheel, mx, my)) {
                super.handleMouseInput();
                return;
            }
            double factor = dWheel > 0 ? 1.25 : 0.8;
            zoom = Math.max(0.5, Math.min(32.0, zoom * factor));
        }
        super.handleMouseInput();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        // 统计过滤框聚焦时优先输入
        if (statsBox != null && statsBox.isFocused()) {
            if (statsBox.textboxKeyTyped(typedChar, keyCode)) return;
            super.keyTyped(typedChar, keyCode);
            return;
        }
        if (keyCode == 1) { // ESC
            if (pastePreview) { // 预览态：ESC 取消预览（不退出）
                pastePreview = false;
                previewTiles = null;
                logLine("已取消粘贴");
                return;
            }
            ChunkOpsGuiHandler.backToMainMenu();
            return;
        }
        boolean ctrl = org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LCONTROL)
                || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RCONTROL);
        switch (keyCode) {
            case org.lwjgl.input.Keyboard.KEY_R:
                if (!ctrl) { runSelectionOp("remove"); return; }
                break;
            case org.lwjgl.input.Keyboard.KEY_C:
                if (ctrl) { runSelectionOp("copy"); return; }
                runSelectionOp("clear");
                return;
            case org.lwjgl.input.Keyboard.KEY_V:
                if (ctrl) { runSelectionOp("paste"); return; }
                break;
            case org.lwjgl.input.Keyboard.KEY_T:
                runSelectionOp("trim");
                return;
            case org.lwjgl.input.Keyboard.KEY_S:
                runStats();
                return;
            case org.lwjgl.input.Keyboard.KEY_F1:
                helpOpen = true;
                return;
        }
        if (typedChar == '?' || typedChar == '/') {
            helpOpen = true;
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
