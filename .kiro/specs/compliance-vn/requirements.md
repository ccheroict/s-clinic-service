# Requirements Document: Tuân thủ quy định Việt Nam (compliance-vn)

## Introduction

Tài liệu này mô tả yêu cầu đưa hệ thống s-clinic lên mức tuân thủ đầy đủ quy định pháp luật Việt Nam về dữ liệu cá nhân, hồ sơ bệnh án điện tử, đơn thuốc điện tử và hóa đơn điện tử, đồng thời hoàn thiện các module nghiệp vụ hiện chỉ tồn tại ở dạng schema.

Phạm vi đã chốt với chủ phòng khám:

- Phòng khám da liễu **tự nguyện toàn phần, KHÔNG nhận BHYT**. Loại khỏi phạm vi: xuất XML theo QĐ 4210, trích chuyển dữ liệu theo TT 48/2017, tách phần BHYT chi trả và đồng chi trả. Vẫn giữ trường `insurance_no` vì bệnh nhân có thể xuất trình thẻ BHYT như giấy tờ định danh.
- Triển khai trên **cloud trong nước**. Loại khỏi phạm vi: hồ sơ đánh giá tác động chuyển dữ liệu ra biên giới (NĐ 13 Đ25). Vẫn cần hợp đồng xử lý dữ liệu với nhà cung cấp cloud (NĐ 13 Đ39).
- Nhà cung cấp hóa đơn điện tử: **VNPT**.
- **Hồ sơ bệnh án điện tử đầy đủ, bỏ hồ sơ giấy**.
- Ký số: **VNPT SmartCA (ký số từ xa)**, đặt sau port `DigitalSignatureProvider` để đổi sang USB token bằng cách thêm adapter.
- Mục tiêu tuân thủ tối đa. Chủ phòng khám chấp nhận ma sát vận hành (break-glass, khóa bản ghi sau khi ký).

### Hiện trạng đã xác minh bằng đọc code

Đã triển khai đầy đủ: Patient CRUD, Appointment (state machine 7 trạng thái, ConflictChecker, BusinessHoursValidator Mon-Sat 08:00-17:00 Asia/Ho_Chi_Minh), Spring Security HTTP Basic stateless 3 role, BCrypt, AdminSeeder, AuditService chạy REQUIRES_NEW, GlobalExceptionHandler, `GET /api/me`.

Chỉ có schema chưa có code Java: `encounter` (+ `clinical_data` jsonb), `clinical_photo`, `treatment_course`/`treatment_session`, `prescription`/`prescription_item`, `invoice`/`invoice_item`, `service`, `specialty`, `integration_outbox`, `external_reference`.

Migration hiện có V1, V2, V3. Migration mới bắt đầu từ V4. `spring.jpa.hibernate.ddl-auto=validate` nên entity phải khớp schema.

### Tình trạng đối chiếu văn bản

Bốn văn bản sau **chưa đọc được nội dung** do hạn chế mạng của môi trường phát triển:

- TT 13/2025/TT-BYT (hồ sơ bệnh án điện tử, có thể thay TT 46/2018)
- QĐ 425/QĐ-BYT 2025 (quy chế Hệ thống thông tin Quốc gia về quản lý kê đơn thuốc)
- Công văn 365/TTYQG-GPQLCL ngày 06/06/2025 (yêu cầu kỹ thuật phần mềm HSBA điện tử)
- TT 32/2023/TT-BYT (mẫu hồ sơ bệnh án)

Nguyên tắc xử lý: Giai đoạn 0 không phụ thuộc các văn bản này. Các yêu cầu phụ thuộc văn bản được đánh dấu `[CẦN KIỂM CHỨNG]` và phải xác nhận nội dung văn bản trước khi hiện thực hóa, không được đoán trường dữ liệu rồi làm lại.

## Glossary

- **Facility_Record**: Bản ghi cơ sở khám chữa bệnh, chứa mã cơ sở KCB, mã liên thông cơ sở, MST, giấy phép hoạt động, người chịu trách nhiệm chuyên môn, cấu hình hóa đơn điện tử.
- **Session_Token**: Token phiên dạng opaque do server sinh và lưu, có thời hạn và thu hồi được, thay thế HTTP Basic.
- **Auth_Event**: Bản ghi sự kiện xác thực, gồm cả thành công và thất bại, phục vụ điều tra an ninh.
- **Audit_Entry**: Bản ghi vết truy cập hoặc thay đổi trong bảng `audit_log`, có hash liên kết tới bản ghi trước.
- **Audit_Chain**: Chuỗi các Audit_Entry liên kết bằng hash, cho phép phát hiện sửa hoặc xóa dòng ở giữa.
- **Break_Glass_Grant**: Quyền truy cập tạm thời ngoài phạm vi thông thường, cấp sau khi người dùng nhập lý do, có thời hạn và bị audit ở mức cảnh báo.
- **Care_Relationship**: Quan hệ điều trị giữa một Staff_Record vai trò DOCTOR và một Patient_Record, xác lập khi tồn tại Appointment_Record hoặc Encounter_Record giữa hai bên.
- **Retention_Policy**: Quy tắc xác định thời hạn lưu trữ tối thiểu của một loại bản ghi.
- **Legal_Hold**: Cờ chặn tiêu hủy bản ghi bất chấp thời hạn lưu trữ đã hết.
- **Consent_Type**: Loại sự đồng ý có nội dung được đánh phiên bản.
- **Consent_Record**: Bản ghi sự đồng ý của một bệnh nhân với một phiên bản Consent_Type cụ thể.
- **Allergy_Record**: Bản ghi dị ứng có cấu trúc của bệnh nhân, tham chiếu chất gây dị ứng có mã.
- **Encounter_Record**: Bản ghi một lần khám, có vòng đời DRAFT, SIGNED, AMENDED.
- **Clinical_Data_Schema**: JSON Schema mô tả cấu trúc dữ liệu lâm sàng đặc thù chuyên khoa, có phiên bản.
- **Signature_Record**: Bản ghi chữ ký số, gồm giá trị ký, thuật toán, thời điểm, người ký, chứng thư, hash dữ liệu đã ký.
- **Amendment**: Bản đính chính của một Encounter_Record đã ký, trỏ về bản gốc, bắt buộc nêu lý do.
- **Prescription_Code**: Mã đơn thuốc 14 ký tự gồm mã cơ sở, mã đơn sinh tự động, ký hiệu loại đơn.
- **National_Rx_System**: Hệ thống thông tin Quốc gia về quản lý kê đơn thuốc.
- **Outbox_Event**: Bản ghi trong `integration_outbox`, ghi cùng transaction với thay đổi nghiệp vụ, được worker đẩy sang hệ thống ngoài.
- **EInvoice_Record**: Trạng thái đồng bộ hóa đơn điện tử trên `invoice`.
- **Payment_Record**: Bản ghi thu tiền gắn với một hóa đơn.
- **Clinical_Photo_Record**: Bản ghi ảnh lâm sàng, lưu bằng `storage_key` trên object storage riêng tư.
- **DSR_Request**: Yêu cầu thực thi quyền của chủ thể dữ liệu theo NĐ 13.
- **Disposal_Record**: Biên bản tiêu hủy bản ghi đã hết thời hạn lưu trữ.

