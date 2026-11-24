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
        dialog.show();
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
        return v instanceof android.view.SurfaceView ? (android.view.SurfaceView) v : null;
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

    public static void styleDialogSquircle(android.app.Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        Context context = dialog.getContext();
        com.limelight.ui.AppThemePalette palette = com.limelight.ui.AppThemePalette.getCurrent(context);
        boolean blurEffective = com.limelight.ui.AppThemePalette.isLiveBlurEffective(context);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(context, 28f));
        bg.setColor(palette.getDialogSurfaceColor(blurEffective));
        float outlineScale = Math.max(0f, PreferenceConfiguration.readPreferences(context).cardOutlineThicknessPercent / 100f);
        int strokePx = outlineScale <= 0f ? 0 : Math.max(1, Math.round(dpToPx(context, 1.25f) * outlineScale));
        if (strokePx > 0) bg.setStroke(strokePx, palette.cardOutline);
        android.view.Window window = dialog.getWindow();
        window.setBackgroundDrawable(bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            int blurPct = blurEffective
                    ? Math.max(0, Math.min(2000, PreferenceConfiguration.readPreferences(context).blurAmountPercent))
                    : 0;
            int blurRadiusPx = blurPct <= 0 ? 0
                    : Math.max(1, Math.round(dpToPx(context, Math.min(64f, 28f * (blurPct / 100f)))));
            try {
                window.setBackgroundBlurRadius(blurRadiusPx);
            } catch (Throwable ignored) {
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
