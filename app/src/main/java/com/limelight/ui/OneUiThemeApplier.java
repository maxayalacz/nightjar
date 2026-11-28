package com.limelight.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;

import com.limelight.R;
import com.limelight.preferences.GroupedListView;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.UiHelper;

/**
 * Applies the active {@link AppThemePalette} colors and unified One UI frosted glass across
 * every view in a {@link NightjarActivity}, keeping all menus, buttons, cards, pills, and
 * settings sections consistent with Theme Studio.
 */
public final class OneUiThemeApplier {
    private OneUiThemeApplier() { }

    /** Applies active theme colors and frosted glass surfaces across the activity's view tree. */
    public static void applyToActivity(@Nullable Activity activity) {
        if (activity == null || PortalBackgroundHelper.isRobolectric()) return;
        if (activity instanceof ThemeStudioActivity) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        AppThemePalette palette = AppThemePalette.getCurrent(activity);
        PortalShaderView psv = PortalBackgroundHelper.getShaderView(activity);
        applyRecursive(activity, content, palette, psv);
        ShellBar.refresh(activity);
    }

    /** Applies unified One UI frosted card background to a view, backed by the activity's PortalShaderView. */
    public static void applyFrostedCard(
            View view,
            @Nullable PortalShaderView psv,
            FlowingAuroraView.CornerMode cornerMode,
            float radiusDp) {
        Context ctx = view.getContext();
        AppThemePalette palette = AppThemePalette.getCurrent(ctx);
        float radiusPx = UiHelper.dpToPx(ctx, radiusDp);
        int fill = palette.getFrostedCardTint(0x52121826);
        int rim = palette.getCardRimColor();
        float outlineScale = Math.max(0f, PreferenceConfiguration.readPreferences(ctx).cardOutlineThicknessPercent / 100f);
        float strokePx = outlineScale <= 0f ? 0f : Math.max(1f, UiHelper.dpToPx(ctx, 1f) * outlineScale);

        if (psv != null) {
            if (view.getBackground() instanceof FlowingAuroraView.FrostedCardDrawable) {
                FlowingAuroraView.FrostedCardDrawable existing =
                        (FlowingAuroraView.FrostedCardDrawable) view.getBackground();
                existing.updateStyle(cornerMode, radiusPx, fill, null, psv);
                existing.setRimColor(rim);
                existing.setStrokeWidthPx(strokePx);
                view.invalidate();
                return;
            }
            FlowingAuroraView.FrostedCardDrawable card = new FlowingAuroraView.FrostedCardDrawable(
                    view, null, psv, cornerMode, radiusPx, fill, strokePx);
            card.setRimColor(rim);
            view.setBackground(card);
        } else {
            GradientDrawable fallback = new GradientDrawable();
            fallback.setShape(GradientDrawable.RECTANGLE);
            fallback.setCornerRadius(radiusPx);
            fallback.setColor(fill);
            if (strokePx > 0f) {
                fallback.setStroke(Math.round(strokePx), rim);
            }
            view.setBackground(fallback);
        }
    }

