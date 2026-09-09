package com.docente.proyectoejemplo.products.dto;

import java.util.List;

public record ProductResponseDto(String role, String message, List<String> products) {
}
