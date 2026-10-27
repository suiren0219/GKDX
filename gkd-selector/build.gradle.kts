plugins {
    kotlin("multiplatform")
}

kotlin {
    // 仅 JVM 目标：commonMain 的 expect 与 jvmMain 的 actual 按原生 KMP 结构编译，
    // @OptionalExpectation 声明在 JVM 目标下无需 actual
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}
