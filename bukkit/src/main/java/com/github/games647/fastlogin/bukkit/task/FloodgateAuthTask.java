package com.github.games647.fastlogin.bukkit.task;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import com.github.games647.fastlogin.bukkit.BukkitLoginSession;
import com.github.games647.fastlogin.bukkit.FastLoginBukkit;
import com.github.games647.fastlogin.core.StoredProfile;
import com.github.games647.fastlogin.core.hooks.AuthPlugin;

public class FloodgateAuthTask implements Runnable {

    private final FastLoginBukkit plugin;
    private final Player player;
    private final FloodgatePlayer floodgatePlayer;

    public FloodgateAuthTask(FastLoginBukkit plugin, Player player, FloodgatePlayer floodgatePlayer) {
        this.plugin = plugin;
        this.player = player;
        this.floodgatePlayer = floodgatePlayer;
    }

    @Override
    public void run() {
        plugin.getLog().info(
                "Player {} is connecting through Geyser Floodgate.",
                player.getName());
        String allowNameConflict = plugin.getCore().getConfig().get("allowFloodgateNameConflict").toString().toLowerCase();
        // check if the Bedrock player is linked to a Java account 
        boolean isLinked = floodgatePlayer.getLinkedPlayer() != null;
        if (allowNameConflict.equals("linked") && !isLinked) {
            plugin.getLog().info(
                    "Bedrock Player {}'s name conflits an existing Java Premium Player's name",
                    player.getName());
            
            // kicking must be synchronous
            // https://www.spigotmc.org/threads/asynchronous-player-kick-problem.168580/
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                public void run() {
                    player.kickPlayer("This name is allready in use by a Premium Java Player");
                }
            });
            return;

        }
        
        AuthPlugin<Player> authPlugin = plugin.getCore().getAuthPluginHook();

        String autoLoginFloodgate = plugin.getCore().getConfig().get("autoLoginFloodgate").toString().toLowerCase();
        boolean autoRegisterFloodgate = plugin.getCore().getConfig().getBoolean("autoRegisterFloodgate");
        
        boolean isRegistered;
        try {
            isRegistered = authPlugin.isRegistered(player.getName());
        } catch (Exception e) {
            plugin.getLog().error(
                    "An error has occured while checking if player {} is registered",
                    player.getName());
            return;
        }
        
        if (!isRegistered && !autoRegisterFloodgate) {
            plugin.getLog().info(
                    "Auto registration is disabled for Floodgate players in config.yml");
            return;
        }
        
        // logging in from bedrock for a second time threw an error with UUID
        StoredProfile profile = plugin.getCore().getStorage().loadProfile(player.getName());
        if (profile == null) {
            profile = new StoredProfile(player.getUniqueId(), player.getName(), true, player.getAddress().toString());
        }

        BukkitLoginSession session = new BukkitLoginSession(player.getName(), isRegistered, profile);
        
        // enable auto login based on the value of 'autoLoginFloodgate' in config.yml
        session.setVerified(autoLoginFloodgate.equals("true")
                || (autoLoginFloodgate.equals("linked") && isLinked));

        // run login task
        Runnable forceLoginTask = new ForceLoginTask(plugin.getCore(), player, session);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, forceLoginTask);
    }

}
