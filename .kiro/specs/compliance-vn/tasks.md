# Implementation Plan: Tuân thủ quy định Việt Nam (compliance-vn)

## Overview

Triển khai 31 task qua 7 giai đoạn để đưa s-clinic lên mức tuân thủ đầy đủ quy định Việt Nam, đồng thời hiện thực hóa các module còn ở dạng schema. Migration mới bắt đầu từ V4. Làm trên branch riêng, không chạm production.

**Quy ước dừng chờ văn bản:** Task 8, 15, 17, 19 phụ thuộc bốn văn bản chưa đọc được (TT 32/2023, TT 13/2025, CV 365/TTYQG, TT 52/2017, QĐ 425/2025). Khi tới các task này phải dừng và yêu cầu chủ phòng khám cung cấp nội dung, không đoán trường dữ liệu rồi làm lại. Task 5 có một điểm `[CẦN KIỂM CHỨNG]` về thời hạn lưu trữ, phải hỏi trước khi hardcode.

## Tasks

- [ ] 1. Giai đoạn 0: Nền tảng bảo mật

  - [x] 1.1 Thực thể cơ sở khám chữa bệnh (Facility)
    - Tạo migration `V4__facility.sql`: bảng `facility` với các trường định danh cơ sở và cấu hình hóa đơn điện tử VNPT
    - Tạo `Facility` entity theo khuôn mẫu `Patient` (Lombok `@Getter/@Setter`, `@UuidGenerator`, `@CreationTimestamp`, `@UpdateTimestamp`)
    - Tạo `FacilityRepository`, `FacilityService`, `FacilityController`, `FacilityRequest`/`FacilityResponse` (record), `FacilityMapper` (MapStruct)
    - Tạo `FacilitySeeder implements CommandLineRunner` theo khuôn mẫu `AdminSeeder`, idempotent theo `count() > 0`
    - `GET /api/facility` cho người dùng đã xác thực, `PUT /api/facility` chỉ ADMIN kèm audit
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_

  - [x] 1.2 Viết test cho Facility
    - Unit test: validate mã cơ sở rỗng bị từ chối, cập nhật ghi audit
    - Integration test: seed idempotent, chạy hai lần vẫn đúng một bản ghi
    - _Requirements: 1.3, 1.5, 1.7_

  - [x] 1.3 Thay HTTP Basic bằng token phiên (RỦI RO CAO)
    - Tạo migration `V5__auth_hardening.sql`: bảng `session_token`, `auth_event`, `staff_password_history`, `staff_backup_code`; bổ sung cột cho `staff` (`password_changed_at`, `must_change_password`, `failed_attempts`, `locked_until`)
    - Tạo `SessionTokenService` (sinh token 256 bit, lưu SHA-256, xác minh, thu hồi), `SessionTokenAuthFilter` (đọc `Authorization: Bearer`), `AuthEventLogger`, `PasswordPolicy`
    - Tạo `AuthController`: `POST /api/auth/login`, `POST /api/auth/logout`, `POST /api/auth/change-password`, `POST /api/auth/revoke-sessions/{staffId}` (ADMIN)
    - Sửa `SecurityConfig`: bỏ `httpBasic`, đăng ký `SessionTokenAuthFilter`
    - Sửa `AdminSeeder`: sinh mật khẩu ngẫu nhiên đủ mạnh, log đúng một lần, đặt `must_change_password = true`. Bỏ mặc định `admin/admin`
    - Token có `scope` (`ENROLL_MFA`, `MFA_PENDING`, `CHANGE_PASSWORD`, `FULL`); chỉ `FULL` truy cập endpoint nghiệp vụ
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8_

  - [x] 1.4 Sửa frontend theo cơ chế token
    - Sửa `src/infra/apiClient.ts`: bỏ `btoa` Basic, chuyển sang `Authorization: Bearer`, giữ nguyên xử lý 401 và sự kiện `auth-expired`
    - Sửa `src/app/authStore.ts`: lưu token và `expiresAt` thay cho cặp tên đăng nhập và mật khẩu, giữ nguyên nguyên tắc chỉ lưu trong bộ nhớ
    - Thêm màn hình buộc đổi mật khẩu lần đầu
    - Cập nhật các test hiện có của `apiClient` và `authStore`
    - _Requirements: 2.1, 2.3, 2.4, 3.3_

  - [x] 1.5 Viết test cho xác thực
    - Unit test: chính sách mật khẩu (độ dài, nhóm ký tự, lịch sử), bộ đếm thất bại và khóa tài khoản, đặt lại bộ đếm sau khi thành công
    - Integration test: token hết hạn trả 401, thu hồi có hiệu lực ngay, HTTP Basic không còn được chấp nhận
    - _Requirements: 2.2, 2.4, 2.6, 2.7, 3.1, 3.2, 3.4, 3.5_

  - [x] 1.6 Xác thực hai yếu tố TOTP
    - Bổ sung cột `totp_secret`, `totp_enabled`, `totp_confirmed_at` cho `staff` trong migration `V6__mfa.sql`
    - Tạo `TotpService` (RFC 6238, cửa sổ lệch ±1 bước), sinh mã QR để đăng ký
    - Tạo `POST /api/auth/mfa/enroll`, `POST /api/auth/mfa/confirm`, `POST /api/auth/mfa/verify`, `POST /api/auth/mfa/reset/{staffId}` (ADMIN)
    - Bắt buộc với ADMIN và DOCTOR, tùy chọn với RECEPTIONIST. Mã dự phòng dùng một lần lưu dạng hash
    - Frontend: thêm bước nhập mã 6 số và màn hình đăng ký TOTP
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7_
    - **Khác thiết kế ban đầu:**
      - Đặt tên `TotpGenerator` (thuần, tĩnh, không trạng thái) thay `TotpService`, tách khỏi `MfaService` (có trạng thái, ghi DB) để kiểm chứng được bằng test vector RFC 6238. Tự hiện thực HmacSHA1 + Base32 RFC 4648 thay vì thêm thư viện chưa kiểm chứng được; đã đối chiếu đủ 6 test vector của RFC
      - Mã QR **không** sinh ở backend. Backend trả `secret` + `provisioningUri` (`otpauth://`), frontend vẽ QR bằng `qrcode@1.5.4`. Lý do: ảnh QR đi qua log hoặc proxy là làm lộ khoá bí mật
      - Danh sách vai trò bắt buộc MFA đưa vào cấu hình `sclinic.auth.mfa-required-roles` (mặc định `ADMIN,DOCTOR`) thay vì hardcode, để test tắt được cổng MFA và để phòng khám tự siết thêm
      - Thứ tự cổng đăng nhập: mật khẩu → MFA → đổi mật khẩu → `FULL`. Đặt MFA **trước** đổi mật khẩu để xác minh danh tính trước khi cho phép đặt mật khẩu mới
      - `totp_secret` hiện còn lưu plaintext, sẽ mã hoá ở task 1.12 (mã hoá cột định danh)
      - Frontend: `isAuthenticated` giờ xét `scope === 'FULL'` thay vì xét cờ `passwordChangeRequired`, vì đã có bốn trạng thái chứ không còn hai. `authStore.pendingStepPath()` là nơi duy nhất quyết định màn hình kế tiếp
      - Frontend: sự kiện `auth-expired` (401) **chỉ** xoá phiên khi scope là `FULL`. Trong lúc đăng nhập dở dang, 401 là câu trả lời bình thường cho một mã nhập sai; xoá token lúc đó sẽ đá người dùng về màn hình mật khẩu chỉ vì gõ sai một chữ số, và làm mất luôn số lần thử còn lại
      - Frontend: mã dự phòng hiển thị ở bước riêng, có tuỳ chọn tải về tệp và bắt buộc tích xác nhận đã lưu mới cho đi tiếp, vì server không thể cấp lại

  - [x] 1.7 Viết test cho MFA
    - Unit test: sinh và xác minh TOTP có cửa sổ thời gian, mã dự phòng dùng một lần bị vô hiệu sau khi dùng
    - Integration test: luồng đăng nhập hai bước, mã sai tính vào bộ đếm khóa, tài khoản không tự đặt lại TOTP của mình
    - _Requirements: 4.3, 4.4, 4.5, 4.7_
    - **Hai lỗi sản phẩm phát hiện khi viết test này, đã sửa:**
      - Cơ chế **khóa tài khoản chưa từng hoạt động**. `AuthService.login` và `verifyMfa` là `@Transactional`, ghi `failed_attempts` rồi ném exception; exception làm rollback đúng transaction vừa ghi, nên bộ đếm luôn quay về 0 và tài khoản không bao giờ bị khóa, dù thử mật khẩu bao nhiêu lần. Sửa bằng bean riêng `LoginAttemptTracker` với `@Transactional(REQUIRES_NEW)` để bộ đếm commit độc lập
      - **Mọi bản ghi audit đăng nhập thất bại đều bị mất.** `AuthEventLogger.success()/failure()` gọi nội bộ `this.record()`, không đi qua proxy Spring, nên `REQUIRES_NEW` khai báo trên `record()` bị bỏ qua và bản ghi rollback cùng caller. Sửa bằng cách annotate trực tiếp trên `success()` và `failure()`
      - Lý do test cũ không phát hiện: `SessionAuthIntegrationTest` có `@Transactional`, transaction của test che mất việc rollback; `AuthServiceTest` dùng mock nên không chạm DB. Đã thêm `AccountLockoutIntegrationTest` **không** `@Transactional` để bắt đúng loại lỗi này. Quy ước từ nay: luồng nào ghi DB rồi ném exception thì integration test không được bọc `@Transactional`

  - [x] 1.8 Audit log bất biến và đầy đủ ngữ cảnh
    - Tạo migration `V7__audit_immutable.sql`: bổ sung `ip`, `user_agent`, `session_id`, `prev_hash`, `entry_hash` cho `audit_log`; tạo DB role riêng cho ứng dụng và `REVOKE UPDATE, DELETE ON audit_log`
    - Sửa `AuditService`: ghi ngữ cảnh yêu cầu, tính `entry_hash` liên kết `prev_hash`, dùng advisory lock của PostgreSQL để tuần tự hóa việc ghi
    - Sửa `detail` chỉ lưu tên trường đã đổi và tham chiếu, không lưu giá trị cũ và mới dạng đọc được. Sửa cả `AppointmentService` đang lưu old→new plaintext
    - Tạo `AuditChainVerifier` và `POST /api/audit/verify-chain` (ADMIN)
    - Bổ sung audit cho hành vi xem bản ghi cụ thể, xuất dữ liệu, in tài liệu
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7_
    - **Khác thiết kế ban đầu:**
      - **Trigger append-only thay cho DB role + REVOKE.** `REVOKE UPDATE, DELETE` không có tác dụng lên chủ sở hữu bảng, mà ứng dụng đang kết nối bằng chính user chủ sở hữu; muốn REVOKE có hiệu lực phải tách role ở tầng hạ tầng và đổi cấu hình triển khai, và `CREATE ROLE` có thể bị từ chối trên DB cloud khiến migration hỏng. Dùng trigger `BEFORE UPDATE OR DELETE` chặn thẳng: có tác dụng bất kể ứng dụng kết nối bằng role nào, và đúng với mối đe dọa thực tế (bug, migration cẩu thả, SQL injection). Superuser gỡ được trigger, nhưng REVOKE cũng không chặn được superuser — đó là việc của chuỗi hash. Tách role vẫn nên làm ở tầng hạ tầng, ghi nợ ở Task 30
      - Thêm trigger `BEFORE TRUNCATE ... FOR EACH STATEMENT`. Trigger cấp dòng KHÔNG chạy với `TRUNCATE`, nên nếu thiếu thì cả trail bị xoá sạch bằng một câu lệnh
      - **Bảng `audit_chain_head` (một dòng) thay cho advisory lock.** Hai lý do: (1) advisory lock phải chạy qua `JdbcTemplate`, tức phụ thuộc giả định `JdbcTemplate` dùng chung connection với JPA — một giả định không nên đặt vào cơ chế toàn vẹn; `SELECT ... FOR UPDATE` qua JPA thì chắc chắn cùng connection với câu INSERT. (2) Nó vá được điểm mù mà chuỗi hash không tự vá: **xoá các bản ghi ở cuối chuỗi** không làm đứt liên kết nào, vì không còn bản ghi nào phía sau để phát hiện. Lưu `head_hash` + `entry_count` ở ngoài `audit_log` nên đuôi bị cắt sẽ lộ ra
      - **Dạng chuẩn hoá của hash dùng tiền tố độ dài, không dùng ký tự phân tách.** Bản đầu tôi viết theo kiểu phân tách bằng ký tự và test tự viết đã bắt được lỗi: nội dung trường có thể mạo danh ranh giới trường (`ip="a␟b", userAgent=null` băm ra cùng kết quả với `ip="a", userAgent="b"`), tức là sửa được một bản ghi thành bản ghi khác mà chuỗi không đứt. Với tiền tố độ dài thì cách phân rã là duy nhất. Null ghi là độ dài `-1` nên không lẫn với chuỗi rỗng. Nhãn kiểu của giá trị trong `detail` cũng phải là một phần riêng, không được dán vào đầu giá trị, vì dán vào đầu thì cũng chỉ là văn bản và một chuỗi thường viết ra được y như vậy
      - `detail` giữ lại **chuyển trạng thái** của `AppointmentStatus` dạng `{from, to}`. Đây là ngoại lệ hẹp và duy nhất của quy tắc "chỉ ghi tên trường": tập giá trị đóng, không chứa gì thuộc về người bệnh, mà hướng chuyển trạng thái lại là dữ kiện hữu ích nhất khi soát lại một lịch hẹn. Giá trị của `reason` và `note` thì bỏ hẳn — đó là ghi chú lâm sàng dạng tự do
      - Audit cho **xuất dữ liệu và in tài liệu** chưa làm: hệ thống chưa có tính năng xuất hay in nào. Sẽ làm cùng lúc với các tính năng đó (Task 17, 19)
      - `AuditAction` thêm `VERIFY_CHAIN` để bản thân việc kiểm tra toàn vẹn cũng có lịch sử

  - [x] 1.9 Viết property test cho audit chain
    - **Property: Audit chain phát hiện mọi thao tác sửa hoặc xóa ở giữa chuỗi**
    - Integration test: tài khoản ứng dụng không UPDATE hoặc DELETE được `audit_log`
    - **Validates: Requirements 5.2, 5.4, 5.5**
    - Property test bao phủ: sửa **từng trường một** của một bản ghi bất kỳ, xoá giữa chuỗi, chèn thêm bản ghi giả đã tự băm đúng, đảo thứ tự hai bản ghi, cắt đuôi chuỗi, đếm sai `entry_count`. Kẻ tấn công được mô hình hoá ở mức mạnh nhất: đã qua được trigger và còn cẩn thận sửa `audit_chain_head` cho khớp với những gì để lại, nên chính chuỗi phải là thứ tố giác
    - **Lỗi sản phẩm thứ ba phát hiện ở task này, đã sửa:** `AuditService.record(action, entityType, entityId)` (3 tham số) gọi nội bộ bản 4 tham số → không qua proxy Spring → `REQUIRES_NEW` bị bỏ qua. Hệ quả: mọi audit CREATE / VIEW / DELETE (toàn bộ `PatientService`, `AppointmentService.create`) chạy trong transaction của caller, nên **bị rollback cùng nghiệp vụ khi nghiệp vụ thất bại** — đúng thứ mà Javadoc của chính class đó cam kết là không xảy ra. Đây là cùng một loại lỗi với `AuthEventLogger` ở task 1.7; đã rà cả hai chỗ. Lỗi này chỉ lộ ra khi thêm `SELECT ... FOR UPDATE`, vì `PatientService.getById` là `@Transactional(readOnly = true)` và Postgres từ chối lock trong transaction read-only
    - Integration test cố tình **không** `@Transactional`, và các test phá hoại chuỗi phải sao lưu rồi hoàn nguyên `audit_log`: trail dùng chung cho cả class và không xoá được, nên một test phá chuỗi mà để nguyên sẽ làm mọi test chạy sau nó fail vì lý do chẳng liên quan. Có một test riêng canh chính cơ chế hoàn nguyên này

  - [x] 1.9b Rà soát lại Giai đoạn 0 và sửa các lỗi đồng thời (concurrency)
    - Lượt rà soát độc lập sau khi xong 1.1–1.9 tìm ra bốn lỗi mà **không** test nào lúc đó bắt được, vì toàn bộ test đều tuần tự. Đã sửa cả bốn và bổ sung test song song:
      - **Bộ đếm khoá tài khoản bị mất cập nhật.** `LoginAttemptTracker` tăng `failed_attempts` theo kiểu đọc-sửa-ghi, không lock dòng và `Staff` không có `@Version`. 24 request sai mật khẩu song song đều đọc cùng một giá trị và ghi cùng một giá trị + 1, nên ngưỡng không bao giờ tới và **tài khoản không bao giờ bị khoá**. Sửa bằng `StaffRepository.findByIdForUpdate` (`PESSIMISTIC_WRITE`). Đo lại: trước sửa 24/24 lần thử được so mật khẩu, sau sửa 8/24. Còn một khoảng dư có chủ ý: caller kiểm tra khoá và so mật khẩu **trước** khi vào tracker, nên các request đang bay lúc khoá ập xuống vẫn được so; khoảng này bị chặn bởi số request server xử lý đồng thời, không phải bởi số lần attacker gửi. Không thể lock ở transaction của caller vì tracker chạy transaction riêng, làm vậy sẽ tự deadlock
      - **Cạn connection pool.** Một lần đăng nhập thất bại lồng ba transaction `REQUIRES_NEW` (`AuthService` → `LoginAttemptTracker` → `AuthEventLogger`), mà transaction bị treo vẫn giữ connection, nên giữ ba connection cùng lúc trên pool mặc định 10 → test song song 8 luồng ném `CannotCreateTransactionException`. Sửa hai việc: thêm `AuthEventLogger.failureInCurrentTransaction` (propagation `MANDATORY`) để tracker ghi trong transaction của chính nó thay vì mở transaction thứ ba, và khai báo `hikari.maximum-pool-size` tường minh kèm giải thích phép tính
      - **`verify-chain` báo giả mạo trong khi không có giả mạo.** Verifier đi hết trail rồi mới đọc head; ở `READ COMMITTED` mỗi câu lệnh thấy một ảnh chụp mới, nên chỉ cần một audit entry được commit giữa lúc đi là head và trail lệch nhau → báo "entries were removed from the end". Mà **mỗi lần mở một hồ sơ bệnh nhân là ghi một entry**, nên admin chạy kiểm tra lúc có tải sẽ thấy BROKEN chập chờn. Sửa: đọc head **trước**, lấy `entry_count` làm đích, mọi entry ghi sau đó nằm ngoài đích và để cho lần chạy sau. Đổi luôn sang phân trang theo khoá (`id > afterId`) vì `OFFSET n` quét lại phần bỏ qua, đi hết trail tốn thời gian bậc hai
      - **Mã dự phòng dùng được hai lần.** `consumeBackupCode` so khớp rồi mới đánh dấu đã dùng, không lock; hai request cùng lúc với cùng một mã đều so khớp thành công. Sửa bằng update có điều kiện `StaffBackupCodeRepository.spend` (`... where used_at is null`), ai lấy được row count 1 thì thắng
    - **Yếu tố thứ hai có thể bị chiếm bằng phiên đã chiếm được.** `POST /api/auth/mfa/enroll` nhận cả token `FULL`, mà `beginEnrolment` thì xoá `totp_enabled` vô điều kiện. Ai có phiên FULL bị chiếm chỉ cần gọi enroll → quét secret mới vào máy mình → confirm, là chiếm luôn yếu tố thứ hai và nhận thêm 10 mã dự phòng mới — đúng thứ mà việc để `mfa/reset` chỉ cho ADMIN nhằm ngăn. Đồng thời một người dùng mở màn thiết lập rồi bỏ dở là mất luôn yếu tố đang chạy. Sửa: `beginEnrolment` từ chối (409, `MfaAlreadyEnrolledException`) khi đã có yếu tố được xác nhận; thay thiết bị mất phải qua ADMIN reset
    - Dọn code chết: `SessionTokenService.exchange`, `AuditLogRepository.findFirstByEntryHashIsNotNullOrderByIdDesc` (kèm javadoc còn nói về advisory lock đã bỏ), `@Slf4j` trên `AuthService`, ba method không dùng trong `AuditDetail`
    - `auth_event.username` giờ bị chặn độ dài (100 ký tự). Endpoint đăng nhập không cần xác thực, nên trước đó ai cũng ghi được dòng cỡ megabyte vào bảng này; và **mật khẩu gõ nhầm vào ô tên đăng nhập** (rất hay xảy ra với autofill) sẽ nằm nguyên dạng thường trong đúng cái bảng mà một lượt soát bảo mật sẽ đọc
    - Thêm `*.tsbuildinfo` vào `.gitignore` của frontend
    - `ChangePasswordPage.vue` giờ có kiểm tra cổng lúc mount như hai màn hình interim còn lại. Trước đó vào trực tiếp thì form vẫn hiện rồi trả "mật khẩu hiện tại không đúng", tức là một cách nói rất khó hiểu cho "bạn chưa đăng nhập"

  - [ ] 1.9c NỢ KỸ THUẬT từ lượt rà soát — cần quyết định hoặc làm ở giai đoạn sau
    - **Mã TOTP dùng lại được trong cửa sổ ±1 bước (~90 giây).** RFC 6238 khuyến nghị từ chối mã đã dùng. Cần thêm cột `totp_last_step` cho `staff` và so sánh. NÊN LÀM, gộp vào migration của Task 1.12
    - **Chuỗi hash không có khoá và không có mốc neo ngoài database.** SHA-256 trần trên các trường ai đọc bảng cũng thấy, còn `audit_chain_head` thì nằm cùng database. Ai ghi được cả hai đều có thể viết lại lịch sử rồi tính lại chuỗi cho khớp. Nó phát hiện được sự bất cẩn, không phát hiện được ý đồ. Muốn thành **chứng cứ** thì cần HMAC với khoá nằm ngoài PostgreSQL, hoặc định kỳ công bố `head_hash` ra một nơi chỉ ghi thêm ở ngoài máy. Task 1.12 sẽ dựng kho khoá, nên làm cùng lúc
    - **`X-Forwarded-For` được tin vô điều kiện** và không chặn độ dài, mà giá trị đó lại được băm vào chuỗi audit như dữ kiện xác thực. Nghĩa là IP trong trail do client tự khai. CẦN USER QUYẾT ĐỊNH: chốt kiến trúc reverse proxy rồi dùng `server.forward-headers-strategy` cùng danh sách proxy tin cậy, thay vì tự đọc header
    - **`V3` bị sửa tại chỗ.** Flyway checksum bảng `flyway_schema_history`, nên nếu môi trường nào đã ghi nhận V3 thì validate sẽ fail. Lập luận là V3 chưa từng apply thành công ở đâu (DDL của Postgres có transaction nên nó luôn rollback), nhưng CẦN USER XÁC NHẬN không môi trường nào có dòng V3 trước khi merge; nếu có thì phải chuyển bản sửa index thành V8
    - **Hai index của V7 không dùng `CONCURRENTLY`**, nên trên bảng đã có dữ liệu sẽ khoá mọi ghi vào `audit_log` — tức khoá mọi nghiệp vụ có audit — suốt thời gian tạo index. Hiện `audit_log` còn rỗng nên chưa ảnh hưởng; trước khi lên production có dữ liệu thì tách thành migration riêng với `-- flyway:executeInTransaction=false`
    - **Chưa có job dọn dẹp** cho `session_token` (đã có `deleteExpiredBefore` nhưng không ai gọi), `auth_event`, `staff_password_history`. Gộp vào Task 1.10 (retention)
    - **Chưa có endpoint cấp lại mã dự phòng.** `MfaService.regenerateBackupCodes` có sẵn nhưng không expose, nên tải lại trang ở bước 2 của enrolment là mất mã vĩnh viễn, chỉ còn đường ADMIN reset
    - **Nhân viên có phiên FULL chưa có yếu tố thứ hai không tự bật MFA được từ UI** (`MfaEnrollPage` yêu cầu scope `ENROLL_MFA`). Ảnh hưởng RECEPTIONIST muốn tự nguyện bật
    - **Interim token hết hạn ở `/mfa-verify` trông giống nhập sai mã**, người dùng gõ lại mã vào một token đã chết
    - **Đổi mật khẩu không bị đếm vào lockout**: cổng mật khẩu và cổng MFA đều đi qua `LoginAttemptTracker`, riêng chỗ kiểm tra mật khẩu hiện tại thì không
    - **Thông báo lỗi xác thực không đồng nhất**: `changePassword` trả "Current password is incorrect", `MfaFailedException` phân biệt "chưa thiết lập" với "mã không đúng" — trái với tính đồng nhất mà javadoc của `AuthService` tuyên bố
    - **`IllegalArgumentException` bị map chung sang 400 kèm nguyên message**, nên một lỗi bất biến nội bộ (ví dụ `Base32.decode`) cũng thành lỗi client và lộ message. Nên có exception riêng cho lỗi client thật
    - **Hiệu năng**: mỗi lần đọc chi tiết bệnh nhân đều ghi audit, tức đều xếp hàng trên một dòng `audit_chain_head` duy nhất; `SessionTokenService.touch` ghi `last_used_at` mỗi request. Chỉ xử lý nếu đo thấy thành vấn đề
    - **`PatientService.search` và `AppointmentService.list` không ghi audit VIEW**, nên liệt kê cả danh sách bệnh nhân thì không để lại dấu vết, còn mở một bệnh nhân thì có. Là lỗ hổng có sẵn, nhưng thuộc đúng chủ đề audit
    - **Tách DB role cho ứng dụng** (xem 1.8) để `REVOKE UPDATE, DELETE ON audit_log` có hiệu lực, làm ở tầng hạ tầng

  - [ ] 1.10 Xóa mềm và metadata lưu trữ
    - DỪNG trước khi hardcode: hỏi chủ phòng khám thời hạn lưu trữ theo Luật KCB 15/2023 `[CẦN KIỂM CHỨNG]`
    - Tạo migration `V8__soft_delete_retention.sql`: bổ sung `deleted_at`, `deleted_by`, `delete_reason`, `retention_until`, `legal_hold` cho `patient` và các bảng thuộc diện lưu trữ; tạo bảng `retention_policy` cấu hình thời hạn theo loại bản ghi
    - Bỏ hard delete khỏi `PatientService.delete`, chuyển sang đánh dấu xóa kèm lý do bắt buộc
    - Dùng phương thức repository tường minh (`findByIdAndDeletedAtIsNull`) thay vì lọc ẩn
    - Tính `retention_until` theo `RetentionPolicy`, tính lại khi bệnh nhân được đánh dấu đã mất
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6_

  - [ ] 1.11 Viết test cho xóa mềm
    - Unit test: tính `retention_until` theo từng loại bản ghi và theo trường hợp tử vong
    - Integration test: bản ghi đã xóa không xuất hiện trong list và search, không còn endpoint nào xóa vĩnh viễn
    - _Requirements: 6.1, 6.3, 6.4, 6.6_

  - [ ] 1.12 Mã hóa cột định danh và sao lưu (RỦI RO CAO)
    - Sao lưu dữ liệu trước khi chạy
    - Tạo `EncryptedStringConverter implements AttributeConverter<String, String>` dùng AES-GCM, khóa từ biến môi trường `SCLINIC_DATA_KEY`
    - Tạo migration `V9__encrypt_identifiers.sql` thêm cột mới, job chuyển đổi dữ liệu một lần, và `V10__drop_plain_identifiers.sql` bỏ cột cũ. Chia hai migration để có điểm quay lại
    - Thêm cột `national_id_hash` chứa HMAC để tra cứu chính xác theo CCCD, vì cột đã mã hóa không tìm kiếm được
    - Viết script sao lưu tự động có mã hóa và script kiểm thử phục hồi cho staging
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

  - [ ] 1.13 Viết test cho mã hóa
    - Integration test: đọc ghi cột đã mã hóa qua API trả về bản rõ, truy vấn SQL thô trả về ciphertext
    - Test chuyển đổi dữ liệu không làm mất bản ghi
    - _Requirements: 7.1, 7.3, 7.4, 7.6_

  - [ ] 1.14 Phân quyền tối thiểu cần thiết và break-glass
    - Tạo migration `V11__break_glass.sql`: bảng `break_glass_grant`
    - Tạo `PatientAccessGuard` quyết định theo vai trò và `Care_Relationship` (tồn tại appointment hoặc encounter giữa bác sĩ và bệnh nhân)
    - Tạo `BreakGlassService` và `POST /api/patients/{id}/break-glass` yêu cầu lý do không rỗng, quyền có thời hạn, audit mức cảnh báo, thông báo ADMIN
    - Tách hai DTO response: bản đầy đủ cho DOCTOR và ADMIN, bản hành chính cho RECEPTIONIST (không chứa `medical_history` và dữ liệu lâm sàng)
    - Sửa `PatientService.search` lọc theo `Care_Relationship` khi vai trò là DOCTOR
    - Frontend: hộp thoại nhập lý do break-glass
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7_

  - [ ] 1.15 Viết test cho phân quyền
    - Unit test: ma trận quyền theo vai trò, quan hệ điều trị, và có hay không break-glass
    - Integration test: break-glass tạo audit đúng loại, hết hạn thì mất quyền, lý do rỗng bị từ chối
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7_

