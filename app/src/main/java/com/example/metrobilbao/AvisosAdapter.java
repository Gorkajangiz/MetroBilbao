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
    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(AvisoItem item);
    }

    public AvisosAdapter(List<AvisoItem> items, OnItemClickListener listener) {
        this.items = items;
        this.listener = listener;
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
        holder.descripcion.setText(item.descripcion);
        holder.fechas.setText(item.fechas);
        holder.itemView.setOnClickListener(v -> listener.onItemClick(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView titulo, descripcion, fechas;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            titulo = itemView.findViewById(R.id.txtTituloAviso);
            descripcion = itemView.findViewById(R.id.txtDescAviso);
            fechas = itemView.findViewById(R.id.txtFechasAviso);
        }
    }
}