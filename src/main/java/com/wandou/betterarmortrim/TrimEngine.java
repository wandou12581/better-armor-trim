package com.wandou.betterarmortrim;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.armortrim.ArmorTrim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 核心：每 20 tick 扫一次四件盔甲，算出应当生效的属性/效果，并与上一轮做 diff。
 * 同时把「算出来的数值」暴露给 /triminfo，方便玩家看到具体加了多少。
 */
public final class TrimEngine {
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final int EFFECT_DURATION = 100;

    private static final Map<String, String> ATTR_LABELS = new HashMap<>();
    private static final Map<String, String> EFFECT_LABELS = new HashMap<>();
    private static final Set<String> PERCENT_ATTRS = new HashSet<>();
    private static final Set<String> BINARY_EFFECTS = new HashSet<>();

    static {
        ATTR_LABELS.put("minecraft:generic.movement_speed", "移动速度");
        ATTR_LABELS.put("minecraft:generic.attack_damage", "攻击伤害");
        ATTR_LABELS.put("minecraft:generic.attack_speed", "攻击速度");
        ATTR_LABELS.put("minecraft:generic.attack_knockback", "击退强度");
        ATTR_LABELS.put("minecraft:generic.max_health", "最大生命");
        ATTR_LABELS.put("minecraft:generic.armor", "护甲");
        ATTR_LABELS.put("minecraft:generic.armor_toughness", "护甲韧性");
        ATTR_LABELS.put("minecraft:generic.knockback_resistance", "击退抗性");
        ATTR_LABELS.put("minecraft:generic.luck", "幸运");
        ATTR_LABELS.put("minecraft:generic.oxygen_bonus", "额外氧气");
        ATTR_LABELS.put("minecraft:generic.water_movement_efficiency", "水中移动");
        ATTR_LABELS.put("minecraft:generic.fall_damage_multiplier", "摔落减伤");
        ATTR_LABELS.put("minecraft:player.block_interaction_range", "方块交互距离");
        ATTR_LABELS.put("minecraft:player.entity_interaction_range", "实体交互距离");
        ATTR_LABELS.put("minecraft:player.sneaking_speed", "潜行速度");
        ATTR_LABELS.put("minecraft:player.submerged_mining_speed", "水下挖掘");
        ATTR_LABELS.put("minecraft:player.sweeping_damage_ratio", "横扫伤害比率");

        PERCENT_ATTRS.add("minecraft:generic.movement_speed");
        PERCENT_ATTRS.add("minecraft:generic.knockback_resistance");
        PERCENT_ATTRS.add("minecraft:player.sneaking_speed");
        PERCENT_ATTRS.add("minecraft:generic.fall_damage_multiplier");

        EFFECT_LABELS.put("minecraft:jump_boost", "跳跃提升");
        EFFECT_LABELS.put("minecraft:speed", "速度");
        EFFECT_LABELS.put("minecraft:resistance", "抗性提升");
        EFFECT_LABELS.put("minecraft:absorption", "伤害吸收");
        EFFECT_LABELS.put("minecraft:strength", "力量");
        EFFECT_LABELS.put("minecraft:regeneration", "生命恢复");
        EFFECT_LABELS.put("minecraft:haste", "急迫");
        EFFECT_LABELS.put("minecraft:luck", "幸运");
        EFFECT_LABELS.put("minecraft:night_vision", "夜视");
        EFFECT_LABELS.put("minecraft:dolphins_grace", "海豚的恩惠");
        EFFECT_LABELS.put("minecraft:conduit_power", "潮涌能量");
        EFFECT_LABELS.put("minecraft:water_breathing", "水下呼吸");
        EFFECT_LABELS.put("minecraft:fire_resistance", "抗火");
        EFFECT_LABELS.put("minecraft:slow_falling", "缓慢下落");
        EFFECT_LABELS.put("minecraft:wind_charged", "蓄风");
        EFFECT_LABELS.put("minecraft:weaving", "盘丝");
        EFFECT_LABELS.put("minecraft:oozing", "渗浆");
        EFFECT_LABELS.put("minecraft:slowness", "缓慢");
        EFFECT_LABELS.put("minecraft:mining_fatigue", "挖掘疲劳");
        EFFECT_LABELS.put("minecraft:weakness", "虚弱");
        EFFECT_LABELS.put("minecraft:blindness", "失明");
        EFFECT_LABELS.put("minecraft:hunger", "饥饿");
        EFFECT_LABELS.put("minecraft:infested", "寄生");

        BINARY_EFFECTS.add("minecraft:night_vision");
        BINARY_EFFECTS.add("minecraft:water_breathing");
        BINARY_EFFECTS.add("minecraft:fire_resistance");
        BINARY_EFFECTS.add("minecraft:dolphins_grace");
        BINARY_EFFECTS.add("minecraft:conduit_power");
        BINARY_EFFECTS.add("minecraft:slow_falling");
        BINARY_EFFECTS.add("minecraft:wind_charged");
        BINARY_EFFECTS.add("minecraft:weaving");
        BINARY_EFFECTS.add("minecraft:oozing");
        BINARY_EFFECTS.add("minecraft:infested");
        BINARY_EFFECTS.add("minecraft:blindness");
    }

