package com.example.metrobilbao;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.maps.model.LatLng;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;


public class MetroRepository {

    private static final String LOG_TAG = "MetroTren";
    private static final String DISPLAY_L1 = "ETX_PLE";
    private static final String DISPLAY_L2 = "BSR_KAB";
    private static final double METERS_PER_DEG_LAT = 111320.0;
    private static final double REF_LAT = 43.2630, REF_LNG = -2.9350;
    private static final double M_PER_DEG_LAT_APPROX = 111320.0;
    private static final double M_PER_DEG_LNG_APPROX =
            M_PER_DEG_LAT_APPROX * Math.cos(Math.toRadians(REF_LAT));

    public interface Callback {
        void onReady(@NonNull MetroRepository repository);
        void onError(@NonNull Throwable error);
    }

    public interface ProgressListener extends GtfsUpdater.Progress {}

    private static MetroRepository instance;

    private final Object lock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Callback> pendingCallbacks = new ArrayList<>();

    private volatile boolean cargando = false;
    private volatile boolean listo = false;
    private volatile Throwable ultimoError;

    private ShapePath displayL1;
    private ShapePath displayL2;
    private Map<String, ShapePath> shapesPorId = Collections.emptyMap();
    private HorarioGestor horarioGestor;
    private final List<MetroGestor.Station> estaciones = new ArrayList<>();
    private final Map<String, String> zonasPorEstacion = new HashMap<>();
    private final Map<String, Double> tarifasIndividuales = new HashMap<>(); // fare_id -> precio GTFS
    private final Map<String, String> reglasTarifarias = new HashMap<>();    // "zonaO|zonaD" -> fare_id

    private MetroRepository() {}

    public static synchronized MetroRepository getInstance() {
        if (instance == null) instance = new MetroRepository();
        return instance;
    }

    public boolean isReady() { return listo; }
    @Nullable public Throwable getLastError() { return ultimoError; }

    public void init(@NonNull Context context, @Nullable Callback callback) {
        init(context, callback, null);
    }

    public void init(@NonNull Context context, @Nullable Callback callback,
                     @Nullable ProgressListener progress) {
        final Context appCtx = context.getApplicationContext();
        synchronized (lock) {
            if (listo) {
                if (callback != null) mainHandler.post(() -> callback.onReady(this));
                return;
            }
            if (callback != null) pendingCallbacks.add(callback);
            if (cargando) return;
            cargando = true;
        }

        GtfsUpdater.ensureFresh(appCtx, new GtfsUpdater.Callback() {
            @Override public void onReady(File gtfsDir, boolean fromDownload) {
                if (progress != null) progress.onProgress("Procesando datos…", true, 0, 0);
                try {
                    loadFromSource(new DirectorySource(gtfsDir));
                } catch (Throwable t) {
                    Log.e(LOG_TAG, "Error cargando GTFS desde filesDir", t);
                    try {
                        loadFromSource(new AssetsSource(appCtx.getAssets()));
                    } catch (Throwable t2) {
                        Log.e(LOG_TAG, "Error cargando GTFS desde assets", t2);
                        fail(t2);
                    }
                }
            }
            @Override public void onError(Throwable t) {
                Log.w(LOG_TAG, "Descarga GTFS falló, usando assets", t);
                try {
                    loadFromSource(new AssetsSource(appCtx.getAssets()));
                } catch (Throwable t2) {
                    Log.e(LOG_TAG, "Error cargando GTFS desde assets", t2);
                    fail(t2);
                }
            }
        }, progress);
    }

    private void loadFromSource(GtfsSource source) throws Exception {
        long t0 = System.currentTimeMillis();

        Map<String, ShapePath> shapes = cargarShapes(source);
        HorarioGestor hg = new HorarioGestor();
        hg.cargar(source, shapes);
        List<MetroGestor.Station> ests = new MetroGestor().getStations(source);

        Map<String, String> zonas = new HashMap<>();
        for (MetroGestor.Station s : ests) zonas.put(s.parentId, s.zoneId);

        Map<String, Double> tarifas = new HashMap<>();
        Map<String, String> reglas = new HashMap<>();
        cargarTarifas(source, tarifas, reglas);

        ShapePath d1 = buildDisplayPath(shapes, DISPLAY_L1);
        ShapePath d2 = buildDisplayPath(shapes, DISPLAY_L2);

        synchronized (lock) {
            this.shapesPorId = shapes;
            this.displayL1 = d1;
            this.displayL2 = d2;
            this.horarioGestor = hg;
            this.estaciones.clear();
            this.estaciones.addAll(ests);
            this.zonasPorEstacion.clear();
            this.zonasPorEstacion.putAll(zonas);
            this.tarifasIndividuales.clear();
            this.tarifasIndividuales.putAll(tarifas);
            this.reglasTarifarias.clear();
            this.reglasTarifarias.putAll(reglas);
            listo = true;
            cargando = false;
        }
        notifySuccess();
        Log.d(LOG_TAG, "GTFS listo en " + (System.currentTimeMillis() - t0) + "ms");
    }

