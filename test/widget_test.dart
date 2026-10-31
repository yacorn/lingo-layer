import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:screen_translator/main.dart';
import 'package:screen_translator/model_library.dart';

void main() {
  const channel = MethodChannel('screen_translate/control');
  final calls = <MethodCall>[];
  late Map<String, dynamic> state;
  bool cancelStart = false;
  int translations = 0;
  Completer<void>? pendingInstall;
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() {
    calls.clear();
    cancelStart = false;
    translations = 0;
    pendingInstall = null;
    state = {
      'models': <Map<String, dynamic>>[
        {
          'id': 'aihub',
          'name': 'AIHub JA→KO INT8',
          'source': 'ja',
          'target': 'ko',
          'bundled': true,
          'bytes': 533479816,
        },
      ],
      'busy': false,
      'running': false,
      'overlayAllowed': true,
      'loadedId': null,
      'selectedId': 'aihub',
      'message': 'ready',
    };
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          switch (call.method) {
            case 'state':
              return state;
            case 'load':
              state['loadedId'] = (call.arguments as Map)['id'];
              state['selectedId'] = state['loadedId'];
              return null;
            case 'inspectModel':
              state['candidate'] = {
                'id': 'remote-fixture',
                'name': '다운로드 시험 모델',
                'source': 'ja',
                'target': 'ko',
                'bytes': 273984743,
                'license': 'MIT / NC-SA',
              };
              return state['candidate'];
            case 'installModel':
              if (pendingInstall != null) {
                await pendingInstall!.future;
                throw PlatformException(
                  code: 'ENGINE',
                  message: '다운로드를 취소했습니다.',
                );
              }
              (state['models'] as List).add(<String, dynamic>{
                ...state['candidate'] as Map,
                'bundled': false,
              });
              return null;
            case 'cancelDownload':
              pendingInstall?.complete();
              return null;
            case 'removeModel':
              (state['models'] as List).removeWhere(
                (m) => m['id'] == (call.arguments as Map)['id'],
              );
              state['selectedId'] = 'aihub';
              state['loadedId'] = null;
              return null;
            case 'start':
              if (cancelStart) throw PlatformException(code: 'CANCELLED');
              state['running'] = true;
              state['automatic'] = (call.arguments as Map)['automatic'];
              return null;
            case 'stop':
              state['running'] = false;
              return null;
            case 'translate':
              translations++;
              return {
                'text': '오늘 날씨가 좋네요. 산책할까요?',
                'ms': 410,
                'cached': translations > 1,
              };
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('screen_translate/events'),
          (_) async => null,
        );
  });
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );
  Future<void> open(WidgetTester tester) async {
    await tester.pumpWidget(const ScreenTranslatorApp());
    if (state['busy'] == true) {
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
    } else {
      await tester.pumpAndSettle();
    }
  }

  Future<void> tapKey(WidgetTester tester, String key) async {
    if (find.byKey(ValueKey(key)).evaluate().isEmpty) {
      await tester.scrollUntilVisible(
        find.byKey(ValueKey(key)),
        180,
        scrollable: find.byType(Scrollable).first,
      );
    }
    await tester.ensureVisible(find.byKey(ValueKey(key)));
    if (state['busy'] != true) await tester.pumpAndSettle();
    await tester.tap(find.byKey(ValueKey(key)));
    if (state['busy'] == true) {
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
    } else {
      await tester.pumpAndSettle();
    }
  }

  Future<void> tab(WidgetTester tester, String label) async {
    await tester.tap(find.text(label).last);
    if (state['busy'] == true) {
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
    } else {
      await tester.pumpAndSettle();
    }
  }

  testWidgets(
    'start prepares fixed model then starts automatic inline capture',
    (tester) async {
      await open(tester);
      await tapKey(tester, 'capture');
      expect(calls.where((c) => c.method == 'load').single.arguments, {
        'id': 'aihub',
      });
      expect(calls.where((c) => c.method == 'start').single.arguments, {
        'manual': true,
        'automatic': true,
      });
      expect(
        calls.indexWhere((c) => c.method == 'load'),
        lessThan(calls.indexWhere((c) => c.method == 'start')),
      );
      expect(find.text('화면 번역 종료'), findsOneWidget);
      await tapKey(tester, 'capture');
      expect(calls.where((c) => c.method == 'stop'), hasLength(1));
    },
  );
  testWidgets('manual mode can still be selected before sharing', (
    tester,
  ) async {
    await open(tester);
    await tapKey(tester, 'automatic-mode');
    await tapKey(tester, 'capture');
    expect(calls.where((c) => c.method == 'start').single.arguments, {
      'manual': true,
      'automatic': false,
    });
  });
  testWidgets('permission settings do not implicitly start sharing', (
    tester,
  ) async {
    state['overlayAllowed'] = false;
    await open(tester);
    await tapKey(tester, 'capture');
    expect(calls.where((c) => c.method == 'overlayPermission'), hasLength(1));
    expect(calls.where((c) => c.method == 'start'), isEmpty);
    state['overlayAllowed'] = true;
    await tapKey(tester, 'capture');
    expect(calls.where((c) => c.method == 'start'), hasLength(1));
    expect(calls.where((c) => c.method == 'load'), hasLength(1));
  });
  testWidgets('cancelled consent recovers and can be retried', (tester) async {
    cancelStart = true;
    await open(tester);
    await tapKey(tester, 'capture');
    expect(find.text('화면 공유를 취소했습니다. 다시 시작할 수 있습니다.'), findsOneWidget);
    cancelStart = false;
    await tapKey(tester, 'capture');
    expect(find.text('화면 번역 종료'), findsOneWidget);
  });
  testWidgets('stop remains available while native translation is busy', (
    tester,
  ) async {
    state['running'] = true;
    state['busy'] = true;
    state['loadedId'] = 'aihub';
    await open(tester);
    expect(
      tester
          .widget<FilledButton>(find.byKey(const ValueKey('capture')))
          .onPressed,
      isNotNull,
    );
    await tab(tester, '직접 번역');
    expect(
      tester
          .widget<FilledButton>(find.byKey(const ValueKey('translate')))
          .onPressed,
      isNull,
    );
    await tab(tester, '홈');
    // A progress indicator is animated indefinitely, so do not pumpAndSettle here.
    await tester.tap(find.byKey(const ValueKey('capture')));
    await tester.pump();
    await tester.pump();
    expect(calls.where((c) => c.method == 'stop'), hasLength(1));
  }, skip: false);
  testWidgets('direct output comes from its own request and exposes cache', (
    tester,
  ) async {
    state['translation'] = '다른 화면의 번역';
    await open(tester);
    await tab(tester, '직접 번역');
    expect(find.text('다른 화면의 번역'), findsNothing);
    await tapKey(tester, 'translate');
    expect(find.text('오늘 날씨가 좋네요. 산책할까요?'), findsOneWidget);
    expect(find.text('번역 0.41초 · 기기에서 처리'), findsOneWidget);
    await tapKey(tester, 'translate');
    expect(find.text('이전 결과 재사용'), findsOneWidget);
    await tester.enterText(find.byType(TextField), '');
    await tester.pump();
    expect(find.text('오늘 날씨가 좋네요. 산책할까요?'), findsNothing);
    expect(
      tester
          .widget<FilledButton>(find.byKey(const ValueKey('translate')))
          .onPressed,
      isNull,
    );
  });
  testWidgets('all pages fit narrow display with large text', (tester) async {
    tester.view.physicalSize = const Size(320, 720);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await open(tester);
    expect(tester.takeException(), isNull);
    for (final label in ['직접 번역', '모델', '설정', '홈']) {
      await tab(tester, label);
      expect(tester.takeException(), isNull);
    }
  });
  testWidgets(
    'repository preview requires an explicit install and model selection',
    (tester) async {
      await open(tester);
      await tab(tester, '모델');
      await tester.ensureVisible(find.byKey(const ValueKey('model-url')));
      await tester.enterText(
        find.byKey(const ValueKey('model-url')),
        'https://huggingface.co/fixture/model',
      );
      await tapKey(tester, 'inspect-model');
      expect(calls.where((c) => c.method == 'inspectModel'), hasLength(1));
      expect(
        tester
            .widget<ModelLibrary>(find.byType(ModelLibrary))
            .state['candidate'],
        isNotNull,
      );
      expect(
        tester
            .widget<TextField>(find.byKey(const ValueKey('model-url')))
            .controller!
            .text,
        'https://huggingface.co/fixture/model',
      );
      expect(find.text('다운로드 시험 모델'), findsOneWidget);
      expect(calls.where((c) => c.method == 'installModel'), isEmpty);
      expect(calls.where((c) => c.method == 'load'), isEmpty);
      await tapKey(tester, 'install-model');
      expect(calls.where((c) => c.method == 'installModel').single.arguments, {
        'id': 'remote-fixture',
      });
      expect(calls.where((c) => c.method == 'load'), isEmpty);
      expect(
        (state['models'] as List).map((m) => m['id']),
        contains('remote-fixture'),
      );
      expect(
        tester.widget<ModelLibrary>(find.byType(ModelLibrary)).state['models'],
        hasLength(2),
      );
      await tester.scrollUntilVisible(
        find.byKey(const ValueKey('select-remote-fixture')),
        -180,
        scrollable: find.byType(Scrollable).first,
      );
      await tapKey(tester, 'select-remote-fixture');
      expect(state['selectedId'], 'remote-fixture');
      await tab(tester, '홈');
      await tapKey(tester, 'capture');
      expect(calls.where((c) => c.method == 'load'), hasLength(1));
    },
  );
  testWidgets('saved selected model is prepared instead of hardcoded default', (
    tester,
  ) async {
    state['selectedId'] = 'remote-fixture';
    (state['models'] as List).add({
      'id': 'remote-fixture',
      'name': '시험 모델',
      'source': 'ja',
      'target': 'ko',
      'bundled': false,
    });
    await open(tester);
    await tapKey(tester, 'capture');
    expect(calls.where((c) => c.method == 'load').single.arguments, {
      'id': 'remote-fixture',
    });
  });
  testWidgets(
    'download model removal requires confirmation and leaves default',
    (tester) async {
      (state['models'] as List).add({
        'id': 'remote-fixture',
        'name': '시험 모델',
        'bundled': false,
      });
      await open(tester);
      await tab(tester, '모델');
      await tapKey(tester, 'remove-remote-fixture');
      expect(calls.where((c) => c.method == 'removeModel'), isEmpty);
      await tester.tap(find.widgetWithText(TextButton, '삭제').last);
      await tester.pumpAndSettle();
      expect(calls.where((c) => c.method == 'removeModel'), hasLength(1));
      expect((state['models'] as List).single['id'], 'aihub');
    },
  );
  testWidgets('model downloads are disabled during screen translation', (
    tester,
  ) async {
    state['running'] = true;
    await open(tester);
    await tab(tester, '모델');
    await tester.ensureVisible(find.byKey(const ValueKey('model-url')));
    expect(
      tester.widget<TextField>(find.byKey(const ValueKey('model-url'))).enabled,
      isFalse,
    );
    expect(
      tester
          .widget<OutlinedButton>(find.byKey(const ValueKey('inspect-model')))
          .onPressed,
      isNull,
    );
    expect(calls.where((c) => c.method == 'inspectModel'), isEmpty);
  });
  testWidgets('download progress exposes cancellation and recovers', (
    tester,
  ) async {
    await open(tester);
    await tab(tester, '모델');
    await tester.ensureVisible(find.byKey(const ValueKey('model-url')));
    await tester.enterText(
      find.byKey(const ValueKey('model-url')),
      'https://huggingface.co/fixture/model',
    );
    await tapKey(tester, 'inspect-model');
    await tester.ensureVisible(find.byKey(const ValueKey('install-model')));
    await tester.pumpAndSettle();
    pendingInstall = Completer<void>();
    await tester.tap(find.byKey(const ValueKey('install-model')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byKey(const ValueKey('cancel-download')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('cancel-download')));
    await tester.pumpAndSettle();
    expect(find.text('다운로드를 취소했습니다.'), findsOneWidget);
    expect(find.byKey(const ValueKey('cancel-download')), findsNothing);
  });
}
