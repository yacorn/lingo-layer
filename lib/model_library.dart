import 'package:flutter/material.dart';

String modelSize(num bytes) => '${(bytes / 1000000).toStringAsFixed(1)} MB';

class ModelLibrary extends StatefulWidget {
  const ModelLibrary({
    super.key,
    required this.state,
    required this.disabled,
    required this.onAction,
  });
  final Map<String, dynamic> state;
  final bool disabled;
  final Future<void> Function(String method, Map<String, dynamic> args)
  onAction;

  @override
  State<ModelLibrary> createState() => _ModelLibraryState();
}

class _ModelLibraryState extends State<ModelLibrary> {
  final address = TextEditingController();
  String? inspectedAddress;
  @override
  void dispose() {
    address.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final models = (widget.state['models'] as List? ?? []).cast<Map>();
    final candidate = widget.state['candidate'] as Map?;
    final candidateVisible =
        candidate != null && inspectedAddress == address.text.trim();
    final installed = models.any((m) => m['id'] == candidate?['id']);
    final scheme = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text('모델 라이브러리', style: Theme.of(context).textTheme.headlineMedium),
        const SizedBox(height: 8),
        const Text('설치한 모델로 기기에서 번역합니다.'),
        const SizedBox(height: 28),
        Text('설치된 모델', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        for (final model in models) ...[
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(
                      model['id'] == widget.state['selectedId']
                          ? Icons.check_circle
                          : Icons.memory_outlined,
                      color: scheme.primary,
                      size: 22,
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            model['name'] as String? ?? 'AIHub JA→KO INT8',
                            style: Theme.of(context).textTheme.titleSmall,
                          ),
                          const SizedBox(height: 5),
                          Text(
                            '일본어 → 한국어 · ${modelSize(model['bytes'] as num? ?? 533479816)}',
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                          Text(
                            model['bundled'] != false
                                ? '기본 제공 · 오프라인 사용'
                                : '다운로드 모델 · ${model['license']}',
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    OutlinedButton(
                      key: ValueKey('select-${model['id']}'),
                      onPressed:
                          widget.disabled ||
                              model['id'] == widget.state['loadedId']
                          ? null
                          : () => widget.onAction('load', {'id': model['id']}),
                      child: Text(
                        model['id'] == widget.state['loadedId']
                            ? '사용 중'
                            : '선택·준비',
                      ),
                    ),
                    if (model['bundled'] == false)
                      TextButton(
                        key: ValueKey('remove-${model['id']}'),
                        onPressed: widget.disabled
                            ? null
                            : () async {
                                final remove = await showDialog<bool>(
                                  context: context,
                                  builder: (context) => AlertDialog(
                                    title: const Text('모델을 삭제할까요?'),
                                    content: Text(
                                      '${model['name']} 파일을 삭제합니다. 다시 사용하려면 다운로드해야 합니다.',
                                    ),
                                    actions: [
                                      TextButton(
                                        onPressed: () =>
                                            Navigator.pop(context, false),
                                        child: const Text('취소'),
                                      ),
                                      TextButton(
                                        onPressed: () =>
                                            Navigator.pop(context, true),
                                        child: const Text('삭제'),
                                      ),
                                    ],
                                  ),
                                );
                                if (remove == true) {
                                  await widget.onAction('removeModel', {
                                    'id': model['id'],
                                  });
                                }
                              },
                        child: const Text('삭제'),
                      ),
                  ],
                ),
              ],
            ),
          ),
          const Divider(height: 1),
        ],
        const SizedBox(height: 28),
        Text('주소로 모델 추가', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        const Text('공개 GitHub·Hugging Face 저장소 주소를 입력하세요. 모델 정보만 먼저 확인합니다.'),
        const SizedBox(height: 16),
        TextField(
          key: const ValueKey('model-url'),
          controller: address,
          enabled: !widget.disabled,
          keyboardType: TextInputType.url,
          autocorrect: false,
          maxLength: 500,
          onChanged: (_) => setState(() {}),
          decoration: const InputDecoration(
            labelText: '저장소 주소',
            hintText: 'https://huggingface.co/작성자/모델',
            counterText: '',
          ),
        ),
        const SizedBox(height: 12),
        OutlinedButton.icon(
          key: const ValueKey('inspect-model'),
          onPressed: widget.disabled || address.text.trim().isEmpty
              ? null
              : () async {
                  setState(() => inspectedAddress = address.text.trim());
                  await widget.onAction('inspectModel', {
                    'url': address.text.trim(),
                  });
                },
          icon: const Icon(Icons.search),
          label: const Text('모델 확인'),
        ),
        TextButton(
          key: const ValueKey('example-model'),
          onPressed: widget.disabled
              ? null
              : () => setState(() {
                  address.text =
                      'https://huggingface.co/sappho192/aihub-ja-ko-translator';
                  inspectedAddress = null;
                }),
          child: const Text('기본 AIHub 저장소 주소 넣기'),
        ),
        AnimatedSize(
          duration: MediaQuery.disableAnimationsOf(context)
              ? Duration.zero
              : const Duration(milliseconds: 180),
          alignment: Alignment.topCenter,
          child: !candidateVisible
              ? const SizedBox.shrink()
              : Container(
                  margin: const EdgeInsets.only(top: 12),
                  padding: const EdgeInsets.all(20),
                  decoration: BoxDecoration(
                    color: scheme.primaryContainer,
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        candidate['name'] as String,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      const SizedBox(height: 8),
                      Text(
                        '일본어 → 한국어 · ${modelSize(candidate['bytes'] as num)}',
                      ),
                      const SizedBox(height: 8),
                      Text('라이선스: ${candidate['license']}'),
                      const SizedBox(height: 8),
                      const Text(
                        '형식 확인 완료. 실행 호환성은 모델 선택 시 검사합니다. 일본어 사전은 기본 제공 파일을 공유합니다.',
                      ),
                      const SizedBox(height: 16),
                      FilledButton.icon(
                        key: const ValueKey('install-model'),
                        onPressed: widget.disabled || installed
                            ? null
                            : () => widget.onAction('installModel', {
                                'id': candidate['id'],
                              }),
                        icon: Icon(
                          installed ? Icons.check : Icons.download_rounded,
                        ),
                        label: Text(
                          installed
                              ? '설치됨'
                              : '${modelSize(candidate['bytes'] as num)} 다운로드',
                        ),
                      ),
                    ],
                  ),
                ),
        ),
        const SizedBox(height: 20),
        const Text(
          '현재 실행 엔진은 AIHub 일본어→한국어 호환 모델팩을 지원합니다. 일반 GGUF·Safetensors·다른 ONNX 모델은 바로 실행할 수 없습니다. 다른 저장소에는 lingolayer-model.json이 필요합니다.',
          style: TextStyle(fontSize: 13, height: 1.6),
        ),
        const SizedBox(height: 8),
        const Text(
          '다운로드에만 인터넷을 사용합니다. 설치 후 번역은 오프라인으로 처리합니다. 비공개·승인 필요 모델과 이어받기는 지원하지 않습니다.',
          style: TextStyle(fontSize: 13, height: 1.6),
        ),
      ],
    );
  }
}
