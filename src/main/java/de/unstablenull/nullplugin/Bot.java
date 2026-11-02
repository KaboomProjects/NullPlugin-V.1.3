package de.unstablenull.nullplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Random;
import java.util.UUID;

/**
 * One bot = an invisible Zombie ("brain": pathfinding, collision, health, armor values)
 * plus a Mannequin ("body": the player-like look) that is moved onto the brain every tick.
 */
public class Bot {

    public enum Mode { OWNER, NEAREST, PASSIVE }

    public enum Difficulty {
        //      reach  miss  speed  eatBelow critJumps totems apples hitAndRun health extraDmg eatMs
        EASY(2.7, 0.25, 0.25, 0.30, false, 0, 3, false, 20, 0, 1800),
        NORMAL(3.0, 0.08, 0.28, 0.40, true, 1, 16, false, 20, 0, 1500),
        HARD(3.2, 0.01, 0.31, 0.50, true, 3, 23, true, 24, 1, 1300),
        INSANE(3.4, 0.0, 0.34, 0.55, true, 5, 64, true, 30, 3, 1000);

        final double reach;
        final double miss;
        final double speed;
        final double eatBelow;
        final boolean critJumps;
        final int totems;
        final int apples;
        final boolean hitAndRun;
        final double health;
        final double extraDamage;
        final long eatMs;

        Difficulty(double reach, double miss, double speed, double eatBelow, boolean critJumps,
                   int totems, int apples, boolean hitAndRun, double health, double extraDamage, long eatMs) {
            this.reach = reach;
            this.miss = miss;
            this.speed = speed;
            this.eatBelow = eatBelow;
            this.critJumps = critJumps;
            this.totems = totems;
            this.apples = apples;
            this.hitAndRun = hitAndRun;
            this.health = health;
            this.extraDamage = extraDamage;
            this.eatMs = eatMs;
        }
    }

    public enum Gear {
        //       helmet, chest, legs, boots, weapon, damage, armor, toughness, knockbackRes, cooldownMs
        NETHERITE(Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS,
                Material.NETHERITE_BOOTS, Material.NETHERITE_SWORD, 8, 20, 12, 0.4, 625),
        DIAMOND(Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS,
                Material.DIAMOND_BOOTS, Material.DIAMOND_SWORD, 7, 20, 8, 0.0, 625),
        IRON(Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS,
                Material.IRON_BOOTS, Material.IRON_SWORD, 6, 15, 0, 0.0, 625),
        NONE(null, null, null, null, null, 1, 0, 0, 0.0, 500);

        final Material helmet, chest, legs, boots, weapon;
        final double damage, armor, toughness, knockbackRes;
        final long cooldownMs;

        Gear(Material helmet, Material chest, Material legs, Material boots, Material weapon,
             double damage, double armor, double toughness, double knockbackRes, long cooldownMs) {
            this.helmet = helmet;
            this.chest = chest;
            this.legs = legs;
            this.boots = boots;
            this.weapon = weapon;
            this.damage = damage;
            this.armor = armor;
            this.toughness = toughness;
            this.knockbackRes = knockbackRes;
            this.cooldownMs = cooldownMs;
        }
    }

    final String name;
    final UUID owner;
    final Zombie brain;
    final LivingEntity body;
    final String skin;

    Mode mode = Mode.OWNER;
    Difficulty difficulty;
    Gear gear;
    /** Custom loadout; if set it replaces the preset gear. */
    Kit kit;
    boolean showName = false;
    private double damage;
    private long cooldownMs;
    private ItemStack mainHand;
    boolean respawn = false;
    int totems;
    int apples;
    /** Values used again after a respawn. */
    int totemsStart;
    int applesStart;
    /** Last known position, used for respawning. */
    Location lastLoc;

    private final Random rnd = new Random();
    private int age = 0;
    private boolean eating = false;
    private long eatingUntil = 0;
    private long nextEat = 0;
    private long nextAttack = 0;
    private long nextJump = 0;
    private long strafeUntil = 0;
    private long retreatUntil = 0;
    private int strafeDir = 1;
    private UUID retaliateTarget = null;
    private long retaliateUntil = 0;
    private Location stuckRef = null;
    private int stuckCount = 0;

    public Bot(String name, UUID owner, Zombie brain, LivingEntity body, Gear gear, Difficulty difficulty, String skin) {
        this.name = name;
        this.owner = owner;
        this.brain = brain;
        this.body = body;
        this.skin = skin;
        this.totems = difficulty.totems;
        this.apples = difficulty.apples;
        this.totemsStart = this.totems;
        this.applesStart = this.apples;
        applyGear(gear);
        applyDifficulty(difficulty);
        updateName();
    }

