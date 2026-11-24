package com.limelight.utils;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import com.limelight.ui.NavigationDialogBuilder;
import android.app.GameManager;
import android.app.GameState;
import android.app.LocaleManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.LocaleList;
import android.view.View;
import android.view.WindowManager;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.ui.AdaptiveLayout;
import com.limelight.ui.UiNavigation;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.Locale;

public class UiHelper {

    public static void showDialog(Context context, android.app.Dialog dialog) {
        // Native preferences no longer own the lifecycle of their Material dialogs.
        androidx.lifecycle.Lifecycle lifecycle = ((androidx.lifecycle.LifecycleOwner) context).getLifecycle();
        androidx.lifecycle.LifecycleEventObserver observer = (owner, event) -> {
            if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) dialog.dismiss();
        };
        dialog.setOnDismissListener(ignored -> lifecycle.removeObserver(observer));
        lifecycle.addObserver(observer);
        // Pad the content before the window first lays out. Padding it after show() resized the
        // dialog mid-animation (the 24dp TV margin), so it looked like it opened twice.
        dialog.create();
        AdaptiveLayout.attach((Activity) context, dialog.findViewById(android.R.id.content));
        if (!com.limelight.ui.PortalBackgroundHelper.isRobolectric()) {
            styleDialogSquircle(dialog);
        }
        dialog.show();
        if (!com.limelight.ui.PortalBackgroundHelper.isRobolectric()) {
            styleDialogSquircle(dialog);
        }
    }

    private static void setGameModeStatus(Context context, boolean streaming, boolean interruptible) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                GameManager gameManager = context.getSystemService(GameManager.class);
                if (gameManager == null) {
                    return; // Not supported on this device (e.g., Meta Quest)
                }

                if (streaming) {
                    gameManager.setGameState(new GameState(false, interruptible ? GameState.MODE_GAMEPLAY_INTERRUPTIBLE : GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE));
                } else {
                    gameManager.setGameState(new GameState(false, GameState.MODE_NONE));
                }
            } catch (Throwable t) {
                // Swallow any failure. Some OEM builds ship partial/incompatible GameManager impls.
            }
        }
    }

    public static void notifyStreamConnecting(Context context) {
        setGameModeStatus(context, true, true);
    }

    public static void notifyStreamConnected(Context context) {
        setGameModeStatus(context, true, false);
    }

    public static void notifyStreamEnteringPiP(Context context) {
        setGameModeStatus(context, true, true);
    }

    public static void notifyStreamExitingPiP(Context context) {
        setGameModeStatus(context, true, false);
    }

    public static void notifyStreamEnded(Context context) {
        setGameModeStatus(context, false, false);
    }

    public static void setLocale(Activity activity)
    {
        String locale = PreferenceConfiguration.readPreferences(activity).language;
        if (!locale.equals(PreferenceConfiguration.DEFAULT_LANGUAGE)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // On Android 13, migrate this non-default language setting into the OS native API
                LocaleManager localeManager = activity.getSystemService(LocaleManager.class);
                localeManager.setApplicationLocales(LocaleList.forLanguageTags(locale));
                PreferenceConfiguration.completeLanguagePreferenceMigration(activity);
            }
            else {
                Configuration config = new Configuration(activity.getResources().getConfiguration());

                // Some locales include both language and country which must be separated
                // before calling the Locale constructor.
                if (locale.contains("-"))
                {
                    config.locale = new Locale(locale.substring(0, locale.indexOf('-')),
                            locale.substring(locale.indexOf('-') + 1));
                }
                else
                {
                    config.locale = new Locale(locale);
                }

                activity.getResources().updateConfiguration(config, activity.getResources().getDisplayMetrics());
            }
        }
    }

    public static Context localeContext(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context;
        String language = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .getString("list_languages", PreferenceConfiguration.DEFAULT_LANGUAGE);
        if (PreferenceConfiguration.DEFAULT_LANGUAGE.equals(language)) return context;
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(language));
        return context.createConfigurationContext(config);
    }

    public static void notifyNewRootView(final Activity activity)
    {
        View rootView = activity.findViewById(android.R.id.content);

        // Set GameState.MODE_NONE initially for all activities
        setGameModeStatus(activity, false, false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Allow this non-streaming activity to layout under notches.
            //
            // We should NOT do this for the Game activity unless
            // the user specifically opts in, because it can obscure
            // parts of the streaming surface.
            activity.getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS |
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        boolean oledBlack = com.limelight.ui.AppTheme.isOledBlack(activity);
        int fallbackBarColor = oledBlack ? Color.BLACK : Color.rgb(17, 26, 31);
        activity.getWindow().setStatusBarColor(Color.TRANSPARENT);
        activity.getWindow().setNavigationBarColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                Color.TRANSPARENT : fallbackBarColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.getWindow().setStatusBarContrastEnforced(false);
            activity.getWindow().setNavigationBarContrastEnforced(false);
            rootView.setForceDarkAllowed(false);
        }
        boolean light = !com.limelight.ui.AppTheme.isDark(activity) &&
                activity.getResources().getBoolean(R.bool.light_system_bars);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(activity.getWindow(), rootView);
        controller.setAppearanceLightStatusBars(light);
        controller.setAppearanceLightNavigationBars(light);

        if (rootView.getTag(R.id.adaptive_layout) == null) UiNavigation.install(activity, rootView);
        AdaptiveLayout.attach(activity, rootView);
    }

    public static void showDecoderCrashDialog(Activity activity) {
        final SharedPreferences prefs = activity.getSharedPreferences("DecoderTombstone", 0);
        final int crashCount = prefs.getInt("CrashCount", 0);
        int lastNotifiedCrashCount = prefs.getInt("LastNotifiedCrashCount", 0);

        // Remember the last crash count we notified at, so we don't
        // display the crash dialog every time the app is started until
        // they stream again
        if (crashCount != 0 && crashCount != lastNotifiedCrashCount) {
            if (crashCount % 3 == 0) {
                // At 3 consecutive crashes, we'll forcefully reset their settings
                PreferenceConfiguration.resetStreamingSettings(activity);
                Dialog.displayDialog(activity,
                        activity.getResources().getString(R.string.title_decoding_reset),
                        activity.getResources().getString(R.string.message_decoding_reset),
                        new Runnable() {
                            @Override
                            public void run() {
                                // Mark notification as acknowledged on dismissal
                                prefs.edit().putInt("LastNotifiedCrashCount", crashCount).apply();
                            }
                        });
            }
            else {
                Dialog.displayDialog(activity,
                        activity.getResources().getString(R.string.title_decoding_error),
                        activity.getResources().getString(R.string.message_decoding_error),
                        new Runnable() {
                            @Override
                            public void run() {
                                // Mark notification as acknowledged on dismissal
                                prefs.edit().putInt("LastNotifiedCrashCount", crashCount).apply();
                            }
                        });
            }
        }
    }

    public static void displayQuitConfirmationDialog(Activity parent, final Runnable onYes, final Runnable onNo) {
        DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which){
                    case DialogInterface.BUTTON_POSITIVE:
                        if (onYes != null) {
                            onYes.run();
                        }
                        break;

                    case DialogInterface.BUTTON_NEGATIVE:
                        if (onNo != null) {
                            onNo.run();
                        }
                        break;
                }
            }
        };

        // Cancel has the first focus: two A presses must not lose the game's unsaved progress.
        AlertDialog.Builder builder = new NavigationDialogBuilder(parent).startOnCancel();
        AlertDialog dialog = builder.setMessage(parent.getResources().getString(R.string.applist_quit_confirmation))
                .setPositiveButton(parent.getResources().getString(R.string.applist_menu_quit), dialogClickListener)
                .setNegativeButton(parent.getResources().getString(android.R.string.cancel), dialogClickListener)
                .setOnCancelListener(ignored -> { if (onNo != null) onNo.run(); })
                .show();
        markDestructive(dialog);
    }

    public static void displayDeletePcConfirmationDialog(Activity parent, ComputerDetails computer, final Runnable onYes, final Runnable onNo) {
        DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which){
                    case DialogInterface.BUTTON_POSITIVE:
                        if (onYes != null) {
                            onYes.run();
                        }
                        break;

                    case DialogInterface.BUTTON_NEGATIVE:
                        if (onNo != null) {
                            onNo.run();
                        }
                        break;
                }
            }
        };

        AlertDialog.Builder builder = new NavigationDialogBuilder(parent);
        AlertDialog dialog = builder.setMessage(parent.getResources().getString(R.string.delete_pc_msg))
                .setTitle(computer.displayName())
                .setPositiveButton(parent.getResources().getString(R.string.pcview_menu_delete_pc), dialogClickListener)
                .setNegativeButton(android.R.string.cancel, dialogClickListener)
                .setOnCancelListener(ignored -> { if (onNo != null) onNo.run(); })
                .show();
        markDestructive(dialog);
    }

    /**
     * A shown confirmation's positive button that removes or resets something: the one red, in place of the moon.
     */
    public static void markDestructive(AlertDialog dialog) {
        android.widget.Button confirm = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        confirm.setBackgroundResource(R.drawable.dialog_button_danger);
        // A pad's first press must not be the one that destroys: focus starts on the way out, not on the red.
        android.view.View safe = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (android.os.Build.VERSION.SDK_INT >= 26 && safe != null) {
            confirm.setFocusedByDefault(false);
            safe.setFocusedByDefault(true);
            if (confirm.isFocused()) safe.requestFocus();
        }
        confirm.setTextColor(androidx.core.content.ContextCompat.getColor(dialog.getContext(), R.color.nj_color_on_danger_fill));
    }

    public static float dpToPx(Context context, float dp) {
        return dp * context.getResources().getDisplayMetrics().density;
    }

    public static float getCardOutlineWidthPx(Context context, float baseDp) {
        float scale = Math.max(0f, PreferenceConfiguration.readPreferences(context).cardOutlineThicknessPercent / 100f);
        return scale <= 0f ? 0f : Math.max(1f, dpToPx(context, baseDp) * scale);
    }

    public static Activity findActivity(Context context) {
        return com.limelight.ui.PortalBackgroundHelper.activityOf(context);
    }

    public static android.view.SurfaceView findStreamSurfaceView(Activity activity) {
        if (activity == null) return null;
        View v = activity.findViewById(R.id.surfaceView);
        if (v instanceof com.limelight.ui.StreamView) {
            return ((com.limelight.ui.StreamView) v).getSurfaceView();
        }
        return v instanceof android.view.SurfaceView ? (android.view.SurfaceView) v : null;
    }

    public static android.view.TextureView findStreamTextureView(Activity activity) {
        if (activity == null) return null;
        View v = activity.findViewById(R.id.surfaceView);
        if (v instanceof com.limelight.ui.StreamView) {
            return ((com.limelight.ui.StreamView) v).getTextureView();
        }
        return v instanceof android.view.TextureView ? (android.view.TextureView) v : null;
    }

    public static View getMenuForegroundView(Activity activity) {
        if (activity == null) return null;
        View rootView = activity.findViewById(android.R.id.content);
        if (!(rootView instanceof android.widget.FrameLayout)) return null;
        android.widget.FrameLayout contentFrame = (android.widget.FrameLayout) rootView;
        for (int i = 0; i < contentFrame.getChildCount(); i++) {
            View child = contentFrame.getChildAt(i);
            if (child != null
                    && !"oneui_flowing_aurora".equals(child.getTag())
                    && !"oneui_portal_starfield_bg".equals(child.getTag())
                    && !"oneui_portal_handoff_bg".equals(child.getTag())
                    && !"oneui_portal_overlay".equals(child.getTag())) {
                return child;
            }
        }
        return null;
    }

    public static void disableAllActivityTransitions(Activity activity) {
        if (activity == null) return;
        disableActivityTransition(activity, false);
        disableActivityTransition(activity, true);
    }

    @SuppressWarnings("deprecation")
    public static void disableActivityTransition(Activity activity, boolean close) {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(
                    close ? Activity.OVERRIDE_TRANSITION_CLOSE : Activity.OVERRIDE_TRANSITION_OPEN,
                    0, 0);
        } else {
            activity.overridePendingTransition(0, 0);
        }
    }

    public static void applyMenuImmersiveMode(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                activity.getWindow(), activity.getWindow().getDecorView());
        if (controller != null) {
            com.limelight.ui.AppThemePalette palette = com.limelight.ui.AppThemePalette.getCurrent(activity);
            controller.setAppearanceLightStatusBars(palette.isLight);
            controller.setAppearanceLightNavigationBars(palette.isLight);
        }
    }

    private static final float ONEUI_DIALOG_SQUIRCLE_RADIUS_DP = 28f;
    private static final float ONEUI_BLUR_RADIUS_DP = 28f;
    private static final int TAG_WINDOW_BLUR_LISTENER = 0x7F0A1201;
    private static final int TAG_DIALOG_BACKDROP_BLUR = 0x7F0A1202;

    public static android.graphics.drawable.GradientDrawable createDialogSquircleDrawable(
            Context context, boolean blurred, float[] cornerRadiiPx) {
        com.limelight.ui.AppThemePalette palette = com.limelight.ui.AppThemePalette.getCurrent(context);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        if (cornerRadiiPx != null && cornerRadiiPx.length == 8) {
            bg.setCornerRadii(cornerRadiiPx);
        } else {
            bg.setCornerRadius(dpToPx(context, ONEUI_DIALOG_SQUIRCLE_RADIUS_DP));
        }
        bg.setColor(palette.getDialogSurfaceColor(blurred));
        int strokePx = Math.round(getCardOutlineWidthPx(context, 1.15f));
        if (strokePx > 0) {
            bg.setStroke(strokePx, palette.getCardRimColor());
        }
        return bg;
    }

    public static void styleDialogSquircle(android.app.Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        if (com.limelight.ui.PortalBackgroundHelper.isRobolectric()) return;
        Context context = dialog.getContext();
        android.view.Window window = dialog.getWindow();
        window.setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);

        View decorView = window.getDecorView();
        decorView.setClipToOutline(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setBackgroundDrawable(createDialogSquircleDrawable(context, true, null));
            decorView.setRenderEffect(null);
            applyBoundedWindowBlur(window, context, null);
        } else {
            window.setBackgroundDrawable(createDialogSquircleDrawable(context, false, null));
        }
        clearOpaquePanelBackgrounds(decorView);
        attachDialogBackdropBlur(dialog, decorView);
        applyDialogTextPalette(dialog, decorView);
    }

    private static void applyDialogTextPalette(android.app.Dialog dialog, View decorView) {
        if (dialog == null || decorView == null) return;
        Context ctx = dialog.getContext();
        com.limelight.ui.AppThemePalette palette = com.limelight.ui.AppThemePalette.getCurrent(ctx);
        int primaryText = palette.getPrimaryTextColor();
        int secondaryText = palette.getSecondaryTextColor();
        int accent = palette.getNeonAccent();

        int alertTitleId = ctx.getResources().getIdentifier("alertTitle", "id", ctx.getPackageName());
        int androidTitleId = ctx.getResources().getIdentifier("alertTitle", "id", "android");
        int[] titleIds = { alertTitleId, androidTitleId, android.R.id.title };
        for (int id : titleIds) {
            if (id != 0) {
                View v = decorView.findViewById(id);
                if (v instanceof android.widget.TextView) {
                    ((android.widget.TextView) v).setTextColor(primaryText);
                }
            }
        }
        View msg = decorView.findViewById(android.R.id.message);
        if (msg instanceof android.widget.TextView) {
            ((android.widget.TextView) msg).setTextColor(secondaryText);
        }

        View list = decorView.findViewById(android.R.id.list);
        if (!(list instanceof android.widget.ListView)) {
            int selListId = ctx.getResources().getIdentifier("select_dialog_listview", "id", ctx.getPackageName());
            if (selListId != 0) {
                list = decorView.findViewById(selListId);
            }
        }
        if (list instanceof android.widget.ListView) {
            android.widget.ListView lv = (android.widget.ListView) list;
            lv.setDivider(new android.graphics.drawable.ColorDrawable(palette.getDividerColor()));
            lv.setDividerHeight(Math.max(1, Math.round(dpToPx(ctx, 0.75f))));
            lv.setSelector(createThemedRowSelector(ctx, palette));
            tintDialogChildrenRecursive(lv, palette);
            lv.setOnHierarchyChangeListener(new android.view.ViewGroup.OnHierarchyChangeListener() {
                @Override
                public void onChildViewAdded(View parent, View child) {
                    tintDialogChildrenRecursive(child, palette);
                }

                @Override
                public void onChildViewRemoved(View parent, View child) {}
            });
        }

        // Theme any custom views, EditTexts, SeekBars, StepSliders, CheckBoxes, RadioButtons inside the dialog
        View customPanel = decorView.findViewById(androidx.appcompat.R.id.customPanel);
        if (customPanel != null) {
            tintDialogChildrenRecursive(customPanel, palette);
        }
        View contentPanel = decorView.findViewById(androidx.appcompat.R.id.contentPanel);
        if (contentPanel != null) {
            tintDialogChildrenRecursive(contentPanel, palette);
        }
        View edit = decorView.findViewById(android.R.id.edit);
        if (edit instanceof android.widget.EditText) {
            android.widget.EditText et = (android.widget.EditText) edit;
            et.setTextColor(primaryText);
            et.setHintTextColor(secondaryText);
            et.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));
        }
    }

    public static android.graphics.drawable.Drawable createThemedRowSelector(
            Context ctx, com.limelight.ui.AppThemePalette palette) {
        float r = dpToPx(ctx, 14f);
        android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();

        android.graphics.drawable.GradientDrawable activeBg = new android.graphics.drawable.GradientDrawable();
        activeBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        activeBg.setCornerRadius(r);
        activeBg.setColor((0x38 << 24) | (palette.accent & 0x00FFFFFF));
        activeBg.setStroke(Math.max(1, Math.round(dpToPx(ctx, 1.25f))), palette.highlight);

        android.graphics.drawable.GradientDrawable pressedBg = new android.graphics.drawable.GradientDrawable();
        pressedBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        pressedBg.setCornerRadius(r);
        pressedBg.setColor((0x42 << 24) | (palette.accent & 0x00FFFFFF));

        sld.addState(new int[]{android.R.attr.state_pressed}, pressedBg);
        sld.addState(new int[]{android.R.attr.state_focused}, activeBg);
        sld.addState(new int[]{android.R.attr.state_selected}, activeBg);
        sld.addState(new int[]{android.R.attr.state_hovered}, pressedBg);
        sld.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        return sld;
    }

    private static void tintDialogChildrenRecursive(View view, com.limelight.ui.AppThemePalette palette) {
        if (view == null) return;
        int primaryText = palette.getPrimaryTextColor();
        int secondaryText = palette.getSecondaryTextColor();
        int accent = palette.getNeonAccent();
        android.content.res.ColorStateList checkTint = new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{accent, secondaryText});

        if (view instanceof android.widget.CheckedTextView) {
            android.widget.CheckedTextView ctv = (android.widget.CheckedTextView) view;
            ctv.setTextColor(primaryText);
            ctv.setCheckMarkTintList(checkTint);
        } else if (view instanceof androidx.appcompat.widget.SwitchCompat) {
            androidx.appcompat.widget.SwitchCompat sw = (androidx.appcompat.widget.SwitchCompat) view;
            sw.setTextColor(primaryText);
            sw.setThumbTintList(new android.content.res.ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{palette.highlight, secondaryText}));
            sw.setTrackTintList(new android.content.res.ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{(0x78 << 24) | (palette.accent & 0x00FFFFFF), (0x44 << 24) | (secondaryText & 0x00FFFFFF)}));
        } else if (view instanceof android.widget.CompoundButton) {
            android.widget.CompoundButton cb = (android.widget.CompoundButton) view;
            cb.setTextColor(primaryText);
            cb.setButtonTintList(checkTint);
        } else if (view instanceof com.google.android.material.slider.Slider) {
            com.google.android.material.slider.Slider sl = (com.google.android.material.slider.Slider) view;
            sl.setThumbTintList(android.content.res.ColorStateList.valueOf(palette.highlight));
            sl.setTrackActiveTintList(android.content.res.ColorStateList.valueOf(palette.accent));
            sl.setTrackInactiveTintList(android.content.res.ColorStateList.valueOf(
                    (0x44 << 24) | (secondaryText & 0x00FFFFFF)));
            sl.setHaloTintList(android.content.res.ColorStateList.valueOf(
                    (0x55 << 24) | (palette.accent & 0x00FFFFFF)));
        } else if (view instanceof android.widget.SeekBar) {
            android.widget.SeekBar sb = (android.widget.SeekBar) view;
            sb.setProgressTintList(android.content.res.ColorStateList.valueOf(palette.accent));
            sb.setThumbTintList(android.content.res.ColorStateList.valueOf(palette.highlight));
            sb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    (0x44 << 24) | (secondaryText & 0x00FFFFFF)));
        } else if (view instanceof android.widget.EditText) {
            android.widget.EditText et = (android.widget.EditText) view;
            et.setTextColor(primaryText);
            et.setHintTextColor(secondaryText);
            et.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));
        } else if (view instanceof android.widget.TextView && !(view instanceof android.widget.Button)) {
            android.widget.TextView tv = (android.widget.TextView) view;
            if (tv.getId() != android.R.id.message) {
                tv.setTextColor(primaryText);
            }
        }

        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                tintDialogChildrenRecursive(vg.getChildAt(i), palette);
            }
        }
    }

    private static void attachDialogBackdropBlur(final android.app.Dialog dialog, final View decorView) {
        if (!(decorView instanceof android.view.ViewGroup)) return;
        final Activity activity = findActivity(dialog.getContext());
        if (activity == null || activity.isFinishing() || activity.getWindow() == null) return;
        if (decorView.getTag(TAG_DIALOG_BACKDROP_BLUR) != null) return;
        decorView.setTag(TAG_DIALOG_BACKDROP_BLUR, Boolean.TRUE);

        final Runnable captureAndApply = () -> captureDialogBackdrop(activity, decorView);
        if (decorView.getWidth() > 0 && decorView.getHeight() > 0) {
            decorView.post(captureAndApply);
        } else {
            decorView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (right - left > 0 && bottom - top > 0) {
                        v.removeOnLayoutChangeListener(this);
                        v.post(captureAndApply);
                    }
                }
            });
        }
    }

    /** Public hook so ActionSheet panels over a stream can also use the exact same live stream backdrop blur. */
    public static void attachPanelBackdropBlur(Activity activity, View panelRoot, float cornerRadiusPx) {
        if (activity == null || panelRoot == null || com.limelight.ui.PortalBackgroundHelper.isRobolectric()) return;
        if (!(panelRoot instanceof android.view.ViewGroup)) return;
        if (panelRoot.getTag(TAG_DIALOG_BACKDROP_BLUR) != null) return;
        panelRoot.setTag(TAG_DIALOG_BACKDROP_BLUR, Boolean.TRUE);
        panelRoot.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadiusPx);
            }
        });
        panelRoot.setClipToOutline(true);
        Runnable start = () -> captureDialogBackdrop(activity, panelRoot);
        if (panelRoot.getWidth() > 0 && panelRoot.getHeight() > 0) {
            panelRoot.post(start);
        } else {
            panelRoot.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    if (r - l > 0 && b - t > 0) {
                        v.removeOnLayoutChangeListener(this);
                        v.post(start);
                    }
                }
            });
        }
    }

    private static void captureDialogBackdrop(final Activity activity, final View decorView) {
        if (activity.isFinishing() || !decorView.isAttachedToWindow()) return;
        if (!com.limelight.ui.AppThemePalette.isLiveBlurEffective(activity)) return;

        final android.view.SurfaceView surfaceView = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? findStreamSurfaceView(activity) : null;
        final android.view.TextureView textureView = (surfaceView == null)
                ? findStreamTextureView(activity) : null;

        final boolean[] stopped = { false };
        final boolean[] inFlight = { false };
        final android.graphics.Bitmap[] buffers = new android.graphics.Bitmap[2];
        final int[] bufferIndex = { 0 };
        final int[] bufferSize = { 0, 0 };
        final long[] lastCaptureMs = { 0L };
        final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

        final int blurPct = Math.max(0, Math.min(2000,
                PreferenceConfiguration.readPreferences(activity).blurAmountPercent));
        final float captureScale = blurPct == 0 ? 0.50f
                : (blurPct <= 35 ? 0.42f : (blurPct <= 80 ? 0.30f : 0.22f));

        final Runnable ensureBuffers = () -> {
            int dialogW = decorView.getWidth();
            int dialogH = decorView.getHeight();
            if (dialogW <= 0 || dialogH <= 0) return;
            int bw = Math.max(1, Math.round(dialogW * captureScale));
            int bh = Math.max(1, Math.round(dialogH * captureScale));
            if (buffers[0] == null || bufferSize[0] != bw || bufferSize[1] != bh) {
                buffers[0] = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
                buffers[1] = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
                bufferSize[0] = bw;
                bufferSize[1] = bh;
            }
        };

        if ((surfaceView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) || textureView != null) {
            final Runnable[] tickHolder = new Runnable[1];
            tickHolder[0] = () -> {
                if (stopped[0] || activity.isFinishing() || !decorView.isAttachedToWindow()) return;
                View videoView = surfaceView != null ? surfaceView : textureView;
                boolean ready = surfaceView != null
                        ? (surfaceView.getWidth() > 0 && surfaceView.getHeight() > 0
                            && surfaceView.getHolder().getSurface().isValid())
                        : (textureView != null && textureView.isAvailable()
                            && textureView.getWidth() > 0 && textureView.getHeight() > 0);
                if (inFlight[0] || !ready) {
                    mainHandler.postDelayed(tickHolder[0], 24L);
                    return;
                }
                int dialogW = decorView.getWidth();
                int dialogH = decorView.getHeight();
                if (dialogW <= 0 || dialogH <= 0) {
                    mainHandler.postDelayed(tickHolder[0], 24L);
                    return;
                }
                ensureBuffers.run();
                final int idx = bufferIndex[0];
                final android.graphics.Bitmap target = buffers[idx];
                int[] dialogLoc = new int[2];
                int[] svLoc = new int[2];
                decorView.getLocationOnScreen(dialogLoc);
                videoView.getLocationOnScreen(svLoc);

                int svW = videoView.getWidth();
                int svH = videoView.getHeight();
                int interLeft = Math.max(svLoc[0], dialogLoc[0]);
                int interTop = Math.max(svLoc[1], dialogLoc[1]);
                int interRight = Math.min(svLoc[0] + svW, dialogLoc[0] + dialogW);
                int interBottom = Math.min(svLoc[1] + svH, dialogLoc[1] + dialogH);
                if (interRight <= interLeft || interBottom <= interTop) {
                    mainHandler.postDelayed(tickHolder[0], 24L);
                    return;
                }

                if (surfaceView == null) {
                    // TextureView capture path
                    try {
                        android.graphics.Bitmap full = textureView.getBitmap(
                                Math.max(32, Math.round(svW * captureScale)),
                                Math.max(32, Math.round(svH * captureScale)));
                        if (full != null) {
                            target.eraseColor(Color.BLACK);
                            float sx = full.getWidth() / (float) svW;
                            float sy = full.getHeight() / (float) svH;
                            android.graphics.Rect srcR = new android.graphics.Rect(
                                    Math.max(0, Math.round((interLeft - svLoc[0]) * sx)),
                                    Math.max(0, Math.round((interTop - svLoc[1]) * sy)),
                                    Math.min(full.getWidth(), Math.round((interRight - svLoc[0]) * sx)),
                                    Math.min(full.getHeight(), Math.round((interBottom - svLoc[1]) * sy)));
                            android.graphics.RectF dstR = new android.graphics.RectF(
                                    (interLeft - dialogLoc[0]) * captureScale,
                                    (interTop - dialogLoc[1]) * captureScale,
                                    (interRight - dialogLoc[0]) * captureScale,
                                    (interBottom - dialogLoc[1]) * captureScale);
                            new android.graphics.Canvas(target).drawBitmap(full, srcR, dstR, null);
                            full.recycle();
                            applyBitmapToDialogBackdrop(decorView, target, blurPct);
                            bufferIndex[0] = 1 - idx;
                        }
                    } catch (Throwable ignored) { }
                    if (!stopped[0] && decorView.isAttachedToWindow()) {
                        mainHandler.postDelayed(tickHolder[0], 24L);
                    }
                    return;
                }

                android.graphics.Rect surfaceFrame = surfaceView.getHolder().getSurfaceFrame();
                int bufW = (surfaceFrame != null && surfaceFrame.width() > 0) ? surfaceFrame.width() : svW;
                int bufH = (surfaceFrame != null && surfaceFrame.height() > 0) ? surfaceFrame.height() : svH;
                float scaleX = bufW / (float) svW;
                float scaleY = bufH / (float) svH;
                android.graphics.Rect srcRect = new android.graphics.Rect(
                        Math.max(0, Math.round((interLeft - svLoc[0]) * scaleX)),
                        Math.max(0, Math.round((interTop - svLoc[1]) * scaleY)),
                        Math.min(bufW, Math.round((interRight - svLoc[0]) * scaleX)),
                        Math.min(bufH, Math.round((interBottom - svLoc[1]) * scaleY)));
                if (srcRect.isEmpty()) {
                    mainHandler.postDelayed(tickHolder[0], 24L);
                    return;
                }

                final boolean fullCoverage = (interLeft == dialogLoc[0] && interTop == dialogLoc[1]
                        && interRight == dialogLoc[0] + dialogW && interBottom == dialogLoc[1] + dialogH);
                final int subW = Math.max(1, Math.round((interRight - interLeft) * captureScale));
                final int subH = Math.max(1, Math.round((interBottom - interTop) * captureScale));
                final android.graphics.Bitmap copyDest = fullCoverage
                        ? target
                        : android.graphics.Bitmap.createBitmap(subW, subH, android.graphics.Bitmap.Config.ARGB_8888);
                final float dstLeft = (interLeft - dialogLoc[0]) * captureScale;
                final float dstTop = (interTop - dialogLoc[1]) * captureScale;

                inFlight[0] = true;
                try {
                    android.view.PixelCopy.request(
                            surfaceView, srcRect, copyDest, result -> {
                                inFlight[0] = false;
                                if (!stopped[0] && result == android.view.PixelCopy.SUCCESS
                                        && decorView.isAttachedToWindow()) {
                                    if (!fullCoverage) {
                                        target.eraseColor(Color.BLACK);
                                        new android.graphics.Canvas(target).drawBitmap(copyDest, dstLeft, dstTop, null);
                                        copyDest.recycle();
                                    }
                                    applyBitmapToDialogBackdrop(decorView, target, blurPct);
                                    bufferIndex[0] = 1 - idx;
                                } else if (!fullCoverage) {
                                    copyDest.recycle();
                                }
                                if (!stopped[0] && decorView.isAttachedToWindow()) {
                                    mainHandler.postDelayed(tickHolder[0], 20L);
                                }
                            }, mainHandler);
                } catch (IllegalArgumentException | IllegalStateException ignored) {
                    inFlight[0] = false;
                    if (!fullCoverage) copyDest.recycle();
                }
            };

            decorView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) { }
                @Override
                public void onViewDetachedFromWindow(View v) {
                    stopped[0] = true;
                    mainHandler.removeCallbacks(tickHolder[0]);
                    android.widget.ImageView backdrop = decorView.findViewWithTag("oneui_dialog_blur_backdrop");
                    if (backdrop != null) backdrop.setImageDrawable(null);
                    v.removeOnAttachStateChangeListener(this);
                }
            });
            tickHolder[0].run();
            return;
        }

        // Menu screens: when API 31+ cross-window blur is active (or blurPct == 0), let SurfaceFlinger
        // composite the live themed PortalShaderView + foreground UI directly through the translucent squircle window.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            WindowManager wm = activity.getSystemService(WindowManager.class);
            if (blurPct == 0 || (wm != null && wm.isCrossWindowBlurEnabled())) {
                return;
            }
        }

        // Fallback for devices where cross-window blur is disabled or API < 31:
        // composite PortalShaderView blurred backdrop + menu foreground UI into the dialog card!
        final View activityDecor = activity.getWindow().getDecorView();
        final Runnable captureActivityFrame = () -> {
            if (stopped[0] || activity.isFinishing() || !decorView.isAttachedToWindow()) return;
            if (activityDecor.getWidth() <= 0 || activityDecor.getHeight() <= 0
                    || decorView.getWidth() <= 0 || decorView.getHeight() <= 0) return;
            ensureBuffers.run();
            int idx = bufferIndex[0];
            android.graphics.Bitmap target = buffers[idx];
            int[] dialogLoc = new int[2];
            decorView.getLocationOnScreen(dialogLoc);

            com.limelight.ui.AppThemePalette palette = com.limelight.ui.AppThemePalette.getCurrent(activity);
            target.eraseColor(palette.background | 0xFF000000);
            android.graphics.Canvas canvas = new android.graphics.Canvas(target);
            com.limelight.ui.PortalShaderView portal = com.limelight.ui.PortalBackgroundHelper.getShaderView(activity);
            if (portal != null && portal.getWidth() > 0 && portal.getHeight() > 0) {
                android.graphics.Bitmap portalBmp = portal.getBlurredBitmap();
                if (portalBmp != null && !portalBmp.isRecycled()) {
                    int[] portalLoc = new int[2];
                    portal.getLocationOnScreen(portalLoc);
                    float sx = portalBmp.getWidth() / (float) portal.getWidth();
                    float sy = portalBmp.getHeight() / (float) portal.getHeight();
                    int relX = dialogLoc[0] - portalLoc[0];
                    int relY = dialogLoc[1] - portalLoc[1];
                    android.graphics.Rect srcR = new android.graphics.Rect(
                            Math.max(0, Math.round(relX * sx)),
                            Math.max(0, Math.round(relY * sy)),
                            Math.min(portalBmp.getWidth(), Math.round((relX + decorView.getWidth()) * sx)),
                            Math.min(portalBmp.getHeight(), Math.round((relY + decorView.getHeight()) * sy)));
                    if (!srcR.isEmpty()) {
                        android.graphics.Rect dstR = new android.graphics.Rect(0, 0, target.getWidth(), target.getHeight());
                        android.graphics.Paint bmpPaint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
                        canvas.drawBitmap(portalBmp, srcR, dstR, bmpPaint);
                    }
                }
            }
            View fgView = getMenuForegroundView(activity);
            if (fgView != null && fgView.getVisibility() == View.VISIBLE) {
                int[] fgLoc = new int[2];
                fgView.getLocationOnScreen(fgLoc);
                canvas.save();
                canvas.scale(captureScale, captureScale);
                canvas.translate(fgLoc[0] - dialogLoc[0], fgLoc[1] - dialogLoc[1]);
                fgView.draw(canvas);
                canvas.restore();
            }
            applyBitmapToDialogBackdrop(decorView, target, blurPct);
            bufferIndex[0] = 1 - idx;
            lastCaptureMs[0] = android.os.SystemClock.uptimeMillis();
        };

        final android.view.ViewTreeObserver.OnPreDrawListener preDrawListener = () -> {
            if (!stopped[0] && decorView.isAttachedToWindow() && !inFlight[0]) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastCaptureMs[0] >= 32L) {
                    inFlight[0] = true;
                    decorView.post(() -> {
                        try {
                            captureActivityFrame.run();
                        } finally {
                            inFlight[0] = false;
                        }
                    });
                }
            }
            return true;
        };

        activityDecor.getViewTreeObserver().addOnPreDrawListener(preDrawListener);
        decorView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override
            public void onViewDetachedFromWindow(View v) {
                stopped[0] = true;
                if (activityDecor.getViewTreeObserver().isAlive()) {
                    activityDecor.getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
                }
                android.widget.ImageView backdrop = decorView.findViewWithTag("oneui_dialog_blur_backdrop");
                if (backdrop != null) backdrop.setImageDrawable(null);
                v.removeOnAttachStateChangeListener(this);
            }
        });
        captureActivityFrame.run();
    }

    private static void applyBitmapToDialogBackdrop(View decorView, android.graphics.Bitmap bitmap, int blurPct) {
        if (!(decorView instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) decorView;
        Context ctx = decorView.getContext();
        com.limelight.ui.AppThemePalette palette = com.limelight.ui.AppThemePalette.getCurrent(ctx);
        int overlayTint = palette.getDialogOverlayTint();

        android.widget.ImageView backdrop = group.findViewWithTag("oneui_dialog_blur_backdrop");
        if (backdrop == null) {
            backdrop = new android.widget.ImageView(ctx);
            backdrop.setTag("oneui_dialog_blur_backdrop");
            backdrop.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            backdrop.setClickable(false);
            backdrop.setFocusable(false);
            backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            group.addView(backdrop, 0, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                float radiusDp = 22f * (blurPct / 100f);
                if (radiusDp > 0.5f) {
                    float radiusPx = dpToPx(ctx, Math.min(64f, radiusDp));
                    android.graphics.RenderEffect blur = android.graphics.RenderEffect.createBlurEffect(
                            radiusPx, radiusPx, android.graphics.Shader.TileMode.CLAMP);
                    android.graphics.RenderEffect tinted = android.graphics.RenderEffect.createColorFilterEffect(
                            new android.graphics.PorterDuffColorFilter(overlayTint, android.graphics.PorterDuff.Mode.SRC_OVER), blur);
                    backdrop.setRenderEffect(tinted);
                } else {
                    backdrop.setRenderEffect(null);
                    backdrop.setColorFilter(overlayTint, android.graphics.PorterDuff.Mode.SRC_OVER);
                }
            } else {
                backdrop.setColorFilter(overlayTint, android.graphics.PorterDuff.Mode.SRC_OVER);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int dialogStroke = Math.round(getCardOutlineWidthPx(ctx, 1.15f));
                if (dialogStroke > 0) {
                    android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable();
                    border.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                    border.setColor(Color.TRANSPARENT);
                    border.setCornerRadius(dpToPx(ctx, ONEUI_DIALOG_SQUIRCLE_RADIUS_DP));
                    border.setStroke(dialogStroke, palette.getCardRimColor());
                    decorView.setForeground(border);
                } else {
                    decorView.setForeground(null);
                }
            }
            decorView.setClipToOutline(true);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && blurPct > 0) {
            int boxRadius = Math.max(1, Math.min(18, Math.round(6f * (blurPct / 100f))));
            fastBoxBlur(bitmap, boxRadius);
        }
        backdrop.setImageBitmap(bitmap);
    }

    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.S)
    private static void applyBoundedWindowBlur(android.view.Window window, Context context, float[] cornerRadiiPx) {
        WindowManager wm = context.getSystemService(WindowManager.class);
        boolean blurEffective = com.limelight.ui.AppThemePalette.isLiveBlurEffective(context);
        int blurPct = blurEffective
                ? Math.max(0, Math.min(2000, PreferenceConfiguration.readPreferences(context).blurAmountPercent))
                : 0;
        final int blurRadiusPx = blurPct <= 0 ? 0
                : Math.max(1, Math.round(dpToPx(context, Math.min(64f, ONEUI_BLUR_RADIUS_DP * (blurPct / 100f)))));
        final java.util.function.Consumer<Boolean> updateBlur = enabled -> {
            boolean useBlurred = enabled || blurRadiusPx == 0;
            window.setBackgroundDrawable(createDialogSquircleDrawable(context, useBlurred, cornerRadiiPx));
            try {
                window.setBackgroundBlurRadius(enabled ? blurRadiusPx : 0);
            } catch (Throwable ignored) {
            }
        };

        boolean blurEnabled = wm != null && wm.isCrossWindowBlurEnabled();
        updateBlur.accept(blurEnabled);

        View decorView = window.getDecorView();
        if (wm == null || Boolean.TRUE.equals(decorView.getTag(TAG_WINDOW_BLUR_LISTENER))) {
            return;
        }
        decorView.setTag(TAG_WINDOW_BLUR_LISTENER, Boolean.TRUE);
        decorView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                wm.addCrossWindowBlurEnabledListener(updateBlur);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                wm.removeCrossWindowBlurEnabledListener(updateBlur);
            }
        });
        if (decorView.isAttachedToWindow()) {
            wm.addCrossWindowBlurEnabledListener(updateBlur);
        }
    }

    private static void clearOpaquePanelBackgrounds(View decorView) {
        if (!(decorView instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) decorView;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!"oneui_dialog_blur_backdrop".equals(child.getTag())) {
                child.setBackground(null);
            }
        }
        View content = decorView.findViewById(android.R.id.content);
        if (content != null) content.setBackground(null);
        Context ctx = decorView.getContext();
        int[] panelIds = {
                ctx.getResources().getIdentifier("parentPanel", "id", "android"),
                ctx.getResources().getIdentifier("parentPanel", "id", ctx.getPackageName()),
                androidx.appcompat.R.id.parentPanel,
                androidx.appcompat.R.id.topPanel,
                androidx.appcompat.R.id.contentPanel,
                androidx.appcompat.R.id.customPanel,
                androidx.appcompat.R.id.buttonPanel
        };
        for (int id : panelIds) {
            if (id != 0) {
                View panel = decorView.findViewById(id);
                if (panel != null) panel.setBackground(null);
            }
        }
    }

    public static void fastBoxBlur(android.graphics.Bitmap bitmap, int radius) {
        if (bitmap == null || radius < 1) return;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= 1 || h <= 1) return;
        int[] pix = new int[w * h];
        int[] tmp = new int[w * h];
        fastBoxBlur(bitmap, radius, pix, tmp);
    }

    public static void fastBoxBlur(android.graphics.Bitmap bitmap, int radius, int[] pix, int[] tmp) {
        if (bitmap == null || radius < 1) return;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= 1 || h <= 1) return;
        int n = w * h;
        if (pix == null || pix.length < n) pix = new int[n];
        if (tmp == null || tmp.length < n) tmp = new int[n];
        bitmap.getPixels(pix, 0, w, 0, 0, w, h);
        int div = radius * 2 + 1;
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < h; y++) {
                int row = y * w;
                int rSum = 0, gSum = 0, bSum = 0;
                for (int i = -radius; i <= radius; i++) {
                    int c = pix[row + Math.max(0, Math.min(w - 1, i))];
                    rSum += (c >> 16) & 0xFF;
                    gSum += (c >> 8) & 0xFF;
                    bSum += c & 0xFF;
                }
                for (int x = 0; x < w; x++) {
                    tmp[row + x] = 0xFF000000 | ((rSum / div) << 16) | ((gSum / div) << 8) | (bSum / div);
                    int add = pix[row + Math.min(w - 1, x + radius + 1)];
                    int rem = pix[row + Math.max(0, x - radius)];
                    rSum += ((add >> 16) & 0xFF) - ((rem >> 16) & 0xFF);
                    gSum += ((add >> 8) & 0xFF) - ((rem >> 8) & 0xFF);
                    bSum += (add & 0xFF) - (rem & 0xFF);
                }
            }
            for (int x = 0; x < w; x++) {
                int rSum = 0, gSum = 0, bSum = 0;
                for (int i = -radius; i <= radius; i++) {
                    int c = tmp[Math.max(0, Math.min(h - 1, i)) * w + x];
                    rSum += (c >> 16) & 0xFF;
                    gSum += (c >> 8) & 0xFF;
                    bSum += c & 0xFF;
                }
                for (int y = 0; y < h; y++) {
                    pix[y * w + x] = 0xFF000000 | ((rSum / div) << 16) | ((gSum / div) << 8) | (bSum / div);
                    int add = tmp[Math.min(h - 1, y + radius + 1) * w + x];
                    int rem = tmp[Math.max(0, y - radius) * w + x];
                    rSum += ((add >> 16) & 0xFF) - ((rem >> 16) & 0xFF);
                    gSum += ((add >> 8) & 0xFF) - ((rem >> 8) & 0xFF);
                    bSum += (add & 0xFF) - (rem & 0xFF);
                }
            }
        }
        bitmap.setPixels(pix, 0, w, 0, 0, w, h);
    }
}
