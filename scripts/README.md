# Chạy web local ổn định

Web cần backend Java và PostgreSQL chạy; `mvn test` chỉ chạy kiểm thử, không bật web.

Build bản chạy bằng JDK 26 (dùng đường dẫn `mvn.cmd` của IntelliJ nếu Maven chưa có trong PATH). Trong IntelliJ, đặt Maven Runner JRE thành JDK 26 và reload Maven sau khi thay đổi `pom.xml`:

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\openjdk-26"
mvn package -DskipTests
New-Item -ItemType Directory -Path .local-runtime -Force
Copy-Item target/demo-0.0.1-SNAPSHOT.jar .local-runtime/application.jar
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/local-web.ps1 -Action Start -JavaPath "$env:JAVA_HOME\bin\java.exe"
```

Mở http://localhost:8081/ sau thông báo `Web ready`. Tiến trình chạy nền độc lập với tab terminal; nếu Java thoát, supervisor chờ 5 giây rồi chạy lại. Nếu thoát liên tục 5 lần, supervisor dừng để tránh lặp lỗi. Log được lưu trong `.local-runtime`, ngoài thư mục build `target`.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/local-web.ps1 -Action Status
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/local-web.ps1 -Action Stop
```

Khi cập nhật code: Stop, build và copy lại JAR, rồi Start. Bản chạy nền dùng JAR đã copy nên việc chạy test/build không thay thế file đang chạy. Sau khi khởi động lại Windows, chạy lệnh Start lại. Web không hoạt động khi máy tắt/ngủ hoặc database ngừng hoạt động. Không chạy thêm backend từ IDE khi bản chạy nền đang chiếm cổng 8081.

# Ảnh bài viết, Announcement và tuyển dụng HR

HR, Admin và Director có thể vào **Employees** để thêm, sửa và xóa hồ sơ nhân viên. Form gồm mã nhân viên, họ tên, email, điện thoại, phòng ban, chức vụ, ngày vào làm và trạng thái hoạt động. Mã nhân viên là duy nhất. Các thao tác này quản lý hồ sơ `employees`; tài khoản đăng nhập và phân quyền vẫn do Admin quản lý. Xóa hồ sơ không xóa tài khoản liên kết.

Trong **Leave Requests**, HR xem được đơn của mọi người, tạo đơn cho chính mình và chỉ sửa/hủy đơn của mình đang `PENDING`. HR không được sửa, duyệt hoặc xóa đơn của người khác. Đơn đã duyệt/từ chối/hủy không được sửa nội dung; quyền duyệt vẫn thuộc Admin/Director khác người đăng ký. API kiểm tra chủ đơn và chặn ngày nghỉ trùng khi tạo/sửa.

HR có một trang chung **Recruitment & News & Announcements**, hiển thị cả bài tuyển dụng, tin tức và thông báo. Dùng bộ lọc Tất cả/Tuyển dụng/Tin tức/Thông báo và ô tìm kiếm để xem bài. Bấm **+ New post**, chọn **Tuyển dụng** hoặc **Tin tức / Thông báo** để mở form phù hợp; sửa hoặc lưu xong đều trở về trang chung. Chuông thông báo cũng mở và đưa tới đúng bài trên trang này.

Trong cả hai form, mục **Thêm hình ảnh** nằm ngay dưới tiêu đề. Chọn một ảnh JPG/PNG, xem trước, thay ảnh hoặc gỡ ảnh khi lưu bài. Giới hạn 5 MB, 16 triệu điểm ảnh và 8000 px mỗi chiều. Ảnh không bắt buộc. Ảnh bài viết cũng xuất hiện trong Preview, danh sách và cửa sổ đọc bài.

Ảnh được kiểm tra nội dung thực, mã hóa lại và lưu trong PostgreSQL cùng transaction với bài viết. Sửa nội dung mà không chọn ảnh mới sẽ giữ nguyên ảnh; xóa bài cũng xóa ảnh. Ảnh bài nháp/đóng chỉ HR, Admin, Director xem được; nhân viên chỉ xem ảnh của bài đã đăng và còn hạn. Khởi động lại web hoặc build không làm mất ảnh.

