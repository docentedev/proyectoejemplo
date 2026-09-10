package com.docente.proyectoejemplo.products;

import com.docente.proyectoejemplo.products.dto.ProductRequestDto;
import com.docente.proyectoejemplo.products.dto.ProductResponseDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public List<ProductResponseDto> getAll() {
        return productService.getAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponseDto> getById(@PathVariable String id) {
        return productService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAnyRole('Admin', 'Colaborador')")
    @PostMapping
    public ResponseEntity<ProductResponseDto> create(@RequestBody ProductRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(request));
    }

    @PreAuthorize("hasRole('Admin')")
    @PutMapping("/{id}")
    public ResponseEntity<ProductResponseDto> update(@PathVariable String id, @RequestBody ProductRequestDto request) {
        return productService.update(id, request)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('Admin')")
    @PatchMapping("/{id}")
    public ResponseEntity<ProductResponseDto> partialUpdate(@PathVariable String id, @RequestBody ProductRequestDto request) {
        return productService.partialUpdate(id, request)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('Admin')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return productService.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}