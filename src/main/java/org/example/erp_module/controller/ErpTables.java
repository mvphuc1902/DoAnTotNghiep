package org.example.erp_module.controller;

import java.util.List;
import java.util.Map;

final class ErpTables {
    private ErpTables() {}
    static final Map<String, List<String>> COLUMNS = Map.ofEntries(
        Map.entry("sales_setup_codes", List.of("id", "category", "code", "name", "active")),
        Map.entry("sales_document_lines", List.of("id", "document_type", "document_no", "status", "line_status", "document_date", "customer_no", "item_no", "description", "unit_of_measure", "quantity", "unit_price", "discount_percent", "discount_amount", "vat_percent", "vat_amount", "line_amount", "currency_code", "location_name", "related_document_no", "document_id", "line_no", "source_table", "source_line_id")),
        Map.entry("sales_headers", List.of("id", "document_type", "quote_no", "customer_id", "location_id", "order_date", "requested_delivery_date", "currency_code", "payment_terms_code", "payment_method_code", "salesperson_code", "status", "subtotal", "discount_amount", "vat_amount", "total_amount", "notes", "sales_order_id", "related_sales_order_id", "sales_return_id", "sales_invoice_id", "created_at", "updated_at")),
        Map.entry("sales_returns", List.of("id", "return_no", "sales_order_id", "location_id", "return_date", "reason", "status", "received_at", "created_at")),
        Map.entry("sales_return_lines", List.of("id", "return_id", "sales_order_line_id", "item_id", "quantity")),
        Map.entry("sales_quote_lines", List.of("id", "sales_header_id", "line_no", "item_id", "description", "unit_of_measure", "quantity", "unit_price", "discount_percent", "vat_percent")),
        Map.entry("accounts", List.of("id", "account_no", "account_name", "parent_id", "account_type", "active", "created_at")),
        Map.entry("customer_ledger", List.of("id", "entry_no", "customer_id", "document_type", "document_id", "posting_date", "debit", "credit", "balance", "description", "created_at")),
        Map.entry("customers", List.of("id", "customer_no", "name", "address", "phone", "email", "currency_code", "payment_terms_code", "payment_method_code", "blocked", "created_at", "updated_at")),
        Map.entry("employees", List.of("id", "employee_code", "name", "email", "phone", "department", "position", "hire_date", "user_id", "active", "created_at")),
        Map.entry("general_ledger", List.of("id", "document_no", "document_type", "posting_date", "account_id", "description", "debit", "credit", "created_at")),
        Map.entry("inventory", List.of("id", "item_id", "product_id", "location_id", "quantity", "reserved_quantity", "available_quantity", "average_cost", "created_at", "updated_at")),
        Map.entry("inventory_ledger", List.of("id", "entry_no", "item_id", "product_id", "location_id", "document_type", "document_id", "posting_date", "quantity_in", "quantity_out", "unit_cost", "balance_quantity", "description", "created_at")),
        Map.entry("items", List.of("id", "item_no", "description", "item_category", "item_type", "quantity", "base_unit_of_measure", "unit_price", "unit_cost", "inventory_posting_group", "gen_prod_posting_group", "vat_prod_posting_group", "blocked", "created_at", "updated_at")),
        Map.entry("item_bom", List.of("id", "parent_item_id", "component_item_id", "quantity")),
        Map.entry("production_orders", List.of("id", "production_no", "item_id", "location_id", "quantity", "status", "notes", "completed_at", "created_at", "updated_at")),
        Map.entry("production_components", List.of("id", "production_order_id", "component_item_id", "quantity_per_unit", "consumed_quantity")),
        Map.entry("locations", List.of("id", "code", "name", "address", "created_at", "location_id")),
        Map.entry("payment_allocations", List.of("id", "payment_id", "sales_invoice_id", "purchase_invoice_id", "allocated_amount", "created_at")),
        Map.entry("payments", List.of("id", "payment_no", "payment_type", "customer_id", "supplier_id", "payment_date", "amount", "payment_method", "account_id", "description", "status", "created_at")),
        Map.entry("purchase_headers", List.of("id", "document_type", "document_no", "vendor_id", "order_date", "posting_date", "requested_receipt_date", "promised_receipt_date", "vendor_reference", "external_document_no", "currency_code", "payment_terms_code", "shipment_method_code", "salesperson_code", "location_code", "status", "discount_percent", "discount_amount", "vat_amount", "total_amount", "notes", "created_at", "updated_at")),
        Map.entry("purchase_lines", List.of("id", "purchase_header_id", "document_type", "document_no", "line_no", "item_id", "description", "location_code", "unit_of_measure", "quantity", "quantity_received", "quantity_invoiced", "unit_price", "discount_percent", "discount_amount", "vat_percent", "vat_amount", "line_amount", "source_purchase_line_id", "purchase_receipt_line_id", "created_at")),
        Map.entry("purchase_receipt_lines", List.of("id", "receipt_id", "line_no", "purchase_order_line_id", "item_id", "quantity", "unit_of_measure", "location_code")),
        Map.entry("purchase_receipts", List.of("id", "receipt_no", "purchase_order_id", "vendor_id", "receipt_date", "location_code", "created_at")),
        Map.entry("purchase_returns", List.of("id", "return_no", "supplier_id", "location_id", "return_date", "reason", "status", "subtotal", "tax_amount", "total_amount", "created_at")),
        Map.entry("sales_invoice_lines", List.of("id", "invoice_id", "line_no", "sales_order_line_id", "item_id", "description", "quantity", "unit_price", "vat_percent", "vat_amount", "line_amount")),
        Map.entry("sales_invoices", List.of("id", "invoice_no", "sales_order_id", "customer_id", "invoice_date", "posting_date", "currency_code", "total_amount", "vat_amount", "created_at")),
        Map.entry("sales_lines", List.of("id", "sales_header_id", "line_no", "item_id", "description", "quantity", "unit_of_measure", "unit_price", "discount_percent", "discount_amount", "vat_percent", "vat_amount", "line_amount", "quantity_shipped", "quantity_invoiced", "location_id", "created_at")),
        Map.entry("sales_order_lines", List.of("id", "description", "discount_amount", "discount_percent", "line_amount", "line_no", "quantity", "quantity_invoiced", "quantity_shipped", "unit_of_measure", "unit_price", "vat_amount", "vat_percent", "item_id", "sales_order_id")),
        Map.entry("sales_orders", List.of("id", "order_no", "customer_id", "location_id", "order_date", "posting_date", "currency_code", "payment_terms_code", "payment_method_code", "salesperson_code", "status", "subtotal", "discount_amount", "vat_amount", "total_amount", "notes", "requested_delivery_date")),
        Map.entry("sales_shipment_lines", List.of("id", "shipment_id", "line_no", "sales_order_line_id", "item_id", "quantity", "unit_of_measure", "location_code")),
        Map.entry("sales_shipments", List.of("id", "shipment_no", "sales_order_id", "customer_id", "shipment_date", "location_code", "created_at")),
        Map.entry("supplier", List.of("id", "code", "name", "phone", "email", "address", "tax_code", "active")),
        Map.entry("supplier_ledger", List.of("id", "entry_no", "supplier_id", "document_type", "document_id", "posting_date", "debit", "credit", "balance", "description", "created_at")),
        Map.entry("tax_codes", List.of("id", "code", "name", "rate", "tax_account_id", "active", "created_at")),
        Map.entry("tax_entries", List.of("id", "document_no", "document_type", "tax_date", "tax_code_id", "taxable_amount", "tax_amount", "direction", "description", "created_at")),
        Map.entry("units_of_measure", List.of("id", "code", "name", "description", "active", "created_at")),
        Map.entry("users", List.of("id", "username", "email", "name", "phone", "department", "team", "role", "dashboard_access")),
        Map.entry("vendors", List.of("id", "vendor_no", "name", "address", "city", "phone", "email", "tax_no", "payment_terms_code", "currency_code", "blocked", "created_at"))
    );
}