    // ------------------------------------------------------------ Setup

    private static void setAttr(LivingEntity e, Attribute a, double v) {
        AttributeInstance inst = e.getAttribute(a);
        if (inst != null) inst.setBaseValue(v);
    }

    public final void applyGear(Gear g) {
        this.gear = g;
        this.kit = null;
        this.damage = g.damage;
        this.cooldownMs = g.cooldownMs;
        // The brain is invisible and carries no items; armor only exists as attribute values.
        setAttr(brain, Attribute.ARMOR, g.armor);
        setAttr(brain, Attribute.ARMOR_TOUGHNESS, g.toughness);
        setAttr(brain, Attribute.KNOCKBACK_RESISTANCE, g.knockbackRes);

        this.mainHand = g.weapon == null ? null : new ItemStack(g.weapon);
        EntityEquipment eq = body.getEquipment();
        if (eq == null) return;
        eq.setHelmet(g.helmet == null ? null : new ItemStack(g.helmet));
        eq.setChestplate(g.chest == null ? null : new ItemStack(g.chest));
        eq.setLeggings(g.legs == null ? null : new ItemStack(g.legs));
        eq.setBoots(g.boots == null ? null : new ItemStack(g.boots));
        eq.setItemInMainHand(mainHand == null ? null : mainHand.clone());
        updateOffhand();
    }

    /** Gives the bot a saved kit: items, combat stats, totems and apples. */
    public void applyKit(Kit k) {
        this.kit = k;
        double[] armor = k.armorStats();
        double[] weapon = k.weaponStats();
        this.damage = weapon[0];
        this.cooldownMs = (long) weapon[1];
        setAttr(brain, Attribute.ARMOR, armor[0]);
        setAttr(brain, Attribute.ARMOR_TOUGHNESS, armor[1]);
        setAttr(brain, Attribute.KNOCKBACK_RESISTANCE, armor[2]);

        this.mainHand = k.main == null ? null : k.main.clone();
        EntityEquipment eq = body.getEquipment();
        if (eq != null) {
            eq.setHelmet(k.helmet == null ? null : k.helmet.clone());
            eq.setChestplate(k.chest == null ? null : k.chest.clone());
            eq.setLeggings(k.legs == null ? null : k.legs.clone());
            eq.setBoots(k.boots == null ? null : k.boots.clone());
            eq.setItemInMainHand(mainHand == null ? null : mainHand.clone());
        }
        setTotems(Math.max(0, Math.min(5, k.totems)));
        setApples(Math.max(0, Math.min(64, k.apples)));
    }

    /** Name of the current loadout for messages. */
    public String loadoutName() {
        return kit != null ? "kit:" + kit.name : gear.name();
    }

    public void setShowName(boolean on) {
        this.showName = on;
        body.setCustomNameVisible(on);
        if (on) updateName();
    }

    public final void applyDifficulty(Difficulty d) {
        double oldMax = maxHealth();
        this.difficulty = d;
        setAttr(brain, Attribute.MOVEMENT_SPEED, d.speed);
        setAttr(brain, Attribute.MAX_HEALTH, d.health);
        double newMax = maxHealth();
        // A higher maximum also heals by the difference, a lower one clamps the current health.
        brain.setHealth(Math.min(newMax, brain.getHealth() + Math.max(0, newMax - oldMax)));
    }

    public void setTotems(int n) {
        this.totems = n;
        this.totemsStart = n;
        updateOffhand();
        updateName();
    }

    public void setApples(int n) {
        this.apples = n;
        this.applesStart = n;
    }

    /** Re-applies totem count and nameplate, e.g. after a respawn. */
    public void refreshVisuals() {
        updateOffhand();
        updateName();
    }

    private void updateOffhand() {
        EntityEquipment eq = body.getEquipment();
        if (eq == null) return;
        if (totems > 0) {
            eq.setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
        } else {
            eq.setItemInOffHand(kit != null && kit.off != null ? kit.off.clone() : null);
        }
    }

    private void updateName() {
        if (!showName) return;
        int hp = (int) Math.ceil(brain.getHealth() + brain.getAbsorptionAmount());
        Component c = Component.text(name + " ", NamedTextColor.WHITE)
                .append(Component.text("\u2665" + hp, NamedTextColor.RED));
        if (totems > 0) c = c.append(Component.text(" T" + totems, NamedTextColor.YELLOW));
        body.customName(c);
    }

    public void aggro(Player p, long now) {
        if (mode == Mode.PASSIVE) return;
        retaliateTarget = p.getUniqueId();
        retaliateUntil = now + 15_000;
    }