- [ ] 2. Giai đoạn 1: Mô hình dữ liệu và sự đồng ý

  - [ ] 2.1 Làm giàu thông tin bệnh nhân (DỪNG CHỜ VĂN BẢN TT 32/2023)
    - Yêu cầu chủ phòng khám cung cấp mẫu hồ sơ bệnh án trước khi thiết kế trường
    - Danh mục đơn vị hành chính có phiên bản, địa chỉ cấu trúc thay cho văn bản tự do
    - `dob` linh hoạt ba dạng: đầy đủ, chỉ năm, số tháng tuổi với trẻ dưới 72 tháng kèm tên bố hoặc mẹ
    - Bổ sung dân tộc, quốc tịch, nghề nghiệp, người liên hệ khẩn cấp, cờ đã mất và ngày mất
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6_

  - [ ] 2.2 Viết test cho thông tin bệnh nhân
    - **Property test: parse và hiển thị `dob` đúng ở cả ba dạng**
    - Unit test: validate địa chỉ theo danh mục, trẻ dưới 72 tháng buộc có tên bố hoặc mẹ
    - **Validates: Requirements 9.3, 9.4, 9.1**

  - [ ] 2.3 Dị ứng có cấu trúc
    - Tạo bảng `patient_allergy` tham chiếu `catalog_allergen`, lưu biểu hiện, mức độ, ngày ghi nhận, người ghi nhận, trạng thái
    - Partial unique index chặn hai bản ghi còn hiệu lực trỏ cùng một chất trên cùng bệnh nhân
    - Di trú `allergies` văn bản tự do sang ghi chú và đánh dấu cần rà soát, không tự động suy diễn
    - Frontend: cảnh báo nổi bật khi có dị ứng mức độ nặng
    - _Requirements: 10.1, 10.2, 10.3, 10.4_

  - [ ] 2.4 Viết test cho dị ứng
    - Unit test: không cho trùng chất còn hiệu lực trên cùng bệnh nhân
    - Migration test: dữ liệu `allergies` cũ không bị mất và được đánh dấu cần rà soát
    - _Requirements: 10.2, 10.3_

  - [ ] 2.5 Module quản lý sự đồng ý
    - Tạo bảng `consent_type`, `consent_type_version`, `consent_record`
    - Tạo 7 loại consent tối thiểu, nội dung có phiên bản. Nội dung về dữ liệu nhạy cảm phải nêu rõ tính nhạy cảm
    - Tạo `ConsentService` với `requireConsent(patientId, type)` để các module khác gọi
    - Rút đồng ý ghi thời điểm, các hành động phụ thuộc bị từ chối từ đó trở đi
    - Frontend: màn hình thu consent khi tiếp nhận bệnh nhân mới
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7_

  - [ ] 2.6 Viết test cho consent
    - Unit test: consent đã rút thì hết hiệu lực, hành động thiếu consent bị từ chối kèm tên loại còn thiếu
    - Integration test: rút consent nhắc hẹn thì ngừng gửi
    - _Requirements: 11.5, 11.6, 11.7_

  - [ ] 2.7 Danh mục dùng chung
    - Tạo bảng `catalog_icd10`, `catalog_drug`, `catalog_allergen`, hoàn thiện `service`
    - `catalog_drug` lưu tên INN, biệt dược, hàm lượng, dạng bào chế, đường dùng, đơn vị tính, mã thuốc, phân loại kiểm soát đặc biệt
    - Liên kết `catalog_drug` với `catalog_allergen` để đối chiếu khi kê đơn
    - Cơ chế nhập danh mục từ tệp, báo cáo số bản ghi thêm mới, cập nhật, bỏ qua
    - Tìm kiếm gợi ý theo cả mã và tên
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5_

  - [ ] 2.8 Viết test cho danh mục
    - Import test với tệp mẫu, kiểm tra số liệu báo cáo
    - Unit test: tìm kiếm khớp theo mã và theo tên
    - _Requirements: 12.4, 12.5_

