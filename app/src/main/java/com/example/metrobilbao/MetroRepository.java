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

/**
 * Singleton que carga el GTFS una vez.
 * - Primero intenta GtfsUpdater (filesDir/gtfs/).
 * - Si falla, usa assets/ (paradas.txt, viajes.txt, etc.).
 * - Cada trip proyecta sobre SU shape real (no sobre 2 maestros).
 */
public class MetroRepository {

    private static final String LOG_TAG = "MetroTren";

    // Para dibujar en el mapa (paralelo-separado)
    private static final String DISPLAY_L1 = "ETX_PLE";
    private static final String DISPLAY_L2 = "BSR_KAB";

    // Geografía para separar visualmente L1/L2 en el tronco común
    private static final double METERS_PER_DEG_LAT = 111320.0;
    private static final double PARALLEL_OFFSET_M = 7.0;
    private static final double PARALLEL_THRESHOLD_M = 100.0;
    private static final double REF_LAT = 43.2630, REF_LNG = -2.9350;
    private static final double M_PER_DEG_LAT_APPROX = 111320.0;
    private static final double M_PER_DEG_LNG_APPROX =
            M_PER_DEG_LAT_APPROX * Math.cos(Math.toRadians(REF_LAT));
    private static final double GRID_CELL_M = 100.0;

    public interface Callback {
        void onReady(@NonNull MetroRepository repository);
        void onError(@NonNull Throwable error);
    }

    public interface ProgressListener extends GtfsUpdater.Progress {
        // vacío — hereda onProgress con la misma firma
    }

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
        Log.d(LOG_TAG, "MetroRepository: " + shapes.size() + " shapes cargados en "
                + (System.currentTimeMillis() - t0) + "ms");

        HorarioGestor hg = new HorarioGestor();
        hg.cargar(source, shapes);
        Log.d(LOG_TAG, "MetroRepository: HorarioGestor listo en "
                + (System.currentTimeMillis() - t0) + "ms");

        List<MetroGestor.Station> ests = new MetroGestor().getStations(source);
        Log.d(LOG_TAG, "MetroRepository: " + ests.size() + " estaciones");

        ShapePath d1 = buildDisplayPath(shapes, DISPLAY_L1);
        ShapePath d2 = buildDisplayPath(shapes, DISPLAY_L2);

        synchronized (lock) {
            this.shapesPorId = shapes;
            this.displayL1 = d1;
            this.displayL2 = d2;
            this.horarioGestor = hg;
            this.estaciones.clear();
            this.estaciones.addAll(ests);
            listo = true;
            cargando = false;
        }
        notifySuccess();
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

    // ---- API pública ----

    public ShapePath getPathMasterL1() { synchronized (lock) { return displayL1; } }
    public ShapePath getPathMasterL2() { synchronized (lock) { return displayL2; } }
    public Map<String, ShapePath> getShapesPorId() { synchronized (lock) { return shapesPorId; } }
    public HorarioGestor getHorarioGestor() { synchronized (lock) { return horarioGestor; } }
    public List<MetroGestor.Station> getEstaciones() {
        synchronized (lock) { return new ArrayList<>(estaciones); }
    }

    // ---- Carga de shapes ----

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
                // fallback a orden canónico GTFS
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
        // fallback: primer shape que tenga puntos
        for (ShapePath p : all.values()) if (p.points.size() >= 2) return p;
        return null;
    }

    // ---- Helpers de CSV ----

    static int colIdx(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    // ---- Sources ----

    interface GtfsSource {
        /** Intenta abrir los nombres en orden. Lanza FileNotFoundException si ninguno existe. */
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

    // ---- API usada por MapaFragment para dibujar ----

    // (Nada más — la lógica de paralelo ya no se usa aquí porque cada shape
    //  ya cubre su recorrido completo. Los displayL1/displayL2 son shapes
    //  íntegros del propio fichero, sin fusión ni offsets.)
}