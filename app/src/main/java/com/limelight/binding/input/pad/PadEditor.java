package com.limelight.binding.input.pad;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.limelight.R;
import com.limelight.ui.NavigationDialogBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Edits the modern controller's layout over a dimmed stream: drag a control to move it, pinch it to resize it, drag a
 * picked control's round corner handle to size its width and height on their own up to the whole surface, tap it to
 * pick it. A dragged control lines up with another's centre or edge when it comes near, and a guide shows the line.
 * The glass panel shows the picked control (its size, opacity, Split or Join, Hide, and the pointer touchpad's Mouse
 * and Camera) or, with nothing picked, every control's size and opacity, the Add tiles of the controls the layout leaves
 * out, the preset and glyphs and whether Done saves for this game only; it changes between them smoothly. Its grip
 * moves it anywhere, and it reopens where it was left. Done keeps the draft; Cancel and Back drop it. A hidden control
 * leaves the surface for the Add tiles.
 */
@SuppressLint("ViewConstructor") // Created by ModernController.
final class PadEditor extends FrameLayout {
    /** Moved controls land on this grid, so rows and mirrored pairs line up without effort. */
    static final float GRID_DP = 8;
    /** The bits of a corner handle: 0 is the top-left one. */
    static final int RIGHT_CORNER = 1, BOTTOM_CORNER = 2;
    private static final float SCALE_STEP = .1f, PANEL_WIDTH_DP = 400, PANEL_MARGIN_DP = 16, SHORT_SURFACE_DP = 480;
    /**
     * On a short surface the panel is wider and lower: the preset, glyphs and game choice sit beside the Add tiles, or
     * in one row below them when the tiles need the width.
     */
    private static final float SHORT_PANEL_WIDTH_DP = 480;
    /** An Add tile's size, the gap between tiles and between groups of them, in dp. */
    static final float TILE_WIDTH_DP = 84, TILE_HEIGHT_DP = 68, TILE_GAP_DP = 8, GROUP_GAP_DP = 20;
    /** The Add groups, in the order the panel shows them. */
    static final int GAMEPAD = 0, KEYBOARD_MOUSE = 1, EXTRAS = 2;
    /** How long the panel takes to come in and to change between the picked control and the layout. */
    private static final long PANEL_MS = 200;
    private static final Interpolator EASE = new PathInterpolator(.2f, 0, 0, 1);
    /** Whether the editor animates; tests and previews turn it off to see each state as it settles. */
    static boolean transitions = true;

    private final ModernController controller;
    private final PadPainter painter;
    private final PadLayout draft;
    private final boolean gameAvailable;
    private boolean perGame;
    private PadLayout.Control selected;
    private final Surface surface;
    private final View panel, collapsedBar, gripView, scrollView, actionsView,
            controlSection, pointerMode, mouseCard, cameraCard, thisGame;
    private final TextView collapsedTitle;
    private final LinearLayout layoutSection, options;
    /** The Add groups side by side, for measuring whether they fit beside the options. */
    private TileFlow groups;
    private final LinearLayout addSection;
    private final TextView selection, sizeText, hint;
    private final MaterialButton hide, reset, split, join;
    private final Chip presetChip, glyphs;
    private boolean compactButtons, shortSurface;
    private boolean collapsed;
    private int expandedWidth;
    private final int basePadStart, basePadEnd, basePadBottom;
    private final Slider opacity;
    private final float density;
    private boolean settingOpacity;
    /** The controls the Add tiles show, so they are built again only when that changes. */
    private String addShown = "";
    private final List<Tile> tiles = new ArrayList<>();
    /**
     * Whether the player has placed the panel with its grip, in this orientation now or before. Then it stays where
     * it was put, its top-left corner at {@link #panelX}, {@link #panelY}, shown kept on the surface, and no longer
     * moves out of the picked control's way by itself.
     */
    private boolean panelPlaced;
    private float panelX, panelY, gripRawX, gripRawY, gripFromX, gripFromY;
    private boolean collapsedDragged;
    /** Once a change of the panel has played, it moves out of the picked control's way with its final size. */
    private final Transition.TransitionListener settled = new Transition.TransitionListener() {
        @Override public void onTransitionEnd(Transition transition) {
            keepPanelClear();
        }

        @Override public void onTransitionStart(Transition transition) { }

        @Override public void onTransitionCancel(Transition transition) { }

        @Override public void onTransitionPause(Transition transition) { }

        @Override public void onTransitionResume(Transition transition) { }
    };

