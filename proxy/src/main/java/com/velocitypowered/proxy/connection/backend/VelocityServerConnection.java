package com.velocitypowered.proxy.connection.backend;

import static com.velocitypowered.proxy.VelocityServer.GSON;
import static com.velocitypowered.proxy.network.Connections.FRAME_DECODER;
import static com.velocitypowered.proxy.network.Connections.FRAME_ENCODER;
import static com.velocitypowered.proxy.network.Connections.HANDLER;
import static com.velocitypowered.proxy.network.Connections.MINECRAFT_DECODER;
import static com.velocitypowered.proxy.network.Connections.MINECRAFT_ENCODER;
import static com.velocitypowered.proxy.network.Connections.READ_TIMEOUT;

import com.google.common.base.Preconditions;
import com.google.common.base.VerifyException;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.PlayerInfoForwarding;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftConnectionAssociation;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.ProtocolConstants;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftEncoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintLengthEncoder;
import com.velocitypowered.proxy.protocol.packet.Handshake;
import com.velocitypowered.proxy.protocol.packet.PluginMessage;
import com.velocitypowered.proxy.protocol.packet.ServerLogin;
import com.velocitypowered.proxy.server.VelocityRegisteredServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

public class VelocityServerConnection implements MinecraftConnectionAssociation, ServerConnection {

  private static final Logger logger = LogManager.getLogger(VelocityServerConnection.class);
  private final VelocityRegisteredServer registeredServer;
  private final ConnectedPlayer proxyPlayer;
  private final VelocityServer server;
  private @Nullable MinecraftConnection connection;
  private boolean legacyForge = false;
  private boolean hasCompletedJoin = false;
  private boolean gracefulDisconnect = false;
  private long lastPingId;
  private long lastPingSent;

  public VelocityServerConnection(VelocityRegisteredServer registeredServer,
      ConnectedPlayer proxyPlayer, VelocityServer server) {
    this.registeredServer = registeredServer;
    this.proxyPlayer = proxyPlayer;
    this.server = server;
  }

  public CompletableFuture<ConnectionRequestBuilder.Result> connect() {
    CompletableFuture<ConnectionRequestBuilder.Result> result = new CompletableFuture<>();
    server.initializeGenericBootstrap()
        .handler(new ChannelInitializer<Channel>() {
          @Override
          protected void initChannel(Channel ch) throws Exception {
            ch.pipeline()
                .addLast(READ_TIMEOUT,
                    new ReadTimeoutHandler(server.getConfiguration().getReadTimeout(),
                        TimeUnit.SECONDS))
                .addLast(FRAME_DECODER, new MinecraftVarintFrameDecoder())
                .addLast(FRAME_ENCODER, MinecraftVarintLengthEncoder.INSTANCE)
                .addLast(MINECRAFT_DECODER,
                    new MinecraftDecoder(ProtocolConstants.Direction.CLIENTBOUND))
                .addLast(MINECRAFT_ENCODER,
                    new MinecraftEncoder(ProtocolConstants.Direction.SERVERBOUND));

            MinecraftConnection mc = new MinecraftConnection(ch, server);
            mc.setState(StateRegistry.HANDSHAKE);
            mc.setAssociation(VelocityServerConnection.this);
            ch.pipeline().addLast(HANDLER, mc);
          }
        })
        .connect(registeredServer.getServerInfo().getAddress())
        .addListener((ChannelFutureListener) future -> {
          if (future.isSuccess()) {
            connection = future.channel().pipeline().get(MinecraftConnection.class);

            // This is guaranteed not to be null, but Checker Framework is whining about it anyway
            if (connection == null) {
              throw new VerifyException("MinecraftConnection not injected into pipeline");
            }

            // Kick off the connection process
            connection.setSessionHandler(
                new LoginSessionHandler(server, VelocityServerConnection.this, result));
            startHandshake();
          } else {
            result.completeExceptionally(future.cause());
          }
        });
    return result;
  }

  private String createLegacyForwardingAddress() {
    // BungeeCord IP forwarding is simply a special injection after the "address" in the handshake,
    // separated by \0 (the null byte). In order, you send the original host, the player's IP, their
    // UUID (undashed), and if you are in online-mode, their login properties (from Mojang).
    StringBuilder data = new StringBuilder(2048)
        .append(registeredServer.getServerInfo().getAddress().getHostString())
        .append('\0')
        .append(proxyPlayer.getRemoteAddress().getHostString())
        .append('\0')
        .append(proxyPlayer.getProfile().getId())
        .append('\0');
    GSON.toJson(proxyPlayer.getProfile().getProperties(), data);
    return data.toString();
  }

