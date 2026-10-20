package com.example.metrobilbao;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public class ViajeFragment extends Fragment {

    private static final String TAG = "MetroTren";
    private ProgressBar progressViaje;
    private Spinner spinnerOrigen, spinnerDestino;
    private Button btnConsultar;
    private TextView txtRuta, txtPrecio, txtTiempo;
    private ListView listProximos;

    private List<MetroGestor.Station> estaciones;
    private HorarioGestor horarioGestor;
    private Map<String, List<String>> grafoEstaciones;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater i, @Nullable ViewGroup c, @Nullable Bundle b) {
        return i.inflate(R.layout.fragment_viaje, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        spinnerOrigen  = v.findViewById(R.id.spinnerOrigen);
        spinnerDestino = v.findViewById(R.id.spinnerDestino);
        btnConsultar   = v.findViewById(R.id.btnConsultarViaje);
        txtRuta        = v.findViewById(R.id.txtRutaResultado);
        txtPrecio      = v.findViewById(R.id.txtPrecio);
        txtTiempo      = v.findViewById(R.id.txtTiempo);
        listProximos   = v.findViewById(R.id.listProximosTrenes);
        progressViaje = v.findViewById(R.id.progressViaje);
        progressViaje.setVisibility(View.VISIBLE);
        btnConsultar.setEnabled(false);

        btnConsultar.setOnClickListener(x -> consultarViaje());
        cargarDatosEnSegundoPlano();
    }

    private static String normalize(String id) {
        if (id == null) return "";
        return id.endsWith(".0") ? id.substring(0, id.length() - 2) : id;
    }

    private void cargarDatosEnSegundoPlano() {
    MetroRepository.getInstance().init(requireContext(), new MetroRepository.Callback() {
        @Override public void onReady(MetroRepository repo) {
            if (!isAdded()) return;
            estaciones = repo.getEstaciones();
            horarioGestor = repo.getHorarioGestor();
            construirGrafo();
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                progressViaje.setVisibility(View.GONE);
                btnConsultar.setEnabled(true);
                configurarSpinners();
            });
        }

        @Override public void onError(Throwable t) {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                progressViaje.setVisibility(View.GONE);
                new AlertDialog.Builder(requireContext())
                        .setTitle("Error")
                        .setMessage("No se pudieron cargar los datos:\n" + t.getMessage())
                        .setPositiveButton("OK", null).show();
            });
        }
    });
}


    private void construirGrafo() {
        grafoEstaciones = new HashMap<>();
        for (HorarioGestor.Trip trip : horarioGestor.getTodosLosTrips().values()) {
            List<HorarioGestor.StopTime> s = trip.stops;
            for (int i = 0; i < s.size() - 1; i++) {
                String a = normalize(s.get(i).stopId);
                String b = normalize(s.get(i + 1).stopId);
                if (a.isEmpty() || b.isEmpty() || a.equals(b)) continue;
                grafoEstaciones.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
                grafoEstaciones.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
            }
        }
    }

    private void configurarSpinners() {
        List<String> nombres = new ArrayList<>();
        for (MetroGestor.Station s : estaciones) nombres.add(s.name);
        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, nombres);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerOrigen.setAdapter(a);
        spinnerDestino.setAdapter(a);
    }

    private void consultarViaje() {
        int io = spinnerOrigen.getSelectedItemPosition();
        int id = spinnerDestino.getSelectedItemPosition();
        if (io < 0 || id < 0 || io == id) {
            Toast.makeText(requireContext(),
                    "Elige origen y destino distintos", Toast.LENGTH_SHORT).show();
            return;
        }
        MetroGestor.Station o = estaciones.get(io);
        MetroGestor.Station d = estaciones.get(id);
        String idO = normalize(o.parentId), idD = normalize(d.parentId);

        List<String> ruta = bfs(idO, idD);
        if (ruta == null || ruta.size() < 2) {
            txtRuta.setText("No se ha encontrado ruta entre estas estaciones.");
            txtTiempo.setText(""); txtPrecio.setText(""); listProximos.setAdapter(null); return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ruta.size(); i++) {
            sb.append(horarioGestor.getNombreParada(ruta.get(i)));
            if (i < ruta.size() - 1) sb.append("  →  ");
        }
        txtRuta.setText(sb.toString());
        txtTiempo.setText("Tiempo estimado: " + ((ruta.size() - 1) * 2) + " min");
        txtPrecio.setText("Barik: 1,02 €   |   Billete individual: 1,50 €");
        mostrarProximosTrenes(idO);
    }

    private List<String> bfs(String ini, String fin) {
        if (ini.isEmpty() || fin.isEmpty()) return null;
        Queue<String> q = new LinkedList<>();
        Map<String, String> padres = new HashMap<>();
        Set<String> vis = new HashSet<>();
        q.add(ini); vis.add(ini);
        while (!q.isEmpty()) {
            String a = q.poll();
            if (a.equals(fin)) {
                LinkedList<String> c = new LinkedList<>();
                String p = fin; while (p != null) { c.addFirst(p); p = padres.get(p); }
                return c;
            }
            List<String> vec = grafoEstaciones.get(a);
            if (vec == null) continue;
            for (String v : vec) if (!vis.contains(v)) {
                vis.add(v); padres.put(v, a); q.add(v);
            }
        }
        return null;
    }

    private void mostrarProximosTrenes(String stopNorm) {
        int ahora = HorarioGestor.ahoraSegundosMadrid();
        List<String> prox = new ArrayList<>();
        List<HorarioGestor.Trip> ts = horarioGestor.getTripsPorServicio()
                .get(horarioGestor.getServicioActivo());
        if (ts == null) ts = new ArrayList<>();
        for (HorarioGestor.Trip t : ts)
            for (HorarioGestor.StopTime st : t.stops) {
                if (!normalize(st.stopId).equals(stopNorm)) continue;
                if (st.arrivalSec <= ahora) continue;
                int diff = st.arrivalSec - ahora;
                if (diff > 3600) continue;
                String h = String.format(java.util.Locale.US, "%02d:%02d",
                        (st.arrivalSec / 3600) % 24, (st.arrivalSec / 60) % 60);
                prox.add(h + "   " + t.headsign);
            }
        java.util.Collections.sort(prox);
        if (prox.isEmpty()) prox.add("Sin trenes en los próximos 60 min.");
        listProximos.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, prox));
    }
}