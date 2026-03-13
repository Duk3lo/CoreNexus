package org.astral.core.api;

import org.astral.core.logger.Core;
import org.astral.core.logger.Log;
import org.jetbrains.annotations.NotNull;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.stream.Stream;

public abstract class BaseModDownloader {

    protected void performBackupAndReplace(Path destinationDir, String oldFileName, Path downloadedFile, boolean keepBackup, Path backupDir, String modKey, Log logTag) throws Exception {
        if (!Files.exists(destinationDir)) Files.createDirectories(destinationDir);

        if (oldFileName != null && !oldFileName.isEmpty()) {
            Path oldFile = destinationDir.resolve(oldFileName);
            if (Files.exists(oldFile)) {
                if (keepBackup) {
                    Path modBackupDir = backupDir.resolve(modKey);
                    if (!Files.exists(modBackupDir)) Files.createDirectories(modBackupDir);

                    String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                    Path backupFile = modBackupDir.resolve(oldFileName + ".backup_" + timeStamp);
                    Files.copy(oldFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
                    Core.atInfo(logTag).log("Backup guardado en: " + modBackupDir.getFileName() + "/" + backupFile.getFileName());
                }
                Files.delete(oldFile);
                Core.atInfo(logTag).log("Versión antigua eliminada.");
            }
        }

        Path finalDestination = destinationDir.resolve(downloadedFile.getFileName());
        Files.move(downloadedFile, finalDestination, StandardCopyOption.REPLACE_EXISTING);
        Core.atInfo(logTag).log("Nuevo mod instalado: " + finalDestination.getFileName());
    }

    protected String restoreLatestBackup(Path destinationDir, @NotNull Path backupDir, String modKey, String currentFileName, Log logTag) throws Exception {
        Path modBackupDir = backupDir.resolve(modKey);

        if (!Files.exists(modBackupDir) || !Files.isDirectory(modBackupDir)) {
            Core.atWarning(logTag).log("No existe una carpeta de backups para: " + modKey);
            return null;
        }

        Path latestBackup = null;

        try (Stream<Path> stream = Files.list(modBackupDir)) {
            latestBackup = stream
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            Core.atWarning(logTag).log("Error al acceder a la carpeta de backups para '" + modKey + "': " + e.getMessage());
        }

        if (latestBackup == null) {
            Core.atWarning(logTag).log("La carpeta de backups para '" + modKey + "' está vacía.");
            return null;
        }

        String backupFullName = latestBackup.getFileName().toString();

        String originalFileName = backupFullName.substring(0, backupFullName.lastIndexOf(".backup_"));

        if (!Files.exists(destinationDir)) Files.createDirectories(destinationDir);

        if (currentFileName != null && !currentFileName.isEmpty()) {
            Path currentFile = destinationDir.resolve(currentFileName);
            Files.deleteIfExists(currentFile);
        }

        Path restoredFile = destinationDir.resolve(originalFileName);
        Files.copy(latestBackup, restoredFile, StandardCopyOption.REPLACE_EXISTING);
        Core.atInfo(logTag).log("✅ Mod restaurado exitosamente desde backup: " + originalFileName);

        return originalFileName;
    }

    protected void downloadStream(InputStream in, Path destination) throws Exception {
        try (InputStream input = in;
             FileOutputStream out = new FileOutputStream(destination.toFile())) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }
}