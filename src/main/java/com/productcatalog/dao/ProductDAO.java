package com.productcatalog.dao;

import com.productcatalog.model.Product;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Data Access Object for Product persistence via SQLite.
 * Handles CRUD operations and batch inserts.
 */
public class ProductDAO {

    private static final Logger LOG = Logger.getLogger(ProductDAO.class.getName());
    private final DatabaseManager dbManager;

    public ProductDAO() {
        this.dbManager = DatabaseManager.getInstance();
    }

    /**
     * Inserts a product into the database. Ignores if code already exists.
     * 
     * @return true if inserted, false if duplicate
     */
    public boolean insert(Product product) {
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO products (name, made_in, code, description, image_path, source_file");
        for (int i = 1; i <= 40; i++) {
            sql.append(", shop_code_").append(i);
        }
        sql.append(") VALUES (?, ?, ?, ?, ?, ?");
        for (int i = 0; i < 40; i++) {
            sql.append(", ?");
        }
        sql.append(") ON CONFLICT(code) DO UPDATE SET ");
        sql.append("name=excluded.name, made_in=excluded.made_in, description=excluded.description, ");
        sql.append("image_path=COALESCE(excluded.image_path, products.image_path), source_file=excluded.source_file");

        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql.toString(),
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, product.getName());
            ps.setString(2, product.getMadeIn());
            ps.setString(3, product.getCode());
            ps.setString(4, product.getDescription());
            ps.setString(5, product.getImagePath());
            ps.setString(6, product.getSourceFile());
            for (int i = 0; i < 40; i++) {
                ps.setString(7 + i, product.getShopCode(i));
            }
            int rows = ps.executeUpdate();
            if (rows > 0) {
                ResultSet keys = ps.getGeneratedKeys();
                if (keys.next()) {
                    product.setId(keys.getInt(1));
                }
                return true;
            }
            return false;
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Error inserting product: " + product.getCode(), e);
            return false;
        }
    }

    /**
     * Batch-inserts a list of products with transaction for performance.
     * 
     * @return number of products actually inserted
     */
    public int insertBatch(List<Product> products) {
        Connection conn = dbManager.getConnection();
        int inserted = 0;
        try {
            conn.setAutoCommit(false);
            for (Product p : products) {
                if (insert(p))
                    inserted++;
            }
            conn.commit();
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Batch insert failed", e);
            try {
                conn.rollback();
            } catch (SQLException ex) {
                /* ignore */ }
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException e) {
                /* ignore */ }
        }
        return inserted;
    }

    /**
     * Updates a product's shop codes and selection state.
     */
    public void update(Product product) {
        StringBuilder sql = new StringBuilder(
                "UPDATE products SET name=?, made_in=?, description=?, image_path=?, selected=?");
        for (int i = 1; i <= 40; i++) {
            sql.append(", shop_code_").append(i).append("=?");
        }
        sql.append(" WHERE code=?");

        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql.toString())) {
            ps.setString(1, product.getName());
            ps.setString(2, product.getMadeIn());
            ps.setString(3, product.getDescription());
            ps.setString(4, product.getImagePath());
            ps.setInt(5, product.isSelected() ? 1 : 0);
            for (int i = 0; i < 40; i++) {
                ps.setString(6 + i, product.getShopCode(i));
            }
            ps.setString(46, product.getCode());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Error updating product: " + product.getCode(), e);
        }
    }

    /**
     * Loads all products from the database.
     */
    public List<Product> findAll() {
        List<Product> products = new ArrayList<>();
        String sql = "SELECT * FROM products ORDER BY code";
        try (Statement stmt = dbManager.getConnection().createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                products.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Error loading products", e);
        }
        return products;
    }

    /**
     * Finds a product by its code.
     */
    public Product findByCode(String code) {
        String sql = "SELECT * FROM products WHERE code = ?";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, code);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return mapRow(rs);
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Error finding product: " + code, e);
        }
        return null;
    }

    /**
     * Checks if a product code exists in the database.
     */
    public boolean existsByCode(String code) {
        String sql = "SELECT COUNT(*) FROM products WHERE code = ?";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, code);
            ResultSet rs = ps.executeQuery();
            if (rs.next())
                return rs.getInt(1) > 0;
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Error checking product existence: " + code, e);
        }
        return false;
    }

    /**
     * Returns the total count of products.
     */
    public int count() {
        try (Statement stmt = dbManager.getConnection().createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM products")) {
            if (rs.next())
                return rs.getInt(1);
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Error counting products", e);
        }
        return 0;
    }

    /**
     * Saves all products (batch update). Used for persisting shop code changes.
     */
    public void saveAll(List<Product> products) {
        Connection conn = dbManager.getConnection();
        try {
            conn.setAutoCommit(false);
            for (Product p : products) {
                update(p);
            }
            conn.commit();
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Save all failed", e);
            try {
                conn.rollback();
            } catch (SQLException ex) {
                /* ignore */ }
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException e) {
                /* ignore */ }
        }
    }

    private Product mapRow(ResultSet rs) throws SQLException {
        Product p = new Product();
        p.setId(rs.getInt("id"));
        p.setName(rs.getString("name"));
        p.setMadeIn(rs.getString("made_in"));
        p.setCode(rs.getString("code"));
        p.setDescription(rs.getString("description"));
        p.setImagePath(rs.getString("image_path"));
        p.setSourceFile(rs.getString("source_file"));
        p.setSelected(rs.getInt("selected") == 1);
        for (int i = 0; i < 40; i++) {
            String sc = rs.getString("shop_code_" + (i + 1));
            p.setShopCode(i, sc != null ? sc : "");
        }
        return p;
    }

    /**
     * Deletes a batch of products identified by their codes.
     * @param codes list of product codes to delete
     * @return number of rows actually deleted
     */
    public int deleteByCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) return 0;
        String placeholders = codes.stream().map(c -> "?").collect(java.util.stream.Collectors.joining(", "));
        String sql = "DELETE FROM products WHERE LOWER(code) IN (" + placeholders + ")";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            for (int i = 0; i < codes.size(); i++) {
                ps.setString(i + 1, codes.get(i).toLowerCase());
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Failed to delete products", e);
            return 0;
        }
    }

    /**
     * Returns distinct base filenames (the part before " [SheetName]") from the source_file column.
     */
    public List<String> getDistinctSourceFilenames() {
        List<String> result = new ArrayList<>();
        String sql = "SELECT DISTINCT source_file FROM products WHERE source_file IS NOT NULL AND source_file != '' ORDER BY source_file";
        try (Statement st = dbManager.getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            while (rs.next()) {
                String sf = rs.getString(1);
                String fname = sf.contains(" [") ? sf.substring(0, sf.indexOf(" [")) : sf;
                if (!fname.isEmpty()) seen.add(fname);
            }
            result.addAll(seen);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Failed to get distinct source filenames", e);
        }
        return result;
    }

    /**
     * Finds all products whose source_file starts with the given base filename.
     */
    public List<Product> findBySourceFilename(String filename) {
        List<Product> products = new ArrayList<>();
        String sql = "SELECT * FROM products WHERE source_file LIKE ? ORDER BY name";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, filename + "%");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) products.add(mapRow(rs));
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Failed to find products by source filename", e);
        }
        return products;
    }

    /**
     * Returns true if any product exists with a source_file matching the given base filename.
     */
    public boolean existsBySourceFilename(String filename) {
        String sql = "SELECT COUNT(*) FROM products WHERE source_file LIKE ?";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, filename + "%");
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1) > 0;
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Failed to check existence by source filename", e);
        }
        return false;
    }
}
