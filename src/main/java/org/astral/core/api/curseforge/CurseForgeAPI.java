package org.astral.core.api.curseforge;

import org.astral.core.api.BaseModDownloader;
import org.astral.core.config.curseforge.CurseForgeConfig;
import org.astral.core.logger.Core;
import org.astral.core.logger.Log;
import org.astral.core.setup.WorkspaceSetup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CurseForgeAPI extends BaseModDownloader {

    private static CurseForgeAPI instance = null;
    private String globalApiKey;

    private CurseForgeAPI() {}

    public static CurseForgeAPI getInstance() {
        if (instance == null) {
            instance = new CurseForgeAPI();
        }
        return instance;
    }

    public void connect(String apiKey) {
        this.globalApiKey = apiKey;
        if (apiKey == null || apiKey.isEmpty() || apiKey.equals("INSERT_YOUR_KEY_HERE")) {
            Core.atWarning(Log.CURSEFORGE).log("No se ha configurado una API Key de CurseForge válida.");
        } else {
            Core.atInfo(Log.CURSEFORGE).log("Conectado a CurseForge con API Key.");
        }
    }

    private @NotNull String readResponse(HttpURLConnection conn) throws Exception {
        StringBuilder jsonBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                jsonBuilder.append(line);
            }
        }
        return jsonBuilder.toString();
    }

    public void addModById(int projectId) {
        CurseForgeConfig cfConfig = WorkspaceSetup.getCurseForge().getConfig();
        if (cfConfig == null) return;

        connect(cfConfig.global_api_key);
        try {
            String apiUrl = "https://api.curseforge.com/v1/mods/" + projectId;
            URI uri = new URI(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("x-api-key", globalApiKey);

            if (conn.getResponseCode() != 200) {
                Core.atError(Log.CURSEFORGE).log("No se encontró ningún mod exacto con el ID: " + projectId);
                return;
            }

            String json = readResponse(conn);
            Matcher nameMatcher = Pattern.compile("\"name\":\"([^\"]+)\"").matcher(json);

            if (nameMatcher.find()) {
                String foundName = nameMatcher.group(1);
                String key = foundName.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();

                if (cfConfig.resources.containsKey(key)) {
                    Core.atWarning(Log.CURSEFORGE).log("El mod ya está registrado con la clave: '" + key + "'");
                    return;
                }

                CurseForgeConfig.CurseForgeResource newResource = CurseForgeConfig.createResource(
                        projectId,
                        WorkspaceSetup.relativize(WorkspaceSetup.getLocalModsPath())
                );

                cfConfig.resources.put(key, newResource);
                WorkspaceSetup.getCurseForge().save();

                Core.atInfo(Log.CURSEFORGE).log("PROCEDER CON LA INSTALACIÓN: " + foundName + " (ID: " + projectId + ")");
                syncMod(key);
            }

        } catch (Exception e) {
            Core.atError(Log.CURSEFORGE).log("Error al agregar por ID: " + e.getMessage());
        }
    }

    public void removeMod(String query) {
        CurseForgeConfig cfConfig = WorkspaceSetup.getCurseForge().getConfig();
        if (cfConfig == null || cfConfig.resources == null) return;
        String targetKey = findKey(query);

        if (targetKey != null) {
            cfConfig.resources.remove(targetKey);
            WorkspaceSetup.getCurseForge().save();
            Core.atInfo(Log.CURSEFORGE).log("Mod eliminado: '" + targetKey + "'");
            Core.atInfo(Log.CURSEFORGE).log("Nota: El archivo físico en el disco no ha sido eliminado.");
        } else {
            Core.atWarning(Log.CURSEFORGE).log("No se encontró ningún mod coincidente con: " + query);
            Core.atInfo(Log.CURSEFORGE).log("--- Mods registrados actualmente ---");
            cfConfig.resources.forEach((key, res) -> Core.atInfo(Log.CURSEFORGE).log("Key: " + key + " | ID: " + res.project_id + " | Archivo: " + res.local_file_name));
            Core.atInfo(Log.CURSEFORGE).log("------------------------------------");
        }
    }

    public void searchAndAddModByName(String modName) {
        CurseForgeConfig cfConfig = WorkspaceSetup.getCurseForge().getConfig();
        if (cfConfig == null) return;

        connect(cfConfig.global_api_key);
        if (globalApiKey == null || globalApiKey.isEmpty() || globalApiKey.equals("INSERT_YOUR_KEY_HERE")) {
            Core.atError(Log.CURSEFORGE).log("Se requiere una API Key para buscar mods.");
            return;
        }

        try {
            int gameId = cfConfig.global_game_id;
            String encodedName = URLEncoder.encode(modName, StandardCharsets.UTF_8);
            String apiUrl = "https://api.curseforge.com/v1/mods/search?gameId=" + gameId + "&searchFilter=" + encodedName;

            Core.atInfo(Log.CURSEFORGE).log("Buscando coincidencias para '" + modName + "' en CurseForge...");

            URI uri = new URI(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("x-api-key", globalApiKey);

            if (conn.getResponseCode() != 200) {
                Core.atError(Log.CURSEFORGE).log("Error en la API. Código: " + conn.getResponseCode());
                return;
            }

            String json = readResponse(conn);

            Pattern pattern = Pattern.compile("\"id\":(\\d+),\"gameId\":" + gameId + ",\"name\":\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(json);

            record ModResult(int id, String name) {
            }

            List<ModResult> results = new ArrayList<>();
            while (matcher.find() && results.size() < 5) {
                results.add(new ModResult(Integer.parseInt(matcher.group(1)), matcher.group(2)));
            }

            if (results.isEmpty()) {
                Core.atWarning(Log.CURSEFORGE).log("No se encontraron resultados para: " + modName);
            } else if (results.size() == 1) {
                Core.atInfo(Log.CURSEFORGE).log("Se encontró un único resultado exacto. Redirigiendo...");
                addModById(results.getFirst().id);
            } else {
                Core.atInfo(Log.CURSEFORGE).log("========================================");
                Core.atInfo(Log.CURSEFORGE).log("🔎 Se encontraron varios mods para: " + modName);
                Core.atInfo(Log.CURSEFORGE).log("========================================");
                for (ModResult res : results) {
                    Core.atInfo(Log.CURSEFORGE).log("Nombre: " + res.name + "   [ID: " + res.id + "]");
                }
                Core.atInfo(Log.CURSEFORGE).log("----------------------------------------");
                Core.atInfo(Log.CURSEFORGE).log("👉 Para instalar uno, copia su ID y usa:");
                Core.atInfo(Log.CURSEFORGE).log("   core-curseforge add <ID_DEL_MOD>");
                Core.atInfo(Log.CURSEFORGE).log("👉 (Si deseas cancelar, simplemente ignora este mensaje)");
                Core.atInfo(Log.CURSEFORGE).log("========================================");
            }

        } catch (Exception e) {
            Core.atError(Log.CURSEFORGE).log("Error al procesar búsqueda: " + e.getMessage());
        }
    }

    private @NotNull String cleanFileNameForSearch(@NotNull String filename) {
        String name = filename.substring(0, filename.lastIndexOf('.'));
        name = name.replaceAll("[-_][vV]?\\d+.*", "");
        name = name.replace("_", " ").replace("-", " ");
        name = name.replaceAll("([a-z])([A-Z]+)", "$1 $2");
        return name.trim();
    }

    private void autoDetectLocalMods() {
        CurseForgeConfig cfConfig = WorkspaceSetup.getCurseForge().getConfig();
        if (cfConfig == null || !cfConfig.auto_search_untracked_mods) return;

        Path modsFolder = WorkspaceSetup.getLocalModsPath();
        if (!Files.exists(modsFolder)) return;

        try {
            java.util.Set<String> trackedFiles = cfConfig.resources.values().stream()
                    .map(r -> r.local_file_name)
                    .filter(name -> name != null && !name.isEmpty())
                    .collect(java.util.stream.Collectors.toSet());

            try (java.util.stream.Stream<Path> stream = Files.list(modsFolder)) {
                stream.filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .filter(name -> name.endsWith(".jar") || name.endsWith(".zip"))
                        .filter(name -> !trackedFiles.contains(name))
                        .filter(name -> {
                            if (cfConfig.ignored_untracked_files.contains(name)) {
                                Core.atInfo(Log.CURSEFORGE).log("IGNORADO (Lista Negra): " + name);
                                return false;
                            }
                            return true;
                        })
                        .forEach(untrackedFile -> {
                            String cleanName = cleanFileNameForSearch(untrackedFile);
                            Core.atInfo(Log.CURSEFORGE).log("🔍 Archivo no registrado detectado: " + untrackedFile);
                            Core.atInfo(Log.CURSEFORGE).log("Tratando de buscarlo en CurseForge como: '" + cleanName + "'");
                            searchAndAddModByName(cleanName);
                        });
            }
        } catch (Exception e) {
            Core.atError(Log.CURSEFORGE).log("Error al auto-detectar mods locales: " + e.getMessage());
        }
    }

    public void syncAll() {
        CurseForgeConfig cfConfig = WorkspaceSetup.getCurseForge().getConfig();
        if (cfConfig == null || cfConfig.resources == null) return;
        connect(cfConfig.global_api_key);
        if (cfConfig.auto_search_untracked_mods) {
            autoDetectLocalMods();
        }
        boolean configNeedsSave = false;
        for (Map.Entry<String, CurseForgeConfig.CurseForgeResource> entry : cfConfig.resources.entrySet()) {
            String key = entry.getKey();
            CurseForgeConfig.CurseForgeResource resource = entry.getValue();
            if (key.equals(WorkspaceSetup.getDefaultCurseForgePrefix())) continue;
            if (!resource.enable) continue;

            if (downloadLatestMod(key, resource)) {
                configNeedsSave = true;
            }
        }
        if (configNeedsSave) WorkspaceSetup.getCurseForge().save();
    }

    private @Nullable String findKey(String query) {
        CurseForgeConfig cfConfig = WorkspaceSetup.getCurseForge().getConfig();
        if (cfConfig == null) return null;

        if (cfConfig.resources.containsKey(query)) return query;

        for (Map.Entry<String, CurseForgeConfig.CurseForgeResource> entry : cfConfig.resources.entrySet()) {
            CurseForgeConfig.CurseForgeResource res = entry.getValue();
            if (query.matches("\\d+") && res.project_id == Integer.parseInt(query)) return entry.getKey();
            if (res.local_file_name != null && res.local_file_name.equalsIgnoreCase(query)) return entry.getKey();
        }
        return null;
    }

    public void syncMod(String query) {
        String modKey = findKey(query);
        if (modKey == null) {
            Core.atWarning(Log.CURSEFORGE).log("No se encontró ningún mod registrado para: " + query);
            return;
        }
        CurseForgeConfig cfConfig = WorkspaceSetup.getCurseForge().getConfig();
        CurseForgeConfig.CurseForgeResource resource = cfConfig.resources.get(modKey);
        connect(cfConfig.global_api_key);
        if (downloadLatestMod(modKey, resource)) {
            WorkspaceSetup.getCurseForge().save();
        }
    }

    private @NotNull String calculateFileHash(Path file, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hashBytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private boolean downloadLatestMod(String modKey, CurseForgeConfig.CurseForgeResource resource) {
        if (globalApiKey == null || globalApiKey.isEmpty() || globalApiKey.equals("INSERT_YOUR_KEY_HERE")) {
            Core.atError(Log.CURSEFORGE).log("Se requiere una API Key para descargar " + resource.project_id);
            return false;
        }
        try {
            Core.atInfo(Log.CURSEFORGE).log("Buscando actualizaciones para el Project ID: " + resource.project_id);
            String apiUrl = "https://api.curseforge.com/v1/mods/" + resource.project_id;
            URI uri = new URI(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("x-api-key", globalApiKey);
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                Core.atError(Log.CURSEFORGE).log("Error al contactar la API. Código HTTP: " + responseCode);
                return false;
            }
            String json = readResponse(conn);
            int remoteFileId = -1;
            Matcher mainIdMatcher = Pattern.compile("\"mainFileId\":(\\d+)").matcher(json);
            if (mainIdMatcher.find()) {
                remoteFileId = Integer.parseInt(mainIdMatcher.group(1));
            }

            String expectedHash = null;
            String fileName = "";
            String downloadUrl = "";
            String idSearch = "\"id\":" + remoteFileId;
            int idPos = json.indexOf(idSearch);

            if (idPos != -1) {
                int endPos = Math.min(idPos + 1000, json.length());
                String chunk = json.substring(idPos, endPos);

                Matcher nameMatcher = Pattern.compile("\"fileName\":\"([^\"]+)\"").matcher(chunk);
                if (nameMatcher.find()) fileName = nameMatcher.group(1);

                Matcher urlMatcher = Pattern.compile("\"downloadUrl\":\"([^\"]+)\"").matcher(chunk);
                if (urlMatcher.find()) downloadUrl = urlMatcher.group(1);

                Matcher sha1Matcher = Pattern.compile("\"value\":\"([a-fA-F0-9]{40})\",\"algo\":1").matcher(chunk);
                if (sha1Matcher.find()) {
                    expectedHash = sha1Matcher.group(1);
                } else {
                    Matcher md5Matcher = Pattern.compile("\"value\":\"([a-fA-F0-9]{32})\",\"algo\":2").matcher(chunk);
                    if (md5Matcher.find()) expectedHash = md5Matcher.group(1);
                }
            }

            Path destinationDir = WorkspaceSetup.resolve(resource.destination_path);
            Path localFile = destinationDir.resolve(resource.local_file_name != null ? resource.local_file_name : "");
            boolean fileExists = Files.exists(localFile);

            if (remoteFileId == resource.local_file_id && fileExists && !Files.isDirectory(localFile)) {
                if (resource.verify_file_integrity && expectedHash != null) {
                    Core.atInfo(Log.CURSEFORGE).log("🔍 Verificando integridad local de '" + modKey + "'...");
                    String hashAlgo = expectedHash.length() == 40 ? "SHA-1" : "MD5";
                    String localHash = calculateFileHash(localFile, hashAlgo);

                    if (localHash.equalsIgnoreCase(expectedHash)) {
                        Core.atInfo(Log.CURSEFORGE).log("✅ '" + modKey + "' está al día e íntegro.");
                        return true;
                    } else {
                        Core.atWarning(Log.CURSEFORGE).log(modKey + "' parece corrupto o modificado. Re-descargando...");
                    }
                } else {
                    Core.atInfo(Log.CURSEFORGE).log("El mod " + modKey + " ya está al día.");
                    return true;
                }
            }

            if (downloadUrl == null || downloadUrl.isEmpty() || downloadUrl.equals("null")) {
                Core.atWarning(Log.CURSEFORGE).log("Distribución deshabilitada por el autor para: " + (fileName.isEmpty() ? "Desconocido" : fileName));
                return false;
            }

            Path downloadFolder = WorkspaceSetup.getCurseForgeDownloadsPath();
            if (!Files.exists(downloadFolder)) Files.createDirectories(downloadFolder);
            Path downloadedFile = downloadFolder.resolve(fileName);

            Core.atInfo(Log.CURSEFORGE).log("¡Enlace obtenido! Descargando: " + fileName);
            downloadFile(downloadUrl, downloadedFile);

            if (resource.verify_file_integrity && expectedHash != null) {
                String hashAlgo = expectedHash.length() == 40 ? "SHA-1" : "MD5";
                String actualHash = calculateFileHash(downloadedFile, hashAlgo);
                if (!actualHash.equalsIgnoreCase(expectedHash)) {
                    Core.atError(Log.CURSEFORGE).log("❌ Error crítico: Descarga corrupta.");
                    Files.deleteIfExists(downloadedFile);
                    return false;
                }
            }

            performBackupAndReplace(destinationDir, resource.local_file_name, downloadedFile,
                    resource.keep_backup, WorkspaceSetup.getCurseForgeBackupPath(),
                    modKey, Log.CURSEFORGE);

            resource.local_file_id = remoteFileId;
            resource.local_file_name = fileName;
            return true;

        } catch (Exception e) {
            Core.atError(Log.CURSEFORGE).log("Error crítico en la sincronización: " + e.getMessage());
            return false;
        }
    }

    public void restoreMod(String modKey) {
        CurseForgeConfig cfConfig = WorkspaceSetup.getCurseForge().getConfig();
        if (cfConfig == null || cfConfig.resources == null) return;

        CurseForgeConfig.CurseForgeResource resource = cfConfig.resources.get(modKey);
        if (resource == null) {
            Core.atWarning(Log.CURSEFORGE).log("No se encontró el mod '" + modKey + "' en la configuración.");
            return;
        }

        try {
            Path destinationDir = WorkspaceSetup.resolve(resource.destination_path);
            String restoredFileName = restoreLatestBackup(
                    destinationDir,
                    WorkspaceSetup.getCurseForgeBackupPath(),
                    modKey,
                    resource.local_file_name,
                    Log.CURSEFORGE
            );

            if (restoredFileName != null) {
                resource.local_file_name = restoredFileName;
                resource.local_file_id = 0;
                WorkspaceSetup.getCurseForge().save();
            }
        } catch (Exception e) {
            Core.atError(Log.CURSEFORGE).log("Fallo al restaurar el backup: " + e.getMessage());
        }
    }

    private void downloadFile(String fileUrl, Path destination) throws Exception {
        URI uri = new URI(fileUrl);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        try {
            downloadStream(connection.getInputStream(), destination);
        } finally {
            connection.disconnect();
        }
    }
}