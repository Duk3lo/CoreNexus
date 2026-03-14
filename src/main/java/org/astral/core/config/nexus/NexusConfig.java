package org.astral.core.config.nexus;

import org.astral.core.setup.WorkspaceSetup;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class NexusConfig {
    public String server_path = "";
    public String jar_name = "";
    public String args = "--assets ../Assets.zip";
    public boolean clearDefaultDestination = false;

    public String apply_Actions_Only = ".jar";
    public List<ActionType> actions = new ArrayList<>();

    public Map<String, Watcher> watchers = new LinkedHashMap<>();

    public NexusConfig() {
        actions.add(ActionType.STOP_SERVER);
        actions.add(ActionType.DELETE);
        actions.add(ActionType.COPY);
        actions.add(ActionType.START_SERVER);

        Watcher defaultWatcher = createWatcher(
                WorkspaceSetup.getLocalModsPath().toString(),
                ""
        );
        watchers.put(WorkspaceSetup.getDefaultWatchPrefix(), defaultWatcher);
    }

    public static class Watcher {
        public boolean enable; // Habilita o desabilita el watcher
        public String path; // Ruta donde el watcher lo sincronizara a la ruta destinataria
        public String path_destination; // Ruta en la cual se enviaran los archivos
        public boolean path_listen_Folders; // Util para escuchar más carpetas dentro de carpetas recursivamente
        public String filter_extensions; // filtro para enviar y recibir solo los archivos definidos
        public boolean bidirectional_sync; // Sincroniza la carpeta de mods del servidor...
        public boolean path_safe_delete; // detiene el servidor elimina el archivo destino y vuelve a iniciar el server
        public boolean copy_on_start; // Copia automáticamente a la carpeta destino cuando se registra o inicia el watcher

        public Watcher(){}
    }

    public static @NotNull Watcher createWatcher(String sourcePath, String destinationPath) {
        Watcher w = new Watcher();
        w.enable = true;
        w.path = sourcePath;
        w.path_destination = destinationPath;
        w.bidirectional_sync = false;
        w.path_safe_delete = true;
        w.path_listen_Folders = false;
        w.filter_extensions = ".*";
        w.copy_on_start = true;
        return w;
    }

    public enum ActionType {
        DELETE,
        COPY,
        STOP_SERVER,
        START_SERVER
    }
}