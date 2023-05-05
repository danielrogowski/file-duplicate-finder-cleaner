package de.rogo.fileduplicatecleanupworker;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * Start looking (takes time, grab a coffe or something)
 */
public class FileDuplicateFinderCleanerApplication extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(FileDuplicateFinderCleanerApplication.class.getResource("hello-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 480, 240);
        stage.setTitle("File duplicate finder / cleaner");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        System.out.println("main");
        launch();
    }
}