package org.astral.core.config.nexus;

import org.astral.core.setup.WorkspaceSetup;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class NexusConfig {
    public String server_path = "";
    public String jar_name = "";
    public String args = "--assets ../Assets.zip";
    public boolean defaultPaste = true;
    public boolean clearDefaultDestination = false;
    public Map<String, Watcher> watchers = new LinkedHashMap<>();


    public NexusConfig() {
        Watcher defaultWatcher = createWatcher(
                WorkspaceSetup.getLocalModsPath().toString(),
                "",
                true
        );
        defaultWatcher.path_sync = true;
        watchers.put(WorkspaceSetup.getDefaultWatchPrefix(), defaultWatcher);
    }

    public static class Watcher {
        public boolean enable; //Habilita o desabilita el watcher
        public String path; // Ruta donde el watcher lo sincronizara a la ruta destinataria
        public String path_destination; // Ruta en la cual se enviaran los archivos
        public boolean path_listen_Folders; // Util para escuchar más carpetas dentro de carpetas recursivamente
        public String filter_extensions; // filtro para enviar y recibir solo los archivos definidos
        public boolean path_sync; // Sincroniza la carpeta de mods del servidor con la del watcher y conjuntamente obtienen los mismos archivos con respecto a sus configuraciones definidas aquí por lo general si es desde el destino hacia el path, pero sí se elimina una carpeta desde el path y está activada la opción path_listen_Folders y la opción path_safe_delete se detendrá y se eliminará
        public boolean path_safe_delete; //detiene el servidor elimina el archivo destines y vuelve a iniciar el server
        public String apply_Actions_Only; // Se aplicarán las acciones al servidor cuando el tipo de extension de aquí cambie su estado
        public List<ActionType> actions = new ArrayList<>(); // Las acciones que se realizara cuando ApplyActionsOnly cambie su estado
        public Watcher(){}
    }

    public static @NotNull Watcher createWatcher(String sourcePath, String destinationPath, boolean withDefaultActions) {
        Watcher w = new Watcher();
        w.enable = true;
        w.path = sourcePath;
        w.path_destination = destinationPath;
        w.path_sync = false;
        w.path_safe_delete = true;
        w.path_listen_Folders = true;
        w.filter_extensions = ".*";
        w.apply_Actions_Only = ".jar";

        if (withDefaultActions) {
            w.actions.add(ActionType.STOP_SERVER);
            w.actions.add(ActionType.DELETE);
            w.actions.add(ActionType.COPY);
            w.actions.add(ActionType.START_SERVER);
        }
        return w;
    }

    public enum ActionType {
        DELETE,
        COPY,
        STOP_SERVER,
        START_SERVER
    }

}