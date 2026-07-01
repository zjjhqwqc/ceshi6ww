package com.HookTest;

/**
 * 坐标转换工具类
 * 支持WGS84(原始坐标) ↔ GCJ02(火星坐标系)
 * 用于微信小程序使用GCJ02坐标
 */
public class CoordinateTransform {

    private static final double PI = 3.1415926535897932384626;
    private static final double A = 6378245.0; // 长半轴
    private static final double EE = 0.00669342162296594323; // 偏心率平方

    /**
     * WGS84转GCJ02（火星坐标系）
     * @param lat 纬度
     * @param lng 经度
     * @return [纬度, 经度]
     */
    public static double[] wgs84ToGcj02(double lat, double lng) {
        if (isOutOfChina(lat, lng)) {
            return new double[]{lat, lng};
        }
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        double mgLat = lat + dLat;
        double mgLng = lng + dLng;
        return new double[]{mgLat, mgLng};
    }

    /**
     * GCJ02转WGS84
     * @param lat 纬度
     * @param lng 经度
     * @return [纬度, 经度]
     */
    public static double[] gcj02ToWgs84(double lat, double lng) {
        if (isOutOfChina(lat, lng)) {
            return new double[]{lat, lng};
        }
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        double mgLat = lat + dLat;
        double mgLng = lng + dLng;
        return new double[]{lat * 2 - mgLat, lng * 2 - mgLng};
    }

    /**
     * GCJ02转WGS84（精确版，使用迭代法）
     * @param lat 纬度
     * @param lng 经度
     * @return [纬度, 经度]
     */
    public static double[] gcj02ToWgs84Exact(double lat, double lng) {
        if (isOutOfChina(lat, lng)) {
            return new double[]{lat, lng};
        }
        // 二分法迭代求解
        double initDelta = 0.01;
        double dLat = initDelta;
        double dLng = initDelta;
        double mLat = lat - dLat;
        double mLng = lng - dLng;
        double pLat = lat + dLat;
        double pLng = lng + dLng;
        double wgsLat = 0, wgsLng = 0;

        for (int i = 0; i < 30; i++) {
            wgsLat = (mLat + pLat) / 2;
            wgsLng = (mLng + pLng) / 2;
            double[] tmp = wgs84ToGcj02(wgsLat, wgsLng);
            double tmpLat = tmp[0];
            double tmpLng = tmp[1];
            dLat = tmpLat - lat;
            dLng = tmpLng - lng;
            if (Math.abs(dLat) < 0.000001 && Math.abs(dLng) < 0.000001) {
                break;
            }
            if (dLat > 0) pLat = wgsLat; else mLat = wgsLat;
            if (dLng > 0) pLng = wgsLng; else mLng = wgsLng;
        }
        return new double[]{wgsLat, wgsLng};
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320.0 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }

    /**
     * 判断坐标是否在中国境外
     */
    private static boolean isOutOfChina(double lat, double lng) {
        return !(lng > 73.66 && lng < 135.05 && lat > 3.86 && lat < 53.55);
    }

    /**
     * 计算两点之间的距离（米）
     */
    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double earthRadius = 6371000; // 地球半径，单位米
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadius * c;
    }
}
