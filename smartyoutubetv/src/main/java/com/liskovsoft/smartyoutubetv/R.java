package com.liskovsoft.smartyoutubetv;

/**
 * Temporary compile-time R stub for smartyoutubetv.
 * - Expanded to include ids/layouts/dimens/styleable entries referenced by current compile errors.
 * - This is a short-term compile helper only. Remove this file once real resources are present
 *   and the Android build system generates the real R class.
 */
public final class R {
    public static final class id {
        public static final int bootstrap_checkbox_wrapper = 0x7f010001;
        public static final int bootstrap_checkbox_content = 0x7f010002;
        public static final int bootstrap_checkbox = 0x7f010003;

        public static final int bootstrap_button_wrapper = 0x7f010004;
        public static final int bootstrap_button_content = 0x7f010005;
        public static final int bootstrap_button_image = 0x7f010006;
        public static final int bootstrap_button_text = 0x7f010007;

        public static final int player_view = 0x7f010008;
        public static final int root = 0x7f010009;

        // PlayerActivity controls
        public static final int tv_filename = 0x7f01000a;
        public static final int btn_vol_up = 0x7f01000b;
        public static final int btn_vol_down = 0x7f01000c;
        public static final int btn_bright_up = 0x7f01000d;
        public static final int btn_bright_down = 0x7f01000e;

        // Widget helper
        public static final int tip_text = 0x7f01000f;
    }

    public static final class layout {
        public static final int bootstrap_check_button = 0x7f020001;
        public static final int bootstrap_large_button = 0x7f020002;
        public static final int player_activity = 0x7f020003;

        // keep placeholders for other commonly referenced layouts if needed
        public static final int bootstrap_text_button = 0x7f020004;
    }

    public static final class dimen {
        public static final int bootstrap_button_text_size = 0x7f030001;
        public static final int bootstrap_text_button_padding = 0x7f030002;
        public static final int bootstrap_large_button_padding = 0x7f030003;
    }

    public static final class styleable {
        // Minimal placeholders for styleable arrays referenced in code.
        // Indices are arbitrary but must exist for compile-time access.
        public static final int[] BootstrapCheckButton = { 0x01010000, 0x01010001 };
        public static final int BootstrapCheckButton_titleText = 0;
        public static final int BootstrapCheckButton_onCheckedChanged = 1;

        public static final int[] BootstrapLargeButton = { 0x01010002, 0x01010003 };
        public static final int BootstrapLargeButton_mainIcon = 0;
        public static final int BootstrapLargeButton_titleText = 1;

        // Base button styleable referenced by BootstrapButtonBase
        public static final int[] BootstrapButtonBase = { 0x01010004 };
        public static final int BootstrapButtonBase_tipText = 0;

        // Keep a general placeholder array for other custom widgets
        public static final int[] BootstrapTextButton = { 0x01010005, 0x01010006 };
        public static final int BootstrapTextButton_titleText = 0;
        public static final int BootstrapTextButton_onClick = 1;
    }
}
