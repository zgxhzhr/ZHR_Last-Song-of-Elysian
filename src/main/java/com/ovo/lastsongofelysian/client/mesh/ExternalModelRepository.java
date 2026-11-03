package com.ovo.lastsongofelysian.client.mesh;

import com.mojang.blaze3d.platform.NativeImage;
import com.ovo.lastsongofelysian.lastsongofelysian;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 外部模型仓库：从 {@code .minecraft/lse-models/<variant>/} 读取玩家自备的 PMX。
 *
 * <p>模型不进 jar。原因见 {@link PmxReader} 的类注释——MMD 模型授权普遍禁止
 * 二次配布，把顶点/贴图打进 jar 就是分发模型内容。
 *
 * <p>目录约定（三者任一即可，按顺序找）：
 * <ol>
 *   <li>{@code lse-models/<variant>/model.pmx}</li>
 *   <li>{@code lse-models/<variant>/} 下的第一个 {@code *.pmx}</li>
 *   <li>{@code lse-models/<variant>.pmx}（平铺）</li>
 * </ol>
 * 贴图按 PMX 里记录的相对路径查找，找不到会回退到同目录忽略大小写再找。
 *
 * <p>解析与贴图上传都必须在渲染线程做（{@link DynamicTexture} 依赖 GL 上下文），
 * 所以这里的所有入口都从渲染器调用。
 */
public final class ExternalModelRepository {

    private static final Logger LOG = LoggerFactory.getLogger("lastsongofelysian/mmd");

    private static final Map<String, Loaded> CACHE = new HashMap<>();
    private static final Map<String, ResourceLocation> TEXTURE_CACHE = new HashMap<>();

    /** 贴图缺失时的品红占位，避免整块模型用错贴图而看不出来。 */
    private static ResourceLocation missingTexture;

    private ExternalModelRepository() {
    }

    /** 一个已加载模型：网格 + 每个贴图槽的 GL 纹理 id（-1 表示无）。 */
    public record Loaded(MeshModel model, int[] textureIds) {
    }

