#!/usr/bin/env python3
"""
Generates sample Excel files and placeholder images for testing the Products Catalog app.

Usage:
    python3 generate_sample_data.py

Output:
    ./excel/  — 3 Excel files with sample products
    ./images/ — PNG placeholder images named by product code
"""

import os
import struct
import zlib

# Sample product data organized by category/file
PRODUCTS = {
    "electronics.xlsx": [
        ("Wireless Mouse", "China", "ELEC001", "Ergonomic wireless mouse with USB receiver"),
        ("Mechanical Keyboard", "Taiwan", "ELEC002", "RGB mechanical keyboard with Cherry MX switches"),
        ("USB-C Hub", "China", "ELEC003", "7-in-1 USB-C hub with HDMI, USB 3.0, SD card reader"),
        ("Webcam HD", "South Korea", "ELEC004", "1080p HD webcam with built-in microphone"),
        ("Bluetooth Speaker", "China", "ELEC005", "Portable Bluetooth 5.0 speaker, waterproof"),
        ("Power Bank", "China", "ELEC006", "20000mAh portable charger with dual USB"),
        ("Smart Watch", "China", "ELEC007", "Fitness tracker with heart rate monitor"),
        ("Earbuds Pro", "Japan", "ELEC008", "True wireless earbuds with ANC"),
        ("Phone Stand", "China", "ELEC009", "Adjustable aluminum phone/tablet stand"),
        ("LED Desk Lamp", "China", "ELEC010", "Dimmable LED desk lamp with USB charging port"),
        ("HDMI Cable 2m", "China", "ELEC011", "High-speed HDMI 2.1 cable, 2 meters"),
        ("Wireless Charger", "South Korea", "ELEC012", "15W fast wireless charging pad"),
        ("USB Flash Drive 64GB", "Taiwan", "ELEC013", "USB 3.0 flash drive, 64GB capacity"),
        ("Screen Protector", "Japan", "ELEC014", "Tempered glass screen protector, anti-glare"),
        ("Car Phone Mount", "China", "ELEC015", "Magnetic car phone mount for dashboard"),
    ],
    "furniture.xlsx": [
        ("Office Chair", "Germany", "FURN001", "Ergonomic office chair with lumbar support"),
        ("Standing Desk", "USA", "FURN002", "Electric height-adjustable standing desk, 120x60cm"),
        ("Bookshelf", "Sweden", "FURN003", "5-tier wooden bookshelf, 80x180cm"),
        ("Filing Cabinet", "Italy", "FURN004", "3-drawer metal filing cabinet with lock"),
        ("Monitor Arm", "Taiwan", "FURN005", "Dual monitor arm, fits 13-32 inch screens"),
        ("Desk Mat", "China", "FURN006", "Large PU leather desk mat, 80x40cm"),
        ("Cable Tray", "Germany", "FURN007", "Under-desk cable management tray"),
        ("Footrest", "China", "FURN008", "Ergonomic tilting footrest with massage surface"),
        ("Whiteboard", "USA", "FURN009", "Magnetic dry-erase whiteboard, 90x60cm"),
        ("Coat Rack", "Sweden", "FURN010", "Free-standing wooden coat rack, 8 hooks"),
    ],
    "stationery.xlsx": [
        ("Notebook A5", "Japan", "STAT001", "Premium A5 lined notebook, 200 pages"),
        ("Gel Pen Set", "Japan", "STAT002", "Set of 10 gel pens, assorted colors"),
        ("Sticky Notes", "USA", "STAT003", "Sticky notes pack, 6 pads, 100 sheets each"),
        ("Stapler", "Germany", "STAT004", "Heavy-duty stapler, 40 sheet capacity"),
        ("Tape Dispenser", "China", "STAT005", "Desktop tape dispenser with non-slip base"),
        ("Scissors", "Germany", "STAT006", "Stainless steel office scissors, 20cm"),
        ("Paper Clips Box", "China", "STAT007", "Box of 200 paper clips, assorted sizes"),
        ("Highlighter Set", "Japan", "STAT008", "Set of 6 pastel highlighters"),
        ("Binder Clips", "China", "STAT009", "Binder clips, 48 pieces, medium size"),
        ("Correction Tape", "Japan", "STAT010", "Correction tape, 5mm x 12m, pack of 3"),
        ("Pencil Case", "China", "STAT011", "Canvas pencil case with zipper"),
        ("Ruler 30cm", "Germany", "STAT012", "Clear plastic ruler, 30cm with metric markings"),
        ("Glue Stick", "USA", "STAT013", "Non-toxic glue stick, 40g, pack of 4"),
        ("Index Cards", "USA", "STAT014", "Ruled index cards, 100 pack, 3x5 inches"),
        ("Desk Organizer", "China", "STAT015", "Mesh metal desk organizer, 5 compartments"),
    ],
}


