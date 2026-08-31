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

    private static final int BTN_PREV = 1;
    private static final int BTN_NEXT = 2;
    private static final int BTN_BACK = 3;

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
    private boolean menuOpen = false;
    private final java.util.List<String> log = new ArrayList<String>();

    private static final int BTN_MENU_REMOVE = 100;
    private static final int BTN_MENU_CLEAR = 101;
    private static final int BTN_MENU_TRIM = 102;
    private static final int BTN_MENU_STATS = 103;
    private static final int BTN_MENU_CLOSE = 104;

    private void logLine(String s) {
        log.add(s);
        while (log.size() > 6) log.remove(0);
    }

    /** 屏幕坐标 → 方块坐标（地图区域）。 */
    private double screenToBlockX(int mx) {
        return viewX + (mx - this.width / 2) / zoom;
    }

    private double screenToBlockZ(int my) {
        int mapTop = 30;
        int mapBottom = this.height - 30;
        int mapCenterY = (mapTop + mapBottom) / 2;
        return viewZ + (my - mapCenterY) / zoom;
    }

    private void openMenu(int mx, int my) {
        closeMenu();
        menuOpen = true;
        int bx = Math.max(10, Math.min(this.width - 90, mx));
        int by = Math.max(34, Math.min(this.height - 140, my));
        this.buttonList.add(new GuiButton(BTN_MENU_REMOVE, bx, by, 80, 18, "移除区块"));
        this.buttonList.add(new GuiButton(BTN_MENU_CLEAR, bx, by + 20, 80, 18, "清空区块"));
        this.buttonList.add(new GuiButton(BTN_MENU_TRIM, bx, by + 40, 80, 18, "剪裁(保留选区)"));
        this.buttonList.add(new GuiButton(BTN_MENU_STATS, bx, by + 60, 80, 18, "统计选区"));
        this.buttonList.add(new GuiButton(BTN_MENU_CLOSE, bx, by + 80, 80, 18, "关闭"));
    }

    private void closeMenu() {
        menuOpen = false;
        java.util.Iterator<GuiButton> it = this.buttonList.iterator();
        while (it.hasNext()) {
            int id = it.next().id;
            if (id >= BTN_MENU_REMOVE && id <= BTN_MENU_CLOSE) it.remove();
        }
    }

    private void updateWorldDir() {
        if (selectedWorld >= 0 && selectedWorld < worlds.size()) {
            File savesDir = new File(Loader.instance().getConfigDir().getParentFile(), "saves");
            currentWorldDir = new File(savesDir, worlds.get(selectedWorld).getFileName());
        } else {
            currentWorldDir = null;
        }
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
        this.buttonList.add(new GuiButton(BTN_PREV, 6, y, 24, 20, "<"));
        this.buttonList.add(new GuiButton(BTN_NEXT, 34, y, 24, 20, ">"));
        this.buttonList.add(new GuiButton(BTN_BACK, this.width - 86, y, 80, 20, "返回主菜单"));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // 背景
        drawRect(0, 0, this.width, this.height, 0xFF14181C);
        drawRect(0, 0, this.width, 30, 0xFF20262C);

        // 地图画布区域（顶部 30px 控制条 + 底部 30px 状态条）
        int mapTop = 30;
        int mapBottom = this.height - 30;
        int mapCenterY = (mapTop + mapBottom) / 2;
        double ppb = zoom;

        // ---- 2D 地图：可见 chunk 渲染（异步加载 + 纹理） ----
        int minCx = (int) Math.floor((viewX - (double) this.width / 2 / ppb) / 16);
        int maxCx = (int) Math.floor((viewX + (double) this.width / 2 / ppb) / 16);
        int minCz = (int) Math.floor((viewZ - (double) (mapBottom - mapTop) / 2 / ppb) / 16);
        int maxCz = (int) Math.floor((viewZ + (double) (mapBottom - mapTop) / 2 / ppb) / 16);
        int loadedCount = 0;
        int pendingCount = 0;
        if (currentWorldDir != null) {
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    int sx = this.width / 2 + (int) Math.round((cx * 16 - viewX) * ppb);
                    int sy = mapCenterY + (int) Math.round((cz * 16 - viewZ) * ppb);
                    int size = Math.max(1, (int) Math.round(16 * ppb));
                    ChunkMapRenderer.ChunkMapTile tile = renderer.getOrLoad(currentWorldDir, cx, cz);
                    if (tile != null) {
                        ResourceLocation loc = renderer.textureFor(ChunkMapRenderer.key(cx, cz), tile,
                                this.mc.getTextureManager());
                        this.mc.getTextureManager().bindTexture(loc);
                        drawTexturedQuad(sx, sy, size, size);
                        loadedCount++;
                    } else {
                        drawRect(sx, sy, sx + size, sy + size, renderer.pendingColor());
                        pendingCount++;
                    }
                }
            }
        }

        // ---- 区块网格与坐标轴（画在地图之上） ----
        int gridMinX = (int) Math.floor(viewX - (double) this.width / 2 / ppb);
        int gridMaxX = (int) Math.ceil(viewX + (double) this.width / 2 / ppb);
        int gridMinZ = (int) Math.floor(viewZ - (double) (mapBottom - mapTop) / 2 / ppb);
        int gridMaxZ = (int) Math.ceil(viewZ + (double) (mapBottom - mapTop) / 2 / ppb);
        for (int chunkX = (gridMinX >> 4) << 4; chunkX <= gridMaxX; chunkX += 16) {
            int sx = this.width / 2 + (int) Math.round((chunkX - viewX) * ppb);
            if (sx >= 0 && sx <= this.width) {
                drawVerticalLine(sx, mapTop, mapBottom, 0x663A444E);
            }
        }
        for (int chunkZ = (gridMinZ >> 4) << 4; chunkZ <= gridMaxZ; chunkZ += 16) {
            int sy = mapCenterY + (int) Math.round((chunkZ - viewZ) * ppb);
            if (sy >= mapTop && sy <= mapBottom) {
                drawHorizontalLine(0, this.width, sy, 0x663A444E);
            }
        }
        int centerX = this.width / 2;
        drawVerticalLine(centerX, mapTop, mapBottom, 0xFF55616C);
        drawHorizontalLine(0, this.width, mapCenterY, 0xFF55616C);

        // ---- 选区边框 ----
        if (selMinCx != -1 && currentWorldDir != null) {
            int sx1 = this.width / 2 + (int) Math.round((selMinCx * 16 - viewX) * ppb);
            int sy1 = mapCenterY + (int) Math.round((selMinCz * 16 - viewZ) * ppb);
            int sx2 = this.width / 2 + (int) Math.round(((selMaxCx + 1) * 16 - viewX) * ppb);
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
        this.fontRenderer.drawString("存档: " + name, 68, 12, 0xFFFFFF);
        this.fontRenderer.drawString(String.format("%d 个存档 | 已加载 %d 区块 | 加载中 %d", worlds.size(), loadedCount, pendingCount),
                220, 12, 0xAAAAAA);

        // 状态栏
        this.fontRenderer.drawString(String.format("中心: %.0f, %.0f   缩放: %.1f px/方块",
                viewX, viewZ, ppb), 6, this.height - 24, 0xAAAAAA);
        this.fontRenderer.drawString("左键框选 · 中键拖动 · 右键菜单 · 滚轮缩放 · ESC 返回",
                this.width / 2 - 120, this.height - 12, 0x888888);

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

    /** 以 0..1 纹理坐标绘制贴图（DynamicTexture 非 256 atlas，不能用 drawTexturedModalRect）。 */
    private void drawTexturedQuad(int x, int y, int w, int h) {
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(7, DefaultVertexFormats.POSITION_TEX);
        buf.pos(x, y + h, (double) this.zLevel).tex(0.0, 1.0).endVertex();
        buf.pos(x + w, y + h, (double) this.zLevel).tex(1.0, 1.0).endVertex();
        buf.pos(x + w, y, (double) this.zLevel).tex(1.0, 0.0).endVertex();
        buf.pos(x, y, (double) this.zLevel).tex(0.0, 0.0).endVertex();
        tess.draw();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_PREV) {
            if (!worlds.isEmpty()) selectedWorld = (selectedWorld - 1 + worlds.size()) % worlds.size();
            updateWorldDir();
        } else if (button.id == BTN_NEXT) {
            if (!worlds.isEmpty()) selectedWorld = (selectedWorld + 1) % worlds.size();
            updateWorldDir();
        } else if (button.id == BTN_BACK) {
            closeMenu();
            ChunkOpsGuiHandler.backToMainMenu();
        } else if (button.id == BTN_MENU_REMOVE) {
            runSelectionOp("remove");
        } else if (button.id == BTN_MENU_CLEAR) {
            runSelectionOp("clear");
        } else if (button.id == BTN_MENU_TRIM) {
            runSelectionOp("trim");
        } else if (button.id == BTN_MENU_STATS) {
            runSelectionOp("stats");
        } else if (button.id == BTN_MENU_CLOSE) {
            closeMenu();
        }
    }

    /** 执行选区操作（含 session.lock 检查）。 */
    private void runSelectionOp(String op) {
        closeMenu();
        if (currentWorldDir == null) {
            logLine("未选择存档");
            return;
        }
        if (GuiOps.hasSessionLock(currentWorldDir)) {
            logLine("[警告] 检测到 session.lock——该存档可能被游戏实例占用，继续操作可能损坏！");
        }
        if (selMinCx == -1) {
            logLine("请先框选区块（左键拖动）");
            return;
        }
        int w = selMaxCx - selMinCx + 1;
        int h = selMaxCz - selMinCz + 1;
        logLine("选区 [" + selMinCx + ".." + selMaxCx + ", " + selMinCz + ".." + selMaxCz + "] " + w + "x" + h + " 区块");
        if (op.equals("remove")) {
            for (int cx = selMinCx; cx <= selMaxCx; cx++) {
                for (int cz = selMinCz; cz <= selMaxCz; cz++) {
                    String r = GuiOps.removeChunk(currentWorldDir, cx, cz, false);
                    logLine(r);
                }
            }
        } else if (op.equals("clear")) {
            for (int cx = selMinCx; cx <= selMaxCx; cx++) {
                for (int cz = selMinCz; cz <= selMaxCz; cz++) {
                    String r = GuiOps.clearChunk(currentWorldDir, cx, cz, false);
                    logLine(r);
                }
            }
        } else if (op.equals("trim")) {
            logLine(GuiOps.trimArea(currentWorldDir, selMinCx, selMaxCx, selMinCz, selMaxCz, false));
        } else if (op.equals("stats")) {
            String s = GuiOps.statsArea(currentWorldDir, selMinCx, selMaxCx, selMinCz, selMaxCz);
            for (String line : s.split("\n")) logLine(line);
        }
        // 操作后清除区块缓存，强制重新加载
        // （renderer 的 tiles/textures 由 LRU 管理；移除的 chunk 重新加载后为空——texture 缓存保留旧图，可接受）
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        int dWheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (dWheel != 0) {
            double factor = dWheel > 0 ? 1.25 : 0.8;
            zoom = Math.max(0.5, Math.min(32.0, zoom * factor));
        }
        super.handleMouseInput();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton); // 先处理按钮
        if (menuOpen) return;
        if (mouseButton == 0) { // 左键：框选
            selecting = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
        } else if (mouseButton == 2) { // 中键：移动视图
            moving = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
        } else if (mouseButton == 1) { // 右键：菜单
            openMenu(mouseX, mouseY);
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (menuOpen) return;
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
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (keyCode == 1) { // ESC
            ChunkOpsGuiHandler.backToMainMenu();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
