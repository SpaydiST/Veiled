package org.example1.veiled;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnLivingEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import net.kyori.adventure.text.Component;
import me.libraryaddict.disguise.DisguiseAPI;
import me.libraryaddict.disguise.disguisetypes.Disguise;
import me.libraryaddict.disguise.disguisetypes.DisguiseType;
import me.libraryaddict.disguise.disguisetypes.MobDisguise;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import me.libraryaddict.disguise.disguisetypes.TargetedDisguise;
import me.libraryaddict.disguise.disguisetypes.watchers.LivingWatcher;
import me.libraryaddict.disguise.utilities.DisguiseUtilities;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HeadDisguiseListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger("veiled");

    private final JavaPlugin plugin;
    private final Set<UUID> headDisguised = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingHeadUpdates = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingCoordinateTrackerRefreshes = ConcurrentHashMap.newKeySet();
    private final Set<UUID> coordinateTrackerRefreshLogged = ConcurrentHashMap.newKeySet();
    private final Set<ViewerEntityPair> coordinateCorrectionLogged = ConcurrentHashMap.newKeySet();
    private final Map<Integer, UUID> headDisguiseEntityIds = new ConcurrentHashMap<>();
    private final Map<UUID, Component> savedDisplayNames = new ConcurrentHashMap<>();
    private final AtomicBoolean teleportWarningLogged = new AtomicBoolean();
    private final AtomicBoolean coordinateOffsetWarningLogged = new AtomicBoolean();
    private static final AtomicBoolean toolkitOffsetWarningLogged = new AtomicBoolean();
    private final AtomicBoolean trackerRefreshWarningLogged = new AtomicBoolean();
    private PacketListenerAbstract coordinateOffsetPacketListener;
    private CoordinateOffsetBridge coordinateOffsetBridge;

    private boolean autoHeadEnabled;
    private String autoHeadName;
    private int autoHeadTaskId = -1;
    private final Set<UUID> autoHeadWorn = ConcurrentHashMap.newKeySet();
    private final Set<UUID> noNameSpoof = ConcurrentHashMap.newKeySet();

    public HeadDisguiseListener(JavaPlugin plugin) {
        this.plugin = plugin;
        ensureDisguiseValues();
        registerCoordinateOffsetPacketListener();
    }

    private record ViewerEntityPair(UUID entityId, UUID viewerId) {
    }

    private void registerCoordinateOffsetPacketListener() {
        org.bukkit.plugin.Plugin coordinateOffset = Bukkit.getPluginManager().getPlugin("CoordinateOffset");
        if (coordinateOffset == null || !coordinateOffset.isEnabled()) {
            return;
        }

        try {
            coordinateOffsetBridge = CoordinateOffsetBridge.load(coordinateOffset);
            coordinateOffsetPacketListener = new PacketListenerAbstract(PacketListenerPriority.LOWEST) {
                @Override
                public void onPacketSend(PacketSendEvent event) {
                    if (event.isCancelled()) {
                        return;
                    }
                    UUID viewerId = event.getUser().getUUID();
                    if (viewerId == null) {
                        return;
                    }

                    if (!(event.getPacketType() instanceof PacketType.Play.Server packetType)) {
                        return;
                    }

                    // LibsDisguises rewrites source entity packets and sends the resulting
                    // disguise packets silently. Translate the source packet first so LD
                    // constructs the disguise at this viewer's client-side coordinates.
                    translateDisguisedEntityCoordinates(event, packetType);

                    if (packetType == PacketType.Play.Server.JOIN_GAME
                            || packetType == PacketType.Play.Server.RESPAWN
                            || packetType == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
                        scheduleCoordinateResyncForViewer(viewerId);
                        return;
                    }

                }
            };
            PacketEvents.getAPI().getEventManager().registerListener(coordinateOffsetPacketListener);
            LOGGER.info("CoordinateOffset detected - disguised spawn and absolute movement coordinates "
                    + "will be translated before LibsDisguises handles them");
        } catch (Throwable error) {
            coordinateOffsetPacketListener = null;
            coordinateOffsetBridge = null;
            LOGGER.log(Level.WARNING,
                    "CoordinateOffset найден, но его API недоступен через classloader плагина; "
                            + "координаты disguise-пакетов не будут переведены до LibsDisguises.", error);
        }
    }

    private void translateDisguisedEntityCoordinates(PacketSendEvent event, PacketType.Play.Server packetType) {
        Player viewer = event.getPlayer();
        if (viewer == null || coordinateOffsetBridge == null) {
            return;
        }

        int entityId;
        Vector3d position;
        float yaw;
        float pitch;
        Object wrapper;

        if (packetType == PacketType.Play.Server.SPAWN_PLAYER) {
            WrapperPlayServerSpawnPlayer spawn = new WrapperPlayServerSpawnPlayer(event);
            entityId = spawn.getEntityId();
            position = spawn.getPosition();
            yaw = spawn.getYaw();
            pitch = spawn.getPitch();
            wrapper = spawn;
        } else if (packetType == PacketType.Play.Server.SPAWN_ENTITY) {
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(event);
            entityId = spawn.getEntityId();
            position = spawn.getPosition();
            yaw = spawn.getYaw();
            pitch = spawn.getPitch();
            wrapper = spawn;
        } else if (packetType == PacketType.Play.Server.SPAWN_LIVING_ENTITY) {
            WrapperPlayServerSpawnLivingEntity spawn = new WrapperPlayServerSpawnLivingEntity(event);
            entityId = spawn.getEntityId();
            position = spawn.getPosition();
            yaw = spawn.getYaw();
            pitch = spawn.getPitch();
            wrapper = spawn;
        } else if (packetType == PacketType.Play.Server.ENTITY_TELEPORT) {
            WrapperPlayServerEntityTeleport teleport = new WrapperPlayServerEntityTeleport(event);
            entityId = teleport.getEntityId();
            position = teleport.getPosition();
            yaw = teleport.getYaw();
            pitch = teleport.getPitch();
            wrapper = teleport;
        } else if (packetType == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
            WrapperPlayServerEntityPositionSync sync = new WrapperPlayServerEntityPositionSync(event);
            entityId = sync.getId();
            EntityPositionData values = sync.getValues();
            position = values.getPosition();
            yaw = values.getYaw();
            pitch = values.getPitch();
            wrapper = sync;
        } else {
            return;
        }

        UUID disguisedId = headDisguiseEntityIds.get(entityId);
        if (disguisedId == null || viewer.getUniqueId().equals(disguisedId)
                || !headDisguised.contains(disguisedId)) {
            return;
        }

        try {
            Location packetLocation = new Location(viewer.getWorld(), position.getX(), position.getY(),
                    position.getZ(), yaw, pitch);
            Location adjusted = coordinateOffsetBridge.apply(viewer, packetLocation);
            if (adjusted == null) {
                return;
            }

            Vector3d adjustedPosition = new Vector3d(adjusted.getX(), adjusted.getY(), adjusted.getZ());
            if (wrapper instanceof WrapperPlayServerSpawnPlayer spawn) {
                spawn.setPosition(adjustedPosition);
            } else if (wrapper instanceof WrapperPlayServerSpawnEntity spawn) {
                spawn.setPosition(adjustedPosition);
            } else if (wrapper instanceof WrapperPlayServerSpawnLivingEntity spawn) {
                spawn.setPosition(adjustedPosition);
            } else if (wrapper instanceof WrapperPlayServerEntityTeleport teleport) {
                teleport.setPosition(adjustedPosition);
            } else if (wrapper instanceof WrapperPlayServerEntityPositionSync sync) {
                EntityPositionData values = sync.getValues();
                values.setPosition(adjustedPosition);
                sync.setValues(values);
            }
            event.markForReEncode(true);

            ViewerEntityPair pair = new ViewerEntityPair(disguisedId, viewer.getUniqueId());
            if (coordinateCorrectionLogged.add(pair)) {
                LOGGER.info("CoordinateOffset coordinates passed to LibsDisguises: viewer=" + viewer.getName()
                        + ", disguised=" + disguisedId
                        + ", packet=" + packetType.getName()
                        + ", sourcePosition=" + position.getX() + "," + position.getY() + "," + position.getZ()
                        + ", clientPosition=" + adjusted.getX() + "," + adjusted.getY() + "," + adjusted.getZ());
            }
        } catch (Throwable error) {
            if (coordinateOffsetWarningLogged.compareAndSet(false, true)) {
                LOGGER.log(Level.WARNING,
                        "Не удалось передать LibsDisguises координаты CoordinateOffset для "
                                + viewer.getName() + "; disguise packet left unchanged.", error);
            }
        }
    }

    private static final class CoordinateOffsetBridge {
        private final Object api;
        private final Method adaptPlayer;
        private final Method getOffset;
        private final Method applyOffset;
        private final Method isZero;

        private CoordinateOffsetBridge(Object api, Method adaptPlayer, Method getOffset,
                                       Method applyOffset, Method isZero) {
            this.api = api;
            this.adaptPlayer = adaptPlayer;
            this.getOffset = getOffset;
            this.applyOffset = applyOffset;
            this.isZero = isZero;
        }

        private static CoordinateOffsetBridge load(org.bukkit.plugin.Plugin plugin) throws Exception {
            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> entryPoint = Class.forName(
                    "com.jtprince.coordinateoffset.api.CoordinateOffset", true, loader);
            Class<?> apiType = Class.forName(
                    "com.jtprince.coordinateoffset.api.CoordinateOffsetAPI", true, loader);
            Class<?> offsetPlayerType = Class.forName(
                    "com.jtprince.coordinateoffset.adapter.OffsetPlayer", true, loader);
            Class<?> fixedOffsetType = Class.forName(
                    "com.jtprince.coordinateoffset.FixedOffset", true, loader);

            Object api = entryPoint.getMethod("api").invoke(null);
            return new CoordinateOffsetBridge(
                    api,
                    apiType.getMethod("adaptPlayer", Object.class),
                    apiType.getMethod("getOffset", offsetPlayerType),
                    fixedOffsetType.getMethod("apply", Object.class),
                    fixedOffsetType.getMethod("isZero"));
        }

        private Location apply(Player viewer, Location location) throws Exception {
            Object offsetPlayer = adaptPlayer.invoke(api, viewer);
            Object offset = getOffset.invoke(api, offsetPlayer);
            boolean zero = (Boolean) isZero.invoke(offset);
            Location adjusted = (Location) applyOffset.invoke(offset, location);
            return zero ? null : adjusted;
        }
    }

    public void shutdown() {
        PacketListenerAbstract listener = coordinateOffsetPacketListener;
        if (listener == null) {
            return;
        }
        coordinateOffsetPacketListener = null;
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        } catch (Throwable error) {
            LOGGER.log(Level.WARNING, "Не удалось снять PacketEvents listener CoordinateOffset.", error);
        }
    }

    private void scheduleCoordinateResyncForViewer(UUID viewerId) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player viewer = plugin.getServer().getPlayer(viewerId);
            if (viewer == null || !viewer.isOnline()) {
                return;
            }

            for (UUID entityId : headDisguised) {
                Player entity = plugin.getServer().getPlayer(entityId);
                if (entity == null || !entity.isOnline() || entity == viewer
                        || entity.getWorld() != viewer.getWorld()) {
                    continue;
                }

                // Recreate the LD tracker after this viewer's offset changes. The LOWEST
                // packet listener above translates the source spawn before LD rewrites it.
                if (locationForViewer(viewer, entity.getLocation()) == null) {
                    continue;
                }

                Disguise disguise = DisguiseAPI.getDisguise(viewer, entity);
                if (disguise == null) {
                    disguise = DisguiseAPI.getDisguise(entity);
                }
                if (disguise != null) {
                    refreshCoordinateDisguiseTracker(entity, disguise);
                } else if (!isCoordinateOffsetEnabled()) {
                    sendTeleport(entity, viewer);
                }
            }
        });
    }

    private void refreshCoordinateDisguiseTracker(Player entity, Disguise disguise) {
        UUID entityId = entity.getUniqueId();
        if (!pendingCoordinateTrackerRefreshes.add(entityId)) {
            return;
        }

        if (!(disguise instanceof TargetedDisguise targetedDisguise)) {
            pendingCoordinateTrackerRefreshes.remove(entityId);
            if (entity.isOnline() && !isCoordinateOffsetEnabled()) {
                resyncEntity(entity);
            }
            return;
        }

        try {
            DisguiseUtilities.refreshTrackers(targetedDisguise);
            if (coordinateTrackerRefreshLogged.add(entityId)) {
                LOGGER.info("CoordinateOffset tracker recovery requested for disguised player " + entity.getName());
            }
            // LibsDisguises re-adds tracked viewers with a two-tick delay. When
            // CoordinateOffset is active, the LOWEST listener translates that spawn
            // before LibsDisguises builds the disguise packet. Keep the legacy Toolkit
            // position refresh only when CoordinateOffset is absent.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                pendingCoordinateTrackerRefreshes.remove(entityId);
                Player onlineEntity = plugin.getServer().getPlayer(entityId);
                if (onlineEntity != null && onlineEntity.isOnline()
                        && headDisguised.contains(entityId) && !isCoordinateOffsetEnabled()) {
                    resyncEntity(onlineEntity);
                }
            }, 4L);
        } catch (Throwable error) {
            pendingCoordinateTrackerRefreshes.remove(entityId);
            if (trackerRefreshWarningLogged.compareAndSet(false, true)) {
                LOGGER.log(Level.WARNING,
                        "Не удалось обновить трекер маскировки для нового смещения CoordinateOffset.", error);
            }
            if (entity.isOnline() && !isCoordinateOffsetEnabled()) {
                resyncEntity(entity);
            }
        }
    }

    public void configureAutoHead(boolean enabled, String name) {
        this.autoHeadEnabled = enabled;
        this.autoHeadName = name;
        if (autoHeadTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(autoHeadTaskId);
            autoHeadTaskId = -1;
        }
        if (enabled) {
            autoHeadTaskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                    plugin, this::tickAutoHead, 0L, 10L);
        }
    }

    private void tickAutoHead() {
        if (!autoHeadEnabled) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean invisible = player.isInvisible()
                    || player.hasPotionEffect(PotionEffectType.INVISIBILITY);
            UUID id = player.getUniqueId();
            if (invisible) {
                applyAutoHead(player, id, true);
            } else if (autoHeadWorn.contains(id)) {
                removeAutoHead(player, id);
            }
        }
    }

    /**
     * CoordinateOffset translates the original spawn/teleport packets before
     * LibsDisguises rewrites them into disguise packets. Those replacement
     * packets are sent silently, so Veiled must not append a second teleport
     * with a separately calculated position.
     */
    public boolean isHeadDisguised(Player player) {
        return headDisguised.contains(player.getUniqueId());
    }

    public boolean isPlayerHeadDisguised(Player player) {
        return headDisguised.contains(player.getUniqueId()) && headOwnerFromHelmet(player) != null;
    }

    private void applyDisplayName(Player player) {
        String owner = headOwnerFromHelmet(player);
        if (owner == null) {
            return;
        }
        savedDisplayNames.putIfAbsent(player.getUniqueId(), player.displayName());
        player.displayName(Component.text(owner));
    }

    private void restoreDisplayName(Player player) {
        Component original = savedDisplayNames.remove(player.getUniqueId());
        if (original != null) {
            player.displayName(original);
        }
    }

    private String headOwnerFromHelmet(Player player) {
        ItemStack helmet = player.getInventory().getHelmet();
        if (helmet == null || helmet.getType() != Material.PLAYER_HEAD) {
            return null;
        }
        if (!(helmet.getItemMeta() instanceof SkullMeta meta)) {
            return null;
        }
        PlayerProfile profile = meta.getPlayerProfile();
        if (profile != null && profile.getName() != null && !profile.getName().isEmpty()) {
            return profile.getName();
        }
        OfflinePlayer owner = meta.getOwningPlayer();
        String name = owner == null ? null : owner.getName();
        return (name != null && !name.isEmpty()) ? name : null;
    }

    private void resyncEntity(Player player) {
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer == player || viewer.getWorld() != world) {
                continue;
            }
            sendTeleport(player, viewer);
        }
    }

    private void sendTeleport(Player entity, Player viewer) {
        if (entity == viewer || entity.getWorld() != viewer.getWorld()) {
            return;
        }
        if (isCoordinateOffsetEnabled()) {
            // CoordinateOffset transforms the source packet before LibsDisguises
            // creates its replacement packet. A later teleport can move an
            // existing client entity, but it cannot repair a missing spawn.
            return;
        }
        try {
            Location serverLocation = entity.getLocation();
            Location viewerServerLocation = viewer.getLocation();
            double[] toolkitOffset = toolkitOffsetFor(viewer.getUniqueId());
            Location location = locationForViewer(viewer, serverLocation, toolkitOffset);
            if (location == null) {
                return;
            }
            Location viewerClientLocation = locationForViewer(viewer, viewerServerLocation, toolkitOffset);
            if (viewerClientLocation == null) {
                viewerClientLocation = viewerServerLocation;
            }
            double distanceToViewer = Math.sqrt(location.distanceSquared(viewerClientLocation));
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer,
                    new WrapperPlayServerEntityTeleport(entity.getEntityId(),
                            new Vector3d(location.getX(), location.getY(), location.getZ()),
                            location.getYaw(), location.getPitch(), false));
            ViewerEntityPair pair = new ViewerEntityPair(entity.getUniqueId(), viewer.getUniqueId());
            if (coordinateCorrectionLogged.add(pair)) {
                String toolkitOffsetDescription = toolkitOffset == null || toolkitOffset.length < 2
                        ? "none"
                        : toolkitOffset[0] + "," + toolkitOffset[1];
                LOGGER.info("CoordinateOffset position correction sent: viewer=" + viewer.getName()
                        + ", disguised=" + entity.getName()
                        + ", serverPosition=" + serverLocation.getX() + "," + serverLocation.getY()
                        + "," + serverLocation.getZ()
                        + ", toolkitOffset=" + toolkitOffsetDescription
                        + ", viewerClientPosition=" + viewerClientLocation.getX() + ","
                        + viewerClientLocation.getY() + "," + viewerClientLocation.getZ()
                        + ", clientPosition=" + location.getX() + "," + location.getY()
                        + "," + location.getZ() + ", distanceToViewer=" + distanceToViewer);
            }
        } catch (Throwable ignored) {
            if (teleportWarningLogged.compareAndSet(false, true)) {
                LOGGER.log(Level.WARNING, "Не удалось отправить корректирующий телепорт маскировки.", ignored);
            }
        }
    }

    private boolean isCoordinateOffsetEnabled() {
        org.bukkit.plugin.Plugin coordinateOffset = Bukkit.getPluginManager().getPlugin("CoordinateOffset");
        return coordinateOffset != null && coordinateOffset.isEnabled();
    }

    private Location locationForViewer(Player viewer, Location realLocation) {
        return locationForViewer(viewer, realLocation, toolkitOffsetFor(viewer.getUniqueId()));
    }

    private Location locationForViewer(Player viewer, Location realLocation, double[] toolkitOffset) {
        Location viewerLocation = realLocation.clone();
        boolean hasOffset = false;

        if (toolkitOffset != null && toolkitOffset.length >= 2) {
            viewerLocation.subtract(toolkitOffset[0], 0, toolkitOffset[1]);
            hasOffset = toolkitOffset[0] != 0 || toolkitOffset[1] != 0;
        }

        org.bukkit.plugin.Plugin coordinateOffset = Bukkit.getPluginManager().getPlugin("CoordinateOffset");
        if (coordinateOffset != null && coordinateOffset.isEnabled()) {
            CoordinateOffsetBridge bridge = coordinateOffsetBridge;
            if (bridge == null) {
                if (coordinateOffsetWarningLogged.compareAndSet(false, true)) {
                    LOGGER.warning("CoordinateOffset API не удалось загрузить; корректирующий телепорт пропущен.");
                }
                return null;
            }
            try {
                Location adjusted = bridge.apply(viewer, viewerLocation);
                if (adjusted != null) {
                    viewerLocation = adjusted;
                    hasOffset = true;
                }
            } catch (Exception error) {
                if (coordinateOffsetWarningLogged.compareAndSet(false, true)) {
                    Throwable cause = error instanceof InvocationTargetException invocation
                            ? invocation.getTargetException()
                            : error;
                    LOGGER.log(Level.WARNING,
                            "Не удалось получить CoordinateOffset для " + viewer.getName()
                                    + "; корректирующий телепорт пропущен.", cause);
                }
                return null;
            }
        }

        return hasOffset ? viewerLocation : null;
    }

    private static volatile boolean toolkitOffsetLookupInitialized;
    private static volatile boolean toolkitPresent;
    private static volatile Method toolkitOffsetMethod;

    /**
     * Оффсет (x, z), который Toolkit приписывает данному наблюдателю
     * (reflection, чтобы не тянуть Toolkit в hard-depend). null — обфускации
     * нет, у такого наблюдателя модель и так стоит правильно.
     */
    private static double[] toolkitOffsetFor(UUID viewerId) {
        if (!toolkitOffsetLookupInitialized) {
            synchronized (HeadDisguiseListener.class) {
                if (!toolkitOffsetLookupInitialized) {
                    org.bukkit.plugin.Plugin toolkit = Bukkit.getPluginManager().getPlugin("Toolkit");
                    toolkitPresent = toolkit != null && toolkit.isEnabled();
                    if (toolkitPresent) {
                        try {
                            // Paper plugins use isolated classloaders. Resolve Toolkit's optional
                            // bridge from Toolkit itself, not from Veiled's classloader.
                            toolkitOffsetMethod = Class.forName(
                                            "org.example1.toolkit.coords.CoordObfuscator",
                                            true,
                                            toolkit.getClass().getClassLoader())
                                    .getMethod("getOffsetXZ", UUID.class);
                        } catch (ClassNotFoundException missingBridge) {
                            if (toolkitOffsetWarningLogged.compareAndSet(false, true)) {
                                LOGGER.info("Toolkit detected, but its coordinate offset API is unavailable; "
                                        + "Toolkit coordinates will not be included in disguise corrections.");
                            }
                        } catch (Throwable error) {
                            if (toolkitOffsetWarningLogged.compareAndSet(false, true)) {
                                LOGGER.log(Level.WARNING,
                                        "Toolkit detected, but its coordinate offset API could not be loaded.", error);
                            }
                        }
                    }
                    toolkitOffsetLookupInitialized = true;
                }
            }
        }
        Method method = toolkitOffsetMethod;
        if (!toolkitPresent || method == null) {
            return null;
        }
        try {
            return (double[]) method.invoke(null, viewerId);
        } catch (Throwable error) {
            if (toolkitOffsetWarningLogged.compareAndSet(false, true)) {
                LOGGER.log(Level.WARNING, "Failed to read Toolkit's coordinate offset for disguise correction.", error);
            }
            return null;
        }
    }

    private void scheduleResync(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                resyncEntity(player);
            }
        });
    }

    @EventHandler
    public void onEquipmentChange(EntityEquipmentChangedEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!event.getEquipmentChanges().containsKey(EquipmentSlot.HEAD)) {
            return;
        }
        scheduleHeadUpdate(player);
    }

    // Let Paper finish the login/equipment update before LibsDisguises refreshes entity trackers.
    private void scheduleHeadUpdate(Player player) {
        UUID id = player.getUniqueId();
        if (!pendingHeadUpdates.add(id)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pendingHeadUpdates.remove(id);
            Player onlinePlayer = plugin.getServer().getPlayer(id);
            if (onlinePlayer != null && onlinePlayer.isOnline()) {
                updateHeadDisguise(onlinePlayer, onlinePlayer.getInventory().getHelmet());
            }
        });
    }

    public void applyCurrentHelmet(Player player) {
        scheduleHeadUpdate(player);
    }

    private void updateHeadDisguise(Player player, ItemStack newItem) {
        UUID id = player.getUniqueId();
        if (headDisguised.contains(id)) {
            undisguiseHead(player);
        }
        if (newItem == null || newItem.getType() == Material.AIR) {
            return;
        }
        DisguiseType mob = mobHeadType(newItem.getType());
        if (mob != null) {
            disguise(player, new MobDisguise(mob));
            return;
        }
        disguiseAsHeadOwner(player, newItem);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UUID id = player.getUniqueId();
        if (!headDisguised.contains(id)) {
            return;
        }
        ItemStack helmet = player.getInventory().getHelmet();
        if (helmet == null || helmet.getType() == Material.AIR) {
            return;
        }
        if (mobHeadType(helmet.getType()) == null && helmet.getType() != Material.PLAYER_HEAD) {
            return;
        }
        boolean wasAutoHead = autoHeadWorn.remove(id)
                || (helmet.getItemMeta() instanceof SkullMeta sm && isAutoHead(sm));
        undisguiseHead(player);
        player.getInventory().setHelmet(null);
        if (!wasAutoHead) {
            player.getWorld().dropItemNaturally(player.getLocation().add(0, 1.2, 0), helmet);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!autoHeadEnabled) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getModifiedType() != PotionEffectType.INVISIBILITY) {
            return;
        }
        UUID id = player.getUniqueId();
        switch (event.getAction()) {
            case ADDED, CHANGED -> applyAutoHead(player, id, true);
            case REMOVED, CLEARED -> {
                if (event.getOldEffect() != null && event.getOldEffect().getType() == PotionEffectType.INVISIBILITY) {
                    removeAutoHead(player, id);
                }
            }
            default -> {
            }
        }
    }

    private void applyAutoHead(Player player, UUID id, boolean forceInvisible) {
        boolean invisible = forceInvisible
                || player.isInvisible()
                || player.hasPotionEffect(PotionEffectType.INVISIBILITY);
        if (!invisible || autoHeadWorn.contains(id)) {
            return;
        }
        ItemStack current = player.getInventory().getHelmet();
        if (current != null && current.getType() != Material.AIR) {
            return;
        }
        ItemStack head = buildAutoHead();
        if (head == null) {
            return;
        }
        autoHeadWorn.add(id);
        noNameSpoof.add(id);
        player.getInventory().setHelmet(head);
        if (headDisguised.contains(id)) {
            return;
        }
        scheduleResync(player);
    }

    private void removeAutoHead(Player player, UUID id) {
        if (!autoHeadWorn.remove(id)) {
            return;
        }
        ItemStack helmet = player.getInventory().getHelmet();
        if (helmet == null || helmet.getType() != Material.PLAYER_HEAD) {
            return;
        }
        SkullMeta meta = helmet.getItemMeta() instanceof SkullMeta sm ? sm : null;
        if (meta == null || !isAutoHead(meta)) {
            return;
        }
        player.getInventory().setHelmet(null);
        undisguiseHead(player);
    }

    private boolean isAutoHead(SkullMeta meta) {
        if (autoHeadName == null || autoHeadName.isEmpty()) {
            return false;
        }
        PlayerProfile profile = meta.getPlayerProfile();
        return profile != null && autoHeadName.equals(profile.getName());
    }

    private ItemStack buildAutoHead() {
        if (autoHeadName == null || autoHeadName.isEmpty()) {
            return null;
        }
        UUID uuid = UUID.nameUUIDFromBytes(autoHeadName.getBytes(StandardCharsets.UTF_8));
        PlayerProfile profile = Bukkit.createProfile(uuid, autoHeadName);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setPlayerProfile(profile);
        head.setItemMeta(meta);
        return head;
    }

    public void undisguiseHead(Player player) {
        UUID id = player.getUniqueId();
        noNameSpoof.remove(id);
        autoHeadWorn.remove(id);
        boolean changed = headDisguised.remove(id);
        headDisguiseEntityIds.entrySet().removeIf(entry -> id.equals(entry.getValue()));
        if (DisguiseAPI.isDisguised(player)) {
            DisguiseAPI.undisguiseToAll(player);
            changed = true;
        }
        restoreDisplayName(player);
        if (changed) {
            scheduleResync(player);
        }
    }

    private void disguise(Player player, Disguise disguise) {
        try {
            ensureDisguiseValues(disguise.getType());
            disguise.setSelfDisguiseVisible(false);
            if (disguise.getWatcher() instanceof LivingWatcher livingWatcher && !livingWatcher.isMaxHealthSet()) {
                livingWatcher.setMaxHealth(20.0);
            }
            DisguiseAPI.disguiseEntity(player, disguise);
            boolean applied = DisguiseAPI.isDisguised(player);
            if (applied) {
                ProgressionAdvancement.award(player);
                if (disguise instanceof MobDisguise) {
                    ProgressionAdvancement.award(player, "veiled_mob");
                }
                UUID id = player.getUniqueId();
                headDisguised.add(id);
                headDisguiseEntityIds.put(player.getEntityId(), id);
                if (!noNameSpoof.remove(id)) {
                    applyDisplayName(player);
                }
                if (hasOffsetViewer(player)) {
                    refreshCoordinateDisguiseTracker(player, disguise);
                } else {
                    scheduleResync(player);
                }
            } else {
                headDisguised.remove(player.getUniqueId());
            }
            LOGGER.info("Маскировка " + player.getName() + " -> " + disguise.getType() + " [applied=" + applied + "]");
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Не удалось замаскировать " + player.getName()
                    + " как " + disguise.getType() + ": " + ex, ex);
        }
    }

    private boolean hasOffsetViewer(Player entity) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer != entity && viewer.getWorld() == entity.getWorld()
                    && locationForViewer(viewer, entity.getLocation()) != null) {
                return true;
            }
        }
        return false;
    }

    private void disguiseAsHeadOwner(Player player, ItemStack helmet) {
        if (helmet == null || helmet.getType() != Material.PLAYER_HEAD) {
            return;
        }
        if (!(helmet.getItemMeta() instanceof SkullMeta meta)) {
            return;
        }
        String name = headOwnerFromHelmet(player);
        if (name == null || name.isEmpty()) {
            return;
        }
        PlayerProfile profile = meta.getPlayerProfile();
        TextureProperty texture = headTexture(meta);
        PlayerDisguise disguise;
        if (texture != null) {
            UUID skinUuid = profile != null ? profile.getUniqueId() : null;
            if (skinUuid == null) {
                skinUuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
            }
            disguise = new PlayerDisguise(new UserProfile(skinUuid, name, List.of(texture)));
        } else {
            disguise = new PlayerDisguise(name);
        }
        disguise.setNameVisible(false);
        disguise(player, disguise);
    }

    private TextureProperty headTexture(SkullMeta meta) {
        PlayerProfile profile = meta.getPlayerProfile();
        if (profile == null) {
            return null;
        }
        for (ProfileProperty property : profile.getProperties()) {
            if (!"textures".equals(property.getName())) {
                continue;
            }
            String value = property.getValue();
            if (value == null || value.isEmpty()) {
                return null;
            }
            return new TextureProperty("textures", value, property.getSignature());
        }
        return null;
    }

    private DisguiseType mobHeadType(Material material) {
        return switch (material) {
            case SKELETON_SKULL -> DisguiseType.SKELETON;
            case WITHER_SKELETON_SKULL -> DisguiseType.WITHER_SKELETON;
            case ZOMBIE_HEAD -> DisguiseType.ZOMBIE;
            case PIGLIN_HEAD -> DisguiseType.PIGLIN;
            default -> null;
        };
    }

    private void ensureDisguiseValues(DisguiseType... types) {
        DisguiseType[] targetTypes = (types != null && types.length > 0) ? types : DisguiseType.values();
        for (DisguiseType type : targetTypes) {
            if (type == null) {
                continue;
            }
            try {
                Method getEntityInfo = null;
                try {
                    getEntityInfo = type.getClass().getMethod("getEntityInfo");
                } catch (NoSuchMethodException ignored) {
                }
                if (getEntityInfo != null && getEntityInfo.invoke(type) != null) {
                    continue;
                }
                Method setEntityInfo = null;
                for (Method m : type.getClass().getMethods()) {
                    if (m.getName().equals("setEntityInfo") && m.getParameterCount() == 1) {
                        setEntityInfo = m;
                        break;
                    }
                }
                if (setEntityInfo == null) {
                    continue;
                }
                Class<?> paramType = setEntityInfo.getParameterTypes()[0];
                Object values = null;
                try {
                    Method getDv = paramType.getMethod("getDisguiseValues", DisguiseType.class);
                    values = getDv.invoke(null, type);
                } catch (Throwable ignored) {
                }
                if (values == null) {
                    for (Constructor<?> c : paramType.getConstructors()) {
                        if (c.getParameterCount() == 3) {
                            values = c.newInstance(type, 20.0, 120);
                            break;
                        } else if (c.getParameterCount() == 2) {
                            values = c.newInstance(20.0, 120);
                            break;
                        }
                    }
                }
                if (values != null) {
                    setEntityInfo.invoke(type, values);
                    LOGGER.info("Резервный DisguiseValues установлен для " + type.name());
                }
            } catch (Throwable ignored) {
            }
        }
    }
}