    private void cargarTarifas(GtfsSource src,
                               Map<String, Double> tarifasOut,
                               Map<String, String> reglasOut) {
        try (BufferedReader r = src.open("fare_attributes.txt")) {
            String header = r.readLine();
            if (header != null) {
                String[] cols = header.split(",", -1);
                int idxId = colIdx(cols, "fare_id");
                int idxPrice = colIdx(cols, "price");
                if (idxId >= 0 && idxPrice >= 0) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        String[] c = l.split(",", -1);
                        if (c.length <= Math.max(idxId, idxPrice)) continue;
                        try {
                            tarifasOut.put(c[idxId].trim(),
                                    Double.parseDouble(c[idxPrice].trim()));
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        } catch (IOException e) {
            Log.w(LOG_TAG, "No se pudo leer fare_attributes.txt", e);
        }

        try (BufferedReader r = src.open("fare_rules.txt")) {
            String header = r.readLine();
            if (header != null) {
                String[] cols = header.split(",", -1);
                int idxFare = colIdx(cols, "fare_id");
                int idxOrigin = colIdx(cols, "origin_id");
                int idxDest = colIdx(cols, "destination_id");
                if (idxFare >= 0 && idxOrigin >= 0 && idxDest >= 0) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        String[] c = l.split(",", -1);
                        if (c.length <= Math.max(idxFare, Math.max(idxOrigin, idxDest))) continue;
                        reglasOut.put(c[idxOrigin].trim() + "|" + c[idxDest].trim(),
                                c[idxFare].trim());
                    }
                }
            }
        } catch (IOException e) {
            Log.w(LOG_TAG, "No se pudo leer fare_rules.txt", e);
        }
    }

    
    public int getZonasCruzadas(String parentIdOrigen, String parentIdDestino) {
        synchronized (lock) {
            String fareId = resolverFareId(parentIdOrigen, parentIdDestino);
            if (fareId == null) return -1;
            if (fareId.endsWith("_1")) return 1;
            if (fareId.endsWith("_2")) return 2;
            if (fareId.endsWith("_3")) return 3;
            return -1;
        }
    }

    
    public double getPrecioOcasional(String parentIdOrigen, String parentIdDestino) {
        synchronized (lock) {
            String fareId = resolverFareId(parentIdOrigen, parentIdDestino);
            if (fareId == null) return -1;
            Double p = tarifasIndividuales.get(fareId);
            return p != null ? p : -1;
        }
    }

    
    public double getPrecioOcasionalF20(@NonNull Context ctx, String o, String d) {
        return lookupArray(ctx, R.array.tarifas_ocasional_f20, getZonasCruzadas(o, d));
    }
    public double getPrecioOcasionalF50(@NonNull Context ctx, String o, String d) {
        return lookupArray(ctx, R.array.tarifas_ocasional_f50, getZonasCruzadas(o, d));
    }

    
    public double getPrecioBarik(@NonNull Context ctx, String o, String d) {
        return lookupArray(ctx, R.array.tarifas_barik, getZonasCruzadas(o, d));
    }
    public double getPrecioBarikF20(@NonNull Context ctx, String o, String d) {
        return lookupArray(ctx, R.array.tarifas_barik_f20, getZonasCruzadas(o, d));
    }
    public double getPrecioBarikF50(@NonNull Context ctx, String o, String d) {
        return lookupArray(ctx, R.array.tarifas_barik_f50, getZonasCruzadas(o, d));
    }

    
    public double getPrecioGizatrans(@NonNull Context ctx, int perfil) {
        String res;
        switch (perfil) {
            case 1: res = ctx.getString(R.string.tarifa_gizatrans_f20); break;
            case 2: res = ctx.getString(R.string.tarifa_gizatrans_f50); break;
            default: res = ctx.getString(R.string.tarifa_gizatrans);
        }
        try { return Double.parseDouble(res.replace(",", ".").trim()); }
        catch (NumberFormatException e) { return -1; }
    }

    private double lookupArray(Context ctx, int arrayRes, int zonas) {
        if (zonas <= 0) return -1;
        String[] arr = ctx.getResources().getStringArray(arrayRes);
        if (zonas - 1 >= arr.length) return -1;
        try { return Double.parseDouble(arr[zonas - 1].replace(",", ".").trim()); }
        catch (NumberFormatException e) { return -1; }
    }

