package com.productcatalog.controller;

import com.productcatalog.dao.DatabaseManager;
import com.productcatalog.dao.ProductDAO;
import com.productcatalog.model.Product;
import com.productcatalog.service.ExcelExportService;
import com.productcatalog.service.ExcelImportService;
import com.productcatalog.service.ImageService;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Controller for the Product Catalog tab.
 * Manages the product table, search/filter, import/export, and shop-code detail
 * panel.
 */
public class CatalogController {

    private static final Logger LOG = Logger.getLogger(CatalogController.class.getName());
    private static final int PAGE_SIZE_DEFAULT = 50;

    private final BorderPane view;
    private final Label statusLabel;

    // Data
    private final ObservableList<Product> allProducts = FXCollections.observableArrayList();
    private FilteredList<Product> filteredProducts;
    private SortedList<Product> sortedProducts;
    private final ImageService imageService = new ImageService();
    private final ProductDAO productDAO = new ProductDAO();

    // UI Components
    private TabPane tabPane;
    private String activeSourceFile = null; // null = All Products
    // tracks which file tabs are already open (filename -> Tab)
    private final Map<String, Tab> openFileTabs = new LinkedHashMap<>();
    private TableView<Product> tableView;
    private TextField searchField;
    private ComboBox<String> searchFieldCombo;
    private ComboBox<String> shopCombo;
    private final ObservableList<String> allShopCodes = FXCollections.observableArrayList();
    private ComboBox<String> sortFieldCombo;
    private ComboBox<String> sortOrderCombo;
    private Button exportButton;
    private Button exportByShopButton;
    private CheckBox imagesOnlyCheckBox;
    private Label loadedCountLabel;
    private Label selectedCountLabel;
    private ProgressBar progressBar;
    private Label progressLabel;
    private CheckBox headerCheckBox;
    private VBox detailPanel;
    private Label detailTitle;
    private ImageView detailImage;
    private Button viewImageBtn;
    private TextField[] shopCodeFields;
    private Product currentDetailProduct;

    // Pagination
    private int currentPage = 0;
    private int pageSize = PAGE_SIZE_DEFAULT;
    private Label pageLabel;

    // Selection count
    private final IntegerProperty selectedCount = new SimpleIntegerProperty(0);

    public CatalogController(Label statusLabel) {
        this.statusLabel = statusLabel;
        this.view = new BorderPane();
        buildUI();
        loadFromDatabase();
    }

    public void selectShopCode(String shopCode) {
        if (shopCode != null && !shopCode.trim().isEmpty()) {
            shopCombo.setValue(shopCode.trim());
        }
    }

    public Node getView() {
        return view;
    }

    public ObservableList<Product> getAllProducts() {
        return allProducts;
    }

    public ImageService getImageService() {
        return imageService;
    }

    // ======================== UI BUILDING ========================

    private void buildUI() {
        // Top: Toolbar (wrapped in ScrollPane so it never clips on small windows)
        view.setTop(buildToolbar());

        // Center: TabPane (filter selector) + shared Table + Detail + Pagination
        VBox centerBox = new VBox();
        centerBox.getStyleClass().add("center-content");

        tabPane = buildTabPane();

        tableView = buildTable();
        VBox.setVgrow(tableView, Priority.ALWAYS);

        HBox paginationBar = buildPaginationBar();

        centerBox.getChildren().addAll(tabPane, tableView, paginationBar);
        view.setCenter(centerBox);

        // Bottom: Status bar with counts
        view.setBottom(buildStatusBar());
    }

