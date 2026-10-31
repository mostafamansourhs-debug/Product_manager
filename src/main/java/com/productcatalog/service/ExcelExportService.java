package com.productcatalog.service;

import com.productcatalog.model.Product;
import javafx.concurrent.Task;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background service that exports selected products to an Excel file
 * with embedded images.
 *
 * Export format (per image specification):
 *  Col 0  – NO           width =  3.71 chars / 950  (font 11, centered)
 *  Col 1  – Product Name width = 24.71 chars / 6326 (bold, font 12, centered, wrap)
 *  Col 2  – Made In      width =  8.71 chars / 2230 (bold, font 12, centered)
 *  Col 3  – Code         width =  9.71 chars / 2486 (font 11, centered)
 *  Col 4  – Description  width = 11.71 chars / 2999 (font 11, centered, wrap)
 *  Col 5  – Image        width = 12.71 chars / 3254 (square image area)
 *  Col 6+ – Sh-01…Sh-15  width =  8.71 chars / 2230 each (font 11, centered)
 *
 *  Header row height = 20pt,  font bold 11pt, blue bg, white text, centered
 *  Data   row height = 73pt,  all text/numbers centered
 */
public class ExcelExportService {

    private static final Logger LOG = Logger.getLogger(ExcelExportService.class.getName());

    // ── Column indices ──────────────────────────────────────────────────────
    private static final int COL_NO         = 0;
    private static final int COL_NAME       = 1;
    private static final int COL_MADE_IN    = 2;
    private static final int COL_CODE       = 3;
    private static final int COL_DESC       = 4;
    private static final int COL_IMAGE      = 5;
    private static final int COL_SHOP_FIRST = 6;   // Sh-01 … Sh-15

    // ── Row heights (POI unit: 1pt = 20 twips) ─────────────────────────────
    private static final short HEADER_ROW_HEIGHT = (short) (20 * 20);   // 20 pt
    private static final short DATA_ROW_HEIGHT   = (short) (73 * 20);   // 73 pt

    // ── Column widths (POI unit: 1 char = 256) ──────────────────────────────
    private static final int W_NO    =  950;
    private static final int W_NAME  = 6326;
    private static final int W_MADE  = 2230;
    private static final int W_CODE  = 2486;
    private static final int W_DESC  = 2999;
    private static final int W_IMAGE = 3254;
    private static final int W_SHOP  = 2230;

    // ────────────────────────────────────────────────────────────────────────

    /**
     * Creates a JavaFX Task for background export (main export).
     */
    /**
     * Synchronously exports products to a file.
     * Safe to call from any thread (no JavaFX Task involved).
     */
    public void exportToFile(List<Product> products, File outputFile,
                             ImageService imageService) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Products");

            CellStyle headerStyle  = createHeaderStyle(wb);
            CellStyle dataStyle    = createDataStyle(wb);
            CellStyle nameStyle    = createProductNameStyle(wb);
            CellStyle madeInStyle  = createMadeInStyle(wb);
            CellStyle noStyle      = createNoStyle(wb);

            applyColumnWidths(sheet);

            Row headerRow = sheet.createRow(0);
            headerRow.setHeight(HEADER_ROW_HEIGHT);
            String[] headers = buildHeaders();
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            XSSFDrawing drawing = sheet.createDrawingPatriarch();
            for (int r = 0; r < products.size(); r++) {
                Product p = products.get(r);
                Row row = sheet.createRow(r + 1);
                row.setHeight(DATA_ROW_HEIGHT);

                Cell noCell = row.createCell(COL_NO);
                noCell.setCellValue(r + 1);
                noCell.setCellStyle(noStyle);

                Cell nameCell = row.createCell(COL_NAME);
                nameCell.setCellValue(p.getName());
                nameCell.setCellStyle(nameStyle);

                Cell madeInCell = row.createCell(COL_MADE_IN);
                madeInCell.setCellValue(p.getMadeIn());
                madeInCell.setCellStyle(madeInStyle);

                Cell codeCell = row.createCell(COL_CODE);
                codeCell.setCellValue(p.getCode());
                codeCell.setCellStyle(dataStyle);

                Cell descCell = row.createCell(COL_DESC);
                descCell.setCellValue(p.getDescription());
                descCell.setCellStyle(dataStyle);

                Cell imgCell = row.createCell(COL_IMAGE);
                imgCell.setCellStyle(dataStyle);
                embedImage(wb, sheet, drawing, p, r + 1, imageService);

                for (int s = 0; s < Product.MAX_SHOP_CODES; s++) {
                    Cell shopCell = row.createCell(COL_SHOP_FIRST + s);
                    shopCell.setCellValue(p.getShopCode(s));
                    shopCell.setCellStyle(dataStyle);
                }
            }

