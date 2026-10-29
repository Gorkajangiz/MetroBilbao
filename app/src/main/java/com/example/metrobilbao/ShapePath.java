package com.example.metrobilbao;

import com.google.android.gms.maps.model.LatLng;

import java.util.List;


class ShapePath {

    private static final double METERS_PER_DEG_LAT = 111320.0;

    
    private static final double HINT_MAX_ERROR_M = 400.0;

    final List<LatLng> points;
    final double[] cumDist;
    final double totalLength;

    ShapePath(List<LatLng> points) {
        this.points = points;
        this.cumDist = new double[points.size()];
        for (int i = 1; i < points.size(); i++)
            cumDist[i] = cumDist[i - 1] + distanceMeters(points.get(i - 1), points.get(i));
        this.totalLength = cumDist[points.size() - 1];
    }

    
    double projectOffset(LatLng p, double hintOffset, double maxSearch) {
        int n = points.size() - 1;
        if (n < 1) return 0;

        boolean hintValid = maxSearch > 0
                && hintOffset >= 0 && hintOffset <= totalLength;

        double bestDist = Double.MAX_VALUE;
        double chosen = 0;
        boolean found = false;

        if (hintValid) {
            double lower = Math.max(0, hintOffset - maxSearch);
            double upper = Math.min(totalLength, hintOffset + maxSearch);

            int startSeg = lowerBoundCumDist(lower);
            int endSeg   = upperBoundCumDist(upper);

            for (int i = startSeg; i <= endSeg; i++) {
                found = true;
                double[] pr = projectPointOnSegment(p, points.get(i), points.get(i + 1));
                double d = distanceMeters(p, new LatLng(pr[0], pr[1]));
                if (d < bestDist) {
                    bestDist = d;
                    chosen = cumDist[i] + pr[2] * (cumDist[i + 1] - cumDist[i]);
                }
            }
        }

        if (!found || bestDist > HINT_MAX_ERROR_M) {
            bestDist = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                double[] pr = projectPointOnSegment(p, points.get(i), points.get(i + 1));
                double d = distanceMeters(p, new LatLng(pr[0], pr[1]));
                if (d < bestDist) {
                    bestDist = d;
                    chosen = cumDist[i] + pr[2] * (cumDist[i + 1] - cumDist[i]);
                }
            }
        }
        return chosen;
    }

    
    private int lowerBoundCumDist(double target) {
        int n = points.size() - 1;
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumDist[mid + 1] < target) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    
    private int upperBoundCumDist(double target) {
        int n = points.size() - 1;
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (cumDist[mid] > target) hi = mid - 1;
            else lo = mid;
        }
        return lo;
    }

    LatLng pointAt(double offset) {
        if (offset <= 0) return points.get(0);
        if (offset >= totalLength) return points.get(points.size() - 1);
        int lo = 0, hi = points.size() - 1;
        while (lo < hi - 1) {
            int mid = (lo + hi) >>> 1;
            if (cumDist[mid] <= offset) lo = mid; else hi = mid;
        }
        double segStart = cumDist[lo];
        double segLen = cumDist[lo + 1] - segStart;
        double t = segLen > 0 ? (offset - segStart) / segLen : 0;
        LatLng a = points.get(lo), b = points.get(lo + 1);
        return new LatLng(a.latitude + (b.latitude - a.latitude) * t,
                a.longitude + (b.longitude - a.longitude) * t);
    }

    float bearingAt(double offset) {
        if (points.size() < 2) return 0f;
        int lo = 0, hi = points.size() - 1;
        if (offset <= 0) {
            lo = 0; hi = 1;
        } else if (offset >= totalLength) {
            lo = points.size() - 2; hi = points.size() - 1;
        } else {
            while (lo < hi - 1) {
                int mid = (lo + hi) >>> 1;
                if (cumDist[mid] <= offset) lo = mid; else hi = mid;
            }
        }
        LatLng a = points.get(lo), b = points.get(lo + 1);
        double lat1 = Math.toRadians(a.latitude);
        double lon1 = Math.toRadians(a.longitude);
        double lat2 = Math.toRadians(b.latitude);
        double lon2 = Math.toRadians(b.longitude);
        double dLon = lon2 - lon1;
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        double brng = Math.toDegrees(Math.atan2(y, x));
        return (float) ((brng + 360) % 360);
    }

    private static double[] projectPointOnSegment(LatLng p, LatLng a, LatLng b) {
        double mLng = metersPerDegLng(p.latitude);
        double ax = (a.longitude - p.longitude) * mLng;
        double ay = (a.latitude - p.latitude) * METERS_PER_DEG_LAT;
        double bx = (b.longitude - p.longitude) * mLng;
        double by = (b.latitude - p.latitude) * METERS_PER_DEG_LAT;
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = 0;
        if (len2 > 1e-9) t = Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
        return new double[]{ p.latitude + (ay + t * dy) / METERS_PER_DEG_LAT,
                p.longitude + (ax + t * dx) / mLng, t };
    }

    private static double metersPerDegLng(double latDeg) {
        return METERS_PER_DEG_LAT * Math.cos(Math.toRadians(latDeg));
    }

    private static double distanceMeters(LatLng a, LatLng b) {
        double mLng = metersPerDegLng((a.latitude + b.latitude) * 0.5);
        double dLat = (b.latitude - a.latitude) * METERS_PER_DEG_LAT;
        double dLng = (b.longitude - a.longitude) * mLng;
        return Math.sqrt(dLat * dLat + dLng * dLng);
    }
}
