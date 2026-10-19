package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/** Settings shortcuts and popup focus lifecycle. */
final class MenuController {
    interface Opener { void open(Intent intent); }

    private final Activity activity;
    private final FrameLayout root;
    private final float density;
    private final Opener opener;
    private PopupWindow menuPopup;

    MenuController(Activity activity, FrameLayout root, float density, Opener opener) {
        this.activity = activity;
        this.root = root;
        this.density = density;
        this.opener = opener;
    }

    boolean isOpen() { return menuPopup != null; }

    void dismiss() {
        if (menuPopup != null) menuPopup.dismiss();
    }

    void showSettingsMenu() {
        dismiss();
        View anchor = activity.findViewById(R.id.btnSettings);
        View panel = activity.getLayoutInflater().inflate(R.layout.popup_settings, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = new PopupWindow(panel, (int) (180 * density),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setOnDismissListener(() -> {
            if (menuPopup == popup) menuPopup = null;
            anchor.requestFocus();
        });
        addRow(rows, popup, R.drawable.ic_settings, R.string.menu_all_settings,
                new Intent(Settings.ACTION_SETTINGS));
        addRow(rows, popup, R.drawable.ic_picture, R.string.menu_picture,
                new Intent(Settings.ACTION_SETTINGS).putExtra("SUB_PAGE", "PICTURE_PAGE_MODE")
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK));
        addRow(rows, popup, R.drawable.ic_wifi, R.string.menu_wifi,
                new Intent(Settings.ACTION_WIFI_SETTINGS));
        addRow(rows, popup, R.drawable.ic_apps, R.string.menu_apps,
                new Intent(Settings.ACTION_APPLICATION_SETTINGS));
        menuPopup = popup;
        popup.showAsDropDown(anchor, 0, Math.round(10 * density), Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private void addRow(LinearLayout rows, PopupWindow popup, int icon, int title, Intent intent) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_setting, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            opener.open(intent);
        });
        int index = rows.getChildCount();
        row.setOnKeyListener((v, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN
                    && key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int next = index + (key == KeyEvent.KEYCODE_DPAD_UP ? -1
                        : key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0);
                if (next >= 0 && next < rows.getChildCount()) rows.getChildAt(next).requestFocus();
            }
            return true;
        });
        rows.addView(row);
    }
}
