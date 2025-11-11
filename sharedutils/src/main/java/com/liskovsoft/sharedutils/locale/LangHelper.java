package com.liskovsoft.sharedutils.locale;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;

public class LangHelper {

    public static void forceLocale(Context context, String localeStr) {
        Locale locale = Locale.forLanguageTag(localeStr.replace("_", "-"));
        Locale.setDefault(locale);
        Configuration config = context.getResources().getConfiguration();
        config.setLocale(locale);
        context.getResources().updateConfiguration(config, context.getResources().getDisplayMetrics());
    }

    public static String guessLocale(Context context) {
        return Locale.getDefault().toString();
    }

    public static String getDefaultLocale() {
        return Locale.getDefault().toString();
    }
}
