package jp.jyn.jecon.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

public class MessageTemplate {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final String template;

    MessageTemplate(String template) {
        this.template = template;
    }

    public Component toComponent() {
        return MM.deserialize(template);
    }

    public Component toComponent(String key, String value) {
        return MM.deserialize(template, Placeholder.unparsed(key, value));
    }

    public Component toComponent(TagResolver... resolvers) {
        return MM.deserialize(template, resolvers);
    }
}