## Requirements

### Requirement 1: Thực thể cơ sở khám chữa bệnh

**User Story:** Là quản trị hệ thống, tôi muốn hệ thống lưu thông tin định danh của phòng khám, để đơn thuốc, hóa đơn và kết nối hệ thống quốc gia có đủ dữ liệu bắt buộc.

#### Acceptance Criteria

1. THE Facility_Record SHALL lưu các trường: tên cơ sở, mã cơ sở KCB, mã liên thông cơ sở, mã số thuế, địa chỉ, số giấy phép hoạt động, ngày cấp giấy phép, người chịu trách nhiệm chuyên môn, điện thoại, email.
2. THE Facility_Record SHALL lưu cấu hình hóa đơn điện tử VNPT gồm: mẫu số hóa đơn, ký hiệu hóa đơn, mã đơn vị trên hệ thống VNPT.
3. WHEN ứng dụng khởi động lần đầu và chưa có Facility_Record nào, THE hệ thống SHALL tạo một Facility_Record từ cấu hình, và SHALL không tạo thêm bản ghi ở các lần khởi động sau.
4. WHEN người dùng đã xác thực gọi endpoint lấy thông tin cơ sở, THE Facility_API SHALL trả về Facility_Record hiện hành.
5. WHEN người dùng có vai trò ADMIN gửi yêu cầu cập nhật thông tin cơ sở với dữ liệu hợp lệ, THE Facility_API SHALL cập nhật Facility_Record và ghi Audit_Entry hành động UPDATE.
6. IF người dùng không có vai trò ADMIN gửi yêu cầu cập nhật thông tin cơ sở, THEN THE Facility_API SHALL trả về mã trạng thái 403 và không thay đổi dữ liệu.
7. IF mã cơ sở KCB rỗng hoặc chỉ chứa khoảng trắng, THEN THE Facility_API SHALL trả về mã trạng thái 400 với thông tin lỗi theo trường.

### Requirement 2: Xác thực bằng token phiên thay cho HTTP Basic

**User Story:** Là người phụ trách tuân thủ, tôi muốn thay HTTP Basic bằng token phiên có thời hạn và thu hồi được, để giảm rủi ro rò rỉ mật khẩu và kiểm soát được phiên truy cập dữ liệu y tế.

#### Acceptance Criteria

1. WHEN người dùng gửi tên đăng nhập và mật khẩu đúng, THE Auth_API SHALL tạo một Session_Token, trả về token kèm thời điểm hết hạn, vai trò và tên người dùng.
2. THE Session_Token SHALL được lưu ở phía server dưới dạng hash, SHALL có thời hạn không quá 8 giờ, và SHALL bị vô hiệu ngay khi bị thu hồi.
3. WHEN người dùng gửi yêu cầu tới endpoint cần xác thực kèm Session_Token còn hiệu lực, THE Backend_API SHALL xử lý yêu cầu với danh tính và vai trò tương ứng.
4. IF Session_Token đã hết hạn, bị thu hồi, hoặc không tồn tại, THEN THE Backend_API SHALL trả về mã trạng thái 401.
5. WHEN người dùng gọi endpoint đăng xuất, THE Auth_API SHALL thu hồi Session_Token đang dùng.
6. WHEN người dùng có vai trò ADMIN thu hồi các phiên của một người dùng khác, THE Auth_API SHALL vô hiệu toàn bộ Session_Token của người đó và ghi Audit_Entry.
7. THE hệ thống SHALL không còn chấp nhận HTTP Basic trên bất kỳ endpoint nào dưới `/api/**`.

### Requirement 3: Chính sách mật khẩu và chống dò mật khẩu

**User Story:** Là người phụ trách tuân thủ, tôi muốn hệ thống buộc dùng mật khẩu mạnh và chặn dò mật khẩu, để tài khoản truy cập dữ liệu y tế không bị chiếm dụng.

#### Acceptance Criteria

