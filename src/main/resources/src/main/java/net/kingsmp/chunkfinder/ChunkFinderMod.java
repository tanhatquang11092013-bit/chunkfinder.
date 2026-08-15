package net.kingsmp.chunkfinder;

// ================================================================
// MOD CHUNKFINDER 1.21.1 FABRIC - ALL-IN-ONE SINGLE FILE
// ================================================================

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.glfw.GLFW;

import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Environment(EnvType.CLIENT)
public class ChunkFinderMod implements ClientModInitializer {

    // ==================== CẤU HÌNH ====================
    public static int scanRadius = 5;
    public static long cooldownMs = 400;
    public static int scoreThreshold = 60;
    public static int maxTargets = 15;
    public static int scanStep = 3;
    public static boolean renderBoxes = true;
    public static boolean renderCoordinates = true;
    public static boolean autoScan = true;
    public static float boxAlpha = 0.7f;
    public static int boxHeight = 40;
    public static int renderMode = 0;

    // ==================== DỮ LIỆU ====================
    private static final MinecraftClient MC = MinecraftClient.getInstance();
    private static final Map<Long, Integer> chunkScore = new ConcurrentHashMap<>();
    private static final Map<Long, Long> chunkLastScan = new ConcurrentHashMap<>();
    private static final Set<Long> targetChunks = ConcurrentHashMap.newKeySet();
    private static long lastScanTime = 0;
    private static int tickCounter = 0;
    private static int renderCounter = 0;
    private static KeyBinding openMenuKey;
    private static ChunkFinderMenu menuInstance;

