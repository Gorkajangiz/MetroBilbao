package com.example.metrobilbao;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.List;

public class NoticiasAdapter extends RecyclerView.Adapter<NoticiasAdapter.ViewHolder> {

    private final List<NewsItem> items;
    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(NewsItem item);
    }

    public NoticiasAdapter(List<NewsItem> items, OnItemClickListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_noticia, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        NewsItem item = items.get(position);
        holder.titulo.setText(item.titulo);
        holder.fecha.setText(item.fecha);
        holder.descripcion.setText(item.descripcion);

        if (item.imagenUrl != null && !item.imagenUrl.isEmpty()) {
            holder.imagen.setVisibility(View.VISIBLE);
            Glide.with(holder.itemView.getContext())
                    .load(item.imagenUrl)
                    .placeholder(android.R.color.darker_gray)
                    .error(android.R.color.darker_gray)
                    .into(holder.imagen);
        } else {
            holder.imagen.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> listener.onItemClick(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView imagen;
        TextView titulo, fecha, descripcion;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            imagen = itemView.findViewById(R.id.imgNoticia);
            titulo = itemView.findViewById(R.id.txtTituloNoticia);
            fecha = itemView.findViewById(R.id.txtFechaNoticia);
            descripcion = itemView.findViewById(R.id.txtDescNoticia);
        }
    }
}