def create_minimal_png(filepath, width=100, height=100, color=(200, 210, 230)):
    """Creates a minimal valid PNG file with a solid color."""
    def chunk(chunk_type, data):
        c = chunk_type + data
        crc = struct.pack('>I', zlib.crc32(c) & 0xFFFFFFFF)
        return struct.pack('>I', len(data)) + c + crc

    signature = b'\x89PNG\r\n\x1a\n'
    ihdr_data = struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)
    ihdr = chunk(b'IHDR', ihdr_data)

    raw_data = b''
    for y in range(height):
        raw_data += b'\x00'  # filter byte
        for x in range(width):
            raw_data += bytes(color)

    compressed = zlib.compress(raw_data)
    idat = chunk(b'IDAT', compressed)
    iend = chunk(b'IEND', b'')

    with open(filepath, 'wb') as f:
        f.write(signature + ihdr + idat + iend)


def create_excel_file(filepath, products):
    """Creates a simple .xlsx file using openpyxl if available, else a basic XML approach."""
    try:
        import openpyxl
        wb = openpyxl.Workbook()
        ws = wb.active
        ws.append(["Product Name", "Made In", "Code", "Description", "Extra Column"])
        for name, made_in, code, desc in products:
            ws.append([name, made_in, code, desc, "ignored data"])
        wb.save(filepath)
        print(f"  Created: {filepath}")
    except ImportError:
        # Fallback: create a minimal xlsx (ZIP with XML)
        import zipfile
        from io import BytesIO

        content_types = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
</Types>'''

        rels = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>'''

        wb_rels = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
</Relationships>'''

        workbook_xml = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets>
</workbook>'''

        # Build shared strings and sheet data
        strings = ["Product Name", "Made In", "Code", "Description", "Extra Column"]
        for name, made_in, code, desc in products:
            strings.extend([name, made_in, code, desc, "ignored data"])

        ss_items = ''.join(f'<si><t>{s}</t></si>' for s in strings)
        shared_strings = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="{len(strings)}" uniqueCount="{len(strings)}">
{ss_items}
</sst>'''

        # Build sheet rows
        rows_xml = ''
        # Header row
        cols = 'ABCDE'
        for ci, col in enumerate(cols):
            rows_xml += f'<c r="{col}1" t="s"><v>{ci}</v></c>'
        rows_xml = f'<row r="1">{rows_xml}</row>'

        for ri, (name, made_in, code, desc) in enumerate(products):
            row_num = ri + 2
            si_base = 5 + ri * 5
            cells = ''
            for ci, col in enumerate(cols):
                cells += f'<c r="{col}{row_num}" t="s"><v>{si_base + ci}</v></c>'
            rows_xml += f'<row r="{row_num}">{cells}</row>'

        sheet_xml = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>{rows_xml}</sheetData>
</worksheet>'''

        buf = BytesIO()
        with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as zf:
            zf.writestr('[Content_Types].xml', content_types)
            zf.writestr('_rels/.rels', rels)
            zf.writestr('xl/_rels/workbook.xml.rels', wb_rels)
            zf.writestr('xl/workbook.xml', workbook_xml)
            zf.writestr('xl/sharedStrings.xml', shared_strings)
            zf.writestr('xl/worksheets/sheet1.xml', sheet_xml)

        with open(filepath, 'wb') as f:
            f.write(buf.getvalue())
        print(f"  Created (fallback): {filepath}")


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    excel_dir = os.path.join(script_dir, "excel")
    images_dir = os.path.join(script_dir, "images")

    os.makedirs(excel_dir, exist_ok=True)
    os.makedirs(images_dir, exist_ok=True)

    print("Generating sample Excel files...")
    all_codes = []
    for filename, products in PRODUCTS.items():
        filepath = os.path.join(excel_dir, filename)
        create_excel_file(filepath, products)
        all_codes.extend([p[2] for p in products])

    print(f"\nGenerating {len(all_codes)} placeholder images...")
    colors = {
        'ELEC': (66, 133, 244),   # Blue
        'FURN': (52, 168, 83),    # Green
        'STAT': (251, 188, 4),    # Yellow
    }
    for code in all_codes:
        prefix = code[:4]
        color = colors.get(prefix, (200, 200, 200))
        img_path = os.path.join(images_dir, f"{code}.png")
        create_minimal_png(img_path, 100, 100, color)

    print(f"\n✅ Sample data generated!")
    print(f"   Excel files: {excel_dir}/ ({len(PRODUCTS)} files)")
    print(f"   Images:      {images_dir}/ ({len(all_codes)} images)")
    print(f"\nTo use: In the app, click 'Import Data', select '{excel_dir}' for Excel")
    print(f"        and '{images_dir}' for images.")


if __name__ == "__main__":
    main()
