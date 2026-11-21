package com.wandou.betterarmortrim;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BetterArmorTrim implements ModInitializer {
    public static final String MOD_ID = "betterarmortrim";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** header 覆盖表：由 mixin 在 tab 包出站时读取。 */
    private static final Map<UUID, Component> TAB_HEADERS = new ConcurrentHashMap<>();
    private static volatile long lastForeignTabPacket = 0L;

    private static TrimConfig config;
    private static TrimEngine engine;
    private static int tickCounter;

    @Override
    public void onInitialize() {
        config = TrimConfig.load(FabricLoader.getInstance().getConfigDir().resolve(MOD_ID));
        engine = new TrimEngine(config);

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int interval = Math.max(1, config.settings.tickInterval);
            if (++tickCounter < interval) {
                return;
            }
            tickCounter = 0;
            engine.tick(server);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                TrimCommand.register(dispatcher));

        LOG.info("更好的盔甲纹饰已加载：{} 条纹饰规则", config.rules.size());
    }

    public static TrimConfig config() {
        return config;
    }

    public static TrimEngine engine() {
        return engine;
    }

    public static void reload() {
        config.reload();
        engine.reset();
    }

    public static void setTabHeader(UUID uuid, Component header) {
        if (header == null || header.getString().isEmpty()) {
            TAB_HEADERS.remove(uuid);
        } else {
            TAB_HEADERS.put(uuid, header);
        }
    }

    public static void clearTabHeader(UUID uuid) {
        TAB_HEADERS.remove(uuid);
    }

    /** 最近一次「别的来源」发出的 tab 包时间，用来判断需不需要我们自己补发。 */
    public static long lastForeignTabPacket() {
        return lastForeignTabPacket;
    }

    /** 由 mixin 调用：把 tab 包的 header 换成我们的，footer 原样保留；没有内容就原样放行。 */
    public static ClientboundTabListPacket rewriteTabPacket(UUID uuid, ClientboundTabListPacket packet) {
        lastForeignTabPacket = System.currentTimeMillis();
        Component header = TAB_HEADERS.get(uuid);
        if (header == null) {
            return packet;
        }
        return new ClientboundTabListPacket(header, packet.footer());
    }
}