1. THE hệ thống SHALL yêu cầu mật khẩu có độ dài tối thiểu 12 ký tự và chứa ít nhất ba trong bốn nhóm: chữ thường, chữ hoa, chữ số, ký tự đặc biệt.
2. THE hệ thống SHALL không cho phép đặt lại mật khẩu trùng với các mật khẩu đã dùng gần nhất của cùng tài khoản.
3. WHEN một tài khoản đăng nhập lần đầu hoặc mật khẩu vừa bị ADMIN đặt lại, THE Auth_API SHALL yêu cầu đổi mật khẩu trước khi cho phép truy cập bất kỳ endpoint nghiệp vụ nào.
4. WHEN số lần đăng nhập thất bại liên tiếp của một tài khoản đạt ngưỡng cấu hình, THE Auth_API SHALL khóa tài khoản trong khoảng thời gian cấu hình và trả về thông báo không tiết lộ lý do cụ thể.
5. WHEN một lần đăng nhập thành công diễn ra, THE hệ thống SHALL đặt lại bộ đếm thất bại của tài khoản đó về không.
6. THE hệ thống SHALL ghi một Auth_Event cho mọi lần đăng nhập, đăng xuất, đổi mật khẩu, khóa tài khoản, kể cả các lần thất bại, gồm thời điểm, tên đăng nhập, địa chỉ IP, user agent và kết quả.
7. WHEN ứng dụng khởi động lần đầu và chưa có tài khoản nào, THE hệ thống SHALL tạo một tài khoản ADMIN với mật khẩu ngẫu nhiên đủ mạnh, ghi mật khẩu đó ra log đúng một lần, và đánh dấu tài khoản buộc phải đổi mật khẩu ở lần đăng nhập đầu.
8. THE hệ thống SHALL không tạo tài khoản với mật khẩu mặc định biết trước.

### Requirement 4: Xác thực hai yếu tố

**User Story:** Là người phụ trách tuân thủ, tôi muốn bắt buộc xác thực hai yếu tố với các vai trò có quyền cao, để hạn chế thiệt hại khi mật khẩu bị lộ.

#### Acceptance Criteria

1. THE hệ thống SHALL bắt buộc xác thực hai yếu tố dạng TOTP với các tài khoản vai trò ADMIN và DOCTOR, và SHALL cho phép bật tùy chọn với vai trò RECEPTIONIST.
2. WHEN một tài khoản bắt buộc dùng TOTP nhưng chưa đăng ký thiết bị, THE Auth_API SHALL yêu cầu hoàn tất đăng ký TOTP trước khi cấp Session_Token đầy đủ quyền.
3. WHEN người dùng nhập đúng mật khẩu và đúng mã TOTP trong cửa sổ thời gian cho phép, THE Auth_API SHALL cấp Session_Token.
4. IF mã TOTP sai hoặc đã hết cửa sổ thời gian, THEN THE Auth_API SHALL từ chối đăng nhập, ghi Auth_Event thất bại, và tính vào bộ đếm khóa tài khoản.
5. THE hệ thống SHALL cấp một tập mã dự phòng dùng một lần khi người dùng đăng ký TOTP, và SHALL vô hiệu từng mã ngay sau khi được dùng.
6. WHEN người dùng có vai trò ADMIN đặt lại TOTP của một tài khoản khác, THE hệ thống SHALL vô hiệu thiết bị TOTP cũ và ghi Audit_Entry.
7. THE hệ thống SHALL không cho phép một tài khoản tự đặt lại TOTP của chính mình mà không qua ADMIN.

### Requirement 5: Vết audit bất biến và đầy đủ ngữ cảnh

**User Story:** Là người phụ trách tuân thủ, tôi muốn vết audit không thể bị sửa và ghi đủ ngữ cảnh, để chứng minh được ai đã truy cập dữ liệu y tế nào và phát hiện can thiệp.

#### Acceptance Criteria

1. THE Audit_Entry SHALL ghi thêm địa chỉ IP, user agent và mã phiên của người thực hiện, ngoài các trường hiện có.
2. THE Audit_Entry SHALL chứa hash của Audit_Entry liền trước, tạo thành Audit_Chain.
3. WHEN người dùng có vai trò ADMIN yêu cầu kiểm tra tính toàn vẹn của Audit_Chain, THE Audit_API SHALL trả về kết quả cho biết chuỗi còn nguyên vẹn hay đã bị can thiệp, kèm vị trí bản ghi đầu tiên bất thường nếu có.
4. IF một Audit_Entry bị sửa nội dung hoặc bị xóa khỏi giữa chuỗi, THEN việc kiểm tra Audit_Chain SHALL phát hiện được bất thường.
5. THE cơ sở dữ liệu SHALL không cho phép tài khoản ứng dụng thực hiện UPDATE hoặc DELETE trên bảng `audit_log`.
6. THE hệ thống SHALL ghi Audit_Entry cho các hành vi xem một bản ghi bệnh nhân cụ thể, xuất dữ liệu ra tệp, và in tài liệu chứa dữ liệu bệnh nhân.
7. THE Audit_Entry SHALL không lưu giá trị dữ liệu nhạy cảm dưới dạng đọc được trong trường `detail`; thay vào đó SHALL lưu tên trường đã thay đổi và tham chiếu, sao cho bản thân bảng audit không trở thành kho dữ liệu nhạy cảm thứ hai.

### Requirement 6: Xóa mềm và metadata lưu trữ

**User Story:** Là chủ phòng khám, tôi muốn hệ thống không bao giờ xóa vĩnh viễn hồ sơ bệnh nhân, để không vi phạm nghĩa vụ lưu trữ hồ sơ bệnh án.

#### Acceptance Criteria

1. THE hệ thống SHALL không cung cấp bất kỳ đường nào xóa vĩnh viễn một Patient_Record hoặc Encounter_Record thông qua API nghiệp vụ.
2. WHEN người dùng có vai trò ADMIN yêu cầu xóa một Patient_Record kèm lý do, THE Patient_API SHALL đánh dấu bản ghi là đã xóa, lưu thời điểm xóa, người xóa và lý do, và trả về mã trạng thái 204.
3. THE các truy vấn danh sách và tìm kiếm SHALL mặc định loại trừ các bản ghi đã đánh dấu xóa.
4. THE mỗi bản ghi thuộc diện lưu trữ bắt buộc SHALL có thời hạn lưu trữ tối thiểu được tính và lưu tại thời điểm tạo hoặc thời điểm xóa mềm. `[CẦN KIỂM CHỨNG]` thời hạn cụ thể theo Luật KCB 15/2023 phải được xác nhận trước khi hiện thực hóa.
5. THE mỗi bản ghi SHALL có cờ Legal_Hold; khi cờ được bật, bản ghi SHALL không bị tiêu hủy bất kể thời hạn lưu trữ đã hết.
6. WHEN một Patient_Record được đánh dấu đã mất kèm ngày mất, THE hệ thống SHALL tính lại thời hạn lưu trữ theo quy tắc áp dụng cho trường hợp tử vong.

