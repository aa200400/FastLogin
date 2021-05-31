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
package com.github.games647.fastlogin.bukkit.listener.protocollib;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketEvent;
import com.github.games647.fastlogin.bukkit.BukkitLoginSession;
import com.github.games647.fastlogin.bukkit.FastLoginBukkit;
import com.github.games647.fastlogin.bukkit.event.BukkitFastLoginPreLoginEvent;
import com.github.games647.fastlogin.core.StoredProfile;
import com.github.games647.fastlogin.core.shared.JoinManagement;
import com.github.games647.fastlogin.core.shared.event.FastLoginPreLoginEvent;
import com.github.games647.fastlogin.core.*;

import java.security.PublicKey;
import java.util.Random;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

public class NameCheckTask extends JoinManagement<Player, CommandSender, ProtocolLibLoginSource>
        implements Runnable {

    private final FastLoginBukkit plugin;
    private final PacketEvent packetEvent;
    private final PublicKey publicKey;

    private final Random random;

    private final Player player;
    private final String username;

    public NameCheckTask(FastLoginBukkit plugin, PacketEvent packetEvent, Random random,
                         Player player, String username, PublicKey publicKey) {
        super(plugin.getCore(), plugin.getCore().getAuthPluginHook());

        this.plugin = plugin;
        this.packetEvent = packetEvent;
        this.publicKey = publicKey;
        this.random = random;
        this.player = player;
        this.username = username;
    }

    @Override
    public void run() {
        try {
            // check if the player is connecting through Geyser
            if (!plugin.getCore().getConfig().get("allowFloodgateNameConflict").toString().equalsIgnoreCase("false")
                    && getFloodgatePlayer(username) != null) {
                plugin.getLog().info("Skipping name conflict checking for player {}", username);
                return;
            }
            super.onLogin(username, new ProtocolLibLoginSource(packetEvent, player, random, publicKey));
        } finally {
            ProtocolLibrary.getProtocolManager().getAsynchronousManager().signalPacketTransmission(packetEvent);
        }
    }

    @Override
    public FastLoginPreLoginEvent callFastLoginPreLoginEvent(String username, ProtocolLibLoginSource source, StoredProfile profile) {
        BukkitFastLoginPreLoginEvent event = new BukkitFastLoginPreLoginEvent(username, source, profile);
        plugin.getServer().getPluginManager().callEvent(event);
        return event;
    }

    //Minecraft server implementation
    //https://github.com/bergerkiller/CraftSource/blob/master/net.minecraft.server/LoginListener.java#L161
    @Override
    public void requestPremiumLogin(ProtocolLibLoginSource source, StoredProfile profile
            , String username, boolean registered) {
        try {
            source.setOnlineMode();
        } catch (Exception ex) {
            plugin.getLog().error("Cannot send encryption packet. Falling back to cracked login for: {}", profile, ex);
            return;
        }

        String ip = player.getAddress().getAddress().getHostAddress();
        core.getPendingLogin().put(ip + username, new Object());

        String serverId = source.getServerId();
        byte[] verify = source.getVerifyToken();

        BukkitLoginSession playerSession = new BukkitLoginSession(username, serverId, verify, registered, profile);
        plugin.putSession(player.getAddress(), playerSession);
        //cancel only if the player has a paid account otherwise login as normal offline player
        synchronized (packetEvent.getAsyncMarker().getProcessingLock()) {
            packetEvent.setCancelled(true);
        }
    }

    @Override
    public void startCrackedSession(ProtocolLibLoginSource source, StoredProfile profile, String username) {
        boolean exists = core.getStorage().existsProfile(username);
        if (exists) {
            BukkitLoginSession loginSession = new BukkitLoginSession(username, profile);
            plugin.putSession(player.getAddress(), loginSession);
        } else {
            try {
                source.kick("You are not on the whitelist for cracked clients. Please contact the server owner on discord to add you.");
            } catch (Exception e) {
                plugin.getLog().error("Failed on kicking cracked player: {}", profile, e);
            }
        }
    }
    
    private static FloodgatePlayer getFloodgatePlayer(String username) {
        if (Bukkit.getServer().getPluginManager().isPluginEnabled("floodgate"))  {
            // the Floodgate API requires UUID, which is inaccessible at NameCheckTask.java
            for (FloodgatePlayer floodgatePlayer : FloodgateApi.getInstance().getPlayers()) {
                if (floodgatePlayer.getUsername().equals(username)) {
                    return floodgatePlayer;
                }
            }
        }
        return null;
    }
}
