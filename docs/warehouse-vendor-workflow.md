# Warehouse và Vendor: mô hình và hướng dẫn vận hành

## Phạm vi đang triển khai

`locations` là danh mục kho dùng chung; không tạo thêm bảng kho trùng. `vendors` là nhà cung cấp của luồng Purchase mới. `supplier` và `supplier_ledger` là dữ liệu cũ, giữ riêng, không tự ghép theo ID hay tên.

Inventory theo dõi tổng số lượng/giá vốn tại Item + Location. Warehouse theo dõi vị trí tại Item + Bin. Bật quản lý Bin theo từng kho, không bật tự động cho toàn bộ kho đang dùng.

Các phần đã có: Zone/Bin; sức chứa và khóa Bin; số dư đầu; nhận Purchase vào RECEIVE; Put-away; Pick; xuất Sales từ SHIP; di chuyển nội bộ; chuyển trực tiếp giữa kho; kiểm kê chốt số hệ thống, phê duyệt, Post; Warehouse Entries; đối chiếu tồn; công nợ Vendor; báo cáo theo ngày.

Các phần nâng cao chưa triển khai: chứng từ Warehouse Receipt/Shipment nháp tách riêng khỏi chứng từ Purchase/Sales, Packing, Transfer In-Transit hai bước, Lot/Serial, quét barcode/QR chuyên dụng, thiết bị/nhân sự kho, thuật toán Directed Put-away/Pick theo kích thước/trọng lượng/FIFO/FEFO. Không bật cờ tính năng giả cho những phần này. Sức chứa Bin hiện tính bằng tổng số đơn vị, chưa quy đổi thể tích/trọng lượng.

## 1. Chuẩn bị danh mục

1. Vendors → tạo nhà cung cấp, mã, tên, liên hệ, tiền tệ và điều khoản thanh toán. Vendor bị khóa không dùng để lập/đăng nghiệp vụ mua mới.
2. Items → chọn Inventory, đơn vị cơ bản. Hàng cần mua chọn Replenishment System = Purchase và Vendor mua hàng.
3. Warehouse → Warehouses / Locations → tạo kho với mã duy nhất.
4. Mở card kho → **Bật quản lý Zone / Bin**. Chỉ Admin/Director được bật. Tồn Item hiện tại được phân bổ vào STORAGE, không tăng Inventory hoặc tạo hóa đơn/công nợ. Kho còn tồn Product cũ phải đối chiếu trước.
5. Các Bin mặc định: RECEIVE, STORAGE, SHIP, QC, QUARANTINE, RETURN. Có thể thêm Zone và Bin, đặt sức chứa/khóa Bin trên card kho.

Không đổi/xóa mã kho đã được chứng từ tham chiếu. Đổi tên/địa chỉ kho vẫn được. Không xóa lịch sử Warehouse Entry hay Vendor Ledger Entry.

## 2. Mua và nhập hàng

Purchase Quote (nếu cần) → Make Order → Purchase Order → Release → Post Receive.

- Chọn Vendor, Item, số lượng và kho trên dòng Purchase.
- Tạo hoặc Release PO không tăng tồn.
- Post Receive bao nhiêu thì tồn tăng bấy nhiêu; Warehouse tự ghi vào Bin RECEIVE của kho đã bật quản lý Bin.
- Mở card kho → Put-away → chọn Item, Bin nguồn RECEIVE, Bin đích STORAGE, số lượng → xác nhận.
- Ví dụ nhận 100, đạt 98: Put-away 98 vào STORAGE và 2 vào QC/QUARANTINE. Tổng Inventory vẫn là 100; 2 hàng cách ly không tính vào khả dụng của Planning.
- Đưa hàng ra khỏi QC/QUARANTINE cần Admin/Director. Trạng thái kiểm tra chất lượng hiện thể hiện bằng vị trí Bin, chưa có biểu mẫu kiểm nghiệm chuyên biệt.

Nhận hàng và hóa đơn là hai việc riêng. Tạo Purchase Invoice từ Receipt rồi Post Invoice để ghi công nợ; không cộng tồn lần thứ hai.

## 3. Bán và xuất hàng

Sales Order → Release → Pick → Post Shipment → Sales Invoice.

1. Sales Order dùng đúng Item/kho, lưu rồi Release.
2. Mở card kho → Pick → chọn Item, từ STORAGE/PICKING sang SHIP, nhập lượng cần lấy. Nếu lấy từ nhiều Bin thì đăng ký từng lượt.
3. Pick chỉ thay đổi vị trí; tổng Inventory chưa giảm.
4. Quay về Sales Order → Post Shipment. Chỉ số lượng có ở Bin SHIPPING mới được giao. Post Shipment giảm Inventory và Bin đúng một lần.
5. Lập hóa đơn theo số lượng đã giao qua luồng Sales hiện có.

Đơn Sales luồng cũ đã xử lý tồn trước đây không được xuất lại qua luồng mới. Pick hiện tập kết theo Item/kho tại SHIP, chưa giữ chỗ riêng theo từng Sales Order.

## 4. Sản xuất và Order Planning

Production Order nháp + BOM → Order Planning → Make Orders → nhận linh kiện → Put-away → hoàn tất sản xuất.