### Requirement 7: Mã hóa dữ liệu định danh và sao lưu

**User Story:** Là người phụ trách tuân thủ, tôi muốn số định danh của bệnh nhân được mã hóa và dữ liệu được sao lưu an toàn, để giảm thiệt hại nếu cơ sở dữ liệu bị truy cập trái phép.

#### Acceptance Criteria

1. THE hệ thống SHALL lưu các trường `national_id`, `insurance_no`, `tax_code` của bệnh nhân dưới dạng đã mã hóa trong cơ sở dữ liệu.
2. THE khóa mã hóa SHALL được cung cấp từ bên ngoài cơ sở dữ liệu qua biến môi trường hoặc dịch vụ quản lý khóa, và SHALL không được lưu trong mã nguồn hay trong cơ sở dữ liệu.
3. WHEN người dùng có quyền xem thông tin bệnh nhân gọi endpoint tương ứng, THE Patient_API SHALL trả về giá trị đã giải mã.
4. IF truy vấn trực tiếp vào cơ sở dữ liệu mà không qua ứng dụng, THEN các trường tại tiêu chí 1 SHALL không đọc được dưới dạng bản rõ.
5. THE hệ thống SHALL có quy trình sao lưu tự động, dữ liệu sao lưu được mã hóa, giữ nhiều bản theo chu kỳ, và có script kiểm thử phục hồi chạy được trên môi trường staging.
6. WHEN dữ liệu hiện có được chuyển sang dạng mã hóa, THE quá trình SHALL không làm mất dữ liệu và SHALL có bản sao lưu trước khi thực hiện.

### Requirement 8: Phân quyền tối thiểu cần thiết và break-glass

**User Story:** Là người phụ trách tuân thủ, tôi muốn bác sĩ chỉ thấy bệnh nhân mình điều trị, để thực hiện nguyên tắc tối thiểu cần thiết khi xử lý dữ liệu y tế.

#### Acceptance Criteria

1. WHEN người dùng có vai trò DOCTOR yêu cầu danh sách bệnh nhân, THE Patient_API SHALL chỉ trả về các Patient_Record có Care_Relationship với bác sĩ đó.
2. WHEN người dùng có vai trò DOCTOR yêu cầu xem một Patient_Record không có Care_Relationship với mình và không có Break_Glass_Grant còn hiệu lực, THE Patient_API SHALL trả về mã trạng thái 403 kèm chỉ dẫn rằng cần break-glass.
3. WHEN người dùng có vai trò DOCTOR yêu cầu Break_Glass_Grant cho một bệnh nhân kèm lý do không rỗng, THE hệ thống SHALL cấp quyền truy cập tạm thời có thời hạn cấu hình, ghi Audit_Entry ở mức cảnh báo, và thông báo cho các tài khoản ADMIN.
4. IF lý do break-glass rỗng hoặc chỉ chứa khoảng trắng, THEN THE hệ thống SHALL từ chối cấp quyền và trả về mã trạng thái 400.
5. WHEN Break_Glass_Grant hết thời hạn, THE hệ thống SHALL tự động ngừng cho phép truy cập theo quyền đó.
6. THE người dùng có vai trò RECEPTIONIST SHALL truy cập được thông tin hành chính của mọi bệnh nhân nhưng SHALL không truy cập được dữ liệu lâm sàng chi tiết.
7. THE người dùng có vai trò ADMIN SHALL truy cập được phục vụ quản trị, và mọi lần truy cập dữ liệu bệnh nhân của ADMIN SHALL được ghi Audit_Entry.

### Requirement 9: Thông tin bệnh nhân theo mẫu hồ sơ bệnh án

**User Story:** Là bác sĩ, tôi muốn hồ sơ bệnh nhân có đủ trường theo mẫu của Bộ Y tế, để hồ sơ bệnh án điện tử được chấp nhận.

`[CẦN KIỂM CHỨNG]` Toàn bộ yêu cầu này phụ thuộc mẫu hồ sơ bệnh án theo TT 32/2023. Phải xác nhận nội dung văn bản trước khi hiện thực hóa.

#### Acceptance Criteria

1. THE Patient_Record SHALL lưu địa chỉ dưới dạng có cấu trúc tham chiếu danh mục đơn vị hành chính, thay cho một trường văn bản tự do.
2. THE danh mục đơn vị hành chính SHALL có phiên bản, để địa chỉ đã ghi vẫn đọc được sau khi có thay đổi sáp nhập đơn vị hành chính.
3. THE Patient_Record SHALL hỗ trợ ngày sinh ở ba dạng: ngày tháng năm đầy đủ, chỉ có năm, và số tháng tuổi với trẻ dưới 72 tháng.
4. WHEN bệnh nhân dưới 72 tháng tuổi, THE hệ thống SHALL yêu cầu nhập tên bố hoặc mẹ hoặc người đưa trẻ đến khám.
5. THE Patient_Record SHALL lưu thêm dân tộc, quốc tịch, nghề nghiệp, và thông tin người liên hệ khẩn cấp gồm họ tên, quan hệ, điện thoại.
6. THE Patient_Record SHALL lưu cờ đã mất và ngày mất.

### Requirement 10: Dị ứng có cấu trúc

