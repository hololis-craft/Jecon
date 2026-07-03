package jp.jyn.jecon.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;

public class MessageConfig {
    private final static String PREFIX = "[Jecon] ";
    public final static Component HEADER = MiniMessage.miniMessage().deserialize("========== Jecon ==========");
    public final static Component PLAYER_ONLY = MiniMessage.miniMessage().deserialize("[Jecon] <red>This command can only be run by players.");

    public final MessageTemplate doNotHavePermission;
    public final MessageTemplate missingArgument;
    /**
     * value
     */
    public final MessageTemplate invalidArgument;
    /**
     * name
     */
    public final MessageTemplate playerNotFound;

    /**
     * name
     */
    public final MessageTemplate accountNotFound;
    public final MessageTemplate notEnough;

    /**
     * name,balance
     */
    public final MessageTemplate show;
    /**
     * amount,name
     */
    public final MessageTemplate paySuccess;
    /**
     * amount,name
     */
    public final MessageTemplate payReceive;
    /**
     * name,balance
     */
    public final MessageTemplate set;
    /**
     * amount,name
     */
    public final MessageTemplate give;
    /**
     * amount,name
     */
    public final MessageTemplate take;
    /**
     * name,balance
     */
    public final MessageTemplate create;
    /**
     * name
     */
    public final MessageTemplate createAlready;
    /**
     * name,balance
     */
    public final MessageTemplate remove;
    public final MessageTemplate reloaded;

    /**
     * page
     */
    public final MessageTemplate topFirst;
    /**
     * rank,name,balance
     */
    public final MessageTemplate topEntry;

    public final HelpMessage help;

    MessageConfig(ConfigurationSection config) {
        doNotHavePermission = parse(config, "doNotHavePermission");
        missingArgument = parse(config, "missingArgument");
        invalidArgument = parse(config, "invalidArgument");
        playerNotFound = parse(config, "playerNotFound");

        accountNotFound = parse(config, "accountNotFound");
        notEnough = parse(config, "notEnough");

        show = parse(config, "show");
        paySuccess = parse(config, "paySuccess");
        payReceive = parse(config, "payReceive");
        set = parse(config, "set");
        give = parse(config, "give");
        take = parse(config, "take");
        create = parse(config, "create");
        createAlready = parse(config, "createAlready");
        remove = parse(config, "remove");
        reloaded = parse(config, "reloaded");

        topFirst = parse(config.getString("topFirst"));
        topEntry = parse(config.getString("topEntry"));

        help = new HelpMessage(config.getConfigurationSection("help"));
    }

    public final static class HelpMessage {
        public final MessageTemplate show;
        public final MessageTemplate pay;
        public final MessageTemplate set;
        public final MessageTemplate give;
        public final MessageTemplate take;
        public final MessageTemplate create;
        public final MessageTemplate remove;
        public final MessageTemplate top;
        public final MessageTemplate reload;
        public final MessageTemplate version;
        public final MessageTemplate help;
        public final MessageTemplate example;

        private HelpMessage(ConfigurationSection config) {
            show = parse(config.getString("show"));
            pay = parse(config.getString("pay"));
            set = parse(config.getString("set"));
            give = parse(config.getString("give"));
            take = parse(config.getString("take"));
            create = parse(config.getString("create"));
            remove = parse(config.getString("remove"));
            top = parse(config.getString("top"));
            reload = parse(config.getString("reload"));
            version = parse(config.getString("version"));
            help = parse(config.getString("help"));
            example = parse(config.getString("example"));
        }
    }

    private static MessageTemplate parse(ConfigurationSection config, String key) {
        return parse(PREFIX + config.getString(key));
    }

    private static MessageTemplate parse(String value) {
        return new MessageTemplate(value);
    }
}
