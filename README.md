# Products Catalog — Shop Discovery Desktop App

A cross-platform JavaFX desktop application for managing product catalogs imported from Excel files, with image matching, shop code assignment, and export functionality.

## Features

- **Excel Import**: Import products from multiple `.xlsx`/`.xls` files in a folder
- **Import with Pic**: Re-import previously exported files and extract their embedded images automatically
- **Image Matching**: Automatically match product images by code (filename = product code)
- **Image Viewer**: Double-click any thumbnail (table or detail panel) to open a large image window
- **Image Management**: Add, replace, or remove a product's image from its table row
- **Shop Code Management**: Assign up to 15 shop codes per product via inline editing
- **Shop Discovery**: Browse products by shop code with a searchable dropdown
- **Excel Export**: Export selected products to `.xlsx` with embedded images
- **Persistent Storage**: SQLite database stores products and shop code assignments between sessions
- **Pagination**: Handle thousands of products efficiently
- **Search & Filter**: Filter by product name, code, made-in, or shop code

## Prerequisites

- **Java 17+** (JDK, not just JRE)
- **Maven 3.8+**

## Quick Start

```bash
# Clone or download the project
cd ProductsCatalog

# Run the application
mvn javafx:run
```

## Building

```bash
# Compile
mvn clean compile

# Package as JAR
mvn clean package
```

## Project Structure

```
src/main/java/com/productcatalog/
├── App.java                          # Main application entry point
├── model/
│   └── Product.java                  # Product data model with JavaFX properties
├── dao/
│   ├── DatabaseManager.java          # SQLite connection & schema management
│   └── ProductDAO.java               # Product CRUD operations
├── service/
│   ├── ExcelImportService.java       # Background Excel import with column matching
│   ├── ExcelExportService.java       # Background Excel export with embedded images
│   └── ImageService.java             # Image scanning, caching, and thumbnail generation
└── controller/
    ├── CatalogController.java        # Product Catalog tab UI & logic
    └── ShopDiscoveryController.java  # Shop Discovery tab UI & logic

src/main/resources/
└── styles/
    └── app.css                       # Blue & white themed stylesheet
```

## Excel File Requirements

- **Header row**: First row must contain column headers
- **Required columns** (matched case-insensitively, any order):
  - `Product Name` — the product's display name
  - `Made In` — country/region of manufacture
  - `Code` — unique product identifier
  - `Description` — product description text
- **Extra columns** are silently ignored
- **Duplicate codes**: If the same code appears in multiple files, the first occurrence is kept and a warning is logged

## Image Matching

- Place product images in a dedicated folder
- Each image filename (without extension) must match a product's `Code`
- Example: product code `A1023` matches `A1023.jpg`, `a1023.PNG`, etc.
- Supported formats: `.jpg`, `.jpeg`, `.png`, `.bmp`
- Matching is **case-insensitive**

## Import with Pic

Use **Import with Pic** to re-import files produced by **Export** or **Export by Shop**:

- Embedded product images are extracted from the Excel drawing layer and saved to the
  app-data `images/` folder (`~/.productscatalog/images` on Linux).
- Images are matched to rows, so no separate image folder is required.
- Existing product codes are updated with the file's data and image.
- Both layout styles are supported, including grouped "Products by File" sheets.
- Legacy shop headers (`shop 1`, `shop 01`) and current headers (`sh-01`) are accepted;
  only the first 15 shop columns are imported.
- Images are ignored when the file uses a header other than `Image` for the picture column.

## Image Viewer

Double-click a product thumbnail in the table (or the image in the detail panel) to open a
large, resizable image window. The **View Image** button in the detail panel does the same.

## Changing a Product Image

Use the small **✎** button in the bottom-right corner of a product's thumbnail:

- **Choose Image…** copies the selected file (PNG, JPG, JPEG, BMP) into the app-data
  `images/` folder, updates the product, and refreshes its thumbnail.
- **Remove Image** clears the product's image and shows the placeholder again. It is
  disabled for products that have no image.
- Removing or replacing an image deletes the old copied file from the app-data folder;
  external files are never deleted.

Changes are saved to the database immediately and are reflected in future exports.

## Sample Data

Generate test data for demo purposes:

```bash
cd sample-data
python3 generate_sample_data.py
```

This creates:
- `sample-data/excel/` — 3 Excel files with sample products
- `sample-data/images/` — Placeholder PNG images for each product

## Data Persistence

The app uses an embedded SQLite database stored in:
- **Linux**: `~/.productscatalog/productcatalog.db`
- **macOS**: `~/Library/Application Support/ProductsCatalog/productcatalog.db`
- **Windows**: `%APPDATA%/ProductsCatalog/productcatalog.db`

Product data and shop code assignments persist between application launches.

## Technology Stack

- **JavaFX 21** — UI framework
- **Apache POI 5.2.5** — Excel reading/writing with image embedding
- **SQLite** (via sqlite-jdbc) — Local persistence
- **ControlsFX 11.2.0** — Enhanced UI controls
- **Maven** — Build and dependency management

## License

This project is provided as-is for internal use.