    public void remove() {
        brain.remove();
        body.remove();
    }

    /**
     * Called for lethal damage. Pops a totem if one is left.
     *
     * @return true if the bot survives (the damage must be cancelled)
     */
    public boolean tryPopTotem() {
        if (totems <= 0) return false;
        totems--;
        brain.setHealth(1.0);
        brain.setAbsorptionAmount(8);
        brain.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
        Location l = brain.getLocation().add(0, 1, 0);
        brain.getWorld().playSound(l, Sound.ITEM_TOTEM_USE, 1f, 1f);
        brain.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, l, 40, 0.4, 0.6, 0.4, 0.4);
        updateOffhand();
        updateName();
        return true;
    }

    // ------------------------------------------------------------ Targeting

    private boolean valid(Player p) {
        if (p == null || !p.isOnline() || p.isDead()) return false;
        if (!p.getWorld().equals(brain.getWorld())) return false;
        GameMode gm = p.getGameMode();
        return gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE;
    }

    private Player findTarget(long now) {
        if (mode == Mode.PASSIVE) return null;

        if (retaliateTarget != null && now < retaliateUntil) {
            Player r = Bukkit.getPlayer(retaliateTarget);
            if (valid(r)) return r;
        }
        if (mode == Mode.OWNER) {
            Player o = Bukkit.getPlayer(owner);
            return valid(o) ? o : null;
        }
        // NEAREST
        Player best = null;
        double bestD = 32 * 32;
        Location bl = brain.getLocation();
        for (Player p : brain.getWorld().getPlayers()) {
            if (!valid(p)) continue;
            double d = p.getLocation().distanceSquared(bl);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    // ------------------------------------------------------------ AI tick

    /** @return false if the bot is dead/invalid and must be removed. */
    public boolean tick(long now) {
        if (!brain.isValid() || brain.isDead() || !body.isValid()) return false;
        age++;
        Location bl = brain.getLocation();
        lastLoc = bl.clone();

        // Zombies without goals would sink: keep the bot afloat.
        if (brain.isInWater()) {
            Vector v = brain.getVelocity();
            if (v.getY() < 0.1) {
                v.setY(0.12);
                brain.setVelocity(v);
            }
        }

        Player target = findTarget(now);
        float yaw = bl.getYaw();
        float pitch = 0f;
        double dist = Double.MAX_VALUE;
        Location tl = null;

        if (target != null) {
            tl = target.getLocation();
            double dx = tl.getX() - bl.getX();
            double dy = tl.getY() - bl.getY();
            double dz = tl.getZ() - bl.getZ();
            double hor = Math.hypot(dx, dz);
            dist = Math.sqrt(hor * hor + dy * dy);
            yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(hor, 0.0001)));
            brain.setRotation(yaw, pitch);
        }

        if (age % 10 == 0) updateName();
        if (target != null && age % 20 == 0) checkStuck(bl, tl, dist);

        handleEating(now, target, tl, bl);

        if (target != null && !eating) {
            combat(now, target, tl, bl, dist);
        } else if (target == null && age % 20 == 0) {
            brain.getPathfinder().stopPathfinding();
        }

        // Move the visible body onto the brain.
        Location bodyLoc = bl.clone();
        bodyLoc.setYaw(yaw);
        bodyLoc.setPitch(pitch);
        body.teleport(bodyLoc);
        return true;
    }

    /** If the bot has not moved for 4 seconds although the target is far away, it blinks next to it. */
    private void checkStuck(Location bl, Location tl, double dist) {
        if (dist > 5 && stuckRef != null && stuckRef.getWorld().equals(bl.getWorld())
                && stuckRef.distanceSquared(bl) < 0.25) {
            stuckCount++;
        } else {
            stuckCount = 0;
        }
        stuckRef = bl.clone();
        if (stuckCount < 4) return;
        stuckCount = 0;

        for (int i = 0; i < 8; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            Location c = tl.clone().add(Math.cos(a) * 3, 0, Math.sin(a) * 3);
            if (c.getBlock().isPassable()
                    && c.clone().add(0, 1, 0).getBlock().isPassable()
                    && c.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
                brain.teleport(c);
                brain.getWorld().playSound(c, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                return;
            }
        }
    }

    private void handleEating(long now, Player target, Location tl, Location bl) {
        double max = maxHealth();

        if (eating) {
            if (now >= eatingUntil) {
                eating = false;
                apples--;
                nextEat = now + 5000;
                brain.setHealth(Math.min(max, brain.getHealth() + 8));
                brain.setAbsorptionAmount(4);
                brain.getWorld().playSound(bl, Sound.ENTITY_PLAYER_BURP, 1f, 1f);
                EntityEquipment eq = body.getEquipment();
                if (eq != null) eq.setItemInMainHand(mainHand == null ? null : mainHand.clone());
            } else if (target != null && age % 8 == 0) {
                // Run away while eating.
                Vector away = bl.toVector().subtract(tl.toVector()).setY(0);
                if (away.lengthSquared() < 0.0001) away = new Vector(1, 0, 0);
                away.normalize().multiply(8);
                brain.getPathfinder().moveTo(bl.clone().add(away), 1.1);
            }
            return;
        }

        if (apples > 0 && now >= nextEat && brain.getHealth() <= max * difficulty.eatBelow) {
            eating = true;
            eatingUntil = now + difficulty.eatMs;
            EntityEquipment eq = body.getEquipment();
            if (eq != null) eq.setItemInMainHand(new ItemStack(Material.GOLDEN_APPLE));
        }
    }

    private void combat(long now, Player target, Location tl, Location bl, double dist) {
        double reach = difficulty.reach;
        double strafeSpeed = 0.14 + 0.02 * difficulty.ordinal();
        boolean onGround = brain.isOnGround();
        boolean falling = !onGround && brain.getVelocity().getY() < 0;

        Vector toward = tl.toVector().subtract(bl.toVector()).setY(0);
        boolean hasDir = toward.lengthSquared() > 0.0001;
        if (hasDir) toward.normalize();

        // Hit-and-run: step back for a moment after a hit.
        if (now < retreatUntil) {
            if (onGround && hasDir) {
                Vector v = brain.getVelocity();
                v.setX(-toward.getX() * 0.14);
                v.setZ(-toward.getZ() * 0.14);
                brain.setVelocity(v);
            }
            return;
        }

        if (dist > reach + 0.5) {
            // Chase
            if (age % 4 == 0) brain.getPathfinder().moveTo(tl, difficulty.hitAndRun ? 1.15 : 1.0);
        } else {
            // Close combat: strafe around the target.
            brain.getPathfinder().stopPathfinding();
            if (now >= strafeUntil) {
                strafeDir = rnd.nextBoolean() ? 1 : -1;
                strafeUntil = now + 500 + rnd.nextInt(900);
            }
            if (onGround && hasDir) {
                Vector perp = new Vector(-toward.getZ(), 0, toward.getX()).multiply(strafeDir);
                double adj = dist < 2.0 ? -0.06 : (dist > 2.8 ? 0.05 : 0.0);
                Vector v = brain.getVelocity();
                v.setX(perp.getX() * strafeSpeed + toward.getX() * adj);
                v.setZ(perp.getZ() * strafeSpeed + toward.getZ() * adj);
                brain.setVelocity(v);
            }
        }

        // Jump so the next hit is a critical hit (hard difficulty).
        if (difficulty.critJumps && onGround && dist < 3.6 && dist > 1.5 && now >= nextJump) {
            Vector v = brain.getVelocity();
            v.setY(0.42);
            brain.setVelocity(v);
            nextJump = now + 700 + rnd.nextInt(400);
        }

        if (dist <= reach && now >= nextAttack && brain.hasLineOfSight(target)) {
            boolean waitForCrit = difficulty.critJumps && !falling && now < nextAttack + 500;
            if (!waitForCrit) attack(now, target, falling);
        }
    }

    private void attack(long now, Player target, boolean crit) {
        nextAttack = now + cooldownMs;
        body.swingMainHand();
        if (rnd.nextDouble() < difficulty.miss) return;

        double dmg = (damage + difficulty.extraDamage) * (crit ? 1.5 : 1.0);
        target.damage(dmg, brain);

        // Sprint-hit knockback and a short retreat, like a player doing a W-tap.
        if (difficulty.hitAndRun) {
            Vector dir = target.getLocation().toVector().subtract(brain.getLocation().toVector()).setY(0);
            if (dir.lengthSquared() > 0.0001) {
                dir.normalize().multiply(0.35).setY(0.05);
                target.setVelocity(target.getVelocity().add(dir));
            }
            retreatUntil = now + 300;
        }

        Location at = target.getLocation().add(0, 1, 0);
        if (crit) {
            target.getWorld().spawnParticle(Particle.CRIT, at, 12, 0.3, 0.5, 0.3, 0.1);
            target.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f);
        } else {
            target.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 1f);
        }
    }

    private double maxHealth() {
        AttributeInstance a = brain.getAttribute(Attribute.MAX_HEALTH);
        return a != null ? a.getValue() : 20.0;
    }
}
