package com.elfmcys.yesstevemodel.client;

import com.elfmcys.yesstevemodel.YesSteveModel;
import com.elfmcys.yesstevemodel.capability.PlayerCapability;
import com.elfmcys.yesstevemodel.capability.PlayerCapabilityProvider;
import com.elfmcys.yesstevemodel.client.model.ModelAssembly;
import com.elfmcys.yesstevemodel.client.model.ModelAssemblyFactory;
import com.elfmcys.yesstevemodel.geckolib3.core.AnimatableEntity;
import com.elfmcys.yesstevemodel.model.ServerModelManager;
import com.elfmcys.yesstevemodel.network.NetworkHandler;
import com.elfmcys.yesstevemodel.network.message.C2SRequestSwitchModelPacket;
import com.elfmcys.yesstevemodel.resource.YSMBinaryDeserializer;
import com.elfmcys.yesstevemodel.resource.YSMClientMapper;
import com.elfmcys.yesstevemodel.resource.YSMFolderDeserializer;
import com.elfmcys.yesstevemodel.resource.models.ModelPackData;
import com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel;
import com.elfmcys.yesstevemodel.util.FileTypeUtil;
import com.elfmcys.yesstevemodel.util.YSMThreadPool;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.commons.lang3.StringUtils;
import rip.ysm.security.YsmCrypt;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@OnlyIn(Dist.CLIENT)
public final class ClientLocalModelManager {

    public static final String LOCAL_MODEL_PREFIX = "local/";

    public static final String LOCAL_ROOT_PACK = "local/";

    private static final Path SELECTION_FILE = ServerModelManager.FOLDER.resolve("local_selection.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final AtomicBoolean RELOADING = new AtomicBoolean(false);

    private static volatile boolean restoreAfterReload;

    private ClientLocalModelManager() {
    }

    public static boolean isLocalModelId(String modelId) {
        return modelId != null && modelId.startsWith(LOCAL_MODEL_PREFIX);
    }

    public static boolean isLocalPackPath(String path) {
        return path != null && path.startsWith(LOCAL_MODEL_PREFIX);
    }

    public static boolean isLocalModelActive() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        boolean[] result = new boolean[]{false};
        minecraft.player.getCapability(PlayerCapabilityProvider.PLAYER_CAP).ifPresent(cap -> {
            result[0] = isLocalModelId(cap.getModelId());
        });
        return result[0];
    }

    public static boolean isLocalAnimatable(AnimatableEntity<?> animatableEntity) {
        if (animatableEntity instanceof PlayerCapability cap) {
            return isLocalModelId(cap.getModelId());
        }
        return animatableEntity != null && isCurrentClientPlayer(animatableEntity.getEntity()) && isLocalModelActive();
    }

    public static void reloadLocalModelsAsync(boolean restoreSelection) {
        restoreAfterReload = restoreAfterReload || restoreSelection;
        Minecraft.getInstance().execute(() -> {
            if (ClientModelManager.getLocalModelContext() == null) {
                YesSteveModel.LOGGER.warn("[YSM] Deferred local model reload because the primary model is not ready.");
                return;
            }
            if (!RELOADING.compareAndSet(false, true)) {
                return;
            }
            YSMThreadPool.submit(() -> {
                LoadResult result = loadLocalModels();
                Minecraft.getInstance().execute(() -> {
                    try {
                        ClientModelManager.replaceLocalModels(result.models(), result.packs());
                        if (result.failureCount() > 0 && Minecraft.getInstance().player != null) {
                            Minecraft.getInstance().player.sendSystemMessage(Component.translatable("message.yes_steve_model.local_model.load_failed", result.failureCount()));
                        }
                        if (restoreAfterReload) {
                            restoreCurrentSelection();
                        }
                    } finally {
                        restoreAfterReload = false;
                        RELOADING.set(false);
                    }
                });
            });
        });
    }

    public static void reloadLocalModelsAsync() {
        reloadLocalModelsAsync(true);
    }

    public static void clearRuntimeState() {
        restoreAfterReload = false;
        RELOADING.set(false);
    }

    public static void saveCurrentSelection(String modelId, String textureId) {
        if (!isLocalModelId(modelId)) {
            return;
        }
        String key = getSelectionKey();
        if (key == null) {
            return;
        }
        JsonObject root = readSelectionRoot();
        JsonObject entry = new JsonObject();
        entry.addProperty("model", modelId);
        entry.addProperty("texture", StringUtils.defaultIfBlank(textureId, FileTypeUtil.DEFAULT_TEXTURE));
        root.add(key, entry);
        writeSelectionRoot(root);
    }

    public static void clearCurrentSelection() {
        String key = getSelectionKey();
        if (key == null) {
            return;
        }
        JsonObject root = readSelectionRoot();
        if (root.remove(key) != null) {
            writeSelectionRoot(root);
        }
    }

