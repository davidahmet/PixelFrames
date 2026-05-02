package me.davidahmet.imagemaps;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PixelFrames for Paper 26.1.2
 * Author: davidahmet
 *
 * Commands:
 * /map <image> [WxH]             Creates a persistent image-map grid. Example: /map cat 2x2
 * /map gui                       Opens a GUI selector for images found under plugins/PixelFrames/images
 * /map place [buildName|last]     Places the last/saved build as item frames on the block face you look at
 * /map url <url> [name] [WxH]     Downloads an image/GIF and creates maps
 * /map save <name>                Saves your last generated grid under a schematic-like build name
 * /map load <name>                Gives you a saved grid again
 * /map dither <on|off>            Toggles Floyd-Steinberg dithering for your future renders
 * /map reload                     Reloads image index and map build cache
 */
@SuppressWarnings({"deprecation", "removal"})
public final class PixelFrames extends JavaPlugin implements Listener, TabExecutor {
    private File imagesDir, cacheDir, downloadsDir, buildsFile;
    private FileConfiguration builds;
    private final Map<String, ImageEntry> imageIndex = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, Build> buildIndex = new ConcurrentHashMap<>();
    private final Map<UUID, Build> lastBuild = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> ditherUsers = new ConcurrentHashMap<>();
    private NamespacedKey buildKey;
    private static final int MAP = 128;
    private static final String GUI_TITLE = ChatColor.DARK_GREEN + "PixelFrames";
    private int defaultWidth = 1, defaultHeight = 1, maxGridWidth = 8, maxGridHeight = 8;
    private boolean defaultDithering = true, allowUrlDownloads = true, allowGifAnimation = true;
    private int maxImageSizeMb = 15, maxGifFrames = 60, connectTimeoutMs = 8000, readTimeoutMs = 15000, placementReach = 8;
    private String prefix = "§d[PixelFrames] §r";

    @Override public void onEnable() {
        buildKey = new NamespacedKey(this, "build");
        imagesDir = new File(getDataFolder(), "images");
        cacheDir = new File(getDataFolder(), "cache");
        downloadsDir = new File(getDataFolder(), "downloads");
        buildsFile = new File(getDataFolder(), "builds.yml");
        imagesDir.mkdirs(); cacheDir.mkdirs(); downloadsDir.mkdirs();
        saveDefaultConfig();
        loadSettings();
        if (!buildsFile.exists()) try { buildsFile.createNewFile(); } catch (IOException ignored) {}
        builds = YamlConfiguration.loadConfiguration(buildsFile);
        Objects.requireNonNull(getCommand("map")).setExecutor(this);
        Objects.requireNonNull(getCommand("map")).setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        rescanImages();
        loadBuildsAndRenderers();
        getLogger().info("PixelFrames enabled for davidahmet. Indexed " + imageIndex.size() + " image(s), loaded " + buildIndex.size() + " build(s).");
    }

    @Override public void onDisable() { saveBuilds(); }

