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
 * 完全按照ShuanQ SDK实现：AES加密 + MD5签名 + Hex编码 + 响应签名验证 + 请求安全码验证 + 时差验证
 * 验证通过后所有hook才能正常运行
 */
public class ShuanQVerifier {

    private static final String TAG = "ShuanQVerifier";

    // ========== 验证服务配置 ==========
    private static final String HOST = "http://arm.luckyyh.top";
    private static final String APP_ID = "1";
    private static final String APP_KEY = "6e59e6715aa80d71a1cfbbca1be7072f";
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

    // 签名时间限制（秒）- 与原SDK一致，最小90秒，最大90秒
    private static final int SIGNATURE_TIME_LIMIT_MIN = 90;
    private static final int SIGNATURE_TIME_LIMIT_MAX = 90;

    // 心跳频率（毫秒）- 与原SDK一致
    private static final int HEARTBEAT_FREQUENCY = 50000;

    // 验证状态
    private static boolean isVerified = false;
    private static String cardCode = "";
    private static long expireTime = 0;
    private static long lastVerifyTime = 0;
    private static String userToken = "";

    // 验证回调接口
    public interface VerifyCallback {
        void onSuccess(String cardInfo);
        void onFailure(String errorMsg);
    }

    // 心跳相关
    private static Timer heartbeatTimer = null;
    private static TimerTask heartbeatTimerTask = null;
    private static boolean heartbeatRunning = false;

    /**
     * 初始化验证系统
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

        final Handler handler = new Handler(Looper.getMainLooper());
        heartbeatTimer = new Timer();
        heartbeatTimerTask = new TimerTask() {
            @Override
            public void run() {
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

        heartbeatTimer.schedule(heartbeatTimerTask, 0, HEARTBEAT_FREQUENCY);
        heartbeatRunning = true;
        Log.d(TAG, "心跳检测已启动，间隔: " + HEARTBEAT_FREQUENCY + "ms");
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
     * 设置卡密（用于Hook端等其他进程初始化心跳时设置）
     */
    public static void setCardCode(String card) {
        cardCode = card;
    }

