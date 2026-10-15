package org.example.inventory_module;

import java.sql.SQLException;
import java.util.Map;
import org.example.salesorder_module.controller.SalesOrderController;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SalesOrderController.class)
public class InventoryErrors {
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException error) {
        String message = "Không thể lưu đơn bán. Kiểm tra tồn kho và dữ liệu liên quan.";
        if (error.getMostSpecificCause() instanceof SQLException sql
                && "23514".equals(sql.getSQLState()) && sql.getMessage().contains("STOCK:")) {
            message = sql.getMessage().substring(sql.getMessage().indexOf("STOCK:") + 6).split("\n")[0].trim();
        }
        return ResponseEntity.status(409).body(Map.of("message", message));
    }
}
