package com.productcatalog.service;

import com.productcatalog.model.Product;
import javafx.concurrent.Task;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFPictureData;
import org.apache.poi.xssf.usermodel.XSSFShape;
import org.apache.poi.xssf.usermodel.XSSFSheet;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final String COL_IMAGE = "image";

    // Matches shop headers such as "shop 1", "shop 01", "sh-01", "sh 1"
    private static final Pattern SHOP_HEADER_PATTERN =
            Pattern.compile("^(?:sh|shop)[\\s-]*(\\d+)$");

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
        public int extractedImages;
        public int missingEmbeddedImages;
        public boolean imageMode;

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
        return createImportTask(excelFiles, imageService, productDAO, false);
    }

    /**
     * Creates a JavaFX Task for background import.
     *
     * @param importImages when true, embedded images are extracted from each
     *                     exported sheet, saved to the app-data folder, and
     *                     linked to the matching product code. Files whose data
     *                     already exists in the DB are still processed so that
     *                     existing products can be refreshed.
     */
    public Task<ImportResult> createImportTask(List<File> excelFiles, ImageService imageService,
                                               com.productcatalog.dao.ProductDAO productDAO,
                                               boolean importImages) {
        return new Task<>() {
            @Override
            protected ImportResult call() throws Exception {
                ImportResult result = new ImportResult();
                result.imageMode = importImages;
                Set<String> seenCodes = new HashSet<>();

                if (excelFiles == null || excelFiles.isEmpty()) {
                    result.errors.add("No Excel files selected.");
                    return result;
                }

                result.totalFiles = excelFiles.size();
                int processed = 0;

                for (File file : excelFiles) {
                    if (isCancelled()) break;

                    // Skip file if its data is already stored in the database.
                    // Image imports must still run so conflicts can refresh images.
                    if (!importImages && productDAO != null
                            && productDAO.existsBySourceFilename(file.getName())) {
                        result.warnings.add("'" + file.getName() + "' already exists in DB — loaded from DB, not re-imported.");
                        result.skipped++;
                        processed++;
                        updateProgress(processed, excelFiles.size());
                        updateMessage("Skipping (already in DB): " + file.getName());
                        continue;
                    }

                    try {
                        processFile(file, result, seenCodes, imageService, importImages);
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

                if (importImages) {
                    updateMessage("Import complete: " + result.products.size() + " products loaded, "
                            + result.extractedImages + " images extracted.");
                } else {
                    updateMessage("Import complete: " + result.products.size() + " new products loaded.");
                }
                return result;
            }
        };
    }

    private void processFile(File file, ImportResult result, Set<String> seenCodes,
                             ImageService imageService, boolean importImages) throws Exception {
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

                warnExtraShopColumns(colMap, result, file, sheet);

                Map<Integer, EmbeddedImage> rowImages = importImages
                        ? extractEmbeddedImages(sheet, colMap.get(COL_IMAGE))
                        : Collections.emptyMap();

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

                        // Match image: prefer an embedded picture from the sheet
                        String imagePath = null;
                        if (importImages && imageService != null) {
                            EmbeddedImage embedded = rowImages.get(i);
                            if (embedded != null) {
                                imagePath = imageService.saveExtractedImage(code, embedded.data, embedded.extension);
                            }
                            if (imagePath != null) {
                                result.extractedImages++;
                            } else {
                                result.missingEmbeddedImages++;
                            }
                        }
                        if (imagePath == null && imageService != null) {
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
                        
                        // Extract shop codes — recognise both source formats:
                        //   original format : "shop 1", "shop 2" … "shop 15"
                        //   exported format : "sh-01", "sh-02" … "sh-15"
                        // Also tolerate zero-padded / unpadded variants ("shop 01", "sh-1").
                        for (int sIdx = 1; sIdx <= Product.MAX_SHOP_CODES; sIdx++) {
                            String shopVal = null;
                            for (String header : shopHeaderVariants(sIdx)) {
                                Integer col = colMap.get(header);
                                if (col != null) {
                                    shopVal = getCellValue(row, col, formatter).trim();
                                    break;
                                }
                            }
                            if (shopVal != null && !shopVal.isEmpty()) {
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

    private String[] shopHeaderVariants(int index) {
        String padded = String.format("%02d", index);
        return new String[] {
                "shop " + index,
                "shop " + padded,
                "sh-" + padded,
                "sh-" + index
        };
    }

    /**
     * Warns once per sheet when shop columns beyond {@link Product#MAX_SHOP_CODES}
     * are present. Only the first 15 slots are imported.
     */
    private void warnExtraShopColumns(Map<String, Integer> colMap, ImportResult result,
                                      File file, Sheet sheet) {
        List<String> extras = new ArrayList<>();
        for (String header : colMap.keySet()) {
            Matcher m = SHOP_HEADER_PATTERN.matcher(header);
            if (m.matches()) {
                try {
                    if (Integer.parseInt(m.group(1)) > Product.MAX_SHOP_CODES) {
                        extras.add(header);
                    }
                } catch (NumberFormatException ignore) {
                    // Not a numeric shop suffix — ignore.
                }
            }
        }
        if (!extras.isEmpty()) {
            Collections.sort(extras);
            result.warnings.add("'" + file.getName() + "' sheet '" + sheet.getSheetName()
                    + "': ignoring shop columns beyond " + Product.MAX_SHOP_CODES
                    + " (" + String.join(", ", extras) + ").");
        }
    }

    /**
     * Maps each row index to the picture anchored on that row in the Image column.
     */
    private Map<Integer, EmbeddedImage> extractEmbeddedImages(Sheet sheet, Integer imageCol) {
        Map<Integer, EmbeddedImage> images = new HashMap<>();
        if (!(sheet instanceof XSSFSheet xssfSheet)) return images;

        XSSFDrawing drawing = xssfSheet.getDrawingPatriarch();
        if (drawing == null) return images;

        for (XSSFShape shape : drawing.getShapes()) {
            if (!(shape instanceof XSSFPicture picture)) continue;

            XSSFClientAnchor anchor = picture.getClientAnchor();
            XSSFPictureData pictureData = picture.getPictureData();
            if (anchor == null || pictureData == null) continue;

            // When the layout has an Image column, ignore pictures elsewhere
            // (e.g. logos). Allow a one-column tolerance for anchors.
            if (imageCol != null && Math.abs(anchor.getCol1() - imageCol) > 1) continue;

            byte[] data = pictureData.getData();
            if (data == null || data.length == 0) continue;

            images.putIfAbsent(anchor.getRow1(),
                    new EmbeddedImage(data, pictureData.suggestFileExtension()));
        }
        return images;
    }

    private static final class EmbeddedImage {
        final byte[] data;
        final String extension;

        EmbeddedImage(byte[] data, String extension) {
            this.data = data;
            this.extension = extension;
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
