package com.docente.proyectoejemplo.products;

import com.docente.proyectoejemplo.products.dto.ProductResponseDto;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    // Los grupos de Cognito (cognito:groups) ya llegan como authorities
    // "ROLE_Admin", "ROLE_Cliente", "ROLE_Colaborador" gracias a
    // CognitoJwtAuthenticationConverter.
    @GetMapping
    public ProductResponseDto getProducts(Authentication authentication) {
        if (hasRole(authentication, "Admin")) {
            return new ProductResponseDto(
                    "Admin",
                    "Acceso total: administración de productos, precios y stock",
                    List.of("Producto A", "Producto B", "Producto C")
            );
        }

        if (hasRole(authentication, "Colaborador")) {
            return new ProductResponseDto(
                    "Colaborador",
                    "Acceso operativo: consulta de stock disponible",
                    List.of("Producto A", "Producto B")
            );
        }

        if (hasRole(authentication, "Cliente")) {
            return new ProductResponseDto(
                    "Cliente",
                    "Catálogo público de productos",
                    List.of("Producto A")
            );
        }

        return new ProductResponseDto("Sin rol", "El usuario no tiene un rol reconocido", List.of());
    }

    private boolean hasRole(Authentication authentication, String role) {
        String expectedAuthority = "ROLE_" + role;
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(expectedAuthority::equals);
    }
}