    private static void applyRecursive(Activity activity, View view, AppThemePalette palette, @Nullable PortalShaderView psv) {
        if (view instanceof PortalShaderView) return;
        if ("oneui_portal_handoff_bg".equals(view.getTag())) return;

        if (view instanceof GlassButton) {
            ((GlassButton) view).refreshTheme();
            return;
        }
        if (view instanceof GlassTabs) {
            ((GlassTabs) view).refreshTheme();
        }
        if (view instanceof GlassSurface) {
            ((GlassSurface) view).onTierChanged();
        }
        if (view instanceof CoverCard) {
            ((CoverCard) view).refreshTheme(psv);
        }
        if (view instanceof PcRow) {
            ((PcRow) view).refreshTheme(psv);
        }
        if (view instanceof PcCardSurface) {
            ((PcCardSurface) view).refreshTheme(psv);
        }
        int viewId = view.getId();
        if (viewId == R.id.home_setup_card && !(view instanceof GlassSurface)) {
            applyFrostedCard(view, psv, FlowingAuroraView.CornerMode.ALL, 20f);
        } else if (viewId == R.id.detail_sheet || viewId == R.id.detail_profile || viewId == R.id.hero_panel) {
            applyFrostedCard(view, psv, FlowingAuroraView.CornerMode.ALL, 22f);
        } else if (viewId == R.id.hero_chip_state || viewId == R.id.hero_chip_store) {
            applyFrostedCard(view, psv, FlowingAuroraView.CornerMode.ALL, 999f);
        } else if (view instanceof androidx.appcompat.widget.SearchView
                && (viewId == R.id.library_search || viewId == R.id.settings_search)) {
            applyFrostedCard(view, psv, FlowingAuroraView.CornerMode.ALL, 18f);
        } else if (view instanceof com.google.android.material.card.MaterialCardView
                && viewId != R.id.grid_card) {
            com.google.android.material.card.MaterialCardView card =
                    (com.google.android.material.card.MaterialCardView) view;
            card.setCardBackgroundColor(Color.TRANSPARENT);
            card.setStrokeWidth(0);
            card.setCardElevation(0f);
            applyFrostedCard(card, psv, FlowingAuroraView.CornerMode.ALL, 18f);
        }
        if (view instanceof GroupedListView) {
            view.setBackgroundColor(Color.TRANSPARENT);
            if (view instanceof ListView) {
                ((ListView) view).setCacheColorHint(Color.TRANSPARENT);
            }
        }
        if (viewId == R.id.home_foot_fade || viewId == R.id.pc_foot_fade) {
            view.setVisibility(View.GONE);
        }

        // Theme Chips
        if (view instanceof com.google.android.material.chip.Chip) {
            styleChip((com.google.android.material.chip.Chip) view, palette);
            return;
        }

        // Theme plain MaterialButtons (skip GlassButton and buttons already using GlassControlDrawable)
        if (view instanceof com.google.android.material.button.MaterialButton
                && !(view.getBackground() instanceof GlassControlDrawable)) {
            styleMaterialButton((com.google.android.material.button.MaterialButton) view, palette);
            return;
        }

        // Theme switches, checkboxes, sliders, progress bars
        if (view instanceof SwitchCompat) {
            SwitchCompat sw = (SwitchCompat) view;
            ColorStateList thumb = new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{palette.accent, palette.secondaryText});
            ColorStateList track = new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{(0x78 << 24) | (palette.accent & 0x00FFFFFF), (0x44 << 24) | (palette.secondaryText & 0x00FFFFFF)});
            sw.setThumbTintList(thumb);
            sw.setTrackTintList(track);
            if (sw instanceof com.google.android.material.materialswitch.MaterialSwitch) {
                ((com.google.android.material.materialswitch.MaterialSwitch) sw).setTrackDecorationTintList(
                        new ColorStateList(
                                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                                new int[]{palette.highlight, (0x66 << 24) | (palette.secondaryText & 0x00FFFFFF)}));
            }
        } else if (view instanceof CompoundButton) {
            CompoundButton cb = (CompoundButton) view;
            if (cb instanceof com.google.android.material.radiobutton.MaterialRadioButton) {
                ((com.google.android.material.radiobutton.MaterialRadioButton) cb).setUseMaterialThemeColors(false);
            } else if (cb instanceof com.google.android.material.checkbox.MaterialCheckBox) {
                ((com.google.android.material.checkbox.MaterialCheckBox) cb).setUseMaterialThemeColors(false);
            }
            ColorStateList tint = new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{palette.accent, palette.secondaryText});
            cb.setButtonTintList(tint);
        } else if (view instanceof SeekBar) {
            SeekBar sb = (SeekBar) view;
            sb.setProgressTintList(ColorStateList.valueOf(palette.accent));
            sb.setThumbTintList(ColorStateList.valueOf(palette.highlight));
            sb.setProgressBackgroundTintList(ColorStateList.valueOf((0x44 << 24) | (palette.secondaryText & 0x00FFFFFF)));
        } else if (view instanceof ProgressBar) {
            ProgressBar pb = (ProgressBar) view;
            pb.setIndeterminateTintList(ColorStateList.valueOf(palette.accent));
            pb.setProgressTintList(ColorStateList.valueOf(palette.accent));
        }

        // Theme TextViews (preserve destructive red text & GlassButton moon contrast)
        if (view instanceof TextView && !(view instanceof GlassButton)) {
            TextView tv = (TextView) view;
            int current = tv.getCurrentTextColor();
            if (!isDangerRed(current)) {
                boolean isSecondary = isSecondaryText(tv, current);
                tv.setTextColor(isSecondary ? palette.getSecondaryTextColor() : palette.getPrimaryTextColor());
                tv.setHintTextColor(palette.getSecondaryTextColor());
            }
        }

        // Theme tinted monochrome ImageView icons (skip cover art / posters / banners)
        if (view instanceof ImageView && !(view instanceof PosterImageView)) {
            ImageView iv = (ImageView) view;
            if (iv.getImageTintList() != null) {
                iv.setImageTintList(ColorStateList.valueOf(
                        iv.isSelected() ? palette.accent : palette.getPrimaryTextColor()));
            }
        }

        // Refresh GlassControlDrawable / GlassPanelDrawable backgrounds if attached
        Drawable bg = view.getBackground();
        if (bg instanceof GlassControlDrawable) {
            ((GlassControlDrawable) bg).refreshTheme(view.getContext());
        } else if (bg instanceof GlassPanelDrawable) {
            ((GlassPanelDrawable) bg).refreshTheme(view.getContext());
        }

        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyRecursive(activity, vg.getChildAt(i), palette, psv);
            }
        }
    }

    /** Themes a Material Chip with active AppThemePalette colors. */
    public static void styleChip(com.google.android.material.chip.Chip chip, AppThemePalette palette) {
        int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}};
        int checkedFill = AppThemePalette.withAlphaInt(palette.accent, 0x5C);
        int uncheckedFill = palette.getFrostedCardTint(0x42121826);
        chip.setChipBackgroundColor(new ColorStateList(states, new int[]{checkedFill, uncheckedFill}));
        chip.setChipStrokeColor(new ColorStateList(states, new int[]{palette.highlight, palette.getCardRimColor()}));
        chip.setChipStrokeWidth(Math.max(1f, UiHelper.dpToPx(chip.getContext(), 1f)));
        ColorStateList textColors = new ColorStateList(states, new int[]{palette.highlight, palette.getPrimaryTextColor()});
        chip.setTextColor(textColors);
        chip.setChipIconTint(textColors);
        chip.setCloseIconTint(textColors);
        chip.setRippleColor(ColorStateList.valueOf(AppThemePalette.withAlphaInt(palette.accent, 0x33)));
    }

    /** Themes a plain MaterialButton (non-GlassButton) with active AppThemePalette colors. */
    public static void styleMaterialButton(com.google.android.material.button.MaterialButton btn, AppThemePalette palette) {
        int currentText = btn.getCurrentTextColor();
        if (isDangerRed(currentText)) {
            return;
        }
        ColorStateList bgTint = btn.getBackgroundTintList();
        int bgColor = bgTint != null ? bgTint.getDefaultColor() : Color.TRANSPARENT;
        int alpha = Color.alpha(bgColor);
        boolean hasStroke = btn.getStrokeWidth() > 0;
        ColorStateList ripple = ColorStateList.valueOf(AppThemePalette.withAlphaInt(palette.accent, 0x33));
        btn.setRippleColor(ripple);

        if (alpha == 0 && !hasStroke) {
            // Text button
            btn.setTextColor(palette.highlight);
            btn.setIconTint(ColorStateList.valueOf(palette.highlight));
            return;
        }
        if (hasStroke && alpha < 0x80) {
            // Outlined button
            btn.setBackgroundTintList(ColorStateList.valueOf(palette.getFrostedCardTint(0x38121826)));
            btn.setStrokeColor(ColorStateList.valueOf(palette.getCardRimColor()));
            btn.setTextColor(palette.getPrimaryTextColor());
            btn.setIconTint(ColorStateList.valueOf(palette.getPrimaryTextColor()));
            return;
        }
        // Distinguish filled primary (saturated/accent) vs tonal/neutral container
        float[] hsv = new float[3];
        Color.colorToHSV(bgColor | 0xFF000000, hsv);
        boolean isPrimaryFilled = !hasStroke && hsv[1] >= 0.35f;
        if (isPrimaryFilled) {
            btn.setBackgroundTintList(ColorStateList.valueOf(palette.accent));
            int onAccent = androidx.core.graphics.ColorUtils.calculateLuminance(palette.accent) > 0.45
                    ? 0xFF0B0E14 : 0xFFFFFFFF;
            btn.setTextColor(onAccent);
            btn.setIconTint(ColorStateList.valueOf(onAccent));
        } else {
            btn.setBackgroundTintList(ColorStateList.valueOf(palette.getFrostedCardTint(0x52121826)));
            btn.setStrokeColor(ColorStateList.valueOf(palette.getCardRimColor()));
            if (btn.getStrokeWidth() <= 0) {
                btn.setStrokeWidth(Math.max(1, Math.round(UiHelper.dpToPx(btn.getContext(), 1f))));
            }
            btn.setTextColor(palette.getPrimaryTextColor());
            btn.setIconTint(ColorStateList.valueOf(palette.getPrimaryTextColor()));
        }
    }

    private static boolean isDangerRed(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        return r > 180 && g < 110 && b < 110;
    }

    private static boolean isSecondaryText(TextView tv, int currentColor) {
        int id = tv.getId();
        if (id == android.R.id.summary || id == R.id.home_hero_meta || id == R.id.home_hero_hints) {
            return true;
        }
        int alpha = Color.alpha(currentColor);
        if (alpha > 0 && alpha < 210) return true;
        // Detect muted grey secondary text in dark/light themes
        int r = Color.red(currentColor), g = Color.green(currentColor), b = Color.blue(currentColor);
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return (max - min) < 30 && max >= 95 && max <= 200;
    }
}
