package de.rogo.fileduplicatecleanupworker;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import lombok.val;

import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class FileDuplicateFinderCleanerController {

    @FXML
    private VBox root;

    @FXML
    public Button choosePath;

    @FXML
    private TextArea directoryText;

    @FXML
    private Button startLooking;

    @FXML
    private ProgressIndicator progressIndicator;

    private final Object hashingMutex = new Object();

    private int filesProcessed;

    @FXML
    protected void onChoosePathButtonClick() {
        val directoryChooser = new DirectoryChooser();
        val dir = directoryChooser.showDialog(root.getScene().getWindow());

        if (dir != null) {
            startLooking.setDisable(false);
            if (!directoryText.getText().isEmpty()) {
                directoryText.appendText("\n" + dir.getAbsolutePath());
            } else {
                directoryText.setText(dir.getAbsolutePath());
            }
        }
    }

    @FXML
    protected void onStartLookingButtonClick() {
        if (directoryText.getText().isEmpty()) {
            return;
        }

        filesProcessed = 0;

        choosePath.setDisable(true);
        startLooking.setDisable(true);
        progressIndicator.setVisible(true);
        progressIndicator.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);

        new Thread(() -> {
            val searchResult = findDuplicates().entrySet();
            Platform.runLater(() -> {
                progressIndicator.setVisible(false);
                displaySearchResults(searchResult);
                directoryText.setText("");
                choosePath.setDisable(false);
            });
        }).start();
    }

    protected Map<String, List<File>> findDuplicates() {
        // Get the chosen directory
        val directoryText = this.directoryText.getText();
        val directoryPaths = directoryText.split("\\r?\\n");

        // Map of file sizes to lists of files with those sizes
        Map<Long, List<File>> sizeMap = new HashMap<>();

        // Iterate over all files in the directory and its subdirectories
        for (val directoryPath : directoryPaths) {
            val directory = new File(directoryPath);

            // Check that the directory exists and is a directory
            if (!directory.exists() || !directory.isDirectory()) {
                Platform.runLater(() -> {
                    val alert = new Alert(Alert.AlertType.ERROR, directoryPath + "isn't a valid directory, ignoring...");
                    alert.showAndWait();
                });
                continue;
            }

            try {
                Files.walk(directory.toPath())
                        .filter(Files::isRegularFile)
                        .forEach(file -> {
                            try {
                                val size = Files.size(file);
                                val files = sizeMap.computeIfAbsent(size, k -> new ArrayList<>());
                                files.add(file.toFile());
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        System.out.println(countFilesToHash(sizeMap) + " files to hash");

        // Map of file hashes to lists of files with those hashes
        Map<String, List<File>> hashMap = new HashMap<>();

        val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        // Iterate over all files with the same size and compute their hashes
        for (val entry : sizeMap.entrySet()) {
            val files = entry.getValue();

            if (files.size() < 2) {
                continue;
            }

            for (val file : files) {
                executor.execute(() -> {
                    String hash = null;

                    try {
                        hash = getFileChecksum(file);
                    } catch (IOException | NoSuchAlgorithmException e) {
                        e.printStackTrace();
                    }

                    if (hash != null) {
                        synchronized (hashingMutex) {
                            val hashFiles = hashMap.computeIfAbsent(hash, k -> new ArrayList<>());
                            hashFiles.add(file);
                        }
                    }

                    synchronized (System.out) {
                        System.out.print("\rFiles processed: " + (++filesProcessed));
                        System.out.flush();
                    }
                });
            }
        }

        executor.shutdown();
        for (int tries = 0; tries < 3; tries++) {
            try {
                if (executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                    break;
                }
            } catch (InterruptedException ignored) {
            }
            if (tries == 2) {
                Platform.runLater(() -> showMessage("The search ended unexpected. Please try again."));
            }
        }

        System.out.println("\n" + filesProcessed + " files hashed");

        return removeHardLinks(hashMap);
    }

    private <K, VV> int countFilesToHash(final Map<K, List<VV>> map) {
        int result = 0;
        for (val entry : map.entrySet()) {
            val list = entry.getValue();

            if (list.size() < 2) {
                continue;
            }

            result += list.size();
        }
        return result;
    }

    private void displaySearchResults(Set<Map.Entry<String, List<File>>> searchResult) {
        for (val entries : searchResult) {
            while (true) {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Duplicate files found");
                alert.setHeaderText(String.format("Found %d duplicate file(s)", entries.getValue().size()));
                alert.setContentText(String.format(
                        "The following files have the same content:\n\n%s\n\nWhat do you want to do?",
                        entries.getValue().stream().map(File::toString).collect(Collectors.joining("\n"))));

                ButtonType ignoreButton = new ButtonType("Ignore");
                ButtonType deleteButton = new ButtonType("Delete");
                ButtonType chooseButton = new ButtonType("Choose which file to keep");
                ButtonType moveButton = new ButtonType("Merge to another location");
                ButtonType openButton = new ButtonType("Open");

                alert.getButtonTypes().setAll(openButton, ignoreButton, deleteButton, chooseButton, moveButton, ButtonType.CANCEL);

                Optional<ButtonType> result = alert.showAndWait();

                if (result.isPresent()) {
                    if (result.get() == ButtonType.CANCEL) {
                        return;
                    }
                    if (result.get() == deleteButton) {
                        entries.getValue().remove(0);
                        for (val file : entries.getValue()) {
                            try {
                                Files.delete(file.toPath());
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    } else if (result.get() == chooseButton) {
                        FileChooser fileChooser = new FileChooser();
                        fileChooser.setTitle("Choose which file to keep");
                        fileChooser.getExtensionFilters().addAll(
                                new FileChooser.ExtensionFilter("All Files", "*.*"),
                                new FileChooser.ExtensionFilter("Text Files", "*.txt"),
                                new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.gif"),
                                new FileChooser.ExtensionFilter("Audio Files", "*.wav", "*.mp3", "*.aac"),
                                new FileChooser.ExtensionFilter("Video Files", "*.mp4", "*.avi", "*.flv")
                        );
                        fileChooser.setInitialDirectory(entries.getValue().get(0).getParentFile());

                        File selectedFile = fileChooser.showOpenDialog(root.getScene().getWindow());

                        if (!entries.getValue().contains(selectedFile)) {
                            showMessage("You didn't choose any of the duplicated files. Please try again...");
                            continue;
                        }

                        if (selectedFile != null) {
                            for (File file : entries.getValue()) {
                                if (!file.equals(selectedFile)) {
                                    try {
                                        Files.delete(file.toPath());
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                        }
                    } else if (result.get() == moveButton) {
                        FileChooser fileChooser = new FileChooser();
                        fileChooser.setTitle("Choose a location to move the file(s) to");
                        fileChooser.setInitialFileName(entries.getValue().get(0).getName());
                        File destFile = fileChooser.showSaveDialog(root.getScene().getWindow());

                        if (destFile != null) {
                            // Move the first file to the chosen location
                            try {
                                Files.move(entries.getValue().get(0).toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }

                            // Delete the other files
                            for (int i = 1; i < entries.getValue().size(); i++) {
                                File file = entries.getValue().get(i);
                                try {
                                    Files.delete(file.toPath());
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    } else if (result.get() == openButton) {
                        var desktopAvailable = true;
                        if (!Desktop.isDesktopSupported()) {
                            desktopAvailable = false;
                            System.out.println("Desktop not supported");
                        }
                        if (!Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                            desktopAvailable = false;
                            System.out.println("Desktop file opening not supported");
                        }

                        for (val file : entries.getValue()) {
                            Task<Void> task;
                            if (desktopAvailable) {
                                System.out.println("Using Desktop to open file");
                                task = new Task<>() {
                                    @Override
                                    public Void call() {
                                        System.out.println("Desktop gets called inside a thread to open file");
                                        try {
                                            Desktop.getDesktop().open(file);
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        }
                                        return null;
                                    }
                                };
                            } else {
                                task = new Task<>() {
                                    @Override
                                    public Void call() {
                                        ProcessBuilder pb = new ProcessBuilder("open", file.getAbsolutePath());
                                        try {
                                            pb.start();
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        }
                                        return null;
                                    }
                                };
                            }
                            System.out.println("Starting thread...");
                            val thread = new Thread(task);
                            thread.setDaemon(true);
                            thread.start();
                        }
                        continue;
                    }
                }
                break;
            }
        }
    }

    private String getFileChecksum(File file) throws IOException, NoSuchAlgorithmException {
        val messageDigest = MessageDigest.getInstance("SHA-256");

        try (val dis = new DigestInputStream(new FileInputStream(file), messageDigest)) {
            while (dis.read() != -1) {
            }
        }

        val digest = messageDigest.digest();

        if (digest == null) {
            return null;
        }

        val sb = new StringBuilder();

        for (val b : digest) {
            sb.append(String.format("%02x", b));
        }

        return sb.toString();
    }

    private Map<String, List<File>> removeHardLinks(Map<String, List<File>> duplicates) {
        // Create a map of inode numbers to files
        Map<String, List<File>> inodes = new HashMap<>();

        // Populate the inode map
        for (List<File> files : duplicates.values()) {
            for (File file : files) {
                val inode = getFileKey(file);
                if (inode != null) {
                    inodes.computeIfAbsent(inode, k -> new ArrayList<>()).add(file);
                }
            }
        }

        // Remove hard-linked files from the duplicate map
        for (List<File> files : duplicates.values()) {
            Iterator<File> iterator = files.iterator();
            while (iterator.hasNext()) {
                File file = iterator.next();
                val inode = getFileKey(file);
                if (inode != null && inodes.get(inode).size() > 1) {
                    // File is a hard link, remove it from the list
                    iterator.remove();
                    inodes.get(inode).remove(file);
                }
            }
        }

        // Remove empty entries from the duplicate map
        duplicates.values().removeIf(List::isEmpty);

        return duplicates;
    }

    private String getFileKey(File file) {
        try {
            // Get the file's inode number
            long inode = (Long) Files.getAttribute(file.toPath(), "unix:ino");
            return file.toPath().getFileSystem().provider().getScheme() + ":" + inode;
        } catch (IOException | UnsupportedOperationException e) {
            // If we can't read the file's attributes, assume it's not a hard link
            return null;
        }
    }

    private void showMessage(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Information");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

}