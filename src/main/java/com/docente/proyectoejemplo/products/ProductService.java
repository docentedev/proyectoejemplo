package com.docente.proyectoejemplo.products;

import com.docente.proyectoejemplo.products.dto.ProductRequestDto;
import com.docente.proyectoejemplo.products.dto.ProductResponseDto;
import com.docente.proyectoejemplo.products.entity.Product;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProductService {
    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public List<ProductResponseDto> getAll() {
        return productRepository.findAll().stream()
                .map(this::toResponseDto)
                .toList();
    }

    public Optional<ProductResponseDto> getById(String id) {
        return productRepository.findById(id).map(this::toResponseDto);
    }

    public ProductResponseDto create(ProductRequestDto request) {
        Product product = new Product(UUID.randomUUID().toString(), request.name(), request.price(), request.stock());
        return toResponseDto(productRepository.save(product));
    }

    public Optional<ProductResponseDto> update(String id, ProductRequestDto request) {
        return productRepository.findById(id).map(product -> {
            product.setName(request.name());
            product.setPrice(request.price());
            product.setStock(request.stock());
            return toResponseDto(productRepository.save(product));
        });
    }

    public Optional<ProductResponseDto> partialUpdate(String id, ProductRequestDto request) {
        return productRepository.findById(id).map(product -> {
            if (request.name() != null) product.setName(request.name());
            if (request.price() != null) product.setPrice(request.price());
            if (request.stock() != null) product.setStock(request.stock());
            return toResponseDto(productRepository.save(product));
        });
    }

    public boolean delete(String id) {
        if (!productRepository.existsById(id)) {
            return false;
        }
        productRepository.deleteById(id);
        return true;
    }

    private ProductResponseDto toResponseDto(Product product) {
        return new ProductResponseDto(product.getId(), product.getName(), product.getPrice(), product.getStock());
    }
}
