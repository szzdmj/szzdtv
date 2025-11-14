package com.liskovsoft.sharedutils.dialogs;

import android.content.Context;
import android.view.View;
import java.util.List;

/**
 * Minimal GenericSelectorDialog shim with nested DialogSourceBase and DialogItem
 * Provided only to satisfy compile-time imports like:
 * import com.liskovsoft.sharedutils.dialogs.GenericSelectorDialog.DialogSourceBase.DialogItem;
 *
 * This is NOT a full UI implementation — it is a tiny compile-time stub.
 */
public final class GenericSelectorDialog {
    private GenericSelectorDialog() {}

    /**
     * Base for dialog data sources. Real implementation supplies items and handles selection.
     */
    public static class DialogSourceBase {
        public interface OnItemSelectedListener {
            void onItemSelected(DialogItem item);
        }

        /**
         * Simple nested DialogItem used by many places in the code.
         */
        public static class DialogItem {
            public CharSequence title;
            public CharSequence subtitle;
            public Object tag;

            public DialogItem(CharSequence title) {
                this(title, null, null);
            }

            public DialogItem(CharSequence title, CharSequence subtitle, Object tag) {
                this.title = title;
                this.subtitle = subtitle;
                this.tag = tag;
            }

            @Override
            public String toString() {
                return title == null ? "" : title.toString();
            }
        }

        // minimal API used at compile time
        public List<DialogItem> getItems() { return java.util.Collections.emptyList(); }
        public CharSequence getTitle() { return ""; }
        public void setOnItemSelectedListener(OnItemSelectedListener l) { /* no-op */ }
        public View createView(Context ctx) { return null; }
    }
}
