package org.bxteam.divinemc.util;

import com.mojang.logging.LogUtils;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.bxteam.divinemc.config.DivineConfig;
import org.slf4j.Logger;

/**
 * SkyHand: lets a one-off action bar message stay readable when another plugin refreshes its own bar in a loop.
 * <p>
 * The client shows one action bar at a time for about 3 seconds, so a plugin that keeps a permanent bar (vanish
 * status, combat timer, parkour chrono...) resends it every 1-2 seconds and wipes any other message within a second.
 * <p>
 * Each action bar is attributed to the plugin that sent it (the innermost plugin class on the stack). A plugin is
 * refreshing a loop from its third message in a row, each less than {@code repeat-window-ms} after the previous one.
 * Any other message is shown and takes the bar for {@code hold-ms}. During that time a refresh from another plugin is
 * dropped, and so is an empty message from another plugin (it would erase the shown text). The owner's own refreshes
 * go through and keep the bar.
 */
public final class ActionBarCourtesy {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final String SERVER = "minecraft";
    private static final int MAX_FRAMES = 96;
    /** A message is a refresh from the third one of a streak: two one-off messages in a row are not a loop. */
    private static final int REFRESH_FROM = 3;

    private static final ClassValue<String> PLUGIN_OF_CLASS = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            if (type.getClassLoader() instanceof ConfiguredPluginClassLoader loader) {
                try {
                    return loader.getConfiguration().getName();
                } catch (Throwable ignored) {
                    return "";
                }
            }
            return "";
        }
    };

    private ActionBarCourtesy() {
    }

    /** Per-connection state. Guarded by its own monitor: action bars can be sent from any thread. */
    public static final class State {
        private String owner;
        private long ownerUntil;
        private final Map<String, Streak> sources = new HashMap<>(4);
    }

    private static final class Streak {
        private long last;
        private int count;
    }

    public static boolean isActionBar(Packet<?> packet) {
        return packet instanceof ClientboundSetActionBarTextPacket
            || (packet instanceof ClientboundSystemChatPacket chat && chat.overlay());
    }

    /**
     * @return false when the packet must not be sent.
     */
    public static boolean allow(State state, Packet<?> packet, String playerName) {
        Component text = packet instanceof ClientboundSetActionBarTextPacket bar ? bar.text() : ((ClientboundSystemChatPacket) packet).content();
        boolean empty = text == null || text.getString().isBlank();
        String source = source();
        long now = System.nanoTime();
        long holdNanos = DivineConfig.MiscCategory.actionBarCourtesyHoldMs * 1_000_000L;
        long windowNanos = DivineConfig.MiscCategory.actionBarCourtesyRepeatWindowMs * 1_000_000L;

        String droppedFor = null;
        synchronized (state) {
            Streak streak = state.sources.get(source);
            if (streak == null) {
                if (state.sources.size() >= 8) {
                    Iterator<Streak> it = state.sources.values().iterator();
                    while (it.hasNext()) {
                        if (now - it.next().last > windowNanos) {
                            it.remove();
                        }
                    }
                }
                streak = new Streak();
                state.sources.put(source, streak);
            } else if (now - streak.last > windowNanos) {
                streak.count = 0;
            }
            streak.last = now;
            streak.count++;
            boolean repeat = streak.count >= REFRESH_FROM;

            boolean held = state.owner != null && !state.owner.equals(source) && now - state.ownerUntil < 0;
            if (held && (repeat || empty)) {
                droppedFor = state.owner;
            } else if (empty) {
                if (source.equals(state.owner)) {
                    state.owner = null;
                }
            } else {
                state.owner = source;
                state.ownerUntil = now + holdNanos;
            }
        }

        if (DivineConfig.MiscCategory.actionBarCourtesyDebug) {
            String shown = empty ? "<empty>" : text.getString();
            if (droppedFor != null) {
                LOGGER.info("[ActionBar] {} dropped from {} (bar held by {}): {}", playerName, source, droppedFor, shown);
            } else {
                LOGGER.info("[ActionBar] {} sent from {}: {}", playerName, source, shown);
            }
        }
        return droppedFor == null;
    }

    private static String source() {
        try {
            return WALKER.walk(frames -> frames.limit(MAX_FRAMES)
                .map(frame -> PLUGIN_OF_CLASS.get(frame.getDeclaringClass()))
                .filter(name -> !name.isEmpty())
                .findFirst()
                .orElse(SERVER));
        } catch (Throwable ignored) {
            return SERVER;
        }
    }
}
