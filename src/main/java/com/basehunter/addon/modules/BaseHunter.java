package com.basehunter.addon.modules;

import com.basehunter.addon.BaseHunterAddon;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.*;

public class BaseHunter extends Module {
    private static final int K_STORAGE = 0, K_FUNC = 1, K_REDSTONE = 2, K_VALUABLE = 3, K_LIGHT = 4, K_BUILD = 5, K_PLAYER = 6, K_COUNT = 7;
    private static final String[] KIND_NAMES = {"storage", "workstation", "redstone", "valuable", "light", "building", "player-only"};
    private static final String[] KIND_KEYS = {"storage", "workstation", "redstone", "valuable", "light", "building", "player-only"};
    private static final double[] DEF_WEIGHT = {3.0, 2.0, 1.5, 4.0, 1.0, 0.15, 6.0};
    private static final double[] CAP = {40, 20, 20, 30, 15, 15, 30};
    private static final boolean[] AMBIGUOUS = {true, true, false, true, true, true, false};

    private static final int S_VILLAGE = 1, S_MINE = 2, S_STRONGHOLD = 4, S_MONUMENT = 8, S_NETHER = 16, S_END = 32, S_TRIAL = 64, S_ANCIENT = 128;
    private static final int S_STRONG = 1 << 10;
    private static final int SIG_THRESHOLD = 4;

    private final IdentityHashMap<Block, Integer> table = new IdentityHashMap<>();
    private Set<Block> highlightSet = new HashSet<>();
    private Block caveAirBlock;

    private final SettingGroup sgScan = settings.createGroup("Scanning");
    private final SettingGroup sgDetect = settings.createGroup("Detection");
    private final SettingGroup sgDonut = settings.createGroup("DonutSMP Mode");
    private final SettingGroup sgWeights = settings.createGroup("Weights");
    private final SettingGroup sgBlocks = settings.createGroup("Block ESP");
    private final SettingGroup sgRender = settings.createGroup("Render & UI Theme");
    private final SettingGroup sgAlerts = settings.createGroup("Alerts");

    private final Setting<Integer> scanRadius = sgScan.add(new IntSetting.Builder().name("scan-radius").defaultValue(16).range(2, 32).sliderRange(2, 32).build());
    private final Setting<Double> budgetMs = sgScan.add(new DoubleSetting.Builder().name("time-budget-ms").defaultValue(3.0).min(0.5).sliderMax(12).build());
    private final Setting<Integer> scanDelay = sgScan.add(new IntSetting.Builder().name("scan-delay-ticks").defaultValue(30).range(0, 200).sliderMax(100).build());

    private final Setting<Boolean> detectGeometry = sgDetect.add(new BoolSetting.Builder().name("tunnel-shaft-detection").defaultValue(true).build());
    private final Setting<Integer> minTunnel = sgDetect.add(new IntSetting.Builder().name("min-tunnel-length").defaultValue(9).range(5, 15).sliderRange(5, 15).visible(detectGeometry::get).build());
    private final Setting<Integer> minShaft = sgDetect.add(new IntSetting.Builder().name("min-shaft-depth").defaultValue(6).range(3, 40).sliderRange(3, 40).visible(detectGeometry::get).build());
    private final Setting<Integer> geometryMaxY = sgDetect.add(new IntSetting.Builder().name("overworld-geometry-max-y").defaultValue(50).range(-60, 200).sliderRange(-60, 120).visible(detectGeometry::get).build());
    private final Setting<Boolean> ignoreCaveAir = sgDetect.add(new BoolSetting.Builder().name("ignore-cave-air").defaultValue(true).visible(detectGeometry::get).build());
    private final Setting<Double> structureDiscount = sgDetect.add(new DoubleSetting.Builder().name("natural-structure-multiplier").defaultValue(0.3).min(0).max(1).sliderRange(0, 1).build());
    private final Setting<Boolean> trackActivity = sgDetect.add(new BoolSetting.Builder().name("track-block-changes").defaultValue(true).build());
    private final Setting<Boolean> trackEntities = sgDetect.add(new BoolSetting.Builder().name("track-entities").defaultValue(true).build());

    private final Setting<Boolean> donutMode = sgDonut.add(new BoolSetting.Builder().name("donutsmp-mode").defaultValue(false).build());
    private final Setting<Integer> donutSurfaceY = sgDonut.add(new IntSetting.Builder().name("surface-y").defaultValue(63).range(-64, 320).sliderRange(0, 128).visible(donutMode::get).build());
    private final Setting<Integer> donutMinEntranceDepth = sgDonut.add(new IntSetting.Builder().name("min-entrance-depth").defaultValue(10).range(5, 60).sliderRange(5, 60).visible(donutMode::get).build());
    private final Setting<Boolean> donutRequireOpenTop = sgDonut.add(new BoolSetting.Builder().name("require-open-top").defaultValue(true).visible(donutMode::get).build());
    private final Setting<Double> donutEntranceBonus = sgDonut.add(new DoubleSetting.Builder().name("entrance-bonus").defaultValue(25).min(0).sliderMax(60).visible(donutMode::get).build());
    private final Setting<Double> donutLitterBonus = sgDonut.add(new DoubleSetting.Builder().name("entrance-litter-bonus").defaultValue(6).min(0).sliderMax(20).visible(donutMode::get).build());
    private final Setting<Integer> donutSurfaceAnomalyY = sgDonut.add(new IntSetting.Builder().name("surface-anomaly-y").defaultValue(0).range(-64, 320).sliderRange(-64, 128).visible(donutMode::get).build());

    private final List<Setting<Double>> kindWeight = new ArrayList<>();
    private final Setting<Double> wShaft;
    private final Setting<Double> wTunnel;
    private final Setting<Double> wEntity;
    private final Setting<Double> wActivity;

