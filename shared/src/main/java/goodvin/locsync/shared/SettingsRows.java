/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package goodvin.locsync.shared;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import goodvin.locsync.logexporter.R;

/** Binds the shared settings row layouts (row_setting_toggle / _action / _input) for both apps. */
public final class SettingsRows {
    private SettingsRows() {}

    /** Toggle row: tapping anywhere on the row flips the switch and reports the new state. */
    public static void bindToggle(View row, String label, String sub, boolean checked, Consumer<Boolean> onChange) {
        text(row, R.id.row_label, label);
        if (sub != null) {
            TextView subView = row.findViewById(R.id.row_sub);
            subView.setText(sub);
            subView.setVisibility(View.VISIBLE);
        }
        CompoundButton sw = row.findViewById(R.id.row_switch);
        sw.setChecked(checked);
        row.setOnClickListener(v -> {
            boolean next = !sw.isChecked();
            sw.setChecked(next);
            onChange.accept(next);
        });
    }

    /** Action row with an optional subtitle and chevron; {@code click} may be null. */
    public static void bindAction(View row, String label, String sub, boolean chevron, Runnable click) {
        text(row, R.id.row_label, label);
        if (sub != null && !sub.isEmpty()) {
            TextView subView = row.findViewById(R.id.row_sub);
            subView.setText(sub);
            subView.setVisibility(View.VISIBLE);
        }
        if (chevron) row.findViewById(R.id.row_chevron).setVisibility(View.VISIBLE);
        if (click != null) row.setOnClickListener(v -> click.run());
    }

    /** Action row with an outlined button on the right; an empty label leaves the label as is. */
    public static void bindActionButton(View row, String label, String sub, String buttonLabel, Runnable buttonClick) {
        if (!label.isEmpty()) text(row, R.id.row_label, label);
        if (sub != null && !sub.isEmpty()) {
            TextView subView = row.findViewById(R.id.row_sub);
            subView.setText(sub);
            subView.setVisibility(View.VISIBLE);
        }
        TextView button = row.findViewById(R.id.row_button);
        button.setText(buttonLabel);
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(v -> buttonClick.run());
    }

    /**
     * Numeric row: every valid edit is reported at once (the services re-read settings live);
     * out-of-range or unparsable input is flagged and not reported.
     */
    public static void bindNumber(View row, String label, double value, double min, double max, DoubleConsumer onChange) {
        text(row, R.id.row_label, String.format(Locale.US, "%s (%s–%s)", label, fmtNum(min), fmtNum(max)));
        EditText input = row.findViewById(R.id.row_input);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(fmtNum(value));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                double v;
                try {
                    v = Double.parseDouble(s.toString().trim().replace(',', '.'));
                } catch (NumberFormatException e) {
                    v = Double.NaN;
                }
                if (Double.isNaN(v) || v < min || v > max) {
                    input.setError(String.format(Locale.US, "%s–%s", fmtNum(min), fmtNum(max)));
                } else {
                    input.setError(null);
                    onChange.accept(v);
                }
            }
        });
    }

    static String fmtNum(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.2f", v);
    }

    private static void text(View root, int id, CharSequence s) {
        TextView tv = root.findViewById(id);
        if (tv != null) tv.setText(s);
    }
}
