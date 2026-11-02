package de.unstablenull.nullplugin;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * A saved set of equipment a bot can wear: four armor pieces, main hand and off hand,
 * plus how many totems and golden apples the bot gets with it.
 */
public final class Kit {

    final String name;
    final ItemStack helmet;
    final ItemStack chest;
    final ItemStack legs;
    final ItemStack boots;
    final ItemStack main;
    final ItemStack off;
    final int totems;
    final int apples;

    Kit(String name, ItemStack helmet, ItemStack chest, ItemStack legs, ItemStack boots,
        ItemStack main, ItemStack off, int totems, int apples) {
        this.name = name;
        this.helmet = copy(helmet);
        this.chest = copy(chest);
        this.legs = copy(legs);
        this.boots = copy(boots);
        this.main = copy(main);
        this.off = copy(off);
        this.totems = Math.max(0, Math.min(5, totems));
        this.apples = Math.max(0, Math.min(64, apples));
    }

    private static ItemStack copy(ItemStack it) {
        return it == null || it.getType().isAir() ? null : it.clone();
    }

    /**
     * Takes what the player wears and holds. Totems and golden apples anywhere in the
     * inventory become the bot's totem and apple count.
     */
    static Kit fromPlayer(String name, Player p) {
        PlayerInventory inv = p.getInventory();
        int totems = 0;
        int apples = 0;
        for (ItemStack it : inv.getStorageContents()) {
            if (it == null) continue;
            if (it.getType() == Material.TOTEM_OF_UNDYING) totems += it.getAmount();
            if (it.getType() == Material.GOLDEN_APPLE || it.getType() == Material.ENCHANTED_GOLDEN_APPLE) {
                apples += it.getAmount();
            }
        }
        ItemStack off = inv.getItemInOffHand();
        if (off.getType() == Material.TOTEM_OF_UNDYING) totems += off.getAmount();
        return new Kit(name, inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots(),
                inv.getItemInMainHand(), off, totems, apples);
    }

    int itemCount() {
        int n = 0;
        for (ItemStack it : new ItemStack[]{helmet, chest, legs, boots, main, off}) {
            if (it != null) n++;
        }
        return n;
    }

    /**
     * @return {armor points, armor toughness, knockback resistance} of the four armor pieces.
     * Protection enchantments are approximated: every level counts as 0.75 armor points.
     */
    double[] armorStats() {
        double armor = 0;
        double toughness = 0;
        double kb = 0;
        int protection = 0;
        for (ItemStack it : new ItemStack[]{helmet, chest, legs, boots}) {
            if (it == null) continue;
            armor += KitStats.armor(it.getType());
            toughness += KitStats.toughness(it.getType());
            kb += KitStats.knockbackResistance(it.getType());
            protection += it.getEnchantmentLevel(Enchantment.PROTECTION);
        }
        armor += protection * 0.75;
        return new double[]{Math.min(30, armor), Math.min(20, toughness), Math.min(1, kb)};
    }

    /** @return {damage of one hit, attack cooldown in milliseconds} of the main hand item. */
    double[] weaponStats() {
        return new double[]{KitStats.damage(main), KitStats.cooldownMs(main)};
    }

    void save(ConfigurationSection sec) {
        sec.set("helmet", helmet);
        sec.set("chest", chest);
        sec.set("legs", legs);
        sec.set("boots", boots);
        sec.set("main", main);
        sec.set("off", off);
        sec.set("totems", totems);
        sec.set("apples", apples);
    }

    static Kit load(String name, ConfigurationSection sec) {
        return new Kit(name, sec.getItemStack("helmet"), sec.getItemStack("chest"), sec.getItemStack("legs"),
                sec.getItemStack("boots"), sec.getItemStack("main"), sec.getItemStack("off"),
                sec.getInt("totems", 0), sec.getInt("apples", 0));
    }
}