## Chuông thông báo

Bài tuyển dụng hoặc tin/bài viết HR được đăng (`PUBLISHED`) sau khi bật tính năng sẽ tạo thông báo. Lưu nháp, sửa bài đã đăng hoặc đăng lại cùng bài không tạo bản trùng. Không phát lại hàng loạt thông báo cho bài cũ.

Chuông tự tải dữ liệu mỗi 2 giây khi tab đang hiển thị, cập nhật khi quay lại tab và sau khi lưu bài trên máy đang dùng. Số trên chuông là số chưa đọc của tài khoản đang đăng nhập; không còn chấm đỏ mặc định. Bấm một thông báo mở đúng bài và đánh dấu đã đọc; có nút đánh dấu tất cả đã đọc. Trạng thái đọc lưu trong PostgreSQL, giữ nguyên sau đăng nhập lại hoặc khởi động lại web. Bài gỡ xuống/xóa hoặc tuyển dụng hết hạn không xuất hiện trong danh sách thông báo.

Chạy `psql -X -h localhost -U postgres -d erp -f scripts/verify-notifications.sql` để kiểm tra phát thông báo, chống trùng và đọc riêng từng tài khoản trong transaction được rollback.

# Item, tồn kho và production

Products và Product Categories đã được bỏ khỏi catalog, menu, All Data và API CRUD; bảng database cũ được giữ để bảo toàn tồn kho lịch sử. Nhóm danh mục còn lại là Đơn vị tính & Thuế. Inventory hiển thị mã/tên vật tư, tên kho, số lượng, đơn vị và giá vốn; có thể tìm theo tên vật tư. Dữ liệu số vẫn giữ nguyên độ chính xác, phần hiển thị bỏ số 0 thập phân dư và dùng dấu phân cách Việt Nam. Tiền VND hiển thị theo đồng (ví dụ `100.000 ₫`), làm tròn khi hiển thị, không thay đổi dữ liệu lưu.

- `Items` là đối tượng quản lý tồn kho: `PURCHASED` (mua sẵn/vật tư), `MANUFACTURED` (có production/thành phẩm/bán thành phẩm). Không tạo Product để đại diện cho Item.
- Tạo Item tự tạo một dòng Inventory bằng 0 tại kho `MAIN`, hoặc kho có ID nhỏ nhất nếu không có `MAIN`. Cần tạo kho trước khi tạo Item. `items.quantity` là tổng tồn của Item ở tất cả kho và không nhập trực tiếp.
- Nhập hàng: tạo Purchase Receipt rồi thêm Purchase Receipt Lines. Kho lấy từ dòng, nếu trống lấy từ phiếu, cuối cùng lấy kho mặc định. Lưu dòng là cộng tồn; sửa/xóa điều chỉnh chênh lệch và chặn nếu hàng đã tiêu thụ khiến tồn âm.
- Bán hàng: tạo Sales Order và chọn kho, thêm Sales Order Lines để trừ tồn ngay. Sửa dòng chỉ trừ/cộng chênh lệch, xóa dòng hoặc chuyển đơn sang `CANCELLED` hoàn tồn. Phiếu giao hàng/hóa đơn không trừ lần nữa. Sales Lines (Legacy) chỉ đọc để tránh nhập trùng hai bảng dòng bán.
- Production: chọn Item `MANUFACTURED`, khai báo BOM trong menu Production (định mức linh kiện cho 1 đơn vị), tạo lệnh `DRAFT`. Chuyển sang `COMPLETED` sẽ trừ `định mức × số lượng sản xuất` và cộng thành phẩm tại kho đã chọn trong cùng transaction. Thiếu linh kiện thì toàn bộ thao tác rollback. BOM không được có vòng lặp. Bán thành phẩm cần có tồn trước khi dùng làm linh kiện; không tự động sản xuất đệ quy.
- Lệnh hoàn tất/hủy không sửa được. Lưu lại trạng thái hoàn tất không ghi tồn hai lần. Bảng linh kiện đã tiêu hao lưu định mức thực tế lúc hoàn tất; thay đổi BOM sau đó không sửa lịch sử.
- Inventory Ledger và Production Components là lịch sử chỉ đọc. Chỉnh số lượng Inventory dùng cho kiểm kê/số dư đầu kỳ, cũng đồng bộ quantity và ghi ledger. Các số lượng nhập qua API tối đa 3 chữ số thập phân.