    /** Builds the TabPane used as a file-tab navigation bar. */
    private TabPane buildTabPane() {
        TabPane tp = new TabPane();
        tp.getStyleClass().add("file-tab-pane");
        tp.setTabClosingPolicy(TabPane.TabClosingPolicy.SELECTED_TAB);
        // Don't constrain height — let the tab pane size itself based on font/DPI.
        // Hardcoded 32-36px was too tight on Windows with DPI scaling.

        Tab allTab = new Tab("📦 All Products");
        allTab.setClosable(false);
        // store marker
        allTab.setUserData(null);
        tp.getTabs().add(allTab);

        tp.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == null)
                return;
            activeSourceFile = (String) newTab.getUserData(); // null for All Products
            hideDetailPanel();
            currentPage = 0;
            if (filteredProducts != null) {
                applyFilter();
                syncHeaderCheckBox();
            }
        });

        return tp;
    }

    /**
     * Opens or focuses a file tab for the given base filename.
     * The tab header supports double-click to rename inline.
     * Call on the JavaFX thread.
     */
    private void openOrFocusFileTab(String filename) {
        // 1. Fast lookup — tab was opened with this exact name (or already renamed to it)
        if (openFileTabs.containsKey(filename)) {
            tabPane.getSelectionModel().select(openFileTabs.get(filename));
            return;
        }
        // 2. Slow scan — the tab's products came from this filename but the tab was renamed;
        //    find it by matching any tab whose userData starts with this filename.
        for (Tab existing : tabPane.getTabs()) {
            Object ud = existing.getUserData();
            if (ud == null) continue; // "All Products" tab
            String tabKey = (String) ud;
            // Match exact OR the tab key starts with filename prefix (sheet-suffix case)
            if (tabKey.equalsIgnoreCase(filename)
                    || tabKey.toLowerCase().startsWith(filename.toLowerCase() + " [")) {
                tabPane.getSelectionModel().select(existing);
                return;
            }
        }
        // 3. No existing tab — create a new one
        Tab tab = new Tab();
        tab.setClosable(true);
        tab.setUserData(filename);

        // ── Inline-rename header graphic ──────────────────────────────────
        Label nameLabel = new Label(filename);
        nameLabel.setStyle("-fx-text-fill: inherit;");

        TextField nameField = new TextField(filename);
        nameField.setPrefWidth(150);
        nameField.setStyle(
                "-fx-background-color: transparent;"
                + " -fx-border-color: #3b82f6;"
                + " -fx-border-width: 0 0 2 0;"
                + " -fx-padding: 0 2;");

        StackPane tabHeader = new StackPane(nameLabel);
        tabHeader.setAlignment(Pos.CENTER_LEFT);
        tab.setGraphic(tabHeader);

        // Double-click label → enter rename mode
        nameLabel.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                nameField.setText(nameLabel.getText());
                nameField.setPrefWidth(Math.max(80, nameLabel.getWidth() + 20));
                tabHeader.getChildren().setAll(nameField);
                nameField.requestFocus();
                nameField.selectAll();
                e.consume();
            }
        });

        Runnable commitRename = () -> {
            String newName = nameField.getText().trim();
            if (newName.isEmpty()) newName = nameLabel.getText();
            String oldKey = (String) tab.getUserData();
            if (!newName.equals(oldKey)) {
                final String finalNewName = newName;
                final String finalOldKey  = oldKey;

                // 1. Persist to DB (bulk rename source_file column)
                productDAO.updateSourceFile(finalOldKey, finalNewName);

                // 2. Update in-memory product objects
                if (allProducts != null) {
                    for (Product p : allProducts) {
                        String sf = p.getSourceFile();
                        if (sf == null) continue;
                        String base = sf.contains(" [") ? sf.substring(0, sf.indexOf(" [")) : sf;
                        if (base.equalsIgnoreCase(finalOldKey)) {
                            String suffix = sf.contains(" [") ? sf.substring(sf.indexOf(" [")) : "";
                            p.setSourceFile(finalNewName + suffix);
                        }
                    }
                }

                // 3. Keep the tab map and userData in sync
                openFileTabs.remove(finalOldKey);
                openFileTabs.put(finalNewName, tab);
                tab.setUserData(finalNewName);

                // 4. If this is the active tab, update activeSourceFile and re-filter
                if (finalOldKey.equals(activeSourceFile)) {
                    activeSourceFile = finalNewName;
                    applyFilter();
                }
            }
            nameLabel.setText(newName);
            tabHeader.getChildren().setAll(nameLabel);
        };


        nameField.setOnAction(e -> commitRename.run());
        nameField.focusedProperty().addListener((obs, was, isFocused) -> {
            if (!isFocused && tabHeader.getChildren().contains(nameField))
                commitRename.run();
        });
        nameField.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                tabHeader.getChildren().setAll(nameLabel); // revert
            }
        });

        tab.setOnClosed(e -> openFileTabs.remove(tab.getUserData()));
        openFileTabs.put(filename, tab);
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
    }


    private Node buildToolbar() {
        HBox toolbar = new HBox(10);
        toolbar.getStyleClass().add("toolbar");
        toolbar.setPadding(new Insets(8, 12, 8, 12));
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Button importButton = new Button("\uD83D\uDCC2 Import");
        importButton.getStyleClass().add("btn-primary");
        importButton.setOnAction(e -> handleImport(false));
        importButton.setMinSize(Button.USE_PREF_SIZE, Button.USE_PREF_SIZE);

        Button importWithPicButton = new Button("\uD83D\uDDBC Import with Pic");
        importWithPicButton.getStyleClass().add("btn-primary");
        importWithPicButton.setOnAction(e -> handleImport(true));
        importWithPicButton.setMinSize(Button.USE_PREF_SIZE, Button.USE_PREF_SIZE);

        imagesOnlyCheckBox = new CheckBox("Images only");
        imagesOnlyCheckBox.setTooltip(new Tooltip(
                "Only export products that have an image"));
        imagesOnlyCheckBox.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        exportButton = new Button("\uD83D\uDCE4 Export");
        exportButton.getStyleClass().add("btn-primary");
        exportButton.setOnAction(e -> handleExport());
        exportButton.disableProperty().bind(selectedCount.lessThanOrEqualTo(0));
        exportButton.setMinSize(Button.USE_PREF_SIZE, Button.USE_PREF_SIZE);

        exportByShopButton = new Button("\uD83D\uDCE4 Export by Shop");
        exportByShopButton.getStyleClass().add("btn-primary");
        exportByShopButton.setOnAction(e -> handleExportByShop());
        exportByShopButton.setMinSize(Button.USE_PREF_SIZE, Button.USE_PREF_SIZE);

        Button exportWithNameButton = new Button("\uD83D\uDCC2 Export with Name");
        exportWithNameButton.getStyleClass().add("btn-primary");
        exportWithNameButton.setOnAction(e -> handleExportWithName());
        exportWithNameButton.setMinSize(Button.USE_PREF_SIZE, Button.USE_PREF_SIZE);

        Button unselectButton = new Button("✕ Unselect");
        unselectButton.getStyleClass().add("btn-secondary");
        unselectButton.setOnAction(e -> handleUnselect());
        unselectButton.disableProperty().bind(selectedCount.lessThanOrEqualTo(0));
        unselectButton.setMinSize(Button.USE_PREF_SIZE, Button.USE_PREF_SIZE);

        Button deleteButton = new Button("\uD83D\uDDD1 Delete");
        deleteButton.getStyleClass().add("btn-danger");
        deleteButton.setOnAction(e -> handleDelete());
        deleteButton.disableProperty().bind(selectedCount.lessThanOrEqualTo(0));
        deleteButton.setMinSize(Button.USE_PREF_SIZE, Button.USE_PREF_SIZE);

        Separator sep = new Separator(Orientation.VERTICAL);
        sep.setMinWidth(Region.USE_PREF_SIZE);

        searchFieldCombo = new ComboBox<>(FXCollections.observableArrayList(
                "All Fields", "Product Name", "Code", "Made In", "Description"));
        searchFieldCombo.setValue("All Fields");
        searchFieldCombo.setOnAction(e -> applyFilter());
        searchFieldCombo.setMinWidth(Region.USE_PREF_SIZE);

        searchField = new TextField();
        searchField.setPromptText("Search...");
        searchField.setPrefWidth(200);
        searchField.setMinWidth(120);
        searchField.getStyleClass().add("search-field");
        searchField.textProperty().addListener((obs, old, val) -> applyFilter());

        Button clearSearch = new Button("✕");
        clearSearch.getStyleClass().add("btn-clear");
        clearSearch.setOnAction(e -> searchField.clear());
        clearSearch.setMinSize(Button.USE_PREF_SIZE, Button.USE_PREF_SIZE);

        Separator sep2 = new Separator(Orientation.VERTICAL);
        sep2.setMinWidth(Region.USE_PREF_SIZE);

        Label shopLabel = new Label("\uD83C\uDFEA Shop:");
        shopLabel.setMinWidth(Region.USE_PREF_SIZE);
        shopCombo = new ComboBox<>(allShopCodes);
        shopCombo.setPromptText("All Shops");
        shopCombo.setPrefWidth(120);
        shopCombo.setMinWidth(80);
        shopCombo.setOnAction(e -> applyFilter());

        Button clearShop = new Button("✕");
        clearShop.getStyleClass().add("btn-clear");
        clearShop.setOnAction(e -> shopCombo.setValue(null));
        clearShop.setMinSize(Button.USE_PREF_SIZE, Button.USE_PREF_SIZE);

        Separator sep3 = new Separator(Orientation.VERTICAL);
        sep3.setMinWidth(Region.USE_PREF_SIZE);

        Label sortLabel = new Label("↕ Sort:");
        sortLabel.setMinWidth(Region.USE_PREF_SIZE);
        sortFieldCombo = new ComboBox<>(FXCollections.observableArrayList(
                "None", "Product Name", "Code", "Made In"));
        sortFieldCombo.setValue("None");
        sortFieldCombo.setPrefWidth(120);
        sortFieldCombo.setMinWidth(80);
        sortFieldCombo.setOnAction(e -> applySort());

        sortOrderCombo = new ComboBox<>(FXCollections.observableArrayList(
                "ASC", "DESC"));
        sortOrderCombo.setValue("ASC");
        sortOrderCombo.setPrefWidth(85);
        sortOrderCombo.setMinWidth(60);
        sortOrderCombo.setOnAction(e -> applySort());

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(150);
        progressBar.setVisible(false);
        progressBar.managedProperty().bind(progressBar.visibleProperty());

        progressLabel = new Label();
        progressLabel.getStyleClass().add("progress-label");
        progressLabel.setVisible(false);
        progressLabel.managedProperty().bind(progressLabel.visibleProperty());

        exportByShopButton.disableProperty().bind(Bindings.createBooleanBinding(() -> {
            return selectedCount.get() <= 0 || shopCombo.getValue() == null || shopCombo.getValue().trim().isEmpty();
        }, selectedCount, shopCombo.valueProperty()));

        toolbar.getChildren().addAll(importButton, importWithPicButton, imagesOnlyCheckBox,
                exportButton, exportByShopButton, exportWithNameButton,
                unselectButton, deleteButton, sep,
                searchFieldCombo, searchField, clearSearch, sep2, shopLabel, shopCombo, clearShop, sep3, sortLabel,
                sortFieldCombo, sortOrderCombo, progressBar, progressLabel);

        // Wrap the toolbar in a ScrollPane so it scrolls horizontally
        // instead of clipping the right side (sort controls) on small windows.
        ScrollPane toolbarScroll = new ScrollPane(toolbar);
        toolbarScroll.setFitToHeight(true);
        toolbarScroll.setFitToWidth(true);
        toolbarScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        toolbarScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        toolbarScroll.getStyleClass().add("toolbar-scroll");
        toolbarScroll.setMinHeight(Region.USE_PREF_SIZE);

        return toolbarScroll;
    }

    @SuppressWarnings("unchecked")
    private TableView<Product> buildTable() {
        TableView<Product> table = new TableView<>();
        table.getStyleClass().add("product-table");
        table.setEditable(false);
        table.setPlaceholder(new Label("No products loaded. Click 'Import Data' to begin."));
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_NEXT_COLUMN);

        // Checkbox column
        TableColumn<Product, Boolean> checkCol = new TableColumn<>();
        checkCol.setPrefWidth(40);
        checkCol.setMaxWidth(45);
        checkCol.setMinWidth(35);
        checkCol.setSortable(false);

        // Header checkbox for select all (scoped to current tab's visible products)
        headerCheckBox = new CheckBox();
        headerCheckBox.setOnAction(e -> {
            boolean sel = headerCheckBox.isSelected();
            // Only select/deselect products visible in the current tab
            if (filteredProducts != null) {
                filteredProducts.forEach(p -> p.setSelected(sel));
            }
            tableView.refresh();
            updateSelectedCount();
        });
        checkCol.setGraphic(headerCheckBox);

        checkCol.setCellFactory(col -> new TableCell<>() {
            private final CheckBox cb = new CheckBox();
            {
                cb.setOnAction(e -> {
                    Product p = getTableRow().getItem();
                    if (p != null) {
                        p.setSelected(cb.isSelected());
                        updateSelectedCount();
                    }
                });
            }

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    cb.setSelected(getTableRow().getItem().isSelected());
                    setGraphic(cb);
                }
            }
        });
        checkCol.setCellValueFactory(data -> data.getValue().selectedProperty().asObject());

        // Image column
        TableColumn<Product, String> imageCol = new TableColumn<>("Image");
        imageCol.setPrefWidth(120);
        imageCol.setMaxWidth(120);
        imageCol.setMinWidth(120);
        imageCol.setSortable(false);
        imageCol.setCellValueFactory(data -> data.getValue().codeProperty());
        imageCol.setCellFactory(col -> new TableCell<>() {
            private final ImageView iv = new ImageView();
            private final Button editBtn = new Button();
            private final StackPane imageBox = new StackPane(iv, editBtn);
            {
                iv.setFitWidth(104);
                iv.setFitHeight(104);
                iv.setPreserveRatio(true);
                iv.setSmooth(true);
                iv.setPickOnBounds(true);

                editBtn.getStyleClass().add("image-edit-btn");
                editBtn.setFocusTraversable(false);
                editBtn.setOnAction(e -> {
                    Product product = getTableRow() != null ? getTableRow().getItem() : null;
                    if (product != null) handleChangeImage(product);
                });
                editBtn.setOnMouseClicked(e -> e.consume());
                StackPane.setAlignment(editBtn, Pos.BOTTOM_RIGHT);
                StackPane.setMargin(editBtn, new Insets(2));

                setCursor(Cursor.HAND);
                setOnMouseClicked(e -> {
                    if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                        Product product = getTableRow() != null ? getTableRow().getItem() : null;
                        if (product != null) showFullImagePopup(product);
                        e.consume();
                    }
                });
            }

            @Override
            protected void updateItem(String code, boolean empty) {
                super.updateItem(code, empty);
                if (empty || code == null || code.isEmpty()) {
                    setGraphic(null);
                } else {
                    boolean hasImage = imageService.hasImage(code);
                    iv.setImage(imageService.getThumbnail(code));
                    editBtn.setText("✎");
                    editBtn.setTooltip(new Tooltip(hasImage
                            ? "Change image for " + code
                            : "Add image for " + code));
                    setGraphic(imageBox);
                    setAlignment(Pos.CENTER);
                    setStyle("-fx-padding: 0;"); // Remove padding to allow image to be as big as the cell
                }
            }
        });

        // Text columns — name / madeIn / description are inline-editable with auto-save
        TableColumn<Product, String> nameCol   = createEditableTextColumn("Product Name", "name",        200);
        TableColumn<Product, String> madeInCol = createEditableTextColumn("Made In",      "madeIn",      100);
        TableColumn<Product, String> codeCol   = createTextColumn(        "Code",          "code",        100);
        // TableColumn<Product, String> skuCol = createTextColumn("SKU", "sku", 200);
        TableColumn<Product, String> descCol   = createEditableTextColumn("Description",  "description", 250);

        // Shops summary column with clickable codes
        TableColumn<Product, Product> shopsCol = new TableColumn<>("Shops");
        shopsCol.setPrefWidth(200);
        shopsCol.setMinWidth(computeHeaderMinWidth("Shops"));
        shopsCol.setSortable(false);
        shopsCol.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        shopsCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Product product, boolean empty) {
                super.updateItem(product, empty);
                if (empty || product == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                FlowPane flow = new FlowPane(3, 2);
                flow.setPadding(new Insets(2));
                flow.setAlignment(Pos.CENTER);
                for (int i = 0; i < Product.MAX_SHOP_CODES; i++) {
                    String sc = product.getShopCode(i);
                    if (sc != null && !sc.trim().isEmpty()) {
                        String code = sc.trim();
                        Hyperlink link = new Hyperlink(code);
                        link.getStyleClass().add("shop-link");
                        link.setOnAction(e -> {
                            selectShopCode(code);
                        });
                        flow.getChildren().add(link);
                    }
                }
                if (flow.getChildren().isEmpty()) {
                    setText("—");
                    setGraphic(null);
                    setStyle("-fx-text-fill: #999;");
                } else {
                    setText(null);
                    ScrollPane scrollPane = new ScrollPane(flow);
                    scrollPane.setFitToWidth(true);
                    scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
                    scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                    scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-padding: 0;");
                    scrollPane.setMaxHeight(90);
                    setGraphic(scrollPane);
                    setStyle("");
                }
            }
        });

        table.getColumns().addAll(checkCol, imageCol, nameCol, madeInCol, codeCol, descCol, shopsCol);



        return table;
    }

    /**
     * Measures the pixel width needed to display {@code title} in the column-header font
     * (bold 12 px, matching the CSS rule on .product-table .column-header .label),
     * then adds horizontal padding so the text always fits on one line.
     */
    private double computeHeaderMinWidth(String title) {
        javafx.scene.text.Text probe = new javafx.scene.text.Text(title);
        probe.setFont(javafx.scene.text.Font.font(
                "Segoe UI", javafx.scene.text.FontWeight.BOLD, 12));
        // 8px left + 8px right column-header padding + 8px for sort-arrow / border
        return Math.ceil(probe.getLayoutBounds().getWidth()) + 24;
    }

    private TableColumn<Product, String> createTextColumn(String title, String property, double width) {
        TableColumn<Product, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setMinWidth(computeHeaderMinWidth(title));
        switch (property) {
            case "name" -> col.setCellValueFactory(data -> data.getValue().nameProperty());
            case "madeIn" -> col.setCellValueFactory(data -> data.getValue().madeInProperty());
            case "code" -> col.setCellValueFactory(data -> data.getValue().codeProperty());
            // case "sku" -> col.setCellValueFactory(data -> data.getValue().skuProperty());
            case "description" -> col.setCellValueFactory(data -> data.getValue().descriptionProperty());
        }
        col.setCellFactory(c -> new TableCell<>() {
            private final TextField textField = new TextField();
            {
                textField.setEditable(false);
                textField.setMaxWidth(Double.MAX_VALUE);
                // Look like a label but allow text selection & copy
                textField.setStyle(
                        "-fx-background-color: transparent;"
                        + " -fx-background-insets: 0;"
                        + " -fx-border-color: transparent;"
                        + " -fx-border-width: 0;"
                        + " -fx-padding: 2 8;"
                        + " -fx-text-fill: #333;"
                        + " -fx-highlight-fill: #b3d4f5;"   // selection highlight color
                );
                textField.setAlignment(Pos.CENTER);
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setAlignment(Pos.CENTER);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(null);
                    textField.setText(item);
                    setGraphic(textField);
                }
            }
        });
        return col;
    }

    /**
     * Creates an inline-editable column for the given property.
     * A Label is shown at rest (correct color, centered, wraps text).
     * Clicking the cell swaps in a TextArea for editing.
     * Saves on focus-lost or Enter; reverts on Escape.
     */
    private TableColumn<Product, String> createEditableTextColumn(String title, String property, double width) {
        TableColumn<Product, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setMinWidth(computeHeaderMinWidth(title));
        switch (property) {
            case "name"        -> col.setCellValueFactory(data -> data.getValue().nameProperty());
            case "madeIn"      -> col.setCellValueFactory(data -> data.getValue().madeInProperty());
            case "description" -> col.setCellValueFactory(data -> data.getValue().descriptionProperty());
        }
        col.setCellFactory(c -> new TableCell<>() {
            private final Label     displayLabel = new Label();
            private final TextField editField    = new TextField();
            private String lastSaved = "";
            private boolean isEditing = false;

            private static final String FIELD_IDLE =
                    "-fx-background-color: transparent;"
                    + " -fx-background-insets: 0;"
                    + " -fx-padding: 2 8;"
                    + " -fx-text-fill: #333;"
                    + " -fx-border-color: transparent;"
                    + " -fx-border-width: 0;";
            private static final String FIELD_FOCUSED =
                    "-fx-background-color: transparent;"
                    + " -fx-background-insets: 0;"
                    + " -fx-padding: 2 8;"
                    + " -fx-text-fill: #333;"
                    + " -fx-border-color: #3b82f6;"
                    + " -fx-border-width: 0 0 2 0;";
            {
                // Display label — shown at rest, wraps text, centered
                displayLabel.setWrapText(true);
                displayLabel.setAlignment(Pos.CENTER);
                displayLabel.setMaxWidth(Double.MAX_VALUE);
                displayLabel.setMaxHeight(Double.MAX_VALUE);
                displayLabel.setStyle("-fx-text-fill: #333; -fx-padding: 2 8;");
                displayLabel.setOnMouseClicked(e -> startEditing());

                // TextField for editing — fixed height, never expands the row
                editField.setMaxWidth(Double.MAX_VALUE);
                editField.setStyle(FIELD_IDLE);

                editField.focusedProperty().addListener((obs, was, isFocused) -> {
                    editField.setStyle(isFocused ? FIELD_FOCUSED : FIELD_IDLE);
                    if (!isFocused && isEditing) finishEdit(true);
                });
                editField.setOnAction(e -> finishEdit(true));
                editField.setOnKeyPressed(e -> {
                    if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                        e.consume();
                        finishEdit(false);
                    }
                });
            }

            private void startEditing() {
                isEditing = true;
                editField.setText(lastSaved);
                editField.setStyle(FIELD_FOCUSED);
                setGraphic(editField);
                editField.requestFocus();
                editField.selectAll();
            }

            private void finishEdit(boolean save) {
                isEditing = false;
                if (save) {
                    Product product = getTableRow() != null ? (Product) getTableRow().getItem() : null;
                    if (product != null) {
                        String newVal = editField.getText() != null ? editField.getText().trim() : "";
                        if (!newVal.equals(lastSaved)) {
                            switch (property) {
                                case "name"        -> product.setName(newVal);
                                case "madeIn"      -> product.setMadeIn(newVal);
                                case "description" -> product.setDescription(newVal);
                            }
                            lastSaved = newVal;
                            productDAO.update(product);
                        }
                    }
                }
                displayLabel.setText(lastSaved);
                editField.setStyle(FIELD_IDLE);
                setGraphic(displayLabel);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setAlignment(Pos.CENTER);
                isEditing = false;
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(null);
                    lastSaved = item;
                    displayLabel.setText(item);
                    setGraphic(displayLabel);
                }
            }
        });
        return col;
    }

    // ======================== DETAIL PANEL ========================


    private VBox buildDetailPanel() {
        VBox panel = new VBox(8);
        panel.getStyleClass().add("detail-panel");
        panel.setPadding(new Insets(12));
        panel.setMaxHeight(300);

        // Header row
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        detailTitle = new Label("Product Details");
        detailTitle.getStyleClass().add("detail-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().add("btn-close-detail");
        closeBtn.setOnAction(e -> hideDetailPanel());
        header.getChildren().addAll(detailTitle, spacer, closeBtn);

        // Content: image + shop codes grid
        HBox content = new HBox(16);
        content.setAlignment(Pos.TOP_LEFT);

        detailImage = new ImageView();
        detailImage.setFitWidth(125);
        detailImage.setFitHeight(125);
        detailImage.setPreserveRatio(true);
        detailImage.setSmooth(true);
        detailImage.setCursor(Cursor.HAND);
        detailImage.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                showFullImagePopup(currentDetailProduct);
            }
        });

        viewImageBtn = new Button("🔍 View Image");
        viewImageBtn.getStyleClass().add("btn-small");
        viewImageBtn.setOnAction(e -> showFullImagePopup());

        VBox imageBox = new VBox(8, detailImage, viewImageBtn);
        imageBox.setAlignment(Pos.TOP_CENTER);
        imageBox.setMinWidth(135);

        // Shop codes layout: wrapped FlowPane inside a ScrollPane
        FlowPane shopFlow = new FlowPane();
        shopFlow.setHgap(12);
        shopFlow.setVgap(8);
        shopFlow.getStyleClass().add("shop-grid");

        Label shopLabel = new Label("Shop Codes:");
        shopLabel.getStyleClass().add("shop-label");

        shopCodeFields = new TextField[Product.MAX_SHOP_CODES];
        for (int i = 0; i < Product.MAX_SHOP_CODES; i++) {
            Label lbl = new Label("S" + (i + 1) + ":");
            lbl.getStyleClass().add("shop-slot-label");
            lbl.setPrefWidth(30);

            TextField tf = new TextField();
            tf.setPrefWidth(90);
            tf.setPromptText("Shop " + (i + 1));
            tf.getStyleClass().add("shop-field");

            final int idx = i;
            tf.textProperty().addListener((obs, old, val) -> {
                if (currentDetailProduct != null && val != null) {
                    // Validate: alphanumeric, max 20 chars
                    String cleaned = val.replaceAll("[^a-zA-Z0-9\\-_]", "");
                    if (cleaned.length() > 20)
                        cleaned = cleaned.substring(0, 20);
                    if (!cleaned.equals(val)) {
                        tf.setText(cleaned);
                        return;
                    }
                    currentDetailProduct.setShopCode(idx, cleaned);
                    productDAO.update(currentDetailProduct);
                    tableView.refresh();
                    refreshShopCodes();
                }
            });

            shopCodeFields[i] = tf;
            HBox cell = new HBox(4);
            cell.setAlignment(Pos.CENTER_LEFT);
            cell.getChildren().addAll(lbl, tf);
            shopFlow.getChildren().add(cell);
        }

        VBox rightSide = new VBox(8);
        HBox.setHgrow(rightSide, Priority.ALWAYS);

        ScrollPane scrollPane = new ScrollPane(shopFlow);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportHeight(140);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; -fx-padding: 0;");

        rightSide.getChildren().addAll(shopLabel, scrollPane);

        content.getChildren().addAll(imageBox, rightSide);
        panel.getChildren().addAll(header, content);
        return panel;
    }

    private void showDetailPanel(Product product) {
        currentDetailProduct = product;
        detailTitle.setText(product.getName() + "  (" + product.getCode() + ")");
        detailImage.setImage(imageService.getFullImage(product.getCode(), 125, 125));

        if (imageService.hasImage(product.getCode())) {
            viewImageBtn.setDisable(false);
            viewImageBtn.setText("🔍 View Image");
            viewImageBtn.setStyle("-fx-text-fill: #333333;");
        } else {
            viewImageBtn.setDisable(true);
            viewImageBtn.setText("Not found");
            viewImageBtn.setStyle("-fx-text-fill: #e74c3c; -fx-font-weight: bold;");
        }

        for (int i = 0; i < Product.MAX_SHOP_CODES; i++) {
            shopCodeFields[i].setText(product.getShopCode(i));
        }

        detailPanel.setVisible(true);
        detailPanel.setManaged(true);
    }

    private void hideDetailPanel() {
        if (detailPanel != null) {
            detailPanel.setVisible(false);
            detailPanel.setManaged(false);
        }
        currentDetailProduct = null;
        if (tableView != null) tableView.getSelectionModel().clearSelection();
    }

    private void showFullImagePopup() {
        showFullImagePopup(currentDetailProduct);
    }

    /**
     * Opens a large, resizable image window. Triggered by the View Image button
     * or by double-clicking a thumbnail (table cell or detail panel).
     */
    private void showFullImagePopup(Product product) {
        if (product == null)
            return;

        String code = product.getCode();
        if (!imageService.hasImage(code))
            return;

        javafx.geometry.Rectangle2D bounds = javafx.stage.Screen.getPrimary().getVisualBounds();
        double maxWidth = Math.max(400, bounds.getWidth() * 0.85);
        double maxHeight = Math.max(300, bounds.getHeight() * 0.85);

        Stage popup = new Stage();
        popup.setTitle("Image - " + product.getName() + " (" + code + ")");

        ImageView iv = new ImageView(imageService.getFullImage(code, maxWidth, maxHeight));
        iv.setPreserveRatio(true);
        iv.setSmooth(true);

        StackPane root = new StackPane(iv);
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #ffffff;");

        Scene scene = new Scene(root);
        popup.setScene(scene);
        popup.sizeToScene();
        popup.show();
    }

    // ======================== PAGINATION ========================

    private HBox buildPaginationBar() {
        HBox bar = new HBox(8);
        bar.getStyleClass().add("pagination-bar");
        bar.setPadding(new Insets(6, 12, 6, 12));
        bar.setAlignment(Pos.CENTER);

        Button prevBtn = new Button("◀ Previous");
        prevBtn.getStyleClass().add("btn-page");
        prevBtn.setOnAction(e -> {
            if (currentPage > 0) {
                currentPage--;
                refreshPage();
            }
        });

        Button nextBtn = new Button("Next ▶");
        nextBtn.getStyleClass().add("btn-page");
        nextBtn.setOnAction(e -> {
            if ((currentPage + 1) * pageSize < getFilteredSize()) {
                currentPage++;
                refreshPage();
            }
        });

        pageLabel = new Label("Page 1 / 1");
        pageLabel.getStyleClass().add("page-label");

        Label pageSizeLabel = new Label("Per page:");
        ComboBox<Integer> pageSizeCombo = new ComboBox<>(
                FXCollections.observableArrayList(25, 50, 100, 200));
        pageSizeCombo.setValue(PAGE_SIZE_DEFAULT);
        pageSizeCombo.setPrefWidth(80);
        pageSizeCombo.setOnAction(e -> {
            pageSize = pageSizeCombo.getValue();
            currentPage = 0;
            refreshPage();
        });

        Button firstBtn = new Button("⏮");
        firstBtn.getStyleClass().add("btn-page");
        firstBtn.setOnAction(e -> {
            currentPage = 0;
            refreshPage();
        });

        Button lastBtn = new Button("⏭");
        lastBtn.getStyleClass().add("btn-page");
        lastBtn.setOnAction(e -> {
            currentPage = Math.max(0, (getFilteredSize() - 1) / pageSize);
            refreshPage();
        });

        bar.getChildren().addAll(firstBtn, prevBtn, pageLabel, nextBtn, lastBtn,
                new Separator(Orientation.VERTICAL), pageSizeLabel, pageSizeCombo);
        return bar;
    }

    private void refreshPage() {
        int totalItems = getFilteredSize();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / pageSize));
        if (currentPage >= totalPages)
            currentPage = totalPages - 1;
        if (currentPage < 0)
            currentPage = 0;

        int from = currentPage * pageSize;
        int to = Math.min(from + pageSize, totalItems);

        List<Product> pageItems;
        if (sortedProducts != null) {
            pageItems = sortedProducts.subList(from, to);
        } else if (filteredProducts != null) {
            pageItems = filteredProducts.subList(from, to);
        } else {
            pageItems = allProducts.subList(from, to);
        }

        tableView.setItems(FXCollections.observableArrayList(pageItems));
        pageLabel.setText("Page " + (currentPage + 1) + " / " + totalPages);
        updateSelectedCount();
    }

    private int getFilteredSize() {
        return filteredProducts != null ? filteredProducts.size() : allProducts.size();
    }

    private ObservableList<Product> getPageItems() {
        return tableView.getItems();
    }

    // ======================== STATUS BAR ========================

    private HBox buildStatusBar() {
        HBox bar = new HBox(20);
        bar.getStyleClass().add("status-bar");
        bar.setPadding(new Insets(6, 12, 6, 12));
        bar.setAlignment(Pos.CENTER_LEFT);

        loadedCountLabel = new Label("0 products loaded");
        loadedCountLabel.getStyleClass().add("status-count");

        selectedCountLabel = new Label("0 selected");
        selectedCountLabel.getStyleClass().add("status-count");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label dbLabel = new Label("💾 SQLite");
        dbLabel.getStyleClass().add("status-db");

        bar.getChildren().addAll(loadedCountLabel, new Separator(Orientation.VERTICAL),
                selectedCountLabel, spacer, dbLabel);
        return bar;
    }

    private void updateSelectedCount() {
        long count = allProducts.stream().filter(Product::isSelected).count();
        selectedCount.set((int) count);
        selectedCountLabel.setText(count + " selected");
        syncHeaderCheckBox();
    }

    private void updateLoadedCount() {
        loadedCountLabel.setText(allProducts.size() + " products loaded");
    }

    /**
     * Syncs the header checkbox state to reflect whether all products
     * in the current tab (filteredProducts) are selected.
     * Must be called after any selection change or tab switch.
     */
    private void syncHeaderCheckBox() {
        if (headerCheckBox == null || filteredProducts == null)
            return;
        if (filteredProducts.isEmpty()) {
            headerCheckBox.setSelected(false);
            headerCheckBox.setIndeterminate(false);
            return;
        }
        long selectedInTab = filteredProducts.stream().filter(Product::isSelected).count();
        if (selectedInTab == 0) {
            headerCheckBox.setIndeterminate(false);
            headerCheckBox.setSelected(false);
        } else if (selectedInTab == filteredProducts.size()) {
            headerCheckBox.setIndeterminate(false);
            headerCheckBox.setSelected(true);
        } else {
            // Some but not all selected — show indeterminate dash
            headerCheckBox.setIndeterminate(true);
        }
    }

    // ======================== IMPORT ========================

    private void handleImport() {
        handleImport(false);
    }

    private void handleImport(boolean importImages) {
        Stage stage = (Stage) view.getScene().getWindow();

        // Step 1: Choose Excel folder or Files
        Alert modeAlert = new Alert(Alert.AlertType.CONFIRMATION, "How would you like to import Excel data?",
                new ButtonType("Select Folder"), new ButtonType("Select Files"), ButtonType.CANCEL);
        modeAlert.setTitle("Import Mode");
        modeAlert.setHeaderText("Choose Import Mode");
        modeAlert.getDialogPane().getStylesheets().add(
                getClass().getResource("/styles/app.css").toExternalForm());
        Optional<ButtonType> modeOpt = modeAlert.showAndWait();
        if (modeOpt.isEmpty() || modeOpt.get() == ButtonType.CANCEL)
            return;

        List<File> selectedExcelFiles = new ArrayList<>();
        if (modeOpt.get().getText().equals("Select Folder")) {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Select Excel Files Folder");
            File excelFolder = dc.showDialog(stage);
            if (excelFolder == null)
                return;
            File[] files = excelFolder.listFiles((dir, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".xlsx") || lower.endsWith(".xls");
            });
            if (files != null)
                selectedExcelFiles.addAll(Arrays.asList(files));
        } else {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select Excel Files");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel Files", "*.xlsx", "*.xls"));
            List<File> files = fc.showOpenMultipleDialog(stage);
            if (files == null || files.isEmpty())
                return;
            selectedExcelFiles.addAll(files);
        }

        if (selectedExcelFiles.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "No Excel files selected.", ButtonType.OK).show();
            return;
        }

        // Step 2: Choose Image folder (optional) — skipped for embedded-image imports
        if (!importImages) {
            Alert imgAlert = new Alert(Alert.AlertType.CONFIRMATION,
                    "Would you like to select an images folder?\n\n" +
                            "Images should be named by product code (e.g. A1023.jpg).",
                    ButtonType.YES, ButtonType.NO);
            imgAlert.setTitle("Images Folder");
            imgAlert.setHeaderText("Select Images Folder?");
            imgAlert.getDialogPane().getStylesheets().add(
                    getClass().getResource("/styles/app.css").toExternalForm());
            Optional<ButtonType> imgResult = imgAlert.showAndWait();

            File imageFolder = null;
            if (imgResult.isPresent() && imgResult.get() == ButtonType.YES) {
                DirectoryChooser idc = new DirectoryChooser();
                idc.setTitle("Select Images Folder");
                imageFolder = idc.showDialog(stage);
            }

            // Scan images
            if (imageFolder != null) {
                imageService.scanFolder(imageFolder);
            }
        }

        // Show progress
        progressBar.setVisible(true);
        progressLabel.setVisible(true);
        progressBar.setProgress(0);

        // Run import in background
        ExcelImportService importService = new ExcelImportService();
        Task<ExcelImportService.ImportResult> task = importService.createImportTask(selectedExcelFiles, imageService,
                productDAO, importImages);

        progressBar.progressProperty().bind(task.progressProperty());
        progressLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(e -> {
            ExcelImportService.ImportResult result = task.getValue();
            Platform.runLater(() -> {
                // Only insert products from files NOT already in DB
                if (!result.products.isEmpty()) {
                    productDAO.insertBatch(result.products);
                }

                // Reload all products to pick up any new ones
                allProducts.clear();
                List<Product> saved = productDAO.findAll();
                allProducts.addAll(saved);
                for (Product p : saved) {
                    if (p.getImagePath() != null && !p.getImagePath().isEmpty()) {
                        imageService.registerImage(p.getCode(), p.getImagePath());
                    }
                }

                // Open a tab for every selected file (new or existing) and focus the last one
                for (File f : selectedExcelFiles) {
                    openOrFocusFileTab(f.getName());
                }

                setupFilter();
                refreshShopCodes();
                refreshPage();
                updateLoadedCount();
                updateSelectedCount();

                progressBar.setVisible(false);
                progressLabel.setVisible(false);
                progressBar.progressProperty().unbind();
                progressLabel.textProperty().unbind();

                // Show summary
                showImportSummary(result);
                if (importImages) {
                    statusLabel.setText(result.products.size() + " products imported, "
                            + result.extractedImages + " images extracted from "
                            + result.totalFiles + " file(s).");
                } else {
                    statusLabel.setText(result.products.size() + " new products added from "
                            + result.totalFiles + " file(s).");
                }
            });
        });

        task.setOnFailed(e -> Platform.runLater(() -> {
            progressBar.setVisible(false);
            progressLabel.setVisible(false);
            progressBar.progressProperty().unbind();
            progressLabel.textProperty().unbind();
            showError("Import Failed", task.getException() != null
                    ? task.getException().getMessage()
                    : "Unknown error");
            statusLabel.setText("Import failed.");
        }));

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    private void showImportSummary(ExcelImportService.ImportResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("✅ New products imported: ").append(result.products.size()).append("\n");
        sb.append("📁 Files processed: ").append(result.totalFiles).append("\n");
        if (result.skipped > 0)
            sb.append("⏭ Files skipped (already in DB): ").append(result.skipped).append("\n");
        if (result.imageMode) {
            sb.append("🖼 Images extracted from files: ").append(result.extractedImages).append("\n");
            sb.append("🖼 Rows without embedded image: ").append(result.missingEmbeddedImages).append("\n");
        }
        sb.append("🖼 Products missing images: ").append(result.missingImages).append("\n");
        if (result.duplicates > 0)
            sb.append("⚠ Duplicate codes skipped: ").append(result.duplicates).append("\n");
        if (!result.warnings.isEmpty()) {
            sb.append("\nWarnings:\n");
            result.warnings.stream().limit(10).forEach(w -> sb.append("  • ").append(w).append("\n"));
            if (result.warnings.size() > 10) {
                sb.append("  ... and ").append(result.warnings.size() - 10).append(" more.\n");
            }
        }
        if (!result.errors.isEmpty()) {
            sb.append("\nErrors:\n");
            result.errors.forEach(err -> sb.append("  ❌ ").append(err).append("\n"));
        }

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Import Summary");
        alert.setHeaderText("Import Complete");
        alert.getDialogPane().setContent(new TextArea(sb.toString()));
        alert.getDialogPane().setPrefWidth(500);
        alert.getDialogPane().setPrefHeight(350);
        alert.getDialogPane().getStylesheets().add(
                getClass().getResource("/styles/app.css").toExternalForm());
        alert.showAndWait();
    }

    // ======================== IMAGE MANAGEMENT ========================

    /**
     * Prompts to add/replace or remove the image for a single product.
     * The chosen file is copied into the app-data images folder.
     */
    private void handleChangeImage(Product product) {
        if (product == null) return;

        String code = product.getCode();
        boolean hasImage = imageService.hasImage(code);

        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle(hasImage ? "Change Image" : "Add Image");
        dialog.setHeaderText((hasImage ? "Change image for " : "Add image for ")
                + product.getName() + " (" + code + ")");
        dialog.setContentText(hasImage
                ? "Choose a new image from this device, or remove the current image."
                : "Choose an image from this device.");

        ButtonType chooseBtn = new ButtonType("Choose Image…", ButtonBar.ButtonData.OK_DONE);
        ButtonType removeBtn = new ButtonType("Remove Image", ButtonBar.ButtonData.OTHER);
        dialog.getButtonTypes().setAll(chooseBtn, removeBtn, ButtonType.CANCEL);

        Node removeNode = dialog.getDialogPane().lookupButton(removeBtn);
        if (removeNode != null) removeNode.setDisable(!hasImage);

        dialog.getDialogPane().getStylesheets().add(
                getClass().getResource("/styles/app.css").toExternalForm());

        Optional<ButtonType> choice = dialog.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) return;

        if (choice.get() == removeBtn) {
            product.setImagePath(null);
            productDAO.update(product);
            imageService.removeImage(code);
            tableView.refresh();
            statusLabel.setText("Image removed for " + code + ".");
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle(hasImage ? "Choose Replacement Image" : "Choose Image");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Image Files", "*.png", "*.jpg", "*.jpeg", "*.bmp"));
        File chosen = fc.showOpenDialog((Stage) view.getScene().getWindow());
        if (chosen == null) return;

        String oldPath = imageService.getImagePath(code);
        String newPath = imageService.importImageFile(code, chosen);
        if (newPath == null) {
            showError("Invalid Image",
                    "The selected file could not be read as a supported image.\n"
                            + "Supported formats: PNG, JPG, JPEG, BMP.");
            return;
        }

        product.setImagePath(newPath);
        productDAO.update(product);
        if (oldPath != null && !oldPath.equals(newPath)) {
            imageService.deleteManagedImage(oldPath);
        }
        tableView.refresh();
        statusLabel.setText("Image " + (hasImage ? "updated" : "added") + " for " + code + ".");
    }

    // ======================== EXPORT ========================

    private void handleUnselect() {
        for (Product p : allProducts) {
            if (p.isSelected()) {
                p.setSelected(false);
            }
        }
        if (headerCheckBox != null) {
            headerCheckBox.setSelected(false);
        }
        tableView.refresh();
        updateSelectedCount();
    }

    private void handleDelete() {
        List<Product> toDelete = allProducts.stream()
                .filter(Product::isSelected)
                .collect(java.util.stream.Collectors.toList());

        if (toDelete.isEmpty())
            return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Are you sure you want to permanently delete " + toDelete.size()
                        + " selected product(s)?\n\nThis cannot be undone.",
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Confirm Delete");
        confirm.setHeaderText("Delete " + toDelete.size() + " product(s)?");
        confirm.getDialogPane().getStylesheets().add(
                getClass().getResource("/styles/app.css").toExternalForm());

        confirm.showAndWait().ifPresent(btn -> {
            if (btn != ButtonType.YES)
                return;

            List<String> codes = toDelete.stream()
                    .map(Product::getCode)
                    .collect(java.util.stream.Collectors.toList());

            int deleted = productDAO.deleteByCodes(codes);
            allProducts.removeAll(toDelete);

            // Close detail panel if the currently displayed product was deleted
            if (currentDetailProduct != null && codes.stream()
                    .anyMatch(c -> c.equalsIgnoreCase(currentDetailProduct.getCode()))) {
                hideDetailPanel();
            }

            if (headerCheckBox != null)
                headerCheckBox.setSelected(false);
            closeEmptyFileTabs();
            setupFilter();
            refreshShopCodes();
            refreshPage();
            updateLoadedCount();
            updateSelectedCount();
            statusLabel.setText(deleted + " product(s) deleted.");
        });
    }

    private boolean imagesOnlySelected() {
        return imagesOnlyCheckBox != null && imagesOnlyCheckBox.isSelected();
    }

    /**
     * Returns only the products that can actually be exported when the
     * "Images only" toggle is on. When it is off, the list is returned as-is.
     */
    private List<Product> filterExportable(List<Product> products) {
        if (!imagesOnlySelected()) return products;
        return products.stream()
                .filter(p -> imageService.hasReadableImage(p.getCode()))
                .collect(Collectors.toList());
    }

    private void showNoImagesWarning(String subject) {
        showError("No Images",
                "Images-only export is on, but " + subject + " have an image.\n"
                        + "Turn off \"Images only\" to export all products.");
    }

    private static String sourceTab(Product product) {
        String sf = product.getSourceFile();
        if (sf == null) return null;
        return sf.contains(" [") ? sf.substring(0, sf.indexOf(" [")) : sf;
    }

    private static boolean belongsToTab(Product product, String tabName) {
        String base = sourceTab(product);
        return base != null && base.equalsIgnoreCase(tabName);
    }

    private static boolean belongsToAnyTab(Product product, Collection<String> tabNames) {
        String base = sourceTab(product);
        return base != null && tabNames.stream().anyMatch(t -> t.equalsIgnoreCase(base));
    }

    private void handleExport() {
        List<Product> selected = allProducts.stream()
                .filter(Product::isSelected)
                .collect(Collectors.toList());

        if (selected.isEmpty()) {
            showError("No Selection", "Please select at least one product to export.");
            return;
        }

        List<Product> toExport = filterExportable(selected);
        if (toExport.isEmpty()) {
            showNoImagesWarning("none of the selected products");
            return;
        }

        Stage stage = (Stage) view.getScene().getWindow();
        FileChooser fc = new FileChooser();
        fc.setTitle("Save Export File");
        fc.setInitialFileName("products_export.xlsx");
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Excel Files", "*.xlsx"));
        File file = fc.showSaveDialog(stage);
        if (file == null)
            return;

        progressBar.setVisible(true);
        progressLabel.setVisible(true);
        progressBar.setProgress(0);

        ExcelExportService exportService = new ExcelExportService();
        Task<Void> task = exportService.createExportTask(toExport, file, imageService);

        progressBar.progressProperty().bind(task.progressProperty());
        progressLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            progressBar.setVisible(false);
            progressLabel.setVisible(false);
            progressBar.progressProperty().unbind();
            progressLabel.textProperty().unbind();

            // Delete only the products that were actually exported
            List<String> codes = toExport.stream()
                    .map(Product::getCode)
                    .collect(Collectors.toList());

            int deleted = productDAO.deleteByCodes(codes);
            allProducts.removeAll(toExport);

            // Close detail panel if the currently displayed product was deleted
            if (currentDetailProduct != null && codes.stream()
                    .anyMatch(c -> c.equalsIgnoreCase(currentDetailProduct.getCode()))) {
                hideDetailPanel();
            }

            if (headerCheckBox != null)
                headerCheckBox.setSelected(false);
            
            closeEmptyFileTabs();
            setupFilter();
            refreshShopCodes();
            refreshPage();
            updateLoadedCount();
            updateSelectedCount();

            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "Successfully exported and deleted " + deleted + " products to:\n" + file.getName());
            alert.setTitle("Export and Delete Complete");
            alert.setHeaderText("Export Successful");
            alert.getDialogPane().getStylesheets().add(
                    getClass().getResource("/styles/app.css").toExternalForm());
            alert.showAndWait();
            statusLabel.setText("Export complete: " + deleted + " products exported and deleted.");
        }));

        task.setOnFailed(e -> Platform.runLater(() -> {
            progressBar.setVisible(false);
            progressLabel.setVisible(false);
            progressBar.progressProperty().unbind();
            progressLabel.textProperty().unbind();
            showError("Export Failed", task.getException() != null
                    ? task.getException().getMessage()
                    : "Unknown error");
            statusLabel.setText("Export failed.");
        }));

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    private void handleExportByShop() {
        List<Product> selected = allProducts.stream()
                .filter(Product::isSelected)
                .collect(Collectors.toList());

        if (selected.isEmpty()) {
            showError("No Selection", "Please select at least one product to export.");
            return;
        }

        List<Product> toExport = filterExportable(selected);
        if (toExport.isEmpty()) {
            showNoImagesWarning("none of the selected products");
            return;
        }

        String shopId = shopCombo.getValue();
        if (shopId != null) shopId = shopId.trim();

        Stage stage = (Stage) view.getScene().getWindow();
        FileChooser fc = new FileChooser();
        fc.setTitle("Save Export File");
        fc.setInitialFileName("products_export_shop.xlsx");
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Excel Files", "*.xlsx"));
        File file = fc.showSaveDialog(stage);
        if (file == null)
            return;

        progressBar.setVisible(true);
        progressLabel.setVisible(true);
        progressBar.setProgress(0);

        ExcelExportService exportService = new ExcelExportService();
        Task<Void> task = exportService.createExportByShopTask(toExport, shopId, file, imageService);

        progressBar.progressProperty().bind(task.progressProperty());
        progressLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            progressBar.setVisible(false);
            progressLabel.setVisible(false);
            progressBar.progressProperty().unbind();
            progressLabel.textProperty().unbind();

            // Unselect only exported products (do NOT delete them)
            for (Product p : toExport) {
                p.setSelected(false);
            }

            if (headerCheckBox != null)
                headerCheckBox.setSelected(false);

            tableView.refresh();
            updateSelectedCount();

            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "Successfully exported " + toExport.size() + " products to:\n" + file.getName());
            alert.setTitle("Export Complete");
            alert.setHeaderText("Export Successful");
            alert.getDialogPane().getStylesheets().add(
                    getClass().getResource("/styles/app.css").toExternalForm());
            alert.showAndWait();
            statusLabel.setText("Export complete: " + toExport.size() + " products exported.");
        }));

        task.setOnFailed(e -> Platform.runLater(() -> {
            progressBar.setVisible(false);
            progressLabel.setVisible(false);
            progressBar.progressProperty().unbind();
            progressLabel.textProperty().unbind();
            showError("Export Failed", task.getException() != null
                    ? task.getException().getMessage()
                    : "Unknown error");
            statusLabel.setText("Export failed.");
        }));

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    private static final class NamedExportResult {
        final int tabs;
        final int products;

        NamedExportResult(int tabs, int products) {
            this.tabs = tabs;
            this.products = products;
        }
    }

    /**
     * Lets the user choose which file tabs to export.
     * Each selected tab is exported as a separate .xlsx file named after the tab,
     * then its products are deleted and the (now-empty) tab is closed.
     */
    private void handleExportWithName() {
        // Collect all open file tabs (exclude "All Products")
        List<Map.Entry<String, Tab>> fileTabs = new ArrayList<>(openFileTabs.entrySet());
        if (fileTabs.isEmpty()) {
            showError("No File Tabs", "No file tabs are open. Import some files first.");
            return;
        }

        boolean imagesOnly = imagesOnlySelected();

        // ── Tab-selection dialog ──────────────────────────────────────────
        Dialog<List<String>> dialog = new Dialog<>();
        dialog.setTitle("Export with Name");
        dialog.setHeaderText("Select the tabs to export.\nEach tab will be saved as a separate .xlsx file."
                + (imagesOnly ? "\nOnly products with images will be exported." : ""));
        dialog.getDialogPane().getStylesheets().add(
                getClass().getResource("/styles/app.css").toExternalForm());

        VBox checkBoxContainer = new VBox(8);
        checkBoxContainer.setPadding(new Insets(12));
        List<CheckBox> checkBoxes = new ArrayList<>();
        for (Map.Entry<String, Tab> entry : fileTabs) {
            String tabName = entry.getKey();
            long count = allProducts.stream()
                    .filter(p -> belongsToTab(p, tabName))
                    .filter(p -> !imagesOnly || imageService.hasReadableImage(p.getCode()))
                    .count();
            CheckBox cb = new CheckBox(tabName + "  (" + count
                    + (imagesOnly ? " with images)" : " products)"));
            cb.setUserData(tabName);
            cb.setSelected(true);
            checkBoxes.add(cb);
            checkBoxContainer.getChildren().add(cb);
        }

        ScrollPane sp = new ScrollPane(checkBoxContainer);
        sp.setFitToWidth(true);
        sp.setPrefHeight(Math.min(300, fileTabs.size() * 36 + 24));
        sp.setStyle("-fx-background-color: transparent;");
        dialog.getDialogPane().setContent(sp);

        ButtonType exportBtn = new ButtonType("Export Selected", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(exportBtn, ButtonType.CANCEL);

        dialog.setResultConverter(bt -> {
            if (bt != exportBtn) return null;
            return checkBoxes.stream()
                    .filter(CheckBox::isSelected)
                    .map(cb -> (String) cb.getUserData())
                    .collect(Collectors.toList());
        });

        List<String> selectedTabs = dialog.showAndWait().orElse(null);
        if (selectedTabs == null || selectedTabs.isEmpty()) return;

        if (imagesOnly) {
            boolean anyExportable = allProducts.stream()
                    .filter(p -> belongsToAnyTab(p, selectedTabs))
                    .anyMatch(p -> imageService.hasReadableImage(p.getCode()));
            if (!anyExportable) {
                showNoImagesWarning("none of the products in the chosen tabs");
                return;
            }
        }

        // ── Choose save directory ─────────────────────────────────────────
        Stage stage = (Stage) view.getScene().getWindow();
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Choose Export Folder");
        File dir = dc.showDialog(stage);
        if (dir == null) return;

        // ── Export each tab sequentially in a background thread ───────────
        progressBar.setVisible(true);
        progressLabel.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        ExcelExportService exportService = new ExcelExportService();
        List<String> tabsToExport = new ArrayList<>(selectedTabs);

        Task<NamedExportResult> task = new Task<>() {
            @Override
            protected NamedExportResult call() throws Exception {
                int totalExported = 0;
                int tabsExported = 0;
                for (int i = 0; i < tabsToExport.size(); i++) {
                    String tabName = tabsToExport.get(i);
                    updateMessage("Exporting " + tabName + " (" + (i + 1) + "/" + tabsToExport.size() + ")…");
                    updateProgress(i, tabsToExport.size());

                    List<Product> tabProducts = allProducts.stream()
                            .filter(p -> belongsToTab(p, tabName))
                            .filter(p -> !imagesOnly || imageService.hasReadableImage(p.getCode()))
                            .collect(Collectors.toList());

                    if (tabProducts.isEmpty()) continue;

                    // Sanitise tab name to a valid filename
                    String safeName = tabName.replaceAll("[/\\\\:*?\"<>|]", "_");
                    File outFile = new File(dir, safeName + ".xlsx");

                    // Call the pure export method directly (safe on background threads)
                    exportService.exportToFile(tabProducts, outFile, imageService);

                    totalExported += tabProducts.size();
                    tabsExported++;
                }
                updateProgress(tabsToExport.size(), tabsToExport.size());
                return new NamedExportResult(tabsExported, totalExported);
            }
        };

        progressBar.progressProperty().bind(task.progressProperty());
        progressLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            progressBar.setVisible(false);
            progressLabel.setVisible(false);
            progressBar.progressProperty().unbind();
            progressLabel.textProperty().unbind();

            // Delete only the products that were actually exported
            List<Product> toDelete = allProducts.stream()
                    .filter(p -> belongsToAnyTab(p, tabsToExport))
                    .filter(p -> !imagesOnly || imageService.hasReadableImage(p.getCode()))
                    .collect(Collectors.toList());

            List<String> codes = toDelete.stream().map(Product::getCode).collect(Collectors.toList());
            productDAO.deleteByCodes(codes);
            allProducts.removeAll(toDelete);

            if (currentDetailProduct != null && codes.stream()
                    .anyMatch(c -> c.equalsIgnoreCase(currentDetailProduct.getCode())))
                hideDetailPanel();

            if (headerCheckBox != null) headerCheckBox.setSelected(false);
            closeEmptyFileTabs();
            setupFilter();
            refreshShopCodes();
            refreshPage();
            updateLoadedCount();
            updateSelectedCount();

            NamedExportResult result = task.getValue();
            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "Exported " + result.tabs + " tab(s) with " + result.products
                            + " products total to:\n" + dir.getAbsolutePath());
            alert.setTitle("Export with Name — Complete");
            alert.setHeaderText("Export Successful");
            alert.getDialogPane().getStylesheets().add(
                    getClass().getResource("/styles/app.css").toExternalForm());
            alert.showAndWait();
            statusLabel.setText("Export with Name: " + result.products
                    + " products exported and deleted.");
        }));

        task.setOnFailed(e -> Platform.runLater(() -> {
            progressBar.setVisible(false);
            progressLabel.setVisible(false);
            progressBar.progressProperty().unbind();
            progressLabel.textProperty().unbind();
            showError("Export Failed", task.getException() != null
                    ? task.getException().getMessage() : "Unknown error");
            statusLabel.setText("Export with Name failed.");
        }));

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    // ======================== FILTER ========================

    private void setupFilter() {
        applyFilter();
    }

    private void applyFilter() {
        if (filteredProducts == null) {
            filteredProducts = new FilteredList<>(allProducts, p -> true);
            sortedProducts = new SortedList<>(filteredProducts);
        }

        String query = searchField.getText();
        String searchType = searchFieldCombo.getValue();
        String selectedShop = shopCombo.getValue();
        String sourceFile = activeSourceFile; // capture for lambda

        filteredProducts.setPredicate(p -> {
            // Tab (source-file) filter
            if (sourceFile != null) {
                String sf = p.getSourceFile();
                if (sf == null)
                    return false;
                String base = sf.contains(" [") ? sf.substring(0, sf.indexOf(" [")) : sf;
                if (!base.equalsIgnoreCase(sourceFile))
                    return false;
            }

            // Shop code filter
            if (selectedShop != null && !selectedShop.trim().isEmpty()) {
                if (!p.hasShopCode(selectedShop)) {
                    return false;
                }
            }

            if (query == null || query.trim().isEmpty()) {
                return true;
            }

            String lower = query.trim().toLowerCase();
            if ("Product Name".equals(searchType)) {
                return p.getName().toLowerCase().contains(lower);
            } else if ("Code".equals(searchType)) {
                return p.getCode().toLowerCase().contains(lower);
            } else if ("Made In".equals(searchType)) {
                return p.getMadeIn().toLowerCase().contains(lower);
            } else if ("Description".equals(searchType)) {
                return p.getDescription().toLowerCase().contains(lower);
            } else {
                return p.getName().toLowerCase().contains(lower)
                        || p.getCode().toLowerCase().contains(lower)
                        || p.getMadeIn().toLowerCase().contains(lower)
                        || p.getDescription().toLowerCase().contains(lower)
                        || p.getShopCodesSummary().toLowerCase().contains(lower);
            }
        });
        applySort();
    }

    private void applySort() {
        if (sortedProducts == null)
            return;

        String sortField = sortFieldCombo.getValue();
        String sortOrder = sortOrderCombo.getValue();

        if ("None".equals(sortField) || sortField == null) {
            sortedProducts.setComparator(null);
        } else {
            Comparator<Product> comparator = (p1, p2) -> {
                String val1 = "";
                String val2 = "";
                switch (sortField) {
                    case "Product Name":
                        val1 = p1.getName();
                        val2 = p2.getName();
                        break;
                    case "Code":
                        val1 = p1.getCode();
                        val2 = p2.getCode();
                        break;
                    case "Made In":
                        val1 = p1.getMadeIn();
                        val2 = p2.getMadeIn();
                        break;
                }
                if (val1 == null)
                    val1 = "";
                if (val2 == null)
                    val2 = "";
                return val1.compareToIgnoreCase(val2);
            };

            if ("DESC".equals(sortOrder)) {
                comparator = comparator.reversed();
            }
            sortedProducts.setComparator(comparator);
        }

        currentPage = 0;
        refreshPage();
    }

    public void refreshShopCodes() {
        Set<String> codes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Product p : allProducts) {
            for (int i = 0; i < Product.MAX_SHOP_CODES; i++) {
                String sc = p.getShopCode(i);
                if (sc != null && !sc.trim().isEmpty()) {
                    codes.add(sc.trim());
                }
            }
        }
        String current = shopCombo.getValue();
        allShopCodes.setAll(codes);
        if (current != null && codes.contains(current)) {
            shopCombo.setValue(current);
        }
    }

    /**
     * Closes any open file tabs whose source file no longer has products in allProducts.
     * Should be called after products are removed (delete or export-and-delete).
     */
    private void closeEmptyFileTabs() {
        List<String> toClose = new ArrayList<>();
        for (Map.Entry<String, Tab> entry : openFileTabs.entrySet()) {
            String filename = entry.getKey();
            boolean hasProducts = allProducts.stream().anyMatch(p -> {
                String sf = p.getSourceFile();
                if (sf == null) return false;
                String base = sf.contains(" [") ? sf.substring(0, sf.indexOf(" [")) : sf;
                return base.equalsIgnoreCase(filename);
            });
            if (!hasProducts) {
                toClose.add(filename);
            }
        }
        for (String filename : toClose) {
            Tab tab = openFileTabs.remove(filename);
            if (tab != null) {
                tabPane.getTabs().remove(tab);
            }
        }
    }

    // ======================== PERSISTENCE ========================

    private void loadFromDatabase() {
        List<Product> saved = productDAO.findAll();
        if (!saved.isEmpty()) {
            allProducts.addAll(saved);
            for (Product p : saved) {
                if (p.getImagePath() != null && !p.getImagePath().isEmpty()) {
                    imageService.registerImage(p.getCode(), p.getImagePath());
                }
            }
            // Open a tab for each distinct source file that was previously imported
            List<String> filenames = productDAO.getDistinctSourceFilenames();
            for (String filename : filenames) {
                openOrFocusFileTab(filename);
            }
            // Return to "All Products" tab after opening file tabs
            tabPane.getSelectionModel().select(0);
            activeSourceFile = null;

            refreshShopCodes();
            setupFilter();
            refreshPage();
            updateLoadedCount();
            updateSelectedCount();
            statusLabel.setText(saved.size() + " products loaded from database.");
        }
    }

    /**
     * Saves all current shop code assignments to the database.
     * Called on app shutdown.
     */
    public void saveAll() {
        if (!allProducts.isEmpty()) {
            productDAO.saveAll(new ArrayList<>(allProducts));
        }
    }

    // ======================== UTILS ========================

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.getDialogPane().getStylesheets().add(
                getClass().getResource("/styles/app.css").toExternalForm());
        alert.showAndWait();
    }
}
