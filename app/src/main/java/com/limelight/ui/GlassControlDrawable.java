package com.limelight.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;
import com.limelight.R;

/**
 * A flat control inside a glass panel: a tint over the panel, never a blur of its own. Its rim is lit from above as the panel's
 * is. Checked it takes the selected tint; hovered or pressed it lifts. Focused, the one ring of the app's glass stands on the
 * control's edge with a gap inside it, so a panel's clip can never cut it and nothing moves.
 */
final class GlassControlDrawable extends Drawable {
    enum Kind {
        /** A tile, a capsule, a round button. */
        CONTROL,
        /** The one red: Quit game. */
        DANGER,
        /** A row inside a group: no fill until it is touched or focused. */
        ROW,
        /** Rows standing together. Never takes state. */
        GROUP,
        /**
         * A key on a keyboard over the stream: its own opaque fill, so its label reads over any game whatever the panel
         * lets through. Selected (a held modifier) it takes the selected tint.
         */
        KEY
    }

    private final Kind kind;
    private final float radius;
    private final float ringWidth;
    private final float gapWidth;
    private int fillColor;
    private int checkedFill;
    private final int lift;
    private int rimTop, rimMid, rimBottom, checkedRimTop;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean focused, checked, active;

    /** The fill at rest and the rim's top colour: for a test. */
    int restFill() {
        return fillColor;
    }

    int restRimTop() {
        return rimTop;
    }

    GlassControlDrawable(Context context, Kind kind, float radius) {
        this.kind = kind;
        this.radius = radius;
        android.content.res.Resources res = context.getResources();
        boolean highContrast = AppTheme.isHighContrast(context);
        ringWidth = res.getDimension(highContrast ? R.dimen.nj_hc_focus_ring_width : R.dimen.dialog_button_ring);
        gapWidth = res.getDimension(R.dimen.dialog_button_gap) - ringWidth / 2f;
        lift = kind == Kind.ROW ? MaterialColors.getColor(context, androidx.appcompat.R.attr.colorControlHighlight, 0)
                : ResourcesCompat.getColor(res, R.color.nj_focus_fill_lift, null);
        rim.setStyle(Paint.Style.STROKE);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(ringWidth);
        refreshTheme(context);
    }

    void refreshTheme(Context context) {
        android.content.res.Resources res = context.getResources();
        AppThemePalette palette = AppThemePalette.getCurrent(context);
        boolean liveBlur = AppThemePalette.isLiveBlurEffective(context);
        int cardFill = palette.getFrostedCardTint(0x52121826);
        int outline = palette.cardOutline;
        float outlineScale = Math.max(0f, com.limelight.preferences.PreferenceConfiguration
                .readPreferences(context).cardOutlineThicknessPercent / 100f);
        rim.setStrokeWidth(outlineScale <= 0f ? 0f : Math.max(1f, res.getDisplayMetrics().density * outlineScale));
        switch (kind) {
            case DANGER:
                fillColor = ResourcesCompat.getColor(res, R.color.nj_color_danger_fill, null);
                rimTop = rimMid = rimBottom = 0x26FFFFFF;
                break;
            case ROW:
                fillColor = 0;
                rimTop = rimMid = rimBottom = 0;
                break;
            case GROUP:
                fillColor = cardFill;
                rimTop = rimMid = rimBottom = outline;
                break;
            case KEY:
                fillColor = (0xE0 << 24) | (palette.surface & 0x00FFFFFF);
                rimTop = rimMid = rimBottom = outline;
                break;
            default:
                fillColor = cardFill;
                rimTop = rimMid = rimBottom = outline;
                break;
        }
        int selected = (0x5C << 24) | (palette.accent & 0x00FFFFFF);
        checkedFill = kind == Kind.KEY ? ColorUtils.compositeColors(selected, fillColor) : selected;
        checkedRimTop = (0xAA << 24) | (palette.highlight & 0x00FFFFFF);
        ring.setColor(kind == Kind.DANGER ? palette.getPrimaryTextColor() : palette.highlight);
        applyRim();
        invalidateSelf();
    }

    private float corner(float width, float height, float inset) {
        return Math.max(0f, Math.min(radius, Math.min(width, height) / 2f) - inset);
    }

    private void applyRim() {
        Rect b = getBounds();
        int top = checked ? checkedRimTop : rimTop;
        if (b.isEmpty() || (top == 0 && rimMid == 0)) {
            rim.setShader(null);
            return;
        }
        rim.setShader(new LinearGradient(0, b.top, 0, b.bottom, new int[] {top, rimMid, rimBottom},
                new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        applyRim();
    }

    @Override
    public boolean isStateful() {
        return kind != Kind.GROUP;
    }

    /** Says the ring is the focus look: a view lays the system's grey square over a background that has none. */
    @Override
    public boolean hasFocusStateSpecified() {
        return kind != Kind.GROUP;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean nowFocused = false, nowChecked = false, nowActive = false;
        for (int s : state) {
            if (s == android.R.attr.state_focused) nowFocused = true;
            else if (s == android.R.attr.state_checked) nowChecked = true;
            // A key's selection is its modifier held: a button that is not checkable says it so.
            else if (s == android.R.attr.state_selected && kind == Kind.KEY) nowChecked = true;
            else if (s == android.R.attr.state_pressed || s == android.R.attr.state_hovered) nowActive = true;
        }
        if (nowFocused == focused && nowChecked == checked && nowActive == active) return false;
        boolean rimChanged = nowChecked != checked;
        focused = nowFocused;
        checked = nowChecked;
        active = nowActive;
        if (rimChanged) applyRim();
        return true;
    }

    float ringWidth() {
        return ringWidth;
    }

    Kind kind() {
        return kind;
    }

    int fillNow() {
        int base = checked ? checkedFill : fillColor;
        return focused || active ? ColorUtils.compositeColors(lift, base) : base;
    }

    boolean ringShown() {
        return focused;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float inset = focused ? ringWidth + gapWidth : 0f;
        int color = fillNow();
        if (color >>> 24 != 0) {
            fill.setColor(color);
            rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
            float r = corner(b.width(), b.height(), inset);
            canvas.drawRoundRect(rect, r, r, fill);
        }
        if (focused) {
            float half = ringWidth / 2f;
            rect.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
            float r = corner(b.width(), b.height(), half);
            canvas.drawRoundRect(rect, r, r, ring);
        } else if (rim.getShader() != null) {
            float half = rim.getStrokeWidth() / 2f;
            rect.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
            float r = corner(b.width(), b.height(), half);
            canvas.drawRoundRect(rect, r, r, rim);
        }
    }

    @Override
    public void getOutline(Outline outline) {
        Rect b = getBounds();
        outline.setRoundRect(b, corner(b.width(), b.height(), 0f));
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
    }

    /** A button style's tint would paint the control over: its colours are the design's. */
    @Override
    public void setColorFilter(ColorFilter filter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