    /**
     * 设置验证状态（用于Hook端等其他进程同步状态）
     */
    public static void setVerified(boolean verified, long expireTimeMs) {
        isVerified = verified;
        if (verified) {
            expireTime = expireTimeMs;
            lastVerifyTime = System.currentTimeMillis();
        }
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
     * 执行卡密验证（完全按照原SDK实现）
     */
    public static void verifyCard(final Context context, final String card, final VerifyCallback callback) {
        final Handler handler = new Handler(Looper.getMainLooper());

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 生成32位随机安全码（与原SDK一致）
                    final String requestSafeCode = getRandomString(32);

                    // 构建请求参数
                    HashMap<String, String> params = new HashMap<>();
                    params.put("card", card);
                    params.put("machine_code", getDeviceId(context));
                    params.put("request_safe_code", requestSafeCode);

                    // 加密参数并发送请求
                    HashMap<String, String> encryptedParams = encryptParams(params);
                    String response = postRequest(HOST + API_CHECK, encryptedParams);

                    Log.d(TAG, "验证响应: " + response);

                    // 解析响应
                    JSONObject jsonResponse = new JSONObject(response);
                    String codeStr = jsonResponse.optString("code", "-1");
                    String message = jsonResponse.optString("message", "未知错误");

                    if ("1".equals(codeStr)) {
                        // 验证成功
                        String encryptedData = jsonResponse.optString("data", "");
                        String timestampStr = jsonResponse.optString("timestamp", "");
                        String signature = jsonResponse.optString("signature", "");

                        // 1. 响应签名验证（与原SDK一致：data + timestamp + appkey）
                        if (!responseDataSignatureVerification(encryptedData, timestampStr, signature)) {
                            throw new Exception("响应数据验签失败");
                        }

                        // 2. 解密data
                        String decryptedData = dataDecrypt(encryptedData);
                        if (decryptedData == null) {
                            throw new Exception("响应数据解密失败");
                        }
                        Log.d(TAG, "解密后的data: " + decryptedData);

                        JSONObject dataObj = new JSONObject(decryptedData);
                        JSONObject cardInfo = dataObj.getJSONObject("cardInfo");
                        JSONObject surplusTime = dataObj.getJSONObject("surplusTime");
                        JSONObject moreOtherData = dataObj.getJSONObject("moreOtherData");
                        String expireTimeStr = dataObj.optString("expireTimeStr", "");

                        // 3. 请求安全码验证（与原SDK一致）
                        String responseSafeCode = moreOtherData.optString("request_safe_code", "");
                        if (!responseSafeCode.equals(requestSafeCode)) {
                            throw new Exception("请求安全码校验失败，检测到数据被截获篡改");
                        }

                        // 4. 时差验证（与原SDK一致）
                        long currentTimeSec = new Date().getTime() / 1000L;
                        long responseTimestamp = Long.parseLong(timestampStr);
                        if (currentTimeSec > responseTimestamp + SIGNATURE_TIME_LIMIT_MAX
                                || currentTimeSec < responseTimestamp - SIGNATURE_TIME_LIMIT_MIN) {
                            throw new Exception("时差验证失败");
                        }

                        // 提取卡密信息
                        userToken = cardInfo.optString("token", "");
                        long endTime = cardInfo.optLong("endtime", 0);
                        if (endTime > 0) {
                            expireTime = endTime * 1000L;
                        }

                        // 保存验证状态
                        isVerified = true;
                        cardCode = card;
                        lastVerifyTime = System.currentTimeMillis();
                        saveVerifyState(context);

                        // 启动心跳
                        startHeartbeat(context);

                        final String info = "卡密: " + card + "\n到期时间: " + expireTimeStr;
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess(info);
                            }
                        });
                    } else {
                        throw new Exception(message);
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
     * 心跳验证（与原SDK完全一致）
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
                    // 生成32位随机安全码
                    final String requestSafeCode = getRandomString(32);

                    // 构建心跳请求参数
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
                    String codeStr = jsonResponse.optString("code", "-1");
                    String message = jsonResponse.optString("message", "未知错误");

                    // 心跳验证中 code == "1" 或 "10000" 都算成功（与原SDK一致）
                    if ("1".equals(codeStr) || "10000".equals(codeStr)) {
                        String encryptedData = jsonResponse.optString("data", "");
                        String timestampStr = jsonResponse.optString("timestamp", "");
                        String signature = jsonResponse.optString("signature", "");

                        // 1. 响应签名验证
                        if (!responseDataSignatureVerification(encryptedData, timestampStr, signature)) {
                            // 签名验证失败，卡密失效（与原SDK一致）
                            isVerified = false;
                            stopHeartbeat();
                            saveVerifyState(context);
                            Log.d(TAG, "心跳响应验签失败，卡密失效");
                            return;
                        }

                        // 2. 解密data
                        String decryptedData = dataDecrypt(encryptedData);
                        if (decryptedData == null) {
                            // 解密失败，卡密失效
                            isVerified = false;
                            stopHeartbeat();
                            saveVerifyState(context);
                            Log.d(TAG, "心跳响应解密失败，卡密失效");
                            return;
                        }
                        Log.d(TAG, "心跳解密后的data: " + decryptedData);

                        JSONObject dataObj = new JSONObject(decryptedData);
                        JSONObject cardInfo = dataObj.getJSONObject("cardInfo");
                        JSONObject surplusTime = dataObj.getJSONObject("surplusTime");
                        JSONObject moreOtherData = dataObj.getJSONObject("moreOtherData");
                        String expireTimeStr = dataObj.optString("expireTimeStr", "");

                        // 3. 请求安全码验证
                        String responseSafeCode = moreOtherData.optString("request_safe_code", "");
                        if (!responseSafeCode.equals(requestSafeCode)) {
                            isVerified = false;
                            stopHeartbeat();
                            saveVerifyState(context);
                            Log.d(TAG, "心跳请求安全码校验失败，卡密失效");
                            return;
                        }

                        // 4. 时差验证
                        long currentTimeSec = new Date().getTime() / 1000L;
                        long responseTimestamp = Long.parseLong(timestampStr);
                        if (currentTimeSec > responseTimestamp + SIGNATURE_TIME_LIMIT_MAX
                                || currentTimeSec < responseTimestamp - SIGNATURE_TIME_LIMIT_MIN) {
                            isVerified = false;
                            stopHeartbeat();
                            saveVerifyState(context);
                            Log.d(TAG, "心跳时差验证失败，卡密失效");
                            return;
                        }

                        // 提取卡密信息
                        userToken = cardInfo.optString("token", "");
                        long endTime = cardInfo.optLong("endtime", 0);
                        if (endTime > 0) {
                            expireTime = endTime * 1000L;
                        }

                        // 检查是否已过期
                        if (currentTimeSec > endTime && endTime > 0) {
                            isVerified = false;
                            stopHeartbeat();
                            saveVerifyState(context);
                            Log.d(TAG, "卡密已到期");
                            return;
                        }

                        lastVerifyTime = System.currentTimeMillis();
                        saveVerifyState(context);
                        Log.d(TAG, "心跳验证成功");
                    } else {
                        // 心跳失败，卡密失效
                        isVerified = false;
                        stopHeartbeat();
                        saveVerifyState(context);
                        Log.d(TAG, "心跳验证失败，卡密已失效: " + message);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "心跳验证异常", e);
                    // 网络异常时不立即失效（与原SDK一致，异常时不杀进程）
                }
            }
        }).start();
    }

    /**
     * 清除验证状态
     */
    public static void clearVerify(Context context) {
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
    // 加密与签名工具方法（与ShuanQ SDK完全一致）
    // ==========================================

    /**
     * 加密请求参数（与原SDK的ParamsUtil.paramHandle完全一致）
     */
    private static HashMap<String, String> encryptParams(HashMap<String, String> params) {
        HashMap<String, String> result = new HashMap<>();

        // 添加appid
        if (params.get("appid") == null || params.get("appid").equals("")) {
            params.put("appid", APP_ID);
        }

        // 添加时间戳
        params.put("timestamp", String.valueOf(new Date().getTime() / 1000L));

        // 生成签名（MD5）
        String signature = getSignMd5(params, APP_KEY);
        params.put("signature", signature);

        // AES加密参数名和参数值 + Hex编码（appid除外，与原SDK一致）
        for (String key : params.keySet()) {
            String value = params.get(key);
            String encryptedKey = key;
            String encryptedValue = value;

            if (!"appid".equals(key) && value != null && !value.isEmpty()) {
                try {
                    // 加密参数名（与原SDK paramNameTransferEncryption=true一致）
                    encryptedKey = getApiDataTransferEncryptionEncoded(encryptAES(key, AES_KEY));
                    // 加密参数值（与原SDK paramValueTransferEncryption=true一致）
                    encryptedValue = getApiDataTransferEncryptionEncoded(encryptAES(value, AES_KEY));
                } catch (Exception e) {
                    Log.e(TAG, "参数加密失败: " + key, e);
                    throw new RuntimeException(e.getMessage());
                }
            }

            result.put(encryptedKey, encryptedValue);
        }

        return result;
    }

    /**
     * 响应签名验证（与原SDK一致：data + timestamp + appkey）
     */
    private static boolean responseDataSignatureVerification(String data, String timestamp, String signature) {
        String signStr = data + timestamp + APP_KEY;
        String calcSign = md5(signStr);
        if (calcSign == null) return false;
        return calcSign.equalsIgnoreCase(signature);
    }

    /**
     * 数据解密（与原SDK dataDecrypt一致：Hex解码 + AES解密）
     */
    private static String dataDecrypt(String encryptedData) {
        try {
            byte[] dataBytes = hexStringToBytes(encryptedData);
            return decryptAES(dataBytes, AES_KEY);
        } catch (Exception e) {
            Log.e(TAG, "数据解密失败", e);
            return null;
        }
    }

    /**
     * 获取数据传输编码后的字符串（Hex编码，与原SDK一致）
     */
    private static String getApiDataTransferEncryptionEncoded(byte[] data) {
        return toHexString(data);
    }

    /**
     * MD5签名（与ShuanQ SDK SignUtil.getSignMd5完全一致）
     */
    private static String getSignMd5(Map<String, String> map, String appKey) {
        String signStr = getSignString(map) + appKey;
        return md5(signStr);
    }

    /**
     * 获取签名字符串（与原SDK SignUtil.getSignString完全一致）
     */
    private static String getSignString(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        ArrayList<Map.Entry<String, String>> list = new ArrayList<>(map.entrySet());

        // 按参数名升序排列
        Collections.sort(list, new Comparator<Map.Entry<String, String>>() {
            @Override
            public int compare(Map.Entry<String, String> e1, Map.Entry<String, String> e2) {
                return e1.getKey().compareTo(e2.getKey());
            }
        });

        // 拼接参数（排除空值、signature、appid，与原SDK一致）
        for (Map.Entry<String, String> entry : list) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.equals("") || "signature".equals(key) || "appid".equals(key)) {
                continue;
            }
            sb.append(key).append("=").append(value).append("&");
        }

        // 去掉首尾的&（与原SDK trimFirstAndLastChar一致）
        return trimFirstAndLastChar(sb.toString(), '&');
    }

    /**
     * 去掉首尾的指定字符（与原SDK SignUtil.trimFirstAndLastChar完全一致）
     */
    private static String trimFirstAndLastChar(String str, char c) {
        String result = str;
        while (true) {
            int firstIdx = result.indexOf(c);
            int start = firstIdx == 0 ? 1 : 0;
            int lastIdx = result.lastIndexOf(c) + 1;
            int end = lastIdx == result.length() ? result.lastIndexOf(c) : result.length();
            result = result.substring(start, end);
            boolean needTrimStart = result.indexOf(c) == 0;
            boolean needTrimEnd = result.lastIndexOf(c) + 1 == result.length();
            if (!needTrimStart && !needTrimEnd) {
                break;
            }
        }
        return result;
    }

    /**
     * AES加密（ECB模式，PKCS5Padding，与原SDK AesUtil.encrypt完全一致）
     */
    private static byte[] encryptAES(String data, String key) throws Exception {
        if (key == null) {
            return null;
        }
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
     * AES解密（与原SDK AesUtil.decrypt完全一致）
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
     * 字节数组转Hex字符串（与原SDK HexUtil.toHexString完全一致，大写）
     */
    private static String toHexString(byte[] bytes) {
        char[] hexChars = "0123456789ABCDEF".toCharArray();
        StringBuilder sb = new StringBuilder("");
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            sb.append(hexChars[(b & 0xF0) >> 4]);
            b = bytes[i];
            sb.append(hexChars[b & 0xF]);
        }
        return sb.toString().trim();
    }

    /**
     * Hex字符串转字节数组（与原SDK HexUtil.hexStringToBytes完全一致，小写）
     */
    private static byte[] hexStringToBytes(String hex) {
        String hexLower = hex.toLowerCase();
        char[] hexChars = hexLower.toCharArray();
        byte[] result = new byte[hexLower.length() / 2];
        return hexStringToBytes(hexChars, result);
    }

    private static byte[] hexStringToBytes(char[] cArray, byte[] byArray) {
        for (int i = 0; i < byArray.length; i++) {
            int n = i * 2;
            int n2 = "0123456789abcdef".indexOf(cArray[n]);
            byArray[i] = (byte) (n2 * 16 + "0123456789abcdef".indexOf(cArray[n + 1]) & 0xFF);
        }
        return byArray;
    }

    /**
     * 生成随机字符串（与原SDK ShuanQUtil.getRandomString完全一致）
     */
    public static String getRandomString(int length) {
        Random random = new Random();
        StringBuffer sb = new StringBuffer();
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < length; i++) {
            int n = random.nextInt(62);
            char c = chars.charAt(n);
            sb.append(c);
        }
        return String.valueOf(sb);
    }

    /**
     * MD5加密（与原SDK Md5Util.getMD5完全一致，小写）
     */
    private static String md5(String data) {
        if (data == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(data.getBytes("UTF-8"));
            byte[] digest = md.digest();
            StringBuffer sb = new StringBuffer();
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
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
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
