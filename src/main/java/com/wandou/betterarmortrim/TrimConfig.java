package com.wandou.betterarmortrim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置结构。
 *
 * <pre>
 * config/betterarmortrim/
 *   settings.json          通用设置：tick 间隔、材质档位、强度曲线、相克对与惩罚
 *   trims/&lt;pattern&gt;.json   每种纹饰一个文件，各自独立
 * </pre>
 */
public final class TrimConfig {
    public static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public Path dir;
    public Settings settings = new Settings();
    public final Map<String, TrimRule> rules = new LinkedHashMap<>();

    // ---------------------------------------------------------------- 模型

    public static final class Settings {
        /** 总开关，false 时完全不跑（用于 A/B 测性能）。 */
        public boolean enabled = true;
        /** 多少 tick 重算一次，20 = 1 秒。 */
        public int tickInterval = 20;
        /** 强度曲线的两个拐点（对应 S）。 */
        public double knee1 = 4.0;
        public double knee2 = 8.0;
        /** 第二段、第三段的斜率。 */
        public double knee1Slope = 0.75;
        public double knee2Slope = 0.375;
        /** 材质 → 档位。 */
        public Map<String, Integer> materials = new LinkedHashMap<>();
        /** 相克对：a 与 b 同时在场即触发。light = 只触发 1 对，heavy = 同时触发 2 对。 */
        public List<Pair> pairs = new ArrayList<>();
        /** tab 列表常驻显示。position 留空 = 关闭。 */
        public Tab tab = new Tab();
    }

    public static final class Tab {
        /** header = 顶部，footer = 底部，留空 = 关闭。 */
        public String position = "footer";
        /** 前缀文字。 */
        public String prefix = "纹饰增益：";
    }

    public static final class Pair {
        public String a = "";
        public String b = "";
        public EffectSpec light = new EffectSpec();
        public EffectSpec heavy = new EffectSpec();
    }

    public static final class EffectSpec {
        public String effect = "";
        public int level = 1;
    }

    public static final class TrimRule {
        public String trim = "";
        public boolean enabled = true;
        /** 大类，用于相克判定。 */
        public String category = "";
        /** 至少几件才生效。 */
        public int minPieces = 1;
        public Modifier base = new Modifier();
        public List<Stage> stages = new ArrayList<>();
    }

    public static final class Modifier {
        /** attribute 或 effect。 */
        public String type = "attribute";
        public String attribute = "";
        public String effect = "";
        /** 属性：加值运算，add / multiply_base。 */
        public String operation = "add";
        /** 每点强度加多少（系数 × B）。 */
        public double perPoint = 0.0;
        /** 封顶，<=0 表示不封。 */
        public double max = -1.0;
        /** 药水：最高等级。 */
        public int maxLevel = 4;
    }

    public static final class Stage {
        public int at = 4;
        public String type = "attribute";
        /** 同 id 的后层会覆盖前层。 */
        public String id = "";
        public String attribute = "";
        public String effect = "";
        /** 属性：固定加值；<=0 时改用 perPoint × B。 */
        public double value = 0.0;
        public double perPoint = 0.0;
        public String operation = "add";
        /** 药水等级。 */
        public int level = 1;
    }

    // ---------------------------------------------------------------- 读写

    public static TrimConfig load(Path dir) {
        TrimConfig config = new TrimConfig();
        config.dir = dir;
        config.reload();
        return config;
    }

    public void reload() {
        try {
            Defaults.writeAll(dir);
            settings = read(dir.resolve("settings.json"), Settings.class);
            rules.clear();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir.resolve("trims"), "*.json")) {
                for (Path file : stream) {
                    TrimRule rule = read(file, TrimRule.class);
                    if (rule != null && rule.trim != null && !rule.trim.isEmpty()) {
                        rules.put(rule.trim, rule);
                    }
                }
            }
            validate();
            BetterArmorTrim.LOG.info("配置已加载：{} 条纹饰，{} 个相克对", rules.size(), settings.pairs.size());
        } catch (Exception e) {
            BetterArmorTrim.LOG.error("配置加载失败", e);
        }
    }

    /** 校验所有 id 能否解析，避免玩家穿上后才在运行时刷未知 id。 */
    private void validate() {
        int bad = 0;
        for (TrimRule rule : rules.values()) {
            if (rule.base != null) {
                bad += check(rule.trim, rule.base.type, rule.base.attribute, rule.base.effect) ? 0 : 1;
            }
            for (Stage stage : rule.stages) {
                bad += check(rule.trim, stage.type, stage.attribute, stage.effect) ? 0 : 1;
            }
        }
        for (Pair pair : settings.pairs) {
            bad += checkEffect(pair.light) ? 0 : 1;
            bad += checkEffect(pair.heavy) ? 0 : 1;
        }
        if (bad == 0) {
            BetterArmorTrim.LOG.info("配置校验通过：所有属性/效果 id 均可解析");
        } else {
            BetterArmorTrim.LOG.warn("配置校验：{} 个 id 无法解析，上面已有明细", bad);
        }
    }

    private boolean check(String trim, String type, String attribute, String effect) {
        if ("effect".equals(type)) {
            return checkEffectId(trim, effect);
        }
        return checkAttribute(trim, attribute);
    }

    private boolean checkEffect(EffectSpec spec) {
        return spec == null || checkEffectId("相克惩罚", spec.effect);
    }

    private boolean checkEffectId(String owner, String id) {
        ResourceLocation parsed = id == null || id.isEmpty() ? null : ResourceLocation.tryParse(id);
        if (parsed == null || BuiltInRegistries.MOB_EFFECT.getOptional(parsed).isEmpty()) {
            BetterArmorTrim.LOG.warn("{}：未知效果 id「{}」", owner, id);
            return false;
        }
        return true;
    }

    private boolean checkAttribute(String owner, String id) {
        ResourceLocation parsed = id == null || id.isEmpty() ? null : ResourceLocation.tryParse(id);
        if (parsed == null || BuiltInRegistries.ATTRIBUTE.getOptional(parsed).isEmpty()) {
            BetterArmorTrim.LOG.warn("{}：未知属性 id「{}」", owner, id);
            return false;
        }
        return true;
    }

    private static <T> T read(Path file, Class<T> type) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, type);
        } catch (JsonSyntaxException e) {
            BetterArmorTrim.LOG.error("JSON 语法错误：{}", file, e);
            return null;
        }
    }

    static void writeJson(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(value, writer);
        }
    }
}
