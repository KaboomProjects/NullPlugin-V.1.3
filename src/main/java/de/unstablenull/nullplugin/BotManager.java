package de.unstablenull.nullplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Handles everything behind "/null bot ...". */
public class BotManager implements Listener {

    private static final int MAX_BOTS = 10;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final JavaPlugin plugin;
    private boolean shuttingDown = false;
    /** lowercase kit name -> kit */
    private final Map<String, Kit> kits = new LinkedHashMap<>();

    /** lowercase bot name -> bot */
    private final Map<String, Bot> bots = new HashMap<>();
    /** brain and body entity uuid -> bot */
    private final Map<UUID, Bot> byEntity = new HashMap<>();

    public BotManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        loadKits();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    public void shutdown() {
        shuttingDown = true;
        for (Bot b : new ArrayList<>(bots.values())) b.remove();
        bots.clear();
        byEntity.clear();
    }

    private void tickAll() {
        if (bots.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Bot b : new ArrayList<>(bots.values())) {
            boolean ok;
            boolean crashed = false;
            try {
                ok = b.tick(now);
            } catch (Exception ex) {
                plugin.getLogger().warning("Bot " + b.name + " crashed and was removed: " + ex);
                ok = false;
                crashed = true;
            }
            if (!ok) {
                removeBot(b);
                if (b.respawn && !crashed) scheduleRespawn(b);
            }
        }
    }

    private void removeBot(Bot b) {
        bots.remove(b.name.toLowerCase(Locale.ROOT));
        byEntity.remove(b.brain.getUniqueId());
        byEntity.remove(b.body.getUniqueId());
        b.remove();
    }

    // ------------------------------------------------------------ Kits

    private File kitFile() {
        return new File(plugin.getDataFolder(), "kits.yml");
    }

