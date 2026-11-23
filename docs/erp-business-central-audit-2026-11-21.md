# Đánh giá ERP đồ án và đối chiếu Business Central

Ngày đánh giá: 21/11/2026 (Asia/Saigon).
Dự án được kiểm tra: `C:\Users\phucv\IdeaProjects\DoAnTotNghiep`; ứng dụng `http://localhost:8081`.

## 1. Kết luận

Dự án chạy được và có nhiều luồng ERP cơ bản hoạt động. Purchase là luồng được kiểm thử xuyên suốt rõ nhất; Sales shipping, Planning và Warehouse cơ bản đã có kiểm soát số lượng. Tuy nhiên **chưa thể kết luận tương đương Business Central hoặc ERP kế toán hoàn chỉnh**. Các thiếu sót quan trọng gồm kế toán Sales, giá thành Production, tải file Report, quy đổi đơn vị và phân quyền nghiệp vụ.

Đây là đánh giá trên phiên bản hiện tại, không phải chứng nhận Microsoft. Test xanh chỉ xác nhận những trường hợp đã viết; không chứng minh mọi tình huống nghiệp vụ đều đúng. Lần này kiểm tra và lập báo cáo, chưa sửa các thiếu sót mới phát hiện.

## 2. Đã kiểm tra những gì

| Phạm vi | Kết quả | Giới hạn |
|---|---|---|
| Maven verify | 208 test; 0 failure, 0 error, 0 skipped; BUILD SUCCESS | Gồm mock/unit và integration, không phải 208 hành trình người dùng |
| Tích hợp PostgreSQL | 7 test trong 6 suite: Purchase, Sales shipping, Planning, Production Planning, Warehouse và Vendor | Giao dịch rollback; sequence có thể tăng dù dữ liệu được rollback |
| JavaScript | 31 file qua kiểm tra cú pháp | Không thay thế kiểm tra mọi sự kiện UI |
| Bảng/list ERP | 44 bảng đọc API và mở danh sách thành công | Bảng trống chưa chứng minh form chi tiết của dữ liệu đó |
| Form | 14 trường hợp mở thành công: Item, Vendor, Location, Customer, BOM, Production, 4 Sales, 4 Purchase | Không lưu tất cả tổ hợp trường từ trình duyệt |
| Report | 7 nhóm có HTML xem/in; đều chưa có tải PDF trực tiếp/Excel | Kiểm tra nội dung sinh ra; không chứng minh mọi báo cáo in nhiều trang hoàn hảo |
| Browser regression | 5 script đạt: chọn dòng/list, lookup Items, Purchase/Sales forms, Planning, Warehouse/report filters | Một số tình huống phân trang/xóa lỗi dùng mock để không sửa dữ liệu thật |
| HR | 8 API GET trả 200; 6 trang mở không lỗi JavaScript | Không gửi email thật; chưa đánh giá tuân thủ pháp luật tiền lương |
| Dữ liệu hiện tại | 0 tồn âm, 0 giữ chỗ vượt tồn, 0 lệch tổng Item/Inventory, 0 nhận/giao/xuất hóa đơn quá lượng trong các truy vấn đã chạy | Hiện không có kho bật Bin, Sales Order mới, Sales Invoice hoặc Vendor Ledger đã Post; một số kết quả 0 là do chưa có giao dịch |

Quan sát ảnh Purchase Order ở 1440×1000: phần thông tin, Purchase Lines và nút lưu hiển thị được. Order Planning có 4 dòng nhu cầu và vùng cuộn ngang. Không phát hiện lỗi JavaScript trong các phiên browser trên. Vẫn có nhãn kỹ thuật/Anh–Việt chưa đồng nhất, ví dụ `invoice_date`, `city`.

## 3. Phát hiện cần ưu tiên

### P1 — Cần hoàn thiện trước khi khẳng định hệ thống có kế toán đầy đủ

