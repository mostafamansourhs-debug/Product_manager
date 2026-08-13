package com.productcatalog.model;

import javafx.beans.property.*;

/**
 * Product data model with JavaFX observable properties for reactive UI binding.
 * Each product has up to 20 shop-code slots representing which shops sell it.
 */
public class Product {

    public static final int MAX_SHOP_CODES = 40;

    private int id;
    private final StringProperty name;
    private final StringProperty madeIn;
    private final StringProperty code;
    private final StringProperty description;
    private final StringProperty imagePath;
    private final StringProperty sku;
    private final StringProperty[] shopCodes;
    private final BooleanProperty selected;
    private String sourceFile; // which Excel file this came from

    public Product() {
        this("", "", "", "", null);
    }

    public Product(String name, String madeIn, String code, String description, String imagePath) {
        this.name = new SimpleStringProperty(name != null ? name : "");
        this.madeIn = new SimpleStringProperty(madeIn != null ? madeIn : "");
        this.code = new SimpleStringProperty(code != null ? code : "");
        this.sku = new SimpleStringProperty(java.util.UUID.randomUUID().toString());
        this.description = new SimpleStringProperty(description != null ? description : "");
        this.imagePath = new SimpleStringProperty(imagePath);
        this.selected = new SimpleBooleanProperty(false);
        this.shopCodes = new StringProperty[MAX_SHOP_CODES];
        for (int i = 0; i < MAX_SHOP_CODES; i++) {
            shopCodes[i] = new SimpleStringProperty("");
        }
    }

    // --- ID ---
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    // --- Name ---
    public String getName() { return name.get(); }
    public void setName(String value) { name.set(value != null ? value : ""); }
    public StringProperty nameProperty() { return name; }

    // --- Made In ---
    public String getMadeIn() { return madeIn.get(); }
    public void setMadeIn(String value) { madeIn.set(value != null ? value : ""); }
    public StringProperty madeInProperty() { return madeIn; }

    // --- Code ---
    public String getCode() { return code.get(); }
    public void setCode(String value) { code.set(value != null ? value : ""); }
    public StringProperty codeProperty() { return code; }

    // --- SKU ---
    public String getSku() { return sku.get(); }
    public void setSku(String value) { sku.set(value != null ? value : ""); }
    public StringProperty skuProperty() { return sku; }

    // --- Description ---
    public String getDescription() { return description.get(); }
    public void setDescription(String value) { description.set(value != null ? value : ""); }
    public StringProperty descriptionProperty() { return description; }

    // --- Image Path ---
    public String getImagePath() { return imagePath.get(); }
    public void setImagePath(String value) { imagePath.set(value); }
    public StringProperty imagePathProperty() { return imagePath; }

    // --- Selected ---
    public boolean isSelected() { return selected.get(); }
    public void setSelected(boolean value) { selected.set(value); }
    public BooleanProperty selectedProperty() { return selected; }

    // --- Shop Codes ---
    public String getShopCode(int index) {
        if (index < 0 || index >= MAX_SHOP_CODES) return "";
        return shopCodes[index].get();
    }

    public void setShopCode(int index, String value) {
        if (index >= 0 && index < MAX_SHOP_CODES) {
            shopCodes[index].set(value != null ? value.trim() : "");
        }
    }

    public StringProperty shopCodeProperty(int index) {
        if (index < 0 || index >= MAX_SHOP_CODES) return new SimpleStringProperty("");
        return shopCodes[index];
    }

    public String[] getAllShopCodes() {
        String[] codes = new String[MAX_SHOP_CODES];
        for (int i = 0; i < MAX_SHOP_CODES; i++) {
            codes[i] = shopCodes[i].get();
        }
        return codes;
    }

    public void setAllShopCodes(String[] codes) {
        if (codes == null) return;
        for (int i = 0; i < Math.min(codes.length, MAX_SHOP_CODES); i++) {
            shopCodes[i].set(codes[i] != null ? codes[i].trim() : "");
        }
    }

    /**
     * Returns a comma-separated string of non-empty shop codes for display.
     */
    public String getShopCodesSummary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_SHOP_CODES; i++) {
            String sc = shopCodes[i].get();
            if (sc != null && !sc.trim().isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(sc.trim());
            }
        }
        return sb.toString();
    }

    /**
     * Returns the count of non-empty shop codes.
     */
    public int getShopCodeCount() {
        int count = 0;
        for (int i = 0; i < MAX_SHOP_CODES; i++) {
            String sc = shopCodes[i].get();
            if (sc != null && !sc.trim().isEmpty()) count++;
        }
        return count;
    }

    /**
     * Checks if this product is sold by the given shop code (case-insensitive).
     */
    public boolean hasShopCode(String shopCode) {
        if (shopCode == null || shopCode.trim().isEmpty()) return false;
        String target = shopCode.trim().toLowerCase();
        for (int i = 0; i < MAX_SHOP_CODES; i++) {
            String sc = shopCodes[i].get();
            if (sc != null && sc.trim().toLowerCase().equals(target)) return true;
        }
        return false;
    }

    // --- Source File ---
    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }

    @Override
    public String toString() {
        return "Product{code='" + getCode() + "', name='" + getName() + "'}";
    }
}
