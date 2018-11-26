package com.velocitypowered.proxy.connection.client;

import static com.velocitypowered.proxy.VelocityServer.GSON;
import static com.velocitypowered.proxy.connection.VelocityConstants.EMPTY_BYTE_ARRAY;
import static com.velocitypowered.proxy.connection.VelocityConstants.VELOCITY_IP_FORWARDING_CHANNEL;
import static com.velocitypowered.api.network.ProtocolVersion.*;

import com.google.common.base.Preconditions;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;

import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.Disconnect;
import com.velocitypowered.proxy.protocol.packet.EncryptionRequest;
import com.velocitypowered.proxy.protocol.packet.EncryptionResponse;
import com.velocitypowered.proxy.protocol.packet.LoginPluginMessage;
import com.velocitypowered.proxy.protocol.packet.LoginPluginResponse;
import com.velocitypowered.proxy.protocol.packet.ServerLogin;
import com.velocitypowered.proxy.protocol.packet.ServerLoginSuccess;
import com.velocitypowered.proxy.protocol.packet.SetCompression;
import com.velocitypowered.proxy.util.EncryptionUtils;
import com.velocitypowered.proxy.util.VelocityMessages;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.text.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public class LoginSessionHandler implements MinecraftSessionHandler {

  private static final Logger logger = LogManager.getLogger(LoginSessionHandler.class);
  private static final String MOJANG_HASJOINED_URL =
      "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=%s&serverId=%s&ip=%s";

  private final VelocityServer server;
  private final MinecraftConnection inbound;
  private final InboundConnection apiInbound;
  private @MonotonicNonNull ServerLogin login;
  private byte[] verify = EMPTY_BYTE_ARRAY;
  private int playerInfoId;
  private @MonotonicNonNull ConnectedPlayer connectedPlayer;

  LoginSessionHandler(VelocityServer server, MinecraftConnection inbound,
      InboundConnection apiInbound) {
    this.server = Preconditions.checkNotNull(server, "server");
    this.inbound = Preconditions.checkNotNull(inbound, "inbound");
    this.apiInbound = Preconditions.checkNotNull(apiInbound, "apiInbound");
  }

  @Override
  public boolean handle(ServerLogin packet) {
    logger.info("ServerLogin has started.");
    this.login = packet;
    if (inbound.getProtocolVersion().compareTo(MINECRAFT_1_13) >= 0) {
      playerInfoId = ThreadLocalRandom.current().nextInt();
      inbound.write(new LoginPluginMessage(playerInfoId, VELOCITY_IP_FORWARDING_CHANNEL,
          Unpooled.EMPTY_BUFFER));
    } else {
      beginPreLogin();
    }
    return true;
  }

  @Override
  public boolean handle(LoginPluginResponse packet) {
    if (packet.getId() == playerInfoId) {
      if (packet.isSuccess()) {
        // Uh oh, someone's trying to run Velocity behind Velocity. We don't want that happening.
        inbound.closeWith(Disconnect.create(VelocityMessages.NO_PROXY_BEHIND_PROXY));
      } else {
        // Proceed with the regular login process.
        beginPreLogin();
      }
    }
    return true;
  }

  @Override
  public boolean handle(EncryptionResponse packet) {
    ServerLogin login = this.login;
    if (login == null) {
      throw new IllegalStateException("No ServerLogin packet received yet.");
    }

    if (verify.length == 0) {
      throw new IllegalStateException("No EncryptionRequest packet sent yet.");
    }

    try {
      KeyPair serverKeyPair = server.getServerKeyPair();
      byte[] decryptedVerifyToken = EncryptionUtils
          .decryptRsa(serverKeyPair, packet.getVerifyToken());
      if (!Arrays.equals(verify, decryptedVerifyToken)) {
        throw new IllegalStateException("Unable to successfully decrypt the verification token.");
      }

      byte[] decryptedSharedSecret = EncryptionUtils
          .decryptRsa(serverKeyPair, packet.getSharedSecret());
      String serverId = EncryptionUtils
          .generateServerId(decryptedSharedSecret, serverKeyPair.getPublic());

      String playerIp = ((InetSocketAddress) inbound.getRemoteAddress()).getHostString();
      String url = String.format(MOJANG_HASJOINED_URL, login.getUsername(), serverId, playerIp);
      server.getHttpClient()
          .get(new URL(url))
          .thenAcceptAsync(profileResponse -> {
            if (inbound.isClosed()) {
              // The player disconnected after we authenticated them.
              return;
            }

            // Go ahead and enable encryption. Once the client sends EncryptionResponse, encryption
            // is enabled.
            try {
              inbound.enableEncryption(decryptedSharedSecret);
            } catch (GeneralSecurityException e) {
              throw new RuntimeException(e);
            }

            if (profileResponse.getCode() == 200) {
              // All went well, initialize the session.
              initializePlayer(GSON.fromJson(profileResponse.getBody(), GameProfile.class), true);
            } else if (profileResponse.getCode() == 204) {
              // Apparently an offline-mode user logged onto this online-mode proxy.
              logger.warn("An offline-mode client ({} from {}) tried to connect!",
                  login.getUsername(), playerIp);
              inbound.closeWith(Disconnect.create(VelocityMessages.ONLINE_MODE_ONLY));
            } else {
              // Something else went wrong
              logger.error(
                  "Got an unexpected error code {} whilst contacting Mojang to log in {} ({})",
                  profileResponse.getCode(), login.getUsername(), playerIp);
              inbound.close();
            }
          }, inbound.eventLoop())
          .exceptionally(exception -> {
            logger.error("Unable to enable encryption", exception);
            inbound.close();
            return null;
          });
    } catch (GeneralSecurityException e) {
      logger.error("Unable to enable encryption", e);
      inbound.close();
    } catch (MalformedURLException e) {
      throw new AssertionError(e);
    }
    return true;
  }

  private void beginPreLogin() {
    ServerLogin login = this.login;
    if (login == null) {
      throw new IllegalStateException("No ServerLogin packet received yet.");
    }
    logger.info("Firing PreLogin.");
    PreLoginEvent event = new PreLoginEvent(apiInbound, login.getUsername());
    server.getEventManager().fire(event)
        .thenRunAsync(() -> {
          if (inbound.isClosed()) {
            // The player was disconnected
            return;
          }
          PreLoginComponentResult result = event.getResult();
          Optional<Component> disconnectReason = result.getReason();
          if (disconnectReason.isPresent()) {
            // The component is guaranteed to be provided if the connection was denied.
            inbound.closeWith(Disconnect.create(disconnectReason.get()));
            return;
          }

          logger.info("PreLogin not cancelled. Continuing.");
          if (!result.isForceOfflineMode() && (server.getConfiguration().isOnlineMode() || result
              .isOnlineModeAllowed())) {
            // Request encryption.
            EncryptionRequest request = generateEncryptionRequest();
            this.verify = Arrays.copyOf(request.getVerifyToken(), 4);
            inbound.write(request);
            logger.info("EncryptionRequest sent.");
          } else {
            initializePlayer(GameProfile.forOfflinePlayer(login.getUsername()), false);
            logger.info("Offline Player init complete.");
          }
        }, inbound.eventLoop());
  }

  private EncryptionRequest generateEncryptionRequest() {
    byte[] verify = new byte[4];
    ThreadLocalRandom.current().nextBytes(verify);

    EncryptionRequest request = new EncryptionRequest();
    request.setPublicKey(server.getServerKeyPair().getPublic().getEncoded());
    request.setVerifyToken(verify);
    return request;
  }

  private void initializePlayer(GameProfile profile, boolean onlineMode) {
    // Some connection types may need to alter the game profile.
    logger.info("Altering GameProfile (if required).");
    profile = inbound.getType().addGameProfileTokensIfRequired(profile,
        server.getConfiguration().getPlayerInfoForwardingMode());
    GameProfileRequestEvent profileRequestEvent = new GameProfileRequestEvent(apiInbound, profile,
        onlineMode);

    logger.info("Firing GameProfileRequestEvent.");
    server.getEventManager().fire(profileRequestEvent).thenCompose(profileEvent -> {
      // Initiate a regular connection and move over to it.
      ConnectedPlayer player = new ConnectedPlayer(server, profileEvent.getGameProfile(), inbound,
          apiInbound.getVirtualHost().orElse(null));
      logger.info("ConnectedPlayer created.");
      this.connectedPlayer = player;

      if (!server.registerConnection(player)) {
        logger.info("Player is already connected.");
        player.disconnect(VelocityMessages.ALREADY_CONNECTED);
        return CompletableFuture.completedFuture(null);
      }

      logger.info("Firing permission setup event.");
      return server.getEventManager()
          .fire(new PermissionsSetupEvent(player, ConnectedPlayer.DEFAULT_PERMISSIONS))
          .thenCompose(event -> {
            // wait for permissions to load, then set the players permission function
            player.setPermissionFunction(event.createFunction(player));
            // then call & wait for the login event
            logger.info("Firing login event.");
            return server.getEventManager().fire(new LoginEvent(player));
          })
          // then complete the connection
          .thenAcceptAsync(event -> {
            if (inbound.isClosed()) {
              // The player was disconnected
              return;
            }

            Optional<Component> reason = event.getResult().getReason();
            if (reason.isPresent()) {
              logger.info("Player disconnected after login event: {}.", reason.get());
              player.disconnect(reason.get());
            } else {
              finishLogin(player);
            }
          }, inbound.eventLoop());
    });

  }

  private void finishLogin(ConnectedPlayer player) {
    logger.info("Starting connection to server.");
    Optional<RegisteredServer> toTry = player.getNextServerToTry();
    if (!toTry.isPresent()) {
      logger.info("Unable to find a server to connect to.");
      player.disconnect(VelocityMessages.NO_AVAILABLE_SERVERS);
      return;
    }

    logger.info("Sending compression threshold.");
    int threshold = server.getConfiguration().getCompressionThreshold();
    if (threshold >= 0) {
      inbound.write(new SetCompression(threshold));
      inbound.setCompressionThreshold(threshold);
    }

    logger.info("Sending server login success.");
    ServerLoginSuccess success = new ServerLoginSuccess();
    success.setUsername(player.getUsername());
    success.setUuid(player.getUniqueId());
    inbound.write(success);

    inbound.setAssociation(player);
    inbound.setState(StateRegistry.PLAY);

    logger.info("{} has connected", player);
    inbound.setSessionHandler(new InitialConnectSessionHandler(player));
    server.getEventManager().fire(new PostLoginEvent(player))
        .thenRun(() -> player.createConnectionRequest(toTry.get()).fireAndForget());
  }

  @Override
  public void handleUnknown(ByteBuf buf) {
    throw new IllegalStateException("Unknown data " + ByteBufUtil.hexDump(buf));
  }

  @Override
  public void disconnected() {
    if (connectedPlayer != null) {
      connectedPlayer.teardown();
    }
  }
}
