package com.limelight.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;

import androidx.core.content.res.ResourcesCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.limelight.R;

/**
 * A capsule button of the glass screens: solid moon for Play (the one thing that is never glass), or the control tint.
 * Focus rings it with the same ring as a cover, ink on the moon; the ring and the glow lie outside its bounds.
 */
public class GlassButton extends MaterialButton {
    private final boolean moon;
    private final FocusRing focusRing;
    private final Paint glow = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final float glowBlur;
    private final float focusScale;
    private final float pressScale;
    private final Runnable invalidator = this::invalidate;

    /** A button that cannot be pressed says so: glass has one fill and one text colour, so the whole capsule dims. */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.45f);
    }

    private final boolean link;

    public GlassButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GlassButton);
        moon = a.getBoolean(R.styleable.GlassButton_glassMoon, false);
        link = a.getBoolean(R.styleable.GlassButton_glassLink, false);
        a.recycle();
        android.content.res.Resources res = context.getResources();
        boolean highContrast = AppTheme.isHighContrast(context);
        glowBlur = moon && !highContrast ? res.getDimension(R.dimen.glass_play_glow) : 0f;
        boolean reduced = Motion.reduced(context);
        focusScale = reduced ? 1f : ResourcesCompat.getFloat(res, R.dimen.nj_focus_scale_control);
        pressScale = reduced ? 1f : 0.97f;
        focusRing = new FocusRing(this, moon);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) setDefaultFocusHighlightEnabled(false);
        refreshTheme();
    }

    /** Re-applies active AppThemePalette colors and One UI frosted glass styling to this button. */
    public void refreshTheme() {
        Context context = getContext();
        android.content.res.Resources res = context.getResources();
        AppThemePalette palette = AppThemePalette.getCurrent(context);
        boolean highContrast = AppTheme.isHighContrast(context);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(res.getDimension(R.dimen.nj_radius_capsule));
        if (moon) {
            shape.setColor(palette.accent);
            int onAccent = androidx.core.graphics.ColorUtils.calculateLuminance(palette.accent) > 0.45
                    ? 0xFF0B0E14 : 0xFFFFFFFF;
            setTextColor(onAccent);
            setIconTint(android.content.res.ColorStateList.valueOf(onAccent));
            glow.setColor((0x66 << 24) | (palette.accent & 0x00FFFFFF));
        } else if (link) {
            shape.setColor(android.graphics.Color.TRANSPARENT);
            setTextColor(palette.accent);
            setIconTint(android.content.res.ColorStateList.valueOf(palette.accent));
        } else {
            boolean liveBlur = AppThemePalette.isLiveBlurEffective(context);
            shape.setColor(palette.getFrostedCardTint(0x52121826));
            float outlineScale = Math.max(0f, com.limelight.preferences.PreferenceConfiguration
                    .readPreferences(context).cardOutlineThicknessPercent / 100f);
            int strokePx = outlineScale <= 0f ? 0 : Math.max(1, Math.round(res.getDisplayMetrics().density * outlineScale));
            if (strokePx > 0) {
                shape.setStroke(strokePx, highContrast ? palette.getPrimaryTextColor() : palette.cardOutline);
            }
            setTextColor(palette.getPrimaryTextColor());
            setIconTint(android.content.res.ColorStateList.valueOf(palette.getPrimaryTextColor()));
        }
        setBackgroundTintList(null);
        setBackground(shape);
        focusRing.refreshTheme(context);
        invalidate();
    }

    /** This control floats over covers with nothing to frost it: the denser fill keeps its words readable on bright art. */
    public void setOverArt() {
        if (getBackground() instanceof GradientDrawable) {
            AppThemePalette palette = AppThemePalette.getCurrent(getContext());
            ((GradientDrawable) getBackground()).setColor((0xC8 << 24) | (palette.surface & 0x00FFFFFF));
        }
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previously) {
        super.onFocusChanged(gainFocus, direction, previously);
        retarget();
    }

    @Override
    public void setPressed(boolean pressed) {
        boolean changed = pressed != isPressed();
        super.setPressed(pressed);
        if (changed) retarget();
    }

    private void retarget() {
        Springs.scale(this, isPressed() ? pressScale : isFocused() ? focusScale : 1f,
                isPressed() ? Springs.Kind.PRESS : Springs.Kind.FOCUS);
    }

    @Override
    public void draw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float r = h / 2f;
        if (glowBlur > 0f) {
            GlassShadow shadow = GlassShadow.get(r, glowBlur, invalidator);
            if (shadow != null) shadow.draw(canvas, 0, 0, w, h, glow);
        }
        if (isFocused()) focusRing.drawHalo(canvas, 0, 0, w, h, r);
        super.draw(canvas);
        if (isFocused()) focusRing.drawRing(canvas, 0, 0, w, h, r);
    }
}
