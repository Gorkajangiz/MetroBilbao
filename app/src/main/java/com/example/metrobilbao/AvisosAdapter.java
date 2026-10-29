package com.example.metrobilbao;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class AvisosAdapter extends RecyclerView.Adapter<AvisosAdapter.ViewHolder> {

    private final List<AvisoItem> items;

    public AvisosAdapter(List<AvisoItem> items) {
        this.items = items;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_aviso, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AvisoItem item = items.get(position);
        holder.titulo.setText(item.titulo);
        holder.descripcion.setText(item.descripcion == null ? "" : item.descripcion);

        if (item.fechas != null && !item.fechas.isEmpty()) {
            holder.fechas.setVisibility(View.VISIBLE);
            holder.fechas.setText(item.fechas);
        } else {
            holder.fechas.setVisibility(View.GONE);
        }

        String tipoLegible = tipoLegible(item.tipo);
        if (!tipoLegible.isEmpty()) {
            holder.tipo.setVisibility(View.VISIBLE);
            holder.tipo.setText(tipoLegible.toUpperCase());
        } else {
            holder.tipo.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    
    private static String tipoLegible(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        if (s.startsWith("Aviso_")) s = s.substring(6);
        switch (s) {
            case "Ascensor":          return "Ascensor";
            case "Escalera":          return "Escalera mecánica";
            case "Escalera_Mecanica": return "Escalera mecánica";
            case "Averia":            return "Avería";
            case "Obras":             return "Obras";
            case "Accesibilidad":     return "Accesibilidad";
            case "Incidencia":        return "Incidencia";
            case "Servicio":          return "Servicio";
            default:
                String limpio = s.replace('_', ' ').trim();
                if (limpio.isEmpty()) return "";
                StringBuilder sb = new StringBuilder();
                for (String palabra : limpio.split("\\s+")) {
                    if (palabra.isEmpty()) continue;
                    sb.append(Character.toUpperCase(palabra.charAt(0)))
                            .append(palabra.substring(1).toLowerCase())
                            .append(' ');
                }
                return sb.toString().trim();
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView titulo, descripcion, fechas, tipo;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            titulo      = itemView.findViewById(R.id.txtTituloAviso);
            descripcion = itemView.findViewById(R.id.txtDescAviso);
            fechas      = itemView.findViewById(R.id.txtFechasAviso);
            tipo        = itemView.findViewById(R.id.txtTipoAviso);
        }
    }
}