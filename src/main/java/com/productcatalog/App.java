package com.productcatalog;

import com.productcatalog.controller.CatalogController;
import com.productcatalog.dao.DatabaseManager;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main JavaFX application entry point.
 * Sets up the stage with a TabPane containing the Product Catalog and Shop Discovery tabs.
 */
public class App extends Application {

    private static final Logger LOG = Logger.getLogger(App.class.getName());

    private CatalogController catalogController;
    private Label statusLabel;

    @Override
    public void start(Stage stage) {
        try {
            // Initialize database
            DatabaseManager.getInstance().initialize();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to initialize database", e);
            showFatalError("Database Error",
                    "Could not initialize the local database.\n" + e.getMessage());
            return;
        }

        // Root layout
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");

        // Status bar (shared)
        statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("global-status");

        HBox globalStatusBar = new HBox(statusLabel);
        globalStatusBar.getStyleClass().add("global-status-bar");
        globalStatusBar.setPadding(new Insets(4, 12, 4, 12));
        globalStatusBar.setAlignment(Pos.CENTER_LEFT);

        // Create controllers
        catalogController = new CatalogController(statusLabel);

        root.setCenter(catalogController.getView());
        root.setBottom(globalStatusBar);

        // Scene
        Scene scene = new Scene(root, 1200, 800);
        scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());

        // Stage
        stage.setScene(scene);
        stage.setTitle("Products Catalog — Shop Discovery");
        stage.setMinWidth(900);
        stage.setMinHeight(600);

        // Save data on close
        stage.setOnCloseRequest(e -> {
            catalogController.saveAll();
            DatabaseManager.getInstance().close();
        });

        stage.setX(100);
        stage.setY(50);
        stage.show();
        LOG.info("Application started.");
    }

    private void showFatalError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