- [ ] 3. Giai đoạn 2: Hồ sơ bệnh án điện tử

  - [ ] 3.1 Bản ghi khám cơ bản
    - Tạo `Encounter` entity, repository, service, controller ở trạng thái DRAFT
    - Tạo bảng `encounter_diagnosis` cho nhiều chẩn đoán, partial unique index đảm bảo đúng một chẩn đoán chính
    - Bổ sung sinh hiệu, lời dặn, hẹn tái khám, chuyển tuyến
    - Liên kết appointment và tự chuyển appointment sang IN_PROGRESS
    - _Requirements: 13.1, 13.2, 13.6_

  - [ ] 3.2 JSON Schema cho dữ liệu lâm sàng
    - Tạo bảng `clinical_data_schema` lưu schema theo chuyên khoa và phiên bản
    - Thêm dependency validate JSON Schema, ghim phiên bản cụ thể
    - Mỗi encounter lưu tham chiếu phiên bản schema đã dùng, validate khi ghi
    - Schema đầu tiên cho da liễu: mô tả tổn thương, vị trí, diện tích, phân độ
    - _Requirements: 13.3, 13.4, 13.5_

  - [ ] 3.3 Viết test cho encounter và clinical_data
    - **Property test: dữ liệu sai schema luôn bị từ chối, dữ liệu đúng luôn được chấp nhận**
    - Unit test: đúng một chẩn đoán chính, tương thích ngược khi schema lên phiên bản
    - Integration test: tạo encounter từ appointment chuyển trạng thái đúng
    - **Validates: Requirements 13.2, 13.4, 13.5, 13.6**

  - [ ] 3.4 Port ký số và adapter VNPT SmartCA
    - Tạo interface `DigitalSignatureProvider` với `sign`, `verify`, `providerCode`
    - Tạo `VnptSmartCaAdapter` gọi API ký từ xa, hỗ trợ chờ người dùng xác nhận trên điện thoại
    - Chọn adapter theo cấu hình `sclinic.signing.provider`
    - Tạo bảng `signature` lưu giá trị ký, thuật toán, thời điểm, người ký, chứng thư, hash dữ liệu đã ký
    - Bổ sung `staff`: số giấy phép hành nghề, mã liên thông bác sĩ, phạm vi hành nghề, tham chiếu chứng thư số
    - Map đủ các cột `Staff` hiện đang thiếu trong entity (`specialty_id`, `phone`, `email`, `created_at`)
    - _Requirements: 14.1, 14.2, 14.3, 14.4_

  - [ ] 3.5 Ký, khóa và đính chính (DỪNG CHỜ VĂN BẢN TT 13/2025 + CV 365)
    - Yêu cầu chủ phòng khám cung cấp quy tắc đính chính trước khi thiết kế
    - Ký chuyển DRAFT sang SIGNED
    - Chặn UPDATE ở tầng service và bằng DB trigger khi trạng thái khác DRAFT
    - Đính chính tạo bản ghi mới trỏ về bản gốc, bắt buộc lý do, phải ký mới có hiệu lực
    - Bản gốc và chuỗi đính chính đọc được theo đúng thứ tự thời gian
    - Kiểm tra chữ ký cho biết còn hợp lệ và dữ liệu chưa bị thay đổi
    - _Requirements: 14.5, 14.6, 14.7, 14.8, 14.9_

  - [ ] 3.6 Viết property test cho tính bất biến
    - **Property: không tồn tại đường nào sửa được bản ghi đã ký**
    - Unit test: chuỗi đính chính nhiều lớp giữ đúng thứ tự
    - **Validates: Requirements 14.6, 14.7, 14.8**

  - [ ] 3.7 Frontend hồ sơ bệnh án
    - Trang khám với form sinh động từ JSON Schema
    - Cảnh báo dị ứng, chọn ICD-10 từ danh mục
    - Nút ký số, timeline lịch sử đính chính
    - In PDF có thông tin chữ ký số
    - _Requirements: 13.1, 13.3, 14.5, 14.8, 10.4_

  - [ ] 3.8 Viết test frontend cho hồ sơ bệnh án
    - Component test: form động render đúng theo schema
    - E2E test: luồng từ lịch hẹn tới bệnh án đã ký
    - _Requirements: 13.3, 14.5_