**User Story:** Là bác sĩ, tôi muốn dị ứng của bệnh nhân được ghi có cấu trúc, để hệ thống chặn được việc kê thuốc gây dị ứng.

#### Acceptance Criteria

1. THE Allergy_Record SHALL tham chiếu một chất gây dị ứng trong danh mục có mã, và SHALL lưu biểu hiện, mức độ, ngày ghi nhận, người ghi nhận, trạng thái còn hiệu lực.
2. THE hệ thống SHALL không cho phép hai Allergy_Record còn hiệu lực trỏ cùng một chất gây dị ứng trên cùng một bệnh nhân.
3. WHEN dữ liệu từ trường `allergies` dạng văn bản tự do được di trú, THE hệ thống SHALL chuyển nội dung sang ghi chú và đánh dấu bệnh nhân cần rà soát lại dị ứng, và SHALL không tự động suy diễn ra chất gây dị ứng có mã.
4. WHEN hồ sơ một bệnh nhân có Allergy_Record mức độ nặng được mở, THE Frontend_App SHALL hiển thị cảnh báo nổi bật.

### Requirement 11: Quản lý sự đồng ý

**User Story:** Là người phụ trách tuân thủ, tôi muốn hệ thống ghi nhận và chứng minh được sự đồng ý của bệnh nhân, để đáp ứng NĐ 13/2023 về dữ liệu cá nhân nhạy cảm.

#### Acceptance Criteria

1. THE Consent_Type SHALL bao gồm tối thiểu: khám chữa bệnh, xử lý dữ liệu cá nhân nhạy cảm, chụp và lưu ảnh lâm sàng, dùng ảnh cho đào tạo, dùng ảnh cho quảng cáo, nhận tin nhắn nhắc hẹn, thực hiện thủ thuật.
2. THE Consent_Type SHALL có nội dung được đánh phiên bản, và Consent_Record SHALL trỏ tới phiên bản cụ thể mà bệnh nhân đã đồng ý.
3. THE Consent_Record SHALL lưu bệnh nhân, loại, phiên bản, mục đích xử lý, thời điểm đồng ý, phương thức thu thập, bằng chứng, người thu thập, và thời điểm rút nếu có.
4. THE nội dung Consent_Type về dữ liệu nhạy cảm SHALL nêu rõ với bệnh nhân rằng dữ liệu được xử lý là dữ liệu cá nhân nhạy cảm.
5. WHEN một hành động cần sự đồng ý được thực hiện mà bệnh nhân chưa có Consent_Record còn hiệu lực tương ứng, THE hệ thống SHALL từ chối hành động đó và nêu loại đồng ý còn thiếu.
6. WHEN bệnh nhân rút một sự đồng ý, THE hệ thống SHALL ghi thời điểm rút, và các hành động phụ thuộc sự đồng ý đó SHALL bị từ chối từ thời điểm đó trở đi.
7. WHEN sự đồng ý nhận tin nhắn nhắc hẹn bị rút, THE hệ thống SHALL ngừng gửi nhắc hẹn cho bệnh nhân đó.

### Requirement 12: Danh mục dùng chung

**User Story:** Là bác sĩ, tôi muốn chọn chẩn đoán và thuốc từ danh mục chuẩn, để dữ liệu liên thông được và tránh sai sót nhập tay.

#### Acceptance Criteria

1. THE hệ thống SHALL có danh mục ICD-10 cho phép tìm theo mã và theo tên.
2. THE danh mục thuốc SHALL lưu tên theo tên chung quốc tế, tên biệt dược, hàm lượng hoặc nồng độ, dạng bào chế, đường dùng, đơn vị tính, mã thuốc, và phân loại thuốc kiểm soát đặc biệt.
3. THE hệ thống SHALL có danh mục chất gây dị ứng có mã, liên kết được với danh mục thuốc để đối chiếu khi kê đơn.
4. THE hệ thống SHALL cho phép nhập danh mục từ tệp do Bộ Y tế công bố, và SHALL báo cáo số bản ghi thêm mới, cập nhật, bị bỏ qua sau mỗi lần nhập.
5. WHEN người dùng nhập một phần mã hoặc tên vào ô tìm kiếm danh mục, THE hệ thống SHALL trả về danh sách gợi ý khớp theo cả mã và tên.

### Requirement 13: Bản ghi khám và dữ liệu lâm sàng linh hoạt

**User Story:** Là bác sĩ, tôi muốn ghi bản ghi khám với dữ liệu đặc thù da liễu, để hồ sơ đủ chi tiết mà hệ thống vẫn mở rộng được sang chuyên khoa khác.

#### Acceptance Criteria

1. THE Encounter_Record SHALL lưu bệnh nhân, bác sĩ, lịch hẹn liên quan nếu có, thời điểm khám, lý do khám, kế hoạch điều trị, lời dặn, hẹn tái khám, thông tin chuyển tuyến nếu có.
2. THE Encounter_Record SHALL cho phép nhiều chẩn đoán, mỗi chẩn đoán tham chiếu một mã ICD-10, và SHALL có đúng một chẩn đoán được đánh dấu là chẩn đoán chính.
3. THE Encounter_Record SHALL lưu dữ liệu lâm sàng đặc thù chuyên khoa theo một Clinical_Data_Schema có phiên bản, và SHALL lưu tham chiếu tới phiên bản schema đã dùng.
4. WHEN dữ liệu lâm sàng được ghi, THE hệ thống SHALL kiểm tra dữ liệu hợp lệ theo Clinical_Data_Schema tương ứng và SHALL từ chối dữ liệu không hợp lệ.
5. WHEN một Clinical_Data_Schema có phiên bản mới, THE các Encounter_Record đã ghi theo phiên bản cũ SHALL vẫn đọc và hiển thị được.
6. WHEN một Encounter_Record được tạo từ một lịch hẹn, THE hệ thống SHALL chuyển trạng thái lịch hẹn đó sang IN_PROGRESS.

