package com.productcatalog.service;

import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.embed.swing.SwingFXUtils;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service for loading and caching product images.
 * Matches image files to product codes by filename (case-insensitive).
 */
public class ImageService {

    private static final Logger LOG = Logger.getLogger(ImageService.class.getName());
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".bmp");
    private static final int THUMBNAIL_SIZE = 250;
    private static final int MAX_CACHE_SIZE = 500;

    // code (lowercase) -> absolute file path
    private final Map<String, String> codeToImagePath = new HashMap<>();
    // code (lowercase) -> cached thumbnail Image
    private final Map<String, Image> thumbnailCache = new ConcurrentHashMap<>();

    private static Image placeholderImage;

    /**
     * Scans a folder for image files and builds the code-to-path mapping.
     * @return number of images found
     */
    public int scanFolder(File folder) {
        codeToImagePath.clear();
        thumbnailCache.clear();

        if (folder == null || !folder.isDirectory()) {
            LOG.warning("Image folder is null or not a directory");
            return 0;
        }

        File[] files = folder.listFiles();
        if (files == null) return 0;

        int count = 0;
        for (File file : files) {
            if (!file.isFile()) continue;
            String fileName = file.getName();
            int dotIdx = fileName.lastIndexOf('.');
            if (dotIdx <= 0) continue;

            String ext = fileName.substring(dotIdx).toLowerCase();
            if (!SUPPORTED_EXTENSIONS.contains(ext)) continue;

            String code = fileName.substring(0, dotIdx).toLowerCase();
            if (!codeToImagePath.containsKey(code)) {
                codeToImagePath.put(code, file.getAbsolutePath());
                count++;
            } else {
                LOG.info("Duplicate image for code: " + code + " (keeping first)");
            }
        }

        LOG.info("Scanned " + count + " images from: " + folder.getAbsolutePath());
        return count;
    }

    /**
     * Registers a single image path for a product code (used when loading from DB).
     */
    public void registerImage(String productCode, String imagePath) {
        if (productCode != null && imagePath != null) {
            codeToImagePath.put(productCode.toLowerCase(), imagePath);
        }
    }

    /**
     * Returns the image file path for a product code, or null if not found.
     */
    public String getImagePath(String productCode) {
        if (productCode == null) return null;
        return codeToImagePath.get(productCode.toLowerCase());
    }

    /**
     * Returns true if an image exists for the given product code.
     */
    public boolean hasImage(String productCode) {
        if (productCode == null) return false;
        return codeToImagePath.containsKey(productCode.toLowerCase());
    }

    /**
     * Loads and returns a thumbnail Image for the product code.
     * Uses caching to avoid re-reading files.
     */
    public Image getThumbnail(String productCode) {
        if (productCode == null || productCode.isEmpty()) {
            return getPlaceholder();
        }

        String key = productCode.toLowerCase();
        Image cached = thumbnailCache.get(key);
        if (cached != null) return cached;

        String path = codeToImagePath.get(key);
        if (path == null) return getPlaceholder();

        try {
            File file = new File(path);
            if (!file.exists()) return getPlaceholder();

            Image img = new Image(file.toURI().toString(),
                    THUMBNAIL_SIZE, THUMBNAIL_SIZE, true, true, false);

            // Evict oldest entries if cache is full
            if (thumbnailCache.size() >= MAX_CACHE_SIZE) {
                Iterator<String> it = thumbnailCache.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            thumbnailCache.put(key, img);
            return img;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error loading thumbnail for: " + productCode, e);
            return getPlaceholder();
        }
    }

    /**
     * Loads a full-size image for the product code (for detail panel / export).
     */
    public Image getFullImage(String productCode, double maxWidth, double maxHeight) {
        if (productCode == null) return getPlaceholder();
        String path = codeToImagePath.get(productCode.toLowerCase());
        if (path == null) return getPlaceholder();

        try {
            return new Image(new File(path).toURI().toString(),
                    maxWidth, maxHeight, true, true, false);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error loading image for: " + productCode, e);
            return getPlaceholder();
        }
    }

    /**
     * Returns the number of images currently mapped.
     */
    public int getImageCount() {
        return codeToImagePath.size();
    }

    /**
     * Returns a placeholder image for products without images.
     */
    public static Image getPlaceholder() {
        if (placeholderImage == null) {
            placeholderImage = createPlaceholderImage();
        }
        return placeholderImage;
    }

    /**
     * Creates a simple placeholder image programmatically using AWT.
     */
    private static Image createPlaceholderImage() {
        int size = THUMBNAIL_SIZE;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();

        // Background
        g2d.setColor(new Color(240, 240, 240));
        g2d.fillRect(0, 0, size, size);

        // Border
        g2d.setColor(new Color(200, 200, 200));
        g2d.drawRect(0, 0, size - 1, size - 1);

        // Text rendering hints for smooth text
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Text
        g2d.setColor(new Color(150, 150, 150));
        g2d.setFont(new Font("SansSerif", Font.BOLD, size / 8));
        
        String text1 = "Not";
        String text2 = "Found";
        
        FontMetrics fm = g2d.getFontMetrics();
        int w1 = fm.stringWidth(text1);
        int w2 = fm.stringWidth(text2);
        int h = fm.getAscent();
        
        g2d.drawString(text1, (size - w1) / 2, size / 2 - 2);
        g2d.drawString(text2, (size - w2) / 2, size / 2 + h + 2);
        
        g2d.dispose();

        return SwingFXUtils.toFXImage(img, null);
    }

    /**
     * Returns the image file bytes for a product code (used by export service).
     */
    public byte[] getImageBytes(String productCode) {
        if (productCode == null) return null;
        String path = codeToImagePath.get(productCode.toLowerCase());
        if (path == null) return null;

        try {
            return Files.readAllBytes(Path.of(path));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error reading image bytes for: " + productCode, e);
            return null;
        }
    }

    /**
     * Returns the file extension for a product code's image.
     */
    public String getImageExtension(String productCode) {
        if (productCode == null) return null;
        String path = codeToImagePath.get(productCode.toLowerCase());
        if (path == null) return null;
        int dotIdx = path.lastIndexOf('.');
        return dotIdx > 0 ? path.substring(dotIdx + 1).toLowerCase() : null;
    }
}
