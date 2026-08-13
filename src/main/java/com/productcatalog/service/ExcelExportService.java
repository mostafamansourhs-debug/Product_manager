package com.productcatalog.service;

import com.productcatalog.model.Product;
import javafx.concurrent.Task;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.*;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background service that exports selected products to an Excel file
 * with embedded images.
 */
public class ExcelExportService {

    private static final Logger LOG = Logger.getLogger(ExcelExportService.class.getName());

    /**
     * Creates a JavaFX Task for background export.
     */
    public Task<Void> createExportTask(List<Product> products, File outputFile,
                                       ImageService imageService) {
        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                updateMessage("Starting export...");

                try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                    XSSFSheet sheet = workbook.createSheet("Products");

                    // Create header style
                    CellStyle headerStyle = createHeaderStyle(workbook);
                    CellStyle dataStyle = createDataStyle(workbook);

                    // Headers
                    String[] headers = new String[6 + Product.MAX_SHOP_CODES];
                    headers[0] = "Product Name";
                    headers[1] = "Made In";
                    headers[2] = "Code";
                    headers[3] = "SKU";
                    headers[4] = "Description";
                    headers[5] = "Image";
                    for (int i = 0; i < Product.MAX_SHOP_CODES; i++) {
                        headers[6 + i] = "Shop " + (i + 1);
                    }

                    Row headerRow = sheet.createRow(0);
                    headerRow.setHeight((short) (20 * 20)); // 20pt
                    for (int i = 0; i < headers.length; i++) {
                        Cell cell = headerRow.createCell(i);
                        cell.setCellValue(headers[i]);
                        cell.setCellStyle(headerStyle);
                    }

                    // Data rows
                    XSSFDrawing drawing = sheet.createDrawingPatriarch();
                    int imageColWidth = 80; // pixels for image column

                    for (int rowIdx = 0; rowIdx < products.size(); rowIdx++) {
                        if (isCancelled()) break;

                        Product product = products.get(rowIdx);
                        Row row = sheet.createRow(rowIdx + 1);
                        row.setHeight((short) (80 * 20)); // 80pt height for images

                        Cell nameCell = row.createCell(0);
                        nameCell.setCellValue(product.getName());
                        nameCell.setCellStyle(dataStyle);

                        Cell madeInCell = row.createCell(1);
                        madeInCell.setCellValue(product.getMadeIn());
                        madeInCell.setCellStyle(dataStyle);

                        Cell codeCell = row.createCell(2);
                        codeCell.setCellValue(product.getCode());
                        codeCell.setCellStyle(dataStyle);

                        Cell skuCell = row.createCell(3);
                        skuCell.setCellValue(product.getSku());
                        skuCell.setCellStyle(dataStyle);

                        Cell descCell = row.createCell(4);
                        descCell.setCellValue(product.getDescription());
                        descCell.setCellStyle(dataStyle);

                        // Embed image
                        embedImage(workbook, sheet, drawing, product, rowIdx + 1, imageService);

                        // Shop codes
                        for (int i = 0; i < Product.MAX_SHOP_CODES; i++) {
                            Cell shopCell = row.createCell(6 + i);
                            shopCell.setCellValue(product.getShopCode(i));
                            shopCell.setCellStyle(dataStyle);
                        }

                        updateProgress(rowIdx + 1, products.size());
                        updateMessage("Exporting: " + (rowIdx + 1) + "/" + products.size());
                    }

                    // Auto-size text columns
                    for (int i = 0; i < 5; i++) {
                        sheet.autoSizeColumn(i);
                    }
                    // Set image column width
                    sheet.setColumnWidth(5, imageColWidth * 40);

                    // Auto-size shop columns
                    for (int i = 6; i < 6 + Product.MAX_SHOP_CODES; i++) {
                        sheet.setColumnWidth(i, 12 * 256); // 12 chars wide
                    }

                    // Freeze header row
                    sheet.createFreezePane(0, 1);

                    // Write to file
                    updateMessage("Saving file...");
                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        workbook.write(fos);
                    }
                }

                updateMessage("Export complete: " + products.size() + " products.");
                return null;
            }
        };
    }

    /**
     * Embeds a product image into a cell using Apache POI.
     */
    private void embedImage(XSSFWorkbook workbook, XSSFSheet sheet,
                            XSSFDrawing drawing, Product product,
                            int rowIndex, ImageService imageService) {
        if (imageService == null || product.getImagePath() == null) return;

        try {
            byte[] imageBytes = imageService.getImageBytes(product.getCode());
            if (imageBytes == null) return;

            String ext = imageService.getImageExtension(product.getCode());
            int pictureType;
            if ("png".equals(ext)) {
                pictureType = Workbook.PICTURE_TYPE_PNG;
            } else if ("jpg".equals(ext) || "jpeg".equals(ext)) {
                pictureType = Workbook.PICTURE_TYPE_JPEG;
            } else {
                return; // BMP and other formats not directly supported by POI
            }

            int pictureIdx = workbook.addPicture(imageBytes, pictureType);

            // Create anchor — image fits in the cell at column 5
            XSSFClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
            anchor.setCol1(5);
            anchor.setRow1(rowIndex);
            anchor.setCol2(6);
            anchor.setRow2(rowIndex + 1);
            anchor.setDx1(Units.EMU_PER_POINT * 2);
            anchor.setDy1(Units.EMU_PER_POINT * 2);
            anchor.setDx2(-Units.EMU_PER_POINT * 2);
            anchor.setDy2(-Units.EMU_PER_POINT * 2);
            anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);

            drawing.createPicture(anchor, pictureIdx);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to embed image for: " + product.getCode(), e);
        }
    }

    private CellStyle createHeaderStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(new XSSFColor(
                new byte[]{(byte) 30, (byte) 136, (byte) 229}, null)); // #1E88E5
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createDataStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }
}
