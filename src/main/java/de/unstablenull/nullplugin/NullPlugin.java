package de.unstablenull.nullplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NullPlugin extends JavaPlugin implements Listener {

    /** This player always has the Null permissions. */
    private static final String OWNER_NAME = "Kab00m_MC";

    // Only a dot counts as decimal separator, commas are treated as separators between numbers.
    // Wither Cannon settings
    private static final int SKULL_WAVES = 10;             // waves per shot
    private static final int SKULLS_PER_WAVE = 40;         // skulls per wave (10 x 40 = 400 per shot)
    private static final long SKULL_WAVE_TICKS = 3L;       // ticks between two waves
    private static final double SKULL_WIDTH = 26.0;        // sideways spread of the volley
    private static final double SKULL_HEIGHT = 12.0;       // height range above the hook
    private static final double SKULL_BACK = 16.0;         // how far behind the hook the volley starts
    private static final double SKULL_DEPTH = 6.0;         // depth of the starting zone
    private static final double SKULL_SPEED = 0.9;         // start speed in blocks per tick
    private static final long WITHER_COOLDOWN_MS = 5000;   // cooldown per player
    /** false = all skulls fly the way the player looks, true = every skull flies in a random horizontal direction. */
    private static final boolean SKULLS_ALL_DIRECTIONS = false;

    private static final Pattern NUM = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private NamespacedKey keyType;
    private NamespacedKey keyData;

    private final Set<UUID> powered = new HashSet<>();
    private final Map<UUID, Location> pendingPearl = new HashMap<>();
    private final Map<UUID, Long> fishCooldown = new HashMap<>();
    private final Map<UUID, Long> bookNotified = new HashMap<>();
    private final Set<UUID> bookBusy = new HashSet<>();
    private final Random rnd = new Random();
    private BotManager bots;
    /** Extra always-allowed owners from config.yml (lowercase names or UUIDs). */
    private final Set<String> owners = new HashSet<>();
    /** portal key -> destination (world;x;y;z). Both directions are stored. */
    private final Map<String, String> links = new HashMap<>();

    // ------------------------------------------------------------ Lifecycle

    @Override
    public void onEnable() {
        keyType = new NamespacedKey(this, "type");
        keyData = new NamespacedKey(this, "data");
        saveDefaultConfig();
        for (String s : getConfig().getStringList("power")) {
            try {
                powered.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Invalid UUID in config.yml (power): " + s);
            }
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        for (String l : getConfig().getStringList("links")) {
            int i = l.indexOf('=');
            if (i > 0) links.put(l.substring(0, i), l.substring(i + 1));
        }
        for (String o : getConfig().getStringList("owners")) owners.add(o.toLowerCase(java.util.Locale.ROOT));
        bots = new BotManager(this);
        bots.start();
    }

    @Override
    public void onDisable() {
        if (bots != null) bots.shutdown();
    }

    private boolean hasPower(Player p) {
        return p.getName().equalsIgnoreCase(OWNER_NAME)
                || owners.contains(p.getName().toLowerCase(java.util.Locale.ROOT))
                || owners.contains(p.getUniqueId().toString())
                || powered.contains(p.getUniqueId());
    }

    private void saveLinks() {
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, String> en : links.entrySet()) l.add(en.getKey() + "=" + en.getValue());
        getConfig().set("links", l);
        saveConfig();
    }

    private void savePower() {
        List<String> l = new ArrayList<>();
        for (UUID u : powered) l.add(u.toString());
        getConfig().set("power", l);
        saveConfig();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        fishCooldown.remove(id);
        pendingPearl.remove(id);
    }

    // ------------------------------------------------------------ Helpers

    private static Component text(String s, NamedTextColor c) {
        return Component.text(s, c);
    }

    private ItemStack make(Material m, String type, String data, String name, NamedTextColor color, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) {
            l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(l);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(keyType, PersistentDataType.STRING, type);
        if (data != null) meta.getPersistentDataContainer().set(keyData, PersistentDataType.STRING, data);
        it.setItemMeta(meta);
        return it;
    }

    private String typeOf(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(keyType, PersistentDataType.STRING);
    }

    private String dataOf(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(keyData, PersistentDataType.STRING);
    }

    private void give(Player p, ItemStack it) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(it);
        for (ItemStack r : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), r);
    }

    private void updateTotemLore(ItemMeta meta, Collection<String> uuids) {
        List<String> names = new ArrayList<>();
        for (String s : uuids) {
            try {
                String n = Bukkit.getOfflinePlayer(UUID.fromString(s)).getName();
                names.add(n != null ? n : s);
            } catch (IllegalArgumentException ignored) {
            }
        }
        String line = names.isEmpty() ? "Bound: nobody" : "Bound: " + String.join(", ", names);
        meta.lore(List.of(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
    }

    /** Format: world;x;y;z */
    private String encode(World w, double x, double y, double z) {
        return w.getName() + ";" + x + ";" + y + ";" + z;
    }

    private Location decode(String s) {
        if (s == null) return null;
        String[] a = s.split(";");
        if (a.length != 4) return null;
        World w = Bukkit.getWorld(a[0]);
        if (w == null) return null;
        try {
            return new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double[] parseCoords(String[] args, int from) {
        if (args.length < from + 3) return null;
        try {
            double[] out = new double[3];
            for (int i = 0; i < 3; i++) {
                String s = args[from + i].replaceAll(",$", "").replace(',', '.');
                out[i] = Double.parseDouble(s);
                if (Double.isNaN(out[i]) || Double.isInfinite(out[i]) || Math.abs(out[i]) > 30_000_000) return null;
            }
            return out;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    // ------------------------------------------------------------ Commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase();

        if (name.equals("nullpower")) {
            return handlePower(sender, args);
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!hasPower(p)) {
            p.sendMessage(text("You don't have the Null permissions. (name seen by the plugin: " + p.getName() + ")", NamedTextColor.RED));
            return true;
        }

        if (name.equals("nulltotem")) {
            return handleNullTotem(p, args);
        }

        if (args.length == 0) {
            p.sendMessage(text("Usage: /null <tpbook|withercannon|stasistotem|totemofnull|pearlcannon|bot>", NamedTextColor.RED));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "tpbook" -> give(p, make(Material.WRITABLE_BOOK, "tpbook", null, "Null TP Book", NamedTextColor.DARK_PURPLE,
                    "Write coordinates on page 1 (x y z),",
                    "sign it and throw it into a nether portal."));
            case "withercannon" -> {
                ItemStack rod = make(Material.FISHING_ROD, "withercannon", null, "Wither Cannon", NamedTextColor.DARK_GRAY,
                        "Cast the rod: wither skulls rain down.");
                ItemMeta m = rod.getItemMeta();
                m.setUnbreakable(true);
                rod.setItemMeta(m);
                give(p, rod);
            }
            case "stasistotem" -> {
                double[] c = parseCoords(args, 1);
                if (c == null) {
                    p.sendMessage(text("Usage: /null stasistotem <x> <y> <z>", NamedTextColor.RED));
                    return true;
                }
                give(p, make(Material.TOTEM_OF_UNDYING, "stasis", encode(p.getWorld(), c[0], c[1], c[2]),
                        "Stasis Totem", NamedTextColor.AQUA,
                        String.format(java.util.Locale.ROOT, "Target: %.1f %.1f %.1f", c[0], c[1], c[2])));
            }
            case "totemofnull" -> {
                ItemStack t = make(Material.TOTEM_OF_UNDYING, "nulltotem", "", "Totem of Null", NamedTextColor.DARK_RED);
                ItemMeta m = t.getItemMeta();
                updateTotemLore(m, List.of());
                t.setItemMeta(m);
                give(p, t);
            }
            case "pearlcannon" -> {
                double[] c = parseCoords(args, 1);
                if (c == null) {
                    p.sendMessage(text("Usage: /null pearlcannon <x> <y> <z>", NamedTextColor.RED));
                    return true;
                }
                give(p, make(Material.ENDER_PEARL, "pearl", encode(p.getWorld(), c[0], c[1], c[2]),
                        "Pearl Cannon", NamedTextColor.LIGHT_PURPLE,
                        String.format(java.util.Locale.ROOT, "Target: %.1f %.1f %.1f", c[0], c[1], c[2])));
            }
            case "bot" -> bots.handle(p, Arrays.copyOfRange(args, 1, args.length));
            default -> p.sendMessage(text("Unknown item.", NamedTextColor.RED));
        }
        return true;
    }

    private boolean handlePower(CommandSender sender, String[] args) {
        if (sender instanceof Player op && !op.isOp()) {
            sender.sendMessage(text("Only operators can do that.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(text("Usage: /nullpower <player>", NamedTextColor.RED));
            return true;
        }
        OfflinePlayer t = findPlayer(args[0]);
        if (t == null) {
            sender.sendMessage(text("Player not found.", NamedTextColor.RED));
            return true;
        }
        if (OWNER_NAME.equalsIgnoreCase(t.getName())) {
            sender.sendMessage(text(OWNER_NAME + " has the Null permissions permanently.", NamedTextColor.YELLOW));
            return true;
        }
        if (powered.remove(t.getUniqueId())) {
            sender.sendMessage(text(args[0] + " lost the Null permissions.", NamedTextColor.YELLOW));
        } else {
            powered.add(t.getUniqueId());
            sender.sendMessage(text(args[0] + " now has the Null permissions.", NamedTextColor.GREEN));
        }
        savePower();
        return true;
    }

    private boolean handleNullTotem(Player p, String[] args) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!"nulltotem".equals(typeOf(hand))) {
            p.sendMessage(text("Hold the Totem of Null in your main hand.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 2 || !(args[0].equalsIgnoreCase("tag") || args[0].equalsIgnoreCase("untag"))) {
            p.sendMessage(text("Usage: /nulltotem <tag|untag> <player>", NamedTextColor.RED));
            return true;
        }
        OfflinePlayer t = findPlayer(args[1]);
        if (t == null) {
            p.sendMessage(text("Player not found.", NamedTextColor.RED));
            return true;
        }
        boolean tag = args[0].equalsIgnoreCase("tag");

        ItemMeta meta = hand.getItemMeta();
        String raw = meta.getPersistentDataContainer().getOrDefault(keyData, PersistentDataType.STRING, "");
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (!raw.isEmpty()) set.addAll(Arrays.asList(raw.split(",")));
        if (tag) set.add(t.getUniqueId().toString());
        else set.remove(t.getUniqueId().toString());
        meta.getPersistentDataContainer().set(keyData, PersistentDataType.STRING, String.join(",", set));
        updateTotemLore(meta, set);
        hand.setItemMeta(meta);

        if (tag) {
            Player tagged = Bukkit.getPlayer(t.getUniqueId());
            if (tagged != null) {
                tagged.sendMessage(text("You have been bound to a Totem of Null.", NamedTextColor.DARK_RED));
            }
        }
        p.sendMessage(text(args[1] + (tag ? " has been bound." : " has been unbound."), NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        String n = cmd.getName().toLowerCase();
        List<String> out = new ArrayList<>();
        if (n.equals("null") && args.length >= 2 && args[0].equalsIgnoreCase("bot")) {
            return bots.complete(Arrays.copyOfRange(args, 1, args.length));
        }
        if (n.equals("null") && args.length == 1) {
            out.addAll(List.of("tpbook", "withercannon", "stasistotem", "totemofnull", "pearlcannon", "bot"));
        } else if (n.equals("nulltotem") && args.length == 1) {
            out.addAll(List.of("tag", "untag"));
        } else if ((n.equals("nulltotem") && args.length == 2) || (n.equals("nullpower") && args.length == 1)) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.toLowerCase().startsWith(last));
        return out;
    }

    // ------------------------------------------------------------ 1) Null TP Book

    /** Start watching a dropped, signed TP book. */
    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        Item item = e.getItemDrop();
        if (!isSignedTpBook(item.getItemStack())) return;

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!item.isValid() || ticks++ > 1200) {
                    cancel();
                    return;
                }
                if (item.getLocation().getBlock().getType() == Material.NETHER_PORTAL) {
                    cancel();
                    tryBook(item, item.getLocation().getBlock());
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    /** Backup trigger: the item touches a portal (also catches books that were not dropped by a player). */
    @EventHandler
    public void onPortalEnter(EntityPortalEnterEvent e) {
        if (!(e.getEntity() instanceof Item item)) return;
        if (!isSignedTpBook(item.getItemStack())) return;
        UUID id = item.getUniqueId();
        if (!bookBusy.add(id)) return;
        Block entered = findPortalBlock(e.getLocation());
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                tryBook(item, entered != null ? entered : item.getLocation().getBlock());
            } finally {
                bookBusy.remove(id);
            }
        });
    }

    /** The book must never travel through the portal like a normal item. */
    @EventHandler
    public void onPortalTravel(EntityPortalEvent e) {
        if (e.getEntity() instanceof Item item && isSignedTpBook(item.getItemStack())) {
            e.setCancelled(true);
        }
    }

    private boolean isSignedTpBook(ItemStack st) {
        return st != null && st.getType() == Material.WRITTEN_BOOK && "tpbook".equals(typeOf(st));
    }

    private void tryBook(Item item, Block originBlock) {
        if (!item.isValid()) return;
        ItemStack st = item.getItemStack();
        if (!isSignedTpBook(st)) return;

        UUID throwerId = item.getThrower();
        Player msg = throwerId != null ? Bukkit.getPlayer(throwerId) : null;

        double[] c = readBookCoords(st);
        if (c == null) {
            long now = System.currentTimeMillis();
            if (msg != null && bookNotified.getOrDefault(item.getUniqueId(), 0L) < now - 3000) {
                bookNotified.put(item.getUniqueId(), now);
                msg.sendMessage(text("Invalid coordinates in the book (x y z on page 1).", NamedTextColor.RED));
            }
            return;
        }

        World.Environment here = item.getWorld().getEnvironment();
        World.Environment want = here == World.Environment.NORMAL ? World.Environment.NETHER
                : here == World.Environment.NETHER ? World.Environment.NORMAL : null;
        if (want == null) return;

        World target = null;
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == want) {
                target = w;
                break;
            }
        }
        if (target == null) {
            if (msg != null) msg.sendMessage(text("Target dimension not found.", NamedTextColor.RED));
            return;
        }

        int x = (int) Math.floor(c[0]);
        int z = (int) Math.floor(c[2]);
        int yMax = want == World.Environment.NETHER ? 120 : target.getMaxHeight() - 6;
        int y = Math.max(target.getMinHeight() + 2, Math.min((int) Math.floor(c[1]), yMax));

        buildPortal(target, x, y, z);
        linkPortals(originBlock, target, x, y, z);
        bookNotified.remove(item.getUniqueId());
        item.remove();
        if (msg != null) {
            msg.sendMessage(text("Portal created at " + x + " " + y + " " + z + ".", NamedTextColor.DARK_PURPLE));
            // Pull the thrower to the new portal: stand in front of it, facing the portal (north = yaw 180)
            Location arrive = new Location(target, x + 1.0, y, z + 1.5, 180f, 0f);
            msg.teleport(arrive, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
    }

    // ------------------------------------------------------------ Portal links

    /** The portal block at or directly next to the given location, or null. */
    private Block findPortalBlock(Location loc) {
        if (loc == null) return null;
        Block b = loc.getBlock();
        if (b.getType() == Material.NETHER_PORTAL) return b;
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block n = b.getRelative(f);
            if (n.getType() == Material.NETHER_PORTAL) return n;
        }
        return null;
    }

    /** Identifies a portal by the smallest x/y/z of all its connected portal blocks. */
    private String portalKey(Block start) {
        if (start == null || start.getType() != Material.NETHER_PORTAL) return null;
        Set<Block> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        int minX = start.getX(), minY = start.getY(), minZ = start.getZ();
        while (!queue.isEmpty() && seen.size() <= 500) {
            Block b = queue.poll();
            minX = Math.min(minX, b.getX());
            minY = Math.min(minY, b.getY());
            minZ = Math.min(minZ, b.getZ());
            for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                    BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block n = b.getRelative(f);
                if (n.getType() == Material.NETHER_PORTAL && seen.add(n)) queue.add(n);
            }
        }
        return start.getWorld().getName() + ";" + minX + ";" + minY + ";" + minZ;
    }

    /** Links the portal the book entered with the freshly built one (both directions). */
    private void linkPortals(Block originBlock, World target, int x, int y, int z) {
        String originKey = portalKey(originBlock);
        if (originKey == null) return;
        String newKey = target.getName() + ";" + x + ";" + y + ";" + z; // interior min corner of the new portal
        links.put(originKey, encode(target, x + 1.0, y, z + 0.5));
        links.put(newKey, encode(originBlock.getWorld(), originBlock.getX() + 0.5, originBlock.getY(), originBlock.getZ() + 0.5));
        saveLinks();
    }

    /** Walking through a linked portal leads to its partner portal instead of the vanilla destination. */
    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent e) {
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) return;
        Block b = findPortalBlock(e.getFrom());
        String key = portalKey(b);
        if (key == null) return;
        Location dest = decode(links.get(key));
        if (dest == null) return;
        e.setTo(dest);
    }

    private double[] readBookCoords(ItemStack st) {
        if (!(st.getItemMeta() instanceof BookMeta bm)) return null;
        List<Component> pages = bm.pages();
        if (pages.isEmpty()) return null;
        String page = PlainTextComponentSerializer.plainText().serialize(pages.get(0));
        Matcher m = NUM.matcher(page);
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            if (!m.find()) return null;
            try {
                c[i] = Double.parseDouble(m.group());
            } catch (NumberFormatException e) {
                return null;
            }
            if (Math.abs(c[i]) > 30_000_000) return null;
        }
        return c;
    }

    private void buildPortal(World w, int x, int y, int z) {
        // Frame along the X axis: interior 2 wide (x..x+1) and 3 tall (y..y+2)
        for (int dx = -1; dx <= 2; dx++) {
            for (int dy = -1; dy <= 3; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block b = w.getBlockAt(x + dx, y + dy, z + dz);
                    boolean frame = dz == 0 && (dx == -1 || dx == 2 || dy == -1 || dy == 3);
                    boolean floor = dz != 0 && dy == -1;
                    if (frame || floor) {
                        b.setType(Material.OBSIDIAN, false);
                    } else if (dy >= 0 && dy <= 2) {
                        b.setType(Material.AIR, false);
                    }
                }
            }
        }
        BlockData portal = Bukkit.createBlockData("minecraft:nether_portal[axis=x]");
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                w.getBlockAt(x + dx, y + dy, z).setBlockData(portal, false);
            }
        }
    }

    // ------------------------------------------------------------ 2) Wither Cannon

    @EventHandler
    public void onFish(PlayerFishEvent e) {
        PlayerFishEvent.State s = e.getState();
        if (s == PlayerFishEvent.State.FISHING || s == PlayerFishEvent.State.BITE || s == PlayerFishEvent.State.LURED) {
            return;
        }
        Player p = e.getPlayer();
        if (!"withercannon".equals(typeOf(p.getInventory().getItemInMainHand()))
                && !"withercannon".equals(typeOf(p.getInventory().getItemInOffHand()))) {
            return;
        }

        if (s == PlayerFishEvent.State.CAUGHT_FISH) e.setCancelled(true);

        long now = System.currentTimeMillis();
        if (fishCooldown.getOrDefault(p.getUniqueId(), 0L) > now) return;
        fishCooldown.put(p.getUniqueId(), now + WITHER_COOLDOWN_MS);

        fireWithers(e.getHook().getLocation(), p.getLocation().getDirection());
    }

    /**
     * Fires a dense volley of blue wither skulls that fly horizontally. By default the volley is a wide curtain
     * that starts behind the hook and sweeps across it in the direction the player is looking.
     */
    private void fireWithers(Location loc, Vector facing) {
        World w = loc.getWorld();
        Vector dir = facing.clone().setY(0);
        if (dir.lengthSquared() < 0.0001) dir = new Vector(0, 0, 1);
        final Vector fwd = dir.normalize();
        final Vector side = new Vector(-fwd.getZ(), 0, fwd.getX());
        final double minY = w.getMinHeight() + 2;
        final double maxY = w.getEnvironment() == World.Environment.NETHER ? 120 : w.getMaxHeight() - 2;

        new BukkitRunnable() {
            int wave = 0;

            @Override
            public void run() {
                if (wave++ >= SKULL_WAVES) {
                    cancel();
                    return;
                }
                for (int i = 0; i < SKULLS_PER_WAVE; i++) {
                    double up = 0.5 + rnd.nextDouble() * SKULL_HEIGHT;
                    Location spawn;
                    Vector flight;
                    if (SKULLS_ALL_DIRECTIONS) {
                        // Skulls appear all around the hook and fly outwards in random directions.
                        double r = Math.sqrt(rnd.nextDouble()) * (SKULL_WIDTH / 2);
                        double a = rnd.nextDouble() * Math.PI * 2;
                        spawn = loc.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
                        double fa = rnd.nextDouble() * Math.PI * 2;
                        flight = new Vector(Math.cos(fa), 0, Math.sin(fa));
                    } else {
                        double along = -SKULL_BACK + rnd.nextDouble() * SKULL_DEPTH;
                        double lateral = (rnd.nextDouble() - 0.5) * SKULL_WIDTH;
                        spawn = loc.clone().add(fwd.clone().multiply(along)).add(side.clone().multiply(lateral));
                        // A few degrees of spread so the curtain looks natural.
                        flight = fwd.clone().rotateAroundY(Math.toRadians((rnd.nextDouble() - 0.5) * 10));
                    }
                    spawn.setY(Math.max(minY, Math.min(maxY, loc.getY() + up)));
                    if (!spawn.getBlock().isPassable()) continue;

                    final Vector f = flight;
                    w.spawn(spawn, WitherSkull.class, sk -> {
                        sk.setCharged(true); // blue wither skulls
                        sk.setDirection(f);
                        sk.setVelocity(f.clone().multiply(SKULL_SPEED));
                    });
                }
            }
        }.runTaskTimer(this, 0L, SKULL_WAVE_TICKS);
    }

    // ------------------------------------------------------------ 3) Stasis Totem + 4) Totem of Null

    /** Same order as vanilla: main hand first, then off hand. */
    private ItemStack usedTotem(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand();
        if (main.getType() == Material.TOTEM_OF_UNDYING) return main;
        ItemStack off = p.getInventory().getItemInOffHand();
        return off.getType() == Material.TOTEM_OF_UNDYING ? off : null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        ItemStack used = usedTotem(p);
        String type = typeOf(used);
        if (type == null) return;
        String data = dataOf(used);

        if (type.equals("stasis")) {
            Location target = decode(data);
            if (target == null) return;
            Bukkit.getScheduler().runTask(this, () -> {
                if (!p.isOnline()) return;
                target.setYaw(p.getLocation().getYaw());
                target.setPitch(p.getLocation().getPitch());
                p.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
            });
        } else if (type.equals("nulltotem")) {
            if (data == null || data.isEmpty()) return;
            List<String> ids = Arrays.asList(data.split(","));
            Bukkit.getScheduler().runTask(this, () -> {
                for (String id : ids) {
                    try {
                        Player t = Bukkit.getPlayer(UUID.fromString(id));
                        if (t != null && !t.isDead()) t.setHealth(0);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------ 5) Pearl Cannon

    @EventHandler
    public void onPearlLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof EnderPearl pearl)) return;
        if (!(pearl.getShooter() instanceof Player p)) return;
        ItemStack main = p.getInventory().getItemInMainHand();
        ItemStack off = p.getInventory().getItemInOffHand();
        ItemStack src = "pearl".equals(typeOf(main)) ? main : "pearl".equals(typeOf(off)) ? off : null;
        if (src == null) return;
        String data = dataOf(src);
        if (data != null) pearl.getPersistentDataContainer().set(keyData, PersistentDataType.STRING, data);
    }

    @EventHandler
    public void onPearlHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof EnderPearl pearl)) return;
        if (!(pearl.getShooter() instanceof Player p)) return;
        Location target = decode(pearl.getPersistentDataContainer().get(keyData, PersistentDataType.STRING));
        if (target == null) return;
        UUID id = p.getUniqueId();
        pendingPearl.put(id, target);
        Bukkit.getScheduler().runTaskLater(this, () -> pendingPearl.remove(id, target), 5L);
    }

    @EventHandler
    public void onPearlTeleport(PlayerTeleportEvent e) {
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        Player p = e.getPlayer();
        Location target = pendingPearl.remove(p.getUniqueId());
        if (target == null) return;
        e.setCancelled(true); // cancelled = no pearl damage, we teleport manually
        Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline()) return;
            target.setYaw(p.getLocation().getYaw());
            target.setPitch(p.getLocation().getPitch());
            p.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        });
    }
}
