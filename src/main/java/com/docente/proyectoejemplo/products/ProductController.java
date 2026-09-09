package com.docente.proyectoejemplo.products;

import com.docente.proyectoejemplo.products.dto.ProductResponseDto;
import com.docente.proyectoejemplo.products.dto.ProductRequestDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final List<ProductResponseDto> productTable;

    public ProductController() {
        this.productTable = new ArrayList<>();
        this.productTable.add(new ProductResponseDto("ABC-02", "Refrigerador", 450.0, 8));
        this.productTable.add(new ProductResponseDto("ABC-03", "Microondas", 85.5, 25));
        this.productTable.add(new ProductResponseDto("ABC-04", "Televisor Smart 4K", 350.0, 15));
        this.productTable.add(new ProductResponseDto("ABC-05", "Licuadora", 45.0, 30));
        this.productTable.add(new ProductResponseDto("ABC-06", "Cafetera Express", 120.0, 10));
        this.productTable.add(new ProductResponseDto("ABC-07", "Aspiradora Robot", 180.0, 5));
        this.productTable.add(new ProductResponseDto("ABC-08", "Horno Eléctrico", 95.0, 14));
        this.productTable.add(new ProductResponseDto("ABC-09", "Ventilador de Torre", 60.0, 40));
        this.productTable.add(new ProductResponseDto("ABC-10", "Aire Acondicionado", 299.9, 6));
        this.productTable.add(new ProductResponseDto("ABC-11", "Extractor de Jugos", 75.0, 18));
    }

    @GetMapping
    public List<ProductResponseDto> getAll() {
        return this.productTable;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponseDto> getById(@PathVariable String id) {
        return this.productTable.stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAnyRole('Admin', 'Colaborador')")
    @PostMapping
    public ResponseEntity<ProductResponseDto> create(@RequestBody ProductRequestDto request) {
        ProductResponseDto product = new ProductResponseDto(UUID.randomUUID().toString(), request.name(), request.price(), request.stock());
        this.productTable.add(product);
        return ResponseEntity.status(HttpStatus.CREATED).body(product);
    }

    @PreAuthorize("hasRole('Admin')")
    @PutMapping("/{id}")
    public ResponseEntity<ProductResponseDto> update(@PathVariable String id, @RequestBody ProductRequestDto request) {
        return this.productTable.stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .map(original -> {
                    ProductResponseDto actualizado = new ProductResponseDto(original.id(), request.name(), request.price(), request.stock());
                    this.productTable.set(this.productTable.indexOf(original), actualizado);
                    return ResponseEntity.ok(actualizado);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('Admin')")
    @PatchMapping("/{id}")
    public ResponseEntity<ProductResponseDto> partialUpdate(@PathVariable String id, @RequestBody ProductRequestDto request) {
        return this.productTable.stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .map(original -> {
                    ProductResponseDto actualizado = new ProductResponseDto(
                            original.id(),
                            request.name() != null ? request.name() : original.name(),
                            request.price() != null ? request.price() : original.price(),
                            request.stock() != null ? request.stock() : original.stock()
                    );
                    this.productTable.set(this.productTable.indexOf(original), actualizado);
                    return ResponseEntity.ok(actualizado);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('Admin')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        boolean removed = this.productTable.removeIf(p -> p.id().equals(id));
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