    private final TrimConfig config;
    private final Map<UUID, Map<ResourceLocation, Holder<Attribute>>> previous = new HashMap<>();
    private final Map<UUID, String> lastTab = new HashMap<>();
    private final Map<UUID, ServerBossEvent> bossBars = new HashMap<>();
    private final Set<String> warned = new LinkedHashSet<>();
    private MinecraftServer server;

    public TrimEngine(TrimConfig config) {
        this.config = config;
    }

    public void reset() {
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                BetterArmorTrim.clearTabHeader(player.getUUID());
                player.connection.send(new ClientboundTabListPacket(Component.empty(), Component.empty()));
                ServerBossEvent bar = bossBars.get(player.getUUID());
                if (bar != null) {
                    bar.removeAllPlayers();
                    bar.setVisible(false);
                }
            }
        }
        previous.clear();
        lastTab.clear();
        bossBars.clear();
    }

    public void tick(MinecraftServer server) {
        if (!config.settings.enabled) {
            return;
        }
        this.server = server;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                Result result = compute(player);
                applyModifiers(player, result.mods);
                applyEffects(player, result.effects);
                updateTab(player, result);
            } catch (Exception e) {
                BetterArmorTrim.LOG.error("处理玩家 {} 的纹饰时出错", player.getGameProfile().getName(), e);
            }
        }
    }

    // ------------------------------------------------------------ 计算

    private Result compute(ServerPlayer player) {
        Result result = new Result();

        for (EquipmentSlot slot : SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ArmorTrim trim = stack.get(DataComponents.TRIM);
            if (trim == null) {
                continue;
            }
            String pattern = idOf(trim.pattern());
            String material = idOf(trim.material());
            TrimConfig.TrimRule rule = config.rules.get(pattern);
            if (rule == null || !rule.enabled) {
                continue;
            }
            int tier = config.settings.materials.getOrDefault(material, 0);
            result.sums.merge(pattern, tier, Integer::sum);
            result.counts.merge(pattern, 1, Integer::sum);
            if (!rule.category.isEmpty()) {
                result.categories.add(rule.category);
            }
        }

        for (Map.Entry<String, Integer> entry : result.sums.entrySet()) {
            String pattern = entry.getKey();
            TrimConfig.TrimRule rule = config.rules.get(pattern);
            if (rule == null || !rule.enabled) {
                continue;
            }
            if (result.counts.getOrDefault(pattern, 0) < rule.minPieces) {
                continue;
            }
            int b = strength(entry.getValue());
            if (b <= 0) {
                continue;
            }
            int layer = layerOf(b);
            result.b.put(pattern, b);
            result.layer.put(pattern, layer);
            addBase(pattern, rule, b, layer, result);
            for (TrimConfig.Stage stage : rule.stages) {
                if (b >= stage.at) {
                    addStage(pattern, stage, b, result);
                }
            }
        }

        // 相克：只看「对」内两个大类是否同时在场
        for (TrimConfig.Pair pair : config.settings.pairs) {
            if (result.categories.contains(pair.a) && result.categories.contains(pair.b)) {
                result.pairs.add(pair);
            }
        }
        for (TrimConfig.Pair pair : result.pairs) {
            TrimConfig.EffectSpec spec = result.pairs.size() == 1 ? pair.light : pair.heavy;
            if (spec == null || spec.effect == null || spec.effect.isEmpty()) {
                continue;
            }
            ResourceLocation id = parse(spec.effect);
            if (id == null) {
                continue;
            }
            result.effects.merge(id, Math.max(0, spec.level - 1), Math::max);
            result.penalties.add(formatEffect(id, Math.max(0, spec.level - 1)));
        }
        return result;
    }

    private void addBase(String pattern, TrimConfig.TrimRule rule, int b, int layer, Result result) {
        TrimConfig.Modifier base = rule.base;
        if (base == null) {
            return;
        }
        if ("effect".equals(base.type)) {
            ResourceLocation id = parse(base.effect);
            if (id == null) {
                return;
            }
            int level = Math.min(Math.max(layer, 1), Math.max(1, base.maxLevel));
            result.effects.merge(id, level - 1, Math::max);
            gain(result, pattern).put("effect:" + id, formatEffect(id, level - 1));
            return;
        }
        ResourceLocation attributeId = parse(base.attribute);
        if (attributeId == null) {
            return;
        }
        double amount = base.perPoint * b;
        if (base.max > 0) {
            amount = Math.min(amount, base.max);
        }
        String key = attrKey(attributeId, "base");
        putModifier(result, pattern, key, attributeId, amount, base.operation);
        gain(result, pattern).put(pattern + "_" + key, formatAttr(attributeId, amount, base.operation));
    }

    private void addStage(String pattern, TrimConfig.Stage stage, int b, Result result) {
        if ("effect".equals(stage.type)) {
            ResourceLocation id = parse(stage.effect);
            if (id != null) {
                int amp = Math.max(0, stage.level - 1);
                result.effects.merge(id, amp, Math::max);
                gain(result, pattern).put("effect:" + id, formatEffect(id, amp));
            }
            return;
        }
        ResourceLocation attributeId = parse(stage.attribute);
        if (attributeId == null) {
            return;
        }
        double amount = stage.value != 0.0 ? stage.value : stage.perPoint * b;
        String key = stage.id == null || stage.id.isEmpty() ? attrKey(attributeId, "stage") : pattern + "_" + sanitize(stage.id);
        putModifier(result, pattern, key, attributeId, amount, stage.operation);
        gain(result, pattern).put(pattern + "_" + key, formatAttr(attributeId, amount, stage.operation));
    }

    private void putModifier(Result result, String pattern, String key,
                             ResourceLocation attributeId, double amount, String operation) {
        Optional<Attribute> attribute = BuiltInRegistries.ATTRIBUTE.getOptional(attributeId);
        if (attribute.isEmpty()) {
            warnOnce("未知属性 " + attributeId);
            return;
        }
        AttributeModifier.Operation op = "multiply_base".equals(operation)
                ? AttributeModifier.Operation.ADD_MULTIPLIED_BASE
                : AttributeModifier.Operation.ADD_VALUE;
        ResourceLocation modifierId = ResourceLocation.fromNamespaceAndPath(BetterArmorTrim.MOD_ID,
                sanitize(key.startsWith(pattern + "_") ? key : pattern + "_" + key));
        result.mods.put(modifierId, new Wanted(BuiltInRegistries.ATTRIBUTE.wrapAsHolder(attribute.get()), amount, op));
    }

    private static Map<String, String> gain(Result result, String pattern) {
        return result.gains.computeIfAbsent(pattern, k -> new LinkedHashMap<>());
    }

    // ------------------------------------------------------------ 应用

    private void applyModifiers(ServerPlayer player, Map<ResourceLocation, Wanted> wanted) {
        Map<ResourceLocation, Holder<Attribute>> prev = previous.getOrDefault(player.getUUID(), Map.of());
        for (Map.Entry<ResourceLocation, Holder<Attribute>> entry : prev.entrySet()) {
            if (wanted.containsKey(entry.getKey())) {
                continue;
            }
            AttributeInstance instance = player.getAttribute(entry.getValue());
            if (instance != null) {
                instance.removeModifier(entry.getKey());
            }
        }
        Map<ResourceLocation, Holder<Attribute>> now = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Wanted> entry : wanted.entrySet()) {
            Wanted w = entry.getValue();
            AttributeInstance instance = player.getAttribute(w.attribute);
            if (instance == null) {
                continue;
            }
            instance.addOrUpdateTransientModifier(
                    new AttributeModifier(entry.getKey(), w.amount, w.operation));
            now.put(entry.getKey(), w.attribute);
        }
        previous.put(player.getUUID(), now);
    }

    private void applyEffects(ServerPlayer player, Map<ResourceLocation, Integer> wanted) {
        for (Map.Entry<ResourceLocation, Integer> entry : wanted.entrySet()) {
            Optional<MobEffect> effect = BuiltInRegistries.MOB_EFFECT.getOptional(entry.getKey());
            if (effect.isEmpty()) {
                warnOnce("未知效果 " + entry.getKey());
                continue;
            }
            player.addEffect(new MobEffectInstance(BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect.get()),
                    EFFECT_DURATION, entry.getValue(), true, false, true));
        }
    }

    // ------------------------------------------------------------ /triminfo

    public List<Component> describe(ServerPlayer player) {
        Result result = compute(player);
        List<Component> lines = new ArrayList<>();

        for (EquipmentSlot slot : SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            ArmorTrim trim = stack.isEmpty() ? null : stack.get(DataComponents.TRIM);
            String name = slotName(slot);
            if (trim == null) {
                lines.add(Component.literal(name + "：无纹饰").withStyle(ChatFormatting.GRAY));
                continue;
            }
            String pattern = idOf(trim.pattern());
            String material = idOf(trim.material());
            int tier = config.settings.materials.getOrDefault(material, 0);
            TrimConfig.TrimRule rule = config.rules.get(pattern);
            String category = rule == null ? "未配置" : rule.category;
            lines.add(Component.literal(name + "：" + shortId(pattern) + " / " + shortId(material)
                    + "（档 " + tier + "，" + category + "）").withStyle(ChatFormatting.GRAY));
        }

        lines.add(Component.literal("—— 生效增益 ——").withStyle(ChatFormatting.DARK_GRAY));
        if (result.b.isEmpty()) {
            lines.add(Component.literal("无").withStyle(ChatFormatting.GRAY));
        }
        for (Map.Entry<String, Integer> entry : result.b.entrySet()) {
            String pattern = entry.getKey();
            int b = entry.getValue();
            int layer = result.layer.getOrDefault(pattern, 0);
            List<String> gains = new ArrayList<>(result.gains.getOrDefault(pattern, java.util.Map.of()).values());
            MutableComponent line = Component.literal(shortId(pattern) + "  S="
                    + result.sums.getOrDefault(pattern, 0) + " B=" + b + " L" + layer)
                    .withStyle(ChatFormatting.GRAY);
            if (!gains.isEmpty()) {
                line.append(Component.literal("  →  ").withStyle(ChatFormatting.DARK_GRAY));
                line.append(Component.literal(String.join("、", gains)).withStyle(ChatFormatting.GREEN));
            }
            lines.add(line);
        }

        if (result.pairs.isEmpty()) {
            lines.add(Component.literal("相克：无").withStyle(ChatFormatting.GRAY));
        } else {
            List<String> names = new ArrayList<>();
            for (TrimConfig.Pair pair : result.pairs) {
                names.add(pair.a + "⇄" + pair.b);
            }
            MutableComponent line = Component.literal("相克：" + String.join("、", names)
                    + (result.pairs.size() == 1 ? "（轻惩罚）" : "（重惩罚）"))
                    .withStyle(ChatFormatting.RED);
            if (!result.penalties.isEmpty()) {
                line.append(Component.literal("  →  ").withStyle(ChatFormatting.DARK_RED));
                line.append(Component.literal(String.join("、", result.penalties)).withStyle(ChatFormatting.RED));
            }
            lines.add(line);
        }
        return lines;
    }
    // ------------------------------------------------------------ tab 列表

    private void updateTab(ServerPlayer player, Result result) {
        TrimConfig.Tab tab = config.settings.tab;
        String position = tab == null || tab.position == null ? "" : tab.position.trim();
        String prev = lastTab.get(player.getUUID());

        if (position.isEmpty()) {
            clearDisplay(player, prev);
            return;
        }

        if ("bossbar".equalsIgnoreCase(position)) {
            updateBossBar(player, tab, result);
            return;
        }

        List<String> lines = tabLines(tab, result);
        if ("header".equalsIgnoreCase(position)) {
            updateHeader(player, lines);
            return;
        }

        if (lines.isEmpty()) {
            clearDisplay(player, prev);
            return;
        }

        String key = "[" + position + "]" + String.join("\n", lines);
        if (key.equals(prev)) {
            return;
        }
        lastTab.put(player.getUUID(), key);

        Component body = Component.literal(String.join("\n", lines)).withStyle(ChatFormatting.AQUA);
        boolean headerSlot = "header".equalsIgnoreCase(position);
        player.connection.send(new ClientboundTabListPacket(
                headerSlot ? body : Component.empty(),
                headerSlot ? Component.empty() : body));
        BetterArmorTrim.LOG.info("[tab] 发给 {} 位置={} 行数={}", player.getGameProfile().getName(), position, lines.size());
    }

    private void clearDisplay(ServerPlayer player, String prev) {
        BetterArmorTrim.clearTabHeader(player.getUUID());
        if (prev == null || prev.isEmpty()) {
            return;
        }
        lastTab.put(player.getUUID(), "");
        player.connection.send(new ClientboundTabListPacket(Component.empty(), Component.empty()));
        ServerBossEvent bar = bossBars.remove(player.getUUID());
        if (bar != null) {
            bar.removeAllPlayers();
            bar.setVisible(false);
        }
    }

    private void updateHeader(ServerPlayer player, List<String> lines) {
        Component header = lines.isEmpty()
                ? null
                : Component.literal(String.join("\n", lines)).withStyle(ChatFormatting.AQUA);
        BetterArmorTrim.setTabHeader(player.getUUID(), header);

        String key = lines.isEmpty() ? "" : "header]" + String.join("\n", lines);
        String prev = lastTab.get(player.getUUID());
        if (key.equals(prev == null ? "" : prev)) {
            return;
        }
        lastTab.put(player.getUUID(), key);

        // 最近有别的来源在发 tab 包（比如 TabTPS）就交给 mixin 改写，避免自己发包把 footer 清空
        if (System.currentTimeMillis() - BetterArmorTrim.lastForeignTabPacket() < 1500L) {
            return;
        }
        player.connection.send(new ClientboundTabListPacket(
                header == null ? Component.empty() : header, Component.empty()));
        BetterArmorTrim.LOG.info("[tab] 自补 header {}", player.getGameProfile().getName());
    }

    private void updateBossBar(ServerPlayer player, TrimConfig.Tab tab, Result result) {
        List<String> entries = new ArrayList<>();
        for (Map<String, String> gains : result.gains.values()) {
            entries.addAll(gains.values());
        }
        String text = entries.isEmpty() ? "" : tab.prefix + String.join(" · ", entries);
        String key = entries.isEmpty() ? "" : "bossbar]" + text;
        String prev = lastTab.get(player.getUUID());
        if (key.equals(prev == null ? "" : prev)) {
            return;
        }
        lastTab.put(player.getUUID(), key);

        if (entries.isEmpty()) {
            clearDisplay(player, prev);
            return;
        }

        ServerBossEvent bar = bossBars.get(player.getUUID());
        if (bar == null) {
            bar = new ServerBossEvent(Component.empty(), BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.PROGRESS);
            bar.setProgress(1.0F);
            bar.addPlayer(player);
            bossBars.put(player.getUUID(), bar);
        }
        bar.setName(Component.literal(text).withStyle(ChatFormatting.AQUA));
        bar.setVisible(true);
        BetterArmorTrim.LOG.info("[tab] 发给 {} 位置=bossbar 条数={}", player.getGameProfile().getName(), entries.size());
    }

    private static List<String> tabLines(TrimConfig.Tab tab, Result result) {
        List<String> entries = new ArrayList<>();
        for (java.util.Map<String, String> gains : result.gains.values()) {
            entries.addAll(gains.values());
        }
        if (entries.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        if (entries.size() <= 2) {
            lines.add(tab.prefix + String.join(" · ", entries));
        } else {
            lines.add(tab.prefix);
            for (int i = 0; i < entries.size(); i += 2) {
                lines.add(String.join(" · ", entries.subList(i, Math.min(i + 2, entries.size()))));
            }
        }
        return lines;
    }

    // ------------------------------------------------------------ 格式化

    private static String formatAttr(ResourceLocation id, double amount, String operation) {
        String name = ATTR_LABELS.getOrDefault(id.toString(), id.getPath());
        if ("minecraft:generic.fall_damage_multiplier".equals(id.toString())) {
            return name + " " + trim(amount * -100) + "%";
        }
        if (PERCENT_ATTRS.contains(id.toString()) || "multiply_base".equals(operation)) {
            return name + " +" + trim(amount * 100) + "%";
        }
        String unit = id.getPath().contains("interaction_range") ? " 格" : "";
        return name + " +" + trim(amount) + unit;
    }

    private static String formatEffect(ResourceLocation id, int amplifier) {
        String name = EFFECT_LABELS.getOrDefault(id.toString(), id.getPath());
        if (BINARY_EFFECTS.contains(id.toString())) {
            return name;
        }
        return name + " " + roman(amplifier + 1);
    }

    private static String trim(double value) {
        String s = String.format("%.2f", value);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(n);
        };
    }

    // ------------------------------------------------------------ 工具

    private int strength(int sum) {
        TrimConfig.Settings s = config.settings;
        double v;
        if (sum <= s.knee1) {
            v = sum;
        } else if (sum <= s.knee2) {
            v = s.knee1 + s.knee1Slope * (sum - s.knee1);
        } else {
            v = s.knee1 + s.knee1Slope * (s.knee2 - s.knee1) + s.knee2Slope * (sum - s.knee2);
        }
        return (int) Math.floor(v + 1.0E-9);
    }

    private static int layerOf(int b) {
        if (b >= 10) {
            return 4;
        }
        if (b >= 7) {
            return 3;
        }
        if (b >= 4) {
            return 2;
        }
        return b >= 1 ? 1 : 0;
    }

    private static String slotName(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "头盔";
            case CHEST -> "胸甲";
            case LEGS -> "护腿";
            case FEET -> "靴子";
            default -> slot.getName();
        };
    }

    private static String idOf(Holder<?> holder) {
        Optional<? extends ResourceKey<?>> key = holder.unwrapKey();
        return key.map(k -> k.location().toString()).orElse("");
    }

    private static String shortId(String id) {
        int index = id.indexOf(':');
        return index >= 0 ? id.substring(index + 1) : id;
    }

    private static String attrKey(ResourceLocation attribute, String suffix) {
        return sanitize(attribute.getPath()) + "_" + suffix;
    }

    private static String sanitize(String raw) {
        return raw.toLowerCase().replaceAll("[^a-z0-9_./-]", "_");
    }

    private static ResourceLocation parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null) {
            BetterArmorTrim.LOG.warn("无效的 id：{}", raw);
        }
        return id;
    }

    private void warnOnce(String message) {
        if (warned.add(message)) {
            BetterArmorTrim.LOG.warn(message);
        }
    }

    private static final class Result {
        final Map<String, Integer> sums = new LinkedHashMap<>();
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final Map<String, Integer> b = new LinkedHashMap<>();
        final Map<String, Integer> layer = new LinkedHashMap<>();
        final Set<String> categories = new LinkedHashSet<>();
        final Map<ResourceLocation, Wanted> mods = new LinkedHashMap<>();
        final Map<ResourceLocation, Integer> effects = new LinkedHashMap<>();
        final Map<String, Map<String, String>> gains = new LinkedHashMap<>();
        final List<TrimConfig.Pair> pairs = new ArrayList<>();
        final List<String> penalties = new ArrayList<>();
    }

    private record Wanted(Holder<Attribute> attribute, double amount, AttributeModifier.Operation operation) {
    }
}