    // ==================== INIT ====================
    @Override
    public void onInitializeClient() {
        loadConfig();
        
        openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.chunkfinder.open",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            "category.chunkfinder"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (openMenuKey.wasPressed() && client.currentScreen == null) {
                menuInstance = new ChunkFinderMenu();
                client.setScreen(menuInstance);
            }
            if (autoScan && MC.world != null && MC.player != null) {
                tickCounter++;
                if (tickCounter % 2 == 0) scanChunks();
            }
            if (MC.world != null && MC.player != null && MC.currentScreen == null) {
                renderHUD();
            }
        });

        WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
            if (!renderBoxes || targetChunks.isEmpty()) return;
            renderCounter++;
            if (renderCounter % 3 != 0) return;
            renderChunks(ctx);
        });

        System.out.println("[ChunkFinder] Loaded - KingsMP optimized");
    }

    // ==================== QUÉT CHUNK ====================
    private static void scanChunks() {
        if (MC.world == null || MC.player == null) return;
        long now = System.currentTimeMillis();
        if (now - lastScanTime < cooldownMs) return;

        int baseX = (int) MC.player.getX() >> 4;
        int baseZ = (int) MC.player.getZ() >> 4;

        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                int cx = baseX + dx, cz = baseZ + dz;
                long key = ChunkPos.toLong(cx, cz);
                WorldChunk chunk = MC.world.getChunk(cx, cz);
                if (chunk == null || chunk.isEmpty()) continue;
                if (chunkLastScan.containsKey(key) && now - chunkLastScan.get(key) < 8000) continue;

                int score = analyzeChunk(chunk, cx, cz);
                chunkScore.put(key, score);
                chunkLastScan.put(key, now);
                if (score >= scoreThreshold) targetChunks.add(key);
                else targetChunks.remove(key);
            }
        }
        lastScanTime = now;
        trimTargets();
    }

    private static int analyzeChunk(WorldChunk chunk, int cx, int cz) {
        int oreCount = 0, spawnerCount = 0, caveAirCount = 0, totalSamples = 0;
        int baseOffset = MC.world.getBottomY() + 4;
        int[] heightMap = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE).asIntArray();
        int step = scanStep;

        for (int x = 0; x < 16; x += step) {
            for (int z = 0; z < 16; z += step) {
                int idx = z * 16 + x;
                int surfaceY = heightMap[idx];
                if (surfaceY <= MC.world.getBottomY()) continue;
                for (int y = surfaceY; y >= baseOffset; y -= step) {
                    BlockPos pos = new BlockPos((cx << 4) + x, y, (cz << 4) + z);
                    Block block = chunk.getBlockState(pos).getBlock();
                    totalSamples++;
                    if (block == Blocks.AIR || block == Blocks.CAVE_AIR) { caveAirCount++; continue; }
                    if (block == Blocks.SPAWNER) { spawnerCount++; continue; }
                    if (isOreBlock(block)) oreCount++;
                }
            }
        }
        if (totalSamples == 0) return 0;
        float oreDensity = (float) oreCount / totalSamples;
        float caveRatio = (float) caveAirCount / totalSamples;
        int spawnerScore = Math.min(spawnerCount * 15, 40);
        int caveScore = caveRatio > 0.3f ? 25 : (caveRatio > 0.15f ? 12 : 0);
        int oreScore = (int) (oreDensity * 130);
        return Math.min(100, oreScore + spawnerScore + caveScore);
    }

    private static boolean isOreBlock(Block block) {
        Identifier id = Registries.BLOCK.getId(block);
        String path = id.getPath();
        return path.endsWith("_ore") || path.equals("ancient_debris") ||
               path.equals("gilded_blackstone") || path.contains("deepslate");
    }

    private static void trimTargets() {
        if (targetChunks.size() <= maxTargets) return;
        List<Map.Entry<Long, Integer>> sorted = new ArrayList<>(chunkScore.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        Set<Long> keep = new HashSet<>();
        for (int i = 0; i < Math.min(maxTargets, sorted.size()); i++) keep.add(sorted.get(i).getKey());
        targetChunks.retainAll(keep);
    }

    public static void forceRescan() {
        chunkScore.clear();
        chunkLastScan.clear();
        targetChunks.clear();
        lastScanTime = 0;
        tickCounter = 0;
        if (MC.world != null && MC.player != null) scanChunks();
    }

    // ==================== RENDER CHUNK ====================
    private static void renderChunks(WorldRenderContext ctx) {
        MatrixStack matrices = ctx.matrixStack();
        VertexConsumerProvider.Immediate vcp = MC.getBufferBuilders().getEntityVertexConsumers();
        double px = MC.player.getX(), py = MC.player.getY(), pz = MC.player.getZ();

        matrices.push();
        matrices.translate(-px, -py, -pz);

        for (long key : targetChunks) {
            int cx = (int) key, cz = (int) (key >> 32);
            int score = chunkScore.getOrDefault(key, 0);
            Color color = getColorByScore(score);
            color = new Color(color.getRed(), color.getGreen(), color.getBlue(), (int)(boxAlpha * 255));
            drawChunkBox(matrices, vcp, cx, cz, color);
        }
        vcp.draw();
        matrices.pop();
    }

    private static void drawChunkBox(MatrixStack matrices, VertexConsumerProvider vcp, int cx, int cz, Color color) {
        double x1 = cx * 16.0, z1 = cz * 16.0, x2 = x1 + 16.0, z2 = z1 + 16.0;
        double y1 = MC.world.getBottomY() + 4, y2 = y1 + boxHeight;
        float r = color.getRed()/255f, g = color.getGreen()/255f, b = color.getBlue()/255f, a = color.getAlpha()/255f;
        var pos = matrices.peek().getPositionMatrix();

        if (renderMode == 1 || renderMode == 2) {
            VertexConsumer vc = vcp.getBuffer(RenderLayer.getTranslucent());
            float fa = 0.15f;
            float[][][] faces = {
                {{x1,y1,z1, x2,y1,z1, x2,y2,z1}, {x1,y1,z1, x1,y2,z1, x2,y2,z1}},
                {{x1,y1,z2, x2,y1,z2, x2,y2,z2}, {x1,y1,z2, x1,y2,z2, x2,y2,z2}},
                {{x1,y1,z1, x1,y1,z2, x1,y2,z2}, {x1,y1,z1, x1,y2,z1, x1,y2,z2}}
            };
            for (var face : faces) for (var tri : face) for (int i=0; i<3; i++)
                vc.vertex(pos, tri[i][0], tri[i][1], tri[i][2]).color(r, g, b, fa).next();
        }
        if (renderMode == 0 || renderMode == 2) {
            VertexConsumer vc = vcp.getBuffer(RenderLayer.getLines());
            float[][] edges = {
                {x1,y1,z1, x2,y1,z1}, {x2,y1,z1, x2,y1,z2}, {x2,y1,z2, x1,y1,z2}, {x1,y1,z2, x1,y1,z1},
                {x1,y2,z1, x2,y2,z1}, {x2,y2,z1, x2,y2,z2}, {x2,y2,z2, x1,y2,z2}, {x1,y2,z2, x1,y2,z1},
                {x1,y1,z1, x1,y2,z1}, {x2,y1,z1, x2,y2,z1}, {x2,y1,z2, x2,y2,z2}, {x1,y1,z2, x1,y2,z2}
            };
            for (float[] e : edges) {
                vc.vertex(pos, e[0], e[1], e[2]).color(r, g, b, a).next();
                vc.vertex(pos, e[3], e[4], e[5]).color(r, g, b, a).next();
            }
        }
    }

    private static Color getColorByScore(int score) {
        float hue = 0.35f - (score - scoreThreshold) / 100.0f * 0.35f;
        return Color.getHSBColor(Math.max(0, Math.min(1, hue)), 1.0f, 0.7f);
    }

    // ==================== HUD ====================
    private static void renderHUD() {
        DrawContext context = new DrawContext(MC, MC.getBufferBuilders().getEntityVertexConsumers());
        int x = 10, y = 10;
        context.fill(x - 5, y - 5, x + 200, y + 75, 0xCC000000);
        context.drawBorder(x - 3, y - 3, 200, 75, 0xFFAA8800);
        y += 2;
        context.drawText(MC.textRenderer, "§6ChunkFinder", x, y, 0xFFFFFF, false);
        y += 12;
        context.drawText(MC.textRenderer, "§7Targets: §a" + targetChunks.size(), x, y, 0xFFFFFF, false);
        y += 10;
        context.drawText(MC.textRenderer, "§7Radius: §f" + scanRadius + "  §7Cooldown: §f" + cooldownMs + "ms", x, y, 0xFFFFFF, false);
        y += 10;
        context.drawText(MC.textRenderer, "§7[§eJ§7] Menu  §7[§eK§7] Rescan", x, y, 0xFFFFFF, false);
    }

    // ==================== CẤU HÌNH ====================
    private static void loadConfig() {
        File file = new File("config/chunkfinder.properties");
        if (!file.exists()) { saveConfig(); return; }
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
            scanRadius = Integer.parseInt(props.getProperty("scanRadius", "5"));
            cooldownMs = Long.parseLong(props.getProperty("cooldownMs", "400"));
            scoreThreshold = Integer.parseInt(props.getProperty("scoreThreshold", "60"));
            maxTargets = Integer.parseInt(props.getProperty("maxTargets", "15"));
            scanStep = Integer.parseInt(props.getProperty("scanStep", "3"));
            renderBoxes = Boolean.parseBoolean(props.getProperty("renderBoxes", "true"));
            renderCoordinates = Boolean.parseBoolean(props.getProperty("renderCoordinates", "true"));
            autoScan = Boolean.parseBoolean(props.getProperty("autoScan", "true"));
            boxAlpha = Float.parseFloat(props.getProperty("boxAlpha", "0.7"));
            boxHeight = Integer.parseInt(props.getProperty("boxHeight", "40"));
            renderMode = Integer.parseInt(props.getProperty("renderMode", "0"));
        } catch (Exception e) { saveConfig(); }
    }

    private static void saveConfig() {
        Properties props = new Properties();
        props.setProperty("scanRadius", String.valueOf(scanRadius));
        props.setProperty("cooldownMs", String.valueOf(cooldownMs));
        props.setProperty("scoreThreshold", String.valueOf(scoreThreshold));
        props.setProperty("maxTargets", String.valueOf(maxTargets));
        props.setProperty("scanStep", String.valueOf(scanStep));
        props.setProperty("renderBoxes", String.valueOf(renderBoxes));
        props.setProperty("renderCoordinates", String.valueOf(renderCoordinates));
        props.setProperty("autoScan", String.valueOf(autoScan));
        props.setProperty("boxAlpha", String.valueOf(boxAlpha));
        props.setProperty("boxHeight", String.valueOf(boxHeight));
        props.setProperty("renderMode", String.valueOf(renderMode));
        new File("config").mkdirs();
        try (FileOutputStream fos = new FileOutputStream("config/chunkfinder.properties")) {
            props.store(fos, "ChunkFinder Config");
        } catch (IOException e) { e.printStackTrace(); }
    }

    // ==================== MENU ====================
    public static class ChunkFinderMenu extends Screen {
        private static final int W = 440, H = 520;
        private int left, top;
        private final Map<String, SliderWidget> sliders = new HashMap<>();

        public ChunkFinderMenu() { super(Text.literal("ChunkFinder")); }

        @Override
        protected void init() {
            left = (width - W) / 2; top = (height - H) / 2;
            addDrawableChild(new TextWidget(left + 10, top + 10, 420, 20,
                Text.literal("§l§6ChunkFinder v3.0 - KingsMP"), textRenderer));

            int y = 40;
            addInfo("Targets: §a" + targetChunks.size(), y); y += 22;
            addInfo("Radius: " + scanRadius + " | Cooldown: " + cooldownMs + "ms", y); y += 22;
            addInfo("Threshold: " + scoreThreshold + " | Auto: " + (autoScan ? "§aON" : "§cOFF"), y); y += 32;

            // Slider: Radius
            SliderWidget rS = new SliderWidget(left + 10, y, 200, 20, Text.literal("Radius: " + scanRadius), 2, 12, scanRadius) {
                @Override protected void updateMessage() { setMessage(Text.literal("Radius: " + (int)value)); }
                @Override protected void applyValue() { scanRadius = (int)value; saveConfig(); }
            };
            addDrawableChild(rS); sliders.put("radius", rS);

            // Slider: Cooldown
            SliderWidget cS = new SliderWidget(left + 220, y, 200, 20, Text.literal("Cooldown: " + cooldownMs + "ms"), 100, 1200, cooldownMs) {
                @Override protected void updateMessage() { setMessage(Text.literal("Cooldown: " + (int)value + "ms")); }
                @Override protected void applyValue() { cooldownMs = (long)value; saveConfig(); }
            };
            addDrawableChild(cS); sliders.put("cooldown", cS);
            y += 30;

            // Slider: Threshold
            SliderWidget tS = new SliderWidget(left + 10, y, 200, 20, Text.literal("Threshold: " + scoreThreshold), 30, 95, scoreThreshold) {
                @Override protected void updateMessage() { setMessage(Text.literal("Threshold: " + (int)value)); }
                @Override protected void applyValue() { scoreThreshold = (int)value; saveConfig(); }
            };
            addDrawableChild(tS); sliders.put("threshold", tS);

            // Slider: Box Height
            SliderWidget hS = new SliderWidget(left + 220, y, 200, 20, Text.literal("Height: " + boxHeight), 10, 80, boxHeight) {
                @Override protected void updateMessage() { setMessage(Text.literal("Height: " + (int)value)); }
                @Override protected void applyValue() { boxHeight = (int)value; saveConfig(); }
            };
            addDrawableChild(hS); sliders.put("height", hS);
            y += 30;

            // Toggles
            addToggle("Auto-scan: ", left + 10, y, 130, () -> autoScan, v -> autoScan = v);
            addToggle("Render: ", left + 150, y, 130, () -> renderBoxes, v -> renderBoxes = v);
            addToggle("Coords: ", left + 290, y, 130, () -> renderCoordinates, v -> renderCoordinates = v);
            y += 30;

            // Mode + Alpha
            ButtonWidget modeBtn = ButtonWidget.builder(
                Text.literal("Mode: " + getModeName(renderMode)),
                btn -> { renderMode = (renderMode + 1) % 3; btn.setMessage(Text.literal("Mode: " + getModeName(renderMode))); saveConfig(); }
            ).dimensions(left + 10, y, 180, 20).build();
            addDrawableChild(modeBtn);

            SliderWidget aS = new SliderWidget(left + 200, y, 210, 20, Text.literal("Alpha: " + (int)(boxAlpha*100) + "%"), 10, 100, (int)(boxAlpha*100)) {
                @Override protected void updateMessage() { setMessage(Text.literal("Alpha: " + (int)value + "%")); }
                @Override protected void applyValue() { boxAlpha = (float)value / 100; saveConfig(); }
            };
            addDrawableChild(aS); sliders.put("alpha", aS);
            y += 30;

            // Actions
            addDrawableChild(ButtonWidget.builder(Text.literal("§cClear"), b -> { targetChunks.clear(); chunkScore.clear(); }).dimensions(left + 10, y, 100, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("§aRescan"), b -> forceRescan()).dimensions(left + 120, y, 100, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("§bList"), b -> showList()).dimensions(left + 230, y, 100, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("§cClose"), b -> close()).dimensions(left + 340, y, 80, 20).build());

            saveConfig();
        }

        private void addInfo(String text, int y) {
            addDrawableChild(new TextWidget(left + 10, y, 420, 20, Text.literal("§7" + text), textRenderer));
        }

        private void addToggle(String label, int x, int y, int w, java.util.function.BooleanSupplier getter, java.util.function.Consumer<Boolean> setter) {
            ButtonWidget btn = ButtonWidget.builder(
                Text.literal(label + (getter.getAsBoolean() ? "§aON" : "§cOFF")),
                b -> { boolean v = !getter.getAsBoolean(); setter.accept(v); b.setMessage(Text.literal(label + (v ? "§aON" : "§cOFF"))); saveConfig(); }
            ).dimensions(x, y, w, 20).build();
            addDrawableChild(btn);
        }

        private String getModeName(int m) { return switch(m) { case 0 -> "Outline"; case 1 -> "Filled"; case 2 -> "Both"; default -> "Unknown"; }; }

        private void showList() {
            System.out.println("=== TARGETS ===");
            for (long key : targetChunks) {
                int cx = (int) key, cz = (int)(key >> 32);
                System.out.printf("(%d, %d) - %d\n", cx, cz, chunkScore.getOrDefault(key, 0));
            }
            System.out.println("Total: " + targetChunks.size());
        }

        @Override
        public void render(DrawContext ctx, int mx, int my, float d) {
            ctx.fill(left - 5, top - 5, left + W + 5, top + H + 5, 0xCC222222);
            ctx.fill(left, top, left + W, top + H, 0xCC333333);
            ctx.drawBorder(left - 2, top - 2, W + 4, H + 4, 0xFFAA8800);
            ctx.drawText(textRenderer, "§7--- Settings ---", left + 10, top + 118, 0xFFFFFF, false);
            ctx.drawText(textRenderer, "§7--- Display ---", left + 10, top + 220, 0xFFFFFF, false);
            ctx.drawText(textRenderer, "§7--- Actions ---", left + 10, top + 330, 0xFFFFFF, false);
            super.render(ctx, mx, my, d);
        }

        @Override public boolean shouldPause() { return false; }
        @Override public boolean keyPressed(int key, int sc, int mod) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_J) { close(); return true; }
            return super.keyPressed(key, sc, mod);
        }
    }
}
