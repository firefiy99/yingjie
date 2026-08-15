plugins {
    id("com.android.application") version "8.13.0" apply false
    id("com.chaquo.python") version "16.1.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}

// 构建产物重定向到 Termux 私有目录（共享存储不支持符号链接，venv 会失败）
subprojects {
    val buildDirOverride = System.getenv("YINGJIE_BUILD_DIR")
    if (buildDirOverride != null) {
        layout.buildDirectory.set(file(buildDirOverride + "/" + name))
    }
}
