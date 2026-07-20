# Products Catalog — Shop Discovery Desktop App

A cross-platform JavaFX desktop application for managing product catalogs imported from Excel files, with image matching, shop code assignment, and export functionality.

## Features

- **Excel Import**: Import products from multiple `.xlsx`/`.xls` files in a folder
- **Image Matching**: Automatically match product images by code (filename = product code)
- **Shop Code Management**: Assign up to 20 shop codes per product via inline editing
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
