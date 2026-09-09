package com.docente.proyectoejemplo.products;

import com.docente.proyectoejemplo.products.dto.ProductRequestDto;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final DynamoDbTable<Product> productTable;

    public ProductController(DynamoDbTable<Product> productTable) {
        this.productTable = productTable;
    }

    // Datos de ejemplo solo la primera vez que arranca la app contra una tabla vacía
    @PostConstruct
    void seedIfEmpty() {
        boolean empty = !productTable.scan().items().iterator().hasNext();
        if (empty) {
            save(new Product(UUID.randomUUID().toString(), "Producto A", 10.0, 100));
            save(new Product(UUID.randomUUID().toString(), "Producto B", 20.0, 50));
            save(new Product(UUID.randomUUID().toString(), "Producto C", 30.0, 25));
        }
    }

    // GET: cualquier usuario autenticado (Admin, Cliente o Colaborador)
    @GetMapping
    public List<Product> getAll() {
        return productTable.scan().items().stream().collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> getById(@PathVariable String id) {
        Product product = productTable.getItem(key(id));
        return product != null ? ResponseEntity.ok(product) : ResponseEntity.notFound().build();
    }

    // POST: solo Admin y Colaborador
    @PreAuthorize("hasAnyRole('Admin', 'Colaborador')")
    @PostMapping
    public ResponseEntity<Product> create(@RequestBody ProductRequestDto request) {
        Product product = new Product(UUID.randomUUID().toString(), request.name(), request.price(), request.stock());
        save(product);
        return ResponseEntity.status(HttpStatus.CREATED).body(product);
    }

    // PUT: solo Admin
    @PreAuthorize("hasRole('Admin')")
    @PutMapping("/{id}")
    public ResponseEntity<Product> update(@PathVariable String id, @RequestBody ProductRequestDto request) {
        Product existing = productTable.getItem(key(id));
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        existing.setName(request.name());
        existing.setPrice(request.price());
        existing.setStock(request.stock());
        save(existing);
        return ResponseEntity.ok(existing);
    }

    // PATCH: solo Admin
    @PreAuthorize("hasRole('Admin')")
    @PatchMapping("/{id}")
    public ResponseEntity<Product> partialUpdate(@PathVariable String id, @RequestBody ProductRequestDto request) {
        Product existing = productTable.getItem(key(id));
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (request.name() != null) {
            existing.setName(request.name());
        }
        if (request.price() != null) {
            existing.setPrice(request.price());
        }
        if (request.stock() != null) {
            existing.setStock(request.stock());
        }
        save(existing);
        return ResponseEntity.ok(existing);
    }

    // DELETE: solo Admin
    @PreAuthorize("hasRole('Admin')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        Key key = key(id);
        if (productTable.getItem(key) == null) {
            return ResponseEntity.notFound().build();
        }
        productTable.deleteItem(key);
        return ResponseEntity.noContent().build();
    }

    private void save(Product product) {
        productTable.putItem(product);
    }

    private Key key(String id) {
        return Key.builder().partitionValue(id).build();
    }
}
