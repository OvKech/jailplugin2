package com.example.jail;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /jail <player> <duration>  - builds a bedrock cell around the player's current position.
 * /unjail <player>           - removes the cell and restores the original blocks.
 *
 * Cell layout (relative to the player's block): 5x4x5 shell, 3x3x2 interior.
 *   y-1 : floor
 *   y   : interior (player feet)
 *   y+1 : interior (player head)
 *   y+2 : ceiling (sea lantern in the centre for light)
 */
public final class JailPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final Pattern FULL = Pattern.compile("(\\d+[smhd])+");
    private static final Pattern PART = Pattern.compile("(\\d+)([smhd])");
    private static final long MAX_SECONDS = 365L * 24 * 3600;

    private record BlockPos(UUID world, int x, int y, int z) {
        static BlockPos of(Block b) {
            return new BlockPos(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }
    }

    private static final class Jail {
        final UUID playerId;
        final Location spawn;
        final List<BlockState> saved;
        final Set<BlockPos> positions;
        BukkitTask task;

        Jail(UUID playerId, Location spawn, List<BlockState> saved, Set<BlockPos> positions) {
            this.playerId = playerId;
            this.spawn = spawn;
            this.saved = saved;
            this.positions = positions;
        }
    }

    private final Map<UUID, Jail> jails = new HashMap<>();
    private final Set<BlockPos> protectedBlocks = new HashSet<>();
    /** Players released while offline; teleported back to where they were jailed on next join. */
    private final Map<UUID, Location> pendingRelease = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        var jail = getCommand("jail");
        var unjail = getCommand("unjail");
        if (jail != null) { jail.setExecutor(this); jail.setTabCompleter(this); }
        if (unjail != null) { unjail.setExecutor(this); unjail.setTabCompleter(this); }
    }

    @Override
    public void onDisable() {
        // Restore every cell so no bedrock is left behind after a shutdown/reload.
        for (UUID id : new ArrayList<>(jails.keySet())) {
            release(id);
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // Operators (and the console) only - a permission grant alone is not enough.
        if (!(sender instanceof ConsoleCommandSender) && !sender.isOp()) {
            msg(sender, "Only server operators can use this command.", NamedTextColor.RED);
            return true;
        }
        // A jailed player (even an op) can never jail/unjail anyone, including themselves.
        if (sender instanceof Player p && jails.containsKey(p.getUniqueId())) {
            msg(sender, "You can't use this command while you are jailed.", NamedTextColor.RED);
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("jail")) {
            if (args.length != 2) {
                msg(sender, "Usage: /jail <player> <duration>  (e.g. 30s, 10m, 1h30m, 1d)", NamedTextColor.RED);
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                msg(sender, "Player '" + args[0] + "' is not online.", NamedTextColor.RED);
                return true;
            }
            long seconds = parseDuration(args[1]);
            if (seconds <= 0 || seconds > MAX_SECONDS) {
                msg(sender, "Invalid duration. Use e.g. 30s, 10m, 1h30m, 1d (max 365d). A bare number means seconds.",
                        NamedTextColor.RED);
                return true;
            }
            String error = jail(target, seconds);
            if (error != null) {
                msg(sender, error, NamedTextColor.RED);
            } else {
                msg(sender, target.getName() + " has been jailed for " + formatDuration(seconds) + ".",
                        NamedTextColor.GREEN);
                target.sendMessage(Component.text("You have been jailed for " + formatDuration(seconds) + ".",
                        NamedTextColor.RED));
            }
            return true;
        }

        // /unjail
        if (args.length != 1) {
            msg(sender, "Usage: /unjail <player>", NamedTextColor.RED);
            return true;
        }
        UUID id = findJailedId(args[0]);
        if (id == null) {
            msg(sender, "'" + args[0] + "' is not jailed.", NamedTextColor.RED);
            return true;
        }
        release(id);
        msg(sender, args[0] + " has been released.", NamedTextColor.GREEN);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            if (cmd.getName().equalsIgnoreCase("jail")) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
                }
            } else {
                for (UUID id : jails.keySet()) {
                    String name = Bukkit.getOfflinePlayer(id).getName();
                    if (name != null && name.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(name);
                }
            }
        } else if (args.length == 2 && cmd.getName().equalsIgnoreCase("jail")) {
            out.addAll(List.of("30s", "5m", "10m", "1h", "1d"));
        }
        return out;
    }

    // ------------------------------------------------------------------ core logic

    /** @return null on success, otherwise an error message. */
    private String jail(Player target, long seconds) {
        if (jails.containsKey(target.getUniqueId())) {
            return target.getName() + " is already jailed.";
        }

        Location loc = target.getLocation();
        World w = loc.getWorld();
        int cx = loc.getBlockX(), cy = loc.getBlockY(), cz = loc.getBlockZ();

        if (cy - 1 < w.getMinHeight() || cy + 2 >= w.getMaxHeight()) {
            return "Not enough vertical space to build a cell at that location.";
        }

        // Snapshot first (includes inventories/tile entities) and make sure we don't overlap another cell.
        List<BlockState> saved = new ArrayList<>();
        Set<BlockPos> positions = new HashSet<>();
        for (int x = -2; x <= 2; x++) {
            for (int y = -1; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    Block b = w.getBlockAt(cx + x, cy + y, cz + z);
                    BlockPos pos = BlockPos.of(b);
                    if (protectedBlocks.contains(pos)) {
                        return "That location overlaps another jail cell.";
                    }
                    saved.add(b.getState());
                    positions.add(pos);
                }
            }
        }

        // Build the cell.
        for (int x = -2; x <= 2; x++) {
            for (int y = -1; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    Block b = w.getBlockAt(cx + x, cy + y, cz + z);
                    boolean interior = Math.abs(x) <= 1 && Math.abs(z) <= 1 && (y == 0 || y == 1);
                    if (interior) {
                        b.setType(Material.AIR, false);
                    } else if (x == 0 && z == 0 && y == 2) {
                        b.setType(Material.SEA_LANTERN, false);
                    } else {
                        b.setType(Material.BEDROCK, false);
                    }
                }
            }
        }

        Location spawn = new Location(w, cx + 0.5, cy, cz + 0.5, loc.getYaw(), loc.getPitch());
        Jail jail = new Jail(target.getUniqueId(), spawn, saved, positions);
        jails.put(target.getUniqueId(), jail);
        protectedBlocks.addAll(positions);

        target.teleport(spawn, TeleportCause.PLUGIN);
        target.setFallDistance(0);
        target.setVelocity(new org.bukkit.util.Vector(0, 0, 0));

        UUID id = target.getUniqueId();
        jail.task = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (release(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.sendMessage(Component.text("Your jail sentence is over.", NamedTextColor.GREEN));
            }
        }, seconds * 20L);
        return null;
    }

    /** Removes the cell and restores the original blocks. */
    private boolean release(UUID id) {
        Jail jail = jails.remove(id);
        if (jail == null) return false;
        if (jail.task != null) jail.task.cancel();

        // Move the player back to where they were jailed first, so they aren't stuck in restored blocks.
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            p.teleport(jail.spawn, TeleportCause.PLUGIN);
            p.setFallDistance(0);
        } else {
            pendingRelease.put(id, jail.spawn);
        }

        protectedBlocks.removeAll(jail.positions);
        for (BlockState state : jail.saved) {
            state.update(true, false);
        }
        return true;
    }

    private UUID findJailedId(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null && jails.containsKey(online.getUniqueId())) return online.getUniqueId();
        for (UUID id : jails.keySet()) {
            String n = Bukkit.getOfflinePlayer(id).getName();
            if (n != null && n.equalsIgnoreCase(name)) return id;
        }
        return null;
    }

    private boolean isProtected(Block b) {
        return !protectedBlocks.isEmpty() && protectedBlocks.contains(BlockPos.of(b));
    }

    // ------------------------------------------------------------------ listeners

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (isProtected(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (jails.containsKey(e.getPlayer().getUniqueId()) || isProtected(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isProtected);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isProtected);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (isProtected(e.getBlock().getRelative(e.getDirection()))) { e.setCancelled(true); return; }
        for (Block b : e.getBlocks()) {
            if (isProtected(b) || isProtected(b.getRelative(e.getDirection()))) { e.setCancelled(true); return; }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (isProtected(b)) { e.setCancelled(true); return; }
        }
    }

    /** Jailed players can't teleport out by any means (pearls, chorus fruit, /tp ...). Only this plugin moves them. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!jails.containsKey(e.getPlayer().getUniqueId())) return;
        if (e.getCause() != TeleportCause.PLUGIN) e.setCancelled(true);
    }

    /** Jailed players (including operators) can't run any commands, so they can't /unjail themselves or cheat out. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent e) {
        if (jails.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Component.text("You can't use commands while you are jailed.",
                    NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Jail jail = jails.get(e.getPlayer().getUniqueId());
        if (jail != null) e.setRespawnLocation(jail.spawn);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Jail jail = jails.get(id);
        if (jail != null) {
            e.getPlayer().teleport(jail.spawn, TeleportCause.PLUGIN);
            return;
        }
        Location back = pendingRelease.remove(id);
        if (back != null) {
            e.getPlayer().teleport(back, TeleportCause.PLUGIN);
            e.getPlayer().sendMessage(Component.text("Your jail sentence is over.", NamedTextColor.GREEN));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void msg(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(Component.text(text, color));
    }

    /** Accepts "90" (seconds) or combinations like 1d2h30m15s. Returns -1 if invalid. */
    static long parseDuration(String input) {
        String s = input.toLowerCase(Locale.ROOT);
        try {
            if (s.matches("\\d+")) return Long.parseLong(s);
            if (!FULL.matcher(s).matches()) return -1;
            long total = 0;
            Matcher m = PART.matcher(s);
            while (m.find()) {
                long n = Long.parseLong(m.group(1));
                total += switch (m.group(2)) {
                    case "s" -> n;
                    case "m" -> n * 60;
                    case "h" -> n * 3600;
                    default -> n * 86400;
                };
                if (total > MAX_SECONDS) return -1;
            }
            return total;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    static String formatDuration(long seconds) {
        long d = seconds / 86400, h = (seconds % 86400) / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 || sb.isEmpty()) sb.append(s).append("s");
        return sb.toString().trim();
    }
}
