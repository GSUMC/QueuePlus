package io.mcatlas.queueplus;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.mcatlas.queueplus.commands.*;
import io.mcatlas.queueplus.config.QueueConfig;
import io.mcatlas.queueplus.util.FlatFileStorage;
import io.mcatlas.queueplus.util.NullStorage;
import io.mcatlas.queueplus.util.SQLStorage;
import io.mcatlas.queueplus.util.Storage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "QueuePlus",
        name = "QueuePlus",
        version = "0.0.1",
        description = "Queue Plus is a simple to use Queue Plugin designed for Velocity Proxies.",
        url = "https://mcatlas.io/plugins/queueplus",
        authors = {"StoicSigma"}
)
public class Main {
    private static Main instance;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path pluginFolderPath;
    private final Map<String, Queue> queues = new ConcurrentHashMap<>();
    private final Map<UUID, QueuedPlayer> queuedPlayers = new HashMap<>();
    private final QueueConfig config;
    private boolean debug = false;
    private Storage storage;
    private final Map<UUID, ScheduledTask> scheduledTasks = new ConcurrentHashMap<>();

    @Inject
    public Main(ProxyServer proxy, CommandManager commandManager, Logger logger, @DataDirectory Path pluginFolderPath) {
        Main.instance = this;
        this.proxy = proxy;
        this.logger = logger;
        this.pluginFolderPath = pluginFolderPath;
        this.config = new QueueConfig(this, pluginFolderPath);
        this.config.load();

        this.storage = config.getStorageType().equalsIgnoreCase("sql")
                ? new SQLStorage(this)
                : new FlatFileStorage(this, pluginFolderPath.resolve("data"));

        try {
            this.storage.enable();
        } catch (Exception e) {
            logger.error("An exception occurred when enabling the storage", e);
            this.storage = new NullStorage();
        }

        commandManager.register("joinqueue", new JoinCommand(this));
        commandManager.register("leavequeue", new LeaveCommand());
        commandManager.register("pausequeue", new PauseCommand());
        commandManager.register("queue", new QueueCommand(this));
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        for (RegisteredServer server : proxy.getAllServers()) {
            queues.put(server.getServerInfo().getName().toLowerCase(), new Queue(server, this));
        }

        proxy.getScheduler().buildTask(this, () -> {
            for (Queue queue : queues().values())
                queue.sendNext();
        }).repeat(500, TimeUnit.MILLISECONDS).schedule();

        proxy.getScheduler().buildTask(this, () -> {
            for (Queue queue : queues.values())
                queue.refreshMaxPlayers();
        }).repeat(10, TimeUnit.SECONDS).schedule();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (this.storage != null) {
            try {
                this.storage.disable();
            } catch (Exception e) {
                logger.error("An exception occurred when disabling the storage", e);
            }
        }
    }

    public boolean reload() {
        if (!this.config.reload())
            return false;

        // Disable storage if it isn't null
        if (this.storage != null) {
            try {
                this.storage.disable();
            } catch (Exception e) {
                logger.error("An exception occurred when disabling the storage.", e);
            }
        }

        this.storage = config.getStorageType().equalsIgnoreCase("sql")
                ? new SQLStorage(this)
                : new FlatFileStorage(this, pluginFolderPath.resolve("data"));

        try {
            this.storage.enable();
        } catch (Exception e) {
            logger.error("An exception occurred when enabling the storage", e);
            this.storage = new NullStorage();
        }

        return true;
    }

    @Subscribe
    public void onPlayerJoin(PostLoginEvent event) {
        // Load saved data for this player async upon login.
        queued(event.getPlayer()).loadData();
    }

    @Subscribe
    public void onPlayerLeave(DisconnectEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();

        QueuedPlayer player = queuedPlayers.get(uuid);
        if (player != null) {
            if (player.isInQueue())
                player.queue().remove(player);

            event.getPlayer().getCurrentServer().ifPresent(server -> {
                if (!config.autoQueueSettings().autoQueueServers().contains(server.getServerInfo().getName().toLowerCase(Locale.ROOT)))
                    player.setLastJoinedServer(server.getServerInfo().getName());
            });

            this.storage.savePlayer(player);
        }

        queuedPlayers.remove(uuid);
        removeAutoQueue(event.getPlayer());
    }

