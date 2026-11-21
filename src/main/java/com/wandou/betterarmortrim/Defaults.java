package com.wandou.betterarmortrim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 首次运行时生成默认配置：settings.json + trims/&lt;pattern&gt;.json。
 * 已存在的文件不会被覆盖，方便手改。
 */
final class Defaults {
    private Defaults() {
    }

    static void writeAll(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("trims"));

        writeIfMissing(dir.resolve("settings.json"), settings());
        writeIfMissing(dir.resolve("README.txt"), readme());

        TrimConfig.TrimRule[] rules = {
                coast(), tide(), flow(),
                dune(), wild(), spire(),
                sentry(), vex(), ward(),
                silence(), shaper(), bolt(),
                eye(), rib(), snout(),
                wayfinder(), raiser(), host()
        };
        for (TrimConfig.TrimRule rule : rules) {
            String file = rule.trim.contains(":") ? rule.trim.substring(rule.trim.indexOf(':') + 1) : rule.trim;
            writeIfMissing(dir.resolve("trims").resolve(file + ".json"), rule);
        }
    }

    private static void writeIfMissing(Path file, Object value) throws IOException {
        if (Files.exists(file)) {
            return;
        }
        TrimConfig.writeJson(file, value);
    }

    private static void writeIfMissing(Path file, String text) throws IOException {
        if (Files.exists(file)) {
            return;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ settings

    private static TrimConfig.Settings settings() {
        TrimConfig.Settings s = new TrimConfig.Settings();
        s.materials.put("minecraft:copper", 1);
        s.materials.put("minecraft:quartz", 1);
        s.materials.put("minecraft:redstone", 1);
        s.materials.put("minecraft:lapis", 1);
        s.materials.put("minecraft:amethyst", 1);
        s.materials.put("minecraft:iron", 2);
        s.materials.put("minecraft:gold", 2);
        s.materials.put("minecraft:emerald", 2);
        s.materials.put("minecraft:diamond", 3);
        s.materials.put("minecraft:netherite", 4);

        s.pairs.add(pair("海洋", "荒漠", "minecraft:slowness", 1, "minecraft:mining_fatigue", 2));
        s.pairs.add(pair("幽域", "血肉", "minecraft:weakness", 1, "minecraft:blindness", 1));
        s.pairs.add(pair("卫戍", "庶民", "minecraft:hunger", 1, "minecraft:infested", 1));
        return s;
    }

    private static TrimConfig.Pair pair(String a, String b, String lightEffect, int lightLevel,
                                        String heavyEffect, int heavyLevel) {
        TrimConfig.Pair p = new TrimConfig.Pair();
        p.a = a;
        p.b = b;
        p.light = effectSpec(lightEffect, lightLevel);
        p.heavy = effectSpec(heavyEffect, heavyLevel);
        return p;
    }

    private static TrimConfig.EffectSpec effectSpec(String effect, int level) {
        TrimConfig.EffectSpec spec = new TrimConfig.EffectSpec();
        spec.effect = effect;
        spec.level = level;
        return spec;
    }

    private static String readme() {
        return String.join("\n",
                "更好的盔甲纹饰 (Better Armor Trim) 配置说明",
                "",
                "settings.json",
                "  tickInterval   每多少 tick 重算一次，20 = 1 秒",
                "  knee1/knee2    强度曲线的两个拐点（对应 S = 四件材质档之和）",
                "  knee1Slope     第二段斜率（默认 0.75）",
                "  knee2Slope     第三段斜率（默认 0.375）",
                "  materials      材质 -> 档位（1~4）",
                "  pairs          相克对：a 与 b 同时在场即触发；light = 只触发 1 对，heavy = 同时 2 对",
                "  tab            常驻显示：position = header/footer/留空关闭（默认 footer），prefix 是前缀文字",
                "",
                "trims/<pattern>.json  每种纹饰一个文件",
                "  trim           纹饰 id，例如 minecraft:coast",
                "  category       大类，用于相克判定",
                "  minPieces      至少几件才生效",
                "  base           基础效果：type=attribute 时按 perPoint × B 线性给；type=effect 时按层数给等级",
                "  stages         里程碑：B >= at 时追加，id 相同则后层覆盖前层",
                "",
                "修改后执行 /batrim reload 热重载。",
                "");
    }

    // -------------------------------------------------------------- 模型

    private static TrimConfig.Modifier attr(String id, double perPoint, double max, String operation) {
        TrimConfig.Modifier m = new TrimConfig.Modifier();
        m.type = "attribute";
        m.attribute = id;
        m.perPoint = perPoint;
        m.max = max;
        m.operation = operation;
        return m;
    }

    private static TrimConfig.Modifier attr(String id, double perPoint) {
        return attr(id, perPoint, -1.0, "add");
    }

    private static TrimConfig.Modifier eff(String id, int maxLevel) {
        TrimConfig.Modifier m = new TrimConfig.Modifier();
        m.type = "effect";
        m.effect = id;
        m.maxLevel = maxLevel;
        return m;
    }

    private static TrimConfig.Stage sAttr(int at, String id, String attribute, double value) {
        TrimConfig.Stage st = new TrimConfig.Stage();
        st.at = at;
        st.type = "attribute";
        st.id = id;
        st.attribute = attribute;
        st.value = value;
        return st;
    }

    private static TrimConfig.Stage sAttrPP(int at, String id, String attribute, double perPoint) {
        TrimConfig.Stage st = new TrimConfig.Stage();
        st.at = at;
        st.type = "attribute";
        st.id = id;
        st.attribute = attribute;
        st.perPoint = perPoint;
        return st;
    }

    private static TrimConfig.Stage sEff(int at, String id, String effect, int level) {
        TrimConfig.Stage st = new TrimConfig.Stage();
        st.at = at;
        st.type = "effect";
        st.id = id;
        st.effect = effect;
        st.level = level;
        return st;
    }

    private static TrimConfig.TrimRule rule(String trim, String category) {
        TrimConfig.TrimRule r = new TrimConfig.TrimRule();
        r.trim = trim;
        r.category = category;
        r.minPieces = 1;
        return r;
    }

    // ------------------------------------------------------------ 18 条纹饰

    private static TrimConfig.TrimRule coast() {
        TrimConfig.TrimRule r = rule("minecraft:coast", "海洋");
        r.base = attr("minecraft:player.submerged_mining_speed", 0.08, 1.0, "add");
        r.stages.add(sAttr(4, "health", "minecraft:generic.max_health", 2));
        r.stages.add(sAttr(7, "health", "minecraft:generic.max_health", 4));
        r.stages.add(sEff(10, "dolphin", "minecraft:dolphins_grace", 1));
        r.stages.add(sEff(10, "resist", "minecraft:resistance", 2));
        return r;
    }

    private static TrimConfig.TrimRule tide() {
        TrimConfig.TrimRule r = rule("minecraft:tide", "海洋");
        r.base = attr("minecraft:generic.oxygen_bonus", 0.1, 1.0, "add");
        r.base.effect = "oxygen";
        r.stages.add(sAttrPP(4, "water_move", "minecraft:generic.water_movement_efficiency", 0.1));
        r.stages.add(sAttrPP(7, "water_move", "minecraft:generic.water_movement_efficiency", 0.15));
        r.stages.add(sEff(10, "conduit", "minecraft:conduit_power", 1));
        return r;
    }

    private static TrimConfig.TrimRule flow() {
        TrimConfig.TrimRule r = rule("minecraft:flow", "海洋");
        r.base = attr("minecraft:generic.water_movement_efficiency", 0.1, 1.0, "add");
        r.stages.add(sAttr(7, "oxygen", "minecraft:generic.oxygen_bonus", 1.0));
        r.stages.add(sAttr(10, "fall", "minecraft:generic.fall_damage_multiplier", -0.5));
        return r;
    }

    private static TrimConfig.TrimRule dune() {
        TrimConfig.TrimRule r = rule("minecraft:dune", "荒漠");
        r.base = attr("minecraft:generic.movement_speed", 0.02, -1.0, "multiply_base");
        return r;
    }

    private static TrimConfig.TrimRule wild() {
        TrimConfig.TrimRule r = rule("minecraft:wild", "荒漠");
        r.base = eff("minecraft:jump_boost", 3);
        r.stages.add(sEff(10, "speed", "minecraft:speed", 2));
        return r;
    }

    private static TrimConfig.TrimRule spire() {
        TrimConfig.TrimRule r = rule("minecraft:spire", "荒漠");
        r.base = attr("minecraft:generic.attack_knockback", 0.1);
        return r;
    }

    private static TrimConfig.TrimRule sentry() {
        TrimConfig.TrimRule r = rule("minecraft:sentry", "卫戍");
        r.base = attr("minecraft:generic.armor", 0.4);
        r.stages.add(sAttrPP(4, "toughness", "minecraft:generic.armor_toughness", 0.1));
        return r;
    }

    private static TrimConfig.TrimRule vex() {
        TrimConfig.TrimRule r = rule("minecraft:vex", "卫戍");
        r.base = attr("minecraft:generic.attack_damage", 0.3);
        return r;
    }

    private static TrimConfig.TrimRule ward() {
        TrimConfig.TrimRule r = rule("minecraft:ward", "卫戍");
        r.base = eff("minecraft:resistance", 2);
        r.stages.add(sEff(7, "absorb", "minecraft:absorption", 2));
        r.stages.add(sEff(10, "strength", "minecraft:strength", 3));
        return r;
    }

    private static TrimConfig.TrimRule silence() {
        TrimConfig.TrimRule r = rule("minecraft:silence", "幽域");
        r.base = attr("minecraft:player.sneaking_speed", 0.03, -1.0, "multiply_base");
        return r;
    }

    private static TrimConfig.TrimRule shaper() {
        TrimConfig.TrimRule r = rule("minecraft:shaper", "幽域");
        r.base = eff("minecraft:haste", 3);
        r.stages.add(sAttr(10, "reach", "minecraft:player.block_interaction_range", 2.0));
        return r;
    }

    private static TrimConfig.TrimRule bolt() {
        TrimConfig.TrimRule r = rule("minecraft:bolt", "幽域");
        r.base = attr("minecraft:generic.attack_speed", 0.1);
        r.stages.add(sAttr(7, "sweep", "minecraft:player.sweeping_damage_ratio", 1.5));
        r.stages.add(sAttr(10, "toughness", "minecraft:generic.armor_toughness", 2.0));
        return r;
    }

    private static TrimConfig.TrimRule eye() {
        TrimConfig.TrimRule r = rule("minecraft:eye", "血肉");
        r.base = attr("minecraft:generic.luck", 0.25);
        r.stages.add(sEff(10, "night", "minecraft:night_vision", 1));
        return r;
    }

    private static TrimConfig.TrimRule rib() {
        TrimConfig.TrimRule r = rule("minecraft:rib", "血肉");
        r.base = attr("minecraft:generic.max_health", 1.0);
        return r;
    }

    private static TrimConfig.TrimRule snout() {
        TrimConfig.TrimRule r = rule("minecraft:snout", "血肉");
        r.base = attr("minecraft:generic.knockback_resistance", 0.03);
        return r;
    }

    private static TrimConfig.TrimRule wayfinder() {
        TrimConfig.TrimRule r = rule("minecraft:wayfinder", "庶民");
        r.base = attr("minecraft:player.block_interaction_range", 0.1, 1.0, "add");
        r.stages.add(sAttr(10, "entity_reach", "minecraft:player.entity_interaction_range", 2.0));
        return r;
    }

    private static TrimConfig.TrimRule raiser() {
        TrimConfig.TrimRule r = rule("minecraft:raiser", "庶民");
        r.base = eff("minecraft:regeneration", 2);
        r.stages.add(sAttr(7, "health", "minecraft:generic.max_health", 4.0));
        r.stages.add(sEff(10, "luck", "minecraft:luck", 1));
        r.stages.add(sEff(10, "jump", "minecraft:jump_boost", 1));
        return r;
    }

    private static TrimConfig.TrimRule host() {
        TrimConfig.TrimRule r = rule("minecraft:host", "庶民");
        r.base = eff("minecraft:absorption", 3);
        r.stages.add(sEff(4, "wind", "minecraft:wind_charged", 1));
        r.stages.add(sEff(7, "weave", "minecraft:weaving", 1));
        r.stages.add(sEff(10, "ooze", "minecraft:oozing", 1));
        r.stages.add(sEff(10, "resist", "minecraft:resistance", 1));
        return r;
    }
}
