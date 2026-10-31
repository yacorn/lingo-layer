import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'model_library.dart';

void main() => runApp(const ScreenTranslatorApp());

class ScreenTranslatorApp extends StatelessWidget {
  const ScreenTranslatorApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'LingoLayer',
    theme: ThemeData(
      colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff206b59))
          .copyWith(
            primary: const Color(0xff206b59),
            onPrimary: Colors.white,
            surface: const Color(0xfff7f7f0),
            onSurface: const Color(0xff202923),
            primaryContainer: const Color(0xffe4eee5),
            onPrimaryContainer: const Color(0xff203d31),
          ),
      scaffoldBackgroundColor: const Color(0xfff7f7f0),
      useMaterial3: true,
      textTheme: const TextTheme(
        headlineMedium: TextStyle(
          fontSize: 28,
          fontWeight: FontWeight.w700,
          letterSpacing: -1,
        ),
        titleMedium: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
        titleSmall: TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
        bodyMedium: TextStyle(fontSize: 14, height: 1.6),
        bodySmall: TextStyle(
          fontSize: 12,
          height: 1.6,
          color: Color(0xff5f6b62),
        ),
      ),
      dividerTheme: const DividerThemeData(
        color: Color(0xffdfe4da),
        thickness: 1,
      ),
      navigationBarTheme: const NavigationBarThemeData(
        backgroundColor: Color(0xfff7f7f0),
        indicatorColor: Color(0xffe0ebdf),
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: Color(0xfff7f7f0),
        scrolledUnderElevation: 0,
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size(48, 52),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
        ),
      ),
      inputDecorationTheme: const InputDecorationTheme(
        filled: true,
        fillColor: Colors.white,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(16)),
        ),
      ),
    ),
    home: const TranslatorPage(),
  );
}

class TranslatorPage extends StatefulWidget {
  const TranslatorPage({super.key});
  @override
  State<TranslatorPage> createState() => _TranslatorPageState();
}

