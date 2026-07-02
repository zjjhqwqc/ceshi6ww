package com.HookTest;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 网络验证核心类
 * 基于ShuanQ SDK实现方式：AES加密 + MD5签名 + Hex编码
 * 验证通过后所有hook才能正常运行
 */
public class ShuanQVerifier {

    private static final String TAG = "ShuanQVerifier";

    // ========== 验证服务配置 ==========
    // 验证服务器地址
    private static final String HOST = "http://arm.luckyyh.top";
    // 应用ID
    private static final String APP_ID = "1";
    // 应用密钥
    private static final String APP_KEY = "6e59e6715aa80d71a1cfbbca1be7072f";
    // AES加密密钥
    private static final String AES_KEY = "55decc546d77795b";
    // ============================================================

    // API接口
    private static final String API_CHECK = "/api/card_app/check";
    private static final String API_GET_CARD_INFO = "/api/card_app/get_card_info";

    // 配置存储
    private static final String PREFS_NAME = "shuanq_verify";
    private static final String KEY_CARD = "card_code";
    private static final String KEY_VERIFIED = "is_verified";
    private static final String KEY_EXPIRE_TIME = "expire_time";
    private static final String KEY_LAST_VERIFY = "last_verify_time";

    // 验证状态
    private static boolean isVerified = false;
    private static String cardCode = "";
    private static long expireTime = 0;
    private static long lastVerifyTime = 0;

    // 验证回调接口
    public interface VerifyCallback {
        void onSuccess(String cardInfo);
        void onFailure(String errorMsg);
    }

    // 心跳相关（与原SDK一致：Timer + TimerTask，间隔50000ms）
    private static final int HEARTBEAT_FREQUENCY = 50000; // 心跳频率，50000毫秒=50秒，与原SDK一致
    private static Timer heartbeatTimer = null;
    private static TimerTask heartbeatTimerTask = null;
    private static boolean heartbeatRunning = false;
    private static String userToken = ""; // 用户token，登录成功后获取

    /**
     * 初始化验证系统
     * 自动加载保存的卡密并执行自动验证
     */
    public static void init(Context context) {
        loadVerifyState(context);
        Log.d(TAG, "验证系统初始化完成，当前验证状态: " + isVerified + ", 卡密: " + cardCode);

        // 自动登录：如果有保存的卡密且未验证，则自动验证
        if (cardCode != null && !cardCode.isEmpty() && !isVerified) {
            Log.d(TAG, "检测到保存的卡密，执行自动验证...");
            verifyCard(context, cardCode, new VerifyCallback() {
                @Override
                public void onSuccess(String cardInfo) {
                    Log.d(TAG, "自动验证成功: " + cardInfo);
                }

                @Override
                public void onFailure(String errorMsg) {
                    Log.d(TAG, "自动验证失败: " + errorMsg);
                }
            });
        }

        // 如果已经验证通过，启动心跳检测
        if (isVerified && cardCode != null && !cardCode.isEmpty()) {
            startHeartbeat(context);
        }
    }

