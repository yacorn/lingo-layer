# 빌드와 검증

## 준비

검증에 사용한 도구는 Flutter 3.47.5 / Dart 3.13.4, Java 21, Android SDK 36, NDK 28.2.13676358, CMake 3.22.1, Python 3.11 이상입니다. Windows PowerShell과 `curl.exe`가 필요합니다. Flutter·Java·Python을 PATH에 추가하고 Android SDK 위치를 설정하세요. 한글 경로에서 도구가 실패하면 영문 경로에 저장소를 복제해 사용하세요.

## ARM64 Release

저장소 루트에서 실행합니다.

```powershell
flutter pub get
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build.ps1
python scripts/verify-apk.py
```

`scripts/build.ps1`은 모델·사전·MeCab 소스를 고정 리비전으로 준비하고 SHA-256을 확인한 뒤 빌드합니다. 최초 빌드에는 인터넷이 필요합니다. 모델이 이미 있으면 `scripts/build.ps1 -CheckModels`로 다운로드 없이 검증할 수 있습니다. MeCab 소스가 없으면 이 옵션에서도 소스를 받습니다.

결과는 `build/app/outputs/flutter-apk/app-release.apk`입니다. 개발용 키로 서명하므로 공개 서비스 배포 시에는 별도 서명 관리가 필요합니다. `scripts/verify-apk.py`는 모델·사전·고지 파일의 일치, ARM64 엔진·일본어 OCR 포함 여부와 ONNX Runtime JNI 클래스 보존을 확인합니다.

## 소스 검사

```powershell
flutter analyze
flutter test
Push-Location android
.\gradlew.bat :app:testDebugUnitTest -Ptarget-platform=android-arm64
Pop-Location
```

Flutter 검사는 화면 조작·모델 관리 UI를, Android 단위 검사는 캡처 규칙과 모델 주소 검증을 다룹니다.

## 기기·에뮬레이터 검사

모델 준비 후 Debug 앱과 테스트 APK를 빌드합니다. x86_64 에뮬레이터에서는 아래의 두 `android-arm64`를 모두 `android-x64`로 바꾸세요.

```powershell
flutter build apk --debug --target-platform android-arm64
Push-Location android
.\gradlew.bat :app:assembleDebugAndroidTest -Ptarget-platform=android-arm64
Pop-Location
adb -s <serial> install -r build/app/outputs/flutter-apk/app-debug.apk
adb -s <serial> install -r build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w -r -e class dev.personal.screen_translator.TokenizerCompatibilityTest,dev.personal.screen_translator.ModelSmokeTest dev.personal.screen_translator.test/androidx.test.runner.AndroidJUnitRunner
```

화면 캡처 회귀 테스트는 사용자가 Android 화면 공유를 승인해야 합니다. 기기에 설치된 일반 Release의 내장 예제·캐시만 확인하려면 `python scripts/verify-phone-release.py <serial> --adb <adb경로>`를 사용하세요. 이 도구는 본 앱을 재시작하므로 테스트 기기에서 실행하세요.

일반 Release는 `android/app/proguard-rules.pro`로 ONNX Runtime JNI 클래스 이름을 보존합니다. 이 규칙을 제거하면 첫 추론에서 앱이 종료될 수 있습니다. 모델 사용 조건은 [제3자 고지](../THIRD_PARTY_NOTICES.md)를 확인하세요.
