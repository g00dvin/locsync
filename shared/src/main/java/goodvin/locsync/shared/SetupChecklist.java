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

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import goodvin.locsync.logexporter.R;

/** Renders the first-run setup steps (view_setup) for both apps. */
public final class SetupChecklist {
    private SetupChecklist() {}

    /**
     * One thing to set up. {@code done} steps show a check mark; the others a button that runs
     * {@code action} (usually opening a system screen). {@code optional} steps are recommended only.
     */
    public record Step(String title, String sub, boolean done, boolean optional, Runnable action) {}

    /** True when every required step is done. */
    public static boolean requiredDone(List<Step> steps) {
        for (Step s : steps) {
            if (!s.done() && !s.optional()) return false;
        }
        return true;
    }

    /** Fills {@code container} (reusing its rows when the step count is unchanged). */
    public static void render(ViewGroup container, List<Step> steps) {
        Context ctx = container.getContext();
        int rowCount = steps.size() * 2 - 1;   // rows with dividers between them
        if (container.getChildCount() != rowCount) {
            container.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(ctx);
            for (int i = 0; i < steps.size(); i++) {
                if (i > 0) {   // like LsDivider: 1dp hairline inset 12dp
                    float d = ctx.getResources().getDisplayMetrics().density;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(d)));
                    lp.setMarginStart(Math.round(12 * d));
                    lp.setMarginEnd(Math.round(12 * d));
                    View divider = new View(ctx);
                    divider.setBackgroundColor(ctx.getColor(R.color.ls_divider));
                    container.addView(divider, lp);
                }
                container.addView(inflater.inflate(R.layout.row_setting_action, container, false));
            }
        }
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            View row = container.getChildAt(i * 2);
            TextView label = row.findViewById(R.id.row_label);
            label.setText(ctx.getString(step.done() ? R.string.setup_step_done : R.string.setup_step_todo,
                    i + 1, step.title()));
            label.setTextColor(ctx.getColor(step.done() ? R.color.ls_text_muted : R.color.ls_text));
            TextView sub = row.findViewById(R.id.row_sub);
            String subText = step.optional() && !step.done()
                    ? ctx.getString(R.string.setup_recommended, step.sub()) : step.sub();
            sub.setText(subText);
            sub.setVisibility(subText == null || subText.isEmpty() ? View.GONE : View.VISIBLE);
            TextView button = row.findViewById(R.id.row_button);
            boolean actionable = !step.done() && step.action() != null;
            button.setText(R.string.setup_open);
            button.setVisibility(actionable ? View.VISIBLE : View.GONE);
            button.setOnClickListener(actionable ? v -> step.action().run() : null);
            row.setOnClickListener(actionable ? v -> step.action().run() : null);
            row.setClickable(actionable);
        }
    }
}
