package com.HookTest;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.text.TextUtils;

/**
 * 配置共享ContentProvider
 * 用于在模块APP和微信之间共享定位配置
 */
public class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "com.HookTest.provider";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/config");

    private static final String PREFS_NAME = "wx_location_prefs";

    // 配置键名
    public static final String KEY_LOCATION_ENABLED = "isLocation";
    public static final String KEY_XCX_ENABLED = "isX";
    public static final String KEY_LATITUDE = "latitude";
    public static final String KEY_LONGITUDE = "longitude";
    public static final String KEY_GPS_PLACE = "gpsPlace";

    private static final String[] ALL_KEYS = {
            KEY_LOCATION_ENABLED,
            KEY_XCX_ENABLED,
            KEY_LATITUDE,
            KEY_LONGITUDE,
            KEY_GPS_PLACE
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Context context = getContext();
        if (context == null) return null;

        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        if (projection == null || projection.length == 0) {
            projection = ALL_KEYS;
        }

        MatrixCursor cursor = new MatrixCursor(projection);
        MatrixCursor.RowBuilder row = cursor.newRow();

        for (String key : projection) {
            if (KEY_LOCATION_ENABLED.equals(key)) {
                row.add(sp.getBoolean(key, false) ? "1" : "0");
            } else if (KEY_XCX_ENABLED.equals(key)) {
                row.add(sp.getBoolean(key, false) ? "1" : "0");
            } else {
                row.add(sp.getString(key, ""));
            }
        }

        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/config";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        Context context = getContext();
        if (context == null || values == null) return 0;

        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();

        for (String key : values.keySet()) {
            Object value = values.get(key);
            if (value instanceof String) {
                editor.putString(key, (String) value);
            } else if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Integer) {
                if (KEY_LOCATION_ENABLED.equals(key) || KEY_XCX_ENABLED.equals(key)) {
                    editor.putBoolean(key, (Integer) value != 0);
                } else {
                    editor.putInt(key, (Integer) value);
                }
            } else if (value instanceof Float) {
                editor.putFloat(key, (Float) value);
            } else if (value instanceof Long) {
                editor.putLong(key, (Long) value);
            }
        }

        editor.commit();
        return 1;
    }

    /**
     * 从ContentProvider读取配置（用于Hook端）
     */
    public static ConfigData readConfig(Context context) {
        ConfigData data = new ConfigData();
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY + "/config");
            Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    String colName = cursor.getColumnName(i);
                    String value = cursor.getString(i);
                    if (TextUtils.isEmpty(value)) continue;

                    if (KEY_LOCATION_ENABLED.equals(colName)) {
                        data.locationEnabled = "1".equals(value);
                    } else if (KEY_XCX_ENABLED.equals(colName)) {
                        data.xcxEnabled = "1".equals(value);
                    } else if (KEY_LATITUDE.equals(colName)) {
                        data.latitude = value;
                    } else if (KEY_LONGITUDE.equals(colName)) {
                        data.longitude = value;
                    } else if (KEY_GPS_PLACE.equals(colName)) {
                        data.gpsPlace = value;
                    }
                }
                cursor.close();
            }
        } catch (Throwable t) {
            android.util.Log.e("WxLocationHook", "读取配置失败", t);
        }
        return data;
    }

    /**
     * 写入配置（用于MainActivity端）
     */
    public static void writeConfig(Context context, ContentValues values) {
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY + "/config");
            context.getContentResolver().update(uri, values, null, null);
        } catch (Throwable t) {
            android.util.Log.e("WxLocationHook", "写入配置失败", t);
        }
    }

    /**
     * 配置数据类
     */
    public static class ConfigData {
        public boolean locationEnabled = false;
        public boolean xcxEnabled = false;
        public String latitude = "39.908823";
        public String longitude = "116.397470";
        public String gpsPlace = "北京市东城区";
    }
}