    private String resolverFareId(String parentIdOrigen, String parentIdDestino) {
        String zO = zonasPorEstacion.get(parentIdOrigen);
        String zD = zonasPorEstacion.get(parentIdDestino);
        if (zO == null || zD == null) return null;
        String fareId = reglasTarifarias.get(zO + "|" + zD);
        if (fareId == null) fareId = reglasTarifarias.get(zD + "|" + zO);
        return fareId;
    }

    private void fail(Throwable t) {
        synchronized (lock) {
            ultimoError = t;
            cargando = false;
        }
        List<Callback> cbs;
        synchronized (lock) {
            cbs = new ArrayList<>(pendingCallbacks);
            pendingCallbacks.clear();
        }
        mainHandler.post(() -> {
            for (Callback cb : cbs) cb.onError(t);
        });
    }

    private void notifySuccess() {
        List<Callback> cbs;
        synchronized (lock) {
            cbs = new ArrayList<>(pendingCallbacks);
            pendingCallbacks.clear();
        }
        mainHandler.post(() -> {
            for (Callback cb : cbs) cb.onReady(this);
        });
    }

    public ShapePath getPathMasterL1() { synchronized (lock) { return displayL1; } }
    public ShapePath getPathMasterL2() { synchronized (lock) { return displayL2; } }
    public Map<String, ShapePath> getShapesPorId() { synchronized (lock) { return shapesPorId; } }
    public HorarioGestor getHorarioGestor() { synchronized (lock) { return horarioGestor; } }
    public List<MetroGestor.Station> getEstaciones() {
        synchronized (lock) { return new ArrayList<>(estaciones); }
    }

    private Map<String, ShapePath> cargarShapes(GtfsSource src) throws IOException {
        Map<String, TreeMap<Integer, LatLng>> raw = new HashMap<>();
        try (BufferedReader r = src.open("shapes.txt", "formas.txt")) {
            String header = r.readLine();
            if (header == null) return Collections.emptyMap();
            String[] cols = header.split(",", -1);
            int idxId = colIdx(cols, "shape_id");
            int idxLat = colIdx(cols, "shape_pt_lat");
            int idxLon = colIdx(cols, "shape_pt_lon");
            int idxSeq = colIdx(cols, "shape_pt_sequence");
            if (idxId < 0 || idxLat < 0 || idxLon < 0 || idxSeq < 0) {
                idxId = 0; idxLat = 1; idxLon = 2; idxSeq = 3;
            }
            String l;
            while ((l = r.readLine()) != null) {
                String[] c = l.split(",", -1);
                if (c.length <= Math.max(Math.max(idxId, idxLat), Math.max(idxLon, idxSeq))) continue;
                try {
                    String shapeId = c[idxId].trim();
                    double lat = Double.parseDouble(c[idxLat].trim());
                    double lon = Double.parseDouble(c[idxLon].trim());
                    int seq = Integer.parseInt(c[idxSeq].trim());
                    raw.computeIfAbsent(shapeId, k -> new TreeMap<>()).put(seq, new LatLng(lat, lon));
                } catch (NumberFormatException ignored) {}
            }
        }
        Map<String, ShapePath> result = new HashMap<>(raw.size());
        for (Map.Entry<String, TreeMap<Integer, LatLng>> e : raw.entrySet()) {
            List<LatLng> pts = new ArrayList<>(e.getValue().values());
            if (pts.size() >= 2) result.put(e.getKey(), new ShapePath(pts));
        }
        return result;
    }

    private ShapePath buildDisplayPath(Map<String, ShapePath> all, String id) {
        ShapePath direct = all.get(id);
        if (direct != null) return direct;
        for (ShapePath p : all.values()) if (p.points.size() >= 2) return p;
        return null;
    }

    static int colIdx(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    interface GtfsSource {
        BufferedReader open(String... names) throws IOException;
    }

    static final class DirectorySource implements GtfsSource {
        private final File dir;
        DirectorySource(File dir) { this.dir = dir; }
        @Override public BufferedReader open(String... names) throws IOException {
            for (String n : names) {
                File f = new File(dir, n);
                if (f.isFile()) {
                    return new BufferedReader(new InputStreamReader(
                            new FileInputStream(f), StandardCharsets.UTF_8));
                }
            }
            throw new FileNotFoundException(Arrays.toString(names));
        }
    }

    static final class AssetsSource implements GtfsSource {
        private final AssetManager am;
        AssetsSource(AssetManager am) { this.am = am; }
        @Override public BufferedReader open(String... names) throws IOException {
            for (String n : names) {
                try {
                    return new BufferedReader(new InputStreamReader(
                            am.open(n), StandardCharsets.UTF_8));
                } catch (IOException ignored) {}
            }
            throw new FileNotFoundException(Arrays.toString(names));
        }
    }
}