- [ ] 4. Giai đoạn 3: Đơn thuốc

  - [ ] 4.1 Đơn thuốc đủ trường (DỪNG CHỜ VĂN BẢN TT 52/2017 + QĐ 425/2025)
    - Yêu cầu chủ phòng khám cung cấp danh sách trường bắt buộc trước khi thiết kế
    - Bổ sung `prescription_item`: tên INN, biệt dược, hàm lượng, dạng bào chế, đường dùng, liều một lần, số lần mỗi ngày, thời điểm dùng, số ngày dùng, đơn vị tính, mã thuốc
    - Bổ sung `prescription`: người kê, mã đơn 14 ký tự, hiệu lực 5 ngày, chữ ký số
    - Sinh mã đơn bằng sequence trong DB, ghép mã cơ sở và ký hiệu loại đơn
    - Phân biệt rõ `prescription_code` nội bộ 14 ký tự với `national_rx_code` đã có trong V2
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5_

  - [ ] 4.2 Viết property test cho mã đơn
    - **Property: mã đơn luôn đúng 14 ký tự và không trùng khi sinh đồng thời**
    - Unit test: đơn hết hiệu lực sau 5 ngày
    - **Validates: Requirements 15.3, 15.4, 15.5**

  - [ ] 4.3 Kiểm tra dị ứng khi kê đơn
    - Tạo `AllergyChecker` đối chiếu `catalog_drug` với `patient_allergy` qua `catalog_allergen`
    - Chặn cứng khi trùng, chỉ cho tiếp tục nếu bác sĩ nhập lý do
    - Cảnh báo trùng hoạt chất trong cùng đơn
    - _Requirements: 15.8, 15.9_

  - [ ] 4.4 Viết test cho kiểm tra dị ứng
    - **Property test: không bỏ sót cảnh báo với mọi tổ hợp thuốc và dị ứng**
    - Unit test: trùng trực tiếp và trùng theo nhóm hoạt chất
    - **Validates: Requirements 15.8, 15.9**

  - [ ] 4.5 Thuốc kiểm soát đặc biệt (DỪNG CHỜ VĂN BẢN)
    - Yêu cầu chủ phòng khám cung cấp quy định chi tiết trước khi thiết kế
    - Phân loại đơn thường, đơn hướng thần (N), đơn gây nghiện (H) với ràng buộc riêng
    - Tạo `controlled_drug_ledger` sổ theo dõi xuất nhập, cân đối theo kỳ
    - _Requirements: 15.6, 15.7_

  - [ ] 4.6 Viết test cho thuốc kiểm soát đặc biệt
    - Unit test: ràng buộc theo từng loại đơn
    - Test sổ theo dõi cân đối xuất nhập theo kỳ
    - _Requirements: 15.6, 15.7_

  - [ ] 4.7 OutboxWorker và adapter đơn thuốc quốc gia
    - Tạo `OutboxWorker` đọc `integration_outbox` PENDING, retry giãn dần, giới hạn số lần thử, đánh dấu SENT hoặc FAILED
    - Tạo `NationalRxAdapter`: `POST /api/auth/dang-nhap-bac-si` lấy token có cache theo hạn, `POST /api/v1/gui-don-thuoc` gửi đơn kèm chữ ký số
    - Cập nhật `national_rx_code` và `sync_status`, lưu mapping vào `external_reference`
    - Không gửi trùng bản ghi đã ở trạng thái đã gửi
    - Xin tài khoản môi trường kiểm thử của Trung tâm Thông tin y tế Quốc gia sớm
    - _Requirements: 15.10, 16.1, 16.2, 16.3, 16.4, 16.5, 16.6, 16.7_

  - [ ] 4.8 Viết test cho outbox và adapter
    - Unit test: worker xử lý lỗi mạng và lỗi nghiệp vụ khác nhau, retry giãn dần, dừng sau số lần cấu hình
    - Unit test: token được lấy mới khi hết hạn, không gửi trùng
    - _Requirements: 16.3, 16.4, 16.5, 16.7_

  - [ ] 4.9 Frontend kê đơn
    - Chọn thuốc từ danh mục, nhập liều theo trường có cấu trúc
    - Cảnh báo dị ứng, ký số, xem trạng thái đồng bộ quốc gia
    - In đơn có QR chứa mã đơn
    - _Requirements: 15.1, 15.8, 15.9, 15.10_

