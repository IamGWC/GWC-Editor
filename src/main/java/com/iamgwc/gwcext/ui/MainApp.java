package com.iamgwc.gwcext.ui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

// Window shell: chrome, startup sizing, global shortcuts, Note to whoever: document editing lives in EditorController
public class MainApp extends Application {

    private enum WindowState { NORMAL, MAXIMIZED, SNAPPED_LEFT, SNAPPED_RIGHT }

    private WindowState windowState = WindowState.NORMAL;
    private double restoreX, restoreY, restoreWidth = 1100, restoreHeight = 750;

    private static final double SNAP_THRESHOLD = 20;

    @Override
    public void start(Stage stage) throws Exception {
        BundledFonts.load();

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/iamgwc/gwcext/ui/editor.fxml"));
        Parent editorRoot = loader.load();

        stage.initStyle(StageStyle.UNDECORATED);

        BorderPane shell = new BorderPane();
        shell.getStyleClass().add("app-shell");
        shell.setTop(buildTitleBar(stage));
        shell.setCenter(editorRoot);

        Scene scene = new Scene(shell, 1280, 800);
        scene.getStylesheets().add(getClass().getResource("/com/iamgwc/gwcext/ui/style.css").toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F11) {
                stage.setFullScreen(!stage.isFullScreen());
                event.consume();
            }
        });

        stage.setTitle("GWC Editor");
        stage.setScene(scene);

        Rectangle2D center = Screen.getPrimary().getVisualBounds();
        restoreX = center.getMinX() + (center.getWidth() - restoreWidth) / 2;
        restoreY = center.getMinY() + (center.getHeight() - restoreHeight) / 2;
        applyMaximizedBounds(stage);

        stage.show();

        EditorController controller = loader.getController();
        controller.attachStage(stage);
    }

    // Fills the screen's visual bounds - Screen.getVisualBounds() excludes the taskbar | setMaximized doesn't reliably do the same on an undecorated stage
    private void applyMaximizedBounds(Stage stage) {
        Rectangle2D vb = Screen.getPrimary().getVisualBounds();
        applyBounds(stage, vb.getMinX(), vb.getMinY(), vb.getWidth(), vb.getHeight());
        windowState = WindowState.MAXIMIZED;
    }

    private void applyBounds(Stage stage, double x, double y, double w, double h) {
        stage.setX(x);
        stage.setY(y);
        stage.setWidth(w);
        stage.setHeight(h);
    }

    private void rememberIfNormal(Stage stage) {
        if (windowState == WindowState.NORMAL) {
            restoreX = stage.getX();
            restoreY = stage.getY();
            restoreWidth = stage.getWidth();
            restoreHeight = stage.getHeight();
        }
    }

    private void toggleMaximize(Stage stage) {
        if (windowState == WindowState.MAXIMIZED) {
            applyBounds(stage, restoreX, restoreY, restoreWidth, restoreHeight);
            windowState = WindowState.NORMAL;
        } else {
            rememberIfNormal(stage);
            applyMaximizedBounds(stage);
        }
    }

    private HBox buildTitleBar(Stage stage) {
        Label title = new Label("GWC Editor");
        title.getStyleClass().add("title-bar-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button minimizeButton = titleBarButton("\u2013");
        minimizeButton.setOnAction(e -> stage.setIconified(true));

        Button maximizeButton = titleBarButton("\u25A1");
        maximizeButton.setOnAction(e -> toggleMaximize(stage));

        Button closeButton = titleBarButton("\u2715");
        closeButton.getStyleClass().add("title-bar-close");
        closeButton.setOnAction(e -> stage.close());

        HBox windowControls = new HBox(4, minimizeButton, maximizeButton, closeButton);

        HBox bar = new HBox(title, spacer, windowControls);
        bar.getStyleClass().add("title-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        setupDragAndSnap(bar, stage);
        return bar;
    }

    // fixed window dragging cause I'm using an undecorated title bar | Note: Window dragging still busted
    private void setupDragAndSnap(HBox bar, Stage stage) {
        double[] dragAnchor = new double[2];
        boolean[] unsnappedThisDrag = new boolean[1];

        bar.setOnMousePressed(e -> {
            dragAnchor[0] = e.getSceneX();
            dragAnchor[1] = e.getSceneY();
            unsnappedThisDrag[0] = false;
        });

        bar.setOnMouseDragged(e -> {
            if (windowState != WindowState.NORMAL && !unsnappedThisDrag[0]) {
                double ratioX = dragAnchor[0] / stage.getWidth();
                stage.setWidth(restoreWidth);
                stage.setHeight(restoreHeight);
                dragAnchor[0] = restoreWidth * ratioX;
                windowState = WindowState.NORMAL;
                unsnappedThisDrag[0] = true;
            }
            stage.setX(e.getScreenX() - dragAnchor[0]);
            stage.setY(e.getScreenY() - dragAnchor[1]);
        });

        bar.setOnMouseReleased(e -> {
            Rectangle2D vb = Screen.getPrimary().getVisualBounds();
            if (e.getScreenY() <= vb.getMinY() + SNAP_THRESHOLD) {
                rememberIfNormal(stage);
                applyBounds(stage, vb.getMinX(), vb.getMinY(), vb.getWidth(), vb.getHeight());
                windowState = WindowState.MAXIMIZED;
            } else if (e.getScreenX() <= vb.getMinX() + SNAP_THRESHOLD) {
                rememberIfNormal(stage);
                applyBounds(stage, vb.getMinX(), vb.getMinY(), vb.getWidth() / 2, vb.getHeight());
                windowState = WindowState.SNAPPED_LEFT;
            } else if (e.getScreenX() >= vb.getMaxX() - SNAP_THRESHOLD) {
                rememberIfNormal(stage);
                applyBounds(stage, vb.getMinX() + vb.getWidth() / 2, vb.getMinY(), vb.getWidth() / 2, vb.getHeight());
                windowState = WindowState.SNAPPED_RIGHT;
            }
        });

        bar.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                toggleMaximize(stage);
            }
        });
    }

    private Button titleBarButton(String glyph) {
        Button button = new Button(glyph);
        button.getStyleClass().add("title-bar-button");
        return button;
    }

    public static void main(String[] args) {
        launch(args);
    }
}