### Requirement 14: Ký số, khóa bản ghi và đính chính

**User Story:** Là bác sĩ, tôi muốn ký số bản ghi khám và không ai sửa được sau khi ký, để hồ sơ bệnh án điện tử có giá trị pháp lý thay cho bản giấy.

`[CẦN KIỂM CHỨNG]` Quy tắc đính chính và yêu cầu kỹ thuật cụ thể phụ thuộc TT 13/2025 và Công văn 365/TTYQG. Phải xác nhận nội dung văn bản trước khi hiện thực hóa.

#### Acceptance Criteria

1. THE hệ thống SHALL cung cấp một cổng ký số trừu tượng, cho phép thay đổi nhà cung cấp ký số bằng cách thêm một hiện thực mới mà không sửa logic nghiệp vụ.
2. THE hệ thống SHALL có một hiện thực cổng ký số dùng dịch vụ ký số từ xa của VNPT.
3. THE Staff_Record SHALL lưu số giấy phép hành nghề, mã liên thông bác sĩ, phạm vi hành nghề, và tham chiếu chứng thư số.
4. THE Signature_Record SHALL lưu giá trị ký, thuật toán, thời điểm ký, người ký, chứng thư, và hash của dữ liệu đã ký.
5. WHEN một Encounter_Record ở trạng thái DRAFT được ký thành công, THE hệ thống SHALL chuyển trạng thái sang SIGNED.
6. THE hệ thống SHALL từ chối mọi thao tác sửa nội dung một Encounter_Record ở trạng thái SIGNED, cả ở tầng ứng dụng và ở tầng cơ sở dữ liệu.
7. WHEN cần sửa một Encounter_Record đã ký, THE hệ thống SHALL yêu cầu tạo một Amendment trỏ về bản gốc, kèm lý do không rỗng, và Amendment SHALL phải được ký mới có hiệu lực.
8. THE bản gốc và toàn bộ chuỗi Amendment SHALL đọc được theo đúng thứ tự thời gian.
9. WHEN chữ ký của một bản ghi được kiểm tra, THE hệ thống SHALL cho biết chữ ký còn hợp lệ và dữ liệu chưa bị thay đổi kể từ khi ký.

### Requirement 15: Đơn thuốc điện tử

**User Story:** Là bác sĩ, tôi muốn kê đơn thuốc điện tử đủ trường theo quy định và gửi lên hệ thống quốc gia, để đơn thuốc hợp pháp và nhà thuốc tra cứu được.

`[CẦN KIỂM CHỨNG]` Danh sách trường bắt buộc và quy tắc thuốc kiểm soát đặc biệt phụ thuộc TT 52/2017 và QĐ 425/2025. Phải xác nhận nội dung văn bản trước khi hiện thực hóa.

#### Acceptance Criteria

1. THE mỗi dòng thuốc trong đơn SHALL lưu tên theo tên chung quốc tế, tên biệt dược, hàm lượng hoặc nồng độ, dạng bào chế, đường dùng, liều dùng một lần, số lần dùng mỗi ngày, thời điểm dùng, số ngày dùng, số lượng, đơn vị tính, mã thuốc.
2. THE đơn thuốc SHALL lưu người kê, thời điểm kê, chẩn đoán, lời dặn, và chữ ký số của người kê.
3. THE Prescription_Code SHALL có đúng 14 ký tự, gồm mã cơ sở, phần sinh tự động, và ký hiệu loại đơn.
4. THE hệ thống SHALL đảm bảo Prescription_Code không trùng nhau kể cả khi nhiều đơn được tạo đồng thời.
5. THE đơn thuốc SHALL có thời hạn hiệu lực 5 ngày kể từ ngày kê, và trạng thái hiệu lực SHALL phản ánh đúng theo thời điểm hiện tại.
6. THE hệ thống SHALL phân loại đơn thường, đơn thuốc hướng thần, đơn thuốc gây nghiện, với ràng buộc riêng theo từng loại.
7. THE hệ thống SHALL cung cấp sổ theo dõi xuất nhập cho thuốc kiểm soát đặc biệt, cân đối được theo kỳ.
8. WHEN một thuốc được kê trùng với một Allergy_Record còn hiệu lực của bệnh nhân, THE hệ thống SHALL chặn việc kê và SHALL chỉ cho phép tiếp tục nếu bác sĩ nhập lý do.
9. WHEN cùng một hoạt chất xuất hiện nhiều lần trong một đơn, THE hệ thống SHALL cảnh báo cho bác sĩ.
10. WHEN một đơn thuốc được ký, THE hệ thống SHALL ghi một Outbox_Event để gửi đơn lên National_Rx_System.

### Requirement 16: Tích hợp bất đồng bộ với hệ thống ngoài

**User Story:** Là chủ phòng khám, tôi muốn việc gửi dữ liệu sang hệ thống quốc gia và hóa đơn điện tử không làm nghẽn công việc tại quầy, để phòng khám vẫn chạy khi hệ thống ngoài gặp sự cố.

#### Acceptance Criteria

1. THE Outbox_Event SHALL được ghi trong cùng transaction với thay đổi nghiệp vụ sinh ra nó.
2. THE hệ thống SHALL có một worker đọc các Outbox_Event ở trạng thái chờ và đẩy sang hệ thống ngoài tương ứng.
3. WHEN việc gửi thất bại, THE worker SHALL thử lại theo khoảng thời gian giãn dần, tăng số lần đã thử, lưu thông báo lỗi cuối cùng, và SHALL dừng thử lại sau số lần cấu hình.
4. WHEN việc gửi thành công, THE worker SHALL đánh dấu Outbox_Event là đã gửi và lưu thời điểm gửi.
5. THE hệ thống SHALL không gửi trùng một Outbox_Event đã ở trạng thái đã gửi.
6. THE ánh xạ giữa mã bản ghi nội bộ và mã do hệ thống ngoài trả về SHALL được lưu lại.
7. WHEN adapter gửi đơn thuốc cần xác thực, THE adapter SHALL đăng nhập bằng mã liên thông bác sĩ, mã liên thông cơ sở và mật khẩu, lưu token trong bộ đệm theo thời hạn, và tự lấy token mới khi hết hạn.