- [ ] 5. Giai đoạn 4: Hóa đơn và thu tiền (chạy song song được với Giai đoạn 3)

  - [ ] 5.1 Hóa đơn đủ trường theo NĐ 123/2020
    - Bổ sung `invoice`: mẫu số, ký hiệu, số hóa đơn, mã cơ quan thuế, người mua dạng bản chụp, cờ không chịu thuế GTGT, giảm giá, làm tròn
    - Sinh số hóa đơn bằng sequence, tuần tự, không trùng khi đồng thời
    - Trigger kiểm tra tổng tiền bằng tổng các dòng sau mỗi thay đổi
    - _Requirements: 17.1, 17.2, 17.3, 17.4_

  - [ ] 5.2 Viết property test cho hóa đơn
    - **Property: tổng tiền luôn khớp tổng dòng sau mọi chuỗi thao tác**
    - Unit test: số hóa đơn tuần tự không trùng khi lập đồng thời
    - **Validates: Requirements 17.3, 17.4**

  - [ ] 5.3 Thu tiền và đối chiếu quỹ
    - Tạo bảng `payment` và `refund`
    - Cho phép thu nhiều lần cho một hóa đơn
    - Báo cáo đối chiếu quỹ cuối ngày theo người thu và phương thức thanh toán
    - _Requirements: 17.5, 17.6, 17.7_

  - [ ] 5.4 Viết property test cho thu tiền
    - **Property: tổng thu trừ tổng hoàn không bao giờ vượt giá trị hóa đơn**
    - Unit test: báo cáo đối chiếu quỹ cuối ngày
    - **Validates: Requirements 17.6, 17.7**

  - [ ] 5.5 Adapter hóa đơn điện tử VNPT
    - Tạo `VnptEInvoiceAdapter` phát hành qua outbox, cập nhật `einvoice_status`, `einvoice_code`, `einvoice_issued_at` (cột đã có trong V2)
    - Hủy và điều chỉnh có vết đầy đủ kèm lý do, hỗ trợ lập thông báo sai sót theo mẫu quy định
    - Gửi hóa đơn qua email khi bệnh nhân đã đồng ý
    - Xin tài khoản môi trường kiểm thử VNPT sớm
    - _Requirements: 17.8, 17.9, 17.10_

  - [ ] 5.6 Viết test cho adapter hóa đơn
    - Unit test với mock VNPT: phát hành, hủy, điều chỉnh
    - Test outbox không gửi trùng
    - _Requirements: 17.8, 17.9, 16.5_

  - [ ] 5.7 Frontend thu ngân
    - Tạo hóa đơn từ ca khám và liệu trình, thu tiền, phát hành hóa đơn điện tử
    - In, xem trạng thái, hủy có lý do
    - Trang báo cáo đối chiếu quỹ cuối ngày
    - _Requirements: 17.1, 17.5, 17.7, 17.8, 17.9_

  - [ ] 5.8 Viết E2E test cho luồng đầy đủ
    - E2E test: từ đặt hẹn, khám, kê đơn, tới thu tiền và xuất hóa đơn điện tử
    - _Requirements: 17.5, 17.8_