    private final Setting<List<Block>> highlightBlocks = sgBlocks.add(new BlockListSetting.Builder().name("blocks").defaultValue(defaultHighlightBlocks()).onChanged(list -> highlightSet = new HashSet<>(list)).build());
    private final Setting<Integer> blockRange = sgBlocks.add(new IntSetting.Builder().name("block-render-range").defaultValue(96).range(8, 512).sliderRange(8, 256).build());
    private final Setting<Integer> maxBlocksPerChunk = sgBlocks.add(new IntSetting.Builder().name("max-blocks-per-chunk").defaultValue(200).range(10, 2000).sliderRange(10, 1000).build());
    private final Setting<ShapeMode> blockShape = sgBlocks.add(new EnumSetting.Builder<ShapeMode>().name("block-shape").defaultValue(ShapeMode.Both).build());

    public enum BiomeTheme { Plains, Desert, Nether, End, Deep_Dark, Cherry_Grove }
    private final Setting<BiomeTheme> uiTheme = sgRender.add(new EnumSetting.Builder<BiomeTheme>().name("biome-ui-theme").defaultValue(BiomeTheme.Plains).build());
    
    private final Setting<SettingColor> blockSide = sgRender.add(new ColorSetting.Builder().name("block-side-color").defaultValue(new SettingColor(0, 200, 255, 45)).build());
    private final Setting<SettingColor> blockLine = sgRender.add(new ColorSetting.Builder().name("block-line-color").defaultValue(new SettingColor(0, 200, 255, 220)).build());
    
    private final Setting<Integer> lowThreshold = sgRender.add(new IntSetting.Builder().name("low-score").defaultValue(12).range(1, 500).build());
    private final Setting<Integer> mediumThreshold = sgRender.add(new IntSetting.Builder().name("medium-score").defaultValue(30).range(1, 500).build());
    private final Setting<Integer> highThreshold = sgRender.add(new IntSetting.Builder().name("high-score").defaultValue(55).range(1, 500).build());
    
    private final Setting<Integer> renderRadius = sgRender.add(new IntSetting.Builder().name("chunk-render-radius").defaultValue(24).range(2, 64).build());
    private final Setting<Integer> minRenderTier = sgRender.add(new IntSetting.Builder().name("min-tier-to-draw").defaultValue(1).range(1, 3).build());
    private final Setting<ShapeMode> chunkShape = sgRender.add(new EnumSetting.Builder<ShapeMode>().name("chunk-shape").defaultValue(ShapeMode.Both).build());
    