    /**
     * 启动心跳检测（与原SDK一致：Timer方式，50秒一次）
     */
    public static void startHeartbeat(final Context context) {
        if (heartbeatRunning) {
            Log.d(TAG, "心跳已在运行中");
            return;
        }

        if (cardCode == null || cardCode.isEmpty()) {
            Log.d(TAG, "没有卡密，不启动心跳");
            return;
        }

        // 使用Timer + TimerTask，与原SDK实现方式一致
        final Handler handler = new Handler(Looper.getMainLooper());
        heartbeatTimer = new Timer();
        heartbeatTimerTask = new TimerTask() {
            @Override
            public void run() {
                // 在主线程执行
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isVerified && cardCode != null && !cardCode.isEmpty()) {
                            Log.d(TAG, "执行心跳验证...");
                            heartbeatVerify(context);
                        }
                    }
                });
            }
        };

        // 立即执行第一次，之后每50秒执行一次（与原SDK一致）
        heartbeatTimer.schedule(heartbeatTimerTask, 0, HEARTBEAT_FREQUENCY);
        heartbeatRunning = true;
        Log.d(TAG, "心跳检测已启动，间隔: " + HEARTBEAT_FREQUENCY + "ms (" + (HEARTBEAT_FREQUENCY / 1000) + "秒)");
    }

    /**
     * 停止心跳检测
     */
    public static void stopHeartbeat() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
        }
        if (heartbeatTimerTask != null) {
            heartbeatTimerTask.cancel();
            heartbeatTimerTask = null;
        }
        heartbeatRunning = false;
        Log.d(TAG, "心跳检测已停止");
    }

    /**
     * 检查是否已验证通过
     */
    public static boolean isVerified() {
        // 如果已过期，返回false
        if (isVerified && expireTime > 0 && System.currentTimeMillis() > expireTime) {
            isVerified = false;
            Log.d(TAG, "验证已过期");
        }
        return isVerified;
    }

    /**
     * 获取卡密
     */
    public static String getCardCode() {
        return cardCode;
    }

    /**
     * 获取设备唯一标识（Android ID）
     */
    public static String getDeviceId(Context context) {
        try {
            return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception e) {
            Log.e(TAG, "获取设备ID失败", e);
            return "unknown";
        }
    }

    /**
     * 执行卡密验证
     */
    public static void verifyCard(final Context context, final String card, final VerifyCallback callback) {
        final Handler handler = new Handler(Looper.getMainLooper());

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 构建请求参数
                    HashMap<String, String> params = new HashMap<>();
                    params.put("card", card);
                    params.put("machine_code", getDeviceId(context));

                    // 加密参数并发送请求
                    HashMap<String, String> encryptedParams = encryptParams(params);
                    String response = postRequest(HOST + API_CHECK, encryptedParams);

                    Log.d(TAG, "验证响应: " + response);

                    // 解析响应
                    JSONObject jsonResponse = new JSONObject(response);
                    int code = jsonResponse.optInt("code", -1);
                    String msg = jsonResponse.optString("msg", "未知错误");

                    if (code == 1) {
                        // 验证成功
                        JSONObject data = jsonResponse.optJSONObject("data");
                        if (data != null && data.has("cardInfo")) {
                            JSONObject cardInfo = data.getJSONObject("cardInfo");
                            String expire = cardInfo.optString("expire_time", "");

                            // 保存验证状态
                            isVerified = true;
                            cardCode = card;
                            lastVerifyTime = System.currentTimeMillis();
                            // 默认为24小时有效期（可根据实际情况调整）
                            expireTime = lastVerifyTime + 24 * 60 * 60 * 1000L;

                            saveVerifyState(context);

                            // 验证成功后启动心跳检测
                            startHeartbeat(context);

                            final String info = "卡密: " + card + "\n到期时间: " + expire;
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    callback.onSuccess(info);
                                }
                            });
                        } else {
                            throw new Exception("响应数据格式错误");
                        }
                    } else {
                        throw new Exception(msg);
                    }
                } catch (final Exception e) {
                    Log.e(TAG, "验证失败", e);
                    isVerified = false;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * 心跳验证（与原SDK一致）
     * 调用 /api/card_app/get_card_info 接口
     * 参数包含：card, machine_code, update_active, user_token, request_safe_code
     */
    public static void heartbeatVerify(final Context context) {
        if (cardCode == null || cardCode.isEmpty()) {
            isVerified = false;
            stopHeartbeat();
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 生成随机安全码（与原SDK一致：32位随机字符串）
                    String requestSafeCode = getRandomString(32);

                    // 构建心跳请求参数（与原SDK一致）
                    HashMap<String, String> params = new HashMap<>();
                    params.put("card", cardCode);
                    params.put("machine_code", getDeviceId(context));
                    params.put("update_active", "1");
                    params.put("user_token", userToken);
                    params.put("request_safe_code", requestSafeCode);

                    // 加密参数并发送请求
                    HashMap<String, String> encryptedParams = encryptParams(params);
                    String response = postRequest(HOST + API_GET_CARD_INFO, encryptedParams);

                    Log.d(TAG, "心跳响应: " + response);

                    // 解析响应
                    JSONObject jsonResponse = new JSONObject(response);
                    int code = jsonResponse.optInt("code", -1);
                    String msg = jsonResponse.optString("msg", "未知错误");

                    if (code == 1) {
                        // 心跳成功
                        lastVerifyTime = System.currentTimeMillis();
                        saveVerifyState(context);
                        Log.d(TAG, "心跳验证成功");

                        // 解析卡密信息，更新到期时间
                        try {
                            JSONObject data = jsonResponse.optJSONObject("data");
                            if (data != null && data.has("cardInfo")) {
                                JSONObject cardInfo = data.getJSONObject("cardInfo");
                                long endTime = cardInfo.optLong("endtime", 0);
                                if (endTime > 0) {
                                    // 转换为毫秒
                                    expireTime = endTime * 1000L;
                                    saveVerifyState(context);
                                    Log.d(TAG, "心跳更新到期时间: " + expireTime);
                                }
                                // 更新token
                                String token = cardInfo.optString("token", "");
                                if (!token.isEmpty()) {
                                    userToken = token;
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "解析心跳卡密信息失败", e);
                        }
                    } else {
                        // 心跳失败，卡密失效
                        isVerified = false;
                        stopHeartbeat();
                        saveVerifyState(context);
                        Log.d(TAG, "心跳验证失败，卡密已失效: " + msg);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "心跳验证异常", e);
                    // 网络异常时不立即失效，保留缓存状态（与原SDK一致，异常时不杀进程）
                }
            }
        }).start();
    }

    /**
     * 清除验证状态
     */
    public static void clearVerify(Context context) {
        // 停止心跳
        stopHeartbeat();

        isVerified = false;
        cardCode = "";
        expireTime = 0;
        lastVerifyTime = 0;
        userToken = "";
        saveVerifyState(context);
    }

    /**
     * 保存验证状态
     */
    private static void saveVerifyState(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();
            editor.putString(KEY_CARD, cardCode);
            editor.putBoolean(KEY_VERIFIED, isVerified);
            editor.putLong(KEY_EXPIRE_TIME, expireTime);
            editor.putLong(KEY_LAST_VERIFY, lastVerifyTime);
            editor.apply();

            // 通过ContentProvider同步验证状态到微信进程
            try {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put("verify_passed", isVerified);
                values.put("verify_card", cardCode);
                values.put("verify_expire", String.valueOf(expireTime));
                ConfigProvider.writeConfig(context, values);
                Log.d(TAG, "验证状态已同步到ContentProvider");
            } catch (Throwable t) {
                Log.e(TAG, "同步验证状态到ContentProvider失败", t);
            }

        } catch (Exception e) {
            Log.e(TAG, "保存验证状态失败", e);
        }
    }

    /**
     * 加载验证状态
     */
    private static void loadVerifyState(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            cardCode = sp.getString(KEY_CARD, "");
            isVerified = sp.getBoolean(KEY_VERIFIED, false);
            expireTime = sp.getLong(KEY_EXPIRE_TIME, 0);
            lastVerifyTime = sp.getLong(KEY_LAST_VERIFY, 0);
        } catch (Exception e) {
            Log.e(TAG, "加载验证状态失败", e);
        }
    }

    // ==========================================
    // 加密与签名工具方法（与ShuanQ SDK一致）
    // ==========================================

    /**
     * 加密请求参数（AES加密 + Hex编码 + MD5签名）
     */
    private static HashMap<String, String> encryptParams(HashMap<String, String> params) {
        HashMap<String, String> result = new HashMap<>();

        // 添加appid
        params.put("appid", APP_ID);

        // 添加时间戳
        params.put("timestamp", String.valueOf(new Date().getTime() / 1000L));

        // 生成签名（MD5）
        String signature = getSignMd5(params, APP_KEY);
        params.put("signature", signature);

        // AES加密参数名和参数值 + Hex编码
        for (String key : params.keySet()) {
            String value = params.get(key);
            String encryptedKey = key;
            String encryptedValue = value;

            if (!key.equals("appid") && value != null && !value.isEmpty()) {
                try {
                    // 加密参数名
                    encryptedKey = toHexString(encryptAES(key, AES_KEY));
                    // 加密参数值
                    encryptedValue = toHexString(encryptAES(value, AES_KEY));
                } catch (Exception e) {
                    Log.e(TAG, "参数加密失败: " + key, e);
                }
            }

            result.put(encryptedKey, encryptedValue);
        }

        return result;
    }

    /**
     * MD5签名（与ShuanQ SDK一致）
     * 规则：按参数名升序排列，拼接key=value&，最后追加appKey，然后MD5
     */
    private static String getSignMd5(Map<String, String> map, String appKey) {
        StringBuilder sb = new StringBuilder();
        ArrayList<Map.Entry<String, String>> list = new ArrayList<>(map.entrySet());

        // 按参数名升序排列
        Collections.sort(list, new Comparator<Map.Entry<String, String>>() {
            @Override
            public int compare(Map.Entry<String, String> e1, Map.Entry<String, String> e2) {
                return e1.getKey().compareTo(e2.getKey());
            }
        });

        // 拼接参数（排除signature和appid）
        for (Map.Entry<String, String> entry : list) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isEmpty() || "signature".equals(key) || "appid".equals(key)) {
                continue;
            }
            sb.append(key).append("=").append(value).append("&");
        }

        // 去掉末尾的&
        String signStr = sb.toString();
        if (signStr.endsWith("&")) {
            signStr = signStr.substring(0, signStr.length() - 1);
        }

        // 追加appKey
        signStr += appKey;

        // MD5
        return md5(signStr);
    }

    /**
     * AES加密（ECB模式，PKCS5Padding）
     */
    private static byte[] encryptAES(String data, String key) throws Exception {
        String keyStr = key;
        if (key.length() != 16) {
            keyStr = key.substring(0, 16);
        }
        SecretKeySpec secretKey = new SecretKeySpec(keyStr.getBytes("UTF-8"), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        return cipher.doFinal(data.getBytes("UTF-8"));
    }

    /**
     * AES解密
     */
    private static String decryptAES(byte[] data, String key) throws Exception {
        String keyStr = key;
        if (key.length() != 16) {
            keyStr = key.substring(0, 16);
        }
        SecretKeySpec secretKey = new SecretKeySpec(keyStr.getBytes("UTF-8"), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        return new String(cipher.doFinal(data), "UTF-8");
    }

    /**
     * 字节数组转Hex字符串
     */
    private static String toHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /**
     * Hex字符串转字节数组
     */
    private static byte[] hexStringToBytes(String hex) {
        int len = hex.length() / 2;
        byte[] result = new byte[len];
        for (int i = 0; i < len; i++) {
            int index = i * 2;
            result[i] = (byte) Integer.parseInt(hex.substring(index, index + 2), 16);
        }
        return result;
    }

    /**
     * 生成随机字符串（与原SDK一致）
     */
    public static String getRandomString(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int index = random.nextInt(chars.length());
            sb.append(chars.charAt(index));
        }
        return sb.toString();
    }

    /**
     * MD5加密
     */
    private static String md5(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(data.getBytes("UTF-8"));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                String hex = Integer.toHexString(b & 0xFF);
                if (hex.length() == 1) {
                    sb.append("0");
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "MD5加密失败", e);
            return null;
        }
    }

    /**
     * 发送POST请求
     */
    private static String postRequest(String urlStr, HashMap<String, String> params) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);
        conn.setDoInput(true);

        // 构建POST数据
        StringBuilder postData = new StringBuilder();
        for (String key : params.keySet()) {
            if (postData.length() > 0) {
                postData.append("&");
            }
            postData.append(URLEncoder.encode(key, "UTF-8"));
            postData.append("=");
            postData.append(URLEncoder.encode(params.get(key), "UTF-8"));
        }

        // 发送请求
        OutputStream os = conn.getOutputStream();
        os.write(postData.toString().getBytes("UTF-8"));
        os.flush();
        os.close();

        // 读取响应
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        conn.disconnect();

        return response.toString();
    }
}
