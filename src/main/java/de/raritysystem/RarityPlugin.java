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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Hängt an jedes Item eine farbige Seltenheit-Zeile (Farbverlauf, optional animiert) im Lore an.
 * Lore sieht man nur im Tooltip (Inventar, Kisten, GUIs) – in der Hand bleibt alles wie normal.
 */
public final class RarityPlugin extends JavaPlugin implements Listener {

    private record Rarity(String id, String display, List<String> patterns,
                          boolean glint, boolean animated, int version) {}

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final List<Rarity> rarities = new ArrayList<>(); // niedrig -> hoch
    private final Map<String, Rarity> byId = new HashMap<>();
    private final Map<Material, Rarity> cache = new EnumMap<>(Material.class);
    private final Set<UUID> viewers = new HashSet<>();
    private Rarity defaultRarity;
    private String format;
    private NamespacedKey key;

    private BukkitTask animTask;
    private long step;
    private double speed;

    @Override
    public void onEnable() {
        key = new NamespacedKey(this, "rarity");
        saveDefaultConfig();
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("RaritySystem aktiv (" + rarities.size() + " Seltenheiten).");
    }

    @Override
    public void onDisable() {
        stopAnimation();
        resetViewers();
    }

    private void load() {
        reloadConfig();
        stopAnimation();
        rarities.clear();
        byId.clear();
        cache.clear();

        format = getConfig().getString("line-format", "<dark_gray>Seltenheit: <rarity>");
        ConfigurationSection sec = getConfig().getConfigurationSection("rarities");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                String display = sec.getString(id + ".display", id);
                Rarity r = new Rarity(id, display, sec.getStringList(id + ".materials"),
                        sec.getBoolean(id + ".glint", false),
                        sec.getBoolean(id + ".animated", false),
                        (display + format).hashCode());
                rarities.add(r);
                byId.put(id, r);
            }
        }
        if (rarities.isEmpty()) {
            Rarity r = new Rarity("common", "<white>COMMON", List.of(), false, false, 0);
            rarities.add(r);
            byId.put(r.id(), r);
        }
        String def = getConfig().getString("default-rarity", rarities.get(0).id());
        defaultRarity = rarities.stream().filter(r -> r.id().equalsIgnoreCase(def)).findFirst()
                .orElse(rarities.get(0));

        if (getConfig().getBoolean("animation.enabled", true)) {
            long interval = Math.max(1, getConfig().getLong("animation.interval-ticks", 3));
            speed = getConfig().getDouble("animation.speed", 0.15);
            animTask = Bukkit.getScheduler().runTaskTimer(this, this::animateTick, interval, interval);
        }
    }

    private void stopAnimation() {
        if (animTask != null) {
            animTask.cancel();
            animTask = null;
        }
    }

    // ---------- Seltenheit bestimmen ----------

    private Rarity rarityOf(Material m) {
        return cache.computeIfAbsent(m, mat -> {
            String name = mat.name();
            for (int i = rarities.size() - 1; i >= 0; i--) { // höchste Stufe zuerst
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

    // ---------- Zeile erzeugen ----------

    private Component line(Rarity r, double phase) {
        String text = r.display().replace("%phase%", String.format(Locale.ROOT, "%.2f", phase));
        return mm.deserialize(format, Placeholder.parsed("rarity", text))
                .decoration(TextDecoration.ITALIC, false);
    }

    private static String markOf(Rarity r) {
        return r.id() + "#" + r.version();
    }

    private static String idOf(String mark) {
        int i = mark.indexOf('#');
        return i < 0 ? mark : mark.substring(0, i);
    }

    /** @return true, wenn das Item verändert wurde */
    private boolean apply(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        Rarity r = rarityOf(item.getType());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String mark = pdc.get(key, PersistentDataType.STRING);
        String want = markOf(r);
        if (want.equals(mark)) return false;

        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        if (mark != null && !lore.isEmpty()) lore.removeLast(); // alte Seltenheitszeile entfernen
        lore.add(line(r, 0));
        meta.lore(lore);
        if (r.glint()) meta.setEnchantmentGlintOverride(true);
        else if (mark != null) meta.setEnchantmentGlintOverride(null);
        pdc.set(key, PersistentDataType.STRING, want);
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

    // ---------- Animation (nur in geöffneten Kisten/GUIs) ----------

    /** Ersetzt nur die letzte Lore-Zeile (= Seltenheit) durch die aktuelle Animationsphase. */
    private boolean setLine(ItemStack item, Map<String, Component> lines) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        String mark = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (mark == null) return false;
        Component c = lines.get(idOf(mark));
        List<Component> lore = meta.lore();
        if (c == null || lore == null || lore.isEmpty()) return false;
        lore = new ArrayList<>(lore);
        lore.set(lore.size() - 1, c);
        meta.lore(lore);
        item.setItemMeta(meta);
        return true;
    }

    private void refresh(Inventory inv, Map<String, Component> lines) {
        if (inv == null) return;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (setLine(s, lines)) inv.setItem(i, s);
        }
    }

    private Map<String, Component> linesAt(double phase) {
        Map<String, Component> lines = new HashMap<>();
        for (Rarity r : rarities) if (r.animated()) lines.put(r.id(), line(r, phase));
        return lines;
    }

    private void animateTick() {
        if (viewers.isEmpty()) return;
        step++;
        Map<String, Component> lines = linesAt(Math.sin(step * speed));
        if (lines.isEmpty()) return;
        for (UUID id : new ArrayList<>(viewers)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                viewers.remove(id);
                continue;
            }
            refresh(p.getOpenInventory().getTopInventory(), lines);
            refresh(p.getInventory(), lines);
        }
    }

    /** Setzt Items auf Phase 0 zurück, damit gleiche Items wieder sauber stacken. */
    private void resetPlayer(Player p, Inventory top) {
        Map<String, Component> zero = linesAt(0);
        if (zero.isEmpty()) return;
        refresh(top, zero);
        refresh(p.getInventory(), zero);
    }

    private void resetViewers() {
        for (UUID id : new ArrayList<>(viewers)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) resetPlayer(p, p.getOpenInventory().getTopInventory());
        }
        viewers.clear();
    }

    // ---------- Events ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        scanPlayer(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (viewers.remove(p.getUniqueId())) resetPlayer(p, p.getOpenInventory().getTopInventory());
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent e) {
        scan(e.getInventory());
        if (e.getPlayer() instanceof Player p) {
            scanPlayer(p);
            if (animTask != null) viewers.add(p.getUniqueId());
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p && viewers.remove(p.getUniqueId())) {
            resetPlayer(p, e.getInventory());
        }
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
            viewers.clear();
            load();
            Bukkit.getOnlinePlayers().forEach(this::scanPlayer);
            sender.sendMessage(Component.text("RaritySystem neu geladen."));
        } else {
            sender.sendMessage(Component.text("/rarity reload"));
        }
        return true;
    }
}
