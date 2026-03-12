package org.astral.core.api.github;

import org.astral.core.api.BaseModDownloader;
import org.astral.core.config.github.GitHubConfig;
import org.astral.core.logger.Core;
import org.astral.core.logger.Log;
import org.astral.core.setup.WorkspaceSetup;
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

            if (downloadLatestRelease(resource)) {
                configNeedsSave = true;
            }
        }

        if (configNeedsSave) {
            WorkspaceSetup.getGithub().save();
        }
    }

    public void syncRepo(String repoKey) {
        GitHubConfig githubCfg = WorkspaceSetup.getGithub().getConfig();
        if (githubCfg == null || githubCfg.resources == null) return;

        GitHubConfig.RepositoryResource resource = githubCfg.resources.get(repoKey);

        if (resource == null) {
            Core.atWarning(Log.GITHUB).log("No se encontró el repositorio con la clave '" + repoKey + "' en la configuración.");
            return;
        }

        connect(githubCfg.personal_token);
        if (downloadLatestRelease(resource)) {
            WorkspaceSetup.getGithub().save();
        }
    }

    private boolean downloadLatestRelease(GitHubConfig.RepositoryResource resource) {
        try {
            GitHub activeGithub = this.globalGithub;
            String activeToken = this.globalToken;

            if (resource.custom_token != null && !resource.custom_token.isEmpty()) {
                activeToken = resource.custom_token;
                activeGithub = new GitHubBuilder().withOAuthToken(activeToken).build();
                Core.atInfo(Log.GITHUB).log("Usando Token Privado para el repositorio: " + resource.repo_slug);
            } else if (activeGithub == null) {
                Core.atError(Log.GITHUB).log("No hay conexión con GitHub activa.");
                return false;
            }

            Core.atInfo(Log.GITHUB).log("Buscando actualización para: " + resource.repo_slug);
            GHRepository repo = activeGithub.getRepository(resource.repo_slug);
            GHRelease release = repo.getLatestRelease();

            String remoteTag = release.getTagName();
            if (remoteTag.equals(resource.local_version_tag)) {
                Core.atInfo(Log.GITHUB).log("El mod " + resource.repo_slug + " ya está en su última versión (" + remoteTag + ").");
                return true;
            }

            GHAsset targetAsset = null;
            for (GHAsset asset : release.listAssets()) {
                if (asset.getName().endsWith(".jar")) {
                    targetAsset = asset;
                    break;
                }
            }

            if (targetAsset == null) {
                Core.atWarning(Log.GITHUB).log("No se encontró ningún archivo .jar en la versión " + remoteTag);
                return false;
            }

            Path downloadFolder = WorkspaceSetup.getGithubDownloadsPath();
            if (!Files.exists(downloadFolder)) Files.createDirectories(downloadFolder);
            Path downloadedFile = downloadFolder.resolve(targetAsset.getName());

            Core.atInfo(Log.GITHUB).log("Descargando " + targetAsset.getName() + " (" + remoteTag + ")...");

            downloadFile(targetAsset.getUrl().toString(), activeToken, downloadedFile);

            Path destinationDir = WorkspaceSetup.resolve(resource.destination_path);
            performBackupAndReplace(
                    destinationDir,
                    resource.local_file_name,
                    downloadedFile,
                    resource.keep_backup,
                    WorkspaceSetup.getGithubBackupPath(),
                    Log.GITHUB
            );

            resource.local_version_tag = remoteTag;
            resource.local_file_name = targetAsset.getName();

            return true;

        } catch (Exception e) {
            Core.atError(Log.GITHUB).log("Fallo al actualizar de GitHub: " + e.getMessage());
            return false;
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