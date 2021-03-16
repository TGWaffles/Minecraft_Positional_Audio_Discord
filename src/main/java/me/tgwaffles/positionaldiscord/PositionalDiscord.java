package me.tgwaffles.positionaldiscord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.bukkit.Location;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import javax.security.auth.login.LoginException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.logging.Logger;

public class PositionalDiscord extends JavaPlugin implements CommandExecutor, Listener {
    Logger log;
    JDA api;
    HashMap<Player, ArrayList<Player>> nearbyPlayers = new HashMap<>();
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
        try {
            api = JDABuilder.createDefault(this.getConfig().getString("discordToken"), intents)           // Use provided token from command line arguments
                    .addEventListeners(new AudioForwarder(this))  // Start listening with this listener
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
        api.shutdown();
    }

    public ArrayList<Player> getNearbyPlayers(Player player) {
        ArrayList<Player> players = new ArrayList<>();
        for (Entity e : player.getNearbyEntities(128, 128, 128)) {
            if (e instanceof Player) {
                players.add((Player) e);
            }
        }
        return players;
    }

    public double[] getAngularVolume(Player player, Player target) {
        double[] output = new double[2];
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
        double distanceMultiplier = Math.min(1, Math.max(0.001, 10/player.getLocation().distance(loc)));
        if (distanceMultiplier < 0) {
            distanceMultiplier = 0;
        }

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
