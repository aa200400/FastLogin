package com.github.games647.fastlogin.bukkit.listener;

import com.github.games647.fastlogin.bukkit.BukkitLoginSession;
import com.github.games647.fastlogin.bukkit.FastLoginBukkit;
import com.github.games647.fastlogin.bukkit.task.ForceLoginTask;
import com.github.games647.fastlogin.core.StoredProfile;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent.Result;
import org.bukkit.event.player.PlayerQuitEvent;
import org.geysermc.connector.GeyserConnector;
import org.geysermc.connector.network.session.GeyserSession;
import org.geysermc.floodgate.FloodgateAPI;
import org.geysermc.floodgate.FloodgatePlayer;

/**
 * This listener tells authentication plugins if the player has a premium account and we checked it successfully. So the
 * plugin can skip authentication.
 */
public class ConnectionListener implements Listener {

    private static final long DELAY_LOGIN = 20L / 2;

    private final FastLoginBukkit plugin;

    public ConnectionListener(FastLoginBukkit plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerLogin(PlayerLoginEvent loginEvent) {
        removeBlockedStatus(loginEvent.getPlayer());
        if (loginEvent.getResult() == Result.ALLOWED && !plugin.isServerFullyStarted()) {
            loginEvent.disallow(Result.KICK_OTHER, plugin.getCore().getMessage("not-started"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent joinEvent) {
        Player player = joinEvent.getPlayer();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // session exists so the player is ready for force login
            // cases: Paper (firing BungeeCord message before PlayerJoinEvent) or not running BungeeCord and already
            // having the login session from the login process
            BukkitLoginSession session = plugin.getSession(player.getAddress());
            
            if(Bukkit.getServer().getPluginManager().getPlugin("Geyser-Spigot") != null &&
                    Bukkit.getServer().getPluginManager().getPlugin("floodgate-bukkit") != null) {                
                FloodgatePlayer floodgatePlayer = null;
                
                // check if the player is really connected through Geyser
                for (GeyserSession geyserPlayer : GeyserConnector.getInstance().getPlayers()) {
                    if (geyserPlayer.getName().equals(player.getName())) {
                        // this also returns a floodgatePlayer for linked Java accounts
                        // that's why the Geyser Server's player list also has to be checked
                        //TODO: does this return null if a player is connected through Geyser Offline mode?
                        floodgatePlayer = FloodgateAPI.getPlayer(player.getUniqueId());
                        break;
                    }
                }
                
                if (floodgatePlayer != null) {
                    plugin.getLog().info(
                            "Player {} is connecting through Geyser Floodgate.",
                            player.getName());
                    String allowNameConflict = plugin.getCore().getConfig().getString("allowFloodgateNameConflict");
                    if (allowNameConflict.equalsIgnoreCase("linked") &&
                            floodgatePlayer.fetchLinkedPlayer() == null) {
                        plugin.getLog().info(
                                "Bedrock Player {}'s name conflits an existing Java Premium Player's name",
                                player.getName());
                        player.kickPlayer("This name is allready in use by a Premium Java Player");

                    }
                    if (!allowNameConflict.equalsIgnoreCase("true") && !allowNameConflict.equalsIgnoreCase("linked")) {
                        plugin.getLog().error(
                                "Invalid value detected for 'allowNameConflict' in FasttLogin/config.yml. Aborting login of Player {}",
                                player.getName());
                        return;
                    }
                    
                    StoredProfile profile = plugin.getCore().getStorage().loadProfile(player.getName());

                    // create fake session to make auto login work
                    session = new BukkitLoginSession(player.getName(), profile.isSaved());
                    session.setVerified(true);

                    // TODO: configurate auto login for floodgate players
                    // TODO: fix bug: registering as bedrock player breaks java auto login

                }
            }
            if (session == null) {
                String sessionId = plugin.getSessionId(player.getAddress());
                plugin.getLog().info("No on-going login session for player: {} with ID {}", player, sessionId);
            } else {
                Runnable forceLoginTask = new ForceLoginTask(plugin.getCore(), player, session);
                Bukkit.getScheduler().runTaskAsynchronously(plugin, forceLoginTask);
            }

            plugin.getBungeeManager().markJoinEventFired(player);
            // delay the login process to let auth plugins initialize the player
            // Magic number however as there is no direct event from those plugins
        }, DELAY_LOGIN);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent quitEvent) {
        Player player = quitEvent.getPlayer();

        removeBlockedStatus(player);
        plugin.getCore().getPendingConfirms().remove(player.getUniqueId());
        plugin.getPremiumPlayers().remove(player.getUniqueId());
        plugin.getBungeeManager().cleanup(player);
    }

    private void removeBlockedStatus(Player player) {
        player.removeMetadata(plugin.getName(), plugin);
    }
}