### Requirement 17: Hóa đơn điện tử và thu tiền

**User Story:** Là lễ tân, tôi muốn lập hóa đơn điện tử và ghi nhận thu tiền chính xác, để đối chiếu quỹ cuối ngày và tuân thủ quy định về hóa đơn.

#### Acceptance Criteria

1. THE hóa đơn SHALL lưu mẫu số, ký hiệu hóa đơn, số hóa đơn, mã cơ quan thuế, và thông tin người mua dạng bản chụp gồm tên, mã số thuế, địa chỉ, email.
2. THE hóa đơn SHALL đánh dấu dịch vụ khám chữa bệnh là không chịu thuế giá trị gia tăng, và SHALL lưu thông tin giảm giá và quy tắc làm tròn.
3. THE số hóa đơn SHALL tăng tuần tự và SHALL không trùng nhau kể cả khi nhiều hóa đơn được lập đồng thời.
4. THE tổng tiền của hóa đơn SHALL luôn bằng tổng các dòng của hóa đơn đó, được đảm bảo ở tầng cơ sở dữ liệu.
5. THE Payment_Record SHALL lưu hóa đơn, phương thức thanh toán, số tiền, thời điểm, người thu, và tham chiếu giao dịch nếu có.
6. THE hệ thống SHALL cho phép thu nhiều lần cho một hóa đơn, và tổng số tiền đã thu trừ số tiền đã hoàn SHALL không vượt quá giá trị hóa đơn.
7. WHEN người dùng yêu cầu báo cáo đối chiếu quỹ cho một ngày, THE hệ thống SHALL trả về tổng thu theo từng người thu và từng phương thức thanh toán.
8. WHEN một hóa đơn được phát hành, THE hệ thống SHALL ghi một Outbox_Event để phát hành hóa đơn điện tử qua nhà cung cấp VNPT, và SHALL cập nhật trạng thái, mã và thời điểm phát hành khi có kết quả.
9. WHEN một hóa đơn bị hủy hoặc điều chỉnh, THE hệ thống SHALL yêu cầu lý do, lưu vết đầy đủ, và hỗ trợ lập thông báo sai sót theo mẫu quy định.
10. WHEN bệnh nhân đã đồng ý nhận hóa đơn qua email, THE hệ thống SHALL gửi hóa đơn điện tử tới địa chỉ email đã lưu.

### Requirement 18: Ảnh lâm sàng an toàn

**User Story:** Là bác sĩ da liễu, tôi muốn lưu ảnh tổn thương da an toàn và chỉ người có quyền xem được, vì đây là dữ liệu rủi ro cao nhất trong hệ thống.

#### Acceptance Criteria

1. THE Clinical_Photo_Record SHALL được lưu trên object storage riêng tư và SHALL không bao giờ được phục vụ qua đường dẫn công khai.
2. WHEN người dùng có quyền yêu cầu xem một ảnh lâm sàng, THE hệ thống SHALL cấp một đường dẫn có chữ ký với thời hạn ngắn.
3. IF đường dẫn có chữ ký đã hết thời hạn, THEN việc truy cập ảnh SHALL bị từ chối.
4. THE Clinical_Photo_Record SHALL lưu hash toàn vẹn, kích thước, kiểu nội dung, người tải lên, loại ảnh, và cờ cho biết ảnh có chứa khuôn mặt.
5. IF bệnh nhân chưa có sự đồng ý chụp và lưu ảnh lâm sàng còn hiệu lực, THEN THE hệ thống SHALL từ chối việc tải ảnh lên.
6. THE hệ thống SHALL ghi Audit_Entry cho mỗi lần xem và mỗi lần tải một ảnh lâm sàng.
7. IF ảnh được yêu cầu dùng cho mục đích đào tạo hoặc quảng cáo mà không có sự đồng ý tương ứng còn hiệu lực, THEN THE hệ thống SHALL từ chối.
8. WHEN nội dung tệp ảnh bị thay đổi so với lúc tải lên, THE việc kiểm tra hash SHALL phát hiện được.

### Requirement 19: Liệu trình đa buổi

**User Story:** Là bác sĩ, tôi muốn quản lý liệu trình nhiều buổi với truy vết vật tư, để xử lý được khi có biến cố và thu hồi lô hàng.

#### Acceptance Criteria

1. THE mỗi buổi của liệu trình SHALL lưu người thực hiện, thời điểm thực hiện, và các vật tư hoặc thuốc đã dùng kèm số lô và hạn dùng.
2. IF bệnh nhân chưa có sự đồng ý thực hiện thủ thuật còn hiệu lực, THEN THE hệ thống SHALL từ chối ghi nhận buổi thực hiện đầu tiên của liệu trình.
3. THE hệ thống SHALL cho phép ghi nhận biến cố bất lợi gắn với một buổi thực hiện.
4. WHEN người dùng tra cứu theo một số lô vật tư, THE hệ thống SHALL trả về danh sách các bệnh nhân đã được dùng lô đó.
5. THE liệu trình SHALL liên kết được với thanh toán theo gói.

### Requirement 20: Quyền của chủ thể dữ liệu

**User Story:** Là bệnh nhân, tôi muốn thực hiện được quyền của mình với dữ liệu cá nhân, theo NĐ 13/2023.

#### Acceptance Criteria

