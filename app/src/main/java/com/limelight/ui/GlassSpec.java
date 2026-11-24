package com.limelight.ui;

import android.content.Context;
import android.content.res.Resources;

import androidx.core.content.res.ResourcesCompat;

import java.util.HashMap;
import java.util.Map;

/** One glass recipe from the design tokens: {@code glass.<material>.<tier>.*}, or {@code day.glass.*} by day. */
final class GlassSpec {
    final float blur;
    final float saturate;
    final float brightness;
    final int fill;
    final int sheenTop;
    final float sheenEnd;
    final int rimTop;
    final int rimMid;
    final int rimBottom;
    final float rimWidth;
    /** A dark outline day glass needs on a white canvas; 0 at night. */
    final int hairline;
    final int shadowColor;
    final float shadowY;
    final float shadowBlur;
    final float lensWidth;
    final float lensBlur;
    final float lensSaturate;
    final float lensBrightness;

    private static final Map<String, GlassSpec> CACHE = new HashMap<>();

    /** Main thread. Each recipe is read once per density. */
    static GlassSpec of(Context context, String material, GlassTier tier, boolean day) {
        Resources res = context.getResources();
        String tierName = tier.name().toLowerCase(java.util.Locale.ROOT);
        // Solid exists at night only: high contrast is always black.
        boolean dayTokens = day && tier != GlassTier.SOLID;
        return of(context, (dayTokens ? "nj_day_glass_" : "nj_glass_") + material + "_" + tierName + "_", dayTokens);
    }

    /** The stream menu over a system blur of its own: that blur has no colour filter, so this recipe has its own fill. */
    static GlassSpec menuWindow(Context context) {
        return of(context, "nj_glass_menu_window_", false);
    }

    /** What stays up over a stream while it plays (statistics, keyboards): see-through, never over a blur. */
    static GlassSpec hud(Context context) {
        return of(context, "nj_glass_menu_hud_", false);
    }

    /** Clears cached GlassSpec entries after Theme Studio or glass preference changes. */
    public static void invalidateCache() {
        CACHE.clear();
    }

    private static GlassSpec of(Context context, String prefix, boolean dayTokens) {
        Resources res = context.getResources();
        com.limelight.preferences.PreferenceConfiguration cfg =
                com.limelight.preferences.PreferenceConfiguration.readPreferences(context);
        AppThemePalette palette = AppThemePalette.getCurrent(context);
        boolean liveBlur = AppThemePalette.isLiveBlurEffective(context);
        String key = prefix + res.getDisplayMetrics().density
                + "|" + cfg.blurAmountPercent + "|" + cfg.blurSaturationPercent
                + "|" + cfg.frostedTintAmountPercent + "|" + cfg.cardOutlineThicknessPercent
                + "|" + liveBlur + "|" + palette.surface + "|" + palette.cardOutline;
        GlassSpec spec = CACHE.get(key);
        if (spec == null) {
            if (CACHE.size() > 64) CACHE.clear();
            spec = new GlassSpec(context, res, context.getPackageName(), prefix, dayTokens, cfg, palette, liveBlur);
            CACHE.put(key, spec);
        }
        return spec;
    }

    private GlassSpec(Context context, Resources res, String pkg, String prefix, boolean day,
                      com.limelight.preferences.PreferenceConfiguration cfg,
                      AppThemePalette palette, boolean liveBlur) {
        float baseBlur = dimen(res, pkg, prefix + "blur");
        float blurScale = liveBlur ? Math.max(0f, Math.min(4f, cfg.blurAmountPercent / 100f)) : 0f;
        blur = baseBlur * blurScale;

        float baseSat = number(res, pkg, prefix + "saturate");
        float satFactor = Math.max(0f, Math.min(3f, cfg.blurSaturationPercent / 100f));
        saturate = baseSat > 0f ? Math.max(0f, baseSat * (satFactor / 1.4f)) : satFactor;

        brightness = number(res, pkg, prefix + "brightness");

        int baseFill = color(res, pkg, prefix + "fill");
        int tintPct = cfg.frostedTintAmountPercent >= 0 ? cfg.frostedTintAmountPercent : palette.frostedTintAmount;
        if (tintPct >= 0) {
            int clamped = Math.max(0, Math.min(100, tintPct));
            int alpha = clamped >= 100 ? 255 : Math.round((clamped / 100f) * 235f);
            fill = (alpha << 24) | (palette.surface & 0x00FFFFFF);
        } else {
            int alpha = (baseFill >>> 24) & 0xFF;
            fill = (alpha << 24) | (palette.surface & 0x00FFFFFF);
        }

        sheenTop = color(res, pkg, prefix + "sheen_top");
        sheenEnd = number(res, pkg, prefix + "sheen_end");

        int baseRimTop = color(res, pkg, prefix + "rim_top");
        int baseRimMid = color(res, pkg, prefix + "rim_mid");
        int baseRimBottom = color(res, pkg, prefix + "rim_bottom");
        int outlineRgb = palette.cardOutline & 0x00FFFFFF;
        int outlineAlpha = (palette.cardOutline >>> 24) & 0xFF;
        if (outlineAlpha > 0) {
            rimTop = (((baseRimTop >>> 24) & 0xFF) << 24) | outlineRgb;
            rimMid = (((baseRimMid >>> 24) & 0xFF) << 24) | outlineRgb;
            rimBottom = (((baseRimBottom >>> 24) & 0xFF) << 24) | outlineRgb;
        } else {
            rimTop = baseRimTop;
            rimMid = baseRimMid;
            rimBottom = baseRimBottom;
        }

        float outlineScale = Math.max(0f, Math.min(4f, cfg.cardOutlineThicknessPercent / 100f));
        rimWidth = dimen(res, pkg, prefix + "rim_width") * outlineScale;
        hairline = day ? color(res, pkg, prefix + "hairline") : 0;
        shadowColor = color(res, pkg, prefix + "shadow_color");
        shadowY = dimen(res, pkg, prefix + "shadow_y");
        shadowBlur = dimen(res, pkg, prefix + "shadow_blur");
        lensWidth = dimen(res, pkg, prefix + "lens_width");
        lensBlur = dimen(res, pkg, prefix + "lens_blur") * blurScale;
        lensSaturate = number(res, pkg, prefix + "lens_saturate") * (satFactor / 1.4f);
        lensBrightness = number(res, pkg, prefix + "lens_brightness");
    }

    private static float dimen(Resources res, String pkg, String name) {
        return res.getDimension(id(res, pkg, name, "dimen"));
    }

    private static int color(Resources res, String pkg, String name) {
        return ResourcesCompat.getColor(res, id(res, pkg, name, "color"), null);
    }

    private static float number(Resources res, String pkg, String name) {
        return ResourcesCompat.getFloat(res, id(res, pkg, name, "dimen"));
    }

    private static int id(Resources res, String pkg, String name, String type) {
        int id = res.getIdentifier(name, type, pkg);
        if (id == 0) throw new Resources.NotFoundException(name);
        return id;
    }
}
