-- Run explicitly with psql -v ON_ERROR_STOP=1 -f scripts/seed-demo.sql.
-- Populate empty business tables. Existing rows are never updated or deleted.
-- DEMO records are presentation fixtures, not real business transactions.
BEGIN;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_proc WHERE proname='erp_complete_production') THEN
        RAISE EXCEPTION 'Legacy seed cannot run after inventory automation. Use Item receipts and sales workflows.';
    END IF;
END $$;
SELECT pg_advisory_xact_lock(20260928, 8081);
DO $$
DECLARE
    t record; empty_tables text[] := '{}'; has_rows boolean;
    customer_id_ bigint; vendor_id_ bigint; supplier_id_ bigint; location_id_ bigint;
    item_id_ bigint; product_id_ bigint; order_id_ bigint; line_id_ bigint;
    po_id bigint; pol_id bigint; receipt_id_ bigint; pinvoice_id bigint;
    shipment_id bigint; invoice_id bigint; tax_id bigint; cash_id bigint;
    receivable_id bigint; payable_id bigint; revenue_id bigint; stock_id bigint;
    tax_account_id_ bigint; cogs_id bigint; customer_payment bigint; supplier_payment bigint;
BEGIN
    FOR t IN SELECT tablename FROM pg_tables WHERE schemaname='public' LOOP
        EXECUTE format('SELECT EXISTS(SELECT 1 FROM public.%I)', t.tablename) INTO has_rows;
        IF NOT has_rows THEN empty_tables := array_append(empty_tables, t.tablename); END IF;
    END LOOP;
    IF cardinality(empty_tables)=0 THEN RAISE NOTICE 'All tables already contain data; nothing inserted.'; RETURN; END IF;

    SELECT id INTO customer_id_ FROM customers ORDER BY id LIMIT 1;
    SELECT id INTO location_id_ FROM locations ORDER BY id LIMIT 1;
    SELECT id INTO cash_id FROM accounts WHERE account_no='111';
    SELECT id INTO receivable_id FROM accounts WHERE account_no='131';
    SELECT id INTO payable_id FROM accounts WHERE account_no='331';
    SELECT id INTO revenue_id FROM accounts WHERE account_no='511';
    SELECT id INTO stock_id FROM accounts WHERE account_no='156';
    SELECT id INTO tax_account_id_ FROM accounts WHERE account_no='3331';
    SELECT id INTO cogs_id FROM accounts WHERE account_no='632';
    IF customer_id_ IS NULL OR location_id_ IS NULL OR cash_id IS NULL OR receivable_id IS NULL
        OR payable_id IS NULL OR revenue_id IS NULL OR stock_id IS NULL OR tax_account_id_ IS NULL OR cogs_id IS NULL
        THEN RAISE EXCEPTION 'Missing prerequisite master data; no changes committed.'; END IF;

    -- Dedicated master records keep the sample chain separate from existing orders.
    INSERT INTO items(item_no,description,base_unit_of_measure,unit_price,unit_cost,blocked)
        SELECT 'DEMO-ITEM-001','[DEMO] Sản phẩm minh họa ERP','CAI',100000,20000,false
        WHERE NOT EXISTS(SELECT 1 FROM items WHERE item_no='DEMO-ITEM-001');
    SELECT id INTO item_id_ FROM items WHERE item_no='DEMO-ITEM-001';
    INSERT INTO product(code,name,category_id,unit,purchase_price,sale_price,vat_rate,quantity,active)
        SELECT 'DEMO-PROD-001','[DEMO] Sản phẩm minh họa ERP',(SELECT id FROM category ORDER BY id LIMIT 1),'CAI',20000,100000,10,9,true
        WHERE NOT EXISTS(SELECT 1 FROM product WHERE code='DEMO-PROD-001');
    SELECT id INTO product_id_ FROM product WHERE code='DEMO-PROD-001';
    INSERT INTO vendors(vendor_no,name,address,email,currency_code)
        SELECT 'DEMO-V001','[DEMO] Nhà cung cấp minh họa','TP. Hồ Chí Minh','demo-vendor@example.com','VND'
        WHERE NOT EXISTS(SELECT 1 FROM vendors WHERE vendor_no='DEMO-V001');
    SELECT id INTO vendor_id_ FROM vendors WHERE vendor_no='DEMO-V001';
    INSERT INTO supplier(code,name,email,address,active)
        SELECT 'DEMO-SUP001','[DEMO] Nhà cung cấp minh họa','demo-supplier@example.com','TP. Hồ Chí Minh',true
        WHERE NOT EXISTS(SELECT 1 FROM supplier WHERE code='DEMO-SUP001');
    SELECT id INTO supplier_id_ FROM supplier WHERE code='DEMO-SUP001';

    IF 'employees'=ANY(empty_tables) THEN
        INSERT INTO employees(employee_code,name,email,department,position,hire_date,active) VALUES
            ('DEMO-NV001','[DEMO] Nguyễn Minh An','demo-sales@example.com','Kinh doanh','Nhân viên kinh doanh',CURRENT_DATE,true),
            ('DEMO-NV002','[DEMO] Trần Thu Hà','demo-warehouse@example.com','Kho vận','Thủ kho',CURRENT_DATE,true),
            ('DEMO-NV003','[DEMO] Lê Quốc Bình','demo-finance@example.com','Kế toán','Kế toán viên',CURRENT_DATE,true);
    END IF;
    IF 'tax_codes'=ANY(empty_tables) THEN
        INSERT INTO tax_codes(code,name,rate,tax_account_id,active) VALUES ('DEMO-VAT10','[DEMO] Thuế GTGT 10%',10,tax_account_id_,true);
    END IF;
    SELECT id INTO tax_id FROM tax_codes ORDER BY (code='DEMO-VAT10') DESC,id LIMIT 1;

    IF NOT EXISTS (SELECT 1 FROM purchase_headers WHERE document_type='ORDER' AND document_no='DEMO-PO001') THEN
        INSERT INTO purchase_headers(document_type,document_no,vendor_id,order_date,currency_code,status,total_amount,vendor_reference)
        VALUES ('ORDER','DEMO-PO001',vendor_id_,CURRENT_DATE,'VND','OPEN',200000,'[DEMO] Bộ dữ liệu minh họa');
    END IF;
    SELECT id INTO po_id FROM purchase_headers WHERE document_type='ORDER' AND document_no='DEMO-PO001';
    IF NOT EXISTS (SELECT 1 FROM purchase_lines WHERE purchase_header_id=po_id AND line_no=10000) THEN
        INSERT INTO purchase_lines(purchase_header_id,document_type,document_no,line_no,item_id,description,unit_of_measure,quantity,unit_price,line_amount)
        VALUES (po_id,'ORDER','DEMO-PO001',10000,item_id_,'[DEMO] Nhập 10 sản phẩm','CAI',10,20000,200000);
    END IF;
    SELECT id INTO pol_id FROM purchase_lines WHERE purchase_header_id=po_id AND line_no=10000;
    IF 'purchase_receipts'=ANY(empty_tables) THEN
        INSERT INTO purchase_receipts(receipt_no,purchase_order_id,vendor_id,receipt_date,location_code)
        VALUES ('DEMO-PR001',po_id,vendor_id_,CURRENT_DATE,(SELECT code FROM locations WHERE id=location_id_));
    END IF;
    SELECT id INTO receipt_id_ FROM purchase_receipts ORDER BY (receipt_no='DEMO-PR001') DESC,id LIMIT 1;
    IF 'purchase_receipt_lines'=ANY(empty_tables) THEN
        INSERT INTO purchase_receipt_lines(receipt_id,line_no,purchase_order_line_id,item_id,quantity,unit_of_measure,location_code)
        VALUES (receipt_id_,10000,pol_id,item_id_,10,'CAI',(SELECT code FROM locations WHERE id=location_id_));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM purchase_headers WHERE document_type='INVOICE' AND document_no='DEMO-PI001') THEN
        INSERT INTO purchase_headers(document_type,document_no,vendor_id,order_date,currency_code,status,total_amount)
        VALUES ('INVOICE','DEMO-PI001',vendor_id_,CURRENT_DATE,'VND','DRAFT',200000);
    END IF;
    SELECT id INTO pinvoice_id FROM purchase_headers WHERE document_type='INVOICE' AND document_no='DEMO-PI001';
    IF NOT EXISTS (SELECT 1 FROM purchase_lines WHERE purchase_header_id=pinvoice_id AND line_no=10000) THEN
        INSERT INTO purchase_lines(purchase_header_id,document_type,document_no,line_no,item_id,description,quantity,quantity_invoiced,unit_price,line_amount,source_purchase_line_id,purchase_receipt_line_id)
        SELECT pinvoice_id,'INVOICE','DEMO-PI001',10000,item_id_,'[DEMO] Hóa đơn nhập 10 sản phẩm',10,10,20000,200000,pol_id,id
        FROM purchase_receipt_lines receipt_line WHERE receipt_line.receipt_id=receipt_id_ ORDER BY id LIMIT 1;
    END IF;
    IF 'purchase_returns'=ANY(empty_tables) THEN
        INSERT INTO purchase_returns(return_no,supplier_id,location_id,reason,status)
        VALUES ('DEMO-RET001',supplier_id_,location_id_,'[DEMO] Phiếu trả hàng nháp, chưa phát sinh xuất kho','OPEN');
    END IF;

    INSERT INTO sales_orders(order_no,customer_id,location_id,order_date,currency_code,status,subtotal,vat_amount,total_amount,notes)
        SELECT 'DEMO-SO001',customer_id_,location_id_,CURRENT_DATE,'VND','INVOICED',100000,10000,110000,'[DEMO] Bộ dữ liệu minh họa, không phải giao dịch thật'
        WHERE NOT EXISTS(SELECT 1 FROM sales_orders WHERE order_no='DEMO-SO001');
    SELECT id INTO order_id_ FROM sales_orders WHERE order_no='DEMO-SO001';
    IF 'sales_order_lines'=ANY(empty_tables) THEN
        INSERT INTO sales_order_lines(sales_order_id,line_no,item_id,description,quantity,unit_of_measure,unit_price,
            discount_percent,discount_amount,vat_percent,vat_amount,line_amount,quantity_shipped,quantity_invoiced)
        VALUES (order_id_,10000,item_id_,'[DEMO] Bán 1 sản phẩm',1,'CAI',100000,0,0,10,10000,100000,1,1);
    END IF;
    SELECT id INTO line_id_ FROM sales_order_lines WHERE sales_order_id=order_id_ ORDER BY id LIMIT 1;
    IF 'sales_lines'=ANY(empty_tables) THEN
        INSERT INTO sales_lines(sales_header_id,line_no,item_id,description,quantity,unit_of_measure,unit_price,vat_percent,vat_amount,line_amount,quantity_shipped,quantity_invoiced,location_id)
        VALUES (order_id_,10000,item_id_,'[DEMO] Dòng minh họa trong bảng sales_lines',1,'CAI',100000,10,10000,100000,1,1,location_id_);
    END IF;
    IF 'sales_shipments'=ANY(empty_tables) THEN
        INSERT INTO sales_shipments(shipment_no,sales_order_id,customer_id,shipment_date,location_code)
        VALUES ('DEMO-SS001',order_id_,customer_id_,CURRENT_DATE,(SELECT code FROM locations WHERE id=location_id_));
    END IF;
    SELECT id INTO shipment_id FROM sales_shipments ORDER BY (shipment_no='DEMO-SS001') DESC,id LIMIT 1;
    IF 'sales_shipment_lines'=ANY(empty_tables) THEN
        INSERT INTO sales_shipment_lines(shipment_id,line_no,sales_order_line_id,item_id,quantity,unit_of_measure,location_code)
        VALUES (shipment_id,10000,line_id_,item_id_,1,'CAI',(SELECT code FROM locations WHERE id=location_id_));
    END IF;
    IF 'sales_invoices'=ANY(empty_tables) THEN
        INSERT INTO sales_invoices(invoice_no,sales_order_id,customer_id,invoice_date,currency_code,total_amount,vat_amount)
        VALUES ('DEMO-SI001',order_id_,customer_id_,CURRENT_DATE,'VND',110000,10000);
    END IF;
    SELECT id INTO invoice_id FROM sales_invoices ORDER BY (invoice_no='DEMO-SI001') DESC,id LIMIT 1;
    IF 'sales_invoice_lines'=ANY(empty_tables) THEN
        INSERT INTO sales_invoice_lines(invoice_id,line_no,sales_order_line_id,item_id,description,quantity,unit_price,vat_percent,vat_amount,line_amount)
        VALUES (invoice_id,10000,line_id_,item_id_,'[DEMO] Hóa đơn bán 1 sản phẩm',1,100000,10,10000,100000);
    END IF;

    IF 'inventory'=ANY(empty_tables) THEN
        INSERT INTO inventory(product_id,location_id,quantity,reserved_quantity,average_cost)
        VALUES (product_id_,location_id_,9,0,20000);
    END IF;
    IF 'inventory_ledger'=ANY(empty_tables) THEN
        INSERT INTO inventory_ledger(entry_no,product_id,location_id,document_type,document_id,quantity_in,quantity_out,unit_cost,balance_quantity,description) VALUES
        ('DEMO-IL001',product_id_,location_id_,'PURCHASE_RECEIPT',receipt_id_,10,0,20000,10,'[DEMO] Nhập 10 sản phẩm'),
        ('DEMO-IL002',product_id_,location_id_,'SALES_SHIPMENT',shipment_id,0,1,20000,9,'[DEMO] Xuất 1 sản phẩm');
    END IF;
    IF 'payments'=ANY(empty_tables) THEN
        INSERT INTO payments(payment_no,payment_type,customer_id,payment_date,amount,payment_method,account_id,description,status)
        VALUES ('DEMO-PAY001','RECEIPT',customer_id_,CURRENT_DATE,110000,'CASH',cash_id,'[DEMO] Thu tiền hóa đơn DEMO-SI001','POSTED');
        INSERT INTO payments(payment_no,payment_type,supplier_id,payment_date,amount,payment_method,account_id,description,status)
        VALUES ('DEMO-PAY002','PAYMENT',supplier_id_,CURRENT_DATE,200000,'CASH',cash_id,'[DEMO] Chi tiền hóa đơn DEMO-PI001','POSTED');
    END IF;
    SELECT id INTO customer_payment FROM payments WHERE payment_no='DEMO-PAY001';
    SELECT id INTO supplier_payment FROM payments WHERE payment_no='DEMO-PAY002';
    IF 'payment_allocations'=ANY(empty_tables) THEN
        INSERT INTO payment_allocations(payment_id,sales_invoice_id,allocated_amount) VALUES (customer_payment,invoice_id,110000);
        INSERT INTO payment_allocations(payment_id,purchase_invoice_id,allocated_amount) VALUES (supplier_payment,pinvoice_id,200000);
    END IF;
    IF 'customer_ledger'=ANY(empty_tables) THEN
        INSERT INTO customer_ledger(entry_no,customer_id,document_type,document_id,posting_date,debit,credit,balance,description) VALUES
        ('DEMO-CL001',customer_id_,'SALES_INVOICE',invoice_id,CURRENT_DATE,110000,0,110000,'[DEMO] Ghi nhận phải thu'),
        ('DEMO-CL002',customer_id_,'PAYMENT',customer_payment,CURRENT_DATE,0,110000,0,'[DEMO] Thu đủ tiền hóa đơn');
    END IF;
    IF 'supplier_ledger'=ANY(empty_tables) THEN
        INSERT INTO supplier_ledger(entry_no,supplier_id,document_type,document_id,posting_date,debit,credit,balance,description) VALUES
        ('DEMO-SL001',supplier_id_,'PURCHASE_INVOICE',pinvoice_id,CURRENT_DATE,0,200000,200000,'[DEMO] Ghi nhận phải trả'),
        ('DEMO-SL002',supplier_id_,'PAYMENT',supplier_payment,CURRENT_DATE,200000,0,0,'[DEMO] Thanh toán nhà cung cấp');
    END IF;
    IF 'general_ledger'=ANY(empty_tables) THEN
        INSERT INTO general_ledger(document_no,document_type,posting_date,account_id,debit,credit,description) VALUES
        ('DEMO-PI001','PURCHASE_INVOICE',CURRENT_DATE,stock_id,200000,0,'[DEMO] Nhập hàng'),
        ('DEMO-PI001','PURCHASE_INVOICE',CURRENT_DATE,payable_id,0,200000,'[DEMO] Phải trả'),
        ('DEMO-SI001','SALES_INVOICE',CURRENT_DATE,receivable_id,110000,0,'[DEMO] Phải thu'),
        ('DEMO-SI001','SALES_INVOICE',CURRENT_DATE,revenue_id,0,100000,'[DEMO] Doanh thu'),
        ('DEMO-SI001','SALES_INVOICE',CURRENT_DATE,tax_account_id_,0,10000,'[DEMO] Thuế đầu ra'),
        ('DEMO-SS001','SALES_SHIPMENT',CURRENT_DATE,cogs_id,20000,0,'[DEMO] Giá vốn'),
        ('DEMO-SS001','SALES_SHIPMENT',CURRENT_DATE,stock_id,0,20000,'[DEMO] Xuất hàng'),
        ('DEMO-PAY001','PAYMENT',CURRENT_DATE,cash_id,110000,0,'[DEMO] Thu tiền'),
        ('DEMO-PAY001','PAYMENT',CURRENT_DATE,receivable_id,0,110000,'[DEMO] Tất toán phải thu'),
        ('DEMO-PAY002','PAYMENT',CURRENT_DATE,payable_id,200000,0,'[DEMO] Tất toán phải trả'),
        ('DEMO-PAY002','PAYMENT',CURRENT_DATE,cash_id,0,200000,'[DEMO] Chi tiền');
    END IF;
    IF 'tax_entries'=ANY(empty_tables) THEN
        INSERT INTO tax_entries(document_no,document_type,tax_date,tax_code_id,taxable_amount,tax_amount,direction,description)
        VALUES ('DEMO-SI001','SALES_INVOICE',CURRENT_DATE,tax_id,100000,10000,'OUTPUT','[DEMO] Thuế hóa đơn bán');
    END IF;
    RAISE NOTICE 'Seeded empty tables: %', array_to_string(empty_tables, ', ');
END $$;
COMMIT;