    private void loadSettings() {
        reloadConfig();
        defaultWidth = Math.max(1, getConfig().getInt("default-width", 1));
        defaultHeight = Math.max(1, getConfig().getInt("default-height", 1));
        maxGridWidth = Math.max(1, getConfig().getInt("max-grid-width", 8));
        maxGridHeight = Math.max(1, getConfig().getInt("max-grid-height", 8));
        defaultDithering = getConfig().getBoolean("default-dithering", true);
        allowUrlDownloads = getConfig().getBoolean("allow-url-downloads", true);
        allowGifAnimation = getConfig().getBoolean("allow-gif-animation", true);
        maxImageSizeMb = Math.max(1, getConfig().getInt("max-image-size-mb", 15));
        maxGifFrames = Math.max(1, getConfig().getInt("max-gif-frames", 60));
        connectTimeoutMs = Math.max(1000, getConfig().getInt("url-connect-timeout-ms", 8000));
        readTimeoutMs = Math.max(1000, getConfig().getInt("url-read-timeout-ms", 15000));
        placementReach = Math.max(1, getConfig().getInt("placement-reach", 8));
        prefix = ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages-prefix", "&d[PixelFrames]&r "));
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (args.length == 0) { help(p); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "gui" -> { if (deny(p, "pixelframes.gui")) return true; openGui(p, 0); }
                case "place" -> { if (deny(p, "pixelframes.place")) return true; placeCommand(p, args.length > 1 ? args[1] : "last"); }
                case "save" -> { if (deny(p, "pixelframes.save")) return true; if (args.length < 2) msg(p, "Usage: /map save <name>"); else saveAlias(p, args[1]); }
                case "load" -> { if (deny(p, "pixelframes.load")) return true; if (args.length < 2) msg(p, "Usage: /map load <name>"); else loadAlias(p, args[1]); }
                case "dither" -> { if (deny(p, "pixelframes.dither")) return true; toggleDither(p, args.length > 1 ? args[1] : "toggle"); }
                case "reload" -> { if (deny(p, "pixelframes.admin")) return true; loadSettings(); rescanImages(); loadBuildsAndRenderers(); msg(p, "Reloaded config. Indexed " + imageIndex.size() + " images."); }
                case "url" -> { if (deny(p, "pixelframes.url")) return true; urlCommand(p, args); }
                default -> { if (deny(p, "pixelframes.use")) return true; createFromLocal(p, args[0], firstGridArg(args, 1), ditherOverride(args, 1)); }
            }
        } catch (Exception ex) {
            getLogger().warning("Command failed: " + ex.getMessage());
            ex.printStackTrace();
            msg(p, ChatColor.RED + "Error: " + ex.getMessage());
        }
        return true;
    }

    private void help(Player p) {
        p.sendMessage(ChatColor.GOLD + "PixelFrames commands:");
        p.sendMessage(ChatColor.YELLOW + "/map <filename> [WxH] [dither|nodither]" + ChatColor.GRAY + " - create maps, e.g. /map cat 2x2");
        p.sendMessage(ChatColor.YELLOW + "/map gui" + ChatColor.GRAY + " - browse image files");
        p.sendMessage(ChatColor.YELLOW + "/map place [last|name]" + ChatColor.GRAY + " - auto-place item frames on the block face you look at");
        p.sendMessage(ChatColor.YELLOW + "/map url <url> [name] [WxH]" + ChatColor.GRAY + " - download image/GIF and map it");
        p.sendMessage(ChatColor.YELLOW + "/map save <name> /map load <name> /map dither on|off");
    }

    private void createFromLocal(Player p, String name, String gridText) { createFromLocal(p, name, gridText, null); }

    private void createFromLocal(Player p, String name, String gridText, Boolean ditherOverride) {
        ImageEntry e = findImage(name);
        if (e == null) { msg(p, "Image not found under plugins/PixelFrames/images: " + name); return; }
        if (!withinSizeLimit(e.file)) { msg(p, ChatColor.RED + "Image is over max-image-size-mb (" + maxImageSizeMb + "MB)."); return; }
        int[] wh = parseGrid(gridText);
        boolean dither = ditherOverride != null ? ditherOverride : userDither(p);
        msg(p, "Processing " + e.key + " as " + wh[0] + "x" + wh[1] + " asynchronously with dithering " + (dither ? "ON" : "OFF") + "...");
        processAsync(p, e.file, e.key, wh[0], wh[1], dither);
    }

    private void urlCommand(Player p, String[] args) throws Exception {
        if (!allowUrlDownloads) { msg(p, ChatColor.RED + "URL downloads are disabled in config.yml."); return; }
        if (args.length < 2) { msg(p, "Usage: /map url <url> [name] [WxH] [dither|nodither]"); return; }
        String url = args[1];
        String name = args.length > 2 && !isGrid(args[2]) && !isDitherToken(args[2]) ? safeName(args[2]) : "downloaded_" + System.currentTimeMillis();
        String grid = firstGridArg(args, 2);
        Boolean ditherOverride = ditherOverride(args, 2);
        boolean dither = ditherOverride != null ? ditherOverride : userDither(p);
        int[] wh = parseGrid(grid);
        msg(p, "Downloading and processing image asynchronously with dithering " + (dither ? "ON" : "OFF") + "...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                File out = download(url, name);
                Bukkit.getScheduler().runTask(this, () -> msg(p, "Downloaded to downloads/" + out.getName()));
                processAsync(p, out, name, wh[0], wh[1], dither);
            } catch (Exception ex) { Bukkit.getScheduler().runTask(this, () -> msg(p, ChatColor.RED + "Download failed: " + ex.getMessage())); }
        });
    }

    private File download(String url, String name) throws Exception {
        URI uri = URI.create(url);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        String ext = path.endsWith(".gif") ? ".gif" : path.endsWith(".jpg") || path.endsWith(".jpeg") ? ".jpg" : ".png";
        File out = new File(downloadsDir, safeName(name) + ext);
        HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
        c.setConnectTimeout(connectTimeoutMs); c.setReadTimeout(readTimeoutMs); c.setRequestProperty("User-Agent", "PixelFrames/1.4");
        long maxBytes = maxImageSizeMb * 1024L * 1024L;
        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192]; int n; long total = 0;
            while ((n = in.read(buf)) != -1) { total += n; if (total > maxBytes) throw new IOException("Download exceeds max-image-size-mb"); os.write(buf, 0, n); }
        }
        return out;
    }

    private void processAsync(Player p, File imageFile, String displayName, int width, int height, boolean dither) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String key = buildKey(imageFile, width, height, dither);
                Build existing = buildIndex.get(key);
                if (existing != null) {
                    Bukkit.getScheduler().runTask(this, () -> { giveBuild(p, existing); lastBuild.put(p.getUniqueId(), existing); msg(p, "Loaded cached maps: " + existing.name); });
                    return;
                }
                List<BufferedImage> frames = readFrames(imageFile);
                if (frames.isEmpty()) throw new IOException("Unsupported or unreadable image.");
                Build build = new Build(key, displayName, imageFile.getAbsolutePath(), width, height, dither, frames.size() > 1, new ArrayList<>());
                int totalW = width * MAP, totalH = height * MAP;
                List<List<byte[]>> allFrameTiles = new ArrayList<>();
                for (BufferedImage frame : frames) allFrameTiles.add(sliceToBytes(scale(frame, totalW, totalH), width, height, dither));
                Bukkit.getScheduler().runTask(this, () -> {
                    try {
                        int tiles = width * height;
                        for (int i = 0; i < tiles; i++) {
                            MapView view = Bukkit.createMap(p.getWorld());
                            view.getRenderers().forEach(view::removeRenderer);
                            int mapId = view.getId();
                            List<byte[]> tileFrames = new ArrayList<>();
                            for (List<byte[]> frameTiles : allFrameTiles) tileFrames.add(frameTiles.get(i));
                            saveTileFrames(mapId, tileFrames);
                            view.addRenderer(tileFrames.size() > 1 ? new AnimatedRenderer(tileFrames) : new StaticRenderer(tileFrames.getFirst()));
                            build.mapIds.add(mapId);
                        }
                        buildIndex.put(build.key, build);
                        persistBuild(build);
                        giveBuild(p, build);
                        lastBuild.put(p.getUniqueId(), build);
                        msg(p, "Created " + width + "x" + height + " map build: " + build.name + (build.animated ? " (animated)" : ""));
                    } catch (Exception ex) { msg(p, ChatColor.RED + "Map creation failed: " + ex.getMessage()); }
                });
            } catch (Exception ex) { Bukkit.getScheduler().runTask(this, () -> msg(p, ChatColor.RED + "Processing failed: " + ex.getMessage())); }
        });
    }

    private void giveBuild(Player p, Build b) {
        for (int row = 0; row < b.height; row++) for (int col = 0; col < b.width; col++) {
            int mapId = b.mapIds.get(row * b.width + col);
            ItemStack item = new ItemStack(Material.FILLED_MAP);
            MapMeta meta = (MapMeta) item.getItemMeta();
            MapView view = Bukkit.getMap(mapId);
            if (view != null) meta.setMapView(view);
            meta.setDisplayName(ChatColor.GREEN + b.name + ChatColor.GRAY + " [" + (col + 1) + "," + (row + 1) + "]");
            meta.getPersistentDataContainer().set(buildKey, PersistentDataType.STRING, b.key);
            item.setItemMeta(meta);
            HashMap<Integer, ItemStack> left = p.getInventory().addItem(item);
            left.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
        }
    }

    private void placeCommand(Player p, String name) {
        Build b = name.equalsIgnoreCase("last") ? lastBuild.get(p.getUniqueId()) : findBuild(name);
        if (b == null) { msg(p, "No build found. Use /map <image> 2x2 or /map save <name> first."); return; }
        RayTraceResult rt = p.rayTraceBlocks(placementReach);
        if (rt == null || rt.getHitBlock() == null || rt.getHitBlockFace() == null) { msg(p, "Look at a flat wall/floor/ceiling within " + placementReach + " blocks."); return; }
        Block block = rt.getHitBlock(); BlockFace face = rt.getHitBlockFace();
        preview(p, block.getLocation(), face, b.width, b.height);
        if (!canPlaceFrames(block, face, b)) { msg(p, ChatColor.RED + "Not enough clear space for " + b.width + "x" + b.height + " item frames."); return; }
        for (int row = 0; row < b.height; row++) for (int col = 0; col < b.width; col++) {
            Location loc = frameLocation(block, face, col, row, b.width, b.height);
            ItemFrame frame = (ItemFrame) p.getWorld().spawnEntity(loc, EntityType.ITEM_FRAME);
            frame.setFacingDirection(face, true);
            frame.setItem(mapItem(b, row * b.width + col));
            frame.setFixed(true);
        }
        msg(p, "Placed build: " + b.name);
    }

    private boolean canPlaceFrames(Block origin, BlockFace face, Build b) {
        World w = origin.getWorld();
        for (int row = 0; row < b.height; row++) for (int col = 0; col < b.width; col++) {
            Location l = frameLocation(origin, face, col, row, b.width, b.height);
            if (!w.getNearbyEntities(l, .45, .45, .45, e -> e instanceof ItemFrame).isEmpty()) return false;
        }
        return true;
    }

    private ItemStack mapItem(Build b, int idx) {
        ItemStack item = new ItemStack(Material.FILLED_MAP); MapMeta meta = (MapMeta) item.getItemMeta();
        MapView view = Bukkit.getMap(b.mapIds.get(idx)); if (view != null) meta.setMapView(view);
        meta.setDisplayName(ChatColor.GREEN + b.name); meta.getPersistentDataContainer().set(buildKey, PersistentDataType.STRING, b.key); item.setItemMeta(meta); return item;
    }

    private Location frameLocation(Block origin, BlockFace face, int col, int row, int width, int height) {
        Location base = origin.getRelative(face).getLocation().add(.5, .5, .5);
        // Horizontal axis and vertical axis for the selected face. Row 0 is top.
        if (face == BlockFace.NORTH) return base.add(width - 1 - col, height - 1 - row, 0);
        if (face == BlockFace.SOUTH) return base.add(col, height - 1 - row, 0);
        if (face == BlockFace.EAST) return base.add(0, height - 1 - row, width - 1 - col);
        if (face == BlockFace.WEST) return base.add(0, height - 1 - row, col);
        if (face == BlockFace.UP) return base.add(col, 0, row);
        if (face == BlockFace.DOWN) return base.add(col, 0, height - 1 - row);
        return base;
    }

    private void preview(Player p, Location origin, BlockFace face, int width, int height) {
        new BukkitRunnable() { int t = 0; public void run() {
            if (t++ > 30) { cancel(); return; }
            for (int x = 0; x <= width; x++) for (int y = 0; y <= height; y++) if (x == 0 || y == 0 || x == width || y == height) {
                Location l = origin.clone().add(.5, .5, .5).add(face.getDirection().multiply(1.05));
                if (face == BlockFace.NORTH || face == BlockFace.SOUTH) l.add(x - .5, y - .5, 0);
                else if (face == BlockFace.EAST || face == BlockFace.WEST) l.add(0, y - .5, x - .5);
                else l.add(x - .5, 0, y - .5);
                p.spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, l, 1, 0, 0, 0, 0);
            }
        }}.runTaskTimer(this, 0L, 2L);
    }

    private void openGui(Player p, int page) {
        List<ImageEntry> entries = new ArrayList<>(imageIndex.values());
        Inventory inv = Bukkit.createInventory(null, 54, GUI_TITLE + " #" + (page + 1));
        int start = page * 45;
        for (int i = 0; i < 45 && start + i < entries.size(); i++) {
            ImageEntry e = entries.get(start + i);
            ItemStack it = new ItemStack(e.animated ? Material.FILLED_MAP : Material.PAPER);
            ItemMeta m = it.getItemMeta();
            m.setDisplayName(ChatColor.GREEN + e.key);
            m.setLore(List.of(ChatColor.GRAY + e.file.getName(), ChatColor.YELLOW + "Click: create 1x1", ChatColor.YELLOW + "Shift-click: create 2x2"));
            it.setItemMeta(m); inv.setItem(i, it);
        }
        ItemStack close = named(Material.BARRIER, ChatColor.RED + "Close"); inv.setItem(49, close);
        if (page > 0) inv.setItem(45, named(Material.ARROW, ChatColor.YELLOW + "Previous"));
        if (start + 45 < entries.size()) inv.setItem(53, named(Material.ARROW, ChatColor.YELLOW + "Next"));
        p.openInventory(inv);
    }

    @EventHandler public void onGui(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!e.getView().getTitle().startsWith(GUI_TITLE)) return;
        e.setCancelled(true);
        ItemStack item = e.getCurrentItem(); if (item == null || !item.hasItemMeta()) return;
        String title = ChatColor.stripColor(item.getItemMeta().getDisplayName());
        int page = 0; try { page = Integer.parseInt(e.getView().getTitle().replace(GUI_TITLE + " #", "")) - 1; } catch (Exception ignored) {}
        if (title.equalsIgnoreCase("Close")) { p.closeInventory(); return; }
        if (title.equalsIgnoreCase("Previous")) { openGui(p, Math.max(0, page - 1)); return; }
        if (title.equalsIgnoreCase("Next")) { openGui(p, page + 1); return; }
        p.closeInventory(); createFromLocal(p, title, e.isShiftClick() ? "2x2" : defaultGrid());
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { ditherUsers.remove(e.getPlayer().getUniqueId()); }

    private void toggleDither(Player p, String v) {
        boolean on = v.equalsIgnoreCase("toggle") ? !userDither(p) : v.equalsIgnoreCase("on") || v.equalsIgnoreCase("true");
        ditherUsers.put(p.getUniqueId(), on);
        msg(p, "Floyd-Steinberg dithering: " + (on ? "ON" : "OFF"));
    }

    private void saveAlias(Player p, String alias) { Build b = lastBuild.get(p.getUniqueId()); if (b == null) { msg(p, "No last build to save."); return; } builds.set("aliases." + safeName(alias), b.key); saveBuilds(); msg(p, "Saved alias " + alias + " -> " + b.name); }
    private void loadAlias(Player p, String alias) { Build b = findBuild(alias); if (b == null) { msg(p, "Saved build not found: " + alias); return; } giveBuild(p, b); lastBuild.put(p.getUniqueId(), b); msg(p, "Loaded build: " + b.name); }
    private Build findBuild(String name) { String k = builds.getString("aliases." + safeName(name)); if (k != null) return buildIndex.get(k); return buildIndex.values().stream().filter(b -> b.name.equalsIgnoreCase(name) || b.key.equalsIgnoreCase(name)).findFirst().orElse(null); }

    private void rescanImages() {
        imageIndex.clear(); scan(imagesDir); scan(downloadsDir);
    }
    private void scan(File dir) {
        File[] files = dir.listFiles(); if (files == null) return;
        for (File f : files) if (f.isDirectory()) scan(f); else if (isImage(f)) {
            String rel = imagesDir.toPath().relativize(f.toPath().startsWith(imagesDir.toPath()) ? f.toPath() : f.toPath()).toString();
            String key = stripExt(f.getName()); imageIndex.putIfAbsent(key, new ImageEntry(key, f, f.getName().toLowerCase(Locale.ROOT).endsWith(".gif")));
            imageIndex.putIfAbsent(rel.replace(File.separatorChar, '/'), new ImageEntry(rel.replace(File.separatorChar, '/'), f, f.getName().toLowerCase(Locale.ROOT).endsWith(".gif")));
        }
    }
    private boolean isImage(File f) { String n = f.getName().toLowerCase(Locale.ROOT); return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif") || n.endsWith(".bmp"); }
    private ImageEntry findImage(String name) { ImageEntry e = imageIndex.get(name); if (e != null) return e; String stripped = stripExt(name); return imageIndex.get(stripped); }

    private void loadBuildsAndRenderers() {
        buildIndex.clear(); builds = YamlConfiguration.loadConfiguration(buildsFile);
        if (!builds.isConfigurationSection("builds")) return;
        for (String key : Objects.requireNonNull(builds.getConfigurationSection("builds")).getKeys(false)) {
            String path = "builds." + key + ".";
            Build b = new Build(key, builds.getString(path + "name", key), builds.getString(path + "source", ""), builds.getInt(path + "width"), builds.getInt(path + "height"), builds.getBoolean(path + "dither"), builds.getBoolean(path + "animated"), builds.getIntegerList(path + "maps"));
            buildIndex.put(key, b);
            for (int id : b.mapIds) {
                MapView v = Bukkit.getMap(id); if (v == null) continue;
                v.getRenderers().forEach(v::removeRenderer);
                List<byte[]> frames = loadTileFrames(id);
                if (!frames.isEmpty()) v.addRenderer(frames.size() > 1 ? new AnimatedRenderer(frames) : new StaticRenderer(frames.getFirst()));
            }
        }
    }
    private void persistBuild(Build b) { builds.set("builds." + b.key + ".name", b.name); builds.set("builds." + b.key + ".source", b.source); builds.set("builds." + b.key + ".width", b.width); builds.set("builds." + b.key + ".height", b.height); builds.set("builds." + b.key + ".dither", b.dither); builds.set("builds." + b.key + ".animated", b.animated); builds.set("builds." + b.key + ".maps", b.mapIds); saveBuilds(); }
    private void saveBuilds() { try { builds.save(buildsFile); } catch (IOException e) { getLogger().warning("Could not save builds.yml: " + e.getMessage()); } }

    private void saveTileFrames(int mapId, List<byte[]> frames) throws IOException { File f = new File(cacheDir, mapId + ".bin"); try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) { out.writeInt(frames.size()); for (byte[] data : frames) { out.writeInt(data.length); out.write(data); } } }
    private List<byte[]> loadTileFrames(int mapId) { File f = new File(cacheDir, mapId + ".bin"); List<byte[]> list = new ArrayList<>(); if (!f.exists()) return list; try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) { int frames = in.readInt(); for (int i = 0; i < frames; i++) { int len = in.readInt(); byte[] b = in.readNBytes(len); if (b.length == len) list.add(b); } } catch (Exception ignored) {} return list; }

    private List<BufferedImage> readFrames(File f) throws IOException {
        if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".gif")) return List.of(ImageIO.read(f));
        if (!allowGifAnimation) return List.of(ImageIO.read(f));
        List<BufferedImage> frames = new ArrayList<>();
        try (ImageInputStream stream = ImageIO.createImageInputStream(f)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream); if (!readers.hasNext()) return frames;
            ImageReader reader = readers.next(); reader.setInput(stream);
            int n = Math.min(reader.getNumImages(true), maxGifFrames); // config cap to protect performance
            for (int i = 0; i < n; i++) frames.add(reader.read(i));
            reader.dispose();
        }
        return frames;
    }
    private BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB); Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, Color.WHITE, null); g.dispose(); return out;
    }
    private List<byte[]> sliceToBytes(BufferedImage img, int wMaps, int hMaps, boolean dither) {
        List<byte[]> out = new ArrayList<>();
        if (dither) img = dithered(img);
        for (int my = 0; my < hMaps; my++) for (int mx = 0; mx < wMaps; mx++) {
            byte[] data = new byte[MAP * MAP];
            for (int y = 0; y < MAP; y++) for (int x = 0; x < MAP; x++) data[y * MAP + x] = MapPalette.matchColor(new Color(img.getRGB(mx * MAP + x, my * MAP + y)));
            out.add(data);
        }
        return out;
    }
    private BufferedImage dithered(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight(); double[][][] a = new double[h][w][3];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) { Color c = new Color(src.getRGB(x, y)); a[y][x][0]=c.getRed(); a[y][x][1]=c.getGreen(); a[y][x][2]=c.getBlue(); }
        BufferedImage out = new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
            Color old = new Color(clamp(a[y][x][0]), clamp(a[y][x][1]), clamp(a[y][x][2])); byte idx = MapPalette.matchColor(old); Color neu = MapPalette.getColor(idx); out.setRGB(x,y,neu.getRGB());
            double er=old.getRed()-neu.getRed(), eg=old.getGreen()-neu.getGreen(), eb=old.getBlue()-neu.getBlue();
            diffuse(a,w,h,x+1,y,er,eg,eb,7.0/16); diffuse(a,w,h,x-1,y+1,er,eg,eb,3.0/16); diffuse(a,w,h,x,y+1,er,eg,eb,5.0/16); diffuse(a,w,h,x+1,y+1,er,eg,eb,1.0/16);
        }
        return out;
    }
    private void diffuse(double[][][] a,int w,int h,int x,int y,double r,double g,double b,double f){ if(x<0||x>=w||y<0||y>=h)return; a[y][x][0]+=r*f; a[y][x][1]+=g*f; a[y][x][2]+=b*f; }
    private int clamp(double v){ return Math.max(0, Math.min(255, (int)Math.round(v))); }

    private int[] parseGrid(String s) { String[] p = s.toLowerCase(Locale.ROOT).split("x"); if (p.length != 2) throw new IllegalArgumentException("Grid must be like 2x2"); int w = Math.max(1, Math.min(maxGridWidth, Integer.parseInt(p[0]))), h = Math.max(1, Math.min(maxGridHeight, Integer.parseInt(p[1]))); return new int[]{w,h}; }
    private String defaultGrid() { return defaultWidth + "x" + defaultHeight; }
    private boolean isGrid(String s) { return s != null && s.toLowerCase(Locale.ROOT).matches("\\d+x\\d+"); }
    private boolean isDitherToken(String s) { return s != null && List.of("dither", "nodither", "no-dither", "--dither", "--no-dither").contains(s.toLowerCase(Locale.ROOT)); }
    private String firstGridArg(String[] args, int start) { for (int i = start; i < args.length; i++) if (isGrid(args[i])) return args[i]; return defaultGrid(); }
    private Boolean ditherOverride(String[] args, int start) { for (int i = start; i < args.length; i++) { String v = args[i].toLowerCase(Locale.ROOT); if (v.equals("dither") || v.equals("--dither")) return true; if (v.equals("nodither") || v.equals("no-dither") || v.equals("--no-dither")) return false; } return null; }
    private boolean userDither(Player p) { return ditherUsers.getOrDefault(p.getUniqueId(), defaultDithering); }
    private boolean withinSizeLimit(File f) { return f.length() <= maxImageSizeMb * 1024L * 1024L; }
    private boolean deny(Player p, String permission) { if (p.hasPermission(permission) || p.hasPermission("pixelframes.admin")) return false; msg(p, ChatColor.RED + "No permission: " + permission); return true; }
    private String buildKey(File f,int w,int h,boolean d) throws Exception { MessageDigest md = MessageDigest.getInstance("SHA-256"); try(InputStream in=new FileInputStream(f)){ in.transferTo(new OutputStream(){ public void write(int b){ md.update((byte)b); } public void write(byte[] b,int off,int len){ md.update(b,off,len); }}); } return bytes(md.digest()).substring(0,24)+"_"+w+"x"+h+"_"+(d?"d":"n"); }
    private String bytes(byte[] b){ StringBuilder sb=new StringBuilder(); for(byte x:b) sb.append(String.format("%02x",x)); return sb.toString(); }
    private String stripExt(String n){ int i=n.lastIndexOf('.'); return i>0?n.substring(0,i):n; }
    private String safeName(String s){ return s.replaceAll("[^a-zA-Z0-9._-]", "_"); }
    private ItemStack named(Material m,String n){ ItemStack i=new ItemStack(m); ItemMeta meta=i.getItemMeta(); meta.setDisplayName(n); i.setItemMeta(meta); return i; }
    private void msg(Player p, String s) { p.sendMessage(prefix + s); }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) { List<String> base = new ArrayList<>(List.of("gui","place","url","save","load","dither","reload")); base.addAll(imageIndex.keySet()); return base.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).limit(50).toList(); }
        if (args.length == 2 && args[0].equalsIgnoreCase("dither")) return List.of("on","off");
        if (args.length == 2 && args[0].equalsIgnoreCase("place")) return new ArrayList<>(builds.isConfigurationSection("aliases") ? Objects.requireNonNull(builds.getConfigurationSection("aliases")).getKeys(false) : List.of());
        if (args.length == 2 && !List.of("gui","place","url","save","load","dither","reload").contains(args[0].toLowerCase(Locale.ROOT))) return List.of("1x1","2x2","3x2","4x3");
        return List.of();
    }

    private record ImageEntry(String key, File file, boolean animated) {}
    private static final class Build { final String key,name,source; final int width,height; final boolean dither,animated; final List<Integer> mapIds; Build(String key,String name,String source,int width,int height,boolean dither,boolean animated,List<Integer> mapIds){this.key=key;this.name=name;this.source=source;this.width=width;this.height=height;this.dither=dither;this.animated=animated;this.mapIds=mapIds;} }
    private static final class StaticRenderer extends MapRenderer { private final byte[] data; StaticRenderer(byte[] data){super(false);this.data=data;} @Override public void render(MapView view, MapCanvas canvas, Player player){ for(int y=0;y<MAP;y++) for(int x=0;x<MAP;x++) canvas.setPixel(x,y,data[y*MAP+x]); } }
    private static final class AnimatedRenderer extends MapRenderer { private final List<byte[]> frames; AnimatedRenderer(List<byte[]> frames){super(false);this.frames=frames;} @Override public void render(MapView view, MapCanvas canvas, Player player){ if(frames.isEmpty())return; byte[] data=frames.get((int)((player.getWorld().getFullTime()/4)%frames.size())); for(int y=0;y<MAP;y++) for(int x=0;x<MAP;x++) canvas.setPixel(x,y,data[y*MAP+x]); } }
}
