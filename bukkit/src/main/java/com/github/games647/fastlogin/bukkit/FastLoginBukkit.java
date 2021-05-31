/*
 * SPDX-License-Identifier: MIT
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015-2021 <Your name and contributors>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.github.games647.fastlogin.bukkit;

import com.github.games647.fastlogin.bukkit.command.CrackedCommand;
import com.github.games647.fastlogin.bukkit.command.CracklistCommand;
import com.github.games647.fastlogin.bukkit.command.PremiumCommand;
import com.github.games647.fastlogin.bukkit.listener.ConnectionListener;
import com.github.games647.fastlogin.bukkit.listener.PaperPreLoginListener;
import com.github.games647.fastlogin.bukkit.listener.protocollib.ProtocolLibListener;
import com.github.games647.fastlogin.bukkit.listener.protocollib.SkinApplyListener;
import com.github.games647.fastlogin.bukkit.listener.protocolsupport.ProtocolSupportListener;
import com.github.games647.fastlogin.bukkit.task.DelayedAuthHook;
import com.github.games647.fastlogin.core.CommonUtil;
import com.github.games647.fastlogin.core.PremiumStatus;
import com.github.games647.fastlogin.core.shared.FastLoginCore;
import com.github.games647.fastlogin.core.shared.PlatformPlugin;

import io.papermc.lib.PaperLib;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.bukkit.Bukkit;
import org.bukkit.Server.Spigot;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.floodgate.api.FloodgateApi;
import org.slf4j.Logger;

/**
 * This plugin checks if a player has a paid account and if so tries to skip offline mode authentication.
 */
public class FastLoginBukkit extends JavaPlugin implements PlatformPlugin<CommandSender> {

    //1 minutes should be enough as a timeout for bad internet connection (Server, Client and Mojang)
    private final ConcurrentMap<String, BukkitLoginSession> loginSession = CommonUtil.buildCache(1, -1);
    private final Map<UUID, PremiumStatus> premiumPlayers = new ConcurrentHashMap<>();
    private final Logger logger;

    private boolean serverStarted;
    private BungeeManager bungeeManager;
    private final BukkitScheduler scheduler;
    private FastLoginCore<Player, CommandSender, FastLoginBukkit> core;

    private PremiumPlaceholder premiumPlaceholder;

    public FastLoginBukkit() {
        this.logger = CommonUtil.createLoggerFromJDK(getLogger());
        this.scheduler = new BukkitScheduler(this, logger, getThreadFactory());
    }

    @Override
    public void onEnable() {
        core = new FastLoginCore<>(this);
        core.load();

        if (getServer().getOnlineMode()) {
            //we need to require offline to prevent a loginSession request for a offline player
            logger.error("Server has to be in offline mode");
            setEnabled(false);
            return;
        }
        
		// Check Floodgate config values
		if (!isValidFloodgateConfigString("autoLoginFloodgate")
				|| !isValidFloodgateConfigString("allowFloodgateNameConflict")) {
			setEnabled(false);
			return;
		}

        bungeeManager = new BungeeManager(this);
        bungeeManager.initialize();
        
        PluginManager pluginManager = getServer().getPluginManager();
        if (bungeeManager.isEnabled()) {
            markInitialized();
        } else {
            if (!core.setupDatabase()) {
                setEnabled(false);
                return;
            }

            if (pluginManager.isPluginEnabled("ProtocolSupport")) {
                pluginManager.registerEvents(new ProtocolSupportListener(this, core.getRateLimiter()), this);
            } else if (pluginManager.isPluginEnabled("ProtocolLib")) {
                ProtocolLibListener.register(this, core.getRateLimiter());

                //if server is using paper - we need to set the skin at pre login anyway, so no need for this listener
                if (!PaperLib.isPaper() && getConfig().getBoolean("forwardSkin")) {
                    pluginManager.registerEvents(new SkinApplyListener(this), this);
                }
            } else {
                logger.warn("Either ProtocolLib or ProtocolSupport have to be installed if you don't use BungeeCord");
            }
        }

        //delay dependency setup because we load the plugin very early where plugins are initialized yet
        getServer().getScheduler().runTaskLater(this, new DelayedAuthHook(this), 5L);

        pluginManager.registerEvents(new ConnectionListener(this), this);

        //if server is using paper - we need to add one more listener to correct the usercache usage
        if (PaperLib.isPaper()) {
            pluginManager.registerEvents(new PaperPreLoginListener(this), this);
        }

        //register commands using a unique name
        getCommand("premium").setExecutor(new PremiumCommand(this));
        getCommand("cracked").setExecutor(new CrackedCommand(this));
        getCommand("cracklist").setExecutor(new CracklistCommand(this));

        if (pluginManager.isPluginEnabled("PlaceholderAPI")) {
            premiumPlaceholder = new PremiumPlaceholder(this);
            premiumPlaceholder.register();
        }

        dependencyWarnings();
    }