    @Subscribe
    public void onServerConnect(ServerConnectedEvent event) {
        QueuedPlayer player = queued(event.getPlayer());

        // Remove the player from their queue if their queue is for the server they just joined.
        if (player.isInQueue() && player.queue().getServer().getServerInfo().getName().equalsIgnoreCase(event.getServer().getServerInfo().getName()))
            player.queue().remove(player);

        processAutoQueue(event, player);
    }

    public void processAutoQueue(ServerConnectedEvent event, QueuedPlayer player) {
        final UUID uuid = event.getPlayer().getUniqueId();

        if (
                player.isAutoQueueDisabled() // Player has auto queue disabled
                        || scheduledTasks.containsKey(uuid) // There's already a scheduled auto queue task for this player
                        || event.getPlayer().getPermissionValue("queue.autoqueue") == Tristate.FALSE // The player has the auto queue permission explicitly set to false
                        || !config.autoQueueSettings().autoQueueServers().contains(event.getServer().getServerInfo().getName().toLowerCase(Locale.ROOT)) // The player isn't on one of the auto queue servers.
        )
            return;

        scheduledTasks.put(uuid, proxy().getScheduler().buildTask(this, () -> {
            scheduledTasks.remove(uuid);

            String target = player.getLastJoinedServer().orElse(config.autoQueueSettings().defaultTarget());
            final String currentServerName = event.getPlayer().getCurrentServer().map(server -> server.getServerInfo().getName()).orElse("unknown");

            // Validate that the target is known to the proxy, it isn't an auto queue server, and the player has permissions to join it, otherwise just return the default target.
            target = proxy.getServer(target).map(server -> server.getServerInfo().getName())
                    .filter(name -> !config.autoQueueSettings().autoQueueServers().contains(name.toLowerCase(Locale.ROOT)))
                    .filter(name -> BaseCommand.hasPrefixedPermission(event.getPlayer(), "queue.join.", name))
                    .orElse(config.autoQueueSettings().defaultTarget());

            // Prevent the player from being auto queued to the server they are already on
            if (target.equalsIgnoreCase(currentServerName))
                return;

            // Simply return if the player doesn't have permissions to join the default target.
            if (!BaseCommand.hasPrefixedPermission(event.getPlayer(), "queue.join.", target))
                return;

            Queue queue = queue(target);
            if (queue != null) {
                debug(event.getPlayer().getUsername() + " has been automatically queued for " + target + ".");
                event.getPlayer().sendMessage(Component.text("You are being automatically queued for " + queue.getServerFormatted() + ".", NamedTextColor.GREEN));
                queue.enqueue(player);
            }
        }).delay(config.autoQueueSettings().delay(), TimeUnit.SECONDS).schedule());
    }

    public void removeAutoQueue(Player player) {
        ScheduledTask task = scheduledTasks.get(player.getUniqueId());
        if (task != null)
            task.cancel();

        scheduledTasks.remove(player.getUniqueId());
    }

    @Subscribe
    public void onPlayerKick(KickedFromServerEvent event) {
        Component reason = event.getServerKickReason().orElse(Component.text("You have been kicked from the server you were on.", NamedTextColor.RED));
        event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason));
    }

    public Map<String, Queue> queues() {
        return queues;
    }

    public static Main instance() {
        return instance;
    }

    public ProxyServer proxy() {
        return this.proxy;
    }

    @Nullable
    public Queue queue(String serverName) {
        Queue queue = queues.get(serverName.toLowerCase());

        if (queue != null)
            return queue;

        // A queue with this name doesn't exist yet, create a new one if a server exists with its name
        Optional<RegisteredServer> registeredServer = proxy.getServer(serverName);
        if (registeredServer.isEmpty())
            return null;

        queue = new Queue(registeredServer.get(), this);
        queues.put(serverName.toLowerCase(), queue);

        return queue;
    }

    public QueuedPlayer queued(Player player) {
        queuedPlayers.putIfAbsent(player.getUniqueId(), new QueuedPlayer(player));

        return queuedPlayers.get(player.getUniqueId());
    }

    public Collection<QueuedPlayer> queuedPlayers() {
        return queuedPlayers.values();
    }

    public static void log(Object message) {
        instance.logger.info(String.valueOf(message));
    }

    public static void warn(Object message) {
        instance.logger.warn(String.valueOf(message));
    }

    public static void debug(Object message) {
        if (instance != null && instance.debug)
            instance.logger.info(String.valueOf(message));
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public Logger logger() {
        return this.logger;
    }

    public QueueConfig config() {
        return this.config;
    }

    public Storage storage() {
        return storage;
    }
}
