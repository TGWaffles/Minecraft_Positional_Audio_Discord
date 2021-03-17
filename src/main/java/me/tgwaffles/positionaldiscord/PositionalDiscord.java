package me.tgwaffles.positionaldiscord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.bukkit.ChatColor;
import org.bukkit.Instrument;
import org.bukkit.Location;
import org.bukkit.Note;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import javax.security.auth.login.LoginException;
import java.util.*;
import java.util.logging.Logger;

public class PositionalDiscord extends JavaPlugin implements CommandExecutor, Listener {
    Logger log;
    JDA api;
    HashMap<Player, ArrayList<Player>> nearbyPlayers = new HashMap<>();
    HashMap<UUID, Integer> playerChannels = new HashMap<>();
    HashMap<UUID, HashMap<UUID, Integer>> playerVolumesMap = new HashMap<>();
    HashMap<Integer, UUID> lockedChannels = new HashMap<>();
    AudioForwarder forwarder;
    int factor;

    public void onEnable() {
        this.saveDefaultConfig();
        this.getServer().getPluginManager().registerEvents(this, this);
        log = this.getServer().getLogger();

        EnumSet<GatewayIntent> intents = EnumSet.of(
                // We need messages in guilds to accept commands from users
                GatewayIntent.GUILD_MESSAGES,
                // We need voice states to connect to the voice channel
                GatewayIntent.GUILD_VOICE_STATES
        );
        forwarder = new AudioForwarder(this);
        factor = this.getConfig().getInt("factor");
        try {
            api = JDABuilder.createDefault(this.getConfig().getString("discordToken"), intents)           // Use provided token from command line arguments
                    .addEventListeners(forwarder)  // Start listening with this listener
                    .setActivity(Activity.listening("positional audio!")) // Inform users that we are jammin' it out
                    .setStatus(OnlineStatus.DO_NOT_DISTURB)     // Please don't disturb us while we're jammin'
                    .enableCache(CacheFlag.VOICE_STATE)         // Enable the VOICE_STATE cache to find a user's connected voice channel
                    .build();
            api.awaitReady();
        } catch (LoginException | InterruptedException e) {
            e.printStackTrace();
        }

        new BukkitRunnable() {
            public void run() {
                for (Player player : getServer().getOnlinePlayers()) {
                    nearbyPlayers.put(player, getNearbyPlayers(player));
                }
            }
        }.runTaskTimer(this, 1, 1);
    }

    public void onDisable() {
        for (Guild guild : api.getGuilds()) {
            forwarder.closeGuild(guild);
        }
        api.shutdown();
    }

