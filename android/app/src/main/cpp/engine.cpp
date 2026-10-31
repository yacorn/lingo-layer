#include <jni.h>
#include <mecab.h>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

static std::unique_ptr<MeCab::Tagger> tokenizer;
static std::unique_ptr<MeCab::Model> model;
static std::string bytes(JNIEnv * env, jbyteArray input) {
    std::string text(env->GetArrayLength(input), '\0');
    env->GetByteArrayRegion(input, 0, text.size(), reinterpret_cast<jbyte *>(text.data()));
    return text;
}
static void fail(JNIEnv * env, const char * error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error);
}
extern "C" JNIEXPORT void JNICALL Java_dev_personal_screen_1translator_NativeEngine_prepareTokenizer(JNIEnv * env, jobject, jbyteArray path) {
    try {
        tokenizer.reset(); model.reset();
        const auto directory = bytes(env, path);
        std::vector<std::string> options{"mecab", "-d", directory, "-r", directory + "/mecabrc"};
        std::vector<char *> argv;
        for (auto & option : options) argv.push_back(option.data());
        model.reset(MeCab::createModel(argv.size(), argv.data()));
        if (!model) throw std::runtime_error(MeCab::getLastError());
        tokenizer.reset(model->createTagger());
        if (!tokenizer) throw std::runtime_error(MeCab::getTaggerError());
    } catch (const std::exception & e) { tokenizer.reset(); model.reset(); fail(env, e.what()); }
}
extern "C" JNIEXPORT void JNICALL Java_dev_personal_screen_1translator_NativeEngine_unloadTokenizer(JNIEnv *, jobject) {
    tokenizer.reset(); model.reset();
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_dev_personal_screen_1translator_NativeEngine_segment(JNIEnv * env, jobject, jbyteArray input) {
    try {
        if (!tokenizer) throw std::runtime_error("Load a tokenizer first");
        const auto text = bytes(env, input);
        // Match Fugashi's null-terminated input; translation rejects embedded NUL.
        const auto nodes = tokenizer->parseToNode(text.c_str());
        if (!nodes) throw std::runtime_error(tokenizer->what());
        std::vector<std::string> words;
        for (auto node = nodes; node; node = node->next) {
            if (node->stat != MECAB_BOS_NODE && node->stat != MECAB_EOS_NODE)
                words.emplace_back(node->surface, node->length);
        }
        // Return UTF-8 bytes; JNI modified UTF-8 strings would corrupt supplementary characters.
        auto result = env->NewObjectArray(words.size(), env->FindClass("[B"), nullptr);
        for (size_t i = 0; i < words.size(); ++i) {
            auto word = env->NewByteArray(words[i].size());
            env->SetByteArrayRegion(word, 0, words[i].size(), reinterpret_cast<const jbyte *>(words[i].data()));
            env->SetObjectArrayElement(result, i, word);
            env->DeleteLocalRef(word);
        }
        return result;
    } catch (const std::exception & e) { fail(env, e.what()); return nullptr; }
}
