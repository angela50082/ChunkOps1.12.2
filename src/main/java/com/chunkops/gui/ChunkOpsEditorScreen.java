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

        // 世界信息
        String name = selectedWorld >= 0 && selectedWorld < worlds.size()
                ? worlds.get(selectedWorld).getDisplayName() : "（无存档）";
        this.fontRenderer.drawString("存档: " + name, 68, 12, 0xFFFFFF);
        this.fontRenderer.drawString(String.format("%d 个存档 | 已加载 %d 区块 | 加载中 %d", worlds.size(), loadedCount, pendingCount),
                220, 12, 0xAAAAAA);

        // 状态栏
        this.fontRenderer.drawString(String.format("中心: %.0f, %.0f   缩放: %.1f px/方块",
                viewX, viewZ, ppb), 6, this.height - 24, 0xAAAAAA);
        this.fontRenderer.drawString("鼠标拖拽移动 · 滚轮缩放 · ESC 返回", this.width / 2 - 90, this.height - 12, 0x888888);

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
            ChunkOpsGuiHandler.backToMainMenu();
        }
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