    @Override
    public void onDisable() {
        loginSession.clear();
        premiumPlayers.clear();

        if (core != null) {
            core.close();
        }

        bungeeManager.cleanup();
        if (premiumPlaceholder != null && getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                premiumPlaceholder.unregister();
            } catch (Exception | NoSuchMethodError exception) {
                logger.error("Failed to unregister placeholder", exception);
            }
        }
    }

    public FastLoginCore<Player, CommandSender, FastLoginBukkit> getCore() {
        return core;
    }

    /**
     * Gets a thread-safe map about players which are connecting to the server are being checked to be premium (paid
     * account)
     *
     * @return a thread-safe loginSession map
     */
    public ConcurrentMap<String, BukkitLoginSession> getLoginSessions() {
        return loginSession;
    }

    public BukkitLoginSession getSession(InetSocketAddress addr) {
        String id = getSessionId(addr);
        return loginSession.get(id);
    }

    public String getSessionId(InetSocketAddress addr) {
        return addr.getAddress().getHostAddress() + ':' + addr.getPort();
    }

    public void putSession(InetSocketAddress addr, BukkitLoginSession session) {
        String id = getSessionId(addr);
        loginSession.put(id, session);
    }

    public void removeSession(InetSocketAddress addr) {
        String id = getSessionId(addr);
        loginSession.remove(id);
    }

    public Map<UUID, PremiumStatus> getPremiumPlayers() {
        return premiumPlayers;
    }

    /**
     * Fetches the premium status of an online player.
     *
     * @param onlinePlayer
     * @return the online status or unknown if an error happened, the player isn't online or BungeeCord doesn't send
     * us the status message yet (This means you cannot check the login status on the PlayerJoinEvent).
     */
    public PremiumStatus getStatus(UUID onlinePlayer) {
        return premiumPlayers.getOrDefault(onlinePlayer, PremiumStatus.UNKNOWN);
    }

    /**
     * Wait before the server is fully started. This is workaround, because connections right on startup are not
     * injected by ProtocolLib
     *
     * @return true if ProtocolLib can now intercept packets
     */
    public boolean isServerFullyStarted() {
        return serverStarted;
    }

    public void markInitialized() {
        this.serverStarted = true;
    }

    public BungeeManager getBungeeManager() {
        return bungeeManager;
    }

    @Override
    public Path getPluginFolder() {
        return getDataFolder().toPath();
    }

    @Override
    public Logger getLog() {
        return logger;
    }

    @Override
    public BukkitScheduler getScheduler() {
        return scheduler;
    }

    @Override
    public void sendMessage(CommandSender receiver, String message) {
        receiver.sendMessage(message);
    }
    
	/**
	 * Checks if a config entry (related to Floodgate) is valid. <br>
	 * Writes to Log if the value is invalid.
	 * <p>
	 * This should be used for:
	 * <ul>
	 * <li>allowFloodgateNameConflict
	 * <li>autoLoginFloodgate
	 * </ul>
	 * </p>
	 * 
	 * @param key the key of the entry in config.yml
	 * @return <b>true</b> if the entry's value is "true", "false", or "linked"
	 */
	private boolean isValidFloodgateConfigString(String key) {
		String value = core.getConfig().get(key).toString().toLowerCase();
		if (!value.equals("true") && !value.equals("linked") && !value.equals("false")) {
			logger.error("Invalid value detected for {} in FastLogin/config.yml.", key);
			return false;
		}
		return true;	
	}

	/**
	 * Checks if a plugin is installed on the server
	 * @param name the name of the plugin
	 * @return true if the plugin is installed
	 */
	private boolean isPluginInstalled(String name) {
	    //the plugin may be enabled after FastLogin, so isPluginEnabled()
	    //won't work here
	    return Bukkit.getServer().getPluginManager().getPlugin(name) != null;
	}

    /**
     * Send warning messages to log if incompatible plugins are used  
     */
    private void dependencyWarnings() {
        if (isPluginInstalled("floodgate-bukkit")) {
            logger.warn("We have detected that you are runnging Floodgate 1.0 which is not supported by the Bukkit "
                    + "version of FastLogin.");
            logger.warn("If you would like to use FastLogin with Floodgate, you can download developement builds of "
                    + "Floodgate 2.0 from https://ci.opencollab.dev/job/GeyserMC/job/Floodgate/job/dev%252F2.0/");
            logger.warn("Don't forget to update Geyser to a supported version as well from "
                    + "https://ci.opencollab.dev/job/GeyserMC/job/Geyser/job/floodgate-2.0/");
    	} else if (isPluginInstalled("floodgate") && isPluginInstalled("ProtocolLib")) {
            logger.warn("We have detected that you are runnging FastLogin alongside Floodgate and ProtocolLib.");
            logger.warn("Currently there is an issue with FastLogin that prevents Floodgate name prefixes from showing up "
                    + "when it is together used with ProtocolLib.");
            logger.warn("If you would like to use Floodgate name prefixes, you can replace ProtocolLib with ProtocolSupport "
                    + "which does not have this issue.");
            logger.warn("For more information visit https://github.com/games647/FastLogin/issues/493");
    	}
    }
}
