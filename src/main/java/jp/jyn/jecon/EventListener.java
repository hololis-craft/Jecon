package jp.jyn.jecon;

import jp.jyn.jecon.config.MainConfig;
import jp.jyn.jecon.repository.BalanceRepository;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.math.BigDecimal;

@SuppressWarnings("unused")
class EventListener implements Listener {
    private final Jecon plugin;
    private final boolean createAccountOnJoin;
    private final BigDecimal defaultBalance;

    private final BalanceRepository repository;

    EventListener(Jecon plugin, MainConfig config, BalanceRepository repository) {
        this.plugin = plugin;
        this.createAccountOnJoin = config.createAccountOnJoin;
        this.defaultBalance = config.defaultBalance;

        this.repository = repository;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        plugin.getDb().getOrCreatePlayerId(player.getUniqueId());
        // Minecraft 名を alias として反映（重複時は暫定 hex-uuid のまま維持）
        plugin.getDb().renameAccount(player.getUniqueId(), player.getName());

        if (createAccountOnJoin) {
            repository.createAccount(player.getUniqueId(), defaultBalance);
        }
    }
}