class _TranslatorPageState extends State<TranslatorPage>
    with WidgetsBindingObserver {
  static const control = MethodChannel('screen_translate/control');
  static const events = EventChannel('screen_translate/events');
  final input = TextEditingController(text: '今日はいい天気ですね。少し散歩しませんか？');
  StreamSubscription<dynamic>? subscription;
  Map<String, dynamic> state = {};
  int page = 0;
  String? error;
  bool requesting = false;
  String directResult = '';
  num directMs = 0;
  bool directCached = false;
  bool automatic = true;
  bool downloading = false;
  bool get activeAutomatic => running ? state['automatic'] == true : automatic;
  bool get busy => state['busy'] == true || requesting;
  bool get running => state['running'] == true;
  String get selectedId => state['selectedId'] as String? ?? 'aihub';
  bool get ready => state['loadedId'] == selectedId;
  List<Map> get modelList => (state['models'] as List? ?? []).cast<Map>();
  Map get selectedModel =>
      modelList.firstWhere((m) => m['id'] == selectedId, orElse: () => {});
  bool get overlayAllowed => state['overlayAllowed'] == true;
  bool get hasModel => (state['models'] as List? ?? []).isNotEmpty;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    subscription = events.receiveBroadcastStream().listen(
      (event) {
        if (mounted) {
          setState(() => state = Map<String, dynamic>.from(event as Map));
        }
      },
      onError: (Object e) {
        if (mounted) setState(() => error = e.toString());
      },
    );
    refresh();
  }

  Future<void> refresh() async {
    try {
      final value = await control.invokeMapMethod<String, dynamic>('state');
      if (mounted && value != null) setState(() => state = value);
    } on PlatformException catch (e) {
      if (mounted) setState(() => error = e.message);
    }
  }

  Future<void> perform(Future<void> Function() action) async {
    if (requesting) return;
    setState(() {
      requesting = true;
      error = null;
    });
    try {
      await action();
    } on PlatformException catch (e) {
      if (mounted) {
        setState(
          () => error = e.code == 'CANCELLED'
              ? '화면 공유를 취소했습니다. 다시 시작할 수 있습니다.'
              : (e.message ?? '처리하지 못했습니다. 다시 시도해 주세요.'),
        );
      }
    } finally {
      if (mounted) {
        setState(() => requesting = false);
        await refresh();
      }
    }
  }

  Future<void> prepare() async {
    if (!ready) await control.invokeMethod<void>('load', {'id': selectedId});
  }

  Future<void> modelAction(String method, Map<String, dynamic> args) async {
    if (method == 'installModel') setState(() => downloading = true);
    try {
      await perform(() async {
        await control.invokeMethod<dynamic>(method, args);
        if (mounted && (method == 'load' || method == 'removeModel')) {
          setState(() => directResult = '');
        }
      });
    } finally {
      if (mounted) setState(() => downloading = false);
    }
  }

  Future<void> start() => perform(() async {
    await prepare();
    await refresh();
    if (!mounted) return;
    if (!overlayAllowed) {
      await control.invokeMethod<void>('overlayPermission');
    } else {
      await control.invokeMethod<void>('start', {
        'manual': true,
        'automatic': automatic,
      });
    }
  });
  Future<void> translate() => perform(() async {
    final text = input.text.trim();
    if (text.isEmpty) return;
    await prepare();
    final value = await control.invokeMapMethod<String, dynamic>('translate', {
      'text': text,
    });
    if (mounted && value != null) {
      setState(() {
        directResult = value['text'] as String? ?? '';
        directMs = value['ms'] as num? ?? 0;
        directCached = value['cached'] == true;
      });
    }
  });
  Future<void> licenses() => perform(() async {
    final values = await control.invokeMapMethod<String, String>('licenses');
    if (!mounted || values == null) return;
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('모델·엔진 라이선스'),
        content: SizedBox(
          width: 480,
          child: SingleChildScrollView(
            child: SelectableText(
              values.entries.map((e) => '${e.key}\n\n${e.value}').join('\n\n'),
              style: const TextStyle(fontSize: 12),
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('닫기'),
          ),
        ],
      ),
    );
  });
  @override
  void didChangeAppLifecycleState(AppLifecycleState status) {
    if (status == AppLifecycleState.resumed) refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    subscription?.cancel();
    input.dispose();
    super.dispose();
  }

  Widget instruction(String number, String title, String description) =>
      Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              number,
              style: TextStyle(
                color: Theme.of(context).colorScheme.primary,
                fontSize: 18,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(description),
                ],
              ),
            ),
          ],
        ),
      );
  String languageName(String code) =>
      const {'ja': '일본어', 'ko': '한국어', 'en': '영어', 'zh': '중국어'}[code] ?? code;

  Widget languageSelector(bool source) {
    final field = source ? 'source' : 'target';
    final code = selectedModel[field] as String? ?? (source ? 'ja' : 'ko');
    final codes = modelList
        .map((m) => m[field] as String? ?? (source ? 'ja' : 'ko'))
        .toSet();
    if (codes.isEmpty) codes.add(code);
    return Expanded(
      child: DropdownButtonFormField<String>(
        key: ValueKey('language-$field-$code'),
        initialValue: code,
        isExpanded: true,
        decoration: InputDecoration(labelText: source ? '원문 언어' : '번역 언어'),
        items: codes
            .map(
              (value) => DropdownMenuItem(
                value: value,
                child: Text(languageName(value)),
              ),
            )
            .toList(),
        onChanged: busy || running
            ? null
            : (value) {
                if (value == null || value == code) return;
                final model = modelList.firstWhere((m) => m[field] == value);
                modelAction('load', {'id': model['id']});
              },
      ),
    );
  }

  List<Widget> home() => [
    Row(
      children: [
        Expanded(
          child: Text(
            '화면 번역',
            style: Theme.of(context).textTheme.headlineMedium,
          ),
        ),
        const Icon(Icons.offline_bolt_outlined, size: 19),
        const SizedBox(width: 4),
        const Text('기기에서 처리', style: TextStyle(fontSize: 12)),
      ],
    ),
    const SizedBox(height: 20),
    Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: const Color(0xff203b31),
        borderRadius: BorderRadius.circular(24),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                running ? Icons.radio_button_checked : Icons.layers_outlined,
                size: 19,
                color: const Color(0xffc2ddc5),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: AnimatedSwitcher(
                  duration: MediaQuery.disableAnimationsOf(context)
                      ? Duration.zero
                      : const Duration(milliseconds: 180),
                  child: Align(
                    key: ValueKey(
                      '${running}_${state['paused']}_$activeAutomatic',
                    ),
                    alignment: Alignment.centerLeft,
                    child: Text(
                      running
                          ? (state['paused'] == true ? '일시정지' : '화면 번역 중')
                          : '번역 대기 중',
                      style: const TextStyle(
                        color: Color(0xffc2ddc5),
                        fontSize: 13,
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 22),
          Text(
            running
                ? (activeAutomatic
                      ? '테두리와 알림에서\n번역을 관리하세요.'
                      : '떠 있는 버튼으로\n화면을 번역하세요.')
                : '${languageName(selectedModel['source'] as String? ?? 'ja')} 화면을\n${languageName(selectedModel['target'] as String? ?? 'ko')}로 번역',
            style: const TextStyle(
              color: Color(0xfff5f6ed),
              fontSize: 26,
              height: 1.4,
              fontWeight: FontWeight.w600,
              letterSpacing: -.8,
            ),
          ),
          const SizedBox(height: 24),
          const Divider(color: Color(0xff4e6858), height: 1),
          const SizedBox(height: 16),
          Text(
            '${languageName(selectedModel['source'] as String? ?? 'ja')} → ${languageName(selectedModel['target'] as String? ?? 'ko')}',
            style: const TextStyle(color: Color(0xffdbe6d8), fontSize: 14),
          ),
        ],
      ),
    ),
    const SizedBox(height: 28),
    Text('번역 언어', style: Theme.of(context).textTheme.titleMedium),
    const SizedBox(height: 14),
    Row(
      children: [
        languageSelector(true),
        const Padding(
          padding: EdgeInsets.symmetric(horizontal: 10),
          child: Icon(Icons.arrow_forward, size: 18),
        ),
        languageSelector(false),
      ],
    ),
    const SizedBox(height: 8),
    Text(
      '설치한 모델이 지원하는 언어를 선택할 수 있습니다.',
      style: Theme.of(context).textTheme.bodySmall,
    ),
    const SizedBox(height: 22),
    ListTile(
      key: const ValueKey('current-model'),
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.memory_outlined),
      title: Text(selectedModel['name'] as String? ?? 'AIHub JA→KO INT8'),
      subtitle: Text(ready ? '준비 완료 · 오프라인 사용' : '시작하면 자동으로 준비합니다.'),
      trailing: const Icon(Icons.chevron_right),
      onTap: () => setState(() => page = 2),
    ),
    const Divider(),
    SwitchListTile(
      key: const ValueKey('automatic-mode'),
      contentPadding: EdgeInsets.zero,
      title: const Text('실시간 자동 번역'),
      subtitle: const Text('끄면 떠 있는 버튼으로 번역합니다.'),
      value: activeAutomatic,
      onChanged: busy || running
          ? null
          : (value) => setState(() => automatic = value),
    ),
    const Divider(),
    ExpansionTile(
      tilePadding: EdgeInsets.zero,
      title: const Text('사용 방법'),
      children: [
        instruction(
          '01',
          '화면 공유 선택',
          activeAutomatic
              ? '공유 창에서 앱 하나를 선택하고 번역할 앱을 고르세요.'
              : '표시 권한을 허용한 뒤 전체 화면을 공유하세요.',
        ),
        instruction(
          '02',
          '원문 위치에 번역',
          activeAutomatic
              ? '선택한 앱을 전체 화면으로 열어 두세요. 알림에서 일시정지·재개할 수 있습니다.'
              : '다른 앱으로 이동해 번역 버튼을 누르세요. 버튼은 끌어서 옮길 수 있습니다.',
        ),
        instruction(
          '03',
          '영역과 원문 확인',
          '번역 메뉴에서 영역 선택·전체 번역 보기·인식한 원문 보기를 사용할 수 있습니다. 기본 영역은 화면 아래쪽입니다.',
        ),
      ],
    ),
  ];
  List<Widget> direct() => [
    Text(
      '문장을 직접 번역',
      style: Theme.of(context).textTheme.headlineSmall
          ?.copyWith(fontWeight: FontWeight.w700),
    ),
    const SizedBox(height: 8),
    const Text('일본어를 입력하거나 붙여 넣으세요.'),
    const SizedBox(height: 24),
    TextField(
      controller: input,
      maxLines: 6,
      minLines: 3,
      maxLength: 700,
      enabled: !busy && !running,
      onChanged: (_) => setState(() => directResult = ''),
      decoration: const InputDecoration(
        labelText: '일본어 원문',
        alignLabelWithHint: true,
      ),
    ),
    const SizedBox(height: 12),
    FilledButton.icon(
      key: const ValueKey('translate'),
      onPressed: busy || running || !hasModel || input.text.trim().isEmpty
          ? null
          : translate,
      icon: const Icon(Icons.translate),
      label: const Text('이 문장 번역'),
    ),
    if (running)
      const Padding(
        padding: EdgeInsets.only(top: 12),
        child: Text('직접 번역하려면 홈에서 화면 번역을 종료해 주세요.'),
      ),
    if (directResult.isNotEmpty)
      Container(
        margin: const EdgeInsets.only(top: 24),
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(16),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Semantics(
              liveRegion: true,
              child: SelectableText(
                directResult,
                style: const TextStyle(fontSize: 19, height: 1.6),
              ),
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: Text(
                    directCached
                        ? '이전 결과 재사용'
                        : '번역 ${(directMs / 1000).toStringAsFixed(2)}초 · 기기에서 처리',
                    style: const TextStyle(fontSize: 12),
                  ),
                ),
                IconButton(
                  tooltip: '번역 복사',
                  onPressed: () =>
                      Clipboard.setData(ClipboardData(text: directResult)),
                  icon: const Icon(Icons.copy_outlined),
                ),
              ],
            ),
          ],
        ),
      ),
  ];
  List<Widget> settings() => [
    Text(
      '번역 설정',
      style: Theme.of(context).textTheme.headlineSmall
          ?.copyWith(fontWeight: FontWeight.w700),
    ),
    const SizedBox(height: 24),
    const Text(
      '자동 번역 또는 버튼 번역',
      style: TextStyle(fontSize: 17, fontWeight: FontWeight.w600),
    ),
    const SizedBox(height: 8),
    const Text(
      '자동 모드는 앱 하나를 공유해 원문 위치에 번역하고, 테두리와 알림창에서 실행 상태를 확인합니다. 버튼 모드는 전체 화면을 공유하고 떠 있는 버튼을 눌러 번역합니다.',
    ),
    const SizedBox(height: 20),
    ListTile(
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.layers_outlined),
      title: const Text('다른 앱 위에 표시'),
      subtitle: Text(overlayAllowed ? '허용됨' : '화면 번역에 필요한 권한'),
      trailing: const Icon(Icons.chevron_right),
      onTap: busy || running
          ? null
          : () =>
                perform(() => control.invokeMethod<void>('overlayPermission')),
    ),
    const Divider(),
    ListTile(
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.offline_bolt_outlined),
      title: Text(selectedModel['name'] as String? ?? 'AIHub JA→KO INT8'),
      subtitle: Text(ready ? '오프라인 모델 준비 완료' : '번역을 시작하면 자동으로 준비합니다.'),
    ),
    if (!ready)
      OutlinedButton.icon(
        key: const ValueKey('load-model'),
        onPressed: busy || running || !hasModel ? null : () => perform(prepare),
        icon: const Icon(Icons.memory_outlined),
        label: const Text('모델 준비'),
      ),
    const SizedBox(height: 8),
    const Text(
      '기본 모델·일본어 사전·OCR은 설치 파일에 포함되어 있습니다. 모델 탭에서 호환 모델을 추가할 수 있습니다.',
      style: TextStyle(fontSize: 13, color: Color(0xff5d645e)),
    ),
    const SizedBox(height: 24),
    ListTile(
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.info_outline),
      title: const Text('모델·엔진 라이선스'),
      trailing: const Icon(Icons.chevron_right),
      onTap: busy ? null : licenses,
    ),
    const Divider(),
    const SizedBox(height: 12),
    const Text(
      '화면 캡처는 기기에서만 처리하며 파일로 저장하지 않습니다. 보호된 화면은 읽지 못할 수 있고, 세로쓰기·루비는 인식이 부정확할 수 있습니다.',
      style: TextStyle(fontSize: 13, color: Color(0xff5d645e)),
    ),
  ];
  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final progress = state['progress'] as num?;
    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'LingoLayer',
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            fontSize: 23,
            fontWeight: FontWeight.w800,
            letterSpacing: -.7,
          ),
        ),
        leading: const Padding(
          padding: EdgeInsets.only(left: 24),
          child: Icon(Icons.layers_rounded, size: 26),
        ),
        titleSpacing: 12,
        actions: [
          IconButton(
            tooltip: '설정',
            onPressed: () => setState(() => page = 3),
            icon: const Icon(Icons.tune_rounded),
          ),
          const SizedBox(width: 12),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: ListView(
                key: ValueKey(page),
                padding: const EdgeInsets.fromLTRB(24, 12, 24, 24),
                children: page == 0
                    ? home()
                    : page == 1
                    ? direct()
                    : page == 2
                    ? [
                        ModelLibrary(
                          state: state,
                          disabled: busy || running,
                          onAction: modelAction,
                        ),
                      ]
                    : settings(),
              ),
            ),
            if (busy || error != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 8, 24, 8),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (busy) ...[
                      LinearProgressIndicator(value: progress?.toDouble()),
                      const SizedBox(height: 8),
                      Text(
                        state['message'] as String? ?? '번역 준비 중…',
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                    if (downloading)
                      TextButton.icon(
                        key: const ValueKey('cancel-download'),
                        onPressed: () =>
                            control.invokeMethod<void>('cancelDownload'),
                        icon: const Icon(Icons.close),
                        label: const Text('다운로드 취소'),
                      ),
                    if (error != null)
                      Semantics(
                        liveRegion: true,
                        child: Text(
                          error!,
                          style: TextStyle(color: scheme.error),
                        ),
                      ),
                  ],
                ),
              ),
            if (page == 0)
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 8, 24, 16),
                child: SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    key: const ValueKey('capture'),
                    onPressed: running
                        ? () =>
                              perform(() => control.invokeMethod<void>('stop'))
                        : (busy || !hasModel ? null : start),
                    icon: Icon(
                      running ? Icons.stop_rounded : Icons.play_arrow_rounded,
                    ),
                    label: Padding(
                      padding: const EdgeInsets.symmetric(vertical: 14),
                      child: Text(
                        running
                            ? '화면 번역 종료'
                            : busy
                            ? '번역 준비 중…'
                            : '화면 번역 시작',
                      ),
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: page,
        onDestinationSelected: (value) => setState(() => page = value),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.home_outlined),
            selectedIcon: Icon(Icons.home),
            label: '홈',
          ),
          NavigationDestination(
            icon: Icon(Icons.translate_outlined),
            label: '직접 번역',
          ),
          NavigationDestination(icon: Icon(Icons.memory_outlined), label: '모델'),
          NavigationDestination(icon: Icon(Icons.tune_outlined), label: '설정'),
        ],
      ),
    );
  }
}
