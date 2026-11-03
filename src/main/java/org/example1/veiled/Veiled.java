package org.example1.veiled;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.advancement.AdvancementDisplay;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.TranslationStore;
import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class Veiled extends JavaPlugin implements Listener, TabExecutor {

    private static final String TEAM_NAME = "veiled";
    private static final String CHATTY_VANISHED_METADATA = "vanished";
    private static final String BLUR_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_";
    private static final TextColor DEFAULT_MESSAGE_COLOR = TextColor.color(0xFFFFFF);
    private static final List<String> LANGUAGES = List.of(
            "en_us", "en_gb", "en_au", "en_ca", "en_nz", "en_pt",
            "ru_ru", "uk_ua", "be_by", "kk_kz",
            "de_de", "fr_fr", "fr_ca", "es_es", "es_mx", "es_ar", "pt_br", "pt_pt", "it_it",
            "pl_pl", "cs_cz", "sk_sk", "hu_hu", "ro_ro", "bg_bg", "el_gr", "tr_tr",
            "nl_nl", "nl_be", "sv_se", "no_no", "nn_no", "da_dk", "fi_fi", "is_is",
            "ja_jp", "ko_kr", "zh_cn", "zh_tw", "zh_hk",
            "ar_sa", "he_il", "th_th", "vi_vn", "id_id", "hi_in",
            "et_ee", "lt_lt", "lv_lv", "sr_sp", "hr_hr", "sl_si",
            "ca_es", "eu_es", "gl_es", "cy_gb", "ga_ie", "fil_ph", "sq_al", "mk_mk", "ms_my", "fa_ir",
            "az_az", "ka_ge", "hy_am", "mn_mn"
    );

    private Scoreboard scoreboard;
    private TranslationStore<MessageFormat> translationStore;
    private String displayMode;
    private String invisibleMode;
    private HeadDisguiseListener headDisguiseListener;
    private int blurMin = 3;
    private int blurMax = 16;
    private boolean updateLang = true;
    private Component messagePrefix;
    private TextColor messageColor;
    private boolean toolkitPresent;
    private boolean flectonePulsePresent;
    private boolean hideNameOnClockCompass;
    private String blurHeadName;
    private boolean playerHeadChatSound;
    private String playerHeadChatSoundName;
    private Class<?> toolkitBridgeClass;
    private Method toolkitSubmitActionBar;
    private boolean toolkitBridgeWarningLogged;
    private final Map<UUID, UUID> lastWhisperPartner = new ConcurrentHashMap<>();
    private final Map<UUID, Component> chattyOriginalDisplayNames = new ConcurrentHashMap<>();
    private final Set<UUID> chattyVanishedMetadataPlayers = ConcurrentHashMap.newKeySet();
    private String colorPrefix;
    private String colorName;
    private String colorText;
    private String colorDeathVictim;
    private String colorDeathKiller;
    private String colorAdvancement;
    private String colorWhisper;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        loadTranslations();
        PluginCommand veiledCommand = getCommand("veiled");
        if (veiledCommand != null) {
            veiledCommand.setExecutor(this);
            veiledCommand.setTabCompleter(this);
        }
        scoreboard = getServer().getScoreboardManager().getMainScoreboard();
        Team team = getOrCreateTeam();
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        team.setCanSeeFriendlyInvisibles(false);
        getServer().getOnlinePlayers().forEach(this::addToTeam);
        getServer().getPluginManager().registerEvents(this, this);
        toolkitPresent = getServer().getPluginManager().getPlugin("Toolkit") != null;
        flectonePulsePresent = getServer().getPluginManager().getPlugin("FlectonePulse") != null;
        if (getServer().getPluginManager().getPlugin("LibsDisguises") != null) {
            headDisguiseListener = new HeadDisguiseListener(this);
            getServer().getPluginManager().registerEvents(headDisguiseListener, this);
            getServer().getOnlinePlayers().forEach(headDisguiseListener::applyCurrentHelmet);
            getLogger().info("LibsDisguises detected - head disguise feature enabled");
        } else {
            getLogger().warning("LibsDisguises not found - head disguise feature disabled (install LibsDisguises + PacketEvents and restart)");
        }
    }

    private void loadConfig() {
        reloadConfig();
        displayMode = getConfig().getString("display-mode", "actionbar");
        invisibleMode = getConfig().getString("invisible-mode", "blur");
        blurMin = Math.max(1, getConfig().getInt("blur-length-min", 3));
        blurMax = Math.max(blurMin, getConfig().getInt("blur-length-max", 16));
        updateLang = getConfig().getBoolean("update-lang", true);
        hideNameOnClockCompass = getConfig().getBoolean("hide-name-on-clock-compass", false);
        blurHeadName = getConfig().getString("blur-head", "");
        playerHeadChatSound = getConfig().getBoolean("player-head-chat-sound", true);
        playerHeadChatSoundName = getConfig().getString("player-head-chat-sound-name", "smprevive:classic_hurt");
        messagePrefix = MiniMessage.miniMessage().deserialize(
                getConfig().getString("message-prefix", ""));
        TextColor color = null;
        String colorStr = getConfig().getString("message-color");
        if (colorStr != null) {
            try {
                color = TextColor.fromCSSHexString(colorStr);
            } catch (Exception ignored) {
            }
        }
        messageColor = color == null ? DEFAULT_MESSAGE_COLOR : color;
        colorPrefix = getConfig().getString("colors.prefix", "");
        colorName = getConfig().getString("colors.name", "#55FF55");
        colorText = getConfig().getString("colors.text", "#CCCCCC");
        colorDeathVictim = getConfig().getString("colors.death-victim", "#AAAAAA");
        colorDeathKiller = getConfig().getString("colors.death-killer", "#AAAAAA");
        colorAdvancement = getConfig().getString("colors.advancement", "#55FF55");
        colorWhisper = getConfig().getString("colors.whisper", "#CCCCCC");
    }

    @Override
    public void onDisable() {
        if (headDisguiseListener != null) {
            headDisguiseListener.shutdown();
        }
        if (translationStore != null) {
            GlobalTranslator.translator().removeSource(translationStore);
            translationStore = null;
        }
        if (scoreboard == null) {
            return;
        }
        Team team = scoreboard.getTeam(TEAM_NAME);
        if (team != null) {
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
            getServer().getOnlinePlayers().forEach(player -> team.removeEntry(player.getName()));
        }
    }

    private Team getOrCreateTeam() {
        Team team = scoreboard.getTeam(TEAM_NAME);
        if (team == null) {
            team = scoreboard.registerNewTeam(TEAM_NAME);
        }
        return team;
    }

    private void loadTranslations() {
        File langDir = new File(getDataFolder(), "lang");
        if (!langDir.isDirectory() && !langDir.mkdirs()) {
            getLogger().warning("[veiled] could not create lang directory: " + langDir);
        }
        for (String lang : LANGUAGES) {
            File target = new File(langDir, lang + ".properties");
            if (!updateLang && target.exists()) {
                continue;
            }
            try (InputStream in = getResource("lang/" + lang + ".properties")) {
                if (in != null) {
                    Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "[veiled] could not save default lang file " + lang, e);
            }
        }
        if (translationStore != null) {
            GlobalTranslator.translator().removeSource(translationStore);
        }
        TranslationStore<MessageFormat> store = TranslationStore.messageFormat(Key.key("veiled", "translations"));
        store.defaultLocale(Locale.US);
        File[] files = langDir.listFiles((dir, name) -> name.endsWith(".properties"));
        int loaded = 0;
        if (files != null) {
            for (File file : files) {
                String tag = file.getName().substring(0, file.getName().length() - ".properties".length()).replace('_', '-');
                Locale locale = Locale.forLanguageTag(tag);
                Properties props = new Properties();
                try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                    props.load(reader);
                } catch (IOException e) {
                    getLogger().log(Level.WARNING, "[veiled] could not load lang file " + file.getName(), e);
                    continue;
                }
                props.forEach((key, value) ->
                        store.register(key.toString(), locale, new MessageFormat(value.toString(), locale)));
                loaded++;
            }
        }
        translationStore = store;
        GlobalTranslator.translator().addSource(store);
        getLogger().info("[veiled] loaded " + loaded + " language files");
    }

    private void broadcastLocalized(Component message) {
        getServer().getConsoleSender().sendMessage(localize(message, Locale.US));
        for (Player player : getServer().getOnlinePlayers()) {
            player.sendMessage(localize(message, localeOf(player)));
        }
    }

    private Locale localeOf(Player player) {
        Locale locale = player.locale();
        return locale == null ? Locale.US : locale;
    }

    private Component localize(Component component, Locale locale) {
        return GlobalTranslator.render(component, locale);
    }

    private Component styleMessage(Component message) {
        if (messagePrefix.equals(Component.empty()) || messagePrefix.equals(Component.text(""))) {
            return message.color(messageColor);
        }
        Component prefix = (colorPrefix == null || colorPrefix.isEmpty())
                ? messagePrefix
                : hexComponent(colorPrefix, messagePrefix);
        return prefix.append(message.color(messageColor));
    }

    private String normalizeColorTag(String hex) {
        String trimmed = hex.trim();
        if (trimmed.startsWith("<") && trimmed.endsWith(">")) {
            return trimmed;
        }
        return "<" + trimmed + ">";
    }

    private Component hexComponent(String hex, String text) {
        if (hex == null || hex.isEmpty()) {
            return Component.text(text);
        }
        return MiniMessage.miniMessage().deserialize(
                normalizeColorTag(hex) + "<text>",
                Placeholder.unparsed("text", text));
    }

    private Component hexComponent(String hex, Component text) {
        if (hex == null || hex.isEmpty()) {
            return text;
        }
        return MiniMessage.miniMessage().deserialize(
                normalizeColorTag(hex) + "<text>",
                Placeholder.component("text", text));
    }

    private Component headAndName(String playerName) {
        return headAndName(playerName, colorName);
    }

    private Component headAndName(String playerName, String hexColor) {
        String safeName = playerName.trim();
        String color = (hexColor == null || hexColor.isEmpty()) ? "#55FF55" : hexColor;
        return MiniMessage.miniMessage().deserialize(
                "<head:" + safeName + "> | " + normalizeColorTag(color) + "<bold><name></bold>",
                Placeholder.unparsed("name", safeName));
    }

    private void addToTeam(Player player) {
        Team team = getOrCreateTeam();
        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void prepareInvisibleJoinForChatty(PlayerJoinEvent event) {
        prepareChattyMessageForInvisiblePlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void prepareInvisibleQuitForChatty(PlayerQuitEvent event) {
        prepareChattyMessageForInvisiblePlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void restoreChattyJoinMessageState(PlayerJoinEvent event) {
        restoreChattyMessageState(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void restoreChattyQuitMessageState(PlayerQuitEvent event) {
        restoreChattyMessageState(event.getPlayer());
    }

    private void prepareChattyMessageForInvisiblePlayer(Player player) {
        if (!isInvisible(player)
                || !getServer().getPluginManager().isPluginEnabled("Chatty")) {
            return;
        }

        // Chatty formats its {player} placeholder from the player's display name.
        // Swap it after normal listeners and restore it after Chatty processes the event.
        if ("hide".equalsIgnoreCase(invisibleMode) || flectonePulsePresent) {
            if (!player.hasMetadata(CHATTY_VANISHED_METADATA)) {
                player.setMetadata(CHATTY_VANISHED_METADATA, new FixedMetadataValue(this, true));
                chattyVanishedMetadataPlayers.add(player.getUniqueId());
            }
        }

        if (!"hide".equalsIgnoreCase(invisibleMode)) {
            UUID playerId = player.getUniqueId();
            chattyOriginalDisplayNames.putIfAbsent(playerId, player.displayName());
            player.displayName(namePart(player));
        }
    }

    private void restoreChattyMessageState(Player player) {
        UUID playerId = player.getUniqueId();
        Component originalDisplayName = chattyOriginalDisplayNames.remove(playerId);
        if (originalDisplayName != null) {
            player.displayName(originalDisplayName);
        }
        if (chattyVanishedMetadataPlayers.remove(playerId)) {
            player.removeMetadata(CHATTY_VANISHED_METADATA, this);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        addToTeam(player);
        if (isInvisible(player)) {
            event.joinMessage(null);
            if (!flectonePulsePresent
                    && !getServer().getPluginManager().isPluginEnabled("Chatty")
                    && !"hide".equalsIgnoreCase(invisibleMode)) {
                broadcastLocalized(styleMessage(Component.translatable("veiled.join", namePart(player))));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        lastWhisperPartner.remove(playerId);
        lastWhisperPartner.values().removeIf(playerId::equals);
        if (headDisguiseListener != null) {
            headDisguiseListener.undisguiseHead(player);
        }
        if (isInvisible(player)) {
            event.quitMessage(null);
            if (!flectonePulsePresent
                    && !getServer().getPluginManager().isPluginEnabled("Chatty")
                    && !"hide".equalsIgnoreCase(invisibleMode)) {
                broadcastLocalized(styleMessage(Component.translatable("veiled.quit", namePart(player))));
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Locale locale = (sender instanceof Player player) ? localeOf(player) : Locale.US;
        if (args.length == 0) {
            sender.sendMessage(styleMessage(localize(Component.translatable(
                    "veiled.admin.status",
                    TranslationArgument.component(Component.text(displayMode)),
                    TranslationArgument.component(Component.text(invisibleMode))
            ), locale)));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                loadConfig();
                loadTranslations();
                sender.sendMessage(styleMessage(localize(Component.translatable("veiled.admin.reload"), locale)));
            }
            case "mode" -> {
                if (args.length < 2 || !setDisplayMode(args[1])) {
                    sender.sendMessage(styleMessage(localize(Component.translatable("veiled.admin.usage"), locale)));
                } else {
                    sender.sendMessage(styleMessage(localize(Component.translatable(
                            "veiled.admin.set",
                            TranslationArgument.component(Component.text("display-mode")),
                            TranslationArgument.component(Component.text(displayMode))
                    ), locale)));
                }
            }
            case "invisible" -> {
                if (args.length < 2 || !setInvisibleMode(args[1])) {
                    sender.sendMessage(styleMessage(localize(Component.translatable("veiled.admin.usage"), locale)));
                } else {
                    sender.sendMessage(styleMessage(localize(Component.translatable(
                            "veiled.admin.set",
                            TranslationArgument.component(Component.text("invisible-mode")),
                            TranslationArgument.component(Component.text(invisibleMode))
                    ), locale)));
                }
            }
            default -> sender.sendMessage(styleMessage(localize(Component.translatable("veiled.admin.usage"), locale)));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("veiled.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filterSuggestions(List.of("reload", "mode", "invisible"), args[0]);
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("mode")) {
                return filterSuggestions(List.of("actionbar", "title"), args[1]);
            }
            if (args[0].equalsIgnoreCase("invisible")) {
                return filterSuggestions(List.of("blur", "hide"), args[1]);
            }
        }
        return List.of();
    }

    private static List<String> filterSuggestions(List<String> options, String prefix) {
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.regionMatches(true, 0, prefix, 0, prefix.length())) {
                result.add(option);
            }
        }
        return result;
    }

    private boolean setDisplayMode(String value) {
        if (!"actionbar".equalsIgnoreCase(value) && !"title".equalsIgnoreCase(value)) {
            return false;
        }
        displayMode = value.toLowerCase(Locale.ROOT);
        getConfig().set("display-mode", displayMode);
        saveConfig();
        return true;
    }

    private boolean setInvisibleMode(String value) {
        if (!"blur".equalsIgnoreCase(value) && !"hide".equalsIgnoreCase(value)) {
            return false;
        }
        invisibleMode = value.toLowerCase(Locale.ROOT);
        getConfig().set("invisible-mode", invisibleMode);
        saveConfig();
        return true;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onChat(AsyncChatEvent event) {
        // FlectonePulse owns chat rendering; cancelling AsyncChatEvent here would
        // silently swallow FP's chat (the legacy AsyncPlayerChatEvent never fires).
        if (flectonePulsePresent) {
            return;
        }
        Player player = event.getPlayer();
        if (isInvisible(player)) {
            event.setCancelled(true);
            return;
        }
        String sound = mobChatSound(player);
        if (sound != null) {
            playChatSound(event, player, sound);
            return;
        }
        if (headDisguiseListener == null || !headDisguiseListener.isHeadDisguised(player)) {
            return;
        }
        if (playerHeadChatSound && isWearingPlayerHead(player)) {
            playChatSound(event, player, playerHeadChatSoundName);
            return;
        }
        if (headDisguiseListener.isPlayerHeadDisguised(player)) {
            String owner = getHeadOwner(player);
            if (owner != null) {
                Component coloredOwner = hexComponent(colorName, owner);
                event.renderer(ChatRenderer.viewerUnaware((source, displayName, message) ->
                        Component.translatable("chat.type.text", coloredOwner, hexComponent(colorText, message))));
            }
        }
    }

    private void playChatSound(AsyncChatEvent event, Player player, String sound) {
        event.setCancelled(true);
        if (sound == null || sound.isEmpty()) {
            return;
        }
        getServer().getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                player.getWorld().playSound(player.getLocation(), sound, 0.9f,
                        0.85f + ThreadLocalRandom.current().nextFloat() * 0.3f);
            }
        });
    }

    private boolean isWearingPlayerHead(Player player) {
        ItemStack helmet = player.getInventory().getHelmet();
        return helmet != null && helmet.getType() == Material.PLAYER_HEAD;
    }

    private String mobChatSound(Player player) {
        ItemStack helmet = player.getInventory().getHelmet();
        if (helmet == null) {
            return null;
        }
        return switch (helmet.getType()) {
            case ZOMBIE_HEAD -> "entity.zombie.ambient";
            case PIGLIN_HEAD -> "entity.piglin.ambient";
            case SKELETON_SKULL -> "entity.skeleton.ambient";
            case WITHER_SKELETON_SKULL -> "entity.wither_skeleton.ambient";
            case CREEPER_HEAD -> "entity.creeper.primed";
            case DRAGON_HEAD -> "entity.ender_dragon.ambient";
            default -> null;
        };
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String rawMessage = event.getMessage();
        String[] cmdAndRest = rawMessage.split(" ", 2);
        String cmd = cmdAndRest[0].toLowerCase(Locale.ROOT);
        String rest = cmdAndRest.length > 1 ? cmdAndRest[1].trim() : "";
        switch (cmd) {
            case "/tell", "/msg", "/w", "/whisper" -> handleWhisper(event, player, rawMessage.split(" ", 3), null);
            case "/r", "/reply" -> handleReply(event, player, rest);
            case "/me" -> handleEmote(event, player, rest);
            case "/say" -> {
                if (isInvisible(player) && !flectonePulsePresent) {
                    event.setCancelled(true);
                }
            }
            default -> {
            }
        }
    }

    private void handleWhisper(PlayerCommandPreprocessEvent event, Player sender, String[] parts, Player replyTarget) {
        // FlectonePulse handles /msg /tell /w via its own tell module; don't
        // cancel the event here, otherwise FP's interceptor never runs
        if (flectonePulsePresent) {
            return;
        }
        if (isInvisible(sender)) {
            event.setCancelled(true);
            return;
        }
        if (parts.length < 3 || parts[2].isEmpty()) {
            return;
        }
        Player target = replyTarget != null ? replyTarget : resolvePlayer(parts[1]);
        if (target == null || target == sender) {
            return;
        }
        whisperIntercept(event, sender, parts, target);
    }

    private void whisperIntercept(PlayerCommandPreprocessEvent event, Player sender, String[] parts, Player target) {
        String targetAlias = headOwnerIfDisguised(target);
        String targetName = targetAlias != null ? targetAlias : target.getName();
        if (targetName == null) {
            return;
        }
        if (headOwnerIfDisguised(sender) == null && targetAlias == null) {
            return;
        }
        event.setCancelled(true);
        lastWhisperPartner.put(sender.getUniqueId(), target.getUniqueId());
        lastWhisperPartner.put(target.getUniqueId(), sender.getUniqueId());
        Component whisperText = hexComponent(colorWhisper, parts[2]);
        // Отправитель всегда пишется реальным ником (не маскировкой-головой).
        target.sendMessage(Component.translatable("commands.message.display.incoming",
                whisperName(sender.getName()), whisperText));
        // Получатель остаётся под маскировкой-головой (если она есть), как раньше.
        sender.sendMessage(Component.translatable("commands.message.display.outgoing",
                whisperName(targetName), whisperText));
    }

    private Component whisperName(String playerName) {
        String safe = playerName.trim();
        String color = (colorName == null || colorName.isEmpty()) ? "#55FF55" : colorName;
        return MiniMessage.miniMessage().deserialize(
                "<head:" + safe + "> " + normalizeColorTag(color) + "<name>",
                Placeholder.unparsed("name", safe));
    }

    private void handleReply(PlayerCommandPreprocessEvent event, Player sender, String messageText) {
        if (flectonePulsePresent) {
            return;
        }
        if (isInvisible(sender)) {
            event.setCancelled(true);
            return;
        }
        if (messageText.isEmpty()) {
            return;
        }
        UUID partnerId = lastWhisperPartner.get(sender.getUniqueId());
        Player partner = partnerId == null ? null : getServer().getPlayer(partnerId);
        if (partner == null || !partner.isOnline()) {
            return;
        }
        handleWhisper(event, sender, new String[]{"/r", partner.getName(), messageText}, partner);
    }

    private void handleEmote(PlayerCommandPreprocessEvent event, Player player, String text) {
        if (flectonePulsePresent) {
            return;
        }
        if (isInvisible(player)) {
            event.setCancelled(true);
            return;
        }
        String sound = mobChatSound(player);
        if (sound != null) {
            event.setCancelled(true);
            if (!text.isEmpty()) {
                getServer().getScheduler().runTask(this, () -> {
                    if (player.isOnline()) {
                        player.getWorld().playSound(player.getLocation(), sound, 0.9f,
                                0.85f + ThreadLocalRandom.current().nextFloat() * 0.3f);
                    }
                });
            }
            return;
        }
        if (headDisguiseListener == null || !headDisguiseListener.isHeadDisguised(player)) {
            return;
        }
        String owner = getHeadOwner(player);
        if (owner != null) {
            event.setCancelled(true);
            if (!text.isEmpty()) {
                Component coloredOwner = hexComponent(colorName, owner);
                getServer().broadcast(Component.translatable("chat.type.emote",
                        coloredOwner, hexComponent(colorText, text)));
            }
        }
    }

    private String headOwnerIfDisguised(Player player) {
        if (headDisguiseListener == null || !headDisguiseListener.isHeadDisguised(player)) {
            return null;
        }
        return getHeadOwner(player);
    }

    private Player resolvePlayer(String name) {
        Player exact = getServer().getPlayerExact(name);
        if (exact != null) {
            return exact;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        Player match = null;
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                if (match != null) {
                    return null;
                }
                match = player;
            }
        }
        return match;
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        if (!isInvisible(player)) {
            return;
        }
        AdvancementDisplay display = event.getAdvancement().getDisplay();
        if (display == null || !display.doesAnnounceToChat()) {
            return;
        }
        Boolean announce = player.getWorld().getGameRuleValue(GameRules.SHOW_ADVANCEMENT_MESSAGES);
        if (announce == null || !announce) {
            return;
        }
        if ("hide".equalsIgnoreCase(invisibleMode)) {
            event.message(null);
            return;
        }
        Component advName = event.getAdvancement().displayName();
        HoverEvent<?> hover = advName.style().hoverEvent();
        if (hover == null) {
            Component tooltip = display.title().colorIfAbsent(display.frame().color())
                    .appendNewline()
                    .append(display.description());
            hover = HoverEvent.showText(tooltip);
        }
        if (colorAdvancement != null && !colorAdvancement.isEmpty()) {
            advName = hexComponent(colorAdvancement, advName);
        }
        advName = advName.hoverEvent(hover);
        String key = "chat.type.advancement." + display.frame().name().toLowerCase(Locale.ROOT);
        Component name = blurHeadAndNameChat();
        event.message(Component.translatable(key, name, advName));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player deceased = event.getPlayer();
        if (headDisguiseListener != null) {
            headDisguiseListener.undisguiseHead(deceased);
        }
        Component message = event.deathMessage();
        if (message == null) {
            return;
        }
        boolean modified = false;
        for (Player player : getServer().getOnlinePlayers()) {
            if (!isInvisible(player)) {
                continue;
            }
            String blurColor = player.equals(deceased) ? colorDeathVictim : colorDeathKiller;
            for (String name : nameVariants(player)) {
                Component updated = hideName(message, name, blurColor);
                if (!updated.equals(message)) {
                    message = updated;
                    modified = true;
                }
            }
        }
        if (modified) {
            if ("hide".equalsIgnoreCase(invisibleMode)) {
                event.deathMessage(null);
            } else {
                event.deathMessage(stripEvents(message));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player target)) {
            return;
        }
        Player clicker = event.getPlayer();
        if (clicker.equals(target)) {
            return;
        }
        if (hideNameOnClockCompass && toolkitPresent && isHoldingClockOrCompass(clicker, event.getHand())) {
            return;
        }
        String headOwner = getHeadOwner(target);
        if (headOwner != null) {
            Player owner = findOnlinePlayerByName(headOwner);
            if (owner != null && isInvisible(owner)) {
                sendDisplay(clicker, blurHeadAndName());
            } else {
                sendDisplay(clicker, headAndName(headOwner));
            }
            return;
        }
        if (isMobHead(target)) {
            sendDisplay(clicker, blurHeadAndName());
            return;
        }
        boolean invisible = isInvisible(target);
        if (invisible && "hide".equalsIgnoreCase(invisibleMode)) {
            return;
        }
        if (invisible) {
            sendDisplay(clicker, blurHeadAndName());
        } else {
            sendDisplay(clicker, headAndName(target.getName()));
        }
    }

    private Component blurHeadAndName() {
        return blurHeadAndName(true, "<gradient:#888888:#555555>");
    }

    private Component blurHeadAndNameChat() {
        return blurHeadAndName(false, "<#FFFFFF>");
    }

    private Component blurHeadAndNameChat(String color) {
        String effectiveColor = (color == null || color.isEmpty()) ? "<#FFFFFF>" : normalizeColorTag(color);
        return blurHeadAndName(false, effectiveColor);
    }

    private Component blurHeadAndName(boolean separator, String color) {
        String head = (blurHeadName == null || blurHeadName.isEmpty())
                ? ""
                : "<head:" + blurHeadName.trim() + ">" + (separator ? " | " : " ");
        String colorTag = (color == null || color.isEmpty()) ? "<#FFFFFF>" : normalizeColorTag(color);
        String blur = colorTag + "<bold><obf>" + randomString(blurMin, blurMax) + "</obf></bold>";
        if (head.isEmpty()) {
            return MiniMessage.miniMessage().deserialize(blur);
        }
        return MiniMessage.miniMessage().deserialize("<#FFFFFF>" + head + "</color>" + blur);
    }

    private boolean isHoldingClockOrCompass(Player player, EquipmentSlot hand) {
        ItemStack item = hand == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (item == null) {
            return false;
        }
        Material type = item.getType();
        return type == Material.CLOCK || type == Material.COMPASS;
    }

    private Component namePart(Player player) {
        if (isInvisible(player)) {
            return "hide".equalsIgnoreCase(invisibleMode)
                    ? Component.empty()
                    : blurHeadAndNameChat();
        }
        return hexComponent(colorName, stripEvents(player.displayName()));
    }

    private String randomString(int min, int max) {
        int length = min + ThreadLocalRandom.current().nextInt(max - min + 1);
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            result.append(BLUR_CHARS.charAt(ThreadLocalRandom.current().nextInt(BLUR_CHARS.length())));
        }
        return result.toString();
    }

    private void sendDisplay(Player clicker, Component name) {
        if ("title".equalsIgnoreCase(displayMode)) {
            clicker.showTitle(Title.title(name, Component.empty()));
            return;
        }
        if (!sendActionBarThroughToolkit(clicker, name)) clicker.sendActionBar(name);
    }

    private boolean sendActionBarThroughToolkit(Player player, Component message) {
        Plugin toolkit = getServer().getPluginManager().getPlugin("Toolkit");
        if (toolkit == null || !toolkit.isEnabled()) return false;

        if (toolkitBridgeClass != toolkit.getClass()) {
            toolkitBridgeClass = toolkit.getClass();
            toolkitSubmitActionBar = null;
            try {
                toolkitSubmitActionBar = toolkitBridgeClass.getMethod("submitActionBar", Player.class,
                        String.class, Component.class, int.class, int.class);
            } catch (NoSuchMethodException error) {
                warnToolkitBridge(error);
                return false;
            }
        }
        if (toolkitSubmitActionBar == null) return false;

        try {
            toolkitSubmitActionBar.invoke(toolkit, player, "veiled:player-name", message, 40, 60);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            toolkitSubmitActionBar = null;
            warnToolkitBridge(error);
            return false;
        }
    }

    private void warnToolkitBridge(Exception error) {
        if (toolkitBridgeWarningLogged) return;
        toolkitBridgeWarningLogged = true;
        getLogger().warning("Не удалось подключить ActionBar Veiled к Toolkit; используется прямой вывод: "
                + error.getMessage());
    }

    private String getHeadOwner(Player player) {
        return headOwner(player.getInventory().getHelmet());
    }

    private String headOwner(ItemStack helmet) {
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
        return (name == null || name.isEmpty()) ? null : name;
    }

    private boolean isMobHead(Player player) {
        ItemStack helmet = player.getInventory().getHelmet();
        if (helmet == null) {
            return false;
        }
        return switch (helmet.getType()) {
            case CREEPER_HEAD, DRAGON_HEAD, PIGLIN_HEAD, SKELETON_SKULL, WITHER_SKELETON_SKULL, ZOMBIE_HEAD -> true;
            default -> false;
        };
    }

    private boolean isInvisible(Player player) {
        return player.isInvisible() || player.hasPotionEffect(PotionEffectType.INVISIBILITY);
    }

    private Player findOnlinePlayerByName(String name) {
        if (name == null) {
            return null;
        }
        for (Player player : getServer().getOnlinePlayers()) {
            for (String variant : nameVariants(player)) {
                if (variant.equalsIgnoreCase(name)) {
                    return player;
                }
            }
        }
        return null;
    }

    private Component hideName(Component message, String name, String blurColor) {
        if (message == null || name == null || name.isEmpty()) {
            return message;
        }
        Component replacement = "hide".equalsIgnoreCase(invisibleMode)
                ? Component.empty()
                : blurHeadAndNameChat(blurColor);
        return replaceWholeWord(message, name, replacement);
    }

    private Set<String> nameVariants(Player player) {
        Set<String> names = new HashSet<>();
        String name = player.getName();
        if (name != null && !name.isEmpty()) {
            names.add(name);
        }
        String displayName = PlainTextComponentSerializer.plainText().serialize(player.displayName());
        if (!displayName.isEmpty()) {
            names.add(displayName);
        }
        return names;
    }

    private Component replaceWholeWord(Component message, String name, Component replacement) {
        Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])");
        return message.replaceText(builder -> builder.match(pattern).replacement(replacement).build());
    }

    private Component stripEvents(Component component) {
        if (component == null) {
            return null;
        }
        if (component instanceof TranslatableComponent translatable) {
            List<TranslationArgument> args = translatable.arguments().stream()
                    .map(arg -> arg.value() instanceof Component c
                            ? TranslationArgument.component(stripEvents(c))
                            : arg)
                    .toList();
            return translatable.arguments(args)
                    .style(style -> style.hoverEvent(null).clickEvent(null).insertion(null));
        }
        List<Component> children = component.children().stream().map(this::stripEvents).toList();
        return component.children(children)
                .style(style -> style.hoverEvent(null).clickEvent(null).insertion(null));
    }
}
