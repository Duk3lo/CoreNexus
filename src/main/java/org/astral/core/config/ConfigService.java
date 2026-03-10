package org.astral.core.config;

import org.astral.core.logger.Core;
import org.astral.core.logger.Log;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

public class ConfigService<T> {
    private T config;
    private final Class<T> configClass;
    private final String fileName;
    private final Supplier<T> defaultSupplier;
    private final Path rootPath;

    public ConfigService(Class<T> configClass, String fileName, Supplier<T> defaultSupplier, Path rootPath) {
        this.configClass = configClass;
        this.fileName = fileName;
        this.defaultSupplier = defaultSupplier;
        this.rootPath = rootPath;
    }

    public void load() {
        Path configPath = rootPath.resolve(fileName);
        Yaml yaml = createYamlInstance();

        try {
            // No resolvemos nada, usamos la ruta que nos dio el Workspace
            if (Files.notExists(configPath)) {
                this.config = defaultSupplier.get();
                save();
                Core.atInfo(Log.CONFIG).log("Generado nuevo archivo: " + fileName + " en " + rootPath.getFileName());
            } else {
                try (InputStream inputStream = Files.newInputStream(configPath)) {
                    this.config = yaml.loadAs(inputStream, configClass);
                    Core.atInfo(Log.CONFIG).log("Archivo cargado: " + fileName);
                }
            }
        } catch (IOException e) {
            Core.atError(Log.CONFIG).log("Error al procesar " + fileName + ": " + e.getMessage());
        }
    }

    public void save() {
        Path configPath = rootPath.resolve(fileName);
        Yaml yaml = createYamlInstance();

        try (Writer writer = Files.newBufferedWriter(configPath)) {
            yaml.dump(config, writer);
        } catch (IOException e) {
            Core.atError(Log.CONFIG).log("Error al guardar " + fileName + ": " + e.getMessage());
        }
    }

    private @NotNull Yaml createYamlInstance() {
        DumperOptions options = new DumperOptions();
        options.setIndent(2);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Representer representer = new Representer(options);
        representer.addClassTag(configClass, Tag.MAP);
        representer.addClassTag(org.astral.core.config.nexus.NexusConfig.ActionType.class, Tag.STR);
        representer.addClassTag(org.astral.core.config.nexus.NexusConfig.Watcher.class, Tag.MAP);

        return new Yaml(representer, options);
    }

    public T getConfig() {
        return config;
    }
}