    private void loadKits() {
        File f = kitFile();
        if (!f.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = yml.getConfigurationSection("kits");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec != null) kits.put(key.toLowerCase(Locale.ROOT), Kit.load(key.toLowerCase(Locale.ROOT), sec));
        }
    }

    private void saveKits() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Kit k : kits.values()) k.save(yml.createSection("kits." + k.name));
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(kitFile());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save kits.yml: " + e.getMessage());
        }
    }

    private void kitCommand(Player p, String[] args) {
        if (args.length < 2) {
            p.sendMessage(msg("Usage: /null bot kit <save|delete|list|give>", NamedTextColor.RED));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "save" -> {
                if (args.length < 3 || !NAME.matcher(args[2]).matches()) {
                    p.sendMessage(msg("Usage: /null bot kit save <kit> (1-16 characters: letters, numbers, _)", NamedTextColor.RED));
                    return;
                }
                Kit k = Kit.fromPlayer(args[2].toLowerCase(Locale.ROOT), p);
                if (k.itemCount() == 0) {
                    p.sendMessage(msg("You wear and hold nothing, there is nothing to save.", NamedTextColor.RED));
                    return;
                }
                kits.put(k.name, k);
                saveKits();
                p.sendMessage(msg("Kit " + k.name + " saved: " + k.itemCount() + " items, " + k.totems
                        + " totems, " + k.apples + " golden apples.", NamedTextColor.GREEN));
            }
            case "delete" -> {
                if (args.length < 3 || kits.remove(args[2].toLowerCase(Locale.ROOT)) == null) {
                    p.sendMessage(msg("Kit not found. Usage: /null bot kit delete <kit>", NamedTextColor.RED));
                    return;
                }
                saveKits();
                p.sendMessage(msg("Kit " + args[2].toLowerCase(Locale.ROOT) + " deleted.", NamedTextColor.YELLOW));
            }
            case "list" -> {
                if (kits.isEmpty()) {
                    p.sendMessage(msg("No kits yet. Wear your gear and use /null bot kit save <kit>.", NamedTextColor.YELLOW));
                } else {
                    for (Kit k : kits.values()) {
                        p.sendMessage(msg(k.name + " - " + k.itemCount() + " items, " + k.totems + " totems, "
                                + k.apples + " golden apples", NamedTextColor.GRAY));
                    }
                }
            }
            case "give" -> {
                if (args.length < 4) {
                    p.sendMessage(msg("Usage: /null bot kit give <bot> <kit>", NamedTextColor.RED));
                    return;
                }
                Bot b = bots.get(args[2].toLowerCase(Locale.ROOT));
                Kit k = kits.get(args[3].toLowerCase(Locale.ROOT));
                if (b == null) {
                    p.sendMessage(msg("Bot not found.", NamedTextColor.RED));
                    return;
                }
                if (k == null) {
                    p.sendMessage(msg("Kit not found. Use /null bot kit list.", NamedTextColor.RED));
                    return;
                }
                b.applyKit(k);
                p.sendMessage(msg(b.name + " now wears kit " + k.name + ".", NamedTextColor.GREEN));
            }
            default -> p.sendMessage(msg("Usage: /null bot kit <save|delete|list|give>", NamedTextColor.RED));
        }
    }

    /** A dead bot with respawn=on comes back after 3 seconds with fresh totems and apples. */
    private void scheduleRespawn(Bot dead) {
        Location where = dead.lastLoc;
        if (where == null || where.getWorld() == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (shuttingDown || bots.size() >= MAX_BOTS || bots.containsKey(dead.name.toLowerCase(Locale.ROOT))) return;
            Bot nb = spawnBot(dead.owner, where.clone().add(0, 0.5, 0), dead.name, dead.gear, dead.difficulty, dead.skin);
            if (dead.kit != null) nb.applyKit(dead.kit);
            nb.setShowName(dead.showName);
            nb.mode = dead.mode;
            nb.respawn = true;
            nb.totems = dead.totemsStart;
            nb.totemsStart = dead.totemsStart;
            nb.apples = dead.applesStart;
            nb.applesStart = dead.applesStart;
            nb.refreshVisuals();
        }, 60L);
    }

    // ------------------------------------------------------------ Spawning

    private Bot spawnBot(UUID owner, Location loc, String name, Bot.Gear gear, Bot.Difficulty diff, String skin) {
        World w = loc.getWorld();

        Zombie brain = w.spawn(loc, Zombie.class, z -> {
            z.setBaby(false);
            z.setShouldBurnInDay(false);
            z.setCanBreakDoors(false);
            z.setInvisible(true);
            z.setSilent(true);
            z.setCanPickupItems(false);
            z.setRemoveWhenFarAway(false);
            z.setPersistent(false);
            z.customName(Component.text(name));
            z.setCustomNameVisible(false);
        });
        // Remove vanilla zombie behavior; the bot is steered by Bot#tick.
        Bukkit.getMobGoals().removeAllGoals(brain);

        Entity raw = w.spawnEntity(loc, EntityType.MANNEQUIN);
        LivingEntity body = (LivingEntity) raw;
        body.setPersistent(false);
        body.setRemoveWhenFarAway(false);
        body.setGravity(false);
        body.setCollidable(false);
        body.setSilent(true);
        body.setCustomNameVisible(false); // no nameplate (toggle with /null bot name <bot> on)

        // Skin + hide the default "Mannequin" description. The skin name is validated (no command injection).
        String nbt = (skin != null && NAME.matcher(skin).matches())
                ? "{profile:\"" + skin + "\",hide_description:1b}"
                : "{hide_description:1b}";
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "data merge entity " + body.getUniqueId() + " " + nbt);

        Bot bot = new Bot(name, owner, brain, body, gear, diff, skin);
        bots.put(name.toLowerCase(Locale.ROOT), bot);
        byEntity.put(brain.getUniqueId(), bot);
        byEntity.put(body.getUniqueId(), bot);
        return bot;
    }

    // ------------------------------------------------------------ Commands

    private static Component msg(String s, NamedTextColor c) {
        return Component.text(s, c);
    }

    /** @param args everything after "/null bot" */
    public void handle(Player p, String[] args) {
        if (args.length == 0) {
            p.sendMessage(msg("Usage: /null bot <spawn|remove|list|info|tp|mode|difficulty|gear|kit|name|respawn|totems|apples>", NamedTextColor.RED));
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> spawn(p, args);
            case "remove" -> remove(p, args);
            case "list" -> {
                if (bots.isEmpty()) {
                    p.sendMessage(msg("No bots.", NamedTextColor.YELLOW));
                } else {
                    for (Bot b : bots.values()) {
                        p.sendMessage(msg(b.name + " - " + b.loadoutName() + ", " + b.difficulty + ", " + b.mode
                                + ", totems " + b.totems + ", apples " + b.apples
                                + ", respawn " + (b.respawn ? "on" : "off"), NamedTextColor.GRAY));
                    }
                }
            }
            case "mode" -> {
                Bot b = needBot(p, args, "Usage: /null bot mode <name> <owner|nearest|passive>");
                if (b == null) return;
                try {
                    b.mode = Bot.Mode.valueOf(args[2].toUpperCase(Locale.ROOT));
                    p.sendMessage(msg(b.name + " mode: " + b.mode, NamedTextColor.GREEN));
                } catch (IllegalArgumentException e) {
                    p.sendMessage(msg("Modes: owner, nearest, passive", NamedTextColor.RED));
                }
            }
            case "difficulty" -> {
                Bot b = needBot(p, args, "Usage: /null bot difficulty <name> <easy|normal|hard|insane>");
                if (b == null) return;
                try {
                    b.applyDifficulty(Bot.Difficulty.valueOf(args[2].toUpperCase(Locale.ROOT)));
                    p.sendMessage(msg(b.name + " difficulty: " + b.difficulty, NamedTextColor.GREEN));
                } catch (IllegalArgumentException e) {
                    p.sendMessage(msg("Difficulties: easy, normal, hard, insane", NamedTextColor.RED));
                }
            }
            case "gear" -> {
                Bot b = needBot(p, args, "Usage: /null bot gear <name> <netherite|diamond|iron|none>");
                if (b == null) return;
                try {
                    b.applyGear(Bot.Gear.valueOf(args[2].toUpperCase(Locale.ROOT)));
                    p.sendMessage(msg(b.name + " gear: " + b.gear, NamedTextColor.GREEN));
                } catch (IllegalArgumentException e) {
                    p.sendMessage(msg("Gear: netherite, diamond, iron, none", NamedTextColor.RED));
                }
            }
            case "kit" -> kitCommand(p, args);
            case "name" -> {
                Bot b = needBot(p, args, "Usage: /null bot name <name> <on|off>");
                if (b == null) return;
                String v = args[2].toLowerCase(Locale.ROOT);
                if (!v.equals("on") && !v.equals("off")) {
                    p.sendMessage(msg("Value must be on or off.", NamedTextColor.RED));
                    return;
                }
                b.setShowName(v.equals("on"));
                p.sendMessage(msg(b.name + " nameplate: " + v, NamedTextColor.GREEN));
            }
            case "info" -> {
                Bot b = args.length < 2 ? null : bots.get(args[1].toLowerCase(Locale.ROOT));
                if (b == null) {
                    p.sendMessage(msg("Usage: /null bot info <name> (bot must exist)", NamedTextColor.RED));
                    return;
                }
                p.sendMessage(msg(b.name + ": " + (int) Math.ceil(b.brain.getHealth()) + " HP, " + b.loadoutName() + ", "
                        + b.difficulty + ", mode " + b.mode + ", totems " + b.totems + ", apples " + b.apples
                        + ", respawn " + (b.respawn ? "on" : "off"), NamedTextColor.GRAY));
            }
            case "tp" -> {
                Bot b = args.length < 2 ? null : bots.get(args[1].toLowerCase(Locale.ROOT));
                if (b == null) {
                    p.sendMessage(msg("Usage: /null bot tp <name> (bot must exist)", NamedTextColor.RED));
                    return;
                }
                b.brain.teleport(p.getLocation());
                p.sendMessage(msg(b.name + " teleported to you.", NamedTextColor.GREEN));
            }
            case "respawn" -> {
                Bot b = needBot(p, args, "Usage: /null bot respawn <name> <on|off>");
                if (b == null) return;
                String v = args[2].toLowerCase(Locale.ROOT);
                if (!v.equals("on") && !v.equals("off")) {
                    p.sendMessage(msg("Value must be on or off.", NamedTextColor.RED));
                    return;
                }
                b.respawn = v.equals("on");
                p.sendMessage(msg(b.name + " respawn: " + v, NamedTextColor.GREEN));
            }
            case "totems" -> {
                Bot b = needBot(p, args, "Usage: /null bot totems <name> <0-5>");
                if (b == null) return;
                try {
                    b.setTotems(Math.max(0, Math.min(5, Integer.parseInt(args[2]))));
                    p.sendMessage(msg(b.name + " totems: " + b.totems, NamedTextColor.GREEN));
                } catch (NumberFormatException e) {
                    p.sendMessage(msg("Please give a number from 0 to 5.", NamedTextColor.RED));
                }
            }
            case "apples" -> {
                Bot b = needBot(p, args, "Usage: /null bot apples <name> <0-64>");
                if (b == null) return;
                try {
                    b.setApples(Math.max(0, Math.min(64, Integer.parseInt(args[2]))));
                    p.sendMessage(msg(b.name + " apples: " + b.apples, NamedTextColor.GREEN));
                } catch (NumberFormatException e) {
                    p.sendMessage(msg("Please give a number from 0 to 64.", NamedTextColor.RED));
                }
            }
            default -> p.sendMessage(msg("Unknown bot subcommand.", NamedTextColor.RED));
        }
    }

    private void spawn(Player p, String[] args) {
        if (args.length < 2) {
            p.sendMessage(msg("Usage: /null bot spawn <name> [gear] [difficulty] [skin]", NamedTextColor.RED));
            return;
        }
        String name = args[1];
        if (!NAME.matcher(name).matches()) {
            p.sendMessage(msg("Name: 1-16 characters, letters, numbers and _ only.", NamedTextColor.RED));
            return;
        }
        if (bots.containsKey(name.toLowerCase(Locale.ROOT))) {
            p.sendMessage(msg("A bot with that name already exists.", NamedTextColor.RED));
            return;
        }
        if (bots.size() >= MAX_BOTS) {
            p.sendMessage(msg("Bot limit reached (" + MAX_BOTS + ").", NamedTextColor.RED));
            return;
        }

        Bot.Gear gear = Bot.Gear.DIAMOND;
        Kit kit = null;
        Bot.Difficulty diff = Bot.Difficulty.NORMAL;
        try {
            if (args.length > 2) {
                kit = kits.get(args[2].toLowerCase(Locale.ROOT));
                if (kit != null) gear = Bot.Gear.NONE;
                else gear = Bot.Gear.valueOf(args[2].toUpperCase(Locale.ROOT));
            }
            if (args.length > 3) diff = Bot.Difficulty.valueOf(args[3].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            p.sendMessage(msg("Unknown gear, kit or difficulty. Gear: netherite, diamond, iron, none (or a kit name). "
                    + "Difficulty: easy, normal, hard, insane.", NamedTextColor.RED));
            return;
        }
        String skin = args.length > 4 ? args[4] : name;

        Bot bot = spawnBot(p.getUniqueId(), p.getLocation(), name, gear, diff, skin);
        if (kit != null) bot.applyKit(kit);
        p.sendMessage(msg("Bot " + name + " spawned (" + bot.loadoutName() + ", " + diff
                + "). It fights you; use /null bot mode to change that.", NamedTextColor.GREEN));
    }

    private void remove(Player p, String[] args) {
        if (args.length < 2) {
            p.sendMessage(msg("Usage: /null bot remove <name|all>", NamedTextColor.RED));
            return;
        }
        if (args[1].equalsIgnoreCase("all")) {
            int n = bots.size();
            for (Bot b : new ArrayList<>(bots.values())) removeBot(b);
            p.sendMessage(msg("Removed " + n + " bot(s).", NamedTextColor.YELLOW));
            return;
        }
        Bot b = bots.get(args[1].toLowerCase(Locale.ROOT));
        if (b == null) {
            p.sendMessage(msg("Bot not found.", NamedTextColor.RED));
            return;
        }
        removeBot(b);
        p.sendMessage(msg("Removed " + b.name + ".", NamedTextColor.YELLOW));
    }

    private Bot needBot(Player p, String[] args, String usage) {
        if (args.length < 3) {
            p.sendMessage(msg(usage, NamedTextColor.RED));
            return null;
        }
        Bot b = bots.get(args[1].toLowerCase(Locale.ROOT));
        if (b == null) p.sendMessage(msg("Bot not found.", NamedTextColor.RED));
        return b;
    }

    /** @param args everything after "/null bot" (last element = the word being completed) */
    public List<String> complete(String[] args) {
        List<String> out = new ArrayList<>();
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (args.length == 1) {
            out.addAll(List.of("spawn", "remove", "list", "info", "tp", "mode", "difficulty", "gear", "kit", "name", "respawn", "totems", "apples"));
        } else if (sub.equals("kit")) {
            if (args.length == 2) {
                out.addAll(List.of("save", "delete", "list", "give"));
            } else if (args.length == 3 && args[1].equalsIgnoreCase("give")) {
                for (Bot b : bots.values()) out.add(b.name);
            } else if (args.length == 3 && args[1].equalsIgnoreCase("delete")) {
                out.addAll(kits.keySet());
            } else if (args.length == 4 && args[1].equalsIgnoreCase("give")) {
                out.addAll(kits.keySet());
            }
        } else if (args.length == 2 && List.of("remove", "info", "tp", "mode", "difficulty", "gear", "name", "respawn", "totems", "apples").contains(sub)) {
            for (Bot b : bots.values()) out.add(b.name);
            if (sub.equals("remove")) out.add("all");
        } else if (args.length == 3 && sub.equals("spawn")) {
            out.addAll(List.of("netherite", "diamond", "iron", "none"));
            out.addAll(kits.keySet());
        } else if (args.length == 4 && sub.equals("spawn")) {
            out.addAll(List.of("easy", "normal", "hard", "insane"));
        } else if (args.length == 3) {
            switch (sub) {
                case "mode" -> out.addAll(List.of("owner", "nearest", "passive"));
                case "difficulty" -> out.addAll(List.of("easy", "normal", "hard", "insane"));
                case "gear" -> out.addAll(List.of("netherite", "diamond", "iron", "none"));
                case "respawn", "name" -> out.addAll(List.of("on", "off"));
                case "totems" -> out.addAll(List.of("0", "1", "2", "3", "4", "5"));
                case "apples" -> out.addAll(List.of("0", "16", "23", "64"));
                default -> { }
            }
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ------------------------------------------------------------ Events

    private static Player attackerOf(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    /** Hits on the visible body are forwarded to the brain (which owns health, armor and knockback). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBotDamage(EntityDamageEvent e) {
        Bot b = byEntity.get(e.getEntity().getUniqueId());
        if (b == null) return;

        if (e instanceof EntityDamageByEntityEvent ee) {
            Player attacker = attackerOf(ee.getDamager());
            if (attacker != null) b.aggro(attacker, System.currentTimeMillis());
            if (e.getEntity().getUniqueId().equals(b.body.getUniqueId())) {
                b.brain.damage(ee.getDamage(), ee.getDamager());
            }
        }
        if (e.getEntity().getUniqueId().equals(b.body.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        // Lethal hit on the brain: use a totem if there is one (not against /kill or the void).
        if (e.getEntity().getUniqueId().equals(b.brain.getUniqueId())
                && e.getCause() != EntityDamageEvent.DamageCause.KILL
                && e.getCause() != EntityDamageEvent.DamageCause.VOID
                && b.brain.getHealth() - e.getFinalDamage() <= 0
                && b.tryPopTotem()) {
            e.setCancelled(true);
        }
    }

    /** Hurt animation and sound on the visible body. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrainHurt(EntityDamageEvent e) {
        Bot b = byEntity.get(e.getEntity().getUniqueId());
        if (b == null || !e.getEntity().getUniqueId().equals(b.brain.getUniqueId())) return;
        b.body.playHurtAnimation(0f);
        b.body.getWorld().playSound(b.body.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);
    }

    @EventHandler
    public void onBotDeath(EntityDeathEvent e) {
        Bot b = byEntity.get(e.getEntity().getUniqueId());
        if (b == null || !e.getEntity().getUniqueId().equals(b.brain.getUniqueId())) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        b.body.getWorld().playSound(b.body.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1f, 1f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBotTarget(EntityTargetEvent e) {
        if (byEntity.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBotTransform(EntityTransformEvent e) {
        if (byEntity.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBotCombust(EntityCombustEvent e) {
        if (byEntity.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    /** Hurt zombies call reinforcements; do not let bots spawn extra zombies. */
    @EventHandler(ignoreCancelled = true)
    public void onReinforcement(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) return;
        for (Bot b : bots.values()) {
            if (b.brain.getWorld().equals(e.getLocation().getWorld())
                    && b.brain.getLocation().distanceSquared(e.getLocation()) < 24 * 24) {
                e.setCancelled(true);
                return;
            }
        }
    }
}
