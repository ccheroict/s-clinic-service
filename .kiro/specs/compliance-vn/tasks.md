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

    - ### ĐIỂM DỪNG 18/10/2026 — đã phân loại 15 mục, chưa viết dòng code nào
      Đã đọc xong code liên quan và chốt cách làm cho từng mục. Hôm sau bắt đầu
      trực tiếp từ Lô A, không cần đọc lại.

      **✅ LÔ A ĐÃ XONG 19/10/2026.** Backend 558 → 594 test xanh. Chi tiết và
      những chỗ làm khác dự tính ghi ở cuối mục 1.9c này.

      **Lô A — ba lỗ hổng thật, làm trước, gộp một migration `V15`:**
      1. *Dùng lại mã TOTP.* Thêm cột `totp_last_step bigint` cho `staff`.
         `TotpGenerator.verify` hiện trả `boolean` nên không biết bước nào khớp —
         cần thêm biến thể trả về bước đã khớp (`long`, -1 nếu không khớp),
         GIỮ NGUYÊN vòng lặp quét hết cửa sổ để thời gian chạy không tiết lộ
         bước nào khớp. `MfaService.verifyChallenge` + `confirmEnrolment` từ chối
         khi `step <= totp_last_step`. Phải cập nhật cột bằng UPDATE có điều kiện
         (`where totp_last_step < :step`) chứ không set rồi save: hai request
         cùng mã, cùng lúc, nếu set vô điều kiện thì cả hai đều lọt — đúng kiểu
         lỗi mà `backupCodeRepository.spend` đã xử lý đúng
      2. *Chuỗi audit không có khoá.* Chuyển `AuditHash` từ SHA-256 trần sang
         HMAC-SHA256, khoá `sclinic.audit.chain-key` (base64, ngoài PostgreSQL),
         KHÔNG mặc định — thiếu khoá là không khởi động, như `sclinic.crypto.key`.
         `AuditHash` đang là utility tĩnh nên phải thành bean (`AuditService` và
         `AuditChainVerifier` đều dùng); `AuditHashTest` sẽ phải sửa theo.
         Tái dùng `CryptoKeys.decode` (hiện package-private, cần mở public) để
         không nhân bản logic "không có mặc định, từ chối khởi động".
         GHI RÕ GIỚI HẠN: việc này chặn người chỉ có quyền ghi database, KHÔNG
         chặn người có cả database và biến môi trường của app. Mốc neo ngoài máy
         (công bố `head_hash` ra nơi chỉ ghi thêm) vẫn còn mở, thuộc hạ tầng
      3. *`X-Forwarded-For` tin vô điều kiện.* KHÔNG cần chờ user chốt kiến trúc
         nữa: đổi mặc định thành KHÔNG tin header (dùng `getRemoteAddr`), thêm
         `sclinic.security.trusted-proxy-count` (mặc định 0). Khi >0 thì lấy
         entry đếm từ PHẢI sang (các entry bên phải do proxy của chính mình ghi
         nên tin được; bên trái là client tự khai). Thêm chặn độ dài. Mặc định an
         toàn, cấu hình là cách opt-in — nên không còn chặn bởi câu hỏi nào

      **✅ LÔ B ĐÃ XONG 19/10/2026** (phần bệnh nhân). Backend 594 → 600 test
      xanh. Phần lịch hẹn: code đã có, test chưa viết được vì endpoint vỡ sẵn —
      xem mục mới "GET /api/appointments vỡ khi thiếu bộ lọc" ở dưới.

      **Lô B — lỗ hổng audit có sẵn:** `PatientService.search` và
      `AppointmentService.list` không ghi `VIEW`, nên liệt kê cả danh sách bệnh
      nhân thì không để lại dấu, mở một bệnh nhân thì có. Cân nhắc: ghi mỗi lần
      search sẽ đẩy lưu lượng lên một dòng `audit_chain_head` duy nhất (xem mục
      hiệu năng), nên ghi kèm `detail` là số kết quả + có hay không từ khoá,
      KHÔNG ghi bản thân từ khoá (từ khoá là tên bệnh nhân)

      **🔶 LÔ C ĐANG LÀM — 20/10/2026.** Backend XONG 4/6 mục, 608 → 618 test
      xanh. Frontend mới làm 1 mục. Còn lại ghi ở cuối mục 1.9c.

      **Lô C — nhất quán xác thực:** cấp lại mã dự phòng (service đã có, chưa
      expose); cho phiên FULL chưa có yếu tố thứ hai tự bật MFA (giữ nguyên
      quyết định 409 khi ĐÃ có yếu tố đã xác nhận); phân biệt interim token hết
      hạn với mã sai ở `/mfa-verify`; đổi mật khẩu phải đếm vào lockout; thống
      nhất thông báo lỗi xác thực; tách exception cho lỗi client thật để
      `IllegalArgumentException` nội bộ không thành 400 kèm nguyên message

      **Lô D — dọn dẹp:** job xoá `session_token` hết hạn (`deleteExpiredBefore`
      đã có, chưa ai gọi), `auth_event`, `staff_password_history`

      **Đã đóng bằng phân tích, không cần code:**
      - *Index V7 không `CONCURRENTLY`*: chỉ gây khoá nếu V7 chạy trên bảng đã có
        dữ liệu. V7 đã chạy ở mọi môi trường hiện có khi `audit_log` còn rỗng, và
        cài mới thì cũng chạy trên bảng rỗng. Dựng lại bằng `CONCURRENTLY` lúc
        này không đổi gì. Mục này moot
      - *Hiệu năng (audit mỗi lượt đọc xếp hàng trên một dòng; `touch` ghi mỗi
        request)*: giữ nguyên quyết định ban đầu — chỉ xử lý khi đo thấy thành
        vấn đề. Lô B làm tăng tải lên dòng này nên ĐO khi làm Lô B

      **Vẫn chặn bởi user:**
      - *`V3` sửa tại chỗ*: cần user xác nhận không môi trường nào có dòng V3
        trong `flyway_schema_history`. Nếu có thì phải chuyển bản sửa index sang
        migration mới
      - *Tách DB role cho ứng dụng* (để `REVOKE UPDATE, DELETE ON audit_log` có
        hiệu lực): tầng hạ tầng, cần quyết định triển khai

    - ### KẾT QUẢ LÔ A — 19/10/2026
      Backend 558 → 594 test xanh (+36). `V15__totp_replay.sql`.

      **1. Chống dùng lại mã TOTP.** `TotpGenerator.matchingStep` trả về BƯỚC đã
      khớp thay vì boolean (`verify` thành wrapper mỏng, giữ lại cho 6 test vector
      RFC 6238 đọc dễ hơn). Vẫn quét hết cửa sổ sau khi khớp để thời gian chạy
      không tiết lộ bước nào khớp. Cột `staff.totp_last_step` + `advanceTotpStep`
      cập nhật CÓ ĐIỀU KIỆN (`where totp_last_step is null or totp_last_step <
      :step`) — chính điều kiện đó mới thật sự ép dùng một lần, còn phép so sánh
      trong service chỉ tiết kiệm một query ở trường hợp thường. Hai request cùng
      mã cùng lúc đều qua được phép so sánh, đúng một request lấy được dòng.
      - Dùng MỘT CỘT chứ không bảng mã đã dùng: bước chỉ tiến về trước nên một số
        trả lời đủ câu hỏi duy nhất được hỏi; mà một bảng các lần xác thực còn
        phải dọn, không dọn thì nó thành bản ghi ngày càng dài về chính xác giờ
        từng nhân viên có mặt ở phòng khám
      - `confirmEnrolment` DÙNG CHUNG cơ chế này, nên mã vừa dùng để xác nhận
        đăng ký không đăng nhập lại được. Không chung thì 90 giây đầu sau khi
        đăng ký vẫn có một mã dùng lại được
      - `beginEnrolment` và `reset` XOÁ `totp_last_step`: bí mật mới sinh ra mã
        không liên quan, giữ mốc cũ chỉ để từ chối mã hợp lệ đầu tiên của yếu tố
        mới khi việc đăng ký rơi vào bước sớm hơn lần đăng nhập cuối
      - Mã dùng lại báo y hệt mã sai. "Mã này đã dùng rồi" là nói cho người đang
        giữ mã lấy được rằng họ có đúng mã và chỉ đến muộn
      - **GIÁ PHẢI TRẢ, đã chấp nhận:** đăng nhập hai lần trong cùng 30 giây thì
        phải chờ mã kế tiếp. Bình thường với TOTP, và rẻ so với một mã còn dùng
        được 90 giây sau khi bất kỳ ai nhìn thấy nó

      **2. Chuỗi audit chuyển sang HMAC-SHA256**, khoá `sclinic.audit.chain-key`
      nằm ngoài PostgreSQL, không mặc định, thiếu là không khởi động.
      `AuditHash` từ utility tĩnh thành bean (`@Autowired` TƯỜNG MINH trên
      constructor nhận properties, vì constructor `byte[]` dành cho test cũng là
      ứng viên và hai constructor không đánh dấu thì Spring đi tìm constructor
      rỗng). Constructor SAO CHÉP mảng khoá: người gọi tự xoá buffer khoá của
      mình — việc đúng nên làm — sẽ âm thầm đổi mọi hash ghi sau đó và chuỗi vỡ
      mà không dòng nào bị sửa.
      - Đổi được là vì CHƯA deploy production. Sau đó thì chỉ còn hai đường, cả
        hai đều tệ: ghi lại toàn bộ hash đã lưu (đúng cái năng lực mà chuỗi tồn
        tại để phủ nhận), hoặc giữ cả hai thuật toán kèm cờ ghi entry nào dùng
        cái nào (đưa cho kẻ tấn công quyền chọn)
      - `CryptoKeys.decode` mở `public` + thêm tham số `purpose`, tái dùng thay
        vì nhân bản logic "không có mặc định, từ chối khởi động". Hai bản sao sẽ
        lệch, và bản lệch sẽ là bản âm thầm mọc fallback
      - **GIỚI HẠN còn lại, ghi rõ trong javadoc của `AuditChainVerifier`:** chặn
        được người CHỈ có quyền ghi database (backup bị lấy, SQL injection, DBA,
        migration cẩu thả). KHÔNG chặn được người có cả database VÀ biến môi
        trường của process. Để đóng nốt cần công bố `head_hash` định kỳ ra nơi
        chỉ ghi thêm ngoài máy — `ChainVerification.headHash()` chính là thứ job
        đó sẽ công bố

      **3. `X-Forwarded-For` không còn được tin vô điều kiện.** Mặc định
      `sclinic.network.trusted-proxy-count = 0` → bỏ qua header, dùng
      `getRemoteAddr`. Với N > 0 thì lấy entry thứ N TỪ PHẢI sang. Thêm chặn độ
      dài 64 ký tự cho IP (header do kẻ tấn công điều khiển, giá trị lại được ghi
      vào mọi dòng audit mà request đó chạm tới — không chặn thì một request ghi
      một megabyte mỗi dòng).
      - `RequestContext.from(HttpServletRequest)` tĩnh bị **XOÁ**, thay bằng bean
        `RequestContextResolver`. Giữ lại một đường tiện mà không an toàn bên
        cạnh đường an toàn thì bảo đảm đường không an toàn sẽ được dùng
      - KHÔNG còn chặn bởi câu hỏi kiến trúc nào: mặc định an toàn, cấu hình là
        cách opt-in. Khi chốt xong kiến trúc proxy thì chỉ cần đặt một con số

      **HAI TEST CHỐT HÀNH VI CŨ, đã sửa:**
      - `AuditTrailIntegrationTest` khẳng định IP ghi nhận là `203.0.113.7` lấy
        từ header. Giờ khẳng định là địa chỉ peer, kèm giải thích; trường hợp có
        proxy do `RequestContextResolverTest` phủ
      - Test phát hiện sửa dòng audit đổi `ip` thành `'127.0.0.1'` — mà đó giờ
        chính là giá trị mọi dòng đang có, nên "sửa" thành không sửa gì và test
        PASS VÌ LÝ DO SAI. Đổi sang `'198.51.100.254'`

      **LỖI TÔI TỰ GÂY RA RỒI SỬA:** phần thêm `advanceTotpStep` rơi vào giữa
      khai báo `findByIdForUpdate`, khiến `@Lock(PESSIMISTIC_WRITE)` chuyển sang
      method mới (vô nghĩa với một câu update) và method cũ MẤT LOCK — tức bộ đếm
      lần đăng nhập sai quay lại thành read-modify-write mà
      `AccountLockoutIntegrationTest` dựng ra để chặn. Đã viết lại cả tệp.

    - ### KẾT QUẢ LÔ B — 19/10/2026
      Backend 594 → 600 test xanh. 6 test mới trong
      `BreakGlassIntegrationTest$BulkReadsAreRecorded` (DB `access_it`).

      `PatientService.search` và `AppointmentService.list` giờ ghi `VIEW` với
      `entity_id = null` (một lượt liệt kê không phải một thực thể; gán một bệnh
      nhân vào đó là nói sai về lượt đọc).

      - **Ghi cho lễ tân và admin, KHÔNG ghi cho bác sĩ.** Danh sách của bác sĩ đã
        bị lọc về đúng bệnh nhân họ điều trị nên không mang nhiều hơn việc mở
        từng người — mà mở bệnh nhân của chính mình thì `getById` cũng cố tình
        không ghi, vì một dòng cho mỗi lượt đọc lâm sàng thường ngày sẽ chôn mất
        những dòng đáng xem. Lễ tân và admin thấy MỌI bệnh nhân của phòng khám,
        đó mới là quyền truy cập cần soát lại được về sau
      - **KHÔNG ghi từ khoá tìm kiếm.** Người ta tìm theo tên bệnh nhân, nên từ
        khoá thường chính là tên một người; ghi nó là sao dữ liệu định danh vào
        một bảng có thời hạn lưu và mức bảo vệ khác với bản ghi nó đến từ — cùng
        lập luận đã giữ lý do xoá ra khỏi `audit_log.detail`. Chỉ ghi CÓ lọc hay
        KHÔNG: phân trang qua cả danh sách đọc khác với tra một người
      - `detail` ghi `scope=list`, `results=<số dòng trang này>`, `filtered`.
        Với lịch hẹn thêm `byDate`, `byDoctor` — cũng là cờ, không phải giá trị,
        vì ngày và id bác sĩ nói ra những bệnh nhân nào đã bị liệt kê
      - Có test chốt dòng audit của lượt liệt kê VẪN NẰM TRONG chuỗi hash: một
        dòng audit ngoài chuỗi là dòng xoá được không để lại vết, mà như vậy thì
        ghi lại lượt đọc thành vô nghĩa
      - **Nợ lại:** chưa ĐO tải thêm lên dòng `audit_chain_head` duy nhất như ghi
        chú ở mục hiệu năng đã hẹn. Danh sách bệnh nhân là trang hay mở nhất, nên
        đây là việc cần làm trước khi lên production

    - [x] **ĐÃ SỬA 20/10/2026 — `GET /api/appointments` vỡ khi thiếu bộ lọc**
      Thay JPQL bằng `Specification` (`AppointmentSpecifications.matching`): bộ
      lọc nào không được truyền thì KHÔNG góp vế nào. Không còn phép so sánh với
      null nên không còn gì để driver suy kiểu, và câu lệnh chỉ chứa đúng điều
      kiện được hỏi — nhờ đó planner dùng được index trên `scheduled_at` và
      `doctor_id` thay vì phải đi qua chuỗi `or` không bao giờ thoả.
      - **Bỏ hai test cũ** xác minh "doctorId có được truyền vào repository" và
        "date có đổi thành khoảng Instant". Chính kiểu test đó đã che lỗi: mock
        không chạy SQL, nên một câu truy vấn không thể chạy trông y hệt câu chạy
        được. Thay bằng `AppointmentListIntegrationTest` (12 test, DB
        `appointment_list_it`) kiểm chứng bằng KẾT QUẢ: danh sách của bác sĩ được
        lọc khi lịch của bác sĩ kia KHÔNG có trong response
      - Test đầu tiên trong class là trường hợp KHÔNG bộ lọc, vì đó là request mà
        client thật gửi và là cái đã vỡ. Có test liệt kê MỌI tổ hợp thiếu bộ lọc,
        vì lỗi xảy ra theo từng tham số
      - Chốt luôn biên của bộ lọc ngày: ngày làm việc hết lúc nửa đêm theo
        `Asia/Ho_Chi_Minh` chứ không theo UTC, và biên trên là loại trừ — nên lịch
        hẹn 23:00 giờ địa phương thuộc đúng ngày nó trông như vậy, còn lịch đầu
        tiên của ngày mai không lọt vào
      - Đã trả lại hai test audit lịch hẹn giữ lại từ commit trước

    - [ ] ~~MỚI PHÁT HIỆN 19/10/2026 — `GET /api/appointments` vỡ khi thiếu bộ lọc~~
      `AppointmentRepository.findWithFilters` viết bằng các vế
      `(? is null or column = ?)`. PostgreSQL KHÔNG suy ra được kiểu cho một tham
      số trần đem so với null, nên câu truy vấn là `SQLGrammarException` mỗi khi
      một bộ lọc để trống — kể cả khi đã truyền `date`. Thực tế endpoint chỉ chạy
      khi client truyền ĐỦ mọi bộ lọc.
      - Phát hiện khi viết test Lô B cho lịch hẹn, KHÔNG phải do Lô B gây ra
      - Lọt được tới giờ vì test hiện có đều gọi service trực tiếp hoặc truyền đủ
        bộ lọc. Không có test nào gọi endpoint ở trạng thái mặc định
      - Hướng sửa: `cast(:param as uuid)` / `cast(:param as text)` trong JPQL
        native, hoặc chuyển sang Specification / query động. ĐỒNG THỜI viết test
        chốt đúng trường hợp không bộ lọc — đó là trường hợp mà client mặc định sẽ
        gọi
      - Code ghi audit cho `AppointmentService.list` ĐÃ CÓ và đúng; chỉ còn thiếu
        hai test (`aReceptionistListingTheScheduleIsRecorded`,
        `aDoctorListingTheirOwnScheduleIsNotRecorded`) nằm chờ trong comment ở
        `BreakGlassIntegrationTest$BulkReadsAreRecorded`

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

  - [x] 1.10 Xóa mềm và metadata lưu trữ
    - DỪNG trước khi hardcode: hỏi chủ phòng khám thời hạn lưu trữ theo Luật KCB 15/2023 `[CẦN KIỂM CHỨNG]`
    - Tạo migration `V8__soft_delete_retention.sql`: bổ sung `deleted_at`, `deleted_by`, `delete_reason`, `retention_until`, `legal_hold` cho `patient` và các bảng thuộc diện lưu trữ; tạo bảng `retention_policy` cấu hình thời hạn theo loại bản ghi
    - Bỏ hard delete khỏi `PatientService.delete`, chuyển sang đánh dấu xóa kèm lý do bắt buộc
    - Dùng phương thức repository tường minh (`findByIdAndDeletedAtIsNull`) thay vì lọc ẩn
    - Tính `retention_until` theo `RetentionPolicy`, tính lại khi bệnh nhân được đánh dấu đã mất
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6_
    - **Làm được mà không cần chờ câu trả lời về thời hạn.** Yêu cầu 6.4 nói con số phải được xác nhận trước khi hiện thực hóa, nhưng thiết kế đã định sẵn `retention_policy` là một **bảng** — tức con số là dữ liệu cấu hình, không phải code. Nên toàn bộ cơ chế dựng được ngay, còn con số thì phòng khám điền sau cùng với căn cứ pháp lý. Bảng để **trống có chủ ý**, không seed giá trị nào
    - **Hướng fail an toàn:** chưa có chính sách thì không tính được `retention_until`, nên nó để null; mà null thì không bao giờ thoả điều kiện "đã quá hạn", nên **hệ thống chưa cấu hình sẽ giữ lại mọi thứ**. Một con số đoán bừa còn tệ hơn không có số, vì nó trông như có căn cứ mà lại âm thầm quyết định khi nào hồ sơ bệnh án được phép tiêu hủy
    - **Phân chia trách nhiệm:** *mốc* bắt đầu tính là quyết định thiết kế nên nằm trong code (`RetentionCalculator`) — ưu tiên ngày mất nếu chính sách có quy định riêng, rồi tới ngày ngừng sử dụng, cuối cùng là ngày tạo; còn *số năm* là câu hỏi pháp lý nên nằm trong dữ liệu. Cột `legal_basis` là `not null`: một thời hạn lưu trữ không truy được về văn bản nào thì không thể kiểm toán
    - `delete_reason` và `deleted_by` được **database** ràng buộc bằng `CHECK`, không chỉ validate ở DTO. Một lần ngừng sử dụng hồ sơ mà không ghi lý do thì không thể soát lại, nên không phó thác cho việc mọi caller tương lai đều nhớ
    - Ràng buộc "ngày mất không ở tương lai" **không** làm bằng `CHECK` trong database: so sánh với `current_date` là hàm không IMMUTABLE, nên một bản dump phục hồi sau nhiều năm có thể fail validation đúng những dòng vốn hợp lệ lúc ghi — mà hồ sơ bệnh án thì phải sống lâu hơn vài thế hệ của chính hệ thống này. Kiểm tra đó đặt ở tầng ứng dụng. Đây đúng loại bẫy đã làm `V3` không áp dụng được
    - Lọc bản ghi đã ngừng sử dụng bằng **tên phương thức tường minh**, không dùng Hibernate filter toàn cục. Filter ẩn làm mọi câu truy vấn trông như không bị lọc, và trường hợp cần thấy bản ghi đã xoá (quản lý lưu trữ, hoặc tra tên bệnh nhân của một lịch hẹn cũ) sẽ âm thầm trả về rỗng — lỗi đó trông giống mất dữ liệu chứ không giống truy vấn bị lọc
    - Đổi `Instant` sang ngày theo **múi giờ phòng khám**, không theo UTC. Một hồ sơ ngừng dùng lúc 23h ngày 31 là ngừng ngày 31 theo giờ địa phương; tính từ ngày 1 vì UTC đã sang ngày mới thì lệch một ngày, mà lệch về phía tiêu hủy sớm hơn
    - Audit chỉ ghi rằng đã có một lần ngừng sử dụng, **không** sao lý do vào `audit_log.detail`. Lý do nằm trên chính dòng bệnh nhân; sao sang chỗ thứ hai là tạo thêm một bản copy của một câu kể về hoàn cảnh người bệnh, ở nơi có mức bảo vệ khác và sống lâu hơn cả hồ sơ nó mô tả
    - Frontend: `apiClient.delete` nhận thêm body (lý do **không** đi qua query string vì query string vào access log, mà lý do có thể kể hoàn cảnh bệnh nhân). `ConfirmDeleteDialog` thêm ô lý do bắt buộc, và sửa lại phần chữ — nó đang nói "thao tác này không thể hoàn tác", tức nói sai sự thật về đúng điều quan trọng nhất: hồ sơ không bị xoá

  - [x] 1.11 Viết test cho xóa mềm
    - Unit test: tính `retention_until` theo từng loại bản ghi và theo trường hợp tử vong
    - Integration test: bản ghi đã xóa không xuất hiện trong list và search, không còn endpoint nào xóa vĩnh viễn
    - _Requirements: 6.1, 6.3, 6.4, 6.6_
    - `RetentionCalculatorTest` (13 test): mốc tính theo từng trường hợp, quy tắc tử vong thắng quy tắc ngừng dùng, chưa cấu hình thì không ra ngày nào, không áp chính sách của loại bản ghi này sang loại khác, và ngày 29/2 cộng số năm thì lùi về 28/2 chứ không nhảy sang tháng 3 — đáng ghim vì lệch một ngày ở đây là lệch một ngày của thời điểm hồ sơ bệnh án được phép tiêu hủy
    - `SoftDeleteIntegrationTest` (23 test, DB `softdelete_it`, **không** `@Transactional`): dòng vẫn còn sau khi xoá; database từ chối xoá khi thiếu lý do, lý do chỉ có khoảng trắng, hoặc thiếu người xoá; bản ghi biến khỏi list, tìm theo tên, tìm theo điện thoại, `GET` và `PUT` theo id; không xoá được hai lần; RECEPTIONIST bị 403; chưa cấu hình chính sách thì không có gì đủ điều kiện tiêu hủy; `legal_hold` giữ lại bản ghi đã quá hạn (yêu cầu 6.5); bản ghi còn đang dùng thì dù quá hạn cũng không đủ điều kiện; và bệnh nhân đã ngừng dùng vẫn tra được qua `findById` để các bản ghi tham chiếu tới họ còn hiển thị được tên
    - Còn thiếu: chưa có test cho `legal_hold` **qua API** vì chưa có endpoint bật/tắt cờ này — nó thuộc task 7.3 (bộ máy lưu trữ và tiêu hủy). Hiện cờ chỉ đặt được trực tiếp trong database

  - [x] 1.12 Mã hóa cột định danh và sao lưu (RỦI RO CAO)
    - Sao lưu dữ liệu trước khi chạy
    - Tạo `EncryptedStringConverter implements AttributeConverter<String, String>` dùng AES-GCM, khóa từ biến môi trường `SCLINIC_DATA_KEY`
    - Tạo migration `V9__encrypt_identifiers.sql` thêm cột mới, job chuyển đổi dữ liệu một lần, và `V10__drop_plain_identifiers.sql` bỏ cột cũ. Chia hai migration để có điểm quay lại
    - Thêm cột `national_id_hash` chứa HMAC để tra cứu chính xác theo CCCD, vì cột đã mã hóa không tìm kiếm được
    - Viết script sao lưu tự động có mã hóa và script kiểm thử phục hồi cho staging
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_
    - **`V10` CỐ TÌNH CHƯA TẠO.** Ship V9 và V10 cùng một lần là **mất dữ liệu**: Flyway chạy trước khi ứng dụng khởi động, nên `drop column` sẽ thực thi trước khi job chuyển đổi kịp chạy lần nào. Khoảng cách giữa hai lần phát hành cũng là cửa sổ duy nhất để chẩn đoán một lần chuyển đổi sai khi giá trị gốc còn nguyên. Nội dung V10 và câu truy vấn kiểm tra điều kiện đã ghi ở comment cuối `V9__encrypt_identifiers.sql`
    - **AES-GCM, không phải CBC.** GCM vừa mã hoá vừa xác thực: ciphertext bị sửa trong database sẽ không giải mã được, thay vì ra chuỗi vô nghĩa nhưng trông hợp lệ — với số định danh cá nhân, đó là khác biệt giữa một lỗi nhìn thấy được và việc một bệnh nhân âm thầm mang số của người khác
    - **IV mới mỗi lần ghi**, nên cùng một CCCD ghi hai lần ra hai ciphertext khác nhau. Đây là thứ ngăn cột đó tiết lộ những bệnh nhân nào trùng số, và cũng là lý do cột đó **không tìm kiếm được**
    - **Blind index** (`national_id_hash`) để tra cứu chính xác theo CCCD. Phương án thường thấy là mã hoá tất định, nhưng nó đổi vấn đề này lấy vấn đề tệ hơn: bản rõ giống nhau ra ciphertext giống nhau, tức cột lộ ra ai trùng số với ai. Dùng HMAC có khoá, và là **khoá riêng** chứ không dùng chung với khoá mã hoá, vì chung một khoá thì một lần lộ là mất cả tính bí mật lẫn index. Không dùng SHA-256 trần: không gian số định danh nhỏ và rất có cấu trúc nên băm trần đảo ngược được bằng brute force trong vài giây
    - Unique index trên `national_id_hash` là **partial** (`where ... is not null`) — đa số bệnh nhân không có CCCD trong hồ sơ, mà unique index thường thì mọi dòng null đụng nhau. Đây cũng là cách duy nhất phát biểu được ràng buộc "hai bệnh nhân không trùng CCCD" khi giá trị mỗi lần mã hoá một khác
    - **JPA `AttributeConverter`, không dùng `pgcrypto`.** Với pgcrypto thì khoá đi trong câu lệnh SQL, tức vào statement log, vào `pg_stat_statements`, và vào slow-query log của bất cứ thứ gì đứng trước database. Làm ở tầng ứng dụng thì khoá không ra khỏi JVM, và một bản backup database là vô dụng nếu không có khoá
    - **Không có khoá mặc định, không có fallback.** Khoá mặc định sẽ nằm công khai trong repo này, làm việc mã hoá thành trang trí; còn âm thầm fallback về ghi bản rõ thì tệ hơn — hệ thống trông như đã tuân thủ trong khi vẫn lưu số định danh dạng thường. Thiếu khoá là **không khởi động được**
    - Converter **có** đọc được giá trị bản rõ cũ, một chiều đọc, để ứng dụng chạy được trong khoảng giữa migration và lúc job chuyển đổi xong. Mọi thứ ghi từ nay đều đã mã hoá
    - **Lỗi do test bắt được:** `CryptoKeys` ban đầu chỉ kiểm tra khoá dài tối thiểu 16 byte, mà AES chỉ nhận đúng 16/24/32. Khoá 34 byte qua được validation rồi mới chết ở **lần ghi đầu tiên** — trên production là chết ở bệnh nhân đầu tiên có người đăng ký, không phải lúc deploy. Đã thêm `decodeAesKey` kiểm tra đúng độ dài ngay lúc khởi động
    - Khoá cho test nằm ở `src/test/resources/application.properties` để mọi `@SpringBootTest` tự nhận. Đặt một chỗ chứ không rải vào từng class: một khoá phải lặp lại ở mười hai chỗ là một khoá sẽ thiếu ở chỗ thứ mười ba
    - `scripts/backup.ps1`: `pg_dump` nối ống trực tiếp sang `openssl` nên bản dump chưa mã hoá **không bao giờ xuống đĩa**; passphrase truyền qua file tạm chứ không qua tham số dòng lệnh (tiến trình khác trên máy đọc được tham số); giữ nhiều bản theo ngày/tuần/tháng; **chỉ prune sau khi đã ghi và kiểm tra thành công** nên một lần chạy lỗi không kéo các bản cũ đi theo; ghi kèm checksum để lúc phục hồi phân biệt được file hỏng với sai passphrase
    - `scripts/restore-check.ps1`: giải mã bản mới nhất, phục hồi vào database nháp, rồi **kiểm tra thực sự** — có `flyway_schema_history` (bản phục hồi mất bảng này thì sau đó không migrate tiếp được), đủ các bảng, **ba trigger append-only của `audit_log` còn nguyên** (mất chúng là bản phục hồi âm thầm mất luôn bảo đảm audit không sửa được), và không dòng nào mang số định danh chưa mã hoá. Thoát khác 0 khi lỗi để scheduler báo động. Nó bắt đúng những lỗi không lộ ra lúc backup: passphrase đã đổi mà schedule chưa cập nhật, dump bị cắt vì đầy đĩa, phiên bản `pg_dump` mà `pg_restore` ở máy phục hồi không đọc được
    - **CẢNH BÁO VẬN HÀNH:** mất `SCLINIC_DATA_KEY` là mất toàn bộ số định danh, không có đường khôi phục. Khoá này và passphrase backup phải lưu ở nơi **khác** server và **khác** chỗ chứa backup. Một bản backup mã hoá bằng khoá để ngay cạnh nó thì không phải là backup

  - [x] 1.13 Viết test cho mã hóa
    - Integration test: đọc ghi cột đã mã hóa qua API trả về bản rõ, truy vấn SQL thô trả về ciphertext
    - Test chuyển đổi dữ liệu không làm mất bản ghi
    - _Requirements: 7.1, 7.3, 7.4, 7.6_
    - `AesGcmCipherTest` (24 test): round trip, null khác rỗng, tiếng Việt có dấu, cùng giá trị ra ciphertext khác nhau, phát hiện sửa ciphertext **và** sửa IV, cắt ngắn, sai khoá, bản rõ không bị nhận nhầm là ciphertext, từng độ dài khoá AES hợp lệ
    - `IdentifierEncryptionIntegrationTest` (22 test, DB `crypto_it`): API trả bản rõ; **mọi assertion về bản rõ đều đọc cột bằng `JdbcTemplate`, đi vòng qua converter** — đó đúng là góc nhìn của một bản backup bị lấy hoặc một tài khoản database bị chiếm; có một test quét **cả bảng** (`patient::text like`) chứ không chỉ cột liên quan, vì một giá trị bị copy sang cột khác do lỗi mapping tương lai vẫn là lộ dữ liệu; tra cứu qua blind index và index được ghi lại khi số thay đổi; từ chối bệnh nhân thứ hai trùng CCCD; cho phép nhiều bệnh nhân không có CCCD; job chuyển đổi mã hoá đúng, dựng index, **không mất bản ghi**, chạy lại nhiều lần không mã hoá hai lần, và để nguyên cột gốc để kiểm tra
    - Mỗi test dùng một số định danh riêng (`AtomicInteger`): cột định danh có unique index qua blind index — chính là hành vi đang test — nên dùng chung một số trên database dùng chung sẽ làm mọi test sau test đầu tiên đụng nhau
    - Còn thiếu: chưa test **đổi khoá** (key rotation). Định dạng lưu đã có tiền tố `v1:` để làm được việc đó, nhưng chưa có cơ chế đọc bằng khoá cũ rồi ghi lại bằng khoá mới. Cần khi phòng khám có quy trình luân chuyển khoá

  - [x] 1.14 Phân quyền tối thiểu cần thiết và break-glass (backend xong, frontend còn nợ)
    - Tạo migration `V11__break_glass.sql`: bảng `break_glass_grant`
    - Tạo `PatientAccessGuard` quyết định theo vai trò và `Care_Relationship` (tồn tại appointment hoặc encounter giữa bác sĩ và bệnh nhân)
    - Tạo `BreakGlassService` và `POST /api/patients/{id}/break-glass` yêu cầu lý do không rỗng, quyền có thời hạn, audit mức cảnh báo, thông báo ADMIN
    - Tách hai DTO response: bản đầy đủ cho DOCTOR và ADMIN, bản hành chính cho RECEPTIONIST (không chứa `medical_history` và dữ liệu lâm sàng)
    - Sửa `PatientService.search` lọc theo `Care_Relationship` khi vai trò là DOCTOR
    - Frontend: hộp thoại nhập lý do break-glass
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7_
    - **Quan hệ điều trị suy ra từ `appointment`, không tạo bảng riêng.** Bảng riêng là lưu cùng một sự thật hai lần, và bản sao sẽ lệch: một bác sĩ bị chuyển khỏi bệnh nhân vẫn giữ quyền cho tới khi có thứ gì đó nhớ xoá dòng quan hệ đi
    - **Break-glass dễ làm nhưng không thể làm âm thầm.** Quyền cấp ngay vì tình huống cần tới nó là tình huống cấp bách; giá phải trả là một lý do được nêu, một bản ghi audit riêng loại, và thông báo tới quản trị. Cửa khoá mà không có chìa dẫn tới dùng chung tài khoản đăng nhập — tệ hơn một cửa mở có tên người mở trên đó
    - Thông báo 403 **nói rõ đường đi qua** và trả kèm `breakGlassPath`. Một cái 403 trơ ở đúng lúc người ta cần hồ sơ sẽ dẫn tới cách lách tệ nhất: vay tài khoản đồng nghiệp
    - **`BREAK_GLASS` là một `AuditAction` riêng, không phải cột mức độ trên `audit_log`.** Action nằm trong phần được hash của chuỗi audit nên không thể hạ cấp về sau mà chuỗi không đứt; một cột severity thêm ngoài phần hash thì sửa được âm thầm từ "cảnh báo" thành "thường"
    - **Hết hạn được đánh giá ngay trong câu truy vấn tra quyền**, không bằng job nền. Job nền để lại một khoảng thời gian mà quyền đã hết hạn vẫn mở được hồ sơ
    - **Hai DTO thật, không phải một DTO với trường để null.** Trường bị null không phân biệt được với trường rỗng, nên caller không biết "bệnh nhân không có dị ứng nào được ghi" khác với "bạn không được phép biết" — trong bối cảnh lâm sàng hai điều đó không được phép lẫn. Một kiểu không có trường đó thì không thể đọc nhầm, và nó cũng khiến một trường lâm sàng thêm vào sau này không tự động xuất hiện ở bản hành chính
    - **Lọc danh sách trong câu truy vấn, không lọc sau khi phân trang.** Lọc sau phân trang trả về trang ngắn và tổng số sai, mà một danh sách bệnh nhân âm thầm thiếu mục còn tệ hơn một danh sách từ chối thẳng
    - Không ghi audit cho việc bác sĩ đọc bệnh nhân của chính mình — đó là hệ thống hoạt động đúng, ghi hết sẽ vùi lấp những bản ghi đáng chú ý. ADMIN đọc thì **luôn** ghi (yêu cầu 8.7), và một lần đọc chỉ vào được nhờ break-glass cũng ghi riêng
    - `NotFound` quyết định **trước** `Forbidden`: trả "không có quyền" cho bệnh nhân không tồn tại sẽ để caller dò ra id nào là thật bằng cách đọc loại lỗi
    - **KHÁC YÊU CẦU 8.3 — thông báo ADMIN chưa phải thông báo thật.** `BreakGlassNotifier` hiện ghi log mức WARN kèm tên người, tức thứ mà log pipeline báo động được. Nó **không** phải email và không phải thông báo trong ứng dụng: hệ thống chưa có kênh nào cho cả hai, và dựng một kênh ở đây đồng nghĩa chọn nhà cung cấp email như tác dụng phụ của một thay đổi về kiểm soát truy cập. Tách thành type riêng để sau này thêm kênh thật là viết implementation mới chứ không sửa logic phân quyền. Có log mức ERROR riêng cho trường hợp phòng khám **không có ADMIN nào đang hoạt động** — khi đó không ai soát việc dùng break-glass, và tính chịu trách nhiệm mà cơ chế này dựa vào trở thành hình thức
    - Frontend ĐÃ XONG: `ui/BreakGlassDialog.vue` + `useBreakGlass` trong `patientQueries.ts`, nối vào nhánh `forbidden` của `PatientFormPage.loadPatient`. Phần chữ trong hộp thoại là nội dung chính của màn hình đó: phải làm hậu quả rõ không thể nhầm mà không làm hành động trông như bị cấm — nói thẳng là được phép mở, có ghi tên, quản trị được thông báo, và quyền sẽ tự hết
    - **Sửa luôn một lỗi ở frontend:** `errorMapper` đang bỏ toàn bộ body của **mọi** 403 và thay bằng câu chung. Đúng với phần lớn trường hợp từ chối, nhưng với trường hợp này thì nó ném đi đúng phần duy nhất mà bác sĩ có thể hành động theo. Giờ giữ lại lời giải thích của server khi có, và mang theo `breakGlassPath` để UI phân biệt được một lần từ chối **có** đường đi qua với một lần từ chối không có
    - Biết trước và chấp nhận: `PatientAccessGuard.viewFor` mặc định trả `FULL` cho vai trò lạ. Mặc định đúng phải là `ADMINISTRATIVE` để một vai trò thêm vào sau không lộ dữ liệu lâm sàng cho tới khi có người nhớ phân loại nó. Có test ghi lại hành vi hiện tại

  - [x] 1.15 Viết test cho phân quyền
    - Unit test: ma trận quyền theo vai trò, quan hệ điều trị, và có hay không break-glass
    - Integration test: break-glass tạo audit đúng loại, hết hạn thì mất quyền, lý do rỗng bị từ chối
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7_
    - `PatientAccessGuardTest` (21 test): ma trận vai trò × quan hệ điều trị × có/không grant; bác sĩ có quan hệ điều trị thì **không** cần tra grant; thông báo từ chối có nêu break-glass; grant đã thu hồi và đã hết hạn đều không còn hiệu lực
    - `BreakGlassIntegrationTest` (28 test, DB `access_it`): ba nhân viên, một bệnh nhân. Bác sĩ điều trị thấy toàn bộ; bác sĩ khác bị 403 kèm đường đi qua; lễ tân vào được nhưng **trường lâm sàng vắng mặt chứ không phải rỗng**, kể cả trong danh sách; lễ tân không xin được break-glass; break-glass mở được hồ sơ, đúng thời hạn cấu hình, lưu lý do, bị từ chối nếu lý do rỗng hoặc quá ngắn, **không** mở bệnh nhân khác, **không** mở cho bác sĩ thứ ba, hết hạn thì mất quyền, bị thu hồi thì mất quyền; audit ghi `BREAK_GLASS` riêng loại và vẫn nằm trong chuỗi hash; ADMIN đọc luôn được ghi, bác sĩ điều trị đọc thường thì không, đọc qua grant thì có; **lý do không bị sao vào `audit_log.detail`**
    - Hai lỗi test tự bắt được: (1) truyền `null` cho tham số keyword làm Hibernate không suy ra được kiểu nên bind thành `bytea` và `lower(bytea)` không tồn tại — phải tách thành hai phương thức repository thay vì một phương thức nhận keyword rỗng; (2) test lùi `expires_at` về quá khứ đã vi phạm đúng ràng buộc `chk_break_glass_window` mình vừa đặt, phải lùi cả `granted_at`
    - Lưu ý fixture: bệnh nhân tích luỹ qua các test trên database dùng chung và danh sách có phân trang, nên mỗi bệnh nhân phải có tên đủ riêng để tìm chính xác. Đọc trang đầu rồi mong bệnh nhân mới nhất nằm ở đó sẽ vỡ ngay khi có hơn một trang — chính lỗi này đã xảy ra

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

  - [x] 2.3 Dị ứng có cấu trúc
    - Tạo bảng `patient_allergy` tham chiếu `catalog_allergen`, lưu biểu hiện, mức độ, ngày ghi nhận, người ghi nhận, trạng thái
    - Partial unique index chặn hai bản ghi còn hiệu lực trỏ cùng một chất trên cùng bệnh nhân
    - Di trú `allergies` văn bản tự do sang ghi chú và đánh dấu cần rà soát, không tự động suy diễn
    - Frontend: cảnh báo nổi bật khi có dị ứng mức độ nặng
    - _Requirements: 10.1, 10.2, 10.3, 10.4_
    - **Khác thiết kế ban đầu:**
      - Thêm cột `certainty` (SUSPECTED/REPORTED/CONFIRMED) ngoài `severity`. Hai thứ độc lập: "nghi sốc phản vệ" và "mẩn đỏ đã xác nhận" là hai tình huống khác nhau, một trường "mức độ nghiêm trọng" không diễn đạt được cái nào
      - Bỏ hiệu lực (retire) bắt buộc có lý do, cả tầng ứng dụng và CHECK constraint. Gỡ một dị ứng khỏi danh sách còn hiệu lực là thay đổi những gì được kê cho bệnh nhân
      - Thứ tự sắp xếp theo mức độ viết bằng CASE tường minh, không dùng `order by severity desc`. Enum lưu dạng chuỗi nên sắp xếp chuỗi cho ra đúng SEVERE/MODERATE/MILD chỉ vì trùng hợp thứ tự chữ cái đảo ngược — thêm một mức (CRITICAL) sẽ âm thầm xếp nó dưới MILD
      - Cột `allergies` cũ CỐ TÌNH giữ lại (như V9 với cột định danh): bỏ ngay trong cùng bản phát hành với lúc di chuyển dữ liệu thì mất luôn cách kiểm tra việc di chuyển có đúng không
      - **Đóng thêm một lỗ hổng không có trong spec:** cột `allergies` vẫn ghi được qua API, nên văn bản tự do nhập MỚI sẽ nằm đúng trạng thái mà bản di trú tồn tại để đánh dấu, mà lại không mang dấu nào. `PatientService` giờ bật `allergyReviewRequired` cho mọi lần ghi văn bản tự do, không chỉ lúc di trú
      - Controller khoá ở mức class `hasAnyRole('DOCTOR','ADMIN')`. `PatientAccessGuard.requireAccess` thoát sớm với mọi vai trò ngoài DOCTOR (việc chặn dữ liệu lâm sàng là của `viewFor`), nên chỉ dựa vào guard thì lễ tân đọc được dị ứng — test đã bắt đúng lỗi này
      - Lượt đọc của ADMIN được ghi nhật ký (yêu cầu 8.7), lượt đọc của bác sĩ điều trị thì không

  - [x] 2.4 Viết test cho dị ứng
    - Unit test: không cho trùng chất còn hiệu lực trên cùng bệnh nhân
    - Migration test: dữ liệu `allergies` cũ không bị mất và được đánh dấu cần rà soát
    - _Requirements: 10.2, 10.3_
    - **Đã làm:** 37 test backend (`PatientAllergyIntegrationTest`, DB `allergy_it`) + 13 test frontend (`allergyWarning.test.ts`). Backend 474 → 511, frontend 356 → 371
    - **Ghi chú:**
      - Test migration ĐỌC chính câu `update` từ tệp V13 rồi chạy nó, chứ không viết lại câu lệnh trong test. Flyway chạy V13 trên schema rỗng nên không quan sát được tác dụng trên dữ liệu thật; lấy câu lệnh từ tệp mới làm cho nó là test của migration — sửa câu lệnh là test đỏ
      - Có test chốt rằng KHÔNG có bản ghi dị ứng có mã nào được sinh ra từ văn bản tự do
      - Có test chốt ràng buộc chống trùng vẫn giữ khi BỎ QUA tầng service (insert trực tiếp bằng SQL): import, script sửa dữ liệu, hay hai instance chạy song song đều phải đụng cùng bức tường
      - **Sửa hạ tầng test:** suite vượt giới hạn kết nối của embedded Postgres khi thêm class thứ 12 (`FATAL: sorry, too many clients already`). Mỗi Spring test context cache lại giữ một pool riêng suốt lượt chạy. Đã nâng `max_connections` lên 300 và chặn pool ở 16/context. KHÔNG hạ xuống 8: một request giữ nhiều hơn một kết nối cùng lúc (transaction đăng nhập mở trong khi ghi audit lấy kết nối riêng), và `AccountLockoutIntegrationTest` chạy 8 luồng song song

  - [x] 2.5 Module quản lý sự đồng ý
    - Tạo bảng `consent_type`, `consent_type_version`, `consent_record`
    - Tạo 7 loại consent tối thiểu, nội dung có phiên bản. Nội dung về dữ liệu nhạy cảm phải nêu rõ tính nhạy cảm
    - Tạo `ConsentService` với `requireConsent(patientId, type)` để các module khác gọi
    - Rút đồng ý ghi thời điểm, các hành động phụ thuộc bị từ chối từ đó trở đi
    - Frontend: màn hình thu consent khi tiếp nhận bệnh nhân mới
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7_
    - **Khác thiết kế ban đầu:**
      - **Seed 7 LOẠI nhưng KHÔNG seed NỘI DUNG.** Danh sách loại do yêu cầu 11.1 ấn định nên seed là an toàn (thiếu một loại thì hành động phụ thuộc hoặc chặn tất cả, hoặc tệ hơn là lọt vì không có gì để kiểm). Nội dung thì không: đó là văn bản pháp lý phòng khám phải chịu trách nhiệm, ship văn bản pháp lý do máy sinh ra nghĩa là sẽ có phòng khám chạy thật và đã thu đồng ý theo lời lẽ không ai ở đó phê duyệt. Giống `retention_policy`: hệ thống chưa cấu hình phải trông như chưa cấu hình
      - **`ConsentNotConfiguredException` TÁCH RIÊNG khỏi `ConsentRequiredException`.** Nhìn từ ngoài hai cái giống nhau (hành động đều bị từ chối) nhưng là hai vấn đề trái ngược: một cái sửa bằng việc bệnh nhân ký, cái kia bằng việc quản trị viên công bố nội dung chưa ai soạn. Báo cái thứ hai thành cái thứ nhất sẽ khiến phòng khám đi xin chữ ký cho thứ sẽ tiếp tục thất bại
      - **Yêu cầu 11.4 ÉP BẰNG TRIGGER, không chỉ bằng service.** Đây là ràng buộc liên bảng (nghĩa vụ ở `consent_type`, văn bản ở `consent_type_version`) nên CHECK không diễn đạt được. Phải khai báo được vì bản ghi version còn đến từ migration và từ script quản trị viên chạy tay — mà chạy tay chính là trường hợp bỏ qua kiểm tra ở tầng service. Trigger `raise ... using errcode = '23514'` để mọi client thấy nó là vi phạm ràng buộc, không phải lỗi server lạ
      - **`consent_type_id` được SAO xuống `consent_record` dù version đã hàm ý nó**, vì câu hỏi chạy trước mọi hành động là "bệnh nhân này có đồng ý loại này còn hiệu lực không" và trả lời bằng join là join trên đường nóng nhất. Bản sao không lệch được: **khoá ngoại tổ hợp** `(version_id, consent_type_id)` trỏ vào `unique (id, type_id)` của bảng version. Đây là cách khai báo thuần, không cần trigger
      - **`purpose` ĐÓNG BĂNG lúc thu đồng ý**, không đọc xuyên join. Sửa nội dung sau này không được phép làm thay đổi hồi tố điều bệnh nhân đã đồng ý — chính là lý do phải đánh phiên bản nội dung
      - **Thêm trạng thái `superseded_at` ngoài `withdrawn_at`.** Đồng ý lại theo nội dung mới KHÔNG phải là rút đồng ý; ghi thành rút sẽ đặt vào vết kiểm toán một sự kiện chưa từng xảy ra. Partial unique index chỉ cho MỘT bản ghi còn hiệu lực mỗi (bệnh nhân, loại) — hai bản ghi còn hiệu lực làm câu "bản nào đang áp dụng" không có đáp án, phá bỏ toàn bộ ý nghĩa của việc đánh phiên bản
      - **Rút đồng ý KHÔNG bắt buộc lý do** (khác với bỏ hiệu lực dị ứng, nơi lý do là bắt buộc). Quyền rút là vô điều kiện theo NĐ 13/2023; bắt khai lý do là hệ thống đòi bệnh nhân biện minh cho việc thực thi quyền. Nhưng *ai ghi nhận* thì bắt buộc
      - **Mọi vai trò đều được thu và ghi nhận rút đồng ý.** Thu đồng ý là việc của lễ tân, và rút phải ghi được bởi bất cứ ai bệnh nhân gặp. Từ chối vì sai vai trò không làm bệnh nhân quay lại sau — nó làm việc rút không được ghi lại. Công bố nội dung thì CHỈ ADMIN: quyết định hỏi bệnh nhân điều gì là việc khác, rủi ro khác, với việc ghi lại họ đã trả lời gì
      - `blocks_care` là DỮ LIỆU trên `consent_type`, không phải luật trong code. Từ chối cho dùng ảnh quảng cáo không bao giờ được ảnh hưởng việc được điều trị, và điều kiện viết trong code là điều kiện sẽ mọc ngoại lệ về sau
      - Version có `effective_from` tương lai thì CHƯA được phục vụ: dàn sẵn một thay đổi không được bắt đầu thu đồng ý theo nội dung chưa áp dụng
      - Chưa có bộ phận gửi nhắc hẹn nào tồn tại. Yêu cầu 11.7 hiện thực hoá thành `ConsentService.mayReceiveReminders(patientId)` — đặt tên theo điều nó quyết định, để thứ phải dừng khi rút đồng ý là MỘT hàm tìm được. Bộ phận gửi sau này phải hỏi nó TỪNG LẦN GỬI, không dựng danh sách người nhận một lần

  - [x] 2.6 Viết test cho consent
    - Unit test: consent đã rút thì hết hiệu lực, hành động thiếu consent bị từ chối kèm tên loại còn thiếu
    - Integration test: rút consent nhắc hẹn thì ngừng gửi
    - _Requirements: 11.5, 11.6, 11.7_
    - **Đã làm:** 47 test backend (`ConsentIntegrationTest`, DB `consent_it`) + 22 test frontend (`consentRules.test.ts`). Backend 511 → 558, frontend 371 → 393
    - **LỖI SẢN PHẨM THẬT TEST BẮT ĐƯỢC — mã hoá nguồn:** `pom.xml` chưa đặt `project.build.sourceEncoding`, nên javac đọc tệp nguồn bằng mã hoá mặc định của máy build (windows-1252 trên Windows). **Mọi chuỗi tiếng Việt trong code Java đang bị biến thành mojibake, kể cả thông báo hiện cho nhân viên phòng khám khi một hành động bị từ chối.** Tệp nguồn luôn đúng; thứ sai là việc đọc chúng — nên lỗi này vô hình cho đến khi có test so sánh một chuỗi tiếng Việt với chính nó sau vòng đi qua database. Đã đặt UTF-8 tường minh: build mà kết quả phụ thuộc locale của máy chạy nó thì cùng một commit cho ra artifact khác nhau
    - **Ghi chú khác:**
      - Spring Data KHÔNG quét repository lồng trong class (`considerNestedRepositories` mặc định false). Gộp ba repository vào một tệp `ConsentRepositories` compile được nhưng không tạo bean — phải tách thành ba interface top-level
      - `getContentAsString()` của MockMvc mặc định ISO-8859-1 khi response không khai báo charset, mà Spring không gắn charset vào `application/json` (JSON mặc nhiên là UTF-8). Client HTTP thật đọc đúng, MockMvc đọc sai — phải `getContentAsString(StandardCharsets.UTF_8)`
      - **KHÔNG làm yếu bất biến "mọi 5xx không lộ chi tiết" ở frontend.** Đã thử carve-out cho 503 để thông báo "chưa công bố nội dung" đến được người dùng, nhưng test property hiện có chốt bất biến này, và nó đúng: hàm đó không phân biệt được ProblemDetail do ứng dụng viết với 503 do proxy/container sinh ra, loại thứ hai mang nội tạng hệ thống. Thay vào đó màn hình lấy cùng thông tin từ chỗ an toàn hơn — endpoint trạng thái báo `notConfigured` theo từng loại — và mutation thu đồng ý làm mới trạng thái CẢ KHI LỖI
      - Có test chốt ràng buộc giữ được khi BỎ QUA service (insert SQL trực tiếp) cho cả ba thứ: trigger 11.4, khoá ngoại tổ hợp loại/version, và chống hai bản ghi còn hiệu lực

  - [x] 2.7 Danh mục dùng chung
    - Tạo bảng `catalog_icd10`, `catalog_drug`, `catalog_allergen`, hoàn thiện `service`
    - `catalog_drug` lưu tên INN, biệt dược, hàm lượng, dạng bào chế, đường dùng, đơn vị tính, mã thuốc, phân loại kiểm soát đặc biệt
    - Liên kết `catalog_drug` với `catalog_allergen` để đối chiếu khi kê đơn
    - Cơ chế nhập danh mục từ tệp, báo cáo số bản ghi thêm mới, cập nhật, bỏ qua
    - Tìm kiếm gợi ý theo cả mã và tên
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5_
    - **Danh mục ship rỗng.** Đây là danh mục quốc gia do Bộ Y tế ban hành; seed sẵn trong migration là đưa mã lâm sàng bịa vào đơn thuốc. Nạp từ tệp qua `CatalogueImportService`
    - Lý do phải mã hoá thay vì văn bản tự do: "Penicillin", "penicilin" và "PNC" là **một** chất với con người và **ba** chuỗi khác nhau với database — mà lần kiểm tra dị ứng có ý nghĩa là lần nổ trên cả ba
    - `catalog_drug` tách `inn_name` (tên gốc, thứ mà kiểm tra dị ứng thực sự quan tâm) khỏi `brand_name` (thứ ghi trên vỏ hộp, và là thứ bệnh nhân sẽ nói). Tìm kiếm khớp **cả hai**: người tìm Augmentin gõ Augmentin, người tìm amoxicillin gõ amoxicillin, cả hai phải ra cùng một thuốc
    - `national_code` tách khỏi `code` của phòng khám vì một thuốc có thể chưa có mã quốc gia, mà đơn thuốc cần mã nào đang có. Unique nhưng **partial**, chỉ khi không null
    - `control_class` đặt trên **thuốc**, không phó thác cho người kê nhớ: nghĩa vụ bổ sung khi kê thuốc kiểm soát đặc biệt không nên dựa vào việc ai đó nhận ra tên thuốc
    - `catalog_allergen.parent_id` tự tham chiếu để ghi phân nhóm (dị ứng một penicillin có thể cảnh báo cả nhóm beta-lactam). Tự tham chiếu chứ không bảng riêng vì phân cấp rất nông, và bản thân một nhóm thuốc cũng là thứ bệnh nhân phản ứng với
    - `catalog_drug_allergen` nhiều-nhiều: một thuốc phối hợp chứa nhiều chất, một chất có trong nhiều thuốc. `findContainingAnyAllergen` chính là câu truy vấn mà kiểm tra dị ứng lúc kê đơn sẽ chạy
    - `service` bổ sung `unit` và `vat_rate` vì hoá đơn theo NĐ 123/2020 cần cả hai trên từng dòng, và chúng thuộc về dịch vụ chứ không phải gõ lại mỗi lần lập hoá đơn
    - **Import khớp theo mã và lặp lại được.** Chạy lại cùng một tệp báo toàn bộ là "bỏ qua" và không đổi gì, nên người vận hành không chắc đã chạy chưa thì cứ chạy lại. Hàng có chi tiết khác thì tính vào "cập nhật" — **đây là con số đáng xem**: một lần cập nhật danh mục âm thầm đổi hàm lượng của thuốc đã dùng trong đơn là việc cần biết trước khi nó xảy ra. "Đã nhập 4.000 dòng" trả lời sai câu hỏi
    - **Một dòng lỗi không làm đổ cả tệp**, chỉ bị từ chối kèm số dòng và lý do. Từ chối cả tệp vì một dấu phẩy lạc nghĩa là danh mục bốn nghìn dòng không bao giờ nạp được
    - Dòng thuốc tham chiếu chất dị ứng không tồn tại thì **bị từ chối**, không nhập thuốc mà bỏ liên kết — nhập thiếu liên kết sẽ làm kiểm tra dị ứng âm thầm không đầy đủ, tệ hơn là từ chối thẳng
    - Tự viết tách CSV thay vì thêm thư viện: tệp là danh mục có cấu trúc cố định, và thứ duy nhất cần xử lý ngoài tách dấu phẩy là trường trong ngoặc kép có chứa dấu phẩy — mà hàm lượng thuốc thì có, kiểu `"500mg, 125mg"`
    - Dòng đầu tiên không rỗng được coi là header **theo vị trí, không theo nội dung**, để một danh mục mà mục đầu tiên tình cờ tên là "code" không bị âm thầm bỏ mất
    - Mã còn tra được kể cả khi `active = false`, chỉ không hiện trong gợi ý: một đơn thuốc viết năm ngoái tham chiếu thuốc nay đã rút số đăng ký, và nó vẫn phải hiển thị được

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

    - ### ĐIỂM DỪNG LÔ C — 20/10/2026
      Backend 608 → 618 test xanh. Frontend 393 test xanh, typecheck + build sạch.

      **ĐÃ XONG (backend):**

      1. **Đổi mật khẩu giờ ĐẾM VÀO LOCKOUT, và tài khoản bị khoá không đổi được
         mật khẩu.** Đây là lỗ hổng thật, không phải chuyện nhất quán: ô "mật
         khẩu hiện tại" là một password oracle. Endpoint đăng nhập cho 5 lần đoán
         rồi khoá, còn endpoint này cho đoán KHÔNG GIỚI HẠN vào cùng một bí mật,
         với bất kỳ ai đang giữ một phiên — kể cả phiên lấy được, mà đoán ra mật
         khẩu chính là bước từ "hành động như người đó lúc này" sang "hành động
         như người đó bất cứ lúc nào". Thiếu kiểm tra khoá thì lockout chỉ đẩy
         việc đoán sang một URL khác
      2. **Phân biệt token phiên chờ HẾT HẠN với mã sai.** Thêm
         `SessionExpiredException` → 401 kèm property `reason=SESSION_EXPIRED`, và
         `SessionTokenService.existsButUnusable`. Token chưa từng tồn tại / sai
         scope / rác thì VẪN là thông báo chung
      3. **Endpoint cấp lại mã dự phòng** `POST /api/auth/mfa/backup-codes`, BẮT
         BUỘC có mã TOTP hiện tại ngoài phiên FULL. Phiên FULL một mình KHÔNG đủ:
         mã dự phòng sống lâu hơn phiên tạo ra chúng và mỗi mã là một đường vượt
         qua yếu tố thứ hai, nên để phiên tự sinh ra chúng là biến một phiên bị
         lấy thành quyền vượt yếu tố thứ hai VĨNH VIỄN. Sai mã ở đây cũng đếm vào
         lockout. Thêm `AuthEventType.MFA_BACKUP_CODES_REISSUED` riêng, không gộp
         vào `MFA_ENROLLED`
      4. **`IllegalArgumentException` không còn tự động thành 400.** Thêm
         `BadRequestException` cho lỗi client CÓ CHỦ Ý; chuyển 10 chỗ ở 6 tệp.
         `IllegalArgumentException` trần giờ rơi xuống handler 500. Trước đó một
         bất biến nội bộ vỡ cũng thành 400 kèm nguyên message — `Base32.decode`
         gặp secret lưu bị hỏng sẽ trả "Not a base32 character: ?" với mã trạng
         thái nói rằng client làm sai, mà client không làm gì sai và không đổi gì
         được để sửa

      **QUYẾT ĐỊNH VỀ "NHẤT QUÁN THÔNG BÁO LỖI" — không flatten:**
      Javadoc của `AuthService` tuyên bố mọi thất bại đều đồng nhất; đã sửa lại
      cho đúng sự thật và nêu rõ quy tắc. Ở `login` thì đồng nhất tuyệt đối, vì
      người gọi chưa chứng minh được gì và mọi khác biệt đều là cách dò username
      nào tồn tại. SAU đó người gọi đã giữ token gắn với MỘT tài khoản, nên không
      còn gì để dò, và thông báo nói rõ cái gì sai. Flatten tiếp chỉ lấy mất cơ
      hội tự sửa của người dùng mà không mua được gì. Ngoại lệ duy nhất vẫn giữ:
      mã dùng lại KHÔNG BAO GIỜ phân biệt với mã sai.

      Comment ở frontend `mfaErrorMessage` nói vagueness là có chủ ý vì "mã sai
      đếm vào lockout nên phải không phân biệt được với phiên cũ" — **lập luận đó
      không đứng vững**: token đang nói tới là token người gọi tự giữ, biết nó hết
      hạn không cho họ thêm thông tin gì về tài khoản, cách sửa là đăng nhập lại
      bằng mật khẩu họ vừa dùng. Việc đoán mã không dễ hơn một chút nào. Còn giá
      phải trả thì thật: token chờ sống 10 phút, ai rời màn hình nhập mã đi nghỉ
      rồi quay lại sẽ bị bảo "mã không đúng", đọc lại mã, gõ lại, rồi kết luận
      ứng dụng authenticator bị lỗi.

      **ĐÃ XONG (frontend):** `ApiUnauthorized.reason`, `classifyResponse` chuyển
      tiếp `reason` cho 401 (message vẫn giữ chung chung), `authStore.sessionExpired`,
      `MfaVerifyPage` đưa người dùng về `/login` khi token hết hạn thay vì giữ họ
      lại gõ mã.

      **✅ HAI MỤC CUỐI CỦA LÔ C ĐÃ XONG 21/10/2026.** Frontend 393 → 403 test xanh.

      - **Cho phiên FULL tự bật MFA.** `authStore.canSetUpMfa` =
        `mfaEnrolmentRequired || isAuthenticated`. Backend vốn đã nhận token
        `FULL`, nên thứ duy nhất chặn lại là chính màn hình này — mà luồng đăng
        nhập thì KHÔNG BAO GIỜ đưa lễ tân tới đây vì vai trò của họ không bắt
        buộc MFA. Kết quả: **nhóm có khả năng tự nguyện bật nhất lại là nhóm
        không bật được.** Đã thêm cả hai màn hình vào menu điều hướng
      - Trang KHÔNG hỏi xem tài khoản đã có yếu tố thứ hai chưa, vì server không
        trả lời và cũng không cần: đăng ký trả 409 nếu đã có. Người vào tự nguyện
        rồi đổi ý thì được trả về app, KHÔNG bị đăng xuất như nút cũ vẫn làm
      - **Trang cấp lại mã dự phòng** `/mfa/recovery-codes`, BẮT BUỘC nhập mã
        TOTP hiện tại. Mã sai và tài khoản chưa bật MFA báo GIỐNG NHAU có chủ ý:
        cả hai đều 401, mã sai đếm vào lockout, nên chỗ này không được thành nơi
        phân biệt mã đúng với mã sai
      - **Thêm biến thể `ApiConflict` cho 409, giữ nguyên nội dung từ server.**
        Mọi 409 của backend này đều là một câu viết cho người dùng đọc (tài khoản
        đã có yếu tố thứ hai, dị ứng này đã có bản ghi còn hiệu lực) và mỗi câu
        nói rõ phải làm gì khác đi. Trước đó chúng rơi xuống catch-all và đến tay
        người dùng thành "An error occurred. Please try again later." — vừa sai về
        việc lỗi của ai, vừa vô dụng về việc phải làm gì. Một test property cũ
        liệt kê 409 vào nhóm "mã chưa định nghĩa"; đã sửa thành chốt hành vi mới

    - ### CÒN LẠI SAU LÔ C
      - [x] **Lô D — dọn dẹp. XONG 21/10/2026.** Backend 618 → 635 test xanh.
        `maintenance/{MaintenanceProperties, SecurityHousekeepingService,
        SecurityHousekeepingJob}`, chạy 03:15 giờ VN. `+AuditAction.PURGE`,
        `+AuthEventRepository.deleteCreatedBefore`, `@EnableScheduling`.

        **PHÁT HIỆN QUAN TRỌNG định hình cả lô này: `audit_log.session_id` TRỎ
        VÀO chính bảng `session_token`.** Xoá một dòng session là làm cho mọi
        dòng audit ghi trong phiên đó mất khả năng nói nó đến từ thiết bị nào,
        địa chỉ nào — dòng audit còn, ngữ cảnh của nó thì không. Mà hồ sơ audit
        y tế phải giữ nhiều năm. Nên "job dọn dẹp" ở đây KHÔNG phải là xoá cho
        gọn; câu trả lời đúng phần lớn là **đừng xoá**.

        **Thiết kế ba mức, theo đúng nguyên tắc `retention_policy`:**
        - `staff_password_history`: **LUÔN dọn**, theo SỐ LƯỢNG chứ không theo
          tuổi. `passwordHistorySize` đã nói giữ bao nhiêu mật khẩu cũ là đủ để
          chặn tái sử dụng, nên các dòng quá vị trí đó không ảnh hưởng được quyết
          định nào. **Không phải bịa ra con số nào cả.** Giữ lại chúng là giữ
          hash bcrypt mật khẩu cũ của nhân viên mà chẳng để làm gì
        - `session_token`: chỉ xoá khi operator CẤU HÌNH thời hạn. Mặc định
          KHÔNG đặt = giữ hết. Ai đặt là đang chấp nhận đánh đổi nói trên, và nên
          đặt CAO HƠN thời hạn lưu audit chứ không thấp hơn
        - `auth_event`: cũng chỉ xoá khi cấu hình. Bảng này CHÍNH LÀ hồ sơ ai đã
          thử đăng nhập và khi nào; một job tự động cắt nó là một job xoá chứng
          cứ theo lịch mà không ai chọn, mà chứng cứ thì thường được cần đến rất
          lâu sau thời điểm có người nghĩ tới việc hỏi
        - Cắt theo `expires_at` chứ không theo lúc tạo, nên thời hạn dù ngắn đến
          đâu cũng KHÔNG BAO GIỜ chạm tới phiên người ta đang dùng
        - Mỗi lượt chạy có xoá gì đều ghi audit `PURGE` kèm SỐ ĐẾM — purge không
          để lại dấu thì không phân biệt được với can thiệp, mà purge này xoá
          đúng những dòng mà rà soát an ninh đọc. `PURGE` tách khỏi `DELETE`: rà
          soát tìm "ai đã ngừng dùng hồ sơ bệnh nhân" không phải lọc bỏ bảo trì
          hằng đêm ra khỏi kết quả. Lượt chạy không xoá gì thì KHÔNG ghi audit
        - `detail` chỉ có số đếm, KHÔNG nêu tài khoản nào: nêu tên là sao mẫu
          hoạt động của nhân viên sang một bảng thứ hai sống lâu hơn chính dòng
          nó mô tả
        - Job bắt lỗi và ghi log chứ không ném lên: một lượt dọn thất bại không
          được làm scheduler bỏ luôn ngày mai
        - **Giả định MỘT instance**, đã ghi rõ trong javadoc của job. Nếu sau này
          chạy hai instance thì cả hai đều chạy và đua trên cùng các dòng — vô
          hại với delete (idempotent) nhưng sẽ đếm đôi trong dòng audit. Cần lock
          TRƯỚC khi có instance thứ hai
      - [x] **ĐÃ ĐO tải lên `audit_chain_head` — 21/10/2026. KẾT LUẬN: KHÔNG CẦN
        LÀM GÌ.** `AuditChainContentionTest` (DB `audit_contention_it`).

        **Số đo: 200 lượt ghi từ 8 luồng trong 579 ms = 345 lượt ghi/giây,
        2,9 ms mỗi lượt**, trên embedded Postgres chạy máy laptop. Dạng ghi dùng
        để đo đúng là dạng Lô B thêm vào: `VIEW` không có `entity_id`, kèm map
        `detail` nhỏ.

        Đối chiếu quy mô: một phòng khám da liễu phát sinh cỡ vài nghìn lượt
        thao tác có audit mỗi ngày. 345 lượt/giây dù đã xếp hàng tuần tự là
        khoảng 1,2 triệu lượt/giờ. Tức **điểm nghẽn lý thuyết này cách quy mô
        thật ba bậc độ lớn** — mối lo là có cơ sở về mặt thiết kế nhưng không có
        hệ quả thực tế, và tối ưu nó lúc này là tối ưu vào chỗ không ai chờ.
        Quyết định cũ ("chỉ xử lý nếu đo thấy thành vấn đề") giữ nguyên, giờ đã
        có số để đứng sau nó.

        Test khẳng định tính ĐÚNG (mọi dòng đều tới, chuỗi vẫn verify được,
        `entry_count` khớp, `prev_hash` không bị trùng — tức chuỗi không phân
        nhánh) và chỉ GHI LOG thông lượng, không assert, để một lượt chạy trên
        máy yếu không làm đỏ build. Có một assert thời gian duy nhất là ngưỡng
        trần 60 giây — để việc lock dòng head suy biến thành timeout thì build
        đỏ, chứ không phải để canh từng milisecond.

        Thêm một test riêng cho việc KHÔNG deadlock. Lập luận "lock chỉ lấy trên
        một bảng và transaction audit không chạm dòng nghiệp vụ nào" vốn chỉ nằm
        trong comment của `AuditService`, mà comment thì không đỏ khi có người
        thêm bảng thứ hai vào transaction đó.
      - [ ] **CHẶN BỞI USER:** xác nhận không môi trường nào có dòng `V3` trong
        `flyway_schema_history`; quyết định tách DB role cho ứng dụng