    public enum RenderY { Player, Auto, Fixed }
    private final Setting<RenderY> renderY = sgRender.add(new EnumSetting.Builder<RenderY>().name("render-height").defaultValue(RenderY.Auto).build());
    private final Setting<Integer> fixedY = sgRender.add(new IntSetting.Builder().name("fixed-y").defaultValue(64).range(-64, 320).visible(() -> renderY.get() == RenderY.Fixed).build());
    private final Setting<Boolean> fullColumn = sgRender.add(new BoolSetting.Builder().name("full-height-column").defaultValue(false).build());
    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder().name("tracers").defaultValue(true).build());
    private final Setting<Integer> tracerTier = sgRender.add(new IntSetting.Builder().name("tracer-min-tier").defaultValue(2).range(1, 3).visible(tracers::get).build());
    private final Setting<Boolean> showPois = sgRender.add(new BoolSetting.Builder().name("show-tunnels-and-shafts").defaultValue(true).build());

    private final Setting<Boolean> chatAlerts = sgAlerts.add(new BoolSetting.Builder().name("chat-alerts").defaultValue(true).build());
    private final Setting<Integer> alertTier = sgAlerts.add(new IntSetting.Builder().name("alert-min-tier").defaultValue(2).range(1, 3).visible(chatAlerts::get).build());
    private final Setting<Boolean> alertSound = sgAlerts.add(new BoolSetting.Builder().name("alert-sound").defaultValue(true).build());
    private final Setting<Boolean> persist = sgAlerts.add(new BoolSetting.Builder().name("save-results").defaultValue(true).build());

    private static final class Report {
        final int cx, cz;
        boolean scanned;
        int score, tier, alertedTier;
        final int[] counts = new int[K_COUNT];
        int shafts, tunnels, entities, activity, structMask;
        int surfaceEntrances, entranceLitter;
        long ySum; int yN;
        int poiY = Integer.MIN_VALUE;
        String reasons = "";
        final List<BlockPos> blocks = new ArrayList<>();
        final List<BlockPos> pois = new ArrayList<>();
        final List<BlockPos> digTargets = new ArrayList<>();
        Report(int cx, int cz) { this.cx = cx; this.cz = cz; }
    }

    private static final class ChunkScan {
        final int cx, cz, baseX, baseZ, minY, height;
        int yCursor;
        byte[] cls;
        final Report r;
        final int[] sigCounts = new int[8];
        ChunkScan(int cx, int cz, int minY, int height) {
            this.cx = cx; this.cz = cz; this.baseX = cx << 4; this.baseZ = cz << 4;
            this.minY = minY; this.height = height; this.cls = new byte[16 * 16 * height];
            this.r = new Report(cx, cz);
        }
    }

    private final Map<String, Map<Long, Report>> byDim = new HashMap<>();
    private Map<Long, Report> reports = new HashMap<>();
    private final Map<Long, Integer> pending = new LinkedHashMap<>();
    private final Map<Long, ChunkScan> inProgress = new HashMap<>();
    private String currentDim = "";
    private int tick;
    private boolean dirty;

    public BaseHunter() { this("base-hunter", "Flags suspicious chunks and highlights blocks."); }

    public BaseHunter(String name, String description) {
        super(BaseHunterAddon.CATEGORY, name, description);
        for (int k = 0; k < K_COUNT; k++) kindWeight.add(sgWeights.add(new DoubleSetting.Builder().name(KIND_KEYS[k] + "-weight").defaultValue(DEF_WEIGHT[k]).min(0).sliderMax(15).build()));
        wShaft = sgWeights.add(new DoubleSetting.Builder().name("shaft-weight").defaultValue(8).min(0).sliderMax(30).build());
        wTunnel = sgWeights.add(new DoubleSetting.Builder().name("tunnel-weight").defaultValue(8).min(0).sliderMax(30).build());
        wEntity = sgWeights.add(new DoubleSetting.Builder().name("entity-weight").defaultValue(4).min(0).sliderMax(20).build());
        wActivity = sgWeights.add(new DoubleSetting.Builder().name("activity-weight").defaultValue(0.5).min(0).sliderMax(5).build());
    }

    private static Block[] defaultHighlightBlocks() {
        String[] paths = {"chest", "trapped_chest", "barrel", "ender_chest", "spawner", "beacon", "respawn_anchor"};
        List<Block> out = new ArrayList<>();
        for (String p : paths) { Block b = Registries.BLOCK.get(new Identifier("minecraft", p)); if (b != null) out.add(b); }
        return out.toArray(new Block[0]);
    }

    @Override public void onActivate() {
        buildTable(); highlightSet = new HashSet<>(highlightBlocks.get());
        tick = 0; pending.clear(); inProgress.clear(); byDim.clear(); reports = new HashMap<>(); currentDim = "";
        if (mc.world != null && mc.player != null) { loadResults(); switchDimension(); }
    }
    @Override public void onDeactivate() { saveResults(); pending.clear(); inProgress.clear(); byDim.clear(); reports = new HashMap<>(); currentDim = ""; }
    @Override public String getInfoString() { int n = 0; for (Report r : reports.values()) if (r.tier > 0) n++; return String.valueOf(n); }

    @EventHandler private void onGameJoined(GameJoinedEvent event) { byDim.clear(); reports = new HashMap<>(); pending.clear(); inProgress.clear(); currentDim = ""; loadResults(); }
    @EventHandler private void onGameLeft(GameLeftEvent event) { saveResults(); byDim.clear(); reports = new HashMap<>(); pending.clear(); inProgress.clear(); currentDim = ""; }

    private void buildTable() {
        table.clear(); caveAirBlock = Registries.BLOCK.get(new Identifier("minecraft", "cave_air"));
        for (Block b : Registries.BLOCK) {
            Identifier id = Registries.BLOCK.getId(b); if (id == null) continue;
            int packed = classify(id.getPath()); if (packed != 0) table.put(b, packed);
        }
    }

    private static boolean in(String p, String... options) { for (String o : options) if (p.equals(o)) return true; return false; }
    private static int classify(String p) {
        int kind = -1; int sig = 0;
        if (in(p, "chest", "trapped_chest", "barrel", "ender_chest") || p.endsWith("shulker_box")) kind = K_STORAGE;
        else if (in(p, "furnace", "blast_furnace", "smoker", "crafting_table", "smithing_table", "fletching_table", "cartography_table", "loom", "grindstone", "stonecutter", "enchanting_table", "brewing_stand", "lectern", "jukebox") || p.endsWith("anvil") || p.endsWith("_bed")) kind = K_FUNC;
        else if (in(p, "hopper", "dropper", "dispenser", "observer", "piston", "sticky_piston", "comparator", "repeater", "redstone_wire", "redstone_lamp", "note_block", "target", "daylight_detector", "slime_block", "honey_block")) kind = K_REDSTONE;
        else if (in(p, "diamond_block", "emerald_block", "gold_block", "iron_block", "netherite_block", "lapis_block", "redstone_block", "coal_block", "raw_iron_block", "raw_gold_block", "raw_copper_block")) kind = K_VALUABLE;
        else if (in(p, "torch", "wall_torch", "soul_torch", "soul_wall_torch", "lantern", "soul_lantern", "glowstone", "sea_lantern", "jack_o_lantern", "end_rod")) kind = K_LIGHT;
        else if (in(p, "beacon", "tnt", "nether_portal", "respawn_anchor", "lodestone", "conduit", "command_block", "chain_command_block", "repeating_command_block")) kind = K_PLAYER;
        else if (in(p, "cobblestone", "stone_bricks", "smooth_stone", "bricks", "glass", "glass_pane", "quartz_block", "smooth_quartz", "bookshelf", "obsidian", "ladder", "scaffolding") || p.endsWith("_stained_glass") || p.endsWith("_stained_glass_pane") || p.endsWith("_concrete") || p.endsWith("_wool") || p.endsWith("_planks") || p.endsWith("_glazed_terracotta") || (p.startsWith("polished_") && !p.contains("blackstone"))) kind = K_BUILD;
        if (in(p, "bell", "hay_block", "dirt_path", "composter")) sig |= S_VILLAGE;
        if (p.endsWith("rail") || p.equals("cobweb")) sig |= S_MINE;
        if (in(p, "end_portal_frame")) sig |= S_STRONGHOLD | S_STRONG;
        if (p.startsWith("prismarine") || p.equals("sea_lantern") || p.equals("wet_sponge") || p.equals("dark_prismarine")) sig |= S_MONUMENT;
        if (p.contains("nether_brick") || p.contains("blackstone")) sig |= S_NETHER;
        if (p.startsWith("purpur") || p.equals("end_stone_bricks")) sig |= S_END;
        if (in(p, "trial_spawner", "vault")) sig |= S_TRIAL | S_STRONG;
        if (p.equals("tuff_bricks") || p.equals("chiseled_tuff") || p.startsWith("waxed_")) sig |= S_TRIAL;
        if (in(p, "reinforced_deepslate", "sculk_shrieker")) sig |= S_ANCIENT | S_STRONG;
        if (in(p, "deepslate_tiles", "sculk_sensor", "sculk_catalyst")) sig |= S_ANCIENT;
        if (kind < 0 && sig == 0) return 0;
        return (kind + 1) | (sig << 4);
    }

    private boolean isInteresting(BlockState s) { Block b = s.getBlock(); return table.containsKey(b) || highlightSet.contains(b); }
    private static boolean isEntranceLitter(String p) { return p.equals("ladder") || p.equals("scaffolding") || p.equals("torch") || p.equals("wall_torch") || p.equals("soul_torch") || p.equals("soul_wall_torch") || p.equals("lantern") || p.equals("rail") || p.equals("powered_rail") || p.equals("detector_rail") || p.equals("activator_rail"); }

    @EventHandler private void onChunkData(ChunkDataEvent event) {
        if (mc.world == null) return; ChunkPos pos = event.chunk().getPos();
        pending.put(((long)(pos.getStartX() >> 4) << 32) | ((pos.getStartZ() >> 4) & 0xFFFFFFFFL), tick + scanDelay.get());
    }

    @EventHandler private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.world == null || mc.player == null) return;
        BlockPos pos = event.pos; BlockState o = event.oldState, n = event.newState;
        if (o.getBlock() == n.getBlock()) return;
        boolean relevant = isInteresting(o) || isInteresting(n);
        long k = ((long)(pos.getX() >> 4) << 32) | ((pos.getZ() >> 4) & 0xFFFFFFFFL);
        if (relevant) pending.putIfAbsent(k, tick + 40);
        if (trackActivity.get()) {
            if (donutMode.get() && pos.getY() < donutSurfaceAnomalyY.get()) return;
            double dx = pos.getX() + 0.5 - mc.player.getX(), dy = pos.getY() + 0.5 - mc.player.getY(), dz = pos.getZ() + 0.5 - mc.player.getZ();
            if (dx * dx + dy * dy + dz * dz < 144) return;
            boolean placedOrBroke = o.isAir() != n.isAir();
            boolean meaningful = n.isAir() ? o.isSolidBlock(mc.world, pos) || isInteresting(o) : n.isSolidBlock(mc.world, pos) || isInteresting(n);
            if (placedOrBroke && meaningful) { Report r = reports.get(k); if (r != null && r.scanned) { r.activity++; rescore(r); } }
        }
    }

    @EventHandler private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return; tick++;
        if (tick % 20 == 0 && !dimKey().equals(currentDim)) switchDimension();
        if (tick % 100 == 0) sweepUnscanned();
        if (tick % 40 == 0 && trackEntities.get() && !donutMode.get()) refreshEntities();
        if (tick % 200 == 0) cleanup();
        if (tick % 1200 == 0 && dirty) saveResults();
        processQueue();
    }

    private String dimKey() {
        if (mc.world.getRegistryKey() == World.OVERWORLD) return "overworld";
        if (mc.world.getRegistryKey() == World.NETHER) return "nether";
        if (mc.world.getRegistryKey() == World.END) return "end";
        return "other";
    }

    private void switchDimension() { currentDim = dimKey(); reports = byDim.computeIfAbsent(currentDim, d -> new HashMap<>()); pending.clear(); inProgress.clear(); queueNearby(); }

    private void queueNearby() {
        BlockPos ppos = mc.player.getBlockPos(); int pcx = ppos.getX() >> 4, pcz = ppos.getZ() >> 4; int r = scanRadius.get();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            int cx = pcx + dx, cz = pcz + dz;
            if (mc.world.getChunkManager().isChunkLoaded(cx, cz)) pending.putIfAbsent(((long)cx << 32) | (cz & 0xFFFFFFFFL), tick + 1);
        }
    }

    private void sweepUnscanned() {
        BlockPos ppos = mc.player.getBlockPos(); int pcx = ppos.getX() >> 4, pcz = ppos.getZ() >> 4; int r = scanRadius.get();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            int cx = pcx + dx, cz = pcz + dz; long k = ((long)cx << 32) | (cz & 0xFFFFFFFFL); Report rep = reports.get(k);
            if ((rep == null || !rep.scanned) && !pending.containsKey(k) && !inProgress.containsKey(k) && mc.world.getChunkManager().isChunkLoaded(cx, cz)) pending.put(k, tick + 1);
        }
    }

    private void processQueue() {
        if (pending.isEmpty() && inProgress.isEmpty()) return;
        long deadline = System.nanoTime() + (long) (budgetMs.get() * 1_000_000.0);
        BlockPos ppos = mc.player.getBlockPos(); int pcx = ppos.getX() >> 4, pcz = ppos.getZ() >> 4;
        for (ChunkScan scan : new ArrayList<>(inProgress.values())) { continueScan(scan); if (System.nanoTime() >= deadline) return; }
        do {
            long best = 0, bestDist = Long.MAX_VALUE; boolean found = false;
            for (Map.Entry<Long, Integer> e : pending.entrySet()) {
                if (e.getValue() > tick) continue; long k = e.getKey(); long dx = (int) (k >> 32) - pcx, dz = (int) k - pcz; long d = dx * dx + dz * dz;
                if (d < bestDist) { bestDist = d; best = k; found = true; }
            }
            if (!found) return; pending.remove(best);
            int cx = (int) (best >> 32), cz = (int) best;
            ChunkScan scan = new ChunkScan(cx, cz, mc.world.getBottomY(), mc.world.getHeight());
            inProgress.put(best, scan); continueScan(scan);
        } while (System.nanoTime() < deadline && !pending.isEmpty());
    }

    private void continueScan(ChunkScan scan) {
        long deadline = System.nanoTime() + 2_000_000L; BlockPos.Mutable mp = new BlockPos.Mutable();
        while (scan.yCursor < scan.height) {
            int wy = scan.minY + scan.yCursor;
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                mp.set(scan.baseX + x, wy, scan.baseZ + z); BlockState st = mc.world.getBlockState(mp);
                int idx = (scan.yCursor * 16 + z) * 16 + x;
                if (st.isAir()) { scan.cls[idx] = (byte) (st.getBlock() == caveAirBlock ? 2 : 1); continue; }
                scan.cls[idx] = (byte) (st.isSolidBlock(mc.world, mp) ? 0 : 3);
                Block b = st.getBlock(); Integer info = table.get(b);
                if (info != null) {
                    int kind = (info & 0xF) - 1, sig = info >> 4;
                    if (sig != 0) { int add = (sig & S_STRONG) != 0 ? SIG_THRESHOLD : 1; for (int bit = 0; bit < 8; bit++) if ((sig & (1 << bit)) != 0) scan.sigCounts[bit] += add; }
                    if (kind >= 0) {
                        boolean count = true;
                        if (kind == K_LIGHT && isOverworld()) count = wy < 56 && mc.world.getLightLevel(LightType.BLOCK, mp) == 0;
                        if (count) { scan.r.counts[kind]++; if (kind != K_BUILD && kind != K_LIGHT) addEvidence(scan.r, wy); }
                    }
                }
                if (highlightSet.contains(b) && scan.r.blocks.size() < maxBlocksPerChunk.get()) scan.r.blocks.add(new BlockPos(scan.baseX + x, wy, scan.baseZ + z));
            }
            scan.yCursor++;
            if (System.nanoTime() >= deadline) return;
        }
        finishScan(scan);
    }

    private boolean isOverworld() { return mc.world.getRegistryKey() == World.OVERWORLD; }
    
    private void finishScan(ChunkScan scan) {
        long key = ((long)scan.cx << 32) | (scan.cz & 0xFFFFFFFFL); inProgress.remove(key);
        for (int bit = 0; bit < 8; bit++) if (scan.sigCounts[bit] >= SIG_THRESHOLD) scan.r.structMask |= 1 << bit;
        if (detectGeometry.get()) findGeometry(scan, isOverworld());
        if (scan.r.yN > 0) scan.r.poiY = (int) (scan.r.ySum / scan.r.yN); scan.r.scanned = true;
        Report old = reports.get(key); if (old != null) { scan.r.entities = old.entities; scan.r.activity = old.activity; scan.r.alertedTier = old.alertedTier; }
        reports.put(key, scan.r); rescore(scan.r);
    }

    private void cleanup() { reports.entrySet().removeIf(e -> e.getValue().tier == 0 && !mc.world.getChunkManager().isChunkLoaded(e.getValue().cx, e.getValue().cz)); }

    private void refreshEntities() {
        Map<Long, Integer> ec = new HashMap<>();
        for (Entity e : mc.world.getEntities()) {
            Identifier id = Registries.ENTITY_TYPE.getId(e.getType()); if (id == null) continue; String p = id.getPath();
            if (p.equals("item_frame") || p.equals("glow_item_frame") || p.equals("armor_stand") || p.equals("chest_minecart") || p.equals("hopper_minecart")) {
                BlockPos bp = e.getBlockPos(); ec.merge(((long)(bp.getX() >> 4) << 32) | ((bp.getZ() >> 4) & 0xFFFFFFFFL), 1, Integer::sum);
            }
        }
        for (Map.Entry<Long, Integer> e : ec.entrySet()) {
            Report r = reports.get(e.getKey());
            if (r == null) { r = new Report((int) (e.getKey() >> 32), (int) (long) e.getKey()); r.scanned = true; reports.put(e.getKey(), r); }
            if (r.entities != e.getValue()) { r.entities = e.getValue(); rescore(r); }
        }
        for (Map.Entry<Long, Report> e : reports.entrySet()) {
            if (e.getValue().entities > 0 && !ec.containsKey(e.getKey())) { e.getValue().entities = 0; rescore(e.getValue()); }
        }
    }

    private static void addEvidence(Report r, int y) { r.ySum += y; r.yN++; }
    private boolean openCell(byte c) { return c == 1 || c == 3 || (c == 2 && !ignoreCaveAir.get()); }
    private int at(ChunkScan scan, int x, int yy, int z) { return scan.cls[(yy * 16 + z) * 16 + x]; }

    private void findGeometry(ChunkScan scan, boolean overworld) {
        int maxLocalY = scan.height - 4;
        int maxWorldY = overworld ? geometryMaxY.get() : Integer.MAX_VALUE;
        if (!donutMode.get() && overworld) maxLocalY = Math.min(maxLocalY, maxWorldY - scan.minY);
        if (maxLocalY < 2) return;
        int minLen = minTunnel.get();

        for (int yy = 1; yy <= maxLocalY; yy++) {
            for (int z = 1; z <= 14; z++) {
                int run = 0, start = 0;
                for (int x = 0; x <= 16; x++) {
                    boolean ok = x < 16 && tunnelCell(scan, x, yy, z, true);
                    if (ok) { if (run == 0) start = x; run++; }
                    else {
                        if (run >= minLen) { scan.r.tunnels++; addPoi(scan.r, scan.baseX + start, scan.minY + yy, scan.baseZ + z); checkDonutTunnel(scan, start, x - 1, yy, z, true); }
                        run = 0;
                    }
                }
            }
        }
        for (int yy = 1; yy <= maxLocalY; yy++) {
            for (int x = 1; x <= 14; x++) {
                int run = 0, start = 0;
                for (int z = 0; z <= 16; z++) {
                    boolean ok = z < 16 && tunnelCell(scan, x, yy, z, false);
                    if (ok) { if (run == 0) start = z; run++; }
                    else {
                        if (run >= minLen) { scan.r.tunnels++; addPoi(scan.r, scan.baseX + x, scan.minY + yy, scan.baseZ + start); checkDonutTunnel(scan, start, z - 1, yy, x, false); }
                        run = 0;
                    }
                }
            }
        }
        int minDepth = minShaft.get();
        for (int x = 1; x <= 14; x++) {
            for (int z = 1; z <= 14; z++) {
                int run = 0, start = 0;
                for (int yy = 0; yy <= maxLocalY + 1; yy++) {
                    boolean ok = yy <= maxLocalY && openCell((byte) at(scan, x, yy, z)) && at(scan, x + 1, yy, z) == 0 && at(scan, x - 1, yy, z) == 0 && at(scan, x, yy, z + 1) == 0 && at(scan, x, yy, z - 1) == 0;
                    if (ok) { if (run == 0) start = yy; run++; }
                    else {
                        if (run >= minDepth) { scan.r.shafts++; addPoi(scan.r, scan.baseX + x, scan.minY + start, scan.baseZ + z); if (donutMode.get()) checkDonutShaft(scan, x, z, start, start + run - 1); }
                        run = 0;
                    }
                }
            }
        }
    }

    private void checkDonutTunnel(ChunkScan scan, int start, int end, int yy, int z, boolean alongX) {
        if (!donutMode.get()) return;
        int worldY = scan.minY + yy;
        if (worldY < donutSurfaceY.get() - 2) return;
        if (donutRequireOpenTop.get()) {
            int topLocal = Math.min(scan.height - 1, yy + 2);
            boolean open = true;
            for (int t = topLocal; t < scan.height; t++) {
                int b = at(scan, alongX ? start : z, t, alongX ? z : start);
                if (b == 0) { open = false; break; }
            }
            if (!open) return;
        }
        scan.r.surfaceEntrances++;
        int bx = scan.baseX + (alongX ? start : z), bz = scan.baseZ + (alongX ? z : start);
        scan.r.pois.add(new BlockPos(bx, worldY, bz));
        countLitter(scan, bx - scan.baseX, yy, bz - scan.baseZ);
    }

    private void checkDonutShaft(ChunkScan scan, int x, int z, int startLocal, int endLocal) {
        int topWorld = scan.minY + endLocal, botWorld = scan.minY + startLocal;
        int depth = topWorld - botWorld;
        if (depth < donutMinEntranceDepth.get()) return;
        if (topWorld < donutSurfaceY.get() - 2) return;
        if (donutRequireOpenTop.get()) {
            boolean open = false;
            for (int t = endLocal + 1; t < scan.height; t++) {
                if (at(scan, x, t, z) == 0) { open = true; break; }
            }
            if (!open) return;
        }
        scan.r.surfaceEntrances++;
        int wx = scan.baseX + x, wz = scan.baseZ + z;
        scan.r.pois.add(new BlockPos(wx, topWorld, wz));
        scan.r.digTargets.add(new BlockPos(wx, botWorld, wz));
        countLitter(scan, x, (startLocal + endLocal) / 2, z);
    }

    private void countLitter(ChunkScan scan, int x, int yy, int z) {
        if (x < 1 || x > 14 || z < 1 || z > 14) return;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) {
            int lx = x + dx, lz = z + dz, ly = yy + dy;
            if (lx < 0 || lx > 15 || lz < 0 || lz > 15 || ly < 0 || ly >= scan.height) continue;
            BlockPos p = new BlockPos(scan.baseX + lx, scan.minY + ly, scan.baseZ + lz);
            Identifier id = Registries.BLOCK.getId(mc.world.getBlockState(p).getBlock());
            if (id != null && isEntranceLitter(id.getPath())) scan.r.entranceLitter++;
        }
    }

    private boolean tunnelCell(ChunkScan scan, int x, int yy, int z, boolean alongX) {
        byte c0 = (byte) at(scan, x, yy, z), c1 = (byte) at(scan, x, yy + 1, z);
        if (!openCell(c0) || !openCell(c1)) return false;
        if (c0 != 1 && c1 != 1) return false;
        if (at(scan, x, yy - 1, z) != 0 || at(scan, x, yy + 2, z) != 0) return false;
        int solid;
        if (alongX) solid = (at(scan, x, yy, z - 1) == 0 ? 1 : 0) + (at(scan, x, yy, z + 1) == 0 ? 1 : 0) + (at(scan, x, yy + 1, z - 1) == 0 ? 1 : 0) + (at(scan, x, yy + 1, z + 1) == 0 ? 1 : 0);
        else solid = (at(scan, x - 1, yy, z) == 0 ? 1 : 0) + (at(scan, x + 1, yy, z) == 0 ? 1 : 0) + (at(scan, x - 1, yy + 1, z) == 0 ? 1 : 0) + (at(scan, x + 1, yy + 1, z) == 0 ? 1 : 0);
        return solid >= 3;
    }

    private void addPoi(Report r, int x, int y, int z) { if (r.pois.size() < 12) r.pois.add(new BlockPos(x, y, z)); addEvidence(r, y); }
    private int tierOf(int score) { if (score >= highThreshold.get()) return 3; if (score >= mediumThreshold.get()) return 2; if (score >= lowThreshold.get()) return 1; return 0; }

    private void rescore(Report r) {
        if (!r.scanned) return;
        double mult = r.structMask != 0 ? structureDiscount.get() : 1.0;
        double total = 0;
        StringBuilder why = new StringBuilder();
        for (int k = 0; k < K_COUNT; k++) {
            if (r.counts[k] == 0) continue;
            double v = Math.min(CAP[k], r.counts[k] * kindWeight.get(k).get());
            if (AMBIGUOUS[k]) v *= mult;
            total += v;
            if (v >= 1) append(why, r.counts[k] + " " + KIND_NAMES[k]);
        }
        double s = Math.min(24, r.shafts * wShaft.get());
        total += s; if (r.shafts > 0 && s > 0) append(why, r.shafts + " shaft");
        s = Math.min(32, r.tunnels * wTunnel.get());
        total += s; if (r.tunnels > 0 && s > 0) append(why, r.tunnels + " tunnel");
        s = Math.min(24, r.entities * wEntity.get());
        total += s; if (r.entities > 0 && s > 0) append(why, r.entities + " frames/stands/carts");
        s = Math.min(15, r.activity * wActivity.get());
        total += s; if (r.activity > 0 && s > 0) append(why, r.activity + " block changes");

        if (donutMode.get()) {
            if (r.surfaceEntrances > 0) { double e = Math.min(60, r.surfaceEntrances * donutEntranceBonus.get()); total += e; append(why, r.surfaceEntrances + " surface entrance"); }
            if (r.entranceLitter > 0) { double e = Math.min(30, r.entranceLitter * donutLitterBonus.get()); total += e; append(why, r.entranceLitter + " entrance litter"); }
        }
        if (r.structMask != 0) append(why, "natural structure nearby: " + structName(r.structMask));

        int old = r.tier;
        r.score = (int) Math.round(total);
        r.tier = tierOf(r.score);
        r.reasons = why.toString();
        if (r.tier != old) dirty = true;
        if (r.tier > r.alertedTier) { r.alertedTier = r.tier; if (r.tier >= alertTier.get() && chatAlerts.get()) alert(r); }
    }

    private static void append(StringBuilder sb, String s) { if (sb.length() > 0) sb.append(", "); sb.append(s); }
    private static String structName(int mask) {
        StringBuilder sb = new StringBuilder();
        if ((mask & S_VILLAGE) != 0) append(sb, "village");
        if ((mask & S_MINE) != 0) append(sb, "mineshaft");
        if ((mask & S_STRONGHOLD) != 0) append(sb, "stronghold");
        if ((mask & S_MONUMENT) != 0) append(sb, "monument");
        if ((mask & S_NETHER) != 0) append(sb, "nether structure");
        if ((mask & S_END) != 0) append(sb, "end city");
        if ((mask & S_TRIAL) != 0) append(sb, "trial chamber");
        if ((mask & S_ANCIENT) != 0) append(sb, "ancient city");
        return sb.toString();
    }

    private void alert(Report r) {
        String[] names = {"", "low", "medium", "HIGH"};
        int x = (r.cx << 4) + 8, z = (r.cz << 4) + 8;
        info("Suspicious chunk (" + names[r.tier] + ") at " + x + ", " + z + " score " + r.score + ": " + r.reasons);
        if (alertSound.get() && mc.player != null) {
            SoundEvent snd = Registries.SOUND_EVENT.get(new Identifier("minecraft", "entity.experience_orb.pickup"));
            if (snd != null) mc.player.playSound(snd, 1.0f, 1.0f);
        }
    }

    private Color getThemeColor(int tier) {
        BiomeTheme theme = uiTheme.get();
        if (theme == BiomeTheme.Plains) {
            if (tier >= 3) return new Color(255, 30, 30, 220);
            if (tier == 2) return new Color(255, 140, 0, 200);
            return new Color(255, 225, 0, 200);
        } else if (theme == BiomeTheme.Desert) {
            if (tier >= 3) return new Color(200, 150, 50, 220);
            if (tier == 2) return new Color(240, 220, 150, 200);
            return new Color(100, 150, 200, 200);
        } else if (theme == BiomeTheme.Nether) {
            if (tier >= 3) return new Color(200, 20, 20, 220);
            if (tier == 2) return new Color(250, 80, 0, 200);
            return new Color(100, 50, 100, 200);
        } else if (theme == BiomeTheme.End) {
            if (tier >= 3) return new Color(200, 200, 255, 220);
            if (tier == 2) return new Color(150, 200, 150, 200);
            return new Color(100, 50, 200, 200);
        } else if (theme == BiomeTheme.Deep_Dark) {
            if (tier >= 3) return new Color(100, 255, 200, 220);
            if (tier == 2) return new Color(30, 50, 80, 200);
            return new Color(50, 60, 70, 200);
        } else if (theme == BiomeTheme.Cherry_Grove) {
            if (tier >= 3) return new Color(255, 180, 200, 220);
            if (tier == 2) return new Color(200, 255, 180, 200);
            return new Color(180, 220, 255, 200);
        }
        return new Color(255, 255, 255, 200);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;
        BlockPos ppos = mc.player.getBlockPos();
        int pcx = ppos.getX() >> 4, pcz = ppos.getZ() >> 4;
        int rad = renderRadius.get();
        long rangeSq = (long) blockRange.get() * blockRange.get();
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        Vec3d view = mc.player.getRotationVec(event.tickDelta);
        Vec3d pos = mc.player.getEyePos();
        double sx = pos.x + view.x, sy = mc.player.getEyeHeight() + view.y, sz = pos.z + view.z;
        int minY = mc.world.getBottomY(), maxY = minY + mc.world.getHeight();

        for (Report r : reports.values()) {
            if (!r.blocks.isEmpty() && Math.max(Math.abs(r.cx - pcx), Math.abs(r.cz - pcz)) * 16 <= blockRange.get() + 16) {
                for (BlockPos bp : r.blocks) {
                    double dx = bp.getX() + 0.5 - px, dy = bp.getY() + 0.5 - py, dz = bp.getZ() + 0.5 - pz;
                    if (dx * dx + dy * dy + dz * dz > rangeSq) continue;
                    event.renderer.box(bp, blockSide.get(), blockLine.get(), blockShape.get(), 0);
                }
            }
            if (donutMode.get() && r.digTargets != null) {
                for (BlockPos bp : r.digTargets) {
                    event.renderer.box(bp, new Color(0, 255, 120, 220), new Color(0, 255, 120, 220), ShapeMode.Lines, 0);
                }
            }
            if (r.tier < minRenderTier.get()) continue;
            if (Math.max(Math.abs(r.cx - pcx), Math.abs(r.cz - pcz)) > rad) continue;

            Color c = getThemeColor(r.tier);
            Color side = new Color(c.r, c.g, c.b, Math.min(c.a, 45));
            double x1 = r.cx * 16, z1 = r.cz * 16, x2 = x1 + 16, z2 = z1 + 16;

            if (fullColumn.get()) event.renderer.box(x1, minY, z1, x2, maxY, z2, side, c, chunkShape.get(), 0);
            else {
                double y = switch (renderY.get()) {
                    case Player -> Math.floor(py);
                    case Fixed -> fixedY.get();
                    default -> r.poiY != Integer.MIN_VALUE ? r.poiY : Math.floor(py);
                };
                event.renderer.box(x1, y, z1, x2, y + 0.15, z2, side, c, chunkShape.get(), 0);
            }

            if (tracers.get() && r.tier >= tracerTier.get()) {
                double ty = r.poiY != Integer.MIN_VALUE ? r.poiY : py;
                event.renderer.line(sx, sy, sz, x1 + 8, ty, z1 + 8, c);
            }
            if (showPois.get()) for (BlockPos bp : r.pois) event.renderer.box(bp, c, c, ShapeMode.Lines, 0);
        }
    }

    private File storeFile() {
        File dir = new File(MeteorClient.FOLDER, "basehunter");
        return new File(dir, Utils.getFileWorldName() + ".json");
    }

    private void saveResults() {
        if (!persist.get() || byDim.isEmpty()) return;
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Map<Long, Report>> d : byDim.entrySet()) {
                JsonArray arr = new JsonArray();
                for (Report r : d.getValue().values()) {
                    if (r.tier < 1) continue;
                    JsonObject o = new JsonObject();
                    o.addProperty("x", r.cx);
                    o.addProperty("z", r.cz);
                    o.addProperty("y", r.poiY == Integer.MIN_VALUE ? 64 : r.poiY);
                    o.addProperty("score", r.score);
                    o.addProperty("reasons", r.reasons);
                    arr.add(o);
                }
                root.add(d.getKey(), arr);
            }
            File f = storeFile();
            f.getParentFile().mkdirs();
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (Writer w = new FileWriter(f)) {
                gson.toJson(root, w);
            }
            dirty = false;
        } catch (Exception e) {
            error("Could not save results: " + e.getMessage());
        }
    }

    private void loadResults() {
        if (!persist.get()) return;
        File f = storeFile();
        if (!f.exists()) return;
        try (Reader rd = new FileReader(f)) {
            JsonObject root = JsonParser.parseReader(rd).getAsJsonObject();
            for (Map.Entry<String, JsonElement> d : root.entrySet()) {
                Map<Long, Report> map = byDim.computeIfAbsent(d.getKey(), k -> new HashMap<>());
                for (JsonElement el : d.getValue().getAsJsonArray()) {
                    JsonObject o = el.getAsJsonObject();
                    Report r = new Report(o.get("x").getAsInt(), o.get("z").getAsInt());
                    r.poiY = o.get("y").getAsInt();
                    r.score = o.get("score").getAsInt();
                    r.reasons = o.get("reasons").getAsString();
                    r.tier = tierOf(r.score);
                    r.alertedTier = r.tier;
                    map.put(key(r.cx, r.cz), r);
                }
            }
        } catch (Exception e) {
            error("Could not load saved results: " + e.getMessage());
        }
    }

    private void exportResults() {
        try {
            List<Report> all = new ArrayList<>();
            for (Report r : reports.values()) if (r.tier > 0) all.add(r);
            all.sort(Comparator.comparingInt((Report r) -> r.score).reversed());
            File f = new File(new File(MeteorClient.FOLDER, "basehunter"), "export-" + Utils.getFileWorldName() + "-" + currentDim + ".txt");
            f.getParentFile().mkdirs();
            try (Writer w = new FileWriter(f)) {
                for (Report r : all) {
                    w.write("score " + r.score + "  x " + ((r.cx << 4) + 8) + "  z " + ((r.cz << 4) + 8) + "  y " + (r.poiY == Integer.MIN_VALUE ? "?" : String.valueOf(r.poiY)) + "  " + r.reasons + "\n");
                }
            }
            info("Exported " + all.size() + " chunks to " + f.getName());
        } catch (Exception e) {
            error("Export failed: " + e.getMessage());
        }
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WHorizontalList list = theme.horizontalList();
        WButton rescan = list.add(theme.button("Rescan")).widget();
        rescan.action = this::rescanAll;
        WButton clear = list.add(theme.button("Clear results")).widget();
        clear.action = this::clearResults;
        WButton export = list.add(theme.button("Export list")).widget();
        export.action = this::exportResults;
        return list;
    }

    private void rescanAll() {
        if (mc.world == null || mc.player == null) return;
        pending.clear(); inProgress.clear(); queueNearby();
        info("Rescanning nearby chunks.");
    }

    private void clearResults() {
        reports.clear(); byDim.clear(); byDim.put(currentDim, reports);
        pending.clear(); inProgress.clear(); dirty = true;
        info("Cleared BaseHunter results.");
        if (mc.world != null && mc.player != null) queueNearby();
    }
    
    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}