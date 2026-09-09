package com.docente.proyectoejemplo.products;

public record Product(String id, String name, Double price, Integer stock) {
    // Constructor de copia
    public Product(String id, Product unProduct) {
        this(id, unProduct.name(), unProduct.price(), unProduct.stock());
    }
}