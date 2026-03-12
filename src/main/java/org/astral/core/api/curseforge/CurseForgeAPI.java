package org.astral.core.api.curseforge;


import org.astral.core.api.BaseModDownloader;
import org.astral.core.config.curseforge.CurseForgeConfig;
import org.astral.core.logger.Core;
import org.astral.core.logger.Log;
import org.astral.core.setup.WorkspaceSetup;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
            Core.atWarning(Log.CURSEFORGE).log("⚠️ No se ha configurado una API Key de CurseForge válida.");
        } else {
            Core.atInfo(Log.CURSEFORGE).log("Conectado a CurseForge con API Key.");
        }
    }

    public void syncAll() {
        CurseForgeConfig cfConfig = WorkspaceSetup.getCurseForge().getConfig();
        if (cfConfig == null || cfConfig.resources == null) return;

        connect(cfConfig.global_api_key);
        boolean configNeedsSave = false;

        for (Map.Entry<String, CurseForgeConfig.CurseForgeResource> entry : cfConfig.resources.entrySet()) {
            String key = entry.getKey();
            CurseForgeConfig.CurseForgeResource resource = entry.getValue();

            if (key.equals(WorkspaceSetup.getDefaultCurseForgePrefix())) continue;
            if (!resource.enable) continue;

            if (downloadLatestMod(resource)) {
                configNeedsSave = true;
            }
        }

        if (configNeedsSave) {
            WorkspaceSetup.getCurseForge().save();
        }
    }

    public void syncMod(String modKey) {
        CurseForgeConfig cfConfig = WorkspaceSetup.getCurseForge().getConfig();
        if (cfConfig == null || cfConfig.resources == null) return;

        CurseForgeConfig.CurseForgeResource resource = cfConfig.resources.get(modKey);

        if (resource == null) {
            Core.atWarning(Log.CURSEFORGE).log("No se encontró el mod '" + modKey + "' en la configuración.");
            return;
        }

        connect(cfConfig.global_api_key);
        if (downloadLatestMod(resource)) {
            WorkspaceSetup.getCurseForge().save();
        }
    }

    private boolean downloadLatestMod(CurseForgeConfig.CurseForgeResource resource) {
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

            if (conn.getResponseCode() != 200) {
                Core.atError(Log.CURSEFORGE).log("Error al contactar la API. Código HTTP: " + conn.getResponseCode());
                return false;
            }
            StringBuilder jsonBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonBuilder.append(line);
                }
            }
            String json = jsonBuilder.toString();
            int remoteFileId = -1;
            String downloadUrl = "";
            String fileName = "";
            Matcher mainIdMatcher = Pattern.compile("\"mainFileId\":(\\d+)").matcher(json);
            if (mainIdMatcher.find()) {
                remoteFileId = Integer.parseInt(mainIdMatcher.group(1));
            }

            if (remoteFileId == -1) {
                Core.atError(Log.CURSEFORGE).log("No se pudo detectar el ID del archivo principal en CurseForge.");
                return false;
            }
            if (remoteFileId == resource.local_file_id) {
                Core.atInfo(Log.CURSEFORGE).log("El mod ID " + resource.project_id + " ya está en su última versión (File: " + remoteFileId + ").");
                return true;
            }
            Matcher fileMatcher = Pattern.compile("\"id\":" + remoteFileId + ".*?\"fileName\":\"([^\"]+)\".*?\"downloadUrl\":\"([^\"]+)\"").matcher(json);
            if (fileMatcher.find()) {
                fileName = fileMatcher.group(1);
                downloadUrl = fileMatcher.group(2);
            }
            if (downloadUrl == null || downloadUrl.isEmpty() || downloadUrl.equals("null")) {
                Core.atWarning(Log.CURSEFORGE).log("El autor del mod ha deshabilitado las descargas por API de terceros para el archivo " + fileName);
                return false;
            }

            Path downloadFolder = WorkspaceSetup.getCurseForgeDownloadsPath();
            if (!Files.exists(downloadFolder)) Files.createDirectories(downloadFolder);
            Path downloadedFile = downloadFolder.resolve(fileName);

            Core.atInfo(Log.CURSEFORGE).log("Descargando " + fileName + " (File ID: " + remoteFileId + ")...");
            downloadFile(downloadUrl, downloadedFile);

            Path destinationDir = WorkspaceSetup.resolve(resource.destination_path);
            performBackupAndReplace(
                    destinationDir,
                    resource.local_file_name,
                    downloadedFile,
                    resource.keep_backup,
                    WorkspaceSetup.getCurseForgeBackupPath(),
                    Log.CURSEFORGE
            );

            resource.local_file_id = remoteFileId;
            resource.local_file_name = fileName;

            return true;

        } catch (Exception e) {
            Core.atError(Log.CURSEFORGE).log("Fallo al actualizar desde CurseForge: " + e.getMessage());
            return false;
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