- [ ] 6. Giai đoạn 5: Ảnh lâm sàng và liệu trình

  - [ ] 6.1 Ảnh lâm sàng an toàn
    - Bổ sung `clinical_photo`: hash toàn vẹn, kích thước, kiểu nội dung, người tải lên, loại ảnh, cờ chứa khuôn mặt
    - Lưu trên object storage riêng tư, chỉ truy cập qua signed URL hạn ngắn
    - Chặn tải lên khi chưa có consent chụp ảnh còn hiệu lực
    - Audit từng lần xem và từng lần tải
    - Consent riêng cho mục đích đào tạo và quảng cáo, thiếu thì chặn
    - _Requirements: 18.1, 18.2, 18.3, 18.4, 18.5, 18.6, 18.7, 18.8_

  - [ ] 6.2 Viết test cho ảnh lâm sàng
    - **Property test: hash phát hiện mọi thay đổi nội dung tệp**
    - Integration test: signed URL hết hạn thì không truy cập được
    - Unit test: thiếu consent thì chặn tải lên và chặn dùng cho quảng cáo
    - **Validates: Requirements 18.2, 18.3, 18.5, 18.7, 18.8**

  - [ ] 6.3 Liệu trình đa buổi
    - Bổ sung người thực hiện từng buổi, vật tư hoặc thuốc kèm số lô và hạn dùng
    - Consent thủ thuật bắt buộc trước buổi đầu
    - Ghi nhận biến cố bất lợi gắn với buổi thực hiện
    - Tra cứu theo số lô trả về danh sách bệnh nhân đã dùng
    - Liên kết thanh toán theo gói
    - _Requirements: 19.1, 19.2, 19.3, 19.4, 19.5_

  - [ ] 6.4 Viết test cho liệu trình
    - Unit test: chặn buổi đầu khi thiếu consent thủ thuật
    - Integration test: truy vết theo số lô trả về đúng danh sách bệnh nhân
    - _Requirements: 19.2, 19.4_

