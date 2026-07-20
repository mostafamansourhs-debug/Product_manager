package com.productcatalog;

import com.productcatalog.controller.CatalogController;
import com.productcatalog.dao.DatabaseManager;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;

import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import javafx.stage.Screen;
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

        // Apply DPI-aware font scaling so the app looks correct on high-DPI Windows displays.
        // On a standard 96 DPI screen this is 1.0x, on 144 DPI (150% scaling) this is 1.5x, etc.
        applyDpiScaling(scene);

        LOG.info("Application started.");
    }

    /**
     * Detects the system DPI scale factor and adjusts the root font size so that
     * all em/relative sizes in the CSS scale proportionally.
     * On Windows with display scaling (e.g. 125%, 150%), packaged JavaFX EXEs
     * often render at 96 DPI regardless, making everything tiny.
     * This method compensates by increasing the base font size.
     */
    private void applyDpiScaling(Scene scene) {
        try {
            double dpi = Screen.getPrimary().getDpi();
            double scaleFactor = dpi / 96.0;
            LOG.info("Detected screen DPI: " + dpi + ", scale factor: " + scaleFactor);

            // Only scale up if the DPI is higher than standard 96.
            // Base font size in CSS is 13px; scale it proportionally.
            if (scaleFactor > 1.05) {
                double scaledFontSize = Math.round(13 * scaleFactor);
                scene.getRoot().setStyle("-fx-font-size: " + (int) scaledFontSize + "px;");
                LOG.info("Applied DPI-scaled font size: " + (int) scaledFontSize + "px");
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not detect DPI for scaling", e);
        }
    }

    private void showFatalError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        // Enable JavaFX hi-DPI scaling support before the toolkit initializes.
        // This ensures JavaFX respects Windows display scaling settings.
        System.setProperty("prism.allowHiDPIScaling", "true");
        System.setProperty("glass.win.uiScale", "1.0");

        launch(args);
    }
}
