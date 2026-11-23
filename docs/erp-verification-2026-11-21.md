# Kết quả kiểm thử lại ERP — 21/11/2026

## Kết luận
Ứng dụng chạy tại http://localhost:8081 bằng bản build mới. Các kiểm thử dưới đây đạt, nhưng chưa đáp ứng toàn bộ yêu cầu lịch sử và không tương đương đầy đủ Business Central.

## Bằng chứng đã thực hiện
- Maven verify: 214 bài, 0 lỗi, 0 thất bại, 0 bỏ qua. Có kết nối PostgreSQL cho integration tests.
- 34 file JavaScript kiểm tra cú pháp đạt.
- Trình duyệt thật: mở 44 danh sách, 14 form; không ghi nhận JavaScript exception.
- HR: 8 API trả 200, 6 trang mở không lỗi; chưa có thanh Report chung trên các trang này.
- 6 kịch bản browser đạt: HR, list actions/report, Purchase compact, Purchase fields, Planning layout, Warehouse shared.
- 7 nhóm báo cáo (Sales, Purchase, Vendor, Location, Items, BOM, Production Order): xem trước và tải được 7 PDF, 7 XLSX. Kiểm tra chữ ký PDF và đọc workbook XLSX thành công. Không coi đây là chứng nhận mọi trang PDF dài đều không bị cắt.
- Bộ lọc ngày Purchase: lấy cả hai mốc đầu/cuối, giới hạn đúng loại chứng từ đang mở; ngày không có dữ liệu trả 0 dòng. Regression report kiểm tra phân trang 101 dòng, escape HTML và From Date lớn hơn To Date.
- Avatar: ảnh được chuẩn hóa 256×256; hai tài khoản dùng hai ảnh khác nhau; đổi A không đổi B; người dùng thường bị chặn đổi ảnh người khác; ảnh giả/ảnh quá 2 MB bị từ chối.
- Presence: nhiều tab ưu tiên ACTIVE; tất cả tab AWAY thì tạm vắng; quá 2 phút không heartbeat thì OFFLINE; rời phiên xóa trạng thái phiên. Trình duyệt đăng nhập thấy avatar và danh sách đồng nghiệp hoạt động.
- Đối soát DB chỉ đọc: 0 tồn âm, 0 giữ chỗ vượt tồn, 0 lệch tổng tồn Item, 0 nhận/giao/hóa đơn vượt lượng, 0 chứng từ G/L mất cân đối.

## Những thay đổi cần kiểm tra lại
1. Mỗi User lưu một avatar theo user_id. Employee liên kết User hiển thị ảnh của cùng người. Employee chưa có tài khoản cần liên kết trước. Không dùng ảnh chung cho các tài khoản.
2. Thêm Đồng nghiệp ở thanh trên. ACTIVE khi hoạt động; AWAY khi đổi tab hoặc không tương tác 5 phút; OFFLINE khi logout hoặc mất heartbeat hơn 2 phút. Không dùng trạng thái này thay bảng chấm công.
3. Sales Invoice mới tự ghi công nợ và G/L khi lưu. Hóa đơn đã ghi sổ không sửa/xóa trực tiếp; đã sửa cả nút “Chỉnh sửa hồ sơ”. Không tự ghi sổ lại hóa đơn lịch sử.
4. Thu tiền hóa đơn liên kết Payments, Payment Allocations, Customer Ledger và G/L; cùng request key không tạo thanh toán trùng; chặn thu vượt công nợ. Integration kiểm tra hóa đơn 132, thu 100, còn 32.
5. Hoàn tất Production ghi giá vốn nguyên vật liệu vào thành phẩm và G/L cân đối. Kiểm thử 8 linh kiện giá 10 tạo 2 thành phẩm, giá vốn mỗi thành phẩm 40. Chưa có phân bổ nhân công/chi phí chung/WIP đầy đủ.
6. BOM chọn được Manufactured Item chưa có Production Order.
7. Khi chưa có quy đổi UOM, chặn đơn vị khác đơn vị cơ sở để tránh sai tồn. Đây là giới hạn có chủ đích, không phải đã triển khai quy đổi đơn vị.
8. Report có Xem trước → Tải PDF/Tải Excel. File dùng đúng dữ liệu bản xem trước. Đã đổi tải Blob từ trang chính. Lỗi đường dẫn tải trong công cụ kiểm thử Windows được sửa riêng; không khẳng định mọi lần tải cũ đều lỗi ở ứng dụng.

## Cách người dùng kiểm tra lại
- Ctrl+F5, đăng nhập, mở Đồng nghiệp, đổi ảnh của tài khoản A. Đăng nhập tài khoản B ở cửa sổ riêng và xác nhận B không đổi ảnh. Đổi tab để xem Tạm vắng; logout để xem Ngoại tuyến.
- Mở Sales/Purchase card đã lưu → Report. Chọn phạm vi, From Date/To Date → Xem trước → Tải PDF và Tải Excel. Đối chiếu số chứng từ, dòng và tổng tiền với bản xem trước. Danh mục tồn hiện tại không giả lập số dư lịch sử bằng bộ lọc ngày.
- Tạo Sales Order mới → Release → giao hàng → lưu Invoice. Mở hóa đơn kiểm tra công nợ/G/L; thu một phần và xem dư còn lại. Đơn cũ legacy không tự chuyển sang cơ chế trừ tồn mới.
- Tạo Manufactured Item → BOM → Production Order; kiểm tra thiếu linh kiện, nhập đủ, hoàn tất và đối chiếu tồn/giá vốn.
- Planning: SO mới đã Release, tồn 30, cần 100, chưa có nguồn cung → đề xuất 70; chọn Make Orders tạo PO. Với BOM, tạo nhu cầu Production và kiểm tra thiếu từng linh kiện.

## Chưa hoàn tất / giới hạn nghiệm thu
- Bộ dữ liệu nghiệp vụ mới đầy đủ theo yêu cầu tên thực tế chưa được thêm trong đợt kiểm tra này. Dữ liệu kiểm thử được rollback; không sửa tên/xóa chứng từ cũ.
- DB hiện có 4 SO legacy, 0 SO luồng mới, 0 Sales Invoice, 0 kho bật Bin. Kết quả đối soát 0 lỗi không chứng minh luồng chưa có dữ liệu đã được người dùng chạy thật; integration tests là bằng chứng riêng.
- HR chưa thống nhất thanh Report với ERP.
- Sales Return chưa có đầy đủ Credit Memo/hoàn tiền khách.
- Chưa có quy đổi đơn vị đầy đủ, giá thành đầy đủ, Planning theo ngày nhận dự kiến/công suất, vận chuyển kho trung chuyển.
- Chưa nghiệm thu tải lớn, nhiều người Post đồng thời, báo cáo rất dài, mạng mất giữa chừng, backup/restore và triển khai trên DB trống.

## Tệp bằng chứng
Workspace C:/Users/phucv/Downloads/EIMS-master/build:
- erp-fix-all-tests.log (không công khai log nguyên bản vì có thể có cấu hình môi trường)
- erp-audit-browser.json, erp-audit-hr.json, erp-audit-db.txt
- people-report-live.json, all-report-live.json
- all-report-downloads/ (PDF/XLSX đã tải thực tế)
- final-*.cjs.log (kết quả hồi quy)

Báo cáo audit cũ erp-business-central-audit-2026-11-21.md là hiện trạng trước các sửa đổi; dùng tài liệu này để đánh giá trạng thái mới nhất.
