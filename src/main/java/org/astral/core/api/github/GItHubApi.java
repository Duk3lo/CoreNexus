package org.astral.core.api.github;

import org.astral.core.api.BaseModDownloader;
import org.astral.core.config.github.GitHubConfig;
import org.astral.core.logger.Core;
import org.astral.core.logger.Log;
import org.astral.core.setup.WorkspaceSetup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class GItHubApi extends BaseModDownloader {

    private static GItHubApi instance = null;
    private GitHub globalGithub;
    private String globalToken;

    private GItHubApi() {}

    public static GItHubApi getInstance() {
        if (instance == null) {
            instance = new GItHubApi();
        }
        return instance;
    }

    public void connect(String token) {
        this.globalToken = token;
        try {
            if (token == null || token.isEmpty()) {
                globalGithub = new GitHubBuilder().build();
                Core.atInfo(Log.GITHUB).log("Conectado a GitHub de forma anónima.");
            } else {
                globalGithub = new GitHubBuilder().withOAuthToken(token).build();
                Core.atInfo(Log.GITHUB).log("Conectado a GitHub con Token Global.");
            }
        } catch (Exception e) {
            Core.atError(Log.GITHUB).log("Error al conectar con GitHub: " + e.getMessage());
        }
    }
    public void syncAll() {
        GitHubConfig githubCfg = WorkspaceSetup.getGithub().getConfig();
        if (githubCfg == null || githubCfg.resources == null) return;

        connect(githubCfg.personal_token);
        boolean configNeedsSave = false;

        for (Map.Entry<String, GitHubConfig.RepositoryResource> entry : githubCfg.resources.entrySet()) {
            String key = entry.getKey();
            GitHubConfig.RepositoryResource resource = entry.getValue();

            if (key.equals(WorkspaceSetup.getDefaultGitHubPrefix())) continue;
            if (!resource.enable) continue;

            if (downloadLatestRelease(key, resource)) {
                configNeedsSave = true;
            }
        }

        if (configNeedsSave) {
            WorkspaceSetup.getGithub().save();
        }
    }

    public void addRepo(@NotNull String repoSlug) {
        if (!repoSlug.contains("/")) {
            Core.atWarning(Log.GITHUB).log("Uso correcto: <usuario/repositorio>");
            return;
        }

        String key = repoSlug.substring(repoSlug.lastIndexOf("/") + 1).toLowerCase();
        GitHubConfig githubCfg = WorkspaceSetup.getGithub().getConfig();
        if (githubCfg == null) return;

        if (githubCfg.resources.containsKey(key)) {
            Core.atWarning(Log.GITHUB).log("El repositorio '" + key + "' ya existe.");
            return;
        }

        GitHubConfig.RepositoryResource newResource = GitHubConfig.createResource(
                repoSlug,
                WorkspaceSetup.relativize(WorkspaceSetup.getLocalModsPath())
        );

        githubCfg.resources.put(key, newResource);
        WorkspaceSetup.getGithub().save();

        Core.atInfo(Log.GITHUB).log("✅ Registrado: " + repoSlug + " como '" + key + "'. Sincronizando...");
        syncRepo(key);
    }

    private @Nullable String findKey(String query) {
        GitHubConfig githubCfg = WorkspaceSetup.getGithub().getConfig();
        if (githubCfg == null) return null;

        if (githubCfg.resources.containsKey(query)) return query;

        for (Map.Entry<String, GitHubConfig.RepositoryResource> entry : githubCfg.resources.entrySet()) {
            GitHubConfig.RepositoryResource res = entry.getValue();
            if (res.repo_slug.equalsIgnoreCase(query)) return entry.getKey();
            if (res.local_file_name != null && res.local_file_name.equalsIgnoreCase(query)) return entry.getKey();
        }
        return null;
    }

    public void syncRepo(String query) {
        String repoKey = findKey(query);
        if (repoKey == null) {
            Core.atWarning(Log.GITHUB).log("No se encontró configuración para: " + query);
            return;
        }

        GitHubConfig githubCfg = WorkspaceSetup.getGithub().getConfig();
        connect(githubCfg.personal_token);
        if (downloadLatestRelease(repoKey, githubCfg.resources.get(repoKey))) {
            WorkspaceSetup.getGithub().save();
        }
    }

    public void removeRepo(String query) {
        GitHubConfig githubCfg = WorkspaceSetup.getGithub().getConfig();
        if (githubCfg == null || githubCfg.resources == null) return;

        String targetKey = findKey(query);

        if (targetKey != null) {
            githubCfg.resources.remove(targetKey);
            WorkspaceSetup.getGithub().save();
            Core.atInfo(Log.GITHUB).log("🗑️ Repositorio eliminado: '" + targetKey + "'");
        } else {
            Core.atWarning(Log.GITHUB).log("No se encontró el repositorio GitHub: " + query);
            Core.atInfo(Log.GITHUB).log("--- Repositorios registrados ---");
            githubCfg.resources.forEach((key, res) -> Core.atInfo(Log.GITHUB).log(" 📌 Key: " + key + " | Repo: " + res.repo_slug + " | Archivo: " + res.local_file_name));
            Core.atInfo(Log.GITHUB).log("--------------------------------");
        }
    }

    private @NotNull String calculateFileHash(Path file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream is = Files.newInputStream(file)) {
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

    private boolean downloadLatestRelease(String modKey, GitHubConfig.RepositoryResource resource) {
        try {
            GitHub activeGithub = this.globalGithub;
            String activeToken = this.globalToken;

            if (resource.custom_token != null && !resource.custom_token.isEmpty()) {
                activeToken = resource.custom_token;
                activeGithub = new GitHubBuilder().withOAuthToken(activeToken).build();
                Core.atInfo(Log.GITHUB).log("Usando Token Privado para: " + resource.repo_slug);
            } else if (activeGithub == null) {
                Core.atError(Log.GITHUB).log("No hay conexión con GitHub activa.");
                return false;
            }

            Core.atInfo(Log.GITHUB).log("Buscando actualización para: " + resource.repo_slug);
            GHRepository repo = activeGithub.getRepository(resource.repo_slug);
            GHRelease release = repo.getLatestRelease();
            String remoteTag = release.getTagName();

            Path destinationDir = WorkspaceSetup.resolve(resource.destination_path);
            Path localFile = destinationDir.resolve(resource.local_file_name != null ? resource.local_file_name : "");
            boolean fileExists = Files.exists(localFile) && !Files.isDirectory(localFile);

            if (remoteTag.equals(resource.local_version_tag) && fileExists) {
                if (resource.verify_file_integrity && resource.last_verified_hash != null && !resource.last_verified_hash.isEmpty()) {
                    Core.atInfo(Log.GITHUB).log("🔍 Verificando integridad local de '" + modKey + "' (SHA-256)...");
                    String currentHash = calculateFileHash(localFile);

                    if (currentHash.equalsIgnoreCase(resource.last_verified_hash)) {
                        Core.atInfo(Log.GITHUB).log("✅ El archivo GitHub está íntegro y al día.");
                        return true;
                    } else {
                        Core.atWarning(Log.GITHUB).log("⚠️ El archivo local de GitHub no coincide con el hash guardado. Re-descargando...");
                    }
                } else {
                    Core.atInfo(Log.GITHUB).log("El mod " + resource.repo_slug + " ya está al día.");
                    return true;
                }
            }

            GHAsset targetAsset = null;
            for (GHAsset asset : release.listAssets()) {
                if (asset.getName().endsWith(".jar") || asset.getName().endsWith(".zip")) {
                    targetAsset = asset;
                    break;
                }
            }

            if (targetAsset == null) {
                Core.atWarning(Log.GITHUB).log("No se encontró archivo compatible en la versión " + remoteTag);
                return false;
            }

            Path downloadFolder = WorkspaceSetup.getGithubDownloadsPath();
            if (!Files.exists(downloadFolder)) Files.createDirectories(downloadFolder);
            Path downloadedFile = downloadFolder.resolve(targetAsset.getName());

            Core.atInfo(Log.GITHUB).log("Descargando de GitHub: " + targetAsset.getName());
            downloadFile(targetAsset.getUrl().toString(), activeToken, downloadedFile);

            String newHash = "";
            if (resource.verify_file_integrity) {
                newHash = calculateFileHash(downloadedFile);
                Core.atInfo(Log.GITHUB).log("✅ Hash SHA-256 generado para futuras verificaciones.");
            }

            performBackupAndReplace(
                    destinationDir,
                    resource.local_file_name,
                    downloadedFile,
                    resource.keep_backup,
                    WorkspaceSetup.getGithubBackupPath(),
                    modKey,
                    Log.GITHUB
            );

            resource.local_version_tag = remoteTag;
            resource.local_file_name = targetAsset.getName();
            resource.last_verified_hash = newHash;

            return true;

        } catch (Exception e) {
            Core.atError(Log.GITHUB).log("Fallo al actualizar de GitHub: " + e.getMessage());
            return false;
        }
    }

    public void restoreRepo(String modKey) {
        GitHubConfig githubCfg = WorkspaceSetup.getGithub().getConfig();
        if (githubCfg == null || githubCfg.resources == null) return;

        GitHubConfig.RepositoryResource resource = githubCfg.resources.get(modKey);
        if (resource == null) {
            Core.atWarning(Log.GITHUB).log("No se encontró el repositorio '" + modKey + "' en la configuración.");
            return;
        }

        try {
            Path destinationDir = WorkspaceSetup.resolve(resource.destination_path);
            String restoredFileName = restoreLatestBackup(
                    destinationDir,
                    WorkspaceSetup.getGithubBackupPath(),
                    modKey,
                    resource.local_file_name,
                    Log.GITHUB
            );

            if (restoredFileName != null) {
                resource.local_file_name = restoredFileName;
                resource.local_version_tag = "";
                WorkspaceSetup.getGithub().save();
            }
        } catch (Exception e) {
            Core.atError(Log.GITHUB).log("Fallo al restaurar el backup de GitHub: " + e.getMessage());
        }
    }

    private void downloadFile(String apiAssetUrl, String token, Path destination) throws Exception {
        HttpURLConnection connection = createGitHubConnection(apiAssetUrl, token);
        downloadStream(connection.getInputStream(), destination);
        connection.disconnect();
    }

    private HttpURLConnection createGitHubConnection(String apiAssetUrl, String token) throws Exception {
        URI uri = new URI(apiAssetUrl);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();

        connection.setInstanceFollowRedirects(false);

        if (token != null && !token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }

        connection.setRequestProperty("Accept", "application/octet-stream");

        int status = connection.getResponseCode();

        if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_SEE_OTHER) {
            String redirectUrl = connection.getHeaderField("Location");
            URI redirectUri = new URI(redirectUrl);
            connection = (HttpURLConnection) redirectUri.toURL().openConnection();
        } else if (status >= 400) {
            throw new Exception("Error HTTP " + status + " al intentar descargar el asset de GitHub.");
        }

        return connection;
    }
}