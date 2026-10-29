package com.example.metrobilbao;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NoticiasFragment extends Fragment {

    private static final String TAG = "MetroTren";
    private static final String RSS_URL =
            "https://cms.metrobilbao.eus/get/open_data/rss/noticias/es";

    private NoticiasAdapter adapter;
    private final List<NewsItem> noticias = new ArrayList<>();
    private ProgressBar progress;
    private TextView vacio;
    private boolean cargado = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater i, @Nullable ViewGroup c, @Nullable Bundle b) {
        return i.inflate(R.layout.fragment_noticias, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        RecyclerView rv = v.findViewById(R.id.recyclerNoticias);
        progress = v.findViewById(R.id.progressNoticias);
        vacio = v.findViewById(R.id.txtVacioNoticias);

        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new NoticiasAdapter(noticias, this::mostrarDetalle);
        rv.setAdapter(adapter);

        if (savedInstanceState != null) cargado = savedInstanceState.getBoolean("cargado", false);

        if (!cargado || noticias.isEmpty()) {
            cargarAsync();
        } else {
            progress.setVisibility(View.GONE);
            vacio.setVisibility(View.GONE);
        }
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("cargado", cargado);
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
    }

    @Override public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void mostrarDetalle(NewsItem item) {
        if (!isAdded() || getContext() == null) return;

        android.app.Dialog dialog = new android.app.Dialog(
                requireContext(), R.style.Theme_MetroBilbao_Dialog);

        View vista = getLayoutInflater().inflate(R.layout.display_noticia, null);

        TextView titulo   = vista.findViewById(R.id.detailTituloNoticia);
        TextView fecha    = vista.findViewById(R.id.detailFechaNoticia);
        TextView desc     = vista.findViewById(R.id.detailDescNoticia);
        ImageView img     = vista.findViewById(R.id.detailImgNoticia);
        Button btnLink    = vista.findViewById(R.id.detailBtnLink);
        Button btnCerrar  = vista.findViewById(R.id.detailBtnCerrar);

        titulo.setText(item.titulo);
        fecha.setText(item.fecha == null ? "" : item.fecha);
        desc.setText(item.descripcion == null ? "" : item.descripcion);

        if (item.imagenUrl != null && !item.imagenUrl.isEmpty()) {
            img.setVisibility(View.VISIBLE);
            Glide.with(requireContext())
                    .load(item.imagenUrl)
                    .placeholder(android.R.color.darker_gray)
                    .error(android.R.color.darker_gray)
                    .into(img);
        } else {
            img.setVisibility(View.GONE);
        }

        if (item.link != null && !item.link.isEmpty()) {
            btnLink.setVisibility(View.VISIBLE);
            btnLink.setOnClickListener(v -> {
                try {
                    startActivity(new android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(item.link)));
                } catch (Exception e) {
                    Toast.makeText(requireContext(),
                            "No se pudo abrir el enlace", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            btnLink.setVisibility(View.GONE);
        }

        btnCerrar.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(vista);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90);
            dialog.getWindow().setLayout(
                    width, android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private void cargarAsync() {
        progress.setVisibility(View.VISIBLE);
        vacio.setVisibility(View.GONE);
        executor.execute(() -> {
            List<NewsItem> lista = new ArrayList<>();
            String error = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(RSS_URL).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("User-Agent", "MetroBilbaoApp/1.0");
                conn.setRequestProperty("Accept-Encoding", "identity");
                int code = conn.getResponseCode();
                if (code != 200) {
                    error = "HTTP " + code;
                } else {
                    try (InputStream in = conn.getInputStream()) {
                        lista = parsearRss(in);
                    }
                }
            } catch (Throwable t) {
                android.util.Log.e(TAG, "Error noticias", t);
                error = t.getMessage();
            } finally {
                if (conn != null) conn.disconnect();
            }

            android.util.Log.d(TAG, "Parseados: " + lista.size());

            final String errF = error;
            final List<NewsItem> listaF = lista;
            mainHandler.post(() -> {
                cargado = true;
                if (!isAdded() || progress == null) return;
                progress.setVisibility(View.GONE);
                noticias.clear();
                noticias.addAll(listaF);
                adapter.notifyDataSetChanged();
                android.util.Log.d(TAG, "Adapter count: " + adapter.getItemCount());
                if (listaF.isEmpty()) {
                    vacio.setVisibility(View.VISIBLE);
                    if (errF != null) Toast.makeText(requireContext(),
                            "No se pudieron cargar noticias: " + errF,
                            Toast.LENGTH_LONG).show();
                } else {
                    vacio.setVisibility(View.GONE);
                }
            });
        });
    }

    private List<NewsItem> parsearRss(InputStream in) throws Exception {
        List<NewsItem> lista = new ArrayList<>();
        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(in, null);
        int ev = p.getEventType();
        NewsItem cur = null;
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String tag = p.getName();
                if (tag.equals("item") || tag.equals("entry")) {
                    cur = new NewsItem();
                } else if (cur != null) {
                    if (tag.equals("title")) {
                        String t = leerTextoCompleto(p).trim();
                        if (cur.titulo == null || cur.titulo.isEmpty()) cur.titulo = t;
                    } else if (tag.equals("description") || tag.equals("summary")) {
                        String raw = leerTextoCompleto(p);
                        if (cur.descripcion == null || cur.descripcion.isEmpty()) {
                            cur.descripcion = Html.fromHtml(raw,
                                    Html.FROM_HTML_MODE_LEGACY).toString().trim();
                        }
                    } else if (tag.equals("pubDate") || tag.equals("updated") || tag.equals("date")) {
                        String f = leerTextoCompleto(p).trim();
                        if (cur.fecha == null || cur.fecha.isEmpty()) {
                            cur.fecha = formatearFecha(f);
                        }
                    } else if (tag.equals("link")) {
                        String href = p.getAttributeValue(null, "href");
                        String link = leerTextoCompleto(p).trim();
                        if (href != null && !href.isEmpty()) link = href;
                        if ((cur.link == null || cur.link.isEmpty()) && !link.isEmpty()) {
                            cur.link = link;
                        }
                    } else if (tag.equals("image")) {
                        String urlAttr = p.getAttributeValue(null, "url");
                        String inner = leerTextoCompleto(p).trim();
                        if (cur.imagenUrl == null || cur.imagenUrl.isEmpty()) {
                            if (urlAttr != null && !urlAttr.isEmpty()) {
                                cur.imagenUrl = urlAttr;
                            } else if (inner.startsWith("http")) {
                                cur.imagenUrl = inner;
                            }
                        }
                    } else if (tag.equals("thumbnail")
                            || tag.equals("content")
                            || tag.equals("enclosure")) {
                        String u = p.getAttributeValue(null, "url");
                        String type = p.getAttributeValue(null, "type");
                        String medium = p.getAttributeValue(null, "medium");
                        boolean esImagen = (type == null || type.startsWith("image/"))
                                || "image".equals(medium);
                        if (u != null && !u.isEmpty() && esImagen
                                && (cur.imagenUrl == null || cur.imagenUrl.isEmpty())) {
                            cur.imagenUrl = u;
                        }
                        leerTextoCompleto(p);
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                String tag = p.getName();
                if ((tag.equals("item") || tag.equals("entry")) && cur != null) {
                    if (cur.titulo != null && !cur.titulo.isEmpty()) lista.add(cur);
                    cur = null;
                }
            }
            ev = p.next();
        }
        return lista;
    }

    private static String leerTextoCompleto(XmlPullParser p) throws Exception {
        StringBuilder sb = new StringBuilder();
        int eventType = p.next();
        while (eventType != XmlPullParser.END_TAG && eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.TEXT || eventType == XmlPullParser.CDSECT) {
                sb.append(p.getText());
            } else if (eventType == XmlPullParser.START_TAG) {
                sb.append(leerTextoCompleto(p));
            }
            eventType = p.next();
        }
        return sb.toString();
    }

    private static String formatearFecha(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String s = raw.trim();
        String[] patterns = {
                "EEE, dd MMM yyyy HH:mm:ss Z",
                "EEE, dd MMM yyyy HH:mm:ss z",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd"
        };
        for (String pat : patterns) {
            try {
                java.text.SimpleDateFormat in =
                        new java.text.SimpleDateFormat(pat, java.util.Locale.US);
                java.util.Date d = in.parse(s);
                java.text.SimpleDateFormat out =
                        new java.text.SimpleDateFormat("d MMM yyyy · HH:mm",
                                new java.util.Locale("es", "ES"));
                return out.format(d);
            } catch (Exception ignored) {}
        }
        return s;
    }
}