1. THE hệ thống SHALL cho phép tạo và theo dõi DSR_Request thuộc các loại: truy cập dữ liệu, sửa dữ liệu, rút sự đồng ý, hạn chế xử lý, xuất dữ liệu.
2. WHEN một DSR_Request loại xuất dữ liệu được xử lý, THE hệ thống SHALL sinh tệp chứa dữ liệu cá nhân của bệnh nhân ở định dạng máy đọc được.
3. THE hệ thống SHALL theo dõi thời hạn phản hồi của mỗi DSR_Request và SHALL cảnh báo khi sắp quá hạn.
4. IF một yêu cầu xóa dữ liệu xung đột với nghĩa vụ lưu trữ hồ sơ bệnh án, THEN THE hệ thống SHALL từ chối phần xung đột, nêu căn cứ pháp lý, và ghi nhận việc từ chối vào DSR_Request.
5. THE mọi thao tác xử lý DSR_Request SHALL được ghi Audit_Entry.

### Requirement 21: Lưu trữ và tiêu hủy có kiểm soát

**User Story:** Là chủ phòng khám, tôi muốn hồ sơ hết thời hạn được tiêu hủy có kiểm soát và có biên bản, để vừa tuân thủ lưu trữ vừa không giữ dữ liệu quá mức cần thiết.

#### Acceptance Criteria

1. THE hệ thống SHALL định kỳ xác định các bản ghi đã hết thời hạn lưu trữ và đưa vào danh sách chờ tiêu hủy.
2. THE hệ thống SHALL không tiêu hủy bất kỳ bản ghi nào khi chưa có phê duyệt của người dùng vai trò ADMIN.
3. IF một bản ghi có Legal_Hold đang bật, THEN bản ghi đó SHALL không xuất hiện trong danh sách chờ tiêu hủy.
4. WHEN việc tiêu hủy được phê duyệt và thực hiện, THE hệ thống SHALL sinh một Disposal_Record ghi lại loại bản ghi, số lượng, thời điểm, người phê duyệt, và căn cứ.

### Requirement 22: Chất lượng dữ liệu môi trường phát triển và nợ kỹ thuật

**User Story:** Là lập trình viên, tôi muốn môi trường phát triển không chứa dữ liệu bệnh nhân thật và các lỗi kỹ thuật tồn đọng được xử lý, để giảm rủi ro rò rỉ và tăng độ tin cậy.

#### Acceptance Criteria

1. THE hệ thống SHALL có script sinh dữ liệu cho môi trường phát triển và kiểm thử ở dạng đã giả danh hóa, không chứa thông tin định danh thật.
2. THE toàn bộ logic phụ thuộc thời gian SHALL dùng thống nhất múi giờ Asia/Ho_Chi_Minh, không phụ thuộc múi giờ mặc định của máy chủ.
3. THE bộ lọc bác sĩ trên màn hình danh sách lịch hẹn SHALL lấy dữ liệu từ danh sách bác sĩ thực tế.
4. THE màn hình lịch hôm nay SHALL truyền mã bác sĩ thực tế thay vì tên đăng nhập khi truy vấn lịch hẹn.
5. THE hệ thống SHALL cho phép gộp hai bản ghi bệnh nhân trùng lặp, giữ lại vết về việc gộp.
6. THE bộ kiểm thử backend SHALL bao gồm kiểm thử tích hợp chạy trên cơ sở dữ liệu thật.
7. THE tài liệu README SHALL phản ánh đúng trạng thái các module đã hoàn thành.

### Requirement 23: Bộ tài liệu tuân thủ

**User Story:** Là chủ phòng khám, tôi muốn có bộ tài liệu tuân thủ để làm việc với cơ quan quản lý, vì phần mềm một mình không tạo ra tuân thủ.

#### Acceptance Criteria

1. THE dự án SHALL có bản mẫu hồ sơ đánh giá tác động xử lý dữ liệu cá nhân để gửi cơ quan chức năng.
2. THE dự án SHALL có bản mẫu quyết định chỉ định người hoặc bộ phận phụ trách bảo vệ dữ liệu cá nhân.
3. THE dự án SHALL có quy trình thông báo vi phạm dữ liệu cá nhân trong thời hạn quy định.
4. THE dự án SHALL có bản mẫu hợp đồng xử lý dữ liệu với nhà cung cấp hạ tầng.
5. THE dự án SHALL có nội quy bảo mật nội bộ và tài liệu đào tạo nhân viên.
6. THE dự án SHALL có bản mẫu đơn đồng ý cho từng loại Consent_Type.
7. THE toàn bộ tài liệu tại yêu cầu này SHALL được đánh dấu là bản thảo cần rà soát pháp lý trước khi sử dụng.

## Ràng buộc ngoài phạm vi phần mềm

Các mục sau cần chủ phòng khám thực hiện, phần mềm chỉ hỗ trợ:

1. Ký hợp đồng và lấy chứng thư số cho từng người hành nghề từ tổ chức cung cấp dịch vụ được cấp phép.
2. Ký hợp đồng dịch vụ hóa đơn điện tử với VNPT và lấy tài khoản môi trường kiểm thử.
3. Đăng ký tài khoản kết nối National_Rx_System và lấy mã liên thông cơ sở, mã liên thông cho từng bác sĩ.
4. Ký hợp đồng xử lý dữ liệu với nhà cung cấp cloud trong nước.
5. Nộp hồ sơ đánh giá tác động xử lý dữ liệu cá nhân cho cơ quan chức năng.
6. Chỉ định người hoặc bộ phận phụ trách bảo vệ dữ liệu cá nhân.
7. Đạt mức trưởng thành công nghệ thông tin theo bộ tiêu chí của Bộ Y tế và hoàn thành thủ tục công bố đủ điều kiện triển khai hồ sơ bệnh án điện tử trước khi bỏ hồ sơ giấy. `[CẦN KIỂM CHỨNG]` Nếu chưa đạt, giai đoạn đầu vẫn phải in và ký giấy song song.