    public ArrayList<Player> getPlayersInChannel(int channelId) {
        ArrayList<Player> playersInChannel = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : playerChannels.entrySet()) {
            if (entry.getValue() == channelId) {
                Player player = getServer().getPlayer(entry.getKey());
                if (player != null) {
                    playersInChannel.add(player);
                }
            }
        }
        return playersInChannel;
    }

    public void playJingle(Player player, boolean join) {
        if (join) {
            player.playNote(player.getLocation(), Instrument.CHIME, Note.natural(0, Note.Tone.G));
            new BukkitRunnable() {
                public void run() {
                    player.playNote(player.getLocation(),
                            Instrument.CHIME, Note.natural(0, Note.Tone.C));
                }
            }.runTaskLater(this, 5);
        } else {
            player.playNote(player.getLocation(), Instrument.CHIME, Note.natural(0, Note.Tone.C));
            new BukkitRunnable() {
                public void run() {
                    player.playNote(player.getLocation(), Instrument.CHIME, Note.natural(0, Note.Tone.G));
                }
            }.runTaskLater(this, 5);
        }
    }

    public void announcePlayerInChannel(Player player, int channel, boolean joined) {
        ArrayList<Player> otherPlayers = getPlayersInChannel(channel);
        for (Player otherPlayer : otherPlayers) {
            if (joined) {
                otherPlayer.sendMessage(ChatColor.GREEN + player.getName() + " has joined your channel.");
            } else {
                otherPlayer.sendMessage(ChatColor.GOLD + player.getName() + " has left your channel.");
            }
            playJingle(otherPlayer, joined);
        }
        playJingle(player, joined);
        if (joined) {
            StringBuilder senderMessage = new StringBuilder();
            senderMessage.append(ChatColor.GOLD).append("Users In Channel\n").append(ChatColor.YELLOW);
            for (Player otherPlayer : otherPlayers) {
                senderMessage.append(otherPlayer.getName()).append("\n");
            }
            player.sendMessage(senderMessage.toString());
        }
    }

    public boolean handleChannelCommand(Player player, String[] args) {
        UUID userId = player.getUniqueId();
        if (args.length == 0) {
            if (playerChannels.get(userId) != null) {
                int channel = playerChannels.remove(userId);
                announcePlayerInChannel(player, channel, false);
                player.sendMessage(ChatColor.GOLD + "You have left your channel.");
                return true;
            } else {
                player.sendMessage(ChatColor.RED + "You were not in a channel!");
                return false;
            }
        } else if (args.length == 1) {
            String inputChannelString = args[0];
            if (inputChannelString.equalsIgnoreCase("list")) {
                StringBuilder toSend = new StringBuilder();
                toSend.append(ChatColor.GOLD);
                toSend.append("List of players in channels:\n");
                toSend.append(ChatColor.YELLOW);
                for (Map.Entry<UUID, Integer> entry : playerChannels.entrySet()) {
                    Player otherPlayer = getServer().getPlayer(entry.getKey());
                    if (otherPlayer == null) {
                        continue;
                    }
                    int channelId = entry.getValue();
                    toSend.append(otherPlayer.getName()).append(": ").append(channelId);
                    UUID locker = lockedChannels.get(channelId);
                    if (locker != null) {
                        toSend.append(" (locked by ").append(getServer().getOfflinePlayer(locker).getName()).append(")");
                    }
                    toSend.append("\n");
                }
                player.sendMessage(toSend.toString());
                return true;
            } else if (inputChannelString.equalsIgnoreCase("reload")) {
                configReload(player);
                return true;
            } else if (inputChannelString.equalsIgnoreCase("lock")) {
                if (playerChannels.get(userId) == null) {
                    player.sendMessage(ChatColor.RED + "You were not in a channel!");
                    return false;
                }
                int channelId = playerChannels.get(userId);
                if (lockedChannels.get(channelId) != null) {
                    if (lockedChannels.get(channelId) != player.getUniqueId()) {
                        player.sendMessage(ChatColor.RED + "You did not lock that channel!");
                        return true;
                    }
                    lockedChannels.remove(channelId);
                    player.sendMessage(ChatColor.GREEN + "Channel unlocked!");
                    ArrayList<Player> otherPlayers = getPlayersInChannel(channelId);
                    for (Player otherPlayer : otherPlayers) {
                        otherPlayer.sendMessage(ChatColor.YELLOW + player.getName() + " unlocked the channel!");
                    }
                    return true;
                }
                lockedChannels.put(channelId, player.getUniqueId());
                player.sendMessage(ChatColor.GREEN + "Channel locked!");
                ArrayList<Player> otherPlayers = getPlayersInChannel(channelId);
                for (Player otherPlayer : otherPlayers) {
                    otherPlayer.sendMessage(ChatColor.YELLOW + player.getName() + " locked the channel!");
                }
                return true;
            }
            int newChannel;
            try {
                newChannel = Integer.parseInt(inputChannelString);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "That channel could not be interpreted as a number.");
                return false;
            }
            if (playerChannels.get(userId) != null) {
                int channel = playerChannels.get(userId);
                if (newChannel == channel) {
                    player.sendMessage(ChatColor.RED + "You're already in that channel.");
                    return true;
                }
                announcePlayerInChannel(player, channel, false);
            }
            UUID channelLocker = lockedChannels.get(newChannel);
            if (channelLocker != null && channelLocker != player.getUniqueId()) {
                player.sendMessage(ChatColor.RED + "That channel is locked.");
                return true;
            }
            announcePlayerInChannel(player, newChannel, true);
            player.sendMessage(ChatColor.GREEN + "You have joined channel " + newChannel);
            playerChannels.put(userId, newChannel);
            return true;
        } else {
            player.sendMessage(ChatColor.RED + "Invalid number of arguments.");
            return false;
        }
    }

    public boolean handleVolumeCommand(Player player, String[] args) {
        if (args.length != 2) {
            return false;
        }
        String playerName = args[0];
        Player otherPlayer = getServer().getPlayer(playerName);
        if (otherPlayer == null) {
            player.sendMessage(ChatColor.RED + "Player \"" + playerName + "\" isn't a valid online player!");
            return true;
        }
        HashMap<UUID, Integer> playerVolumes = playerVolumesMap.computeIfAbsent(
                player.getUniqueId(), k -> new HashMap<>());
        String volumeString = args[1];
        int newVolume;
        try {
            newVolume = Integer.parseInt(volumeString);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + volumeString + " could not be interpreted as a number.");
            return false;
        }
        if (newVolume < 0 || newVolume > 100) {
            player.sendMessage(ChatColor.RED + "The new volume must be between 0 and 100.");
            return true;
        }
        playerVolumes.put(otherPlayer.getUniqueId(), newVolume);
        player.sendMessage(ChatColor.GREEN + "Set " + otherPlayer.getName() + "'s volume to " +
                newVolume + "%!");
        return true;
    }

    public double getVolumeFor(Player player, Player target) {
        HashMap<UUID, Integer> playerVolumes = playerVolumesMap.get(player.getUniqueId());
        if (playerVolumes == null) {
            return 1;
        }
        Integer playerVolume = playerVolumes.get(target.getUniqueId());
        if (playerVolume != null) {
            return (double) playerVolume / 100;
        } else {
            return 1;
        }
    }

    public void configReload(Player player) {
        this.reloadConfig();
        this.factor = this.getConfig().getInt("factor");
    }

    public boolean onCommand (@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                              String[] args) {
        if (!command.getName().equalsIgnoreCase("volume") &&
                !command.getName().equalsIgnoreCase("radio")) {
            return false;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "You must be a player to execute that command!");
            return true;
        }
        if (command.getName().equalsIgnoreCase("radio")) {
            return handleChannelCommand((Player) sender, args);
        } else if (command.getName().equalsIgnoreCase("volume")) {
            return handleVolumeCommand((Player) sender, args);
        }
        return false;
    }

    public ArrayList<Player> getNearbyPlayers(Player player) {
        ArrayList<Player> players = new ArrayList<>();
        for (Entity e : player.getNearbyEntities(128, 128, 128)) {
            if (e instanceof Player) {
                players.add((Player) e);
            }
        }
        Integer playerChannel = playerChannels.get(player.getUniqueId());
        if (playerChannel != null) {
            for (Player otherPlayer : getPlayersInChannel(playerChannel)) {
                if (otherPlayer != player && !players.contains(otherPlayer)) {
                    players.add(otherPlayer);
                }
            }
        }
        return players;
    }

    public boolean checkSharedChannels(Player player1, Player player2) {
        Integer player1Channel = playerChannels.get(player1.getUniqueId());
        Integer player2Channel = playerChannels.get(player2.getUniqueId());
        if (player1Channel == null || player2Channel == null) {
            return false;
        }
        return player1Channel.equals(player2Channel);
    }

    public double[] getAngularVolume(Player player, Player target) {
        double[] output = new double[2];
        double volumeModifier = getVolumeFor(player, target);
        if (checkSharedChannels(player, target)) {
            output[0] = volumeModifier / 2.2;
            output[1] = volumeModifier / 2.2;
            return output;
        }
        Location loc = target.getLocation();
        Vector toPlayer = loc.toVector().subtract(player.getLocation().toVector()).normalize();
        toPlayer.setY(0);
        Vector lookDirection = player.getEyeLocation().getDirection().normalize();
        lookDirection.setY(0);
        double result = Math.atan2(lookDirection.getX() * toPlayer.getZ() - lookDirection.getZ() * toPlayer.getX(),
                lookDirection.getX() * toPlayer.getZ() + lookDirection.getZ() * toPlayer.getX());
        float angle = (float) ((lookDirection.angle(toPlayer) * 180) / Math.PI);

        double toSet;
        if (angle < 90) {
            toSet = (90 - angle) / 90;
        } else {
            toSet = (angle - 90) / 90;
        }
//        double distanceMultiplier = (180 - player.getLocation().distance(loc)) / 180;
        double distanceMultiplier;
        try {
            distanceMultiplier = Math.min(1, Math.max(0.001, factor / player.getLocation().distance(loc)));
        } catch (IllegalArgumentException e) {
            distanceMultiplier = 0;
        }
        if (distanceMultiplier < 0) {
            distanceMultiplier = 0;
        }
        distanceMultiplier *= volumeModifier;

        double factor = (1 + toSet) / distanceMultiplier;

        if (result < 0) {
            output[0] = 1 / factor;
            output[1] = toSet / factor;
        } else {
            output[0] = toSet / factor;
            output[1] = 1 / factor;
        }
        return output;
    }

}