- [ ] 7. Giai đoạn 6: Quyền chủ thể dữ liệu và vận hành

  - [ ] 7.1 Thực thi quyền chủ thể dữ liệu
    - Tạo bảng `dsr_request` và các endpoint xử lý: truy cập, sửa, rút đồng ý, hạn chế xử lý, xuất dữ liệu
    - Xuất dữ liệu ở định dạng máy đọc được
    - Theo dõi thời hạn phản hồi và cảnh báo khi sắp quá hạn
    - Xung đột giữa quyền xóa và nghĩa vụ lưu trữ thì từ chối phần xung đột kèm căn cứ pháp lý
    - Mọi thao tác ghi audit
    - _Requirements: 20.1, 20.2, 20.3, 20.4, 20.5_

  - [ ] 7.2 Viết test cho quyền chủ thể dữ liệu
    - Unit test: xung đột xóa và lưu trữ trả về từ chối kèm căn cứ
    - Integration test: xuất dữ liệu đầy đủ và đúng định dạng
    - _Requirements: 20.2, 20.4_

  - [ ] 7.3 Bộ máy lưu trữ và tiêu hủy
    - Job định kỳ xác định bản ghi hết thời hạn, đưa vào danh sách chờ
    - Bắt buộc phê duyệt của ADMIN trước khi tiêu hủy
    - Tôn trọng `legal_hold`
    - Sinh `disposal_record` ghi loại bản ghi, số lượng, thời điểm, người phê duyệt, căn cứ
    - _Requirements: 21.1, 21.2, 21.3, 21.4_

  - [ ] 7.4 Viết test cho tiêu hủy
    - Unit test: chọn đúng bản ghi hết hạn theo từng loại
    - Unit test: `legal_hold` luôn chặn tiêu hủy
    - _Requirements: 21.1, 21.3_

  - [ ] 7.5 Giả danh hóa và sửa nợ kỹ thuật
    - Script sinh dữ liệu dev và test đã giả danh hóa
    - Sửa `ClockConfig` dùng thống nhất Asia/Ho_Chi_Minh thay cho `systemDefaultZone`
    - Nối `useDoctors()` vào `doctorOptions` trong `AppointmentListPage.vue` (hiện hardcode ref rỗng)
    - Sửa `TodayDashboard.vue` truyền mã bác sĩ thật thay vì tên đăng nhập
    - Xóa `src/plugins/vuetify.ts` (dead code, Vuetify cấu hình inline trong `main.ts`)
    - Quyết định bỏ hoặc dùng vee-validate (đang cài nhưng không dùng)
    - Định nghĩa `lombok.version` trong `pom.xml`
    - Sửa `build.ps1` hardcode JDK 21 và đường dẫn maven
    - Cập nhật README (checklist status đang lỗi thời)
    - Thêm gộp bệnh nhân trùng lặp, giữ vết về việc gộp
    - Thêm Testcontainers và integration test cho backend
    - _Requirements: 22.1, 22.2, 22.3, 22.4, 22.5, 22.6, 22.7_

  - [ ] 7.6 Viết test cho giả danh hóa và timezone
    - **Property test: giả danh hóa không để lọt thông tin định danh**
    - Integration test: logic thời gian nhất quán bất kể múi giờ máy chủ
    - **Validates: Requirements 22.1, 22.2**

  - [ ] 7.7 Bộ tài liệu tuân thủ
    - Soạn bản thảo trong `docs/compliance/`: hồ sơ đánh giá tác động xử lý dữ liệu cá nhân, quyết định chỉ định người phụ trách bảo vệ dữ liệu, quy trình thông báo vi phạm, hợp đồng xử lý dữ liệu với nhà cung cấp cloud, nội quy bảo mật nội bộ, tài liệu đào tạo nhân viên, mẫu đơn đồng ý cho từng loại consent
    - Đánh dấu rõ toàn bộ là bản thảo cần rà soát pháp lý trước khi sử dụng
    - _Requirements: 23.1, 23.2, 23.3, 23.4, 23.5, 23.6, 23.7_
