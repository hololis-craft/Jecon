package jp.jyn.jecon;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import jp.jyn.jbukkitlib.uuid.UUIDRegistry;
import jp.jyn.jecon.command.Convert;
import jp.jyn.jecon.command.Create;
import jp.jyn.jecon.command.Give;
import jp.jyn.jecon.command.Pay;
import jp.jyn.jecon.command.Reload;
import jp.jyn.jecon.command.Remove;
import jp.jyn.jecon.command.Set;
import jp.jyn.jecon.command.Show;
import jp.jyn.jecon.command.Take;
import jp.jyn.jecon.command.Top;
import jp.jyn.jecon.command.Version;
import jp.jyn.jecon.config.ConfigLoader;
import jp.jyn.jecon.config.MainConfig;
import jp.jyn.jecon.db.Database;
import jp.jyn.jecon.repository.BalanceRepository;
import jp.jyn.jecon.repository.LazyRepository;
import jp.jyn.jecon.repository.SyncRepository;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@SuppressWarnings("UnstableApiUsage")
public class Jecon extends JavaPlugin {
    private static Jecon instance = null;

    private ConfigLoader config;
    private BalanceRepository repository;
    private VaultEconomy economy;

    // Fields elevated for cross-reload access
    private UUIDRegistry registry;
    private Database db;
    private VersionChecker checker;
    private Runnable saveAll;

    // Stack(LIFO)
    private final Deque<Runnable> destructor = new ArrayDeque<>();

    // Brigadier commands are registered only once via LifecycleEvents.COMMANDS
    private boolean commandsRegistered = false;

    @Override
    public void onEnable() {
        instance = this;
        destructor.clear();

        if (config == null) {
            config = new ConfigLoader();
        }
        config.reloadConfig();
        MainConfig main = config.getMainConfig();

        if (registry == null) {
            registry = UUIDRegistry.getSharedCacheRegistry(this);
        }
        if (checker == null) {
            checker = new VersionChecker(main.versionCheck, config.getMessageConfig());
        }

        BukkitTask task = getServer().getScheduler().runTaskLater(
                this,
                () -> checker.check(Bukkit.getConsoleSender()), 20 * 30);
        destructor.addFirst(task::cancel);

        // connect db
        db = Database.connect(main.database);
        destructor.addFirst(db::close);

        // methods for internal use
        Consumer<UUID> consistency;
        Consumer<UUID> save;
        // init repository
        if (main.lazyWrite) {
            LazyRepository lazy = new LazyRepository(main, db);
            repository = lazy;

            consistency = lazy::consistency;
            save = lazy::save;
            saveAll = lazy::saveAll;
        } else {
            repository = new SyncRepository(main, db);

            consistency = u -> {
            };
            save = u -> {
            };
            saveAll = () -> {
            };
        }
        destructor.addFirst(() -> {
            saveAll.run();
            repository = null;
        });

        // register vault
        if (economy == null) {
            Plugin vault = getServer().getPluginManager().getPlugin("Vault");
            if (vault != null) {
                if (vault.isEnabled()) {
                    vaultHook();
                } else {
                    getServer().getPluginManager().registerEvents(new VaultRegister(), this);
                }
            }
        } else {
            economy.init(main, registry, repository);
        }

        // register events
        getServer().getPluginManager().registerEvents(
                new EventListener(main, checker, repository, consistency, save), this);
        destructor.addFirst(() -> HandlerList.unregisterAll(this));

        // register commands via Brigadier (only once per plugin lifecycle)
        if (!commandsRegistered) {
            commandsRegistered = true;
            this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
                LiteralCommandNode<CommandSourceStack> node = buildCommandTree().build();
                event.registrar().register(node, "Jecon economy plugin", List.of("money"));
            });
        }
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildCommandTree() {
        Show show = new Show(this, config);
        Pay pay = new Pay(this, config);
        Set set = new Set(this, config);
        Give give = new Give(this, config);
        Take take = new Take(this, config);
        Create create = new Create(this, config);
        Remove remove = new Remove(this, config);
        Top top = new Top(this, config);
        Convert convert = new Convert(this, config);
        Reload reload = new Reload(this, config);
        Version version = new Version(this, config);

        return Commands.literal("jecon")
                .requires(s -> s.getSender().hasPermission("jecon.show"))
                .executes(show::executeSelf)
                .then(show.create())
                .then(pay.create())
                .then(set.create())
                .then(give.create())
                .then(take.create())
                .then(create.create())
                .then(remove.create())
                .then(top.create())
                .then(convert.create())
                .then(reload.create())
                .then(version.create());
    }

    private void vaultHook() {
        if (economy != null) {
            return;
        }

        economy = new VaultEconomy(config.getMainConfig(), registry, repository);
        getServer().getServicesManager().register(Economy.class, economy, this, ServicePriority.Normal);
        getLogger().info("Hooked Vault");
    }

    @Override
    public void onDisable() {
        while (!destructor.isEmpty()) {
            destructor.removeFirst().run();
        }
    }

    /**
     * Get Jecon instance
     *
     * @return Jecon
     */
    public static Jecon getInstance() {
        return instance;
    }

    /**
     * Get BalanceRepository
     *
     * @return BalanceRepository
     */
    public BalanceRepository getRepository() {
        return repository;
    }

    public UUIDRegistry getRegistry() {
        return registry;
    }

    public Database getDb() {
        return db;
    }

    public VersionChecker getChecker() {
        return checker;
    }

    public Runnable getSaveAll() {
        return saveAll;
    }

    public ConfigLoader getConfigLoader() {
        return config;
    }

    private class VaultRegister implements Listener {
        @EventHandler(ignoreCancelled = true)
        public void onPluginEnable(PluginEnableEvent e) {
            if (!e.getPlugin().getName().equals("Vault")) {
                return;
            }
            vaultHook();
            PluginEnableEvent.getHandlerList().unregister(this);
        }
    }
}