Nhu cầu linh kiện = định mức BOM × số lượng sản xuất. Planning cộng nhu cầu Sales và Production theo Item/kho, trừ tồn khả dụng và PO chưa nhận. QC/Quarantine/Return/Bin khóa được loại khỏi khả dụng. Kho được bật Bin chỉ cho tiêu hao từ Storage/Picking/Shipping; linh kiện vừa nhận cần Put-away trước.

Thành phẩm nhập từ sản xuất đi vào RECEIVE; tiếp tục Put-away để đưa vào vị trí lưu trữ. Không bật Bin ở tất cả kho trước khi hiểu thay đổi này.

## 5. Di chuyển và chuyển kho

- **Movement / QC:** cùng Location, khác Bin. Một bút toán âm ở Bin nguồn và một bút toán dương ở Bin đích; không đổi Inventory, không phát sinh công nợ/G/L.
- **Transfer trực tiếp:** hai Location khác nhau, cả hai đã bật Bin. Kiểm tra tồn nguồn; xuất kho nguồn và nhập kho đích trong cùng giao dịch. Giữ giá vốn nguồn, tính lại bình quân ở đích theo độ chính xác tiền tệ của hệ thống. Chưa có trạng thái In-Transit.
- Nếu nguồn thiếu, Bin đích khóa hoặc vượt sức chứa, toàn bộ thao tác rollback.

## 6. Kiểm kê và điều chỉnh

1. Card kho → Lập phiếu kiểm kê → chọn Bin và Item.
2. Hệ thống hiển thị/chốt số hệ thống. Kiểm đếm rồi nhập số thực tế và lý do.
3. Xác nhận tạo phiếu PENDING.
4. Admin/Director → Phê duyệt → APPROVED.
5. Post điều chỉnh → POSTED. Nếu có phát sinh ở Bin từ khi chốt số, phải tạo lại phiếu kiểm kê.
6. Số chênh lệch cập nhật Bin, Inventory và Inventory Ledger; tính giá trị theo giá vốn bình quân. Bút toán VND dùng tài khoản INVENTORY/ADJUSTMENT trong cấu hình hạch toán Purchase dùng chung; thiếu cấu hình thì không Post.

Ví dụ hệ thống 100, thực tế 95: chênh lệch -5, giảm tồn 5. Không trực tiếp sửa Warehouse Entries. Post lại một phiếu đã Post không ghi thêm lần nữa.

## 7. Vendor và công nợ

Card Vendor hiển thị thống kê chứng từ và số dư theo từng tiền tệ. Vendor Ledger Entries chỉ phát sinh khi Post Invoice, Payment, Credit Memo, Refund; không tự thêm/sửa/xóa trực tiếp.

PO nháp/đã Release là cam kết mua, không phải công nợ đã ghi sổ. Nhận hàng là biến động kho. Invoice mới là căn cứ ghi công nợ. Thanh toán giảm công nợ, không thay đổi tồn.

Trả hàng: đưa hàng về Bin có thể xuất, lập Purchase Return Order và Ship Return → xuất trả; Credit Memo → giảm công nợ; Refund khi có khoản hoàn tiền. Không tự ghi nhận công nợ chỉ vì di chuyển Bin.

## 8. Đối chiếu sau mỗi luồng

- Tổng các Bin của Item trong kho phải bằng Inventory của Item/kho đó.
- Warehouse Entries ghi vị trí; Inventory Ledger ghi thay đổi tổng tồn.
- Card Warehouse hiển thị tồn, giữ chỗ, khả dụng và hàng đang mua theo Item.
- Card Vendor tách công nợ từng tiền tệ; thống kê giá trị PO không được coi là số phải trả.
- Report: chọn bản ghi hiện tại hoặc From Date–To Date; ngày lọc tùy loại chứng từ. Danh mục không có ngày thì bỏ trống khoảng ngày.
- Các lần kiểm thử tự động dùng rollback, không để lại chứng từ thử trong dữ liệu vận hành.

## Report và các liên kết dùng chung

- Mở card Location hoặc Vendor → Report → chọn **Nội dung báo cáo**.
- Location: tồn Bin hiện tại, Warehouse Entries, Inventory Ledger hoặc Purchase Receipts; báo cáo giữ bộ lọc đúng kho đang mở.
- Vendor: Vendor Ledger Entries, Purchase Headers hoặc Purchase Receipts; báo cáo giữ đúng Vendor đang mở.
- Chứng từ Warehouse: Report có Warehouse Entries và G/L liên quan; phần Dữ liệu liên quan cho phép truy ngược chứng từ.
- Lịch sử kho dùng ngày phát sinh; công nợ dùng Posting Date; Receipt dùng ngày nhận hàng. From Date và To Date đều được tính. Bỏ trống một phía để không giới hạn phía đó.
- Tồn Bin là số dư hiện tại, không phải báo cáo tồn tại một ngày quá khứ; form không cho dùng bộ lọc ngày gây hiểu nhầm. Muốn xem phát sinh theo kỳ, chọn Warehouse Entries hoặc Inventory Ledger.
- Form thao tác kho dùng header và nhóm trường của profile form hiện có; Report dùng chung mẫu in/PDF của hệ thống.
