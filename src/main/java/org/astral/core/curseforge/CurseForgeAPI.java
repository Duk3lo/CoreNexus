package org.astral.core.curseforge;

import org.astral.core.config.curseforge.CurseForgeConfig;
import org.astral.core.logger.Core;
import org.astral.core.logger.Log;
import org.astral.core.setup.WorkspaceSetup;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
//Proximamente
public class CurseForgeAPI {
    private static CurseForgeAPI instance;
    private final HttpClient httpClient;
    private static final String BASE_URL = "https://api.curseforge.com/v1";

    // El ID oficial de Hytale en CurseForge
    public static final int HYTALE_GAME_ID = 76380;

    private CurseForgeAPI() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public static CurseForgeAPI getInstance() {
        if (instance == null) instance = new CurseForgeAPI();
        return instance;
    }

    public void fetchModInfo(int modId) {
        CurseForgeConfig cfg = WorkspaceSetup.getCurseForge().getConfig();

        if (cfg.apiKey == null || cfg.apiKey.contains("INSERT_YOUR_KEY")) {
            Core.atError(Log.CURSEFORGE).log("API Key no configurada.");
            return;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/mods/" + modId))
                .header("x-api-key", cfg.apiKey)
                .header("Accept", "application/json")
                // El User-Agent evita que CurseForge devuelva 403 en muchas peticiones de Java
                .header("User-Agent", "CoreNexus-Hytale-Client/1.0 (Contact: dukelo)")
                .GET()
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        Core.atInfo(Log.CURSEFORGE).log("Datos del Mod: " + response.body());
                    } else if (response.statusCode() == 403) {
                        Core.atError(Log.CURSEFORGE).log("Error 403: Acceso denegado. Revisa que tu API Key esté activa.");
                    } else {
                        Core.atError(Log.CURSEFORGE).log("Error " + response.statusCode() + ": " + response.body());
                    }
                })
                .exceptionally(ex -> {
                    Core.atError(Log.CURSEFORGE).log("Fallo de red: " + ex.getMessage());
                    return null;
                });
    }

    public void testConnection() {
        CurseForgeConfig cfg = WorkspaceSetup.getCurseForge().getConfig();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.curseforge.com/v1/games"))
                .header("x-api-key", cfg.apiKey.trim()) // Usamos .trim() por si hay un espacio invisible
                .header("User-Agent", "CoreNexus-Hytale-Client/1.0")
                .GET()
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> {
                    if (res.statusCode() == 200) {
                        Core.atInfo(Log.CURSEFORGE).log("¡Key válida! Conexión establecida.");
                    } else {
                        Core.atError(Log.CURSEFORGE).log("Fallo de Key (Código " + res.statusCode() + "): " + res.body());
                    }
                });
    }

    public void searchHytaleMods(@NotNull String searchFilter) {
        CurseForgeConfig cfg = WorkspaceSetup.getCurseForge().getConfig();

        // Endpoint: /v1/mods/search?gameId=76380&searchFilter=BetterMap
        String query = String.format("/mods/search?gameId=%d&searchFilter=%s",
                HYTALE_GAME_ID, searchFilter.replace(" ", "%20"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + query))
                .header("x-api-key", cfg.apiKey)
                .header("User-Agent", "CoreNexus-Hytale-Client")
                .GET()
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> Core.atInfo(Log.CURSEFORGE).log("Resultados búsqueda: " + res.body()));
    }
}