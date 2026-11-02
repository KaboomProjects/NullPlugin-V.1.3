package de.unstablenull.nullplugin;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

/** Turns the items of a kit into the numbers the bot fights with. */
final class KitStats {

    private KitStats() { }

    //                                   leather golden chain iron diamond netherite turtle
    private static final int[] HELMET = {1, 2, 2, 2, 3, 3, 2};
    private static final int[] CHEST = {3, 5, 5, 6, 8, 8, 0};
    private static final int[] LEGS = {2, 3, 4, 5, 6, 6, 0};
    private static final int[] BOOTS = {1, 1, 1, 2, 3, 3, 0};

    private static int tier(String n) {
        if (n.startsWith("LEATHER_")) return 0;
        if (n.startsWith("GOLDEN_")) return 1;
        if (n.startsWith("CHAINMAIL_")) return 2;
        if (n.startsWith("IRON_")) return 3;
        if (n.startsWith("DIAMOND_")) return 4;
        if (n.startsWith("NETHERITE_")) return 5;
        if (n.startsWith("TURTLE_")) return 6;
        return -1;
    }

    private static boolean isArmor(String n) {
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS");
    }

    static double armor(Material m) {
        String n = m.name();
        int t = tier(n);
        if (t < 0) return 0;
        if (n.endsWith("_HELMET")) return HELMET[t];
        if (n.endsWith("_CHESTPLATE")) return CHEST[t];
        if (n.endsWith("_LEGGINGS")) return LEGS[t];
        if (n.endsWith("_BOOTS")) return BOOTS[t];
        return 0;
    }

    static double toughness(Material m) {
        String n = m.name();
        if (!isArmor(n)) return 0;
        if (n.startsWith("DIAMOND_")) return 2;
        if (n.startsWith("NETHERITE_")) return 3;
        return 0;
    }

    static double knockbackResistance(Material m) {
        String n = m.name();
        return isArmor(n) && n.startsWith("NETHERITE_") ? 0.1 : 0;
    }

    /** Damage of one hit with the given weapon, without criticals. */
    static double damage(ItemStack it) {
        if (it == null || it.getType().isAir()) return 1.0;
        String n = it.getType().name();
        double base = 1.0;
        boolean sharpable = false;

        if (n.endsWith("_SWORD")) {
            sharpable = true;
            base = switch (tier(n.replace("WOODEN_", "LEATHER_"))) {
                case 0 -> 4;   // wooden
                case 1 -> 4;   // golden
                case 3 -> 6;   // iron
                case 4 -> 7;   // diamond
                case 5 -> 8;   // netherite
                default -> n.startsWith("STONE_") ? 5 : 4;
            };
        } else if (n.endsWith("_AXE")) {
            sharpable = true;
            base = switch (tier(n.replace("WOODEN_", "LEATHER_"))) {
                case 0 -> 7;
                case 1 -> 7;
                case 3 -> 9;
                case 4 -> 9;
                case 5 -> 10;
                default -> n.startsWith("STONE_") ? 9 : 7;
            };
        } else if (n.equals("TRIDENT")) {
            base = 9;
        } else if (n.equals("MACE")) {
            base = 6;
        }

        if (sharpable) {
            int lvl = it.getEnchantmentLevel(Enchantment.SHARPNESS);
            if (lvl > 0) base += 0.5 * lvl + 0.5;
        }
        return base;
    }

    static long cooldownMs(ItemStack it) {
        if (it == null || it.getType().isAir()) return 500;
        String n = it.getType().name();
        if (n.endsWith("_SWORD")) return 625;
        if (n.endsWith("_AXE")) {
            if (n.startsWith("WOODEN_") || n.startsWith("STONE_")) return 1250;
            if (n.startsWith("IRON_")) return 1100;
            return 1000;
        }
        if (n.equals("TRIDENT")) return 900;
        if (n.equals("MACE")) return 1700;
        return 500;
    }
}