Migration `inventory-v1` chạy một lần khi khởi động. Tồn kho Product cũ được giữ riêng, không tự đoán liên kết với Items. Items có sẵn được khởi tạo tồn bằng 0; cần nhập số dư thực tế đã đối soát. Chứng từ cũ không bị tự động ghi tồn lại và không cho đổi số lượng/xóa khi chưa có lịch sử tự động. Không sửa tồn Product cũ để dùng thay tồn Item.

Kiểm tra nghiệp vụ trực tiếp trên PostgreSQL (dùng cấu hình kết nối/PGPASSWORD của môi trường):

```powershell
psql -X -h localhost -U postgres -d erp -f scripts/verify-inventory.sql
```

Script tự kiểm tra schema trong transaction, chạy các tình huống nhập/bán/sản xuất/thiếu hàng và kết thúc bằng `ROLLBACK`, không giữ dữ liệu kiểm thử. `mvn test` chạy bộ test Java riêng.

# Dữ liệu minh họa ERP

## Sales Returns — trả hàng bán

Trong Sales Orders, mở **Sales Returns · Trả hàng bán** → tạo phiếu nháp theo đơn bán gốc và kho nhận hàng. Mở chi tiết phiếu → **Thêm dòng trả hàng**, chọn dòng bán gốc và số lượng. Sau khi nhận hàng thực tế, bấm **Nhận hàng vào kho**. Phiếu nháp chưa đổi tồn; nhận hàng cộng lại inventory và quantity của Item, ghi inventory ledger trong cùng giao dịch.

Không nhận vượt tổng số lượng đã xuất trừ các phiếu trả đã nhận. Phiếu đã nhận/đã hủy bị khóa; đơn và dòng bán có hàng đã trả không được thay đổi tồn hoặc hủy để hoàn kho trùng. Chứng từ cũ chưa có lịch sử xuất kho tự động không được nhận trả tự động. Chức năng này chưa tạo hoàn tiền, hóa đơn điều chỉnh hoặc bút toán công nợ.

Migration `sales-returns-v1` chạy một lần sau `inventory-v1`. Kiểm tra `scripts/test-sales-returns.sql` trong transaction và **ROLLBACK** sau khi chạy để không lưu dữ liệu thử.

## Form dạng trang chứng từ

Các form ERP, khách hàng và HR dùng chung `document-form.js` / `document-form.css`: thanh Lưu cố định, nhóm thông tin thu gọn, bố cục hai cột và tóm tắt bên phải. Trên màn hình nhỏ, các nhóm chuyển thành một cột. Input và handler cũ được giữ nguyên; form HR vẫn có tải ảnh và xem trước bài viết.

Form tạo Sales Order có bảng dòng vật tư, tìm mã/tên, thêm/xóa dòng, số lượng, đơn giá, chiết khấu và VAT. Tổng tiền hiển thị VND. Form gửi một request đến `POST /api/v1/sales-orders`; backend tính lại tiền và lưu header cùng các dòng trong một transaction. Thiếu tồn kho sẽ rollback toàn bộ. Tiền dòng làm tròn đến hai chữ số thập phân trước khi tính chiết khấu/VAT; giao diện hiển thị tiền Việt không có phần lẻ. Form sửa đơn hiện vẫn sửa thông tin chung; chỉnh dòng đã lưu trong danh mục dòng đơn bán.

Các trường nhập liệu bám theo dữ liệu hệ thống hiện có. Những chức năng riêng của ảnh mẫu như Copilot, duyệt chứng từ và địa chỉ giao hàng tùy chỉnh chưa được thêm.

`seed-demo.sql` được chạy thủ công trên database `erp`, không tự chạy khi khởi động ứng dụng.

