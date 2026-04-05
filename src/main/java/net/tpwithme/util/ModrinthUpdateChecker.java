package net.tpwithme.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.tpwithme.TpWithMe;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ModrinthUpdateChecker {

    private static final String PROJECT_ID = "wc6Vjaxn";
    private static final String RELEASE = "release";
    private static final String FABRIC = "fabric";
    private static final String MINECRAFT_MOD_ID = "minecraft";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();
    private static final AtomicBoolean CHECK_STARTED = new AtomicBoolean(false);

    private ModrinthUpdateChecker() {
    }

    public static void checkOnceAsync() {
        if (!CHECK_STARTED.compareAndSet(false, true)) {
            return;
        }

        Thread thread = new Thread(ModrinthUpdateChecker::checkForUpdate, "tpwithme-modrinth-update-check");
        thread.setDaemon(true);
        thread.start();
    }

    private static void checkForUpdate() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.modrinth.com/v2/project/" + PROJECT_ID + "/version"))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "TpWithMe/" + currentVersion())
                .GET()
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                TpWithMe.LOGGER.debug("{} Update check returned HTTP {}.", TpWithMe.prefix(), response.statusCode());
                return;
            }

            Optional<VersionCandidate> latestVersion = extractLatestVersion(response.body(), currentMinecraftVersion());
            if (latestVersion.isEmpty()) {
                TpWithMe.LOGGER.debug("{} Update check returned no usable versions.", TpWithMe.prefix());
                return;
            }

            String currentVersion = currentVersion();
            String newestVersion = latestVersion.get().versionNumber();
            if (isNewerVersion(newestVersion, currentVersion)) {
                TpWithMe.LOGGER.info("{} New version available: {} (current: {})",
                        TpWithMe.prefix(),
                        newestVersion, currentVersion);
            } else {
                TpWithMe.LOGGER.debug("{} No newer version available (current: {}, latest compatible: {}).",
                        TpWithMe.prefix(),
                        currentVersion, newestVersion);
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            TpWithMe.LOGGER.debug("{} Update check failed.", TpWithMe.prefix(), e);
        }
    }

    private static Optional<VersionCandidate> extractLatestVersion(String responseBody, String minecraftVersion) {
        JsonElement root = JsonParser.parseString(responseBody);
        if (!root.isJsonArray()) {
            return Optional.empty();
        }

        JsonArray versions = root.getAsJsonArray();
        VersionCandidate newestCompatibleRelease = null;
        VersionCandidate newestRelease = null;

        for (JsonElement versionElement : versions) {
            if (!versionElement.isJsonObject()) {
                continue;
            }

            JsonObject versionObject = versionElement.getAsJsonObject();
            String versionNumber = getString(versionObject, "version_number");
            if (versionNumber == null || versionNumber.isBlank()) {
                continue;
            }

            Instant publishedAt = getPublishedAt(versionObject);
            if (publishedAt == null) {
                continue;
            }

            String versionType = getString(versionObject, "version_type");
            if (!RELEASE.equalsIgnoreCase(versionType)) {
                continue;
            }

            VersionCandidate candidate = new VersionCandidate(versionNumber, publishedAt);
            newestRelease = newestOf(newestRelease, candidate);

            if (jsonArrayContains(versionObject, "loaders", FABRIC)
                    && jsonArrayContains(versionObject, "game_versions", minecraftVersion)) {
                newestCompatibleRelease = newestOf(newestCompatibleRelease, candidate);
            }
        }

        return Optional.ofNullable(newestCompatibleRelease != null ? newestCompatibleRelease : newestRelease);
    }

    private static String getString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }

        return value.getAsString();
    }

    private static boolean jsonArrayContains(JsonObject object, String key, String expectedValue) {
        if (expectedValue == null || expectedValue.isBlank()) {
            return false;
        }

        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray()) {
            return false;
        }

        for (JsonElement element : value.getAsJsonArray()) {
            if (element != null && element.isJsonPrimitive() && expectedValue.equalsIgnoreCase(element.getAsString())) {
                return true;
            }
        }

        return false;
    }

    private static Instant getPublishedAt(JsonObject object) {
        String publishedAt = getString(object, "date_published");
        if (publishedAt == null || publishedAt.isBlank()) {
            return null;
        }

        try {
            return Instant.parse(publishedAt);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static VersionCandidate newestOf(VersionCandidate first, VersionCandidate second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }

        return second.publishedAt().isAfter(first.publishedAt()) ? second : first;
    }

    private static String currentVersion() {
        return FabricLoader.getInstance()
                .getModContainer(TpWithMe.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static String currentMinecraftVersion() {
        return FabricLoader.getInstance()
                .getModContainer(MINECRAFT_MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static boolean isNewerVersion(String candidate, String current) {
        try {
            Version candidateVersion = Version.parse(candidate);
            Version currentVersion = Version.parse(current);
            return candidateVersion.compareTo(currentVersion) > 0;
        } catch (VersionParsingException e) {
            TpWithMe.LOGGER.debug("{} Could not compare versions '{}' and '{}'.",
                    TpWithMe.prefix(), candidate, current, e);
            return false;
        }
    }

    private record VersionCandidate(String versionNumber, Instant publishedAt) {
    }
}
