# Luồng Purchase đang triển khai

Luồng chính: Vendor → Quote → Make Order → Release → Post Receive → Purchase Invoice → Post Invoice → Thanh toán.

## Cách sử dụng

1. Tạo Vendor và Item; chọn Unit và Location hợp lệ. Item nhận/trả kho phải là Inventory.
2. Tạo Purchase Quote (`PQ`), thêm Purchase Lines: mặt hàng, đơn vị, kho, số lượng, đơn giá, chiết khấu, VAT.
3. Mở card Quote, bấm **Make Order**. Hệ thống tạo `PO`, giữ Quote để tra cứu. Bấm lại không tạo đơn trùng.
4. Chỉnh sửa PO khi còn DRAFT. **Release** khóa nội dung và cho phép nhận hàng. Hai bước này chưa tăng tồn, chưa ghi công nợ/G/L.
5. **Post Receive**: nhập số lượng thực nhận cho từng dòng; nhập 0 cho dòng chưa nhận. Có thể nhận 60 rồi 40 trên đơn 100. Mỗi lần tạo Posted Purchase Receipt, cập nhật Quantity Received và tồn kho. Phiếu đã đăng không được sửa/xóa trực tiếp. Giai đoạn này ghi giá trị EXPECTED, chưa ghi công nợ.
6. **Tạo Purchase Invoice**: chọn cùng Vendor và các dòng phiếu nhập còn số lượng. Nhập **Số chứng từ ngoài / Vendor Invoice No.**. Lưu nháp chưa ghi công nợ; dòng nháp giữ phần số lượng phiếu nhập để không lập trùng.
7. **Post Invoice**: ghi công nợ và G/L, cập nhật Quantity Invoiced, ghi Value Entries loại ACTUAL. Không tăng tồn lần thứ hai. Hoặc dùng **Post Receive & Invoice** trên PO để thực hiện nhận và hóa đơn trong một giao dịch; lỗi một bước sẽ rollback toàn bộ.
8. **Thanh toán** trên card Invoice: nhập số tiền, ngày và tham chiếu giao dịch. Hệ thống ghi giảm công nợ và tiền, chặn trả vượt số dư.
9. Tạo `P-RO`, chọn Vendor/Items/Unit/Location, Release rồi **Post Return Shipment**. Hàng bị trừ khỏi tồn; công nợ chưa giảm. Giá vốn được chuyển sang tài khoản hàng gửi trả chờ xử lý.
10. **Purchase Credit Memo** trên Invoice: nhập tổng giảm, VAT giảm và tham chiếu. Nếu trả hàng, nhập mã `P-RO` đã gửi trả cùng Vendor/tiền tệ; có thể giảm từng phần trong giới hạn giá trị trả. Nếu chỉ giảm giá thì bỏ trống Return Order. Không xuất kho lần nữa.
11. Credit Memo sau khi hóa đơn đã thanh toán có thể tạo số dư âm. **Nhận hoàn tiền** ghi tiền nhà cung cấp trả lại và tất toán phần dư này.

## Kế toán và kiểm soát

- Cấu hình hạch toán trên card Purchase: tồn kho, chi phí, thuế đầu vào, phải trả, tiền, hàng gửi trả chờ xử lý và điều chỉnh giá.
- Cấu hình ban đầu dựa trên danh mục VND của dự án: 156, 632, 1331, 331, 112, 1388; có thể đổi trước khi sử dụng. Đây là cấu hình của ứng dụng, không phải xác nhận chính sách kế toán cho một doanh nghiệp.
- Đăng Invoice: Nợ tồn kho, Nợ VAT đầu vào, Có phải trả. Thanh toán: Nợ phải trả, Có tiền. Trả hàng: Nợ hàng gửi trả chờ xử lý, Có tồn kho. Credit Memo: giảm phải trả, VAT và đối ứng trả hàng/điều chỉnh. Nhận hoàn tiền: Nợ tiền, Có phải trả.
- Mỗi bộ bút toán phải cân bằng Nợ/Có. G/L đã đăng và lịch sử công nợ/giá trị không sửa/xóa trực tiếp.
- Lịch sử trên card tách riêng Receipt, Vendor Entries, G/L và Value Entries. EXPECTED và ACTUAL là hai loại giá trị, không cộng gộp như hai lần mua.
- Khóa giao dịch và mã yêu cầu giúp chống xử lý trùng. Dữ liệu cũ không tự động đăng sổ lại.

## Phạm vi hiện tại

- Quy trình kho cơ bản cho Item Inventory và kế toán VND. Không tự quy đổi ngoại tệ; đăng G/L ngoại tệ bị chặn.
- Requisition, Approval Workflow, Warehouse Receipt gom nhiều nguồn và Bin/Put-away là các bước tùy chọn trong tài liệu, chưa bật trong luồng này.
- Chưa có kỳ kế toán/khóa sổ và bộ máy tính giá vốn truy hồi đầy đủ như Business Central. Giá vốn kho dùng bình quân hiện có; Value Entries lưu giá trị nhận dự kiến và giá trị hóa đơn thực tế riêng để đối chiếu. Không tự điều chỉnh giá vốn xuất hàng lịch sử khi hóa đơn thay đổi giá.
- Công nợ Purchase dùng `purchase_vendor_entries` gắn `vendors`; không trộn ID Vendor vào bảng `supplier_ledger` cũ dùng danh mục khác. G/L tích hợp bảng `general_ledger` hiện có.

## Kiểm thử

`PurchaseWorkflowTest`: kiểm tra số lượng, dòng trùng và số tiền/VAT.

`PurchaseWorkflowIntegrationTest`: chạy khi có biến môi trường `PURCHASE_TEST_DB_URL`, `PURCHASE_TEST_DB_USER`, `PURCHASE_TEST_DB_PASSWORD`. Kiểm tra trực tiếp PostgreSQL trong transaction rollback: Quote → Order, nhận 60/40, Invoice 5.500.000, thanh toán từng phần, Return, Credit, hoàn tiền, nhận kèm hóa đơn, chống trùng, đối chiếu công nợ và G/L.
