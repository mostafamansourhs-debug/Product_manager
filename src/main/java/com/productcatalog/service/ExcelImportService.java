package com.productcatalog.service;

import com.productcatalog.model.Product;
import javafx.concurrent.Task;
import org.apache.poi.ss.usermodel.*;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background service that imports products from all Excel files in a folder.
 * Matches columns by header name (case-insensitive), tolerates extra columns.
 */
public class ExcelImportService {

    private static final Logger LOG = Logger.getLogger(ExcelImportService.class.getName());

    // Expected column names (case-insensitive matching)
    private static final String COL_NAME = "product name";
    private static final String COL_MADE_IN = "made in";
    private static final String COL_CODE = "code";
    private static final String COL_DESCRIPTION = "description";
    private static final String COL_SKU = "sku";

    /**
     * Result of an import operation.
     */
    public static class ImportResult {
        public final List<Product> products;
        public final List<String> warnings;
        public final List<String> errors;
        public int totalFiles;
        public int totalRows;
        public int duplicates;
        public int missingImages;
        public int skipped;

        public ImportResult() {
            this.products = new ArrayList<>();
            this.warnings = new ArrayList<>();
            this.errors = new ArrayList<>();
        }
    }

    /**
     * Creates a JavaFX Task for background import.
     * Files whose data already exists in the DB (matched by filename) are skipped;
     * a note is added to result.warnings for each skipped file.
     */
    public Task<ImportResult> createImportTask(List<File> excelFiles, ImageService imageService,
                                               com.productcatalog.dao.ProductDAO productDAO) {
        return new Task<>() {
            @Override
            protected ImportResult call() throws Exception {
                ImportResult result = new ImportResult();
                Set<String> seenCodes = new HashSet<>();

                if (excelFiles == null || excelFiles.isEmpty()) {
                    result.errors.add("No Excel files selected.");
                    return result;
                }

                result.totalFiles = excelFiles.size();
                int processed = 0;

                for (File file : excelFiles) {
                    if (isCancelled()) break;

                    // Skip file if its data is already stored in the database
                    if (productDAO != null && productDAO.existsBySourceFilename(file.getName())) {
                        result.warnings.add("'" + file.getName() + "' already exists in DB — loaded from DB, not re-imported.");
                        result.skipped++;
                        processed++;
                        updateProgress(processed, excelFiles.size());
                        updateMessage("Skipping (already in DB): " + file.getName());
                        continue;
                    }

                    try {
                        processFile(file, result, seenCodes, imageService);
                    } catch (Exception e) {
                        result.errors.add("Error reading file '" + file.getName() + "': " + e.getMessage());
                        LOG.log(Level.WARNING, "Error processing file: " + file.getName(), e);
                    }
                    processed++;
                    updateProgress(processed, excelFiles.size());
                    updateMessage("Processing: " + file.getName() + " (" + processed + "/" + excelFiles.size() + ")");
                }

                // Count missing images
                if (imageService != null) {
                    for (Product p : result.products) {
                        if (!imageService.hasImage(p.getCode())) {
                            result.missingImages++;
                        }
                    }
                } else {
                    result.missingImages = result.products.size();
                }

                updateMessage("Import complete: " + result.products.size() + " new products loaded.");
                return result;
            }
        };
    }

    private void processFile(File file, ImportResult result, Set<String> seenCodes,
                             ImageService imageService) throws Exception {
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = WorkbookFactory.create(fis)) {

            int numSheets = workbook.getNumberOfSheets();
            for (int s = 0; s < numSheets; s++) {
                Sheet sheet = workbook.getSheetAt(s);
                if (sheet == null) continue;

                Row headerRow = sheet.getRow(0);
                if (headerRow == null) {
                    continue; // Skip empty sheets
                }

                // Map column indices by header name
                Map<String, Integer> colMap = mapColumns(headerRow);

                if (!colMap.containsKey(COL_CODE)) {
                    continue; // Skip sheets without a code column
                }

                DataFormatter formatter = new DataFormatter();

                for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;

                    try {
                        String code = getCellValue(row, colMap.get(COL_CODE), formatter).trim();
                        if (code.isEmpty()) continue; // Ignore completely empty rows

                        // Check for duplicate codes
                        if (seenCodes.contains(code.toLowerCase())) {
                            result.duplicates++;
                            result.warnings.add("Duplicate code '" + code + "' in '" + file.getName()
                                    + "' sheet '" + sheet.getSheetName() + "' row " + (i + 1) + " — skipped (keeping first).");
                            continue;
                        }
                        seenCodes.add(code.toLowerCase());

                        String name = getCellValue(row, colMap.get(COL_NAME), formatter).trim();
                        String madeIn = getCellValue(row, colMap.get(COL_MADE_IN), formatter).trim();
                        String description = getCellValue(row, colMap.get(COL_DESCRIPTION), formatter).trim();

                        // Match image
                        String imagePath = null;
                        if (imageService != null) {
                            imagePath = imageService.getImagePath(code);
                        }

                        Product product = new Product(name, madeIn, code, description, imagePath);
                        if (colMap.containsKey(COL_SKU)) {
                            String sku = getCellValue(row, colMap.get(COL_SKU), formatter).trim();
                            if (!sku.isEmpty()) {
                                product.setSku(sku);
                            }
                        }
                        product.setSourceFile(file.getName() + " [" + sheet.getSheetName() + "]");
                        
                        // Extract shop codes
                        for (int sIdx = 1; sIdx <= Product.MAX_SHOP_CODES; sIdx++) {
                            String shopHeader = "shop " + sIdx;
                            if (colMap.containsKey(shopHeader)) {
                                String shopVal = getCellValue(row, colMap.get(shopHeader), formatter).trim();
                                product.setShopCode(sIdx - 1, shopVal);
                            }
                        }

                        result.products.add(product);
                        result.totalRows++;

                    } catch (Exception e) {
                        result.warnings.add(file.getName() + " sheet '" + sheet.getSheetName() + "' row " + (i + 1) + ": parse error — " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Maps header names (lowercase) to column indices.
     */
    private Map<String, Integer> mapColumns(Row headerRow) {
        Map<String, Integer> map = new HashMap<>();
        DataFormatter formatter = new DataFormatter();
        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            Cell cell = headerRow.getCell(i);
            if (cell != null) {
                String header = formatter.formatCellValue(cell).trim().toLowerCase();
                if (!header.isEmpty()) {
                    map.put(header, i);
                }
            }
        }
        return map;
    }

    /**
     * Safely gets a cell value as a trimmed string.
     */
    private String getCellValue(Row row, Integer colIndex, DataFormatter formatter) {
        if (colIndex == null) return "";
        Cell cell = row.getCell(colIndex);
        if (cell == null) return "";
        return formatter.formatCellValue(cell);
    }
}
