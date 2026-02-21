# DaenamuScreen

> Bambu Lab 3D 프린터를 위한 안드로이드 컨트롤러 앱

[![Android CI](https://github.com/rlatn1234/DaenamuScreen/actions/workflows/android-build.yml/badge.svg)](https://github.com/rlatn1234/DaenamuScreen/actions/workflows/android-build.yml)

---

## 개요

**대나무 터치폰**은 Bambu Lab 3D 프린터를 안드로이드 기기에서 완전히 제어할 수 있는 네이티브 앱입니다.  
Bambu Cloud에 로그인한 후 TLS-MQTT 연결을 통해 프린터와 실시간으로 통신합니다.

- 패키지명: `dev.kimsu.daenamutouchphone`
- 최소 안드로이드 버전: **8.0 (API 26)**
- 대상 안드로이드 버전: **14 (API 34)**
- 현재 버전: **1.1.0** (versionCode 2)

---

## 주요 기능

### 🏠 홈 화면
- 실시간 출력 진행률 (원형 도넛 + 선형 프로그레스 바)
- 현재 레이어 / 총 레이어 수
- 남은 출력 시간
- 노즐 지름 및 재질 정보
- 노즐 / 베드 온도 실시간 표시
- 챔버 온도 (X1-시리즈만 해당, P1S/P1P/A1/A1 Mini는 표시 안 함)
- 출력 속도 단계 선택 (Silent / Standard / Sport / Ludicrous)
- 일시정지 / 재개 / 정지 버튼
- 챔버 조명 토글
- HMS(하드웨어 모니터링 시스템) 오류 알림 배너
- WiFi 신호 강도 (dBm) 표시

### 🧵 AMS 화면
- AMS 유닛별 4슬롯 수평 레이아웃 (Bambu Lab 터치스크린 스타일)
- 각 슬롯: 필라멘트 색상 블록 + 타입 레이블 + 트레이 번호
- 슬롯을 **탭** → 필라멘트 편집 다이얼로그
  - 필라멘트 타입 드롭다운 (PLA / PETG / ABS / ASA / PA / PA-CF / PC / PET-CF / PLA-CF / TPU / PVA)
  - 제조사 편집 드롭다운 (MQTT에서 자동 수신, 12개 주요 브랜드 프리셋 + 직접 입력)
  - 색상 Hex 코드 입력 + 실시간 색상 미리보기
  - 최소 / 최대 노즐 온도 설정
  - 현재 활성 슬롯에서는 "Load to Nozzle" 버튼 숨김
- 슬롯을 **5초 길게 누르기** → 해당 필라멘트 자동 로드
  - 다른 필라멘트가 로드 중이면 언로드 후 재로드
  - 진행 원형 인디케이터로 5초 카운트다운 시각화
- 상단 **Unload** 버튼으로 현재 로드된 필라멘트 언로드
- AMS 습도 / 온도 정보 표시

### 🎮 컨트롤 화면
- 노즐 정보 카드 (지름, 재질, 현재 온도)
- XY 축 조그 휠 (포인터 입력으로 방향 감지)
- 베드 Z축 이동 버튼 (↑10 / ↑1 / ↓1 / ↓10 mm)
- 노즐 / 베드 목표 온도 슬라이더
- 팬 속도 상태 표시
- 모든 축 홈 이동

### ⚙️ 설정 화면
- **Bambu Cloud 로그인** (이메일 + 비밀번호)
  - 이메일 인증 코드 2FA 지원
  - TOTP(앱 인증) 2FA 지원
  - 인증 코드 입력 패널: 오류 시 패널 유지, 재입력 가능
- 내 프린터 목록 불러오기 → 프린터 선택
- 연결 저장 및 MQTT 연결

### 🔄 가로/세로 모드
- **세로 모드**: 하단 네비게이션 바
- **가로 모드**: 왼쪽 네비게이션 레일 (Bambu Lab 위키 스타일)

---

## 지원 모델

| 모델 | 챔버 온도 | AMS | HMS |
|------|-----------|-----|-----|
| X1C  | ✅ | ✅ | ✅ |
| X1E  | ✅ | ✅ | ✅ |
| P1S  | ❌ (센서 없음) | ✅ | ✅ |
| P1P  | ❌ (센서 없음) | ✅ | ✅ |
| A1   | ❌ (센서 없음) | ✅ | ✅ |
| A1 Mini | ❌ (센서 없음) | ✅ | ✅ |

### P1S에서 지원하지 않는 기능

| 기능 | 이유 |
|------|------|
| 챔버 온도 모니터링 | 챔버 온도 센서 없음 |
| Lidar / AI 감지 | X1 시리즈 전용 하드웨어 |
| Flow 교정 (Lidar 기반) | X1 시리즈 전용 |
| Micro Lidar 교정 | X1 시리즈 전용 |
| 카메라 스트리밍 | 별도 구현 필요 |

---

## 빌드

### 요구 사항
- Android Studio Hedgehog 이상
- JDK 17
- Android SDK 34

### 빌드 방법

```bash
cd android
./gradlew assembleDebug     # 디버그 APK
./gradlew assembleRelease   # 릴리즈 APK (서명 설정 필요)
```

APK 출력 위치:
- `android/app/build/outputs/apk/debug/app-debug.apk`
- `android/app/build/outputs/apk/release/app-release-unsigned.apk`

### GitHub Actions CI

`android/**` 경로에 push 또는 PR이 생성되면 자동으로 빌드됩니다.  
빌드된 APK는 GitHub Actions 아티팩트로 업로드됩니다.

→ [Actions 탭에서 확인](../../actions/workflows/android-build.yml)

---

## 기술 스택

| 항목 | 기술 |
|------|------|
| 언어 | Kotlin |
| UI | Jetpack Compose + Material3 |
| 아키텍처 | MVVM (ViewModel + StateFlow) |
| 네비게이션 | Navigation Compose |
| 설정 저장 | DataStore Preferences |
| MQTT | Eclipse Paho MQTT v3 (TLS) |
| HTTP | OkHttp 4 |
| JSON | Gson |
| 코루틴 | Kotlin Coroutines |

---

## 통신 프로토콜

### Bambu Cloud 로그인
- **엔드포인트**: `https://api.bambulab.com/v1/user-service/user/login`
- 2FA: `loginType == "verifyCode"` → 이메일 코드 입력  
- 2FA: `loginType == "tfa"` → TOTP 코드 입력

### MQTT 연결 (Cloud 모드)
- **브로커**: `us.mqtt.bambulab.com:8883` (글로벌) / `cn.mqtt.bambulab.com:8883` (중국)
- **사용자명**: JWT에서 추출한 `u_{userId}` (Preference API 폴백 지원)
- **비밀번호**: 클라우드 인증 토큰
- **TLS**: 시스템 신뢰 저장소 (CA 서명 인증서)
- **구독 토픽**: `device/{serialNumber}/report`
- **발행 토픽**: `device/{serialNumber}/request`

---

## 프로젝트 구조

```
android/
├── app/
│   ├── src/main/
│   │   ├── kotlin/dev/kimsu/daenamutouchphone/
│   │   │   ├── MainActivity.kt              # 앱 진입점, 네비게이션 호스트
│   │   │   ├── data/model/
│   │   │   │   ├── AppSettings.kt           # DataStore 설정 데이터 클래스
│   │   │   │   ├── PrinterStatus.kt         # 프린터 상태 데이터 클래스
│   │   │   │   └── AmsStatus.kt             # AMS 상태 데이터 클래스
│   │   │   ├── data/repository/
│   │   │   │   └── SettingsRepository.kt    # DataStore 읽기/쓰기
│   │   │   ├── network/
│   │   │   │   ├── BambuCloudApi.kt         # 클라우드 로그인 / 장치 목록 API
│   │   │   │   └── BambuMqttService.kt      # TLS-MQTT 연결 및 명령 전송
│   │   │   ├── ui/screens/
│   │   │   │   ├── HomeScreen.kt            # 홈 화면
│   │   │   │   ├── AmsScreen.kt             # AMS 화면
│   │   │   │   ├── ControlScreen.kt         # 컨트롤 화면
│   │   │   │   └── SettingsScreen.kt        # 설정 화면
│   │   │   ├── ui/theme/
│   │   │   │   ├── Color.kt                 # Bambu 브랜드 색상
│   │   │   │   └── Theme.kt                 # Material3 테마
│   │   │   └── viewmodel/
│   │   │       └── PrinterViewModel.kt      # 비즈니스 로직 / 상태 관리
│   │   ├── res/
│   │   │   ├── drawable/                    # 런처 아이콘 (대나무 + 핸드폰)
│   │   │   ├── mipmap-anydpi-v26/           # 어댑티브 아이콘
│   │   │   ├── values/strings.xml
│   │   │   └── xml/network_security_config.xml
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
```

---

## 라이선스

이 프로젝트는 개인 목적으로 개발된 비공식 서드파티 앱입니다.  
Bambu Lab과는 공식적인 관계가 없습니다.

> **Bambu Lab** 및 관련 상표는 Bambu Lab의 소유입니다.
