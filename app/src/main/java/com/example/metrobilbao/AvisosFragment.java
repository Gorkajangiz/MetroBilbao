package com.example.metrobilbao;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AvisosFragment extends Fragment {

    private static final String TAG = "MetroTren";
    private static final String AVISOS_URL =
            "https://cms.metrobilbao.eus/get/open_data/json/avisos/es";

    private AvisosAdapter adapter;
    private final List<AvisoItem> avisos = new ArrayList<>();
    private ProgressBar progress;
    private TextView vacio;
    private boolean cargado = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater i, @Nullable ViewGroup c, @Nullable Bundle b) {
        return i.inflate(R.layout.fragment_avisos, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        RecyclerView rv = v.findViewById(R.id.recyclerAvisos);
        progress = v.findViewById(R.id.progressAvisos);
        vacio = v.findViewById(R.id.txtVacioAvisos);

        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new AvisosAdapter(avisos, this::mostrarDetalle);
        rv.setAdapter(adapter);

        if (savedInstanceState != null) cargado = savedInstanceState.getBoolean("cargado", false);

        if (!cargado) {
            cargarAsync();
        } else {
            progress.setVisibility(View.GONE);
            if (avisos.isEmpty()) vacio.setVisibility(View.VISIBLE);
        }
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("cargado", cargado);
    }

    @Override public void onDestroyView() {
        executor.shutdownNow();
        super.onDestroyView();
    }

    private void mostrarDetalle(AvisoItem item) {
        new AlertDialog.Builder(requireContext())
                .setTitle(item.titulo)
                .setMessage(item.descripcion + "\n\n" + item.fechas)
                .setPositiveButton("Cerrar", null).show();
    }

    private void cargarAsync() {
        progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            List<AvisoItem> lista = new ArrayList<>();
            String error = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(AVISOS_URL).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "MetroBilbaoApp/1.0");
                if (conn.getResponseCode() != 200) {
                    error = "HTTP " + conn.getResponseCode();
                } else {
                    StringBuilder sb = new StringBuilder();
                    try (InputStream in = conn.getInputStream();
                         BufferedReader br = new BufferedReader(
                                 new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                    JSONObject root = new JSONObject(sb.toString());
                    JSONArray data = root.optJSONArray("data");
                    if (data != null) {
                        long hoy = System.currentTimeMillis();
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject o = data.getJSONObject(i);
                            if (!o.optBoolean("is_published", true)) continue;
                            String titulo = o.optString("title_es", "").trim();
                            if (titulo.isEmpty()) continue;

                            // FIX #5: descartar avisos caducados
                            String fin = o.optString("publish_end_date", "").trim();
                            long finMs = parseFecha(fin);
                            if (finMs > 0 && finMs < hoy) continue;

                            String tipo = o.optString("type", "").trim();

                            // FIX #5: usar descripción real si existe
                            String descripcion = o.optString("description_es", "").trim();
                            if (descripcion.isEmpty())
                                descripcion = o.optString("summary_es", "").trim();
                            if (descripcion.isEmpty())
                                descripcion = tipo.isEmpty() ? "" : "Tipo: " + tipo;

                            String inicio = o.optString("publish_start_date", "").trim();
                            StringBuilder fechas = new StringBuilder();
                            if (!inicio.isEmpty()) fechas.append("Desde: ").append(inicio);
                            if (!fin.isEmpty()) {
                                if (fechas.length() > 0) fechas.append("   ");
                                fechas.append("Hasta: ").append(fin);
                            }

                            lista.add(new AvisoItem(titulo, descripcion, fechas.toString()));
                        }
                    }
                }
            } catch (Throwable t) {
                android.util.Log.e(TAG, "Error avisos", t);
                error = t.getMessage();
            } finally {
                if (conn != null) conn.disconnect();
            }
            final String errF = error;
            final List<AvisoItem> listaF = lista;
            mainHandler.post(() -> {
                cargado = true;
                if (!isAdded() || progress == null) return;
                progress.setVisibility(View.GONE);
                avisos.clear();
                avisos.addAll(listaF);
                adapter.notifyDataSetChanged();
                if (listaF.isEmpty()) {
                    vacio.setVisibility(View.VISIBLE);
                    if (errF != null) Toast.makeText(requireContext(),
                            "No se pudieron cargar avisos: " + errF,
                            Toast.LENGTH_LONG).show();
                } else {
                    vacio.setVisibility(View.GONE);
                }
            });
        });
    }

    // FIX #5: parser de fechas tolerante (yyyy-MM-dd, ISO 8601 y con hora)
    private static long parseFecha(String s) {
        if (s == null || s.isEmpty()) return 0L;
        s = s.replace('T', ' ').trim();
        try {
            if (s.length() >= 19) {
                return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        java.util.Locale.US).parse(s.substring(0, 19)).getTime();
            }
            if (s.length() >= 10) {
                return new java.text.SimpleDateFormat("yyyy-MM-dd",
                        java.util.Locale.US).parse(s.substring(0, 10)).getTime();
            }
        } catch (Exception ignored) {}
        return 0L;
    }
}