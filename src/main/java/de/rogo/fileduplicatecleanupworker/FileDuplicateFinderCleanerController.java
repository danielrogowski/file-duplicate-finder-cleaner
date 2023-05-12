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

    private final Object listOfDuplicatesListsMutex = new Object();

    private int filesProcessed;

    private boolean error = false;

    private final boolean debug = false;

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
            val searchResult = findDuplicates();
            Platform.runLater(() -> {
                progressIndicator.setVisible(false);
                displaySearchResults(searchResult);
                directoryText.setText("");
                choosePath.setDisable(false);
            });
        }).start();
    }

    protected List<List<File>> findDuplicates() {
        // Get the chosen directory
        val directoryText = this.directoryText.getText();
        val directoryPaths = directoryText.split("\\r?\\n");

        // Map of file sizes to lists of files with those sizes
        final Map<Long, List<File>> sizeMap = new HashMap<>();

        // Iterate over all files in the directory and its subdirectories
        for (val directoryPath : directoryPaths) {
            val directory = new File(directoryPath);

            // Check that the directory exists and is a directory
            if (!directory.exists() || !directory.isDirectory()) {
                Platform.runLater(() -> {
                    new Alert(Alert.AlertType.ERROR, directoryPath + "isn't a valid directory, ignoring...").showAndWait();
                });
                continue;
            }

            try {
                Files.walk(directory.toPath())
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            try {
                                return !Files.isHidden(path);
                            } catch (IOException e) {
                                processIOException(e);
                            }
                            return false;
                        })
                        .filter(path -> !path.toString().contains(File.separator + "."))
                        .forEach(file -> {
                            if (error) {
                                return;
                            }
                            try {
                                val size = Files.size(file);
                                val files = sizeMap.computeIfAbsent(size, k -> new ArrayList<>());
                                files.add(file.toFile());
                            } catch (IOException e) {
                                processIOException(e);
                            }
                        });
            } catch (IOException e) {
                processIOException(e);
            }
        }

        System.out.println(countFilesToProcess(sizeMap) + " possible duplicates to process");

        final List<List<File>> listOfDuplicatesLists = new LinkedList<>();

        val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        // Iterate over all files with the same size and compare
        for (val entry : sizeMap.entrySet()) {
            if (error) {
                break;
            }

            val files = entry.getValue();

            if (files.size() < 2) {
                continue;
            }

            executor.execute(() -> {
                try {
                    val numberFiles = files.size();
                    val compareResult = compareFiles(files);

                    synchronized (listOfDuplicatesListsMutex) {
                        listOfDuplicatesLists.addAll(compareResult);
                    }

                    synchronized (System.out) {
                        System.out.print("\rFiles processed: " + (filesProcessed += numberFiles));
                        System.out.flush();
                    }
                } catch (IOException e) {
                    processIOException(e);
                }
            });
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

        if (error) {
            return List.of();
        }

        return removeHardLinks(listOfDuplicatesLists);
    }

    private void processIOException(IOException e) {
        error = true;
        synchronized (Platform.class) {
            Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                    "An I/O error occured while processing files. Please check your file system for errors")
                    .showAndWait());
            e.printStackTrace();
        }
    }

    private <K, VV> int countFilesToProcess(final Map<K, List<VV>> map) {
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

    private void displaySearchResults(final List<List<File>> searchResult) {
        for (val fileList : searchResult) {
            while (true) {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Duplicate files found");
                alert.setHeaderText(String.format("Found %d duplicate file(s)", fileList.size()));
                alert.setContentText(String.format(
                        "The following files have the same content:\n\n%s\n\nWhat do you want to do?",
                        fileList.stream().map(File::toString).collect(Collectors.joining("\n"))));

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
                        fileList.remove(0);
                        for (val file : fileList) {
                            try {
                                Files.delete(file.toPath());
                            } catch (IOException e) {
                                processIOException(e);
                                return;
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
                        fileChooser.setInitialDirectory(fileList.get(0).getParentFile());

                        File selectedFile = fileChooser.showOpenDialog(root.getScene().getWindow());

                        if (!fileList.contains(selectedFile)) {
                            showMessage("You didn't choose any of the duplicated files. Please try again...");
                            continue;
                        }

                        if (selectedFile != null) {
                            for (File file : fileList) {
                                if (!file.equals(selectedFile)) {
                                    try {
                                        Files.delete(file.toPath());
                                    } catch (IOException e) {
                                        processIOException(e);
                                        return;
                                    }
                                }
                            }
                        }
                    } else if (result.get() == moveButton) {
                        FileChooser fileChooser = new FileChooser();
                        fileChooser.setTitle("Choose a location to move the file(s) to");
                        fileChooser.setInitialFileName(fileList.get(0).getName());
                        File destFile = fileChooser.showSaveDialog(root.getScene().getWindow());

                        if (destFile != null) {
                            // Move the first file to the chosen location
                            try {
                                Files.move(fileList.get(0).toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            } catch (IOException e) {
                                processIOException(e);
                                return;
                            }

                            // Delete the other files
                            for (int i = 1; i < fileList.size(); i++) {
                                File file = fileList.get(i);
                                try {
                                    Files.delete(file.toPath());
                                } catch (IOException e) {
                                    processIOException(e);
                                    return;
                                }
                            }
                        }
                    } else if (result.get() == openButton) {
                        var desktopAvailable = true;
                        if (!Desktop.isDesktopSupported()) {
                            desktopAvailable = false;
                            if (debug) {
                                System.out.println("Desktop not supported");
                            }
                        }
                        if (!Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                            desktopAvailable = false;
                            if (debug) {
                                System.out.println("Desktop file opening not supported");
                            }
                        }

                        for (val file : fileList) {
                            if (error) {
                                return;
                            }
                            Task<Void> task;
                            if (desktopAvailable) {
                                task = new Task<>() {
                                    @Override
                                    public Void call() {
                                        try {
                                            Desktop.getDesktop().open(file);
                                        } catch (IOException e) {
                                            processIOException(e);
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
                                            processIOException(e);
                                        }
                                        return null;
                                    }
                                };
                            }
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

    private List<List<File>> compareFiles(List<File> files) throws IOException {
        if (debug) {
            synchronized (System.out) {
                System.out.println("\nFiles to compare:\n\t" + files);
            }
        }

        List<List<File>> result = new ArrayList<>();

        Map<File, Set<File>> map = new HashMap<>();

        List<File> unmatched = new ArrayList<>();

        var j = 1;

        while (true) {
            val file1 = files.get(0);
            val file2 = files.get(j);
            if (filesEqual(file1, file2)) {
                val set = map.computeIfAbsent(file1, file -> new HashSet<>());
                set.add(file1);
                set.add(file2);
            } else {
                unmatched.add(file2);
            }

            if (j + 1 < files.size()) {
                j++;
            } else if (unmatched.isEmpty()) {
                break;
            } else if (unmatched.size() > 1) {
                files = unmatched;
                unmatched = new ArrayList<>();
                j = 1;
            } else {
                break;
            }
        }

        for (val entry : map.entrySet()) {
            result.add(new ArrayList<>(entry.getValue()));
        }

        if (debug) {
            synchronized (System.out) {
                System.out.println("\nResult:\n\t" + result);
            }
        }

        return result;
    }

    private static boolean filesEqual(final File file1, final File file2) throws IOException {
        try (val in1 = new FileInputStream(file1);
             val in2 = new FileInputStream(file2)) {

            val buf1 = new byte[1024];
            val buf2 = new byte[1024];

            int n1, n2;

            do {
                n1 = in1.read(buf1);
                n2 = in2.read(buf2);

                if (n1 != n2 || !Arrays.equals(buf1, buf2)) {
                    return false;
                }
            } while (n1 > 0);
        }

        return true;
    }


    private List<List<File>> removeHardLinks(final List<List<File>> listOfDuplicatesLists) {
        // Create a map of inode numbers to files
        Map<String, List<File>> inodes = new HashMap<>();

        // Populate the inode map
        for (val files : listOfDuplicatesLists) {
            for (val file : files) {
                val inode = getFileKey(file);
                if (inode != null) {
                    inodes.computeIfAbsent(inode, k -> new ArrayList<>()).add(file);
                }
            }
        }

        // Remove hard-linked files from the duplicate map
        for (val files : listOfDuplicatesLists) {
            val iterator = files.iterator();
            while (iterator.hasNext()) {
                val file = iterator.next();
                val inode = getFileKey(file);
                if (inode != null && inodes.get(inode).size() > 1) {
                    // File is a hard link, remove it from the list
                    iterator.remove();
                    inodes.get(inode).remove(file);
                }
            }
        }

        // Remove empty entries from the duplicate list
        listOfDuplicatesLists.removeIf(List::isEmpty);

        return listOfDuplicatesLists;
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
        val alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Information");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

}