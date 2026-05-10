package com.elfmcys.yesstevemodel.client;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

final class LegacyYsmArchiveExtractor {
    private static final byte[] YSGP_MAGIC = new byte[]{'Y', 'S', 'G', 'P'};
    private static final Path CACHE_ROOT = Path.of("config", "yes_steve_model", "cache", "client", "local_ysm");

    private LegacyYsmArchiveExtractor() {
    }

    static Optional<Path> extractIfLegacy(Path source, byte[] data) throws Exception {
        int version = getLegacyVersion(data);
        if (version != 1 && version != 2) {
            return Optional.empty();
        }

        Path outputDir = CACHE_ROOT.resolve(hashName(data));
        recreateDirectory(outputDir);
        extractResources(data, version, outputDir);
        createManifestIfMissing(source, outputDir);
        return Optional.of(outputDir);
    }

    private static int getLegacyVersion(byte[] data) {
        if (data.length < 24 || !startsWithMagic(data)) {
            return -1;
        }
        int version = readIntBE(data, 4);
        return version == 1 || version == 2 ? version : -1;
    }

    private static boolean startsWithMagic(byte[] data) {
        if (data.length < YSGP_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < YSGP_MAGIC.length; i++) {
            if (data[i] != YSGP_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static void extractResources(byte[] data, int version, Path outputDir) throws Exception {
        int offset = 8;
        requireRemaining(data, offset, 16);
        offset += 16;

        while (offset < data.length) {
            requireRemaining(data, offset, 4);
            int nameLength = readIntBE(data, offset);
            offset += 4;
            if (nameLength <= 0) {
                throw new IOException("Invalid YSGP resource name length: " + nameLength);
            }
            requireRemaining(data, offset, nameLength);
            byte[] nameBytes = Arrays.copyOfRange(data, offset, offset + nameLength);
            offset += nameLength;

            String resourceName = version == 2
                    ? new String(Base64.getDecoder().decode(nameBytes), StandardCharsets.UTF_8)
                    : new String(nameBytes, StandardCharsets.UTF_8);

            requireRemaining(data, offset, 4);
            int encryptedDataLength = readIntBE(data, offset);
            offset += 4;
            if (encryptedDataLength <= 0) {
                throw new IOException("Invalid YSGP resource data length: " + encryptedDataLength);
            }

            byte[] aesKey;
            byte[] iv;
            byte[] encryptedData;
            if (version == 2) {
                requireRemaining(data, offset, 4);
                int encryptedKeyLength = readIntBE(data, offset);
                offset += 4;
                if (encryptedKeyLength != 0x20) {
                    throw new IOException("Invalid YSGP encrypted key length: " + encryptedKeyLength);
                }
                requireRemaining(data, offset, encryptedKeyLength + 16 + encryptedDataLength);
                byte[] encryptedKey = Arrays.copyOfRange(data, offset, offset + encryptedKeyLength);
                offset += encryptedKeyLength;
                iv = Arrays.copyOfRange(data, offset, offset + 16);
                offset += 16;
                encryptedData = Arrays.copyOfRange(data, offset, offset + encryptedDataLength);
                offset += encryptedDataLength;
                aesKey = decryptAesCbcNoPadding(encryptedKey, deriveRandomKey(encryptedData), iv);
            } else {
                requireRemaining(data, offset, 16 + 16 + encryptedDataLength);
                aesKey = Arrays.copyOfRange(data, offset, offset + 16);
                offset += 16;
                iv = Arrays.copyOfRange(data, offset, offset + 16);
                offset += 16;
                encryptedData = Arrays.copyOfRange(data, offset, offset + encryptedDataLength);
                offset += encryptedDataLength;
            }

            byte[] decrypted = decryptAesCbcNoPadding(encryptedData, aesKey, iv);
            writeResource(outputDir, resourceName, inflateZlib(decrypted));
        }
    }

    private static byte[] deriveRandomKey(byte[] encryptedData) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(encryptedData);
        long seed = 0L;
        for (int i = 8; i < 16; i++) {
            seed = (seed << 8) | (digest[i] & 0xFFL);
        }
        byte[] key = new byte[16];
        new Random(seed).nextBytes(key);
        return key;
    }

    private static byte[] decryptAesCbcNoPadding(byte[] encrypted, byte[] key, byte[] iv) throws Exception {
        if (encrypted.length % 16 != 0) {
            throw new IOException("AES/CBC payload length is not block aligned.");
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, 0, 16, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(encrypted);
    }

    private static byte[] inflateZlib(byte[] data) throws DataFormatException {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        byte[] buffer = new byte[8192];
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, data.length * 2))) {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count > 0) {
                    out.write(buffer, 0, count);
                    continue;
                }
                if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw new DataFormatException("Zlib stream ended before completion.");
                }
                throw new DataFormatException("Zlib inflater made no progress.");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new DataFormatException(e.getMessage());
        } finally {
            inflater.end();
        }
    }

    private static void writeResource(Path outputDir, String resourceName, byte[] data) throws IOException {
        String normalizedName = resourceName.replace('\\', '/');
        if (normalizedName.isBlank() || normalizedName.indexOf('\0') >= 0
                || normalizedName.startsWith("/") || normalizedName.matches("^[A-Za-z]:.*")) {
            throw new IOException("Unsafe YSGP resource path: " + resourceName);
        }

        Path target = outputDir;
        for (String segment : normalizedName.split("/+")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new IOException("Unsafe YSGP resource path: " + resourceName);
            }
            target = target.resolve(segment);
        }

        Path normalizedOutput = outputDir.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedOutput)) {
            throw new IOException("Unsafe YSGP resource path: " + resourceName);
        }

        Files.createDirectories(normalizedTarget.getParent());
        Files.write(normalizedTarget, data);
    }

    private static void createManifestIfMissing(Path source, Path outputDir) throws IOException {
        if (Files.exists(outputDir.resolve("ysm.json"))) {
            return;
        }

        List<String> resources;
        try (Stream<Path> paths = Files.walk(outputDir)) {
            resources = paths.filter(Files::isRegularFile)
                    .map(path -> outputDir.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }

        String mainModel = pickResource(resources, "models/main.json", "main.json");
        if (mainModel == null) {
            mainModel = resources.stream()
                    .filter(LegacyYsmArchiveExtractor::isGeometryJson)
                    .findFirst()
                    .orElseThrow(() -> new IOException("Legacy YSGP archive does not contain a player model json."));
        }
        String armModel = pickResource(resources, "models/arm.json", "arm.json");

        List<String> textures = resources.stream()
                .filter(LegacyYsmArchiveExtractor::isImage)
                .toList();
        Map<String, String> animations = collectAnimations(resources);
        String modelName = stripExtension(source.getFileName().toString());
        String defaultTexture = textures.isEmpty() ? "default" : stripExtension(fileName(textures.get(0)));

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"spec\": 2,\n");
        json.append("  \"metadata\": {\n");
        json.append("    \"name\": \"").append(escapeJson(modelName)).append("\",\n");
        json.append("    \"tips\": \"Legacy YSGP local model\"\n");
        json.append("  },\n");
        json.append("  \"properties\": {\n");
        json.append("    \"height_scale\": 0.7,\n");
        json.append("    \"width_scale\": 0.7,\n");
        json.append("    \"default_texture\": \"").append(escapeJson(defaultTexture)).append("\",\n");
        json.append("    \"free\": true\n");
        json.append("  },\n");
        json.append("  \"files\": {\n");
        json.append("    \"player\": {\n");
        json.append("      \"model\": {\n");
        json.append("        \"main\": \"").append(escapeJson(mainModel)).append("\"");
        if (armModel != null) {
            json.append(",\n        \"arm\": \"").append(escapeJson(armModel)).append("\"\n");
        } else {
            json.append("\n");
        }
        json.append("      }");

        if (!animations.isEmpty()) {
            json.append(",\n      \"animation\": {\n");
            int index = 0;
            for (Map.Entry<String, String> entry : animations.entrySet()) {
                if (index++ > 0) {
                    json.append(",\n");
                }
                json.append("        \"").append(escapeJson(entry.getKey())).append("\": \"").append(escapeJson(entry.getValue())).append("\"");
            }
            json.append("\n      }");
        }

        if (!textures.isEmpty()) {
            json.append(",\n      \"texture\": [\n");
            for (int i = 0; i < textures.size(); i++) {
                if (i > 0) {
                    json.append(",\n");
                }
                json.append("        \"").append(escapeJson(textures.get(i))).append("\"");
            }
            json.append("\n      ]");
        }

        json.append("\n    }\n");
        json.append("  }\n");
        json.append("}\n");

        Files.writeString(outputDir.resolve("ysm.json"), json.toString(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> collectAnimations(List<String> resources) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        String[] preferred = new String[]{
                "main", "arm", "extra", "tac", "carryon", "parcool", "swem", "slashblade", "tlm", "fp.arm"
        };
        for (String key : preferred) {
            String file = pickResource(resources, "animations/" + key + ".animation.json", key + ".animation.json");
            if (file != null) {
                result.put(normalizeAnimationKey(key), file);
            }
        }
        for (String resource : resources) {
            if (resource.endsWith(".animation.json")) {
                String key = normalizeAnimationKey(stripAnimationSuffix(fileName(resource)));
                result.putIfAbsent(key, resource);
            }
        }
        return result;
    }

    private static String normalizeAnimationKey(String key) {
        return key.replace('.', '_');
    }

    private static String stripAnimationSuffix(String fileName) {
        String suffix = ".animation.json";
        return fileName.endsWith(suffix) ? fileName.substring(0, fileName.length() - suffix.length()) : stripExtension(fileName);
    }

    private static String pickResource(List<String> resources, String... candidates) {
        for (String candidate : candidates) {
            for (String resource : resources) {
                if (resource.equals(candidate)) {
                    return resource;
                }
            }
        }
        return null;
    }

    private static boolean isGeometryJson(String resource) {
        String lower = resource.toLowerCase(Locale.ROOT);
        return lower.endsWith(".json")
                && !"ysm.json".equals(lower)
                && !lower.endsWith(".animation.json")
                && !lower.contains("/controller/")
                && !lower.contains("controller");
    }

    private static boolean isImage(String resource) {
        String lower = resource.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".avif");
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String escapeJson(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.toString();
    }

    private static String hashName(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            builder.append(String.format("%02x", b & 0xFF));
        }
        return builder.toString();
    }

    private static void recreateDirectory(Path directory) throws IOException {
        Path root = CACHE_ROOT.toAbsolutePath().normalize();
        Path target = directory.toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Refusing to clear directory outside local YSM cache: " + directory);
        }
        if (Files.exists(target)) {
            try (Stream<Path> paths = Files.walk(target)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        Files.createDirectories(target);
    }

    private static int readIntBE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static void requireRemaining(byte[] data, int offset, int length) throws IOException {
        if (length < 0 || offset < 0 || offset > data.length - length) {
            throw new IOException("Malformed YSGP archive.");
        }
    }
}
