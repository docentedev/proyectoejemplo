package com.docente.proyectoejemplo.products;

import com.docente.proyectoejemplo.products.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository  extends JpaRepository<Product, String> {
}