- Chèn dữ liệu vào các bảng đang trống; không cập nhật hoặc xóa bản ghi có sẵn.
- Bổ sung các bản ghi cha `DEMO-...` cần thiết để không thay đổi đơn hàng đang có.
- Thực hiện trong một transaction. Lỗi sẽ rollback toàn bộ bản ghi của lần chạy.
- Khi tất cả bảng đã có dữ liệu, chạy lại không chèn thêm.
- Bản sao lưu trước lần chạy nằm trong `target/erp-before-demo-*.dump` (không đưa vào Git).

## Chuỗi minh họa

Nhập 10 sản phẩm × 20.000 VND = 200.000 VND; bán 1 sản phẩm giá 100.000 VND, thuế 10.000 VND, tổng 110.000 VND. Tồn kho còn 9 sản phẩm. Hai hóa đơn được thanh toán đủ. Sổ cái có các cặp nợ/có cân bằng, gồm giá vốn 20.000 VND. Phiếu trả hàng là bản nháp chưa tác động tồn kho.

`items`/`product` và `vendors`/`supplier` là các bảng riêng trong schema hiện tại; dữ liệu DEMO tương ứng dùng cùng tên minh họa. `sales_lines` là bảng bán hàng cũ, `sales_order_lines` là bảng được hóa đơn/xuất hàng tham chiếu. Không cộng hai bảng dòng bán hàng với nhau.

Đây là dữ liệu phục vụ trình diễn cũ. Không chạy lại `seed-demo.sql` sau khi bật migration tồn kho tự động: script cũ ghi cả hai bảng dòng bán và tự chèn tồn kho. Các quy trình tồn kho mới được mô tả ở trên; hạch toán tài chính vẫn cần đối soát riêng.

## Sales form theo mẫu Business Central (nghiệp vụ Việt Nam)

- Định nghĩa field bổ sung: `sales-form-fields.json`; lưu trong `sales_headers`, không tạo các field CFDI.
- General, Sales Lines, Invoice Details, Shipping and Billing có Show more/less. Các trường lookup cấu hình tại All Data → Sales Setup; nhân viên và người phụ trách lấy từ Employees/Users. Payment Method vẫn lấy từ Payment.
- `sales_lines` là bảng đọc chung, liên kết bằng `sales_header_id`. Trigger đồng bộ từ các bảng dòng cũ, giữ ID chung ổn định. Các API ghi cũ vẫn là bộ xử lý tương thích để bảo toàn kiểm tra kho, số lượng hóa đơn và trả hàng; chưa xóa các bảng cũ.
- Chỉ bộ xử lý dòng đơn bán tác động kho; đồng bộ `sales_lines` không tác động kho. Migration phục hồi trigger Sales bị thiếu, không ghi lại tồn kho của chứng từ lịch sử.
- Báo giá giữ nguyên khi Make Order và sinh Sales Header ORDER riêng; các field bổ sung được sao chép. Make Invoice tạo đơn/hóa đơn trong một transaction và ghi liên kết chống tạo trùng khi gửi lại.
- Archive Document lưu snapshot header và lines theo phiên bản. Release/Reopen hiện áp dụng cho báo giá. API đọc snapshot: `/api/erp/sales-form/headers/{id}/archives`.
- Các mã posting group/dimension hiện được lưu và chọn từ danh mục, chưa phải bộ máy hạch toán/thuế đa cấu hình của Business Central. Chưa có chiết khấu toàn chứng từ, giá gồm VAT, assembly theo dòng hoặc tạo warehouse pick.
- Kiểm thử SQL: chạy `test-sales-common.sql` và `test-sales-returns.sql` trong `BEGIN`/`ROLLBACK` sau migrations. Không dùng dữ liệu kiểm thử để ghi sổ thật.

### Java sources and tests in one directory

All Java files now live under `src/main/java/org/example`, including the tests beside their modules. Maven excludes `**/*Test.java` from application compilation and compiles only those files during `test-compile`, with test dependencies kept in test scope. Name new test classes `*Test.java`; run them with `mvn test`. Do not add test libraries to application dependencies to resolve IDE highlighting. Reload the Maven project after changing source paths. IDEs that cannot distinguish main/test files inside the same source root should run tests through Maven.