**A. Sales Invoice chưa ghi công nợ và G/L tự động.**

- Thử trong giao dịch rollback: hóa đơn 6 đơn vị × 10 = 60; số dòng Customer Ledger tăng 0, G/L tăng 0.
- `ErpWriteController.createSalesInvoiceWithLines` lưu header/lines và chi tiết, chưa có posting service kế toán tương ứng. Triggers đang chạy chủ yếu tính tổng, kiểm tra lượng đã giao và đồng bộ Sales Header.
- Hóa đơn được lưu chưa đồng nghĩa đã hạch toán. Sales Return nhận lại hàng cũng chưa tạo thành luồng Sales Credit Memo/hoàn tiền hoàn chỉnh.
- Cần: Draft → Post Invoice; Posting Setup doanh thu, phải thu, VAT, giá vốn/tồn kho; Customer Ledger, Value Entries và G/L; Payment/application; Credit Memo và reversal; khóa dữ liệu đã Post.
- Business Central cập nhật sổ khách hàng, sổ hàng và G/L khi ghi sổ bán hàng: [Posting sales](https://learn.microsoft.com/en-us/dynamics365/business-central/ui-post-sales).

**B. Production đúng số lượng trong phép thử nhưng chưa chuyển giá thành.**

- Thử rollback: linh kiện 8 đơn vị, giá vốn 10; BOM dùng 4 cho 1 thành phẩm; hoàn tất 2 thành phẩm.
- Kết quả: linh kiện còn 0, thành phẩm có 2, ghi tiêu hao 8; nhưng giá vốn thành phẩm vẫn 0 và không sinh G/L.
- Với ví dụ chỉ tính nguyên liệu, chi phí đầu vào là 80; hiện hệ thống không tự chuyển khoản này thành giá thành. Chưa có phương pháp standard/actual cost để giải thích chênh lệch.
- Cần: snapshot BOM theo lệnh, consumption/output, tích lũy chi phí, cost adjustment; nếu mô phỏng BC Manufacturing đầy đủ thì cần thêm WIP, routing/capacity và variance.
- Đối chiếu: [Production order posting](https://learn.microsoft.com/en-gb/dynamics365/business-central/design-details-production-order-posting).

**C. Report chưa đạt yêu cầu tải file của người dùng.**

- Có khoảng ngày, HTML xem/in, nút gọi `window.print()`.
- Chưa có nút tạo/tải `.pdf` trực tiếp; chưa có xuất `.xlsx`. “In / Lưu PDF” hiện phụ thuộc hộp thoại của trình duyệt.
- Chưa có bước Xem trước thống nhất với tập dữ liệu cố định dùng lại cho file tải xuống; nút hiện mang tên “Xuất Report”.
- Cần: request page → Xem trước → Tải PDF; Excel chỉ cho báo cáo phù hợp, nhưng phải là file XLSX thật. Preview và download phải dùng cùng bộ lọc/dataset.
- BC có request page, preview và lưu file; đây là các khả năng khác nhau: [Run and print reports](https://learn.microsoft.com/en-us/dynamics365/business-central/ui-work-report).

**D. Phân quyền nghiệp vụ còn rộng.**

- `SecurityConfig` chặn HR ngoài phạm vi, hạn chế quản lý Users và DELETE, nhưng nhiều POST/PUT nghiệp vụ chỉ yêu cầu đăng nhập. Test hiện cũng chấp nhận người dùng thường POST/PUT endpoint nghiệp vụ mẫu.
- Cấu hình tài khoản và phê duyệt kiểm kê có kiểm tra Admin/Director; chưa thấy ma trận xuyên suốt quyền Sales, Purchase, Warehouse và Accounting.
- Cần tách Create/Edit/Release/Post/Pay/Approve, kiểm tra tại backend; test cả thao tác được phép và bị từ chối. Không chỉ ẩn nút UI.

**E. Chọn Unit chưa tương đương quy đổi đơn vị.**

- Có danh mục đơn vị và lookup; chưa thấy bảng Item Unit of Measure có hệ số chuyển đổi và Quantity (Base).
- Nếu cho một Item tồn theo PCS nhưng mua theo BOX, hiện chưa có căn cứ quy đổi BOX thành PCS. Không được coi đổi nhãn đơn vị là đổi số lượng cơ sở.
- Cần giới hạn đơn vị bằng base UOM, hoặc bổ sung hệ số theo từng Item và dùng xuyên suốt mua/bán/kho/BOM.
- BC dùng base UOM và Qty. per Unit of Measure: [Set up units of measure](https://learn.microsoft.com/en-ca/dynamics365/business-central/inventory-how-setup-units-of-measure).

### P2 — Thiếu hoặc khác mô hình Business Central, cần thống nhất phạm vi

**F. Thứ tự tạo BOM và Production đang bị phụ thuộc ngược ở lookup.**

`/production-order-items` chỉ lấy Item đã xuất hiện trong Production Orders. Item sản xuất mới chưa có lệnh có thể không xuất hiện khi tạo BOM. Nên cho tạo BOM từ Item sản xuất trước, rồi chọn BOM khi tạo Production Order. Hiện BOM là quan hệ parent/component, chưa có header số BOM riêng, Certified/version/effective date. [Create production BOMs](https://learn.microsoft.com/en-us/dynamics365/business-central/production-how-to-create-production-boms).

**G. Production mới là quy trình đơn giản.**

Trạng thái hiện có DRAFT/COMPLETED/CANCELLED, hoàn tất một lần; chưa có output/consumption từng phần, routing, work/machine center, lịch công suất. BC có Simulated, Planned, Firm Planned, Released, Finished. Không bắt buộc đồ án có tất cả, nhưng phải gọi đúng là phạm vi sản xuất rút gọn. [About production orders](https://learn.microsoft.com/en-us/dynamics365/business-central/production-about-production-orders).

**H. Planning chưa xét ngày cung–cầu và chưa tạo tất cả loại supply order.**

- Đang gộp theo Item + kho; demand Sales released và Production draft; trừ tồn khả dụng và lượng PO chưa nhận.
- Make Orders chỉ tạo Purchase; liên hệ nguồn được ghi trong notes, chưa có allocation/tracking theo từng demand line.
- Chưa so ngày hàng về với ngày cần; PO về muộn vẫn có thể che thiếu hàng cần sớm.
- Chưa có tạo Production/Transfer từ kế hoạch, safety stock, MOQ/order multiple, lead time, đa cấp BOM.
- BC Order Planning có demand-date, khả dụng và lựa chọn Purchase/Transfer/Prod. Order: [Plan for new demand](https://learn.microsoft.com/en-us/dynamics365/business-central/production-how-to-plan-for-new-demand).

**I. Warehouse cơ bản có thật, nhưng traceability chưa đầy đủ.**

Zone/Bin, Put-away, Pick, movement, chuyển trực tiếp, kiểm kê phê duyệt và Warehouse Entries đã có. Tuy nhiên thao tác Pick chỉ gắn Item/Bin, chưa gắn Sales Order/line để cấp phát hàng đã Pick cho từng đơn. Chưa có Warehouse Receipt/Shipment nháp riêng, transfer in-transit hai bước, Lot/Serial, barcode chuyên dụng, directed pick/put-away. Bin capacity hiện là tổng số đơn vị, chưa quy đổi khối lượng/thể tích. QC/cách ly là quy ước riêng của đồ án, không nên nói toàn bộ ý nghĩa Bin giống BC. [Warehouse overview](https://learn.microsoft.com/en-gb/dynamics365/business-central/design-details-warehouse-overview).

**J. Report đã chung CSS nhưng chưa đồng bộ toàn bộ nội dung/nghiệp vụ.**

- Test ngày bao gồm hai đầu, từ ngày lớn hơn đến ngày, phân trang 101 dòng và escape HTML đạt.
- Vendor/kho report giữ đúng filter ngữ cảnh; tồn Bin hiện tại không giả lập tồn lịch sử bằng ngày tạo danh mục — đây là hành vi hợp lý.
- `reportDateColumn` dùng order_date cho mọi purchase_headers; báo cáo hóa đơn/công nợ cần phân biệt rõ Posting Date với ngày chứng từ.
- Chưa có tồn đầu–nhập–xuất–tồn cuối, aging công nợ, valuation theo kỳ, cost production, trial balance như bộ report chuyên biệt.
- Shipment chưa có adapter report chi tiết riêng như Sales/Purchase header. HR có preview phiếu lương riêng, chưa dùng toàn bộ request page/layout Report chung.
- BOM mới chưa chọn Item chưa hiện Report; chỉ đánh giá sau khi chọn/lưu Item, không coi đó là lỗi lưu BOM.

**K. Dữ liệu cũ và mới cần kịch bản demo rõ ràng.**

Có 4 Sales Orders legacy (`ship_on_post=false`) và 0 đơn mới trong dữ liệu đang kiểm tra. Supplier và Vendor tách riêng, không tự ghép. Không tự chuyển đơn cũ sang luồng mới vì có thể trừ tồn lần hai. Cần migration có đối soát nếu muốn thống nhất lịch sử.

**L. Triển khai và kiểm thử còn phụ thuộc máy cá nhân.**

VS Code task đang trỏ Maven/JDK bằng đường dẫn tuyệt đối. Test Java nằm chung src/main/java nhưng Maven đã tách compile/testCompile bằng include/exclude nên build hiện thành công. Cần tài liệu cài đặt, môi trường riêng, CI chạy integration với DB và kiểm tra triển khai trên DB trống. Chưa kiểm tra tải lớn, chạy đồng thời, backup/restore, mất kết nối khi Post, email thật và đầy đủ kích thước mobile.

## 4. Luồng hiện có và chứng từ/sổ phát sinh

| Bước nghiệp vụ | Đồ án tạo/cập nhật gì | Đánh giá |
|---|---|---|
| Tạo Vendor/Customer/Item/Location/Unit | Master data | Đọc/form hoạt động; cần UOM theo Item và kiểm soát quyền |
| Purchase Quote → Make Order | PQ → PO và các Purchase Lines | Có test chống tạo trùng |
| Release PO | Trạng thái RELEASED | Không tăng tồn, đúng trong test |
| Post Receive từng phần | Receipt/Receipt Lines; Inventory và Inventory Ledger; RECEIVE Bin nếu bật kho | Test nhận 60 rồi 40; chặn nhận 41 khi chỉ còn 40 |
| Tạo Purchase Invoice từ receipt → Post | P-INV, Vendor Entry, Value Entry, G/L | Test không cộng tồn lần hai; bút toán cân |
| Pay/Credit/Refund | Vendor Entries và G/L tương ứng | Test 5.500.000 → trả 3.000.000 → giảm 550.000 → trả 1.950.000 = 0; giảm thêm rồi hoàn tiền cũng về 0 |
| Purchase Return Release → Ship Return | P-RO và giảm tồn; liên kết Credit Memo khi làm bước tài chính | Test trả 10 giảm tồn 100 còn 90; không tự giảm công nợ trước Credit |
| Sales Quote → Make Order → Release | SQ → SO; nhu cầu mới vào Planning | Có luồng; lưu/release đơn mới không xuất kho |
| Sales Post Shipment | Shipment/Lines, giảm tồn, tăng quantity_shipped | Test giao từng phần; chặn vượt lượng/tồn; retry không lặp |
| Sales Invoice | Invoice/Lines và tổng tiền, quantity invoiced | Kiểm soát lượng đã giao; CHƯA có luồng kế toán hoàn chỉnh |
| Sales Return nhận hàng | Return/Lines, tăng lại tồn | CHƯA hoàn chỉnh Credit Memo/hoàn tiền khách |
| Production | Lệnh nháp → hoàn tất; Consumed Components, giảm linh kiện/tăng thành phẩm | Đúng số lượng trong probe; thiếu giá thành |
| Planning | Tính thiếu; tạo PO nháp theo Vendor/kho | Ví dụ tồn 30, cần 100 → mua 70 đã được test; chưa xét thời gian |
| Warehouse movement | Warehouse Operations + Entries + Bin Contents | Chuyển Bin không đổi tổng Inventory |
| Transfer trực tiếp | Giảm kho nguồn, tăng kho đích, giữ/pha trộn giá vốn | Có integration test; chưa phải in-transit |
| Count → Approve → Post | Điều chỉnh chênh lệch, Inventory Ledger, Warehouse Entries, G/L | Test chặn số kiểm kê cũ, chống Post lặp và cân G/L |
| HR | Nghỉ phép, task, payroll, nhân viên, nội dung/tuyển dụng | API/UI và test hiện có đạt; phạm vi riêng, không suy ra đáp ứng toàn bộ BC |

BC hỗ trợ nhận hàng và hóa đơn Purchase độc lập hoặc cùng lúc; luồng Purchase hiện theo đúng hướng đó. [Record purchases](https://learn.microsoft.com/en-us/dynamics365/business-central/purchasing-how-record-purchases).

## 5. Thứ tự hoàn thiện đề xuất

1. Chốt phạm vi đồ án: ERP thương mại + kho + sản xuất đơn giản hay mô phỏng Manufacturing/Finance sâu. Không đặt mục tiêu “giống 100% Business Central” nếu không có tiêu chí nghiệm thu cụ thể.
2. Hoàn thiện Sales posting/accounting, Credit Memo/payment, giá thành Production và chặn sửa sổ đã Post.
3. Hoàn thiện Report preview + tải PDF thật; XLSX theo loại report; dữ liệu/tổng tiền/đơn vị/ngày phải khớp bản xem trước.
4. Chuẩn hóa UOM, BOM trước Production, snapshot BOM và ma trận quyền.
5. Bổ sung demand date, nguồn cung về trễ, liên kết Pick–Sales line và demand–supply.
6. Chạy lại bộ integration với chứng từ mới; thêm kịch bản từ UI đến ledger, concurrency/retry, rollback khi giữa chừng lỗi, tải báo cáo nhiều trang, phân quyền âm tính.
7. Chuẩn bị bộ dữ liệu demo riêng, đối soát trước/sau từng bước; bảo vệ phải trình bày rõ phần đã có và phần mở rộng.

## 6. Đánh giá cuối cùng

- **Khả năng chạy/build:** đạt trong môi trường đã kiểm tra.
- **Mua hàng và kho cơ bản:** có nền tảng tốt, nhiều kiểm soát đã được test.
- **Bán hàng đủ kế toán:** chưa đạt.
- **Sản xuất đủ giá thành:** chưa đạt.
- **Report theo yêu cầu tải PDF/Excel:** chưa đạt.
- **Tương đương Business Central toàn diện:** chưa đạt; có thể mô tả là đồ án ERP tham khảo một số luồng Business Central.
- **Sẵn sàng bảo vệ:** có thể demo các luồng đã kiểm chứng, nhưng phải xử lý hoặc công khai giới hạn P1; không nên tuyên bố mọi module đã hoàn chỉnh.

Bằng chứng được lưu tại thư mục build của workspace: `erp-audit-browser.json`, `erp-audit-hr.json`, `erp-audit-db.txt`, các script rollback và log browser. Log Maven có thể chứa cấu hình môi trường nên không đưa nguyên bản vào tài liệu nộp/bản công khai.
