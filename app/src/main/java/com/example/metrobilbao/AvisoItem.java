package com.example.metrobilbao;

public class AvisoItem {
    public final String titulo;
    public final String descripcion;
    public final String fechas;
    public final String tipo;

    public AvisoItem(String titulo, String descripcion, String fechas, String tipo) {
        this.titulo = titulo;
        this.descripcion = descripcion;
        this.fechas = fechas;
        this.tipo = tipo;
    }
}