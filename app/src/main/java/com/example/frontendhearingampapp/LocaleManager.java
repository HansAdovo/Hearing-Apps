package com.example.frontendhearingampapp;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;

import java.util.Locale;

public class LocaleManager {

    private static final String LANGUAGE_KEY = "Settings";
    private static final String LANGUAGE_DEFAULT = "en";

    public static void loadLocale(Context context) {
        String language = getLanguagePref(context);
        setLocale(context, language);
    }

    public static void changeLocale(Context context, String lang) {
        setLanguagePref(context, lang);
        setLocale(context, lang);
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private static void setLocale(Context context, String lang) {
        Locale locale = new Locale(lang);
        Locale.setDefault(locale);
        Configuration config = new Configuration();
        config.locale = locale;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(locale);
            context = context.createConfigurationContext(config);
        } else {
            context.getResources().updateConfiguration(config, context.getResources().getDisplayMetrics());
        }
    }

    private static void setLanguagePref(Context context, String lang) {
        SharedPreferences.Editor editor = context.getSharedPreferences(LANGUAGE_KEY, Context.MODE_PRIVATE).edit();
        editor.putString("My_Lang", lang);
        editor.apply();
    }

    private static String getLanguagePref(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(LANGUAGE_KEY, Context.MODE_PRIVATE);
        return prefs.getString("My_Lang", LANGUAGE_DEFAULT);
    }
}