    /** 原始模型根目录：{@code <gameDir>/lse-models}。 */
    public static Path rootDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("lse-models");
    }

    /**
     * 取模型。首次调用会解析 PMX 并上传贴图；失败返回 {@code null} 且只记一次日志。
     *
     * @param variant 小写变体名，如 {@code mobius}
     */
    public static Loaded get(String variant) {
        String key = variant.toLowerCase(Locale.ROOT);
        // 用 containsKey 而不是非空判断：加载失败也要缓存（值为 null），
        // 否则渲染器每帧都会重试一次，日志被刷爆。
        if (CACHE.containsKey(key)) {
            return CACHE.get(key);
        }
        Loaded loaded = load(key);
        CACHE.put(key, loaded);
        return loaded;
    }

    /** 清空缓存，用于玩家改了模型文件后重载。 */
    public static void invalidate() {
        CACHE.clear();
        TEXTURE_CACHE.clear();
    }

    private static Loaded load(String key) {
        Path pmx = findPmx(key);
        if (pmx == null) {
            LOG.warn("[MMD] 没找到模型：在 {} 或 {}.pmx 下都找不到 .pmx 文件",
                    rootDir().resolve(key), rootDir().resolve(key));
            return null;
        }
        try {
            long t0 = System.nanoTime();
            MeshModel model = PmxReader.parse(pmx);
            int[] texIds = new int[model.textures.size()];
            for (int i = 0; i < texIds.length; i++) {
                texIds[i] = bindTexture(key, model.textures.get(i));
            }
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            LOG.info("[MMD] 已加载 {} ← {}（顶点 {} / 三角形 {} / 材质 {} / 骨骼 {}，用时 {} ms）",
                    key, pmx.getFileName(), model.vertexCount, model.triangleCount,
                    model.materials.size(), model.bones.size(), String.format("%.1f", ms));
            return new Loaded(model, texIds);
        } catch (IOException | RuntimeException e) {
            LOG.error("[MMD] 解析 {} 失败：{}", pmx, e.toString());
            return null;
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static Path findPmx(String key) {
        Path dir = rootDir().resolve(key);
        if (Files.isDirectory(dir)) {
            Path canonical = dir.resolve("model.pmx");
            if (Files.isRegularFile(canonical)) {
                return canonical;
            }
            // 一个目录里可能同时躺着主模型和道具（书、画笔、替身等）。
            // 主模型体积总是最大的，按体积取比按文件名排序可靠得多。
            try (Stream<Path> s = Files.list(dir)) {
                return s.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pmx"))
                        .max((a, b) -> Long.compare(sizeOf(a), sizeOf(b)))
                        .orElse(null);
            } catch (IOException e) {
                return null;
            }
        }
        Path flat = rootDir().resolve(key + ".pmx");
        return Files.isRegularFile(flat) ? flat : null;
    }

    /**
     * 从磁盘读一张贴图并注册成动态纹理，返回 GL id。
     *
     * @param rawPath PMX 里解析出来的绝对路径，空串表示该槽位没有贴图
     */
    private static int bindTexture(String variant, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return -1;
        }
        ResourceLocation cached = TEXTURE_CACHE.get(rawPath);
        if (cached != null) {
            return Minecraft.getInstance().getTextureManager().getTexture(cached).getId();
        }

        ResourceLocation rl;
        try {
            rl = new ResourceLocation(lastsongofelysian.MODID,
                    "mmd/" + variant + "/" + sanitize(rawPath));
        } catch (RuntimeException e) {
            LOG.warn("[MMD] 贴图路径无法转成资源名，改用占位：{}", rawPath);
            return missingTextureId();
        }
        try (InputStream in = Files.newInputStream(Path.of(rawPath))) {
            NativeImage image = NativeImage.read(in);
            DynamicTexture texture = new DynamicTexture(image);
            texture.setFilter(false, false);
            Minecraft.getInstance().getTextureManager().register(rl, texture);
            TEXTURE_CACHE.put(rawPath, rl);
            return Minecraft.getInstance().getTextureManager().getTexture(rl).getId();
        } catch (IOException | RuntimeException e) {
            LOG.warn("[MMD] 贴图加载失败 {}：{}", rawPath, e.toString());
            return missingTextureId();
        }
    }

    /** 品红 8×8 占位贴图，只创建一次。 */
    private static int missingTextureId() {
        if (missingTexture == null) {
            NativeImage img = new NativeImage(8, 8, false);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    boolean alt = ((x >> 2) + (y >> 2)) % 2 == 0;
                    img.setPixelRGBA(x, y, alt ? 0xFFFF00FF : 0xFF000000);
                }
            }
            ResourceLocation rl = new ResourceLocation(lastsongofelysian.MODID, "mmd/__missing");
            Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(img));
            missingTexture = rl;
        }
        return Minecraft.getInstance().getTextureManager().getTexture(missingTexture).getId();
    }

    /**
     * 绝对路径转成合法的资源路径片段，加哈希防重名。
     *
     * <p>注意：只能用 ASCII 白名单。{@code Character.isLetterOrDigit} 对汉字返回 true，
     * 而 {@link ResourceLocation} 的路径只接受 {@code [a-z0-9/._-]}——MMD 贴图名
     * 大量是中日文（衣服.png / 顔.png），用 isLetterOrDigit 会直接抛
     * ResourceLocationException。
     */
    private static String sanitize(String path) {
        String name = Path.of(path).getFileName().toString();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 40; i++) {
            char c = name.charAt(i);
            boolean ascii = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (ascii) {
                sb.append(Character.toLowerCase(c));
            } else if (c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        if (sb.length() == 0) {
            sb.append("tex");
        }
        return sb + "_" + Integer.toHexString(path.hashCode());
    }
}