    public static boolean restoreCurrentSelection() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return false;
        }
        Optional<LocalSelection> selection = getCurrentSelection();
        if (selection.isEmpty()) {
            return false;
        }
        boolean[] restored = new boolean[]{false};
        minecraft.player.getCapability(PlayerCapabilityProvider.PLAYER_CAP).ifPresent(cap -> {
            restored[0] = applySelectionIfAvailable(cap, selection.get(), true, true);
        });
        return restored[0];
    }

    public static boolean restoreSelectionFor(Entity entity, PlayerCapability cap) {
        if (!isCurrentClientPlayer(entity)) {
            return false;
        }
        Optional<LocalSelection> selection = getCurrentSelection();
        return selection.filter(localSelection -> applySelectionIfAvailable(cap, localSelection, false, false)).isPresent();
    }

    public static void syncServerVisibleDefault() {
        if (NetworkHandler.isClientConnected()) {
            NetworkHandler.sendToServer(new C2SRequestSwitchModelPacket(FileTypeUtil.DEFAULT_MODEL_ID, FileTypeUtil.DEFAULT_TEXTURE));
        }
    }

    private static boolean applySelectionIfAvailable(PlayerCapability cap, LocalSelection selection, boolean syncDefault, boolean notifyFailure) {
        if (!ClientModelManager.getModelContext(selection.modelId()).isPresent()) {
            clearCurrentSelection();
            if (isLocalModelId(cap.getModelId())) {
                cap.initModelWithTexture(FileTypeUtil.DEFAULT_MODEL_ID, FileTypeUtil.DEFAULT_TEXTURE);
                syncServerVisibleDefault();
            }
            if (notifyFailure && Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.sendSystemMessage(Component.translatable("message.yes_steve_model.local_model.restore_failed", selection.modelId()));
            }
            return false;
        }
        cap.initModelWithTexture(selection.modelId(), selection.textureId());
        saveCurrentSelection(cap.getModelId(), cap.getCurrentTextureName());
        if (syncDefault) {
            syncServerVisibleDefault();
        }
        return true;
    }

    private static LoadResult loadLocalModels() {
        Map<String, ModelAssembly> models = new LinkedHashMap<>();
        Map<String, ModelPackData> packs = createLocalRootPackMap();
        int failures = 0;
        Path customDir = ServerModelManager.CUSTOM;
        try {
            Files.createDirectories(customDir);
        } catch (IOException e) {
            YesSteveModel.LOGGER.error("[YSM] Failed to create local model directory: " + customDir, e);
            return new LoadResult(models, packs, 1);
        }

        try (Stream<Path> stream = Files.walk(customDir)) {
            for (Path ysmJson : stream.filter(Files::isRegularFile).filter(path -> "ysm.json".equals(path.getFileName().toString())).toList()) {
                Path modelDir = ysmJson.getParent();
                String modelId = toLocalModelId(customDir, modelDir);
                if (StringUtils.isBlank(modelId) || LOCAL_MODEL_PREFIX.equals(modelId)) {
                    continue;
                }
                if (!loadFolderModel(modelDir, modelId, models)) {
                    failures++;
                }
            }
        } catch (Exception e) {
            failures++;
            YesSteveModel.LOGGER.error("[YSM] Failed to scan local folder models: " + customDir, e);
        }

        try (Stream<Path> stream = Files.walk(customDir)) {
            for (Path archive : stream.filter(Files::isRegularFile).filter(ClientLocalModelManager::isLocalArchive).toList()) {
                String modelId = toLocalModelId(customDir, archive);
                if (StringUtils.isBlank(modelId) || models.containsKey(modelId)) {
                    continue;
                }
                if (!loadArchiveModel(archive, modelId, models)) {
                    failures++;
                }
            }
        } catch (Exception e) {
            failures++;
            YesSteveModel.LOGGER.error("[YSM] Failed to scan local archive models: " + customDir, e);
        }

        return new LoadResult(models, packs, failures);
    }

    private static boolean loadFolderModel(Path source, String modelId, Map<String, ModelAssembly> models) {
        try (YSMFolderDeserializer deserializer = new YSMFolderDeserializer(source)) {
            RawYsmModel rawModel = deserializer.deserialize();
            models.put(modelId, buildModelAssembly(rawModel, modelId));
            return true;
        } catch (Exception e) {
            YesSteveModel.LOGGER.error("[YSM] Failed to load local folder model: " + source, e);
            return false;
        }
    }

    private static boolean loadArchiveModel(Path source, String modelId, Map<String, ModelAssembly> models) {
        String fileName = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".zip")) {
            return loadFolderModel(source, modelId, models);
        }
        try {
            byte[] raw = Files.readAllBytes(source);
            Optional<Path> legacyArchive = LegacyYsmArchiveExtractor.extractIfLegacy(source, raw);
            if (legacyArchive.isPresent()) {
                return loadFolderModel(legacyArchive.get(), modelId, models);
            }
            byte[] decrypted = YsmCrypt.decryptYsmFile(raw);
            try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(decrypted)) {
                RawYsmModel rawModel = deserializer.deserializeKeepOpen();
                deserializer.parseYSMFooter(rawModel);
                models.put(modelId, buildModelAssembly(rawModel, modelId));
                return true;
            }
        } catch (Exception binaryFailure) {
            if (loadFolderModel(source, modelId, models)) {
                return true;
            }
            YesSteveModel.LOGGER.error("[YSM] Failed to load local ysm model: " + source, binaryFailure);
            return false;
        }
    }

    private static ModelAssembly buildModelAssembly(RawYsmModel rawModel, String modelId) {
        ClientModelInfo parsedBundle = YSMClientMapper.buildParsedBundle(rawModel, modelId);
        return ModelAssemblyFactory.buildAssembly(parsedBundle, false, false);
    }

    private static Map<String, ModelPackData> createLocalRootPackMap() {
        Map<String, Map<String, String>> translations = new HashMap<>();
        Map<String, String> english = new HashMap<>();
        english.put("name", "Local Models");
        english.put("description", "Models loaded from this client's config/yes_steve_model/custom directory.");
        translations.put("en_us", english);
        Map<String, String> chinese = new HashMap<>();
        chinese.put("name", "本地模型");
        chinese.put("description", "从本客户端 config/yes_steve_model/custom 目录加载的模型。");
        translations.put("zh_cn", chinese);

        Map<String, ModelPackData> packs = new LinkedHashMap<>();
        packs.put(LOCAL_ROOT_PACK, new ModelPackData(LOCAL_ROOT_PACK, "Local Models", "Client local models", null, translations));
        return packs;
    }

    private static boolean isLocalArchive(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".zip") || fileName.endsWith(".ysm");
    }

    private static String toLocalModelId(Path baseDir, Path path) {
        return LOCAL_MODEL_PREFIX + baseDir.relativize(path).toString().replace('\\', '/');
    }

    private static Optional<LocalSelection> getCurrentSelection() {
        String key = getSelectionKey();
        if (key == null) {
            return Optional.empty();
        }
        JsonObject root = readSelectionRoot();
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject object = element.getAsJsonObject();
        String modelId = object.has("model") ? object.get("model").getAsString() : null;
        String textureId = object.has("texture") ? object.get("texture").getAsString() : FileTypeUtil.DEFAULT_TEXTURE;
        if (!isLocalModelId(modelId)) {
            return Optional.empty();
        }
        return Optional.of(new LocalSelection(modelId, StringUtils.defaultIfBlank(textureId, FileTypeUtil.DEFAULT_TEXTURE)));
    }

    private static JsonObject readSelectionRoot() {
        if (!Files.exists(SELECTION_FILE)) {
            return new JsonObject();
        }
        try {
            JsonElement element = JsonParser.parseString(Files.readString(SELECTION_FILE, StandardCharsets.UTF_8));
            if (element != null && element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        } catch (Exception e) {
            YesSteveModel.LOGGER.error("[YSM] Failed to read local model selection file: " + SELECTION_FILE, e);
        }
        return new JsonObject();
    }

    private static void writeSelectionRoot(JsonObject root) {
        try {
            Files.createDirectories(SELECTION_FILE.getParent());
            Files.writeString(SELECTION_FILE, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            YesSteveModel.LOGGER.error("[YSM] Failed to write local model selection file: " + SELECTION_FILE, e);
        }
    }

    private static String getSelectionKey() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null) {
            return null;
        }
        return getCurrentServerKey() + "|" + player.getUUID();
    }

    private static String getCurrentServerKey() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isLocalServer()) {
            return "local";
        }
        ServerData serverData = minecraft.getCurrentServer();
        if (serverData != null && StringUtils.isNotBlank(serverData.ip)) {
            return serverData.ip;
        }
        if (minecraft.getConnection() != null && minecraft.getConnection().getConnection() != null) {
            SocketAddress remoteAddress = minecraft.getConnection().getConnection().getRemoteAddress();
            if (remoteAddress != null) {
                return remoteAddress.toString();
            }
        }
        return "unknown";
    }

    private static boolean isCurrentClientPlayer(Entity entity) {
        return entity != null && entity == Minecraft.getInstance().player;
    }

    private record LocalSelection(String modelId, String textureId) {
    }

    private record LoadResult(Map<String, ModelAssembly> models, Map<String, ModelPackData> packs, int failureCount) {
    }
}
