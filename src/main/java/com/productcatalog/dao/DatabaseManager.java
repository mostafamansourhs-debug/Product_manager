package com.productcatalog.dao;

import java.io.File;
import java.sql.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the embedded SQLite database connection and schema creation.
 * Uses singleton pattern for global access.
 */
public class DatabaseManager {

    private static final Logger LOG = Logger.getLogger(DatabaseManager.class.getName());
    private static DatabaseManager instance;
    private Connection connection;
    private String dbPath;

    private DatabaseManager() {}

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    /**
     * Initializes the database, creating the file and schema if needed.
     */
    public void initialize() throws SQLException {
        dbPath = getAppDataDir() + File.separator + "productcatalog.db";
        File dbFile = new File(dbPath);
        dbFile.getParentFile().mkdirs();

        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        // Enable WAL mode for better concurrent read performance
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
        }
        createTables();
        LOG.info("Database initialized at: " + dbPath);
    }

    private void createTables() throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE IF NOT EXISTS products (");
        sql.append("id INTEGER PRIMARY KEY AUTOINCREMENT, ");
        sql.append("name TEXT NOT NULL DEFAULT '', ");
        sql.append("made_in TEXT NOT NULL DEFAULT '', ");
        sql.append("code TEXT UNIQUE NOT NULL, ");
        sql.append("description TEXT NOT NULL DEFAULT '', ");
        sql.append("image_path TEXT, ");
        sql.append("source_file TEXT, ");
        for (int i = 1; i <= 40; i++) {
            sql.append("shop_code_").append(i).append(" TEXT DEFAULT '', ");
        }
        sql.append("selected INTEGER DEFAULT 0");
        sql.append(")");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql.toString());
        }

        // Try to alter existing table to add new columns (21-40) in case DB already exists
        try (Statement stmt = connection.createStatement()) {
            for (int i = 21; i <= 40; i++) {
                try {
                    stmt.execute("ALTER TABLE products ADD COLUMN shop_code_" + i + " TEXT DEFAULT ''");
                } catch (SQLException ignore) {
                    // Column might already exist
                }
            }
        }

        // Create index on code for fast lookup
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_products_code ON products(code)");
        }
    }

    /**
     * Returns the app data directory path (cross-platform).
     */
    private String getAppDataDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return (appData != null ? appData : home) + File.separator + "ProductsCatalog";
        } else if (os.contains("mac") || os.contains("darwin")) {
            return home + "/Library/Application Support/ProductsCatalog";
        } else {
            return home + "/.productscatalog";
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public String getDbPath() {
        return dbPath;
    }

    /**
     * Closes the database connection gracefully.
     */
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                LOG.info("Database connection closed.");
            } catch (SQLException e) {
                LOG.log(Level.WARNING, "Error closing database", e);
            }
        }
    }

    /**
     * Clears all products from the database (used before re-import).
     */
    public void clearProducts() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM products");
        }
    }
}
