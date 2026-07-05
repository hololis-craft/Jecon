package jp.jyn.jecon;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import jp.jyn.jecon.account.Account;
import jp.jyn.jecon.account.AccountService;
import jp.jyn.jecon.account.AccountServiceImpl;
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
import jp.jyn.jecon.event.JeconAccountCreatedEvent;
import jp.jyn.jecon.event.JeconAccountRemovedEvent;
import jp.jyn.jecon.modifier.ModifierRegistry;
import jp.jyn.jecon.modifier.ModifierRegistryImpl;
import jp.jyn.jecon.repository.BalanceRepository;
import jp.jyn.jecon.repository.SyncRepository;
import jp.jyn.jecon.services.JeconServices;
import jp.jyn.jecon.transfer.TransferService;
import jp.jyn.jecon.transfer.TransferServiceImpl;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

@SuppressWarnings("UnstableApiUsage")
public class Jecon extends JavaPlugin {
    private static Jecon instance = null;

    private ConfigLoader config;
    private BalanceRepository repository;
    private VaultEconomy economy;
    private VaultUnlockedEconomy economyUnlocked;
    private AccountService accountService;
    private TransferService transferService;
    private ModifierRegistry modifierRegistry;
    private final JeconServices services = new JeconServices();

    // Fields elevated for cross-reload access
    private Database db;

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

        // connect db
        db = Database.connect(main.database);
        destructor.addFirst(db::close);

        // init repository (Sync only per ADR-0012)
        repository = new SyncRepository(main, db);
        destructor.addFirst(() -> repository = null);

        // AccountService (ADR-0011 / ADR-0013)
        accountService = new AccountServiceImpl(db, new AccountLifecycleAdapter());

        // Modifier registry と TransferService (ADR-0004 / 03-transfer-api.md / 05-modifier-pipeline.md)
        modifierRegistry = new ModifierRegistryImpl();
        transferService = new TransferServiceImpl(this, db, accountService, modifierRegistry);

        // BalanceRepository (SyncRepository) は TransferService の下流に接続する (ADR-0010)。
        ((SyncRepository) repository).bindTransferService(transferService);

        // legacy source/sink 口座を用意する
        ensureLegacyAccounts();

        services.register(AccountService.class, accountService);
        services.register(BalanceRepository.class, repository);
        services.register(TransferService.class, transferService);
        services.register(ModifierRegistry.class, modifierRegistry);
        destructor.addFirst(() -> {
            services.clear();
            accountService = null;
            transferService = null;
            modifierRegistry = null;
        });

        // register vault (旧 Vault)
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
            economy.init(main, db, repository, transferService, accountService);
        }

        // register VaultUnlocked (Vault 2.x)
        Plugin vault2 = getServer().getPluginManager().getPlugin("VaultUnlocked");
        if (vault2 != null) {
            hookVaultUnlocked();
        }

        // register events
        getServer().getPluginManager().registerEvents(
                new EventListener(this, main, repository), this);
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

        economy = new VaultEconomy(config.getMainConfig(), db, repository, transferService, accountService);
        getServer().getServicesManager().register(Economy.class, economy, this, ServicePriority.Normal);
        getLogger().info("Hooked Vault");
    }

    private void hookVaultUnlocked() {
        if (economyUnlocked != null) {
            return;
        }
        try {
            economyUnlocked = new VaultUnlockedEconomy(config.getMainConfig(), db, repository, transferService, accountService);
            getServer().getServicesManager().register(
                net.milkbowl.vault2.economy.Economy.class, economyUnlocked, this, ServicePriority.Normal);
            getLogger().info("Hooked VaultUnlocked");
        } catch (NoClassDefFoundError e) {
            getLogger().warning("VaultUnlocked plugin was detected but API classes are missing: " + e.getMessage());
        }
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

    public Database getDb() {
        return db;
    }

    public ConfigLoader getConfigLoader() {
        return config;
    }

    /**
     * 公開 Service Locator。{@code services().get(AccountService.class)} のように取得する。
     */
    public JeconServices services() {
        return services;
    }

    public AccountService getAccountService() {
        return accountService;
    }

    public TransferService getTransferService() {
        return transferService;
    }

    public ModifierRegistry getModifierRegistry() {
        return modifierRegistry;
    }

    /**
     * BalanceRepository shim が対向として使う {@code system:legacy_source} /
     * {@code system:legacy_sink} を、プラグイン起動時に確実に用意する。
     */
    private void ensureLegacyAccounts() {
        ensureSystemAccount(SyncRepository.LEGACY_SOURCE_UUID, SyncRepository.LEGACY_SOURCE_ALIAS);
        ensureSystemAccount(SyncRepository.LEGACY_SINK_UUID, SyncRepository.LEGACY_SINK_ALIAS);
        ensureSystemAccount(VaultEconomy.VAULT_BRIDGE_UUID, VaultEconomy.VAULT_BRIDGE_ALIAS);
        ensureSystemAccount(VaultUnlockedEconomy.VAULT_UNLOCKED_BRIDGE_UUID,
            VaultUnlockedEconomy.VAULT_UNLOCKED_BRIDGE_ALIAS);
    }

    private void ensureSystemAccount(java.util.UUID uuid, String alias) {
        if (!accountService.exists(uuid)) {
            accountService.createAccount(uuid, alias, false);
        }
    }

    private class AccountLifecycleAdapter implements AccountServiceImpl.AccountLifecycleObserver {
        @Override
        public void onAccountCreated(Account account) {
            getServer().getPluginManager().callEvent(
                new JeconAccountCreatedEvent(account, BigDecimal.ZERO)
            );
        }

        @Override
        public void onAccountRemoved(Account account) {
            BigDecimal balance = repository == null
                ? BigDecimal.ZERO
                : repository.getDecimal(account.uuid()).orElse(BigDecimal.ZERO);
            getServer().getPluginManager().callEvent(
                new JeconAccountRemovedEvent(account, balance)
            );
        }
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