            sheet.createFreezePane(0, 1);
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                wb.write(fos);
            }
        }
    }

    public Task<Void> createExportTask(List<Product> products, File outputFile,
                                       ImageService imageService) {
        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                updateMessage("Starting export...");
                exportToFile(products, outputFile, imageService);
                updateMessage("Export complete: " + products.size() + " products.");
                return null;
            }
        };
    }


    // ────────────────────────────────────────────────────────────────────────

    /**
     * Creates a JavaFX Task for background export by shop ID.
     */
    public Task<Void> createExportByShopTask(List<Product> products, String shopId,
                                              File outputFile, ImageService imageService) {
        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                updateMessage("Starting export by shop...");

                try (XSSFWorkbook wb = new XSSFWorkbook()) {

                    // ── Styles ───────────────────────────────────────────────
                    CellStyle headerStyle    = createHeaderStyle(wb);
                    CellStyle redHeaderStyle = createRedHeaderStyle(wb);
                    CellStyle dataStyle      = createDataStyle(wb);
                    CellStyle nameStyle      = createProductNameStyle(wb);
                    CellStyle madeInStyle    = createMadeInStyle(wb);
                    CellStyle noStyle        = createNoStyle(wb);

                    // ── TAB 1: Products by File ──────────────────────────────
                    XSSFSheet sheet1 = wb.createSheet("Products by File");
                    applyColumnWidths(sheet1);

                    Row totalRow = sheet1.createRow(0);
                    totalRow.setHeight(HEADER_ROW_HEIGHT);
                    Cell totalCell = totalRow.createCell(0);
                    totalCell.setCellValue("Total Products: " + products.size());
                    totalCell.setCellStyle(headerStyle);

                    int rowIndex = 2; // leave one blank row after total

                    XSSFDrawing drawing = sheet1.createDrawingPatriarch();

                    java.util.Map<String, List<Product>> grouped = products.stream()
                            .collect(java.util.stream.Collectors.groupingBy(
                                    pr -> {
                                        String sf = pr.getSourceFile();
                                        if (sf == null) return "Unknown File";
                                        // Use only the base name (strip " [SheetName]" suffix)
                                        int bracket = sf.indexOf(" [");
                                        return bracket >= 0 ? sf.substring(0, bracket) : sf;
                                    }
                            ));

                    List<String> sortedFiles = new java.util.ArrayList<>(grouped.keySet());
                    java.util.Collections.sort(sortedFiles);

                    int productCounter = 0;

                    for (String fileName : sortedFiles) {
                        List<Product> groupProducts = grouped.get(fileName);

                        // Group header row (merged across all columns)
                        Row groupHeaderRow = sheet1.createRow(rowIndex++);
                        groupHeaderRow.setHeight(HEADER_ROW_HEIGHT);
                        Cell groupCell = groupHeaderRow.createCell(0);
                        groupCell.setCellValue(fileName + " - Total: " + groupProducts.size());
                        groupCell.setCellStyle(redHeaderStyle);
                        int lastCol = COL_SHOP_FIRST + Product.MAX_SHOP_CODES - 1;
                        sheet1.addMergedRegion(new CellRangeAddress(
                                rowIndex - 1, rowIndex - 1, 0, lastCol));

                        // Column header row for this group
                        Row colHeaderRow = sheet1.createRow(rowIndex++);
                        colHeaderRow.setHeight(HEADER_ROW_HEIGHT);
                        String[] cols = buildHeaders();
                        for (int i = 0; i < cols.length; i++) {
                            Cell cell = colHeaderRow.createCell(i);
                            cell.setCellValue(cols[i]);
                            cell.setCellStyle(headerStyle);
                        }

                        // Product rows
                        for (int gIdx = 0; gIdx < groupProducts.size(); gIdx++) {
                            if (isCancelled()) break;
                            Product p = groupProducts.get(gIdx);

                            Row row = sheet1.createRow(rowIndex++);
                            row.setHeight(DATA_ROW_HEIGHT);

                            Cell noCell = row.createCell(COL_NO);
                            noCell.setCellValue(gIdx + 1);
                            noCell.setCellStyle(noStyle);

                            Cell nameCell = row.createCell(COL_NAME);
                            nameCell.setCellValue(p.getName());
                            nameCell.setCellStyle(nameStyle);

                            Cell madeInCell = row.createCell(COL_MADE_IN);
                            madeInCell.setCellValue(p.getMadeIn());
                            madeInCell.setCellStyle(madeInStyle);

                            Cell codeCell = row.createCell(COL_CODE);
                            codeCell.setCellValue(p.getCode());
                            codeCell.setCellStyle(dataStyle);

                            Cell descCell = row.createCell(COL_DESC);
                            descCell.setCellValue(p.getDescription());
                            descCell.setCellStyle(dataStyle);

                            Cell imgCell = row.createCell(COL_IMAGE);
                            imgCell.setCellStyle(dataStyle);
                            embedImage(wb, sheet1, drawing, p, rowIndex - 1, imageService);

                            for (int s = 0; s < Product.MAX_SHOP_CODES; s++) {
                                Cell shopCell = row.createCell(COL_SHOP_FIRST + s);
                                shopCell.setCellValue(p.getShopCode(s));
                                shopCell.setCellStyle(dataStyle);
                            }

                            productCounter++;
                            updateProgress(productCounter, products.size());
                            updateMessage("Exporting: " + productCounter + "/" + products.size());
                        }

                        rowIndex++; // blank row between groups
                    }

                    sheet1.createFreezePane(0, 1);

                    // ── TAB 2: Shop Codes list ───────────────────────────────
                    String sheetName = shopId != null ? "Shop Codes-" + shopId : "Shop Codes";
                    XSSFSheet sheet2 = wb.createSheet(sheetName);
                    sheet2.setColumnWidth(0, W_CODE);

                    Row s2Header = sheet2.createRow(0);
                    s2Header.setHeight(HEADER_ROW_HEIGHT);
                    Cell s2HeaderCell = s2Header.createCell(0);
                    s2HeaderCell.setCellValue("Code");
                    s2HeaderCell.setCellStyle(headerStyle);

                    for (int i = 0; i < products.size(); i++) {
                        Row r = sheet2.createRow(i + 1);
                        Cell c = r.createCell(0);
                        c.setCellValue(products.get(i).getCode());
                        c.setCellStyle(dataStyle);
                    }

                    // Write main file
                    updateMessage("Saving file...");
                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        wb.write(fos);
                    }

                    // Write secondary codes-only file
                    try (XSSFWorkbook codesWb = new XSSFWorkbook()) {
                        XSSFSheet codesSheet = codesWb.createSheet(sheetName);
                        codesSheet.setColumnWidth(0, W_CODE);
                        CellStyle cHeaderStyle = createHeaderStyle(codesWb);
                        CellStyle cDataStyle   = createDataStyle(codesWb);

                        Row cHeader = codesSheet.createRow(0);
                        cHeader.setHeight(HEADER_ROW_HEIGHT);
                        Cell cHeaderCell = cHeader.createCell(0);
                        cHeaderCell.setCellValue("Code");
                        cHeaderCell.setCellStyle(cHeaderStyle);

                        for (int i = 0; i < products.size(); i++) {
                            Row r = codesSheet.createRow(i + 1);
                            Cell c = r.createCell(0);
                            c.setCellValue(products.get(i).getCode());
                            c.setCellStyle(cDataStyle);
                        }

                        updateMessage("Saving codes file...");
                        String originalPath = outputFile.getAbsolutePath();
                        int dotIndex = originalPath.lastIndexOf('.');
                        String codesFilePath = dotIndex > 0
                                ? originalPath.substring(0, dotIndex) + "_Codes" + originalPath.substring(dotIndex)
                                : originalPath + "_Codes";
                        try (FileOutputStream fos2 = new FileOutputStream(new File(codesFilePath))) {
                            codesWb.write(fos2);
                        }
                    }
                }

                updateMessage("Export by shop complete: " + products.size() + " products.");
                return null;
            }
        };
    }

    // ── Helper: headers array ────────────────────────────────────────────────

    private String[] buildHeaders() {
        // NO | Product Name | Made In | Code | Description | Image | Sh-01 … Sh-15
        String[] h = new String[COL_SHOP_FIRST + Product.MAX_SHOP_CODES];
        h[COL_NO]      = "NO";
        h[COL_NAME]    = "Product Name";
        h[COL_MADE_IN] = "Made In";
        h[COL_CODE]    = "Code";
        h[COL_DESC]    = "Description";
        h[COL_IMAGE]   = "Image";
        for (int i = 0; i < Product.MAX_SHOP_CODES; i++) {
            h[COL_SHOP_FIRST + i] = "Sh-" + String.format("%02d", i + 1);
        }
        return h;
    }

    // ── Helper: apply all column widths ─────────────────────────────────────

    private void applyColumnWidths(XSSFSheet sheet) {
        sheet.setColumnWidth(COL_NO,      W_NO);
        sheet.setColumnWidth(COL_NAME,    W_NAME);
        sheet.setColumnWidth(COL_MADE_IN, W_MADE);
        sheet.setColumnWidth(COL_CODE,    W_CODE);
        sheet.setColumnWidth(COL_DESC,    W_DESC);
        sheet.setColumnWidth(COL_IMAGE,   W_IMAGE);
        for (int i = 0; i < Product.MAX_SHOP_CODES; i++) {
            sheet.setColumnWidth(COL_SHOP_FIRST + i, W_SHOP);
        }
    }

    // ── Helper: fit the image inside the cell without exceeding cell bounds ──

    static int[] calculateImageFit(int cellWidth, int cellHeight, int imageWidth, int imageHeight, int padding) {
        int availableWidth = Math.max(0, cellWidth - (padding * 2));
        int availableHeight = Math.max(0, cellHeight - (padding * 2));
        if (imageWidth <= 0 || imageHeight <= 0 || availableWidth <= 0 || availableHeight <= 0) {
            return new int[]{0, 0, 0, 0};
        }

        double scale = Math.min((double) availableWidth / imageWidth,
                                (double) availableHeight / imageHeight);
        int fittedWidth = Math.max(1, (int) Math.round(imageWidth * scale));
        int fittedHeight = Math.max(1, (int) Math.round(imageHeight * scale));
        int offsetX = (cellWidth - fittedWidth) / 2;
        int offsetY = (cellHeight - fittedHeight) / 2;
        return new int[]{fittedWidth, fittedHeight, offsetX, offsetY};
    }

    private void embedImage(XSSFWorkbook wb, XSSFSheet sheet,
                            XSSFDrawing drawing, Product product,
                            int rowIndex, ImageService imageService) {
        if (imageService == null || product.getImagePath() == null) return;
        try {
            byte[] imageBytes = imageService.getImageBytes(product.getCode());
            if (imageBytes == null) return;

            String ext = imageService.getImageExtension(product.getCode());
            int pictureType;
            if ("png".equals(ext))                         pictureType = Workbook.PICTURE_TYPE_PNG;
            else if ("jpg".equals(ext) || "jpeg".equals(ext)) pictureType = Workbook.PICTURE_TYPE_JPEG;
            else return;

            int pictureIdx = wb.addPicture(imageBytes, pictureType);

            Row row = sheet.getRow(rowIndex);
            if (row == null) return;

            BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
                return;
            }

            int cellWidth  = Math.round(sheet.getColumnWidthInPixels(COL_IMAGE) * Units.EMU_PER_PIXEL);
            int cellHeight = Math.round(row.getHeightInPoints() * Units.EMU_PER_POINT);
            int inset = Math.max(1, Units.EMU_PER_POINT * 3);

            int sourceWidth = Units.toEMU(source.getWidth());
            int sourceHeight = Units.toEMU(source.getHeight());
            int[] fit = calculateImageFit(cellWidth, cellHeight, sourceWidth, sourceHeight, inset);
            int imageWidth = fit[0];
            int imageHeight = fit[1];
            int imageX = fit[2];
            int imageY = fit[3];

            XSSFClientAnchor imageAnchor = wb.getCreationHelper().createClientAnchor();
            imageAnchor.setCol1(COL_IMAGE);
            imageAnchor.setRow1(rowIndex);
            imageAnchor.setCol2(COL_IMAGE + 1);
            imageAnchor.setRow2(rowIndex + 1);
            imageAnchor.setDx1(imageX);
            imageAnchor.setDy1(imageY);
            imageAnchor.setDx2(-(cellWidth - imageX - imageWidth));
            imageAnchor.setDy2(-(cellHeight - imageY - imageHeight));
            imageAnchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);
            drawing.createPicture(imageAnchor, pictureIdx);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to embed image for: " + product.getCode(), e);
        }
    }

    // ── Style factories ──────────────────────────────────────────────────────

    /** Header row: blue bg, white bold 11pt, centered, thin borders. */
    private CellStyle createHeaderStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(
                new XSSFColor(new byte[]{(byte) 30, (byte) 136, (byte) 229}, null)); // #1E88E5
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        applyThinBorders(style);
        return style;
    }

    /** Data cells: centered, 11pt, wrap text, thin borders. */
    private CellStyle createDataStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setFontHeightInPoints((short) 11);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        applyThinBorders(style);
        return style;
    }

    /** Product Name cells: bold 12pt, centered, wrap, thin borders. */
    private CellStyle createProductNameStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        applyThinBorders(style);
        return style;
    }

    /** Made In cells: bold 12pt, centered, wrap, thin borders. */
    private CellStyle createMadeInStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        applyThinBorders(style);
        return style;
    }

    /** No. (row-number) cells: 11pt, centered, thin borders. */
    private CellStyle createNoStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setFontHeightInPoints((short) 11);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        applyThinBorders(style);
        return style;
    }

    /** Red group-header (export-by-shop): red bg, white bold 11pt, centered. */
    private CellStyle createRedHeaderStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(
                new XSSFColor(new byte[]{(byte) 229, (byte) 57, (byte) 53}, null)); // #E53935
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        applyThinBorders(style);
        return style;
    }

    private void applyThinBorders(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }
}