  private void startHandshake() {
    MinecraftConnection mc = connection;
    if (mc == null) {
      throw new IllegalStateException("No connection established!");
    }

    PlayerInfoForwarding forwardingMode = server.getConfiguration().getPlayerInfoForwardingMode();

    // Initiate a handshake.
    Handshake handshake = new Handshake();
    handshake.setNextStatus(StateRegistry.LOGIN_ID);
    handshake.setProtocolVersion(proxyPlayer.getConnection().getNextProtocolVersion());
    if (forwardingMode == PlayerInfoForwarding.LEGACY) {
      handshake.setServerAddress(createLegacyForwardingAddress());
    } else if (proxyPlayer.getConnection().isLegacyForge()) {
      handshake.setServerAddress(handshake.getServerAddress() + "\0FML\0");
    } else {
      handshake.setServerAddress(registeredServer.getServerInfo().getAddress().getHostString());
    }
    handshake.setPort(registeredServer.getServerInfo().getAddress().getPort());
    mc.write(handshake);

    int protocolVersion = proxyPlayer.getConnection().getNextProtocolVersion();
    mc.setProtocolVersion(protocolVersion);
    mc.setState(StateRegistry.LOGIN);
    mc.write(new ServerLogin(proxyPlayer.getUsername()));
  }

  @Nullable
  public MinecraftConnection getConnection() {
    return connection;
  }

  @Override
  public VelocityRegisteredServer getServer() {
    return registeredServer;
  }

  @Override
  public ServerInfo getServerInfo() {
    return registeredServer.getServerInfo();
  }

  @Override
  public ConnectedPlayer getPlayer() {
    return proxyPlayer;
  }

  public void disconnect() {
    if (connection != null) {
      gracefulDisconnect = true;
      connection.close();
      connection = null;
    }
  }

  @Override
  public String toString() {
    return "[server connection] " + proxyPlayer.getProfile().getName() + " -> " + registeredServer
        .getServerInfo().getName();
  }

  @Override
  public boolean sendPluginMessage(ChannelIdentifier identifier, byte[] data) {
    Preconditions.checkNotNull(identifier, "identifier");
    Preconditions.checkNotNull(data, "data");

    MinecraftConnection mc = connection;
    if (mc == null) {
      throw new IllegalStateException("Not connected to a server!");
    }

    if (mc.getState() != StateRegistry.PLAY) {
      // A packet somehow got through at the wrong moment. We'll log it for now,
      // but then discard it as it's likely to be from the connection from the
      // old server (mods should not know what the new mod set is until the PLAY
      // phase anyway, so will be working on the old server pretence)
      logger.warn("Attempted to send plugin message packet with ID \"{}\" on {}",
              identifier.getId(), toString());
      return false;
    }

    PluginMessage message = new PluginMessage();
    message.setChannel(identifier.getId());
    message.setData(data);
    mc.write(message);
    return true;
  }

  public boolean isLegacyForge() {
    return legacyForge;
  }

  void setLegacyForge(boolean modded) {
    legacyForge = modded;
  }

  public boolean hasCompletedJoin() {
    return hasCompletedJoin;
  }

  public void setHasCompletedJoin(boolean hasCompletedJoin) {
    this.hasCompletedJoin = hasCompletedJoin;
  }

  boolean isGracefulDisconnect() {
    return gracefulDisconnect;
  }

  public long getLastPingId() {
    return lastPingId;
  }

  public long getLastPingSent() {
    return lastPingSent;
  }

  void setLastPingId(long lastPingId) {
    this.lastPingId = lastPingId;
    this.lastPingSent = System.currentTimeMillis();
  }

  public void resetLastPingId() {
    this.lastPingId = -1;
  }

  /**
   * Ensures that this server connection remains "active": the connection is established and not
   * closed, the player is still connected to the server, and the player still remains online.
   *
   * @return whether or not the player is online
   */
  boolean isActive() {
    return connection != null && !connection.isClosed() && !gracefulDisconnect
        && proxyPlayer.isActive();
  }
}
