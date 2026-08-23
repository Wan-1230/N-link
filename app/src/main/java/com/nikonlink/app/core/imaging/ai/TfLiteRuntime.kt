package com.nikonlink.app.core.imaging.ai

import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

/**
 * LiteRT 推理运行时（PRD-AI修图 8.2）
 *
 * 加速路径：GPU Delegate 优先 → CPU 多线程兜底（NNAPI 由 Interpreter Options 预留）。
 * 当前模型文件尚未发布（models.json 为占位清单），本类提供统一的加载/推理/回收骨架，
 * 模型就绪后由各能力引擎（AiEnhancer 等）直接复用。
 *
 * 日志来源: TfLiteRuntime 标签输出后端选择与加载耗时。
 */
class TfLiteRuntime private constructor(
    private val interpreter: Interpreter,
    val backend: String
) : Closeable {

    companion object {
        private const val TAG = "TfLiteRuntime"

        /**
         * 加载模型：先尝试 GPU，失败回退 CPU 多线程（PRD 8.2 逐级回退）。
         */
        fun load(modelFile: File, threads: Int = 4): TfLiteRuntime {
            val start = System.currentTimeMillis()
            // GPU 尝试（反射创建 delegate，避免无 GPU 支持设备上的硬依赖崩溃）
            runCatching {
                val delegateClass = Class.forName("org.tensorflow.lite.gpu.GPUDelegate")
                val delegate = delegateClass.newInstance() as org.tensorflow.lite.Delegate
                val opts = Interpreter.Options().addDelegate(delegate)
                val interpreter = Interpreter(modelFile, opts)
                Timber.tag(TAG).i(
                    "Loaded ${modelFile.name} on GPU in ${System.currentTimeMillis() - start}ms"
                )
                return TfLiteRuntime(interpreter, "GPU")
            }.onFailure { gpuErr ->
                Timber.tag(TAG).w("GPU delegate unavailable, fallback CPU: ${gpuErr.message}")
            }

            val opts = Interpreter.Options().setNumThreads(threads)
            val interpreter = Interpreter(modelFile, opts)
            Timber.tag(TAG).i(
                "Loaded ${modelFile.name} on CPU($threads) in ${System.currentTimeMillis() - start}ms"
            )
            return TfLiteRuntime(interpreter, "CPU")
        }
    }

    /** 输入张量形状（用于调用方组装 ByteBuffer） */
    fun inputShape(index: Int = 0): IntArray =
        interpreter.getInputTensor(index).shape()

    fun outputShape(index: Int = 0): IntArray =
        interpreter.getOutputTensor(index).shape()

    /** 单输入单输出推理 */
    fun run(input: ByteBuffer, output: ByteBuffer) {
        interpreter.run(input, output)
    }

    override fun close() {
        runCatching { interpreter.close() }
    }
}