    PadEditor(Context context, ModernController controller, PadPainter painter, PadLayout draft,
              boolean perGame, boolean gameAvailable) {
        // The stream's dark surfaces whatever theme the activity has, like the stream menu.
        super(new androidx.appcompat.view.ContextThemeWrapper(context, R.style.StreamTheme));
        context = getContext();
        this.controller = controller;
        this.painter = painter;
        this.draft = draft;
        this.perGame = perGame && gameAvailable;
        this.gameAvailable = gameAvailable;
        this.collapsed = !com.limelight.ui.PortalBackgroundHelper.isRobolectric();
        density = context.getResources().getDisplayMetrics().density;
        surface = new Surface(context);
        addView(surface, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        panel = LayoutInflater.from(context).inflate(R.layout.pad_editor_panel, this, false);
        basePadStart = panel.getPaddingStart();
        basePadEnd = panel.getPaddingEnd();
        basePadBottom = panel.getPaddingBottom();
        panel.setBackground(new FrostedEditorPanelDrawable());
        LayoutParams initParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                collapsed ? (Gravity.TOP | Gravity.CENTER_HORIZONTAL) : Gravity.CENTER);
        if (collapsed) {
            initParams.topMargin = Math.round(16 * density);
        }
        addView(panel, initParams);

        collapsedBar = panel.findViewById(R.id.pad_editor_collapsed_bar);
        collapsedTitle = panel.findViewById(R.id.pad_editor_collapsed_title);
        gripView = panel.findViewById(R.id.pad_editor_grip);
        scrollView = panel.findViewById(R.id.pad_editor_scroll);
        actionsView = panel.findViewById(R.id.pad_editor_actions);

        gripView.setOnTouchListener((grip, event) -> dragPanel(event));
        collapsedBar.setOnTouchListener((v, event) -> handleCollapsedTouch(event));
        // A panel that grows or shrinks (the picked control's rows), or a surface that turns or folds, keeps a
        // placed panel whole on the surface.
        panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> showPlacedPanel());
        int[] placed = controller.panelPosition();
        if (placed != null) placePanel(placed[0], placed[1]);
        selection = panel.findViewById(R.id.pad_editor_selection);
        sizeText = panel.findViewById(R.id.pad_editor_size);
        controlSection = panel.findViewById(R.id.pad_editor_control);
        layoutSection = panel.findViewById(R.id.pad_editor_layout);
        addSection = panel.findViewById(R.id.pad_editor_add);
        options = panel.findViewById(R.id.pad_editor_options);
        hide = panel.findViewById(R.id.pad_editor_hide);
        reset = panel.findViewById(R.id.pad_editor_reset);
        presetChip = panel.findViewById(R.id.pad_editor_preset);
        presetChip.setOnClickListener(v -> choosePreset());
        glyphs = panel.findViewById(R.id.pad_editor_glyphs);
        glyphs.setContentDescription(getContext().getString(R.string.pad_edit_glyphs));
        glyphs.setOnClickListener(v -> chooseGlyphs());
        pointerMode = panel.findViewById(R.id.pad_editor_pointer_mode);
        mouseCard = panel.findViewById(R.id.pad_editor_mode_mouse);
        mouseCard.setOnClickListener(v -> setPointerMode(PadLayout.PointerMode.MOUSE));
        cameraCard = panel.findViewById(R.id.pad_editor_mode_camera);
        cameraCard.setOnClickListener(v -> setPointerMode(PadLayout.PointerMode.CAMERA));
        split = panel.findViewById(R.id.pad_editor_split);
        split.setOnClickListener(v -> split());
        join = panel.findViewById(R.id.pad_editor_join);
        join.setOnClickListener(v -> join());
        hint = panel.findViewById(R.id.pad_editor_hint);
        opacity = panel.findViewById(R.id.pad_editor_opacity);
        panel.findViewById(R.id.pad_editor_smaller).setOnClickListener(v -> scaleBy(-SCALE_STEP));
        panel.findViewById(R.id.pad_editor_larger).setOnClickListener(v -> scaleBy(SCALE_STEP));
        hide.setOnClickListener(v -> toggleHidden());
        reset.setOnClickListener(v -> {
            draft.reset();
            painter.setGlyph(draft.glyph());
            painter.setFacesSwapped(draft.facesSwapped());
            select(null);
            changed();
        });
        panel.findViewById(R.id.pad_editor_cancel).setOnClickListener(v -> controller.finishEditing(false));
        panel.findViewById(R.id.pad_editor_done).setOnClickListener(v -> controller.finishEditing(true));
        MaterialSwitch gameSwitch = panel.findViewById(R.id.pad_editor_this_game);
        thisGame = gameSwitch;
        gameSwitch.setVisibility(gameAvailable ? VISIBLE : GONE);
        gameSwitch.setChecked(this.perGame);
        gameSwitch.setOnCheckedChangeListener((button, checked) -> this.perGame = checked);
        com.limelight.ui.StepSlider.showValue(opacity, value -> String.format(Locale.getDefault(), "%d %%", value));
        opacity.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser && !settingOpacity) setOpacity(Math.round(value));
        });
        setClickable(true);
        applyCollapsedVisibility();
        applyThemeToPanel();
        updatePanel();
        if (transitions) {
            // The dimmed surface and the panel come in quickly rather than at once.
            surface.setAlpha(0);
            surface.animate().alpha(1).setDuration(PANEL_MS).setInterpolator(EASE).start();
            panel.setAlpha(0);
            panel.setScaleX(.96f);
            panel.setScaleY(.96f);
            panel.animate().alpha(1).scaleX(1).scaleY(1).setDuration(PANEL_MS).setInterpolator(EASE).start();
        }
    }

    private void applyCollapsedVisibility() {
        if (collapsed) {
            collapsedBar.setVisibility(VISIBLE);
            gripView.setVisibility(GONE);
            scrollView.setVisibility(GONE);
            actionsView.setVisibility(GONE);
            int hp = Math.round(14 * density);
            panel.setPaddingRelative(hp, 0, hp, 0);
        } else {
            collapsedBar.setVisibility(GONE);
            gripView.setVisibility(VISIBLE);
            scrollView.setVisibility(VISIBLE);
            actionsView.setVisibility(VISIBLE);
            panel.setPaddingRelative(basePadStart, panel.getPaddingTop(), basePadEnd, basePadBottom);
        }
        panel.invalidate();
    }

    /** Expands the floating editor window on touch; stays expanded until the editor window is closed. */
    void expandPanel() {
        if (!collapsed) return;
        transition();
        collapsed = false;
        applyCollapsedVisibility();
        LayoutParams params = (LayoutParams) panel.getLayoutParams();
        int targetW = expandedWidth > 0 ? expandedWidth
                : Math.round(Math.min(PANEL_WIDTH_DP * density, Math.max(240 * density, getWidth() - 2 * PANEL_MARGIN_DP * density)));
        params.width = Math.max(0, targetW);
        if (!panelPlaced) {
            params.gravity = Gravity.CENTER;
            params.leftMargin = params.topMargin = params.rightMargin = params.bottomMargin = 0;
        }
        updatePanel();
        panel.setLayoutParams(params);
        post(() -> {
            if (panelPlaced) showPlacedPanel();
            else keepPanelClear();
        });
    }

    private boolean handleCollapsedTouch(MotionEvent event) {
        if (!collapsed) return false;
        float slop = 10 * density;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                collapsedDragged = false;
                gripRawX = event.getRawX();
                gripRawY = event.getRawY();
                gripFromX = panel.getX();
                gripFromY = panel.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - gripRawX;
                float dy = event.getRawY() - gripRawY;
                if (collapsedDragged || Math.hypot(dx, dy) > slop) {
                    collapsedDragged = true;
                    placePanel(gripFromX + dx, gripFromY + dy);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (collapsedDragged) {
                    if (panelPlaced) controller.savePanelPosition(Math.round(panelX), Math.round(panelY));
                } else {
                    expandPanel();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (collapsedDragged && panelPlaced) {
                    controller.savePanelPosition(Math.round(panelX), Math.round(panelY));
                }
                return true;
            default:
                return false;
        }
    }

    /** Live One UI frosted card background for the floating editor window (pill when collapsed, squircle when expanded). */
    private final class FrostedEditorPanelDrawable extends android.graphics.drawable.Drawable {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path clipPath = new android.graphics.Path();
        private final RectF boundsF = new RectF();
        private final RectF rimBounds = new RectF();

        FrostedEditorPanelDrawable() {
            rimPaint.setStyle(Paint.Style.STROKE);
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;
            boundsF.set(b);
            float r = collapsed ? (boundsF.height() * 0.5f) : (22f * density);
            clipPath.reset();
            clipPath.addRoundRect(boundsF, r, r, android.graphics.Path.Direction.CW);

            Context ctx = getContext();
            com.limelight.ui.AppThemePalette palette = com.limelight.ui.AppThemePalette.getCurrent(ctx);
            boolean drewBlur = controller.blurHelper() != null
                    && controller.blurHelper().drawCardBlurForBounds(canvas, panel, clipPath, boundsF, 1f);
            fillPaint.setColor(drewBlur ? palette.getDialogOverlayTint() : palette.getDialogSurfaceColor(false));
            canvas.drawPath(clipPath, fillPaint);

            float outlineScale = com.limelight.ui.PortalBackgroundHelper.isRobolectric()
                    ? 1f
                    : Math.max(0f, com.limelight.preferences.PreferenceConfiguration.readPreferences(ctx).cardOutlineThicknessPercent / 100f);
            float strokePx = outlineScale <= 0.01f ? 0f : Math.max(1f, density * 1.15f * outlineScale);
            if (strokePx > 0f) {
                rimPaint.setStrokeWidth(strokePx);
                rimPaint.setColor(collapsed ? ((0xCC << 24) | (palette.highlight & 0x00FFFFFF)) : palette.getCardRimColor());
                rimBounds.set(boundsF);
                rimBounds.inset(strokePx * 0.5f, strokePx * 0.5f);
                canvas.drawRoundRect(rimBounds, Math.max(0f, r - strokePx * 0.5f), Math.max(0f, r - strokePx * 0.5f), rimPaint);
            }
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    void invalidateBlurTargets() {
        surface.invalidate();
        panel.invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // As wide as the design asks on a tablet, never wider than a phone in portrait leaves room for. A short surface
        // (a phone in landscape) gets a wider, lower panel without the hint, so it covers fewer controls.
        boolean shortSurface = h < SHORT_SURFACE_DP * density;
        int width = Math.round(Math.min((shortSurface ? SHORT_PANEL_WIDTH_DP : PANEL_WIDTH_DP) * density,
                w - 2 * PANEL_MARGIN_DP * density));
        // A narrow panel shows Reset as its icon so Cancel and Done stay on the row.
        boolean narrow = width < PANEL_WIDTH_DP * density;
        expandedWidth = Math.max(0, width);
        LayoutParams params = (LayoutParams) panel.getLayoutParams();
        int desiredWidth = collapsed ? LayoutParams.WRAP_CONTENT : expandedWidth;
        if (params.width != desiredWidth || narrow != compactButtons || shortSurface != this.shortSurface) {
            params.width = desiredWidth;
            post(() -> {
                compactButtons = narrow;
                this.shortSurface = shortSurface;
                updatePanel();
                params.width = collapsed ? LayoutParams.WRAP_CONTENT : expandedWidth;
                panel.setLayoutParams(params);
                if (!collapsed) keepPanelClear();
            });
        }
        if (oldw != 0 && oldh != 0 && (w > h) != (oldw > oldh)) {
            // Turned while editing: the panel goes where it was left in this orientation, or back to the middle.
            post(() -> {
                int[] placed = controller.panelPosition();
                if (placed != null) {
                    placePanel(placed[0], placed[1]);
                } else {
                    unplacePanel();
                }
            });
        } else {
            post(this::showPlacedPanel);
        }
    }

    /**
     * Where the preset and glyph chips and the game switch go, so the panel stays low on a short surface: beside the
     * Add tiles when every group of them fits there in one row, else in one row below them. On a tall surface the
     * chips sit above the switch, below the tiles.
     */
    private void arrangeLayout() {
        int margin = Math.round(12 * density);
        int baseW = collapsed ? expandedWidth : ((LayoutParams) panel.getLayoutParams()).width;
        int content = baseW - basePadStart - basePadEnd;
        boolean beside = false;
        if (shortSurface && content > 0 && addSection.getVisibility() == VISIBLE && groups != null) {
            int any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            // The options as wide as their own words, whatever row they were in before.
            View chips = (View) glyphs.getParent();
            chips.measure(any, any);
            thisGame.measure(any, any);
            groups.measure(any, any);
            int side = Math.max(chips.getMeasuredWidth(), thisGame.getMeasuredWidth());
            beside = groups.getMeasuredWidth() <= content - side - margin;
        }
        layoutSection.setOrientation(beside ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams add = (LinearLayout.LayoutParams) addSection.getLayoutParams();
        add.width = beside ? 0 : LinearLayout.LayoutParams.MATCH_PARENT;
        add.weight = beside ? 1 : 0;
        add.setMarginEnd(beside ? margin : 0);
        addSection.setLayoutParams(add);
        boolean oneRow = shortSurface && !beside;
        options.setOrientation(oneRow ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        options.setGravity(oneRow ? Gravity.CENTER_VERTICAL : Gravity.NO_GRAVITY);
        LinearLayout.LayoutParams side = (LinearLayout.LayoutParams) options.getLayoutParams();
        side.width = beside ? LinearLayout.LayoutParams.WRAP_CONTENT : LinearLayout.LayoutParams.MATCH_PARENT;
        options.setLayoutParams(side);
        // Beside the tiles the switch is as wide as its words, so its column is; in one row it takes what the chips
        // leave; below the chips it spans the panel.
        LinearLayout.LayoutParams game = (LinearLayout.LayoutParams) thisGame.getLayoutParams();
        game.width = beside ? LinearLayout.LayoutParams.WRAP_CONTENT : oneRow ? 0 : LinearLayout.LayoutParams.MATCH_PARENT;
        game.weight = oneRow ? 1 : 0;
        game.setMarginStart(oneRow ? Math.round(16 * density) : 0);
        thisGame.setLayoutParams(game);
    }

    /** The grip: dragging it moves the panel anywhere on the surface; letting go remembers where, per orientation. */
    private boolean dragPanel(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Raw coordinates: the grip moves with the panel, so its own would chase the finger.
                gripRawX = event.getRawX();
                gripRawY = event.getRawY();
                gripFromX = panel.getX();
                gripFromY = panel.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                placePanel(gripFromX + event.getRawX() - gripRawX, gripFromY + event.getRawY() - gripRawY);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (panelPlaced) controller.savePanelPosition(Math.round(panelX), Math.round(panelY));
                return true;
            default:
                return false;
        }
    }

    /** Puts the panel's top-left corner at ({@code x}, {@code y}), kept whole on the surface, and leaves it there. */
    private void placePanel(float x, float y) {
        LayoutParams params = (LayoutParams) panel.getLayoutParams();
        if (!panelPlaced || params.gravity != (Gravity.TOP | Gravity.START)) {
            // The layout puts it in the top-left corner; its translation carries the place, so a drag lays out nothing.
            params.gravity = Gravity.TOP | Gravity.START;
            params.leftMargin = params.topMargin = params.rightMargin = params.bottomMargin = 0;
            panel.setLayoutParams(params);
        }
        panelPlaced = true;
        panelX = Math.max(0, Math.min(maxPanelX(), x));
        panelY = Math.max(0, Math.min(maxPanelY(), y));
        showPlacedPanel();
    }

    /** Shows a placed panel where it was put, moved only as far as it takes to keep it whole on the surface. */
    private void showPlacedPanel() {
        if (!panelPlaced) return;
        float x = Math.max(0, Math.min(maxPanelX(), panelX)), y = Math.max(0, Math.min(maxPanelY(), panelY));
        panel.setTranslationX(x - panel.getLeft());
        panel.setTranslationY(y - panel.getTop());
    }

    /** Back to the panel's own place in the middle, moving out of the picked control's way by itself. */
    private void unplacePanel() {
        panelPlaced = false;
        panel.setTranslationX(0);
        panel.setTranslationY(0);
        LayoutParams params = (LayoutParams) panel.getLayoutParams();
        params.gravity = Gravity.CENTER;
        params.leftMargin = params.topMargin = params.rightMargin = params.bottomMargin = 0;
        panel.setLayoutParams(params);
        keepPanelClear();
    }

    private float maxPanelX() {
        return getWidth() > 0 && panel.getWidth() > 0 ? Math.max(0, getWidth() - panel.getWidth()) : Float.MAX_VALUE;
    }

    private float maxPanelY() {
        return getHeight() > 0 && panel.getHeight() > 0 ? Math.max(0, getHeight() - panel.getHeight()) : Float.MAX_VALUE;
    }

    /** The panel, for tests. */
    View panel() {
        return panel;
    }

    /** What the surface draws a control with at rest, for tests. */
    PadPainter.State previewState(PadLayout.Control control) {
        return surface.previewState(control);
    }

    /** The Add tile for {@code control}, or null when the layout shows it; for tests. */
    View tile(PadLayout.Control control) {
        for (Tile tile : tiles) {
            if (tile.control == control) return tile;
        }
        return null;
    }

    /** How many alignment guides the surface shows now, for tests. */
    int guides() {
        return surface.guidesX + surface.guidesY;
    }

    PadLayout draft() {
        return draft;
    }

    boolean perGame() {
        return perGame && gameAvailable;
    }

    PadLayout.Control selected() {
        return selected;
    }

    void select(PadLayout.Control control) {
        if (control != selected) transition();
        selected = control;
        updatePanel();
        surface.invalidate();
        post(this::keepPanelClear);
    }

    /**
     * Animates the panel's next change: what goes fades out, what comes fades in and the rest, the panel too, moves to
     * its new place; once played, the panel moves out of the picked control's way.
     */
    private void transition() {
        if (!transitions || !isLaidOut()) return;
        TransitionSet set = new TransitionSet().setOrdering(TransitionSet.ORDERING_TOGETHER)
                .addTransition(new Fade()).addTransition(new ChangeBounds());
        set.setDuration(PANEL_MS);
        set.setInterpolator(EASE);
        set.excludeTarget(surface, true);
        set.addListener(settled);
        TransitionManager.beginDelayedTransition(this, set);
    }

    /** The controls the editor shows: everything but what the settings keep off. */
    private boolean editable(PadLayout.Control control) {
        return !controller.forcedHidden(control);
    }

    /**
     * Whether the surface draws the control and a tap can pick it: a shown control the settings allow. A cluster and
     * its members never show together: a member is off the surface while its cluster shows.
     */
    private boolean onSurface(PadLayout.Control control) {
        if (!editable(control) || draft.spec(control).hidden) return false;
        PadLayout.Control cluster = control.cluster();
        return cluster == null || draft.spec(cluster).hidden;
    }

    /**
     * Whether the Add tiles offer the control: the layout leaves it out and the settings allow it. A split cluster is
     * not offered, its members are; a member only while its cluster is split, so adding never shows a cluster with
     * its members.
     */
    boolean addable(PadLayout.Control control) {
        if (!editable(control) || !draft.spec(control).hidden) return false;
        PadLayout.Control cluster = control.cluster();
        if (cluster != null) return draft.isSplit(cluster);
        return !draft.isSplit(control);
    }

    /** The Add group a control is offered in: the PC's gamepad, keyboard and mouse, or the extras beyond a gamepad. */
    static int addGroup(PadLayout.Control control) {
        if (control == PadLayout.Control.WASD || control == PadLayout.Control.POINTER_PAD) return KEYBOARD_MOUSE;
        if (control.steam || control == PadLayout.Control.TOUCHPAD) return EXTRAS;
        return GAMEPAD;
    }

    private static int groupName(int group) {
        switch (group) {
            case KEYBOARD_MOUSE: return R.string.pad_edit_group_keyboard_mouse;
            case EXTRAS: return R.string.pad_edit_group_extras;
            default: return R.string.pad_edit_group_gamepad;
        }
    }

    /**
     * The plus and minus buttons: the picked control, or every control, grows or shrinks by a step in both directions,
     * keeping its shape, up to the whole surface.
     */
    void scaleBy(float step) {
        float width = surfaceWidth(), height = surfaceHeight();
        for (PadLayout.Control control : PadLayout.Control.values()) {
            if (!editable(control) || (selected != null && control != selected)) continue;
            PadLayout.Spec spec = draft.spec(control);
            // Round the larger side to the step so repeated taps land on 50 %, 60 %, ... rather than drifting.
            float from = spec.scale(), to = Math.round((from + step) / SCALE_STEP) * SCALE_STEP;
            draft.scaleFrom(control, spec.widthScale, spec.heightScale, to / from, width, height, density);
        }
        changed();
    }

    /** The surface's size: the editor's own once laid out, before that the stream frame it fills. */
    private float surfaceWidth() {
        return surface.getWidth() > 0 ? surface.getWidth() : controller.frame().getWidth();
    }

    private float surfaceHeight() {
        return surface.getHeight() > 0 ? surface.getHeight() : controller.frame().getHeight();
    }

    void setOpacity(int value) {
        for (PadLayout.Control control : PadLayout.Control.values()) {
            if (!editable(control) || (selected != null && control != selected)) continue;
            draft.setOpacity(control, value);
        }
        surface.invalidate();
    }

    /** Hides the picked control, which leaves the surface for the Add tiles but stays picked, so Show undoes it. */
    void toggleHidden() {
        if (selected == null) return;
        PadLayout.Spec spec = draft.spec(selected);
        spec.hidden = !spec.hidden;
        changed();
    }

    /**
     * Splits the picked face cluster or D-pad into four controls of their own, where its buttons were and at their
     * sizes, and picks the first, so Join is at hand.
     */
    void split() {
        if (selected == null || PadLayout.members(selected).length == 0) return;
        PadLayout.Control cluster = selected;
        draft.split(cluster, surfaceWidth(), surfaceHeight(), density);
        select(PadLayout.members(cluster)[0]);
        changed();
    }

    /** Joins the picked member's cluster again where its members stand, in their arrangement, and picks it. */
    void join() {
        if (selected == null || selected.cluster() == null) return;
        PadLayout.Control cluster = selected.cluster();
        draft.join(cluster, surfaceWidth(), surfaceHeight(), density);
        painter.setFacesSwapped(draft.facesSwapped());
        select(cluster);
        changed();
    }

    /** Puts a control the layout leaves out back in, where it was, and picks it, so it can be placed at once. */
    void add(PadLayout.Control control) {
        if (!addable(control)) return;
        if (control == PadLayout.Control.FACE) {
            // Its members were hidden: it comes back in the arrangement they stood in.
            draft.takeFaceArrangement();
            painter.setFacesSwapped(draft.facesSwapped());
        }
        draft.spec(control).hidden = false;
        select(control);
        changed();
    }

    /** Puts the WASD stick in the layout and picks it. */
    void addWasd() {
        add(PadLayout.Control.WASD);
    }

    /** Puts the pointer touchpad in the layout and picks it, ready to move, resize and set its mode. */
    void addTouchpad() {
        add(PadLayout.Control.POINTER_PAD);
    }

    /** A preset replaces the layout with its selection of controls; Done keeps it, Cancel drops it. */
    void choosePreset() {
        new NavigationDialogBuilder(getContext()).setTitle(R.string.pad_edit_preset)
                .setItems(R.array.pad_preset_names, (dialog, which) -> {
                    draft.applyPreset(PadLayout.Preset.values()[which]);
                    painter.setGlyph(draft.glyph());
                    painter.setFacesSwapped(draft.facesSwapped());
                    select(null);
                    changed();
                }).setNegativeButton(android.R.string.cancel, null).create().show();
    }

    /** The face letters and shoulder names of one controller family. */
    void chooseGlyphs() {
        new NavigationDialogBuilder(getContext()).setTitle(R.string.pad_edit_glyphs)
                .setItems(R.array.pad_glyph_names, (dialog, which) -> {
                    draft.setGlyph(PadLayout.Glyph.values()[which]);
                    painter.setGlyph(draft.glyph());
                    painter.setFacesSwapped(draft.facesSwapped());
                    changed();
                }).setNegativeButton(android.R.string.cancel, null).create().show();
    }

    /** The pointer touchpad's mode, from its Mouse and Camera cards. */
    void setPointerMode(PadLayout.PointerMode mode) {
        if (draft.pointerMode() == mode) return;
        draft.setPointerMode(mode);
        changed();
    }

    /** Switches the pointer touchpad between moving the pointer and moving the camera. */
    void togglePointerMode() {
        setPointerMode(draft.pointerMode() == PadLayout.PointerMode.MOUSE ?
                PadLayout.PointerMode.CAMERA : PadLayout.PointerMode.MOUSE);
    }

    private void changed() {
        updatePanel();
        surface.invalidate();
    }

    private PadLayout.Spec reference() {
        if (selected != null) return draft.spec(selected);
        for (PadLayout.Control control : PadLayout.Control.values()) {
            if (editable(control)) return draft.spec(control);
        }
        return draft.spec(PadLayout.Control.L3);
    }

    private void applyThemeToPanel() {
        Context ctx = getContext();
        com.limelight.ui.AppThemePalette palette = com.limelight.ui.AppThemePalette.getCurrent(ctx);
        int primary = palette.getPrimaryTextColor();
        int secondary = palette.getSecondaryTextColor();
        int accent = palette.getNeonAccent();
        int highlight = palette.highlight;
        int rim = palette.getCardRimColor();
        int onAccent = androidx.core.graphics.ColorUtils.calculateLuminance(accent) > 0.45 ? 0xFF0B0E14 : 0xFFFFFFFF;
        android.content.res.ColorStateList ripple = android.content.res.ColorStateList.valueOf(
                (0x38 << 24) | (accent & 0x00FFFFFF));

        View cIcon = panel.findViewById(R.id.pad_editor_collapsed_icon);
        if (cIcon instanceof android.widget.ImageView) {
            ((android.widget.ImageView) cIcon).setImageTintList(android.content.res.ColorStateList.valueOf(highlight));
        }
        if (collapsedTitle != null) {
            collapsedTitle.setTextColor(primary);
        }
        View cChev = panel.findViewById(R.id.pad_editor_collapsed_chevron);
        if (cChev instanceof android.widget.ImageView) {
            ((android.widget.ImageView) cChev).setImageTintList(android.content.res.ColorStateList.valueOf(secondary));
        }

        View gripBar = panel.findViewById(R.id.pad_editor_grip_bar);
        if (gripBar != null) {
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            gd.setCornerRadius(4 * density);
            gd.setColor((0x99 << 24) | (secondary & 0x00FFFFFF));
            gripBar.setBackground(gd);
        }

        selection.setTextColor(primary);
        sizeText.setTextColor(primary);
        hint.setTextColor(secondary);

        View opacityParent = (View) opacity.getParent();
        if (opacityParent instanceof ViewGroup && ((ViewGroup) opacityParent).getChildCount() > 0) {
            View opLabel = ((ViewGroup) opacityParent).getChildAt(0);
            if (opLabel instanceof TextView) {
                ((TextView) opLabel).setTextColor(secondary);
            }
        }

        for (int id : new int[]{R.id.pad_editor_smaller, R.id.pad_editor_larger}) {
            View btn = panel.findViewById(id);
            if (btn instanceof MaterialButton) {
                MaterialButton mb = (MaterialButton) btn;
                mb.setIconTint(android.content.res.ColorStateList.valueOf(primary));
                mb.setRippleColor(ripple);
            }
        }

        opacity.setThumbTintList(android.content.res.ColorStateList.valueOf(highlight));
        opacity.setTrackActiveTintList(android.content.res.ColorStateList.valueOf(accent));
        opacity.setTrackInactiveTintList(android.content.res.ColorStateList.valueOf(
                (0x44 << 24) | (secondary & 0x00FFFFFF)));
        opacity.setHaloTintList(android.content.res.ColorStateList.valueOf(
                (0x44 << 24) | (accent & 0x00FFFFFF)));

        for (MaterialButton tonal : new MaterialButton[]{split, join, hide}) {
            tonal.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    (0x38 << 24) | (accent & 0x00FFFFFF)));
            tonal.setStrokeColor(android.content.res.ColorStateList.valueOf(rim));
            tonal.setStrokeWidth(Math.max(1, Math.round(density)));
            tonal.setTextColor(highlight);
            tonal.setIconTint(android.content.res.ColorStateList.valueOf(highlight));
            tonal.setRippleColor(ripple);
        }

        stylePointerCard(mouseCard, palette);
        stylePointerCard(cameraCard, palette);

        for (Chip chip : new Chip[]{presetChip, glyphs}) {
            chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                    (0x36 << 24) | (palette.surface & 0x00FFFFFF)));
            chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(rim));
            chip.setChipStrokeWidth(Math.max(1f, density));
            chip.setChipIconTint(android.content.res.ColorStateList.valueOf(highlight));
            chip.setTextColor(primary);
            chip.setRippleColor(ripple);
        }

        if (thisGame instanceof MaterialSwitch) {
            MaterialSwitch ms = (MaterialSwitch) thisGame;
            ms.setTextColor(primary);
            ms.setThumbTintList(new android.content.res.ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{highlight, secondary}));
            ms.setTrackTintList(new android.content.res.ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{(0x80 << 24) | (accent & 0x00FFFFFF), (0x44 << 24) | (secondary & 0x00FFFFFF)}));
            ms.setTrackDecorationTintList(new android.content.res.ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{highlight, (0x66 << 24) | (secondary & 0x00FFFFFF)}));
        }

        View cancelBtn = panel.findViewById(R.id.pad_editor_cancel);
        for (View v : new View[]{reset, cancelBtn}) {
            if (v instanceof MaterialButton) {
                MaterialButton mb = (MaterialButton) v;
                mb.setTextColor(secondary);
                mb.setIconTint(android.content.res.ColorStateList.valueOf(secondary));
                mb.setRippleColor(ripple);
            }
        }
        View doneBtn = panel.findViewById(R.id.pad_editor_done);
        if (doneBtn instanceof MaterialButton) {
            MaterialButton done = (MaterialButton) doneBtn;
            done.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));
            done.setTextColor(onAccent);
            done.setRippleColor(android.content.res.ColorStateList.valueOf(0x33FFFFFF));
        }
    }

    private void stylePointerCard(View card, com.limelight.ui.AppThemePalette palette) {
        if (!(card instanceof ViewGroup)) return;
        float r = 14f * density;
        int stroke = Math.max(1, Math.round(density * 1.15f));

        android.graphics.drawable.GradientDrawable selBg = new android.graphics.drawable.GradientDrawable();
        selBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        selBg.setCornerRadius(r);
        selBg.setColor((0x42 << 24) | (palette.accent & 0x00FFFFFF));
        selBg.setStroke(stroke, palette.highlight);

        android.graphics.drawable.GradientDrawable normBg = new android.graphics.drawable.GradientDrawable();
        normBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        normBg.setCornerRadius(r);
        normBg.setColor((0x2C << 24) | (palette.surface & 0x00FFFFFF));
        normBg.setStroke(Math.max(1, Math.round(density)), palette.getCardRimColor());

        android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_selected}, selBg);
        sld.addState(new int[]{}, normBg);
        card.setBackground(sld);

        int[][] states = new int[][]{new int[]{android.R.attr.state_selected}, new int[]{}};
        android.content.res.ColorStateList activeList = new android.content.res.ColorStateList(
                states, new int[]{palette.highlight, palette.getPrimaryTextColor()});

        ViewGroup vg = (ViewGroup) card;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            if (c instanceof android.widget.ImageView) {
                ((android.widget.ImageView) c).setImageTintList(activeList);
            } else if (c instanceof ViewGroup) {
                ViewGroup col = (ViewGroup) c;
                if (col.getChildCount() > 0 && col.getChildAt(0) instanceof TextView) {
                    ((TextView) col.getChildAt(0)).setTextColor(activeList);
                }
                if (col.getChildCount() > 1 && col.getChildAt(1) instanceof TextView) {
                    ((TextView) col.getChildAt(1)).setTextColor(palette.getSecondaryTextColor());
                }
            }
        }
    }

    private void updatePanel() {
        PadLayout.Spec spec = reference();
        String name = selected == null ? getContext().getString(R.string.pad_edit_all) :
                name(getContext(), selected, draft.glyph());
        if (selected != null && spec.hidden) name = getContext().getString(R.string.pad_edit_hidden, name);
        selection.setText(name);
        if (collapsedTitle != null) {
            collapsedTitle.setText(selected == null
                    ? getContext().getString(R.string.pad_editor_collapsed_pill)
                    : name);
        }
        sizeText.setText(sizeLabel(spec));
        settingOpacity = true;
        opacity.setValue(Math.max(PadLayout.MIN_OPACITY, Math.min(PadLayout.MAX_OPACITY, Math.round(spec.opacity / 5f) * 5)));
        settingOpacity = false;
        // A picked control shows its own section; with none, the layout's: the Add tiles, preset, glyphs and game.
        boolean picked = selected != null;
        controlSection.setVisibility(picked ? VISIBLE : GONE);
        layoutSection.setVisibility(picked ? GONE : VISIBLE);
        boolean showing = picked && spec.hidden;
        hide.setIconResource(showing ? R.drawable.ic_visibility : R.drawable.ic_visibility_off);
        hide.setText(showing ? R.string.pad_edit_show : R.string.pad_edit_hide);
        label(reset, getContext().getString(R.string.pad_edit_reset));
        boolean pointerPad = selected == PadLayout.Control.POINTER_PAD;
        pointerMode.setVisibility(pointerPad ? VISIBLE : GONE);
        boolean camera = draft.pointerMode() == PadLayout.PointerMode.CAMERA;
        mouseCard.setSelected(!camera);
        cameraCard.setSelected(camera);
        // The pointer touchpad's line says what Camera does, so the mode is found; a short surface shows only that.
        hint.setText(pointerPad ? R.string.pad_edit_hint_camera : R.string.pad_edit_hint);
        hint.setVisibility(pointerPad || !shortSurface ? VISIBLE : GONE);
        // Split for a shown face cluster or D-pad; Join for one of the members that stand in for it.
        split.setVisibility(picked && PadLayout.members(selected).length > 0 && !spec.hidden ? VISIBLE : GONE);
        join.setVisibility(picked && selected.cluster() != null ? VISIBLE : GONE);
        glyphs.setText(glyphName(getContext(), draft.glyph()));
        updateAdd();
        arrangeLayout();
    }

    /** Builds the Add tiles again when the controls the layout leaves out changed, and redraws them otherwise. */
    private void updateAdd() {
        StringBuilder shown = new StringBuilder();
        List<PadLayout.Control> out = new ArrayList<>();
        for (PadLayout.Control control : PadLayout.Control.values()) {
            if (!addable(control)) continue;
            out.add(control);
            shown.append(control.name()).append(',');
        }
        if (shown.toString().equals(addShown)) {
            for (Tile tile : tiles) tile.refresh();
            return;
        }
        addShown = shown.toString();
        addSection.removeAllViews();
        tiles.clear();
        groups = null;
        addSection.setVisibility(out.isEmpty() ? GONE : VISIBLE);
        if (out.isEmpty()) return;
        com.limelight.ui.AppThemePalette palette = com.limelight.ui.AppThemePalette.getCurrent(getContext());
        addSection.addView(headingThemed(R.string.pad_edit_add, R.style.TextAppearance_Nightjar_Label,
                palette.getPrimaryTextColor()));
        // Each group its name over its tiles; the groups side by side as far as the panel is wide.
        int gap = Math.round(TILE_GAP_DP * density);
        groups = new TileFlow(getContext(), Math.round(GROUP_GAP_DP * density), gap);
        addSection.addView(groups, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        for (int group : new int[] {GAMEPAD, KEYBOARD_MOUSE, EXTRAS}) {
            TileFlow flow = null;
            for (PadLayout.Control control : out) {
                if (addGroup(control) != group) continue;
                if (flow == null) {
                    LinearLayout block = new LinearLayout(getContext());
                    block.setOrientation(LinearLayout.VERTICAL);
                    block.addView(headingThemed(groupName(group), R.style.TextAppearance_Nightjar_Caption,
                            palette.getSecondaryTextColor()));
                    flow = new TileFlow(getContext(), gap, gap);
                    block.addView(flow);
                    groups.addView(block);
                }
                Tile tile = new Tile(getContext(), control);
                tiles.add(tile);
                flow.addView(tile);
            }
        }
    }

    private TextView headingThemed(int text, int appearance, int colorInt) {
        TextView view = new TextView(getContext());
        TextViewCompat.setTextAppearance(view, appearance);
        view.setTextColor(colorInt);
        view.setText(text);
        view.setPadding(0, Math.round(6 * density), 0, Math.round(4 * density));
        return view;
    }

    /** "120 %", or "150 × 80 %" for a control whose width and height differ. */
    static String sizeLabel(PadLayout.Spec spec) {
        int w = Math.round(spec.widthScale * 100), h = Math.round(spec.heightScale * 100);
        return w == h ? String.format(Locale.getDefault(), "%d %%", w) :
                String.format(Locale.getDefault(), "%d × %d %%", w, h);
    }

    private static String glyphName(Context context, PadLayout.Glyph glyph) {
        switch (glyph) {
            case PLAYSTATION: return context.getString(R.string.pad_glyph_playstation);
            case NINTENDO: return context.getString(R.string.pad_glyph_nintendo);
            default: return context.getString(R.string.pad_glyph_xbox);
        }
    }

    /** A labelled button, or on a narrow panel its icon alone with the label as description and tooltip. */
    private void label(MaterialButton button, String text) {
        // The style's own padding and minimum width, kept so a panel that widens again restores them.
        if (button.getTag(R.id.pad_editor_hide) == null) {
            button.setTag(R.id.pad_editor_hide, new int[] {button.getPaddingStart(), button.getPaddingEnd(), button.getMinWidth()});
        }
        int[] wide = (int[]) button.getTag(R.id.pad_editor_hide);
        int touch = Math.round(PadLayout.MIN_TOUCH_DP * density);
        // Icon alone: a 48 dp square with the icon in its middle, so the row fits a phone.
        int side = compactButtons ? Math.max(0, (touch - button.getIconSize()) / 2) : 0;
        button.setText(compactButtons ? null : text);
        button.setContentDescription(compactButtons ? text : null);
        button.setIconPadding(compactButtons ? 0 : Math.round(8 * density));
        button.setIconGravity(compactButtons ? MaterialButton.ICON_GRAVITY_TEXT_START : MaterialButton.ICON_GRAVITY_START);
        button.setPaddingRelative(compactButtons ? side : wide[0], button.getPaddingTop(),
                compactButtons ? side : wide[1], button.getPaddingBottom());
        button.setMinWidth(compactButtons ? touch : wide[2]);
        button.setMinimumWidth(compactButtons ? touch : wide[2]);
        androidx.appcompat.widget.TooltipCompat.setTooltipText(button, compactButtons ? text : null);
    }

    /** The control's name for people, with Xbox's face letters. */
    static String name(Context context, PadLayout.Control control) {
        return name(context, control, PadLayout.Glyph.XBOX);
    }

    /** The control's name for people: its label, or what it is when it has none; a face button's in its glyph set. */
    static String name(Context context, PadLayout.Control control, PadLayout.Glyph glyph) {
        if (control.shape == PadLayout.Shape.FACE_BUTTON) {
            return context.getString(R.string.pad_face_button, PadGlyphs.face(glyph, control.memberIndex()));
        }
        switch (control) {
            case DPAD_UP: return context.getString(R.string.pad_dpad_up);
            case DPAD_DOWN: return context.getString(R.string.pad_dpad_down);
            case DPAD_LEFT: return context.getString(R.string.pad_dpad_left);
            case DPAD_RIGHT: return context.getString(R.string.pad_dpad_right);
            case LEFT_STICK: return context.getString(R.string.pad_left_stick);
            case RIGHT_STICK: return context.getString(R.string.pad_right_stick);
            case DPAD: return context.getString(R.string.pad_dpad);
            case FACE: return context.getString(R.string.pad_face);
            case SELECT: return context.getString(R.string.pad_view);
            case START: return context.getString(R.string.pad_menu);
            case HOME: return context.getString(R.string.pad_home);
            case TOUCHPAD: return context.getString(R.string.pad_touchpad);
            case POINTER_PAD: return context.getString(R.string.pad_pointer_touchpad);
            case WASD: return context.getString(R.string.pad_wasd);
            case LEFT_PAD: return context.getString(R.string.pad_left_trackpad);
            case RIGHT_PAD: return context.getString(R.string.pad_right_trackpad);
            case LEFT_GRIP: return context.getString(R.string.pad_left_grip);
            case RIGHT_GRIP: return context.getString(R.string.pad_right_grip);
            default: return control.name();
        }
    }

    /** Moves the panel to the other half when the picked control sits under it, until the player places it. */
    private void keepPanelClear() {
        if (collapsed || selected == null || panel.getWidth() == 0 || panelPlaced) return;
        float[] box = draft.bounds(selected, getWidth(), getHeight(), density, new float[4]);
        RectF control = new RectF(box[0], box[1], box[0] + box[2], box[1] + box[3]);
        RectF under = new RectF(panel.getLeft(), panel.getTop(), panel.getRight(), panel.getBottom());
        if (!RectF.intersects(control, under)) return;
        LayoutParams params = (LayoutParams) panel.getLayoutParams();
        boolean controlHigh = control.centerY() < getHeight() / 2f;
        int gravity = Gravity.CENTER_HORIZONTAL | (controlHigh ? Gravity.BOTTOM : Gravity.TOP);
        int margin = Math.round(16 * density);
        if (params.gravity == gravity && params.topMargin == margin && params.bottomMargin == margin) return;
        transition();
        params.gravity = gravity;
        params.topMargin = params.bottomMargin = margin;
        panel.setLayoutParams(params);
    }

    /**
     * The nudge that lines a dragged box's start, centre or end up with another control's when one is within
     * {@code tolerance}, the nearest winning; NaN when none is near. {@code lines} holds each other control's start,
     * centre and end along the same axis, {@code count} values in all. Edges meet edges, on either side, and centres
     * meet centres.
     */
    static float alignNudge(float start, float centre, float end, float[] lines, int count, float tolerance) {
        float best = Float.NaN;
        for (int i = 0; i + 2 < count; i += 3) {
            float otherStart = lines[i], otherCentre = lines[i + 1], otherEnd = lines[i + 2];
            best = nearer(best, otherCentre - centre, tolerance);
            best = nearer(best, otherStart - start, tolerance);
            best = nearer(best, otherEnd - end, tolerance);
            best = nearer(best, otherStart - end, tolerance);
            best = nearer(best, otherEnd - start, tolerance);
        }
        return best;
    }

    private static float nearer(float best, float candidate, float tolerance) {
        if (Math.abs(candidate) > tolerance) return best;
        return Float.isNaN(best) || Math.abs(candidate) < Math.abs(best) ? candidate : best;
    }

    /**
     * Which of the other controls' {@code lines} (as {@link #alignNudge} takes them) the box's start, centre or end lies
     * on, to half a pixel: edges on edges, centres on centres. Each line goes once into {@code out}, as far as it
     * reaches; returns how many.
     */
    static int alignedLines(float start, float centre, float end, float[] lines, int count, float[] out) {
        int found = 0;
        for (int mine = 0; mine < 3 && found < out.length; mine++) {
            float line = mine == 0 ? start : mine == 1 ? centre : end;
            for (int i = 0; i < count; i++) {
                if ((i % 3 == 1) != (mine == 1) || Math.abs(lines[i] - line) > .5f) continue;
                boolean known = false;
                for (int k = 0; k < found; k++) known |= Math.abs(out[k] - line) <= .5f;
                if (!known) out[found++] = line;
                break;
            }
        }
        return found;
    }

    /** One control the layout leaves out, drawn small as it plays, with its name under it; a tap puts it in. */
    @SuppressLint("ViewConstructor")
    final class Tile extends View {
        final PadLayout.Control control;
        private final TextPaint caption = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final float captionSize;
        private String name = "";

        Tile(Context context, PadLayout.Control control) {
            super(context);
            this.control = control;
            com.limelight.ui.AppThemePalette palette = com.limelight.ui.AppThemePalette.getCurrent(context);
            android.graphics.drawable.GradientDrawable tileBg = new android.graphics.drawable.GradientDrawable();
            tileBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            tileBg.setCornerRadius(12f * density);
            tileBg.setColor((0x2C << 24) | (palette.surface & 0x00FFFFFF));
            tileBg.setStroke(Math.max(1, Math.round(density)), palette.getCardRimColor());
            android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
            mask.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            mask.setCornerRadius(12f * density);
            mask.setColor(0xFFFFFFFF);
            setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf((0x38 << 24) | (palette.accent & 0x00FFFFFF)),
                    tileBg, mask));
            setFocusable(true);
            setClickable(true);
            setOnClickListener(v -> add(control));
            caption.setTextAlign(Paint.Align.CENTER);
            captionSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics());
            caption.setTextSize(captionSize);
            caption.setColor(palette.getPrimaryTextColor());
            refresh();
        }

        /** Its name follows the glyph set (a face button's letter), its drawing that and the touchpad's mode. */
        void refresh() {
            name = name(getContext(), control, draft.glyph());
            caption.setColor(com.limelight.ui.AppThemePalette.getCurrent(getContext()).getPrimaryTextColor());
            setContentDescription(getContext().getString(R.string.pad_edit_add_control, name));
            invalidate();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(Math.round(TILE_WIDTH_DP * density), Math.round(TILE_HEIGHT_DP * density));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float pad = 6 * density, width = getWidth(), captionHeight = captionSize + 4 * density;
            // The control at its default shape, as large as the tile's picture area allows.
            float most = width - 2 * pad, tall = getHeight() - captionHeight - 2 * pad;
            float ratio = PadLayout.baseWidth(control) / PadLayout.baseHeight(control);
            float w = most, h = most / ratio;
            if (h > tall) {
                h = tall;
                w = tall * ratio;
            }
            painter.draw(canvas, control, (width - w) / 2, pad + (tall - h) / 2, w, h, surface.previewState(control));
            // A long name shrinks to fit, down to 9 sp, before it is cut short.
            float room = width - pad, size = captionSize;
            caption.setTextSize(size);
            float wide = caption.measureText(name);
            if (wide > room) caption.setTextSize(Math.max(captionSize * 9 / 11, size * room / wide));
            CharSequence text = TextUtils.ellipsize(name, caption, room, TextUtils.TruncateAt.END);
            canvas.drawText(text, 0, text.length(), width / 2, getHeight() - pad - caption.descent(), caption);
        }
    }

    /**
     * Lays its children out in rows, as many to a row as fit, {@code across} apart in a row and {@code down} between
     * rows; it is as wide as its widest row unless told otherwise, so a flow of flows wraps each in turn.
     */
    static final class TileFlow extends ViewGroup {
        private final int across, down;

        TileFlow(Context context, int across, int down) {
            super(context);
            this.across = across;
            this.down = down;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int mode = MeasureSpec.getMode(widthSpec), size = MeasureSpec.getSize(widthSpec);
            int limit = mode == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE : size;
            int childWidth = mode == MeasureSpec.UNSPECIFIED ? widthSpec : MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST);
            int x = 0, y = 0, row = 0, widest = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                measureChild(child, childWidth, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + child.getMeasuredWidth() > limit) {
                    x = 0;
                    y += row + down;
                    row = 0;
                }
                x += child.getMeasuredWidth() + across;
                widest = Math.max(widest, x - across);
                row = Math.max(row, child.getMeasuredHeight());
            }
            setMeasuredDimension(mode == MeasureSpec.EXACTLY ? size : Math.min(limit, widest), resolveSize(y + row, heightSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l, x = 0, y = 0, row = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
                if (x > 0 && x + w > width) {
                    x = 0;
                    y += row + down;
                    row = 0;
                }
                child.layout(x, y, x + w, y + h);
                x += w + across;
                row = Math.max(row, h);
            }
        }
    }

    /** Draws the dimmed stream, the grid, every control, the outlines and the guides, and takes the editing touches. */
    private final class Surface extends View {
        private final Paint scrim = new Paint(), grid = new Paint(), guide = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] box = new float[4], other = new float[4];
        private final PadPainter.State idle = new PadPainter.State(), pointerIdle = new PadPainter.State();
        private float offsetX, offsetY, pinchDistance, pinchWidth, pinchHeight, anchorX, anchorY;
        private boolean dragging, pinching;
        /** The corner handle being dragged ({@link #corner}'s bits), or -1. */
        private int resizing = -1;
        /** The other controls' lines along one axis while a control is dragged ({@link #alignNudge}). */
        private float[] lines = new float[96];
        /** The lines the dragged control shares with others now: x of the upright guides, y of the level ones. */
        private final float[] guideX = new float[3], guideY = new float[3];
        private int guidesX, guidesY;

        Surface(Context context) {
            super(context);
            scrim.setColor(com.limelight.ui.PortalBackgroundHelper.isRobolectric() ? 0x99000000 : 0x4D000000);
            grid.setColor(0x14FFFFFF);
            grid.setStrokeWidth(Math.max(1, density));
            guide.setColor(painter.highlight());
            guide.setStrokeWidth(Math.max(1, density));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float width = getWidth(), height = getHeight();
            canvas.drawRect(0, 0, width, height, scrim);
            float step = GRID_DP * 3 * density;
            for (float x = step; x < width; x += step) canvas.drawLine(x, 0, x, height, grid);
            for (float y = step; y < height; y += step) canvas.drawLine(0, y, width, y, grid);
            guide.setColor(painter.highlight());
            float base = controller.prefs().oscOpacity / 100f;
            painter.bindBlurView(this, controller.blurHelper());
            try {
                for (PadLayout.Control control : PadLayout.Control.values()) {
                    if (!onSurface(control)) continue;
                    PadLayout.Spec spec = draft.spec(control);
                    draft.bounds(control, width, height, density, box);
                    // Show the real opacity, but never so faint that a control cannot be found.
                    float alpha = Math.max(.35f, base * spec.opacity / 100f);
                    int saved = canvas.saveLayerAlpha(box[0], box[1], box[0] + box[2], box[1] + box[3],
                            Math.round(alpha * 255));
                    painter.draw(canvas, control, box[0], box[1], box[2], box[3], previewState(control));
                    canvas.restoreToCount(saved);
                    painter.drawOutline(canvas, box[0], box[1], box[2], box[3], control == selected);
                }
            } finally {
                painter.clearBlurView();
            }
            for (int i = 0; i < guidesX; i++) canvas.drawLine(guideX[i], 0, guideX[i], height, guide);
            for (int i = 0; i < guidesY; i++) canvas.drawLine(0, guideY[i], width, guideY[i], guide);
        }

        /** What a control shows at rest here: the pointer touchpad its mode's word, as it does in play. */
        PadPainter.State previewState(PadLayout.Control control) {
            if (control != PadLayout.Control.POINTER_PAD) return idle;
            pointerIdle.label = painter.pointerLabel(draft.pointerMode() == PadLayout.PointerMode.CAMERA);
            return pointerIdle;
        }

        private PadLayout.Control hit(float x, float y) {
            PadLayout.Control[] controls = PadLayout.Control.values();
            float slop = 8 * density;
            // The last drawn is on top, so it wins.
            for (int i = controls.length - 1; i >= 0; i--) {
                if (!onSurface(controls[i])) continue;
                draft.bounds(controls[i], getWidth(), getHeight(), density, box);
                if (x >= box[0] - slop && x <= box[0] + box[2] + slop && y >= box[1] - slop && y <= box[1] + box[3] + slop) {
                    return controls[i];
                }
            }
            return null;
        }

        /**
         * The picked control's corner handle under ({@code x}, {@code y}), as {@link #RIGHT_CORNER} and
         * {@link #BOTTOM_CORNER} bits, or -1. Each handle takes a 48 dp touch around the corner it is drawn on;
         * inside the control only a corner's quarter counts, so a small control's middle still moves it.
         */
        private int corner(float x, float y) {
            if (selected == null || !onSurface(selected)) return -1;
            draft.bounds(selected, getWidth(), getHeight(), density, box);
            float reach = PadLayout.MIN_TOUCH_DP / 2 * density, outline = PadPainter.OUTLINE_DP * density;
            float insideX = Math.min(reach, box[2] / 4), insideY = Math.min(reach, box[3] / 4);
            boolean inside = x > box[0] && x < box[0] + box[2] && y > box[1] && y < box[1] + box[3];
            int best = -1;
            float bestDistance = Float.MAX_VALUE;
            for (int corner = 0; corner < 4; corner++) {
                boolean right = (corner & RIGHT_CORNER) != 0, bottom = (corner & BOTTOM_CORNER) != 0;
                float cornerX = right ? box[0] + box[2] : box[0], cornerY = bottom ? box[1] + box[3] : box[1];
                float handleX = cornerX + (right ? outline : -outline), handleY = cornerY + (bottom ? outline : -outline);
                if (Math.abs(x - handleX) > reach || Math.abs(y - handleY) > reach) continue;
                if (inside && (Math.abs(x - cornerX) > insideX || Math.abs(y - cornerY) > insideY)) continue;
                float distance = (float) Math.hypot(x - handleX, y - handleY);
                if (distance < bestDistance) {
                    best = corner;
                    bestDistance = distance;
                }
            }
            return best;
        }

        @SuppressLint("ClickableViewAccessibility") // Editing gestures; the panel's buttons are the accessible path.
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    pinching = false;
                    guidesX = guidesY = 0;
                    // A corner handle of the picked control wins over whatever lies under it.
                    resizing = corner(event.getX(), event.getY());
                    if (resizing >= 0) {
                        dragging = false;
                        draft.bounds(selected, getWidth(), getHeight(), density, box);
                        boolean right = (resizing & RIGHT_CORNER) != 0, bottom = (resizing & BOTTOM_CORNER) != 0;
                        // The dragged corner follows the finger from where it took hold; the opposite one stays.
                        offsetX = (right ? box[0] + box[2] : box[0]) - event.getX();
                        offsetY = (bottom ? box[1] + box[3] : box[1]) - event.getY();
                        anchorX = right ? box[0] : box[0] + box[2];
                        anchorY = bottom ? box[1] : box[1] + box[3];
                        break;
                    }
                    PadLayout.Control control = hit(event.getX(), event.getY());
                    select(control);
                    dragging = control != null;
                    if (dragging) {
                        draft.bounds(control, getWidth(), getHeight(), density, box);
                        offsetX = box[0] + box[2] / 2 - event.getX();
                        offsetY = box[1] + box[3] / 2 - event.getY();
                    }
                    break;
                }
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (selected != null && event.getPointerCount() == 2) {
                        pinching = true;
                        dragging = false;
                        resizing = -1;
                        guidesX = guidesY = 0;
                        pinchDistance = distance(event);
                        pinchWidth = draft.spec(selected).widthScale;
                        pinchHeight = draft.spec(selected).heightScale;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (pinching && event.getPointerCount() >= 2 && pinchDistance > 0) {
                        draft.scaleFrom(selected, pinchWidth, pinchHeight, distance(event) / pinchDistance, getWidth(),
                                getHeight(), density);
                        changed();
                    } else if (resizing >= 0 && selected != null) {
                        resize(event.getX() + offsetX, event.getY() + offsetY);
                    } else if (dragging && selected != null) {
                        drag(event.getX() + offsetX, event.getY() + offsetY);
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    // The pinch ends; the remaining finger does not jump the control.
                    pinching = false;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    pinching = false;
                    resizing = -1;
                    guidesX = guidesY = 0;
                    invalidate();
                    keepPanelClear();
                    updatePanel();
                    break;
                default:
                    break;
            }
            return true;
        }

        /**
         * Moves the dragged control's centre to ({@code x}, {@code y}): onto another control's centre or edge line when
         * one is within half a grid step, else onto the grid; then shows the lines it shares with others.
         */
        private void drag(float x, float y) {
            float width = getWidth(), height = getHeight(), grid = GRID_DP * density, tolerance = grid / 2;
            draft.bounds(selected, width, height, density, box);
            float w = box[2], h = box[3];
            int count = otherLines(true);
            float nudge = alignNudge(x - w / 2, x, x + w / 2, lines, count, tolerance);
            x = Float.isNaN(nudge) ? Math.round(x / grid) * grid : x + nudge;
            count = otherLines(false);
            nudge = alignNudge(y - h / 2, y, y + h / 2, lines, count, tolerance);
            y = Float.isNaN(nudge) ? Math.round(y / grid) * grid : y + nudge;
            draft.moveTo(selected, x, y, width, height, density);
            // Where it landed, kept on the surface, decides the guides.
            draft.bounds(selected, width, height, density, box);
            float left = box[0], top = box[1], right = box[0] + box[2], bottom = box[1] + box[3];
            count = otherLines(true);
            guidesX = alignedLines(left, (left + right) / 2, right, lines, count, guideX);
            count = otherLines(false);
            guidesY = alignedLines(top, (top + bottom) / 2, bottom, lines, count, guideY);
            invalidate();
        }

        /** The other shown controls' start, centre and end along x ({@code across}) or y, into {@link #lines}. */
        private int otherLines(boolean across) {
            int count = 0;
            for (PadLayout.Control control : PadLayout.Control.values()) {
                if (control == selected || !onSurface(control)) continue;
                draft.bounds(control, getWidth(), getHeight(), density, other);
                if (lines.length < count + 3) lines = Arrays.copyOf(lines, lines.length * 2);
                float start = across ? other[0] : other[1], size = across ? other[2] : other[3];
                lines[count++] = start;
                lines[count++] = start + size / 2;
                lines[count++] = start + size;
            }
            return count;
        }

        /**
         * Puts the dragged corner at ({@code x}, {@code y}), on the grid or flush with the surface's edge, and sizes
         * the picked control's width and height between it and the opposite corner, which stays: never smaller than
         * a full touch, never past the surface.
         */
        private void resize(float x, float y) {
            float width = getWidth(), height = getHeight();
            float u = PadLayout.unit(width, height, density);
            float leastWide = PadLayout.width(selected, u, density, PadLayout.MIN_SCALE);
            float leastHigh = PadLayout.height(selected, u, density, PadLayout.MIN_SCALE);
            x = snapEdge(x, width);
            y = snapEdge(y, height);
            float left, right, top, bottom;
            if ((resizing & RIGHT_CORNER) != 0) {
                left = anchorX;
                right = Math.max(anchorX + leastWide, Math.min(width, x));
            } else {
                right = anchorX;
                left = Math.min(anchorX - leastWide, Math.max(0, x));
            }
            if ((resizing & BOTTOM_CORNER) != 0) {
                top = anchorY;
                bottom = Math.max(anchorY + leastHigh, Math.min(height, y));
            } else {
                bottom = anchorY;
                top = Math.min(anchorY - leastHigh, Math.max(0, y));
            }
            draft.setBounds(selected, left, top, right - left, bottom - top, width, height, density);
            changed();
        }

        /** A dragged edge lands on the grid, or flush with the surface's edge when that is nearer. */
        private float snapEdge(float position, float size) {
            float grid = GRID_DP * density;
            float snapped = Math.round(position / grid) * grid;
            return Math.abs(size - position) < Math.abs(snapped - position) ? size : snapped;
        }

        private float distance(MotionEvent event) {
            return (float) Math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1));
        }
    }
}
