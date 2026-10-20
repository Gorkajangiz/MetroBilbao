package com.example.metrobilbao;

import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.maps.model.LatLng;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TrenGestorTiempoReal {

    private static final String LOG_TAG = "MetroTren";
    private static final String FEED_URL =
            "https://ctb-gtfs-rt.s3.eu-south-2.amazonaws.com/metro-bilbao-vehicle-positions.pb";

    private static final int BASE_INTERVAL_SECONDS = 5;
    private static final int MAX_BACKOFF_SECONDS   = 60;
    private static final int CONNECT_TIMEOUT_MS    = 10000;
    private static final int READ_TIMEOUT_MS       = 10000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    private ScheduledFuture<?> pollingTask;
    private int consecutiveErrors = 0;
    private long fetchId = 0;
    private String lastSignature = "";

    public interface Callback {
        void onUpdate(List<TrainPosition> trains, long feedTimestamp,
                      long fetchId, boolean sameAsBefore);
        void onError(String message);
    }

    public static class TrainPosition {
        public final String vehicleId;
        public final String tripId;
        public final String routeId;
        public final String entityId;
        public final LatLng position;
        public final long vehicleTimestamp;
        public final long feedTimestamp;
        public final String stopId;

        // NUEVOS
        public final int currentStatus;       // 0=INCOMING_AT, 1=STOPPED_AT, 2=IN_TRANSIT_TO, -1=desconocido
        public final int currentStopSequence; // -1 = desconocido
        public final long fetchedAtMs;        // para descartar hints viejos

        public TrainPosition(String vehicleId, String tripId, String routeId,
                             String entityId, LatLng position,
                             long vehicleTimestamp, long feedTimestamp, String stopId,
                             int currentStatus, int currentStopSequence, long fetchedAtMs) {
            this.vehicleId = vehicleId;
            this.tripId = tripId;
            this.routeId = routeId;
            this.entityId = entityId;
            this.position = position;
            this.vehicleTimestamp = vehicleTimestamp;
            this.feedTimestamp = feedTimestamp;
            this.stopId = stopId;
            this.currentStatus = currentStatus;
            this.currentStopSequence = currentStopSequence;
            this.fetchedAtMs = fetchedAtMs;
        }

        public String key() {
            if (vehicleId != null && !vehicleId.isEmpty()) return "veh:" + vehicleId;
            if (tripId != null && !tripId.isEmpty())       return "trip:" + tripId;
            return null;
        }
    }

    public synchronized void start(Callback callback) {
        if (pollingTask != null && !pollingTask.isCancelled()) return;
        pollingTask = executor.scheduleWithFixedDelay(
                () -> descargarFeed(callback), 0, BASE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void descargarFeed(Callback callback) {
        if (!inFlight.compareAndSet(false, true)) {
            android.util.Log.d(LOG_TAG, "fetch skipped: previa en vuelo");
            return;
        }
        final long myFetchId = ++fetchId;
        final long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(FEED_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/x-protobuf");

            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new Exception("HTTP " + status);

            FeedMessage feed;
            try (InputStream in = conn.getInputStream()) {
                feed = FeedMessage.parseFrom(in);
            }

            long feedTs = (feed.hasHeader() && feed.getHeader().hasTimestamp())
                    ? feed.getHeader().getTimestamp() : 0L;

            List<TrainPosition> trains = new ArrayList<>();
            StringBuilder sig = new StringBuilder(256);
            int entities = feed.getEntityCount();
            int rejNoPos = 0, rejCoords = 0, rejNoId = 0;

            for (FeedEntity entity : feed.getEntityList()) {
                if (!entity.hasVehicle()) continue;
                VehiclePosition v = entity.getVehicle();
                if (!v.hasPosition()) { rejNoPos++; continue; }

                double lat = v.getPosition().getLatitude();
                double lon = v.getPosition().getLongitude();
                if (!Double.isFinite(lat) || !Double.isFinite(lon)
                        || lat < -90 || lat > 90 || lon < -180 || lon > 180
                        || (lat == 0 && lon == 0)) {
                    rejCoords++;
                    continue;
                }

                String entityId = entity.getId();

                String vehicleId = null;
                if (v.hasVehicle() && v.getVehicle().hasId()) {
                    String vid = v.getVehicle().getId();
                    if (vid != null && !vid.isEmpty()) vehicleId = vid;
                }
                String tripId = "";
                if (v.hasTrip() && v.getTrip().hasTripId())
                    tripId = v.getTrip().getTripId();
                String routeId = "";
                if (v.hasTrip() && v.getTrip().hasRouteId())
                    routeId = v.getTrip().getRouteId();

                if ((vehicleId == null || vehicleId.isEmpty()) && tripId.isEmpty()) {
                    rejNoId++;
                    continue;
                }

                long vehicleTs = v.hasTimestamp() ? v.getTimestamp() : 0L;
                String stopId = v.hasStopId() ? v.getStopId() : "";

                // NUEVOS
                int currentStatus = -1;
                if (v.hasCurrentStatus()) {
                    currentStatus = v.getCurrentStatus().getNumber();
                }
                int currentStopSequence = -1;
                if (v.hasCurrentStopSequence()) {
                    currentStopSequence = v.getCurrentStopSequence();
                }
                long fetchedAtMs = System.currentTimeMillis();

                trains.add(new TrainPosition(
                        vehicleId, tripId, routeId, entityId,
                        new LatLng(lat, lon), vehicleTs, feedTs, stopId,
                        currentStatus, currentStopSequence, fetchedAtMs));

                // Log de depuración: comprobar qué envía realmente Metro Bilbao
                android.util.Log.d(LOG_TAG, "hint trip=" + tripId
                        + " stop=" + stopId
                        + " status=" + currentStatus
                        + " seq=" + currentStopSequence);

                String k = (vehicleId != null && !vehicleId.isEmpty())
                        ? "v:" + vehicleId : "t:" + tripId;
                sig.append(k).append('|').append(tripId).append('|')
                        .append(String.format(java.util.Locale.US, "%.5f,%.5f", lat, lon))
                        .append('|').append(vehicleTs).append(';');
            }

            String signature = sig.toString();
            boolean sameAsBefore = signature.equals(lastSignature);
            lastSignature = signature;

            long dt = System.currentTimeMillis() - t0;
            android.util.Log.d(LOG_TAG, "fetch#" + myFetchId + " OK"
                    + " entities=" + entities
                    + " valid=" + trains.size()
                    + " header_ts=" + feedTs
                    + " sameAsBefore=" + sameAsBefore
                    + " rej(noPos=" + rejNoPos + ",coords=" + rejCoords
                    + ",noId=" + rejNoId + ")"
                    + " dt=" + dt + "ms");

            consecutiveErrors = 0;

            List<TrainPosition> result = Collections.unmodifiableList(trains);
            final long finalTs = feedTs;
            final long finalFetchId = myFetchId;
            final boolean finalSame = sameAsBefore;
            mainHandler.post(() ->
                    callback.onUpdate(result, finalTs, finalFetchId, finalSame));

        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.trim().isEmpty()) msg = e.getClass().getSimpleName();
            android.util.Log.e(LOG_TAG, "fetch#" + myFetchId + " ERROR: " + msg, e);

            consecutiveErrors++;
            int delay = Math.min(
                    BASE_INTERVAL_SECONDS + consecutiveErrors * BASE_INTERVAL_SECONDS,
                    MAX_BACKOFF_SECONDS);
            android.util.Log.d(LOG_TAG, "backoff " + delay + "s (err=" + consecutiveErrors + ")");

            synchronized (this) {
                if (pollingTask != null) pollingTask.cancel(false);
                final Callback cb = callback;
                pollingTask = executor.scheduleWithFixedDelay(
                        () -> descargarFeed(cb), delay, BASE_INTERVAL_SECONDS, TimeUnit.SECONDS);
            }
            final String errMsg = msg;
            mainHandler.post(() -> callback.onError(errMsg));

        } finally {
            if (conn != null) conn.disconnect();
            inFlight.set(false);
        }
    }

    public synchronized void stop() {
        if (pollingTask != null) { pollingTask.cancel(true); pollingTask = null; }
    }

    public void shutdown() {
        stop();
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }
}