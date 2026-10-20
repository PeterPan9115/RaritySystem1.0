package de.raritysystem;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Hängt an jedes Item eine farbige "Seltenheit"-Zeile im Lore an.
 * Lore sieht man nur im Tooltip (Inventar, Kisten, GUIs) – in der Hand
 * bleibt alles wie im normalen Minecraft.
 */
public final class RarityPlugin extends JavaPlugin implements Listener {

    private record Rarity(String id, Component line, List<String> patterns) {}

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final List<Rarity> rarities = new ArrayList<>(); // niedrig -> hoch
    private final Map<Material, Rarity> cache = new EnumMap<>(Material.class);
    private Rarity defaultRarity;
    private NamespacedKey key;

    @Override
    public void onEnable() {
        key = new NamespacedKey(this, "rarity");
        saveDefaultConfig();
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("RaritySystem aktiv (" + rarities.size() + " Seltenheiten).");
    }

    private void load() {
        reloadConfig();
        rarities.clear();
        cache.clear();

        String format = getConfig().getString("line-format", "<dark_gray>Seltenheit: <rarity>");
        ConfigurationSection sec = getConfig().getConfigurationSection("rarities");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                String display = sec.getString(id + ".display", id);
                Component line = mm.deserialize(format, Placeholder.parsed("rarity", display))
                        .decoration(TextDecoration.ITALIC, false);
                rarities.add(new Rarity(id, line, sec.getStringList(id + ".materials")));
            }
        }
        if (rarities.isEmpty()) {
            rarities.add(new Rarity("common",
                    mm.deserialize("<white>COMMON").decoration(TextDecoration.ITALIC, false), List.of()));
        }
        String def = getConfig().getString("default-rarity", rarities.get(0).id());
        defaultRarity = rarities.stream().filter(r -> r.id().equalsIgnoreCase(def)).findFirst()
                .orElse(rarities.get(0));
    }

    private Rarity rarityOf(Material m) {
        return cache.computeIfAbsent(m, mat -> {
            String name = mat.name();
            for (int i = rarities.size() - 1; i >= 0; i--) { // höchste zuerst
                for (String p : rarities.get(i).patterns()) {
                    if (matches(p.toUpperCase(Locale.ROOT), name)) return rarities.get(i);
                }
            }
            return defaultRarity;
        });
    }

    private static boolean matches(String pattern, String name) {
        if (pattern.startsWith("*")) return name.endsWith(pattern.substring(1));
        if (pattern.endsWith("*")) return name.startsWith(pattern.substring(0, pattern.length() - 1));
        return pattern.equals(name);
    }

    /** @return true, wenn das Item verändert wurde */
    private boolean apply(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        Rarity r = rarityOf(item.getType());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String mark = pdc.get(key, PersistentDataType.STRING);
        if (r.id().equals(mark)) return false;

        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        if (mark != null && !lore.isEmpty()) lore.removeLast(); // alte Seltenheitszeile entfernen
        lore.add(r.line());
        meta.lore(lore);
        pdc.set(key, PersistentDataType.STRING, r.id());
        item.setItemMeta(meta);
        return true;
    }

    private void scan(Inventory inv) {
        if (inv == null) return;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (apply(s)) inv.setItem(i, s);
        }
    }

    private void scanPlayer(Player p) {
        scan(p.getInventory());
        ItemStack cursor = p.getItemOnCursor();
        if (apply(cursor)) p.setItemOnCursor(cursor);
    }

    // ---------- Events ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        scanPlayer(e.getPlayer());
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent e) {
        scan(e.getInventory());
        if (e.getPlayer() instanceof Player p) scanPlayer(p);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Item entity = e.getItem();
        ItemStack s = entity.getItemStack();
        if (apply(s)) entity.setItemStack(s);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Bukkit.getScheduler().runTask(this, () -> {
            scanPlayer(p);
            scan(p.getOpenInventory().getTopInventory());
        });
    }

    @EventHandler
    public void onCreative(InventoryCreativeEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Bukkit.getScheduler().runTask(this, () -> scanPlayer(p));
    }

    // ---------- Command ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("raritysystem.admin")) {
            sender.sendMessage(Component.text("Keine Berechtigung."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            load();
            Bukkit.getOnlinePlayers().forEach(this::scanPlayer);
            sender.sendMessage(Component.text("RaritySystem neu geladen."));
        } else {
            sender.sendMessage(Component.text("/rarity reload"));
        }
        return true